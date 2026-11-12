package id.phonelink.android.service

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import id.phonelink.android.core.ConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

/** Where photo sync stands for one Mac or phone: which photo was sent last, and how many so far. */
data class PhotoMark(val dateAdded: Long, val mediaId: Long)

/**
 * Sends new photos to the Macs and phones (photo backup, 1.6) that have photo sync on, whenever they're connected.
 * Each keeps its own position, so a phone and a Mac never share one. Only photos taken after sync was
 * turned on are sent, oldest first, and the last one sent is remembered so nothing goes twice.
 */
object PhotoSync {
    private const val PREFS = "photo_sync"
    const val KIND = "photo"
    /** At most this many photos per run, so a big burst doesn't block other transfers for long. */
    const val BATCH = 50

    private val _sent = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Photos sent per Mac since the app started, for the settings row. */
    val sent: StateFlow<Map<String, Int>> = _sent.asStateFlow()

    /** Set by the service, so turning sync on starts it right away. */
    @Volatile var trigger: (() -> Unit)? = null

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(context: Context, macId: String) = prefs(context).getBoolean("on_$macId", false)

    fun setOn(context: Context, macId: String, on: Boolean) {
        val edit = prefs(context).edit().putBoolean("on_$macId", on)
        // Starting fresh: photos already on the phone stay there, only new ones go.
        if (on && !prefs(context).contains("date_$macId")) {
            val now = System.currentTimeMillis() / 1000
            edit.putLong("date_$macId", now).putLong("id_$macId", Long.MAX_VALUE).putLong("start_$macId", now)
        }
        edit.apply()
        if (on) trigger?.invoke()
    }

    /** Only sends over Wi-Fi without a data limit, so a phone hotspot or mobile data isn't used up. */
    fun wifiOnly(context: Context, macId: String) = prefs(context).getBoolean("wifi_$macId", false)

    fun setWifiOnly(context: Context, macId: String, on: Boolean) {
        prefs(context).edit().putBoolean("wifi_$macId", on).apply()
        if (!on) trigger?.invoke()
    }

    /** Who gets photos now: Macs, and phones that keep a backup (1.6), each by its own switches. Pure, for the tests. */
    fun targets(
        peers: List<id.phonelink.android.core.Peer>,
        isOn: (String) -> Boolean,
        wifiOnly: (String) -> Boolean,
        chargingOnly: (String) -> Boolean,
        onWifi: Boolean,
        charging: Boolean,
    ): List<id.phonelink.android.core.Peer> = peers
        .filter { it.isPaired && it.isConnected && (it.isMac || it.canBackup) && isOn(it.id) }
        .filter { !wifiOnly(it.id) || onWifi }
        .filter { !chargingOnly(it.id) || charging }

    /** Only sends while this phone charges (new in 1.6, for both Macs and phones). */
    fun chargingOnly(context: Context, peerId: String) = prefs(context).getBoolean("charging_$peerId", false)

    fun setChargingOnly(context: Context, peerId: String, on: Boolean) {
        prefs(context).edit().putBoolean("charging_$peerId", on).apply()
        if (!on) trigger?.invoke()
    }

    fun isCharging(context: Context): Boolean {
        val battery = context.getSystemService(android.os.BatteryManager::class.java) ?: return false
        return battery.isCharging
    }

    private val _stopped = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Why photo backup to a phone stopped or waits, such as "turned off on B", until the next photo goes. */
    val stopped: StateFlow<Map<String, String>> = _stopped.asStateFlow()

    fun setStopped(peerId: String, why: String?) {
        _stopped.value = if (why == null) _stopped.value - peerId else _stopped.value + (peerId to why)
    }

