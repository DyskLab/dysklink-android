package id.phonelink.android.service

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.provider.Settings
import id.phonelink.android.MainActivity
import id.phonelink.android.R
import id.phonelink.android.core.BatteryAlert
import id.phonelink.android.core.LocationFix
import id.phonelink.android.core.NowPlaying
import id.phonelink.android.core.PeerAsk
import id.phonelink.android.core.PhoneFeature
import id.phonelink.android.core.PhoneRemote
import id.phonelink.android.core.QuietMath
import id.phonelink.android.core.QuietRequest
import id.phonelink.android.core.RemoteRefused
import id.phonelink.android.core.SafeLink
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * This phone (B) as another phone's remote: links shown with Open, the music, the ringer and Do Not Disturb, and the
 * location. The core already checked this phone's switch for the asking phone before any of these run.
 */
class PhoneRemoteImpl(context: Context) : PhoneRemote {
    private val context = context.applicationContext
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private val local: String get() = LocalDevice(context).name

    override fun offerLink(peerId: String, peerName: String, link: SafeLink.Link) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_LINKS, "Links from phones", NotificationManager.IMPORTANCE_HIGH))
        val uri = Uri.parse(link.uri)
        // Only ever opened by a tap. A number goes to the dialer, filled in; it never calls by itself.
        val open = when (link.kind) {
            SafeLink.Kind.Phone -> Intent(Intent.ACTION_DIAL, uri)
            SafeLink.Kind.Web -> Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
            SafeLink.Kind.Map -> Intent(Intent.ACTION_VIEW, uri)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val tag = "link:$peerId:${link.uri.hashCode()}"
        val tap = PendingIntent.getActivity(context, tag.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val what = when (link.kind) {
            SafeLink.Kind.Phone -> "Phone number: ${link.uri.removePrefix("tel:")}"
            SafeLink.Kind.Map -> "Place: ${link.uri}"
            SafeLink.Kind.Web -> link.uri
        }
        manager.notify(
            tag, NotificationIds.PHONE_LINK,
            Notification.Builder(context, CHANNEL_LINKS)
                .setSmallIcon(R.drawable.ic_logo_glyph)
                .setContentTitle("$peerName sent a link")
                .setContentText(what)
                .setStyle(Notification.BigTextStyle().bigText(what))
                .setContentIntent(tap)
                .setAutoCancel(true)
                .addAction(Notification.Action.Builder(null, if (link.kind == SafeLink.Kind.Phone) "Open dialer" else "Open", tap).build())
                .build(),
        )
    }

    // Music

    private fun controller(): MediaController? {
        if (!PhoneDataImpl(context).isGranted(PhoneFeature.Notifications)) return null
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
        val sessions = runCatching { manager.getActiveSessions(ComponentName(context, DyskNotificationListener::class.java)) }.getOrNull().orEmpty()
        return sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
    }

    override fun media(action: String): NowPlaying {
        val session = controller()
        val keys = DeviceActionsImpl(context)
        when (action) {
            "play_pause" -> if (session == null) keys.media("play_pause") else {
                if (session.playbackState?.state == PlaybackState.STATE_PLAYING) session.transportControls.pause() else session.transportControls.play()
            }
            "next" -> session?.transportControls?.skipToNext() ?: keys.media("next")
            "previous" -> session?.transportControls?.skipToPrevious() ?: keys.media("previous")
            // No volume panel pops up on this phone.
            "volume_up" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            "volume_down" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
        }
        // The player takes a moment to update its state after a button.
        if (action == "play_pause" || action == "next" || action == "previous") Thread.sleep(SETTLE_MS)
        return nowPlaying(controller() ?: session)
    }

    private fun nowPlaying(session: MediaController?): NowPlaying {
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (!PhoneDataImpl(context).isGranted(PhoneFeature.Notifications)) {
            return NowPlaying(volume = volume, maxVolume = max, note = "Turn on notification access for DyskLink on $local to see what plays.")
        }
        session ?: return NowPlaying(volume = volume, maxVolume = max, note = "Nothing is playing on $local.")
        val metadata = session.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        val art = (metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART))
            ?.let(::smallJpeg)
        return NowPlaying(
            title = title?.take(MAX_TEXT), artist = artist?.take(MAX_TEXT), app = appName(session.packageName),
            playing = session.playbackState?.state == PlaybackState.STATE_PLAYING, art = art, volume = volume, maxVolume = max,
        )
    }

    private fun appName(pkg: String): String = runCatching {
        val manager = context.packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun smallJpeg(bitmap: Bitmap): String? = runCatching {
        val scale = ART_PX.toFloat() / maxOf(bitmap.width, bitmap.height, 1)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true) else bitmap
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, out)
        java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }.getOrNull()

    // Quiet

    override fun quiet(peerId: String, peerName: String, request: QuietRequest): QuietRequest =
        QuietMode.apply(context, peerId, peerName, request)

    // Location

    /**
     * Answered only while DyskLink's service runs, with the location permission given while using the app. The
     * service adds the location type while some phone may ask (see [PhoneLinkService]); without it Android 10 and
     * newer give nothing in the background, and the answer says so.
     */
    override fun locate(): LocationFix {
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) throw RemoteRefused("Location permission is off for DyskLink on $local.")
        val manager = context.getSystemService(LocationManager::class.java) ?: throw RemoteRefused("No location on $local.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !manager.isLocationEnabled) throw RemoteRefused("Location is turned off on $local.")
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            if (fine) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }.filter { runCatching { it in manager.allProviders && manager.isProviderEnabled(it) }.getOrDefault(false) }
        val fix = providers.firstNotNullOfOrNull { current(manager, it) }
            ?: providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            ?: throw RemoteRefused(
                if (!PhoneLinkService.isUiVisible) "Location unavailable while DyskLink is in the background on $local. Open DyskLink there once, then try again."
                else "Location unavailable on $local right now.",
            )
        return LocationFix(fix.latitude, fix.longitude, fix.accuracy.takeIf { fix.hasAccuracy() }, fix.time, fix.provider)
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun current(manager: LocationManager, provider: String): Location? {
        val latch = CountDownLatch(1)
        var found: Location? = null
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val cancel = CancellationSignal()
                manager.getCurrentLocation(provider, cancel, context.mainExecutor) { location ->
                    found = location
                    latch.countDown()
                }
                if (!latch.await(FIX_WAIT_MS, TimeUnit.MILLISECONDS)) cancel.cancel()
            } else {
                val listener = LocationListener { location ->
                    found = location
                    latch.countDown()
                }
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                if (!latch.await(FIX_WAIT_MS, TimeUnit.MILLISECONDS)) manager.removeUpdates(listener)
            }
        }.isSuccess
        return if (ok) found else null
    }

    companion object {
        private const val CHANNEL_LINKS = "phone_links"
        private const val SETTLE_MS = 400L
        private const val MAX_TEXT = 200
        private const val ART_PX = 128
        private const val FIX_WAIT_MS = 12_000L
    }
}

