package id.phonelink.android.core

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** Shareable phone data. Everything is off until the user turns it on from the phone. */
enum class PhoneFeature(val id: String) {
    Notifications("notifications"),
    Sms("sms"),
    Calls("calls"),
    Contacts("contacts"),
    Files("files"),
    Screen("screen"),
    Control("control"),
    ScreenTime("screentime"),
    Gallery("gallery"),
}

/** A gallery file ready to send. */
class GalleryFile(val name: String, val size: Long, val open: () -> java.io.InputStream)

/** Access to phone data. Separate from [PhoneBridge] so its access rules can be tested without Android. */
interface PhoneData {
    val storageRoot: File
    fun isGranted(feature: PhoneFeature): Boolean
    fun activeNotifications(): List<NotificationItem>
    fun dismissNotification(key: String)
    /** Answers a notification through its reply action. Returns why it failed, or null when it was sent. */
    fun replyNotification(key: String, text: String): String? = "This notification can't be answered"
    /** Presses a notification's "mark as read" action. False when it has none. */
    fun markNotificationRead(key: String): Boolean = false
    /** Newest messages first, only those with an ID above [afterId]. */
    fun readSms(limit: Int, afterId: Long = 0): List<SmsItem>
    /** One thread's messages, newest first. */
    fun readSmsThread(threadId: Long, limit: Int): List<SmsItem> = readSms(SMS_SCAN).filter { it.threadId == threadId }.take(limit)
    /** Free space where received files go, in bytes. */
    fun freeBytes(): Long = runCatching { storageRoot.usableSpace }.getOrDefault(Long.MAX_VALUE)
    /** [done] receives a failure message, or null if the carrier accepted the SMS. */
    fun sendSms(address: String, body: String, done: (String?) -> Unit)
    fun readContacts(): List<ContactItem>
    /** Only shows the request. Recording starts after the user approves the Android dialog. */
    fun requestScreen(peerId: String)
    fun syncScreen(peerId: String)
    fun stopScreen(peerId: String)
    fun control(event: ControlEvent)
    /** Today's screen time, or null when it can't be read. */
    fun screenTime(): UsageReport? = null
    /** Photos and videos, newest first, with thumbnails. */
    fun gallery(offset: Int, limit: Int): List<GalleryItem> = emptyList()
    fun galleryFile(id: Long): GalleryFile? = null

    companion object {
        /** How far back [readSmsThread] looks without a thread query. */
        const val SMS_SCAN = 1_000
    }
}

/**
 * The only way phone data leaves the device. Each request is rechecked against the user's switches
 * and Android permissions, so even a paired Mac only gets what the user turned on.
 * [handle] must only be called for paired devices.
 */