    fun onFreeWifi(context: Context): Boolean {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    // Earlier photos: a second mark that walks from [days] ago up to when sync was turned on.

    private val _earlier = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Earlier photos sent per Mac in the current catch-up. A Mac is in the map while one runs. */
    val earlier: StateFlow<Map<String, Int>> = _earlier.asStateFlow()

    /** Starts sending photos from the last [days] days, or every photo when null. */
    fun sendEarlier(context: Context, macId: String, days: Int?) {
        val p = prefs(context)
        val now = System.currentTimeMillis() / 1000
        // Before this was saved, the start is unknown, so the catch-up runs to the current mark; the Mac skips copies.
        val until = p.getLong("start_$macId", p.getLong("date_$macId", now))
        val from = if (days == null) 0L else now - days * 86_400L
        p.edit().putLong("old_date_$macId", from - 1).putLong("old_id_$macId", Long.MAX_VALUE).putLong("old_until_$macId", until).apply()
        _earlier.value = _earlier.value + (macId to 0)
        trigger?.invoke()
    }

    fun stopEarlier(context: Context, macId: String) {
        prefs(context).edit().remove("old_date_$macId").remove("old_id_$macId").remove("old_until_$macId").apply()
        _earlier.value = _earlier.value - macId
    }

    fun isSendingEarlier(context: Context, macId: String) = prefs(context).contains("old_until_$macId")

    /** The catch-up's mark and its end, or null when none runs. */
    fun earlierMark(context: Context, macId: String): Pair<PhotoMark, Long>? {
        val p = prefs(context)
        if (!p.contains("old_until_$macId")) return null
        return PhotoMark(p.getLong("old_date_$macId", 0), p.getLong("old_id_$macId", Long.MAX_VALUE)) to p.getLong("old_until_$macId", 0)
    }

    fun saveEarlierMark(context: Context, macId: String, mark: PhotoMark) {
        prefs(context).edit().putLong("old_date_$macId", mark.dateAdded).putLong("old_id_$macId", mark.mediaId).apply()
        _earlier.value = _earlier.value + (macId to (_earlier.value[macId] ?: 0) + 1)
    }

    /** Earlier photos stop at the second sync was turned on; later ones go with the normal mark. */
    fun until(seconds: Long) = " AND ${MediaStore.Files.FileColumns.DATE_ADDED} <= $seconds"

    /** Videos go too, when turned on for this Mac. Off by default, because videos are large. */
    fun sendsVideos(context: Context, macId: String) = prefs(context).getBoolean("videos_$macId", false)

    fun setSendsVideos(context: Context, macId: String, on: Boolean) {
        prefs(context).edit().putBoolean("videos_$macId", on).apply()
        if (on) trigger?.invoke()
    }

    val videoPermission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun canReadVideos(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, videoPermission) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager())

    /** Photos only, or photos and videos, in MediaStore's shared files table, so both keep one order and one mark. */
    fun typeFilter(videos: Boolean): String {
        val image = MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
        val video = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        return " AND ${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" + (if (videos) "$image,$video" else "$image") + ")"
    }

    fun mark(context: Context, macId: String) =
        PhotoMark(prefs(context).getLong("date_$macId", System.currentTimeMillis() / 1000), prefs(context).getLong("id_$macId", Long.MAX_VALUE))

    fun saveMark(context: Context, macId: String, mark: PhotoMark) {
        prefs(context).edit().putLong("date_$macId", mark.dateAdded).putLong("id_$macId", mark.mediaId).apply()
        _sent.value = _sent.value + (macId to (_sent.value[macId] ?: 0) + 1)
    }

    /** The permission that lets the app read photos on this Android version. */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun canRead(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager())

    /** Photos after [mark]: a later second, or the same second with a larger ID. */
    fun selection(mark: PhotoMark): Pair<String, Array<String>> {
        val idLimit = if (mark.mediaId == Long.MAX_VALUE) "" else " OR (${MediaStore.Images.Media.DATE_ADDED} = ? AND ${MediaStore.Images.Media._ID} > ?)"
        val args = if (mark.mediaId == Long.MAX_VALUE) arrayOf(mark.dateAdded.toString())
        else arrayOf(mark.dateAdded.toString(), mark.dateAdded.toString(), mark.mediaId.toString())
        return "(${MediaStore.Images.Media.DATE_ADDED} > ?$idLimit)" to args
    }