/**
 * The ringer mode and Do Not Disturb as another phone set them, with Undo. What was on before the first change is
 * kept until Undo, the end time, or (with no end time) the notification being swiped away.
 */
object QuietMode {
    private const val PREFS = "quiet_mode"
    private const val CHANNEL = "phone_quiet"
    private const val KEY_RINGER = "ringer"
    private const val KEY_FILTER = "filter"
    private const val KEY_UNTIL = "until"

    fun hasAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun apply(context: Context, peerId: String, peerName: String, request: QuietRequest): QuietRequest {
        val local = LocalDevice(context).name
        val notifications = context.getSystemService(NotificationManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val needsAccess = request.mode == QuietMath.SILENT || request.mode == QuietMath.DND_ON || request.mode == QuietMath.DND_OFF
        if (needsAccess && !notifications.isNotificationPolicyAccessGranted) {
            throw RemoteRefused("Do Not Disturb access is off for DyskLink on $local. Turn on \"Let $peerName silence this phone\" there.")
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_RINGER)) {
            prefs.edit().putInt(KEY_RINGER, audio.ringerMode).putInt(KEY_FILTER, notifications.currentInterruptionFilter).apply()
        }
        try {
            when (request.mode) {
                QuietMath.SILENT -> audio.ringerMode = AudioManager.RINGER_MODE_SILENT
                QuietMath.VIBRATE -> audio.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                QuietMath.NORMAL -> audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
                QuietMath.DND_ON -> notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                QuietMath.DND_OFF -> notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        } catch (_: SecurityException) {
            // Leaving silent or Do Not Disturb for a ringer mode needs the access too.
            throw RemoteRefused("Android needs Do Not Disturb access for DyskLink on $local to do that.")
        }
        val until = request.until
        prefs.edit().putLong(KEY_UNTIL, until ?: 0L).apply()
        schedule(context, peerId, until)
        show(context, peerId, peerName, request.mode, until)
        return request
    }

    /** Back to what was on before, from Undo or the end time. */
    fun undo(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_RINGER)) {
            val notifications = context.getSystemService(NotificationManager::class.java)
            val audio = context.getSystemService(AudioManager::class.java)
            if (notifications.isNotificationPolicyAccessGranted) {
                runCatching { notifications.setInterruptionFilter(prefs.getInt(KEY_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL)) }
            }
            runCatching { audio.ringerMode = prefs.getInt(KEY_RINGER, AudioManager.RINGER_MODE_NORMAL) }
        }
        prefs.edit().clear().apply()
        schedule(context, "", null)
        context.getSystemService(NotificationManager::class.java).cancel(NotificationIds.PHONE_QUIET)
    }

    /** The notification was swiped away: with no end time pending, what was on before is forgotten. */
    fun forget(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_UNTIL, 0L) == 0L) prefs.edit().clear().apply()
    }

    private fun schedule(context: Context, peerId: String, until: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val expire = PeerActionReceiver.quietExpire(context, peerId)
        alarms.cancel(expire)
        if (until == null) return
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        runCatching {
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, expire)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, until, expire)
        }
    }

    private fun show(context: Context, peerId: String, peerName: String, mode: String, until: Long?) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sound changed by phones", NotificationManager.IMPORTANCE_DEFAULT))
        val title = when (mode) {
            QuietMath.DND_ON -> "$peerName turned on Do Not Disturb"
            QuietMath.DND_OFF -> "$peerName turned off Do Not Disturb"
            else -> "$peerName set this phone to ${QuietMath.label(mode)}"
        }
        val time = until?.let { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)) }
        val undo = PeerActionReceiver.quietUndo(context, peerId)
        manager.notify(
            NotificationIds.PHONE_QUIET,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_logo_glyph)
                .setContentTitle(title)
                .setContentText(if (time != null) "Until $time. Undo puts back what was on before." else "Undo puts back what was on before.")
                .setDeleteIntent(PeerActionReceiver.quietForget(context, peerId))
                .addAction(Notification.Action.Builder(null, "Undo", undo).build())
                .build(),
        )
    }
}