class PhoneBridge(
    directory: File,
    private val scope: CoroutineScope,
    private val data: PhoneData,
    private val files: FileTransfers,
    private val pairedPeers: () -> List<String>,
    /** The computers that get new SMS as they arrive: Macs, and PCs that read SMS. */
    private val smsPeers: () -> List<String> = pairedPeers,
    /** Forwards to other phones, by their own switches. Null when there are none (tests, the desktop). */
    private val relay: NotificationRelay? = null,
    /** New SMS for the phones that read this phone's SMS, by their own switch. */
    private val smsShare: SmsShare? = null,
    private val send: (peerId: String, envelope: Envelope) -> Unit,
) {
    private val settingsFile = File(directory, "features.json")
    private val browser by lazy { FsBrowser(data.storageRoot) }

    private val _enabled = MutableStateFlow(loadEnabled())
    val enabled: StateFlow<Set<PhoneFeature>> = _enabled.asStateFlow()

    /** Features the user turned on whose Android permission is granted. */
    private val _active = MutableStateFlow(computeActive())
    val active: StateFlow<Set<PhoneFeature>> = _active.asStateFlow()

    private val calls = CallTracker()
    @Volatile private var lastSmsId = 0L

    fun setEnabled(feature: PhoneFeature, on: Boolean) {
        _enabled.value = if (on) _enabled.value + feature else _enabled.value - feature
        val ids = _enabled.value.map { it.id }
        settingsFile.writeText(protocolJson.encodeToString(ListSerializer(String.serializer()), ids))
        refresh()
    }

    /** Called when permissions may have changed, such as after returning from Settings. */
    fun refresh() {
        val previous = _active.value
        val current = computeActive()
        if (current == previous) return
        _active.value = current
        pairedPeers().forEach { peerId ->
            sendFeatures(peerId)
            if (PhoneFeature.Notifications in current - previous) sendActiveNotifications(peerId)
        }
    }

    fun peerReady(peerId: String) {
        sendFeatures(peerId)
        sendActiveNotifications(peerId)
    }

    fun handle(peerId: String, envelope: Envelope) {
        // Control skips coroutines because the order of gestures and typing must be kept.
        if (envelope.type == MessageType.CONTROL) {
            val event = envelope.control?.let(ControlRules::sanitize) ?: return
            if (isActive(PhoneFeature.Control)) runCatching { data.control(event) }
            return
        }
        // Reading contacts or large folders can be slow, so it must not hold up the connection reader.
        scope.launch(Dispatchers.IO) { runCatching { process(peerId, envelope) } }
    }

    private fun process(peerId: String, envelope: Envelope) {
        when (envelope.type) {
            MessageType.NOTIFICATION_DISMISS -> if (isActive(PhoneFeature.Notifications)) {
                envelope.key?.let(data::dismissNotification)
            }
            MessageType.SMS_LIST -> if (isActive(PhoneFeature.Sms)) {
                val messages = data.readSms(SMS_HISTORY)
                messages.maxOfOrNull { it.id }?.let { lastSmsId = maxOf(lastSmsId, it) }
                sendPages(messages, SmsItem.serializer()) { page, more ->
                    send(peerId, Envelope(MessageType.SMS_MESSAGES, sms = page, more = more))
                }
            }
            MessageType.SMS_SEND -> sendSms(peerId, envelope.smsSend ?: return)
            MessageType.CONTACTS_LIST -> if (isActive(PhoneFeature.Contacts)) {
                sendPages(data.readContacts(), ContactItem.serializer()) { page, more ->
                    send(peerId, Envelope(MessageType.CONTACTS, contacts = page, more = more))
                }
            }
            MessageType.USAGE_LIST -> if (isActive(PhoneFeature.ScreenTime)) {
                data.screenTime()?.let { send(peerId, Envelope(MessageType.USAGE, usage = it)) }
            }
            MessageType.GALLERY_LIST -> listGallery(peerId, (envelope.offset ?: 0).coerceAtLeast(0))
            MessageType.GALLERY_GET -> if (isActive(PhoneFeature.Gallery)) {
                val file = envelope.galleryId?.let(data::galleryFile)
                if (file == null) {
                    send(peerId, Envelope(MessageType.GALLERY, gallery = emptyList(), error = "That photo is gone"))
                } else {
                    files.sendFile(peerId, file.name, file.size, GALLERY_KIND) { file.open() }
                }
            }
            MessageType.FS_LIST -> listFiles(peerId, envelope.path.orEmpty(), envelope.hidden == true)
            MessageType.FS_GET -> getFile(peerId, envelope.path.orEmpty())
            MessageType.SCREEN_REQUEST -> if (isActive(PhoneFeature.Screen)) data.requestScreen(peerId)
            // These two messages only apply to the sending Mac's own session and never start recording.
            MessageType.SCREEN_SYNC -> data.syncScreen(peerId)
            MessageType.SCREEN_STOP -> data.stopScreen(peerId)
        }
    }

    private fun sendSms(peerId: String, request: SmsSend) {
        fun reply(error: String?) {
            send(peerId, Envelope(MessageType.SMS_RESULT, smsResult = SmsResult(request.id, error == null, error)))
        }
        when {
            !isActive(PhoneFeature.Sms) -> reply("SMS is turned off on the phone")
            request.address.isBlank() || request.address.length > MAX_ADDRESS -> reply("Invalid recipient number")
            request.body.isBlank() || request.body.length > MAX_SMS_CHARS -> reply("Invalid message text")
            else -> runCatching { data.sendSms(request.address, request.body, ::reply) }
                .onFailure { reply(it.message ?: "Failed to send SMS") }
        }
    }

    private fun listFiles(peerId: String, path: String, showHidden: Boolean) {
        fun reply(entries: List<FsEntry>, more: Boolean, error: String?) = send(
            peerId,
            Envelope(MessageType.FS_LISTING, path = path, entries = entries, more = more, error = error),
        )
        if (!isActive(PhoneFeature.Files)) return reply(emptyList(), false, "File access is turned off on the phone")
        val entries = runCatching { browser.list(path, showHidden) }.getOrElse {
            return reply(emptyList(), false, it.message ?: "Can't open the folder")
        }
        sendPages(entries, FsEntry.serializer()) { page, more -> reply(page, more, null) }
    }

    private fun listGallery(peerId: String, offset: Int) {
        if (!isActive(PhoneFeature.Gallery)) {
            return send(peerId, Envelope(MessageType.GALLERY, gallery = emptyList(), offset = offset, galleryEnd = true, error = "Gallery is turned off on the phone"))
        }
        val items = runCatching { data.gallery(offset, GALLERY_PAGE) }.getOrDefault(emptyList())
        val end = items.size < GALLERY_PAGE
        if (items.isEmpty()) return send(peerId, Envelope(MessageType.GALLERY, gallery = emptyList(), offset = offset, galleryEnd = true))
        sendPages(items, GalleryItem.serializer()) { page, more ->
            send(peerId, Envelope(MessageType.GALLERY, gallery = page, offset = offset, more = more, galleryEnd = end && !more))
        }
    }

    private fun getFile(peerId: String, path: String) {
        fun fail(error: String?) = send(
            peerId,
            Envelope(MessageType.FS_LISTING, path = path, entries = emptyList(), error = error ?: "Can't open the file"),
        )
        if (!isActive(PhoneFeature.Files)) return fail("File access is turned off on the phone")
        val file = runCatching { browser.file(path) }.getOrElse { return fail(it.message) }
        files.sendFile(peerId, file.name, file.length()) { file.inputStream() }
    }

    // Data pushed by the phone

    fun notificationPosted(item: NotificationItem) {
        relay?.posted(item)
        if (!isActive(PhoneFeature.Notifications)) return
        pairedPeers().forEach { send(it, Envelope(MessageType.NOTIFICATION_POSTED, notification = item)) }
    }

    fun notificationRemoved(key: String) {
        relay?.removed(key)
        if (!isActive(PhoneFeature.Notifications)) return
        pairedPeers().forEach { send(it, Envelope(MessageType.NOTIFICATION_REMOVED, key = key)) }
    }

    /** Marks existing messages so only new ones are sent once watching starts. */
    fun markSmsSeen() = onIo {
        if (isActive(PhoneFeature.Sms)) {
            data.readSms(1).firstOrNull()?.let { lastSmsId = maxOf(lastSmsId, it.id) }
        }
    }

    fun smsChanged() = onIo {
        smsShare?.changed()
        if (!isActive(PhoneFeature.Sms)) return@onIo
        val fresh = data.readSms(SMS_PUSH_LIMIT, afterId = lastSmsId)
        val newest = fresh.maxOfOrNull { it.id } ?: return@onIo
        lastSmsId = newest
        smsPeers().forEach { send(it, Envelope(MessageType.SMS_NEW, sms = fresh)) }
    }

    fun callStateChanged(state: String, number: String?) {
        val toMacs = isActive(PhoneFeature.Calls)
        // Phones go by their own switch, but still need Android's phone permission.
        val toPhones = relay?.wantsCalls() == true && runCatching { data.isGranted(PhoneFeature.Calls) }.getOrDefault(false)
        if (!toMacs && !toPhones) return
        val event = calls.update(state, number, System.currentTimeMillis()) ?: return
        if (toMacs) pairedPeers().forEach { send(it, Envelope(MessageType.CALL, call = event)) }
        if (toPhones) relay?.call(event)
    }

    private fun sendFeatures(peerId: String) {
        send(peerId, Envelope(MessageType.FEATURES, features = _active.value.map { it.id }))
    }

    private fun sendActiveNotifications(peerId: String) = onIo {
        if (!isActive(PhoneFeature.Notifications)) return@onIo
        data.activeNotifications().forEach {
            send(peerId, Envelope(MessageType.NOTIFICATION_POSTED, notification = it.copy(silent = true)))
        }
    }

    private fun isActive(feature: PhoneFeature): Boolean {
        // Permissions can be revoked from Settings at any time, so they are rechecked every time.
        val on = feature in _enabled.value && data.isGranted(feature)
        if (on != feature in _active.value) refresh()
        return on
    }

    private fun computeActive(): Set<PhoneFeature> =
        _enabled.value.filter { runCatching { data.isGranted(it) }.getOrDefault(false) }.toSet()

    private fun loadEnabled(): Set<PhoneFeature> {
        val ids = runCatching {
            protocolJson.decodeFromString(ListSerializer(String.serializer()), settingsFile.readText())
        }.getOrDefault(emptyList())
        return PhoneFeature.entries.filter { it.id in ids }.toSet()
    }

    private fun onIo(block: () -> Unit) {
        scope.launch(Dispatchers.IO) { runCatching(block) }
    }

    companion object {
        private const val SMS_HISTORY = 300
        private const val SMS_PUSH_LIMIT = 20
        private const val MAX_ADDRESS = 64
        private const val MAX_SMS_CHARS = 2_000
        /** Well under the 1 MiB frame limit, because other message fields share the same frame. */
        internal const val PAGE_BYTES = 512 * 1024
        /** Photos per request; their thumbnails are split over frames by [sendPages]. */
        const val GALLERY_PAGE = 60
        const val GALLERY_KIND = "gallery"

        private val types = setOf(
            MessageType.NOTIFICATION_DISMISS, MessageType.SMS_LIST, MessageType.SMS_SEND,
            MessageType.CONTACTS_LIST, MessageType.FS_LIST, MessageType.FS_GET, MessageType.USAGE_LIST,
            MessageType.GALLERY_LIST, MessageType.GALLERY_GET,
            MessageType.SCREEN_REQUEST, MessageType.SCREEN_SYNC, MessageType.SCREEN_STOP, MessageType.CONTROL,
        )

        fun handles(type: String) = type in types

        /** Splits long lists so each frame stays under the size limit. */
        internal fun <T> sendPages(items: List<T>, serializer: KSerializer<T>, emit: (List<T>, Boolean) -> Unit) {
            val pages = ArrayList<List<T>>()
            var page = ArrayList<T>()
            var bytes = 0
            for (item in items) {
                val size = protocolJson.encodeToString(serializer, item).encodeToByteArray().size + 1
                if (bytes + size > PAGE_BYTES && page.isNotEmpty()) {
                    pages.add(page)
                    page = ArrayList()
                    bytes = 0
                }
                page.add(item)
                bytes += size
            }
            pages.add(page)
            pages.forEachIndexed { index, content -> emit(content, index < pages.lastIndex) }
        }
    }
}