    /** Mac folders sort by name, so a name without a date gets one from when the photo was added. */
    fun fileName(displayName: String?, mediaId: Long, mime: String?): String {
        if (!displayName.isNullOrBlank()) return displayName
        if (mime?.startsWith("video/") == true) {
            val video = when (mime) {
                "video/quicktime" -> "mov"
                "video/3gpp" -> "3gp"
                "video/webm" -> "webm"
                else -> "mp4"
            }
            return "VID_$mediaId.$video"
        }
        val ext = when (mime) {
            "image/png" -> "png"
            "image/heic", "image/heif" -> "heic"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        return "IMG_$mediaId.$ext"
    }
}

/** Watches the gallery and the connected Macs, and sends new photos. Runs inside the service. */
class PhotoSyncer(private val context: Context, private val connections: ConnectionManager, private val scope: CoroutineScope) {
    private val lock = Mutex()
    private var pending: Job? = null
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = schedule(PHOTO_SETTLE_MS)
    }

    fun start() {
        runCatching { context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer) }
        runCatching { context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer) }
        PhotoSync.trigger = { schedule(0) }
        startPower()
        // A Mac or phone coming back gets what was taken while it was away.
        scope.launch {
            connections.peers
                .map { peers -> peers.filter(::isTarget).filter { it.isConnected }.map { it.id }.toSet() }
                .distinctUntilChanged()
                .collect { schedule(CONNECT_SETTLE_MS) }
        }
    }

    /** Plugging in starts a run for targets that wait for charging. */
    private val power = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: android.content.Intent) = schedule(CONNECT_SETTLE_MS)
    }

    fun startPower() {
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(
                context, power, android.content.IntentFilter(android.content.Intent.ACTION_POWER_CONNECTED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    private fun isTarget(peer: id.phonelink.android.core.Peer) = peer.isPaired && (peer.isMac || peer.canBackup)

    fun stop() {
        runCatching { context.unregisterReceiver(power) }
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
        PhotoSync.trigger = null
    }

    /** Set while a run is sending; a change then only asks for another run after it, never cancels it. */
    private var running = false
    private var rerun = false

    /**
     * A camera writes a photo in steps, so wait for it to settle before reading. Only a run still waiting is
     * replaced: cancelling one mid-send would drop the transfer's result before the mark is saved, and the
     * photo would go again.
     */
    private fun schedule(delayMs: Long) = synchronized(this) {
        if (running) {
            rerun = true
            return@synchronized
        }
        pending?.cancel()
        pending = scope.launch(Dispatchers.IO) {
            delay(delayMs)
            synchronized(this@PhotoSyncer) {
                if (!isActive) return@launch
                running = true
                rerun = false
            }
            try {
                while (true) {
                    val more = runOnce()
                    val again = synchronized(this@PhotoSyncer) {
                        val changed = rerun
                        rerun = false
                        if (!changed && !more) running = false
                        changed to (changed || more)
                    }
                    if (!again.second) break
                    if (again.first) delay(PHOTO_SETTLE_MS)
                }
            } finally {
                synchronized(this@PhotoSyncer) { running = false }
            }
        }
    }

    /** Returns true when there are more photos to send right away. */
    private suspend fun runOnce(): Boolean = lock.withLock {
        if (!PhotoSync.canRead(context)) return@withLock false
        val targets = PhotoSync.targets(
            connections.peers.value,
            isOn = { PhotoSync.isOn(context, it) },
            wifiOnly = { PhotoSync.wifiOnly(context, it) },
            chargingOnly = { PhotoSync.chargingOnly(context, it) },
            onWifi = PhotoSync.onFreeWifi(context),
            charging = PhotoSync.isCharging(context),
        )
        var more = false
        for (target in targets) {
            phones = if (target.isPhone) phones + target.id else phones - target.id
            val newMore = sendNew(target.id)
            // New photos first, then the catch-up a batch at a time.
            more = newMore || (!newMore && PhotoSync.isOn(context, target.id) && sendEarlier(target.id)) || more
        }
        more
    }

    /** Targets that are phones: they get each photo's SHA-256 in the offer, so a copy they have is skipped. */
    @Volatile private var phones = emptySet<String>()

    private fun sha256(uri: Uri): String? = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        } ?: return null
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** A phone refused: full (backup stops, with a notification), or not allowed (waits, with why on its page). */
    private fun refused(peerId: String, outcome: id.phonelink.android.core.FileTransfers.Outcome) {
        val why = outcome.error ?: "Refused"
        PhotoSync.setStopped(peerId, why)
        if (outcome.reason == id.phonelink.android.core.PhoneKinds.LOW_SPACE) {
            PhotoSync.setOn(context, peerId, false)
            PhoneNotices.backupStopped(context, peerId, why)
        }
    }

    private suspend fun sendNew(macId: String): Boolean =
        send(macId, PhotoSync.mark(context, macId), "") { PhotoSync.saveMark(context, macId, it) }

    /** Returns true when the catch-up has more to send. */
    private suspend fun sendEarlier(macId: String): Boolean {
        val (mark, until) = PhotoSync.earlierMark(context, macId) ?: return false
        val more = send(macId, mark, PhotoSync.until(until)) { PhotoSync.saveEarlierMark(context, macId, it) }
        if (!more && lastRunComplete) PhotoSync.stopEarlier(context, macId)
        return more
    }

    /** Whether the last [send] got through every photo it found, without a failure. */
    private var lastRunComplete = true

    /** Returns true when there are more photos than one batch. */
    private suspend fun send(macId: String, start: PhotoMark, extra: String, saved: (PhotoMark) -> Unit): Boolean {
        lastRunComplete = false
        val (where, args) = PhotoSync.selection(start)
        val videos = PhotoSync.sendsVideos(context, macId) && PhotoSync.canReadVideos(context)
        val pendingFilter = if (Build.VERSION.SDK_INT >= 29) " AND ${MediaStore.MediaColumns.IS_PENDING} = 0" else ""
        val columns = arrayOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED, MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val order = "${MediaStore.Files.FileColumns.DATE_ADDED} ASC, ${MediaStore.Files.FileColumns._ID} ASC"
        val photos = mutableListOf<Photo>()
        runCatching {
            val table = MediaStore.Files.getContentUri("external")
            context.contentResolver.query(table, columns, where + extra + PhotoSync.typeFilter(videos) + pendingFilter, args, order)?.use { c ->
                while (c.moveToNext() && photos.size <= PhotoSync.BATCH) {
                    val id = c.getLong(0)
                    val isVideo = c.getInt(5) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                    photos += Photo(id, PhotoSync.fileName(c.getString(1), id, c.getString(4)), c.getLong(2), c.getLong(3), isVideo)
                }
            }
        }
        for (photo in photos.take(PhotoSync.BATCH)) {
            val base = if (photo.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = ContentUris.withAppendedId(base, photo.id)
            // The size in MediaStore can be stale right after saving, so read the real one.
            val size = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()
                ?.takeIf { it > 0 } ?: photo.size
            if (size <= 0) {
                saved(PhotoMark(photo.dateAdded, photo.id))
                continue
            }
            val toPhone = macId in phones
            val sha = if (toPhone) sha256(uri) else null
            var transfer = ""
            val ok = suspendCancellableCoroutine { done ->
                transfer = connections.fileTransfers.sendFile(macId, photo.name, size, PhotoSync.KIND, onFinished = { done.resume(it) }, sha256 = sha) {
                    context.contentResolver.openInputStream(uri) ?: error("The photo is gone")
                }
            }
            val outcome = connections.fileTransfers.outcome(transfer)
            // Stop at the first failure and try again next time, so the order stays and nothing is skipped.
            if (!ok) {
                if (toPhone && outcome?.reason != null) refused(macId, outcome)
                return false
            }
            if (toPhone) PhotoSync.setStopped(macId, null)
            saved(PhotoMark(photo.dateAdded, photo.id))
        }
        lastRunComplete = true
        return photos.size > PhotoSync.BATCH
    }

    private data class Photo(val id: Long, val name: String, val size: Long, val dateAdded: Long, val isVideo: Boolean = false)

    private companion object {
        const val PHOTO_SETTLE_MS = 4_000L
        const val CONNECT_SETTLE_MS = 3_000L
    }
}