/** The first request of each kind from a phone asks here: Allow, Not now, Never. Then any missing Android access. */
object RemoteAsks {
    private const val CHANNEL_ASK = "phone_ask"
    private const val CHANNEL_ALERTS = "phone_battery"

    private fun nameOf(peerId: String): String =
        PhoneLinkService.connectionManager.value?.peers?.value?.firstOrNull { it.id == peerId }?.name ?: "Another phone"

    fun ask(context: Context, peerId: String, kind: PeerAsk) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ASK, "Requests from phones", NotificationManager.IMPORTANCE_HIGH))
        val name = nameOf(peerId)
        val (title, text) = when (kind) {
            PeerAsk.Links -> "Accept links from $name?" to "Links from $name show as a notification here. Nothing opens until you tap Open."
            PeerAsk.Media -> "Let $name control music here?" to "$name can play, pause, skip, and change the volume, and sees what plays."
            PeerAsk.Usage -> "Share screen time with $name?" to "$name sees how long this phone was used today, and in which apps."
            PeerAsk.Quiet -> "Let $name silence this phone?" to "$name can set Silent, Vibrate, Normal, or Do Not Disturb. Each time you get a notification with Undo."
            PeerAsk.Locate -> "Share location with $name?" to "$name can ask where this phone is. It gets the location only when it asks, while DyskLink runs."
            PeerAsk.Move -> "Accept a phone move from $name?" to "$name wants to move its contacts, photos, and files here. Photos go to Pictures › DyskLink, files to Download › DyskLink."
            PeerAsk.Sms -> "Let $name read and send SMS?" to "Warning: $name could read every SMS on this phone, including one-time codes, and send SMS from this phone's number. You get a notification each time it sends one. Only allow a device you own."
            PeerAsk.Backup -> "Accept photo backup from $name?" to "$name sends its new photos here, to Pictures › DyskLink › $name. It stops when less than 1 GB is free."
        }
        manager.notify(
            PeerActionReceiver.askTag(peerId, kind), NotificationIds.PHONE_ASK,
            Notification.Builder(context, CHANNEL_ASK)
                .setSmallIcon(R.drawable.ic_logo_glyph)
                .setContentTitle(title)
                .setContentText("$text You can change it on the page for $name.")
                .setStyle(Notification.BigTextStyle())
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .addAction(Notification.Action.Builder(null, "Allow", PeerActionReceiver.answer(context, peerId, kind, PeerActionReceiver.ALLOW)).build())
                .addAction(Notification.Action.Builder(null, "Not now", PeerActionReceiver.answer(context, peerId, kind, PeerActionReceiver.NOT_NOW)).build())
                .addAction(Notification.Action.Builder(null, "Never", PeerActionReceiver.answer(context, peerId, kind, PeerActionReceiver.NEVER)).build())
                .build(),
        )
    }

    /** Whether Android access that [kind] needs on this phone is still missing. */
    fun missing(context: Context, kind: PeerAsk): Boolean = when (kind) {
        PeerAsk.Links -> false
        PeerAsk.Media -> !PhoneDataImpl(context).isGranted(PhoneFeature.Notifications)
        PeerAsk.Usage -> !PhoneApps.hasUsageAccess(context)
        PeerAsk.Quiet -> !QuietMode.hasAccess(context)
        PeerAsk.Locate -> !hasLocation(context)
        PeerAsk.Sms -> !PhoneDataImpl(context).isGranted(PhoneFeature.Sms)
        PeerAsk.Move, PeerAsk.Backup -> false
    }

    fun hasLocation(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The Settings page for that access. Location is a runtime permission, asked on the phone's page instead. */
    fun settingsIntent(context: Context, kind: PeerAsk): Intent? = when (kind) {
        PeerAsk.Media -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        PeerAsk.Usage -> PhoneApps.usageAccessIntent(context)
        PeerAsk.Quiet -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        else -> null
    }

    /** Allowed from the notification, but Android access is missing: one more tap opens where it's given. */
    fun needsSetup(context: Context, peerId: String, kind: PeerAsk) {
        if (!missing(context, kind)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val target = settingsIntent(context, kind) ?: Intent(context, MainActivity::class.java)
        val tap = PendingIntent.getActivity(
            context, "setup:${kind.name}".hashCode(), target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val what = when (kind) {
            PeerAsk.Media -> "notification access"
            PeerAsk.Usage -> "Usage access"
            PeerAsk.Quiet -> "Do Not Disturb access"
            PeerAsk.Locate -> "the location permission"
            PeerAsk.Sms -> "the SMS permission"
            PeerAsk.Links, PeerAsk.Move, PeerAsk.Backup -> return
        }
        manager.notify(
            "setup:$peerId:${kind.name}", NotificationIds.PHONE_SETUP,
            Notification.Builder(context, CHANNEL_ASK)
                .setSmallIcon(R.drawable.ic_logo_glyph)
                .setContentTitle("One more step for ${nameOf(peerId)}")
                .setContentText("Tap to give DyskLink $what.")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** A battery alert about another phone, by this phone's settings for it. */
    fun battery(context: Context, peerId: String, alert: BatteryAlert) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Battery of other phones", NotificationManager.IMPORTANCE_DEFAULT))
        val peer = PhoneLinkService.connectionManager.value?.peers?.value?.firstOrNull { it.id == peerId }
        val name = peer?.name ?: "Another phone"
        val percent = peer?.info?.batteryPercent
        val (title, text) = when (alert) {
            BatteryAlert.Low -> "$name battery is low${percent?.let { ": $it %" }.orEmpty()}" to "It should be charged soon."
            BatteryAlert.Full -> "$name is fully charged" to "It can be unplugged."
        }
        manager.notify(
            "battery:$peerId", NotificationIds.PHONE_BATTERY,
            Notification.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_logo_glyph)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
}