/** Browses phone storage. Paths from the Mac can never leave [root]. */
class FsBrowser(root: File) {
    private val root: File = root.canonicalFile

    fun list(path: String, showHidden: Boolean = false): List<FsEntry> {
        val folder = resolve(path)
        if (!folder.isDirectory) throw IOException("Not a folder")
        val children = folder.listFiles() ?: throw IOException("Can't open the folder")
        return children
            .filter { (showHidden || !it.name.startsWith(".")) && isInside(it.canonicalFile) }
            .map { FsEntry(it.name, it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified()) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun file(path: String): File {
        val file = resolve(path)
        if (!file.isFile) throw IOException("Not a file")
        return file
    }

    fun folder(path: String): File {
        val folder = resolve(path)
        if (!folder.isDirectory) throw IOException("Not a folder")
        return folder
    }

    /** A new file for an upload in [folderPath]. Never replaces a file: a taken name gets " (2)" and so on. */
    fun newFile(folderPath: String, name: String): File {
        val folder = folder(folderPath)
        val clean = name.replace(Regex("[\\u0000-\\u001f/\\\\:]"), "_").trim().trimStart('.').take(180)
        if (clean.isEmpty()) throw IOException("Bad name")
        val stem = clean.substringBeforeLast('.', clean)
        val ext = clean.substringAfterLast('.', "").let { if (it.isEmpty() || it == clean) "" else ".$it" }
        var candidate = File(folder, clean)
        var n = 2
        while (candidate.exists() || File(candidate.path + ".part").exists()) candidate = File(folder, "$stem ($n)$ext").also { n++ }
        if (!isInside(candidate.canonicalFile)) throw IOException("Location not allowed")
        return candidate
    }

    private fun resolve(path: String): File {
        if ('\u0000' in path) throw IOException("Location not allowed")
        // canonicalFile also follows symlinks, so links pointing outside root are refused too.
        val target = File(root, path).canonicalFile
        if (!isInside(target)) throw IOException("Location not allowed")
        return target
    }

    private fun isInside(file: File) = file == root || file.path.startsWith(root.path + File.separator)
}

/** Turns Android telephony states into events that mean something to the user. */
class CallTracker {
    private var state = IDLE
    private var ringing = false
    private var number: String? = null

    /** [newState]: `IDLE`, `RINGING`, or `OFFHOOK`. Returns null if nothing is new. */
    fun update(newState: String, newNumber: String?, now: Long): CallEvent? {
        val known = newNumber?.takeIf { it.isNotBlank() }
        if (newState == state) {
            // Android sends a second broadcast carrying the number when the call log permission is granted.
            if (known == null || known == number || state == IDLE) return null
            number = known
            return CallEvent(label(newState), known, now)
        }
        val label = when (newState) {
            RINGING -> "ringing"
            OFFHOOK -> if (ringing) "answered" else "outgoing"
            IDLE -> if (ringing && state == RINGING) "missed" else "ended"
            else -> return null
        }
        if (known != null) number = known
        val event = CallEvent(label, number, now)
        state = newState
        when (newState) {
            RINGING -> ringing = true
            IDLE -> {
                ringing = false
                number = null
            }
        }
        return event
    }

    private fun label(current: String) = when (current) {
        RINGING -> "ringing"
        else -> if (ringing) "answered" else "outgoing"
    }

    private companion object {
        const val IDLE = "IDLE"
        const val RINGING = "RINGING"
        const val OFFHOOK = "OFFHOOK"
    }
}
