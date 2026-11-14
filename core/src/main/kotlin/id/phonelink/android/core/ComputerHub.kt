package id.phonelink.android.core

import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** A phone, or another computer, as a Windows or Linux computer sees it. */
/** A phone that is an extra display of this PC: [width] × [height] once [active], the size asked for until then. */
data class HubExtend(val phoneId: String, val width: Int, val height: Int, val active: Boolean)

data class HubPhone(
    val id: String,
    val name: String,
    val model: String,
    val isPaired: Boolean,
    val isConnected: Boolean,
    val info: DeviceInfo? = null,
    val features: List<String> = emptyList(),
    val platform: String = "android",
    /** What its hello listed in `caps`, while connected. */
    val caps: List<String> = emptyList(),
    /** Connected through a USB cable (`adb reverse`, so it came from this computer's loopback address) instead of Wi-Fi. */
    val viaUsb: Boolean = false,
) {
    /** A Mac, or another Windows or Linux PC. They share the clipboard, files, and ringing, and nothing more. */
    val isComputer: Boolean get() = platform in PhoneLinkProtocol.COMPUTERS
    /** A phone whose DyskLink serves its screen to PCs ([PhoneLinkProtocol.CAP_PC_SCREEN]). Older phones need an update. */
    val servesScreen: Boolean get() = !isComputer && PhoneLinkProtocol.CAP_PC_SCREEN in caps
    val servesCamera: Boolean get() = !isComputer && PhoneLinkProtocol.CAP_PC_CAMERA in caps
    val servesGallery: Boolean get() = !isComputer && PhoneLinkProtocol.CAP_PC_GALLERY in caps
    val servesSms: Boolean get() = !isComputer && PhoneLinkProtocol.CAP_PC_SMS in caps
    val servesContacts: Boolean get() = !isComputer && PhoneLinkProtocol.CAP_PC_CONTACTS in caps
    val servesFiles: Boolean get() = !isComputer && PhoneLinkProtocol.CAP_PC_FILES in caps
    /** A phone that takes several fingers at once from a PC, for game keys ([PhoneLinkProtocol.CAP_PC_GAMEKEYS]). */
    val servesGameKeys: Boolean get() = !isComputer && PhoneLinkProtocol.CAP_PC_GAMEKEYS in caps
}

enum class VideoKind { Screen, Camera }

/**
 * The phone's screen or camera on this PC. Asked for: [waiting] until the phone agrees (Android's dialog for the
 * screen, a tap on the notification for the camera). Then [frame] holds the newest JPEG and [frames] counts them.
 * Ended with [error] when the phone refused or stopped with a reason; ended without one, it's gone from the map.
 */
data class PhoneVideo(
    val kind: VideoKind,
    val waiting: Boolean = true,
    val live: Boolean = false,
    val width: Int = 0,
    val height: Int = 0,
    /** Camera: clockwise degrees that turn the picture upright. */
    val rotation: Int = 0,
    /** Camera: `front` or `back`. */
    val lens: String? = null,
    val torch: Boolean = false,
    val hasFlash: Boolean = false,
    val error: String? = null,
    val frame: ByteArray? = null,
    val frames: Long = 0,
)

/** The phone's photos and videos read so far, newest first. */
data class PhoneGallery(
    val items: List<GalleryItem> = emptyList(),
    val loading: Boolean = false,
    val end: Boolean = false,
    val error: String? = null,
)

/** The phone's contacts, read whole: [loading] while pages still come. */
data class PhoneContacts(val contacts: List<ContactItem> = emptyList(), val loading: Boolean = false, val loaded: Boolean = false)

/**
 * A folder of the phone's storage as the PC browses it: [folder] is the path under internal storage ("" is the top),
 * [entries] folders first. [error] is why the folder or a download couldn't be opened.
 */
data class PhoneFolder(
    val folder: String = "",
    val entries: List<FsEntry> = emptyList(),
    val loading: Boolean = false,
    val showHidden: Boolean = false,
    val error: String? = null,
)

/** An SMS sent from this PC: [ok] null while the carrier hasn't answered. */
data class SmsOutgoing(val id: String, val address: String, val body: String, val time: Long, val ok: Boolean? = null, val error: String? = null)

/** The phone's SMS read so far, newest first, and what was sent from here. */
data class PhoneSms(
    val messages: List<SmsItem> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val sent: List<SmsOutgoing> = emptyList(),
)

/**
 * Pairing in progress. Usually this computer shows [code] and the user types it on the phone. When this computer
 * asked another computer, [typing] is true: the code shows over there and is typed here.
 */
data class HubPairing(
    val phoneId: String,
    val phoneName: String,
    val code: String,
    val attempts: Int = 0,
    val error: String? = null,
    val typing: Boolean = false,
    /** Typing here: the code was sent and the answer is awaited. Only then does the other side's accept count. */
    val localAccepted: Boolean = false,
    /** The other device has an older DyskLink that can't pair with CPace. [error] says to update it. */
    val outdated: Boolean = false,
)

/** What the computer itself does with what phones send. Called on background threads. */
interface HubEvents {
    /** Returns false if the clipboard couldn't be set. */
    fun clipboard(text: String, from: String): Boolean
    fun ring(on: Boolean, from: String)
    fun notification(item: NotificationItem, from: String) {}
    fun call(call: CallEvent, from: String) {}
    /** Touchpad, keyboard, and shortcuts from the phone. Only called while [canControl] says yes. */
    fun input(event: InputEvent, from: String) {}
    /** play_pause, next, previous, volume_up, volume_down, or mute. */
    fun media(action: String, from: String) {}
    /** The phone's sound: [rate] and [channels] before the chunks, then 16-bit PCM chunks, then null when it ends. */
    fun phoneSound(from: String, rate: Int, channels: Int) {}
    fun phoneSoundChunk(from: String, pcm: ByteArray) {}
    fun phoneSoundEnded(from: String) {}
    /** A picture copied on another computer, as PNG, for this clipboard. */
    fun clipboardImage(png: ByteArray, from: String) {}
    /** SMS that just arrived on the phone (incoming only), for a notification here. */
    fun sms(items: List<SmsItem>, from: String) {}
    /** Whether this computer can move the pointer and type. On Wayland only through the desktop portal or /dev/uinput. */
    val canControl: Boolean get() = false

    // What this PC serves phones: its sound, commands, and a terminal. All off unless the app says otherwise.

    /** Null when [from] may hear this PC now, or why not. */
    fun soundRefusal(from: String): String? = "This PC can't send its sound yet. Sound from the phone to the PC works."
    /** A new recorder of this PC's sound, or null when there's none on this system. */
    fun soundCapture(): PcSoundCapture? = null
    /** The commands [from] may run, or null while that phone may not run commands. */
    fun commands(from: String): List<SavedCommand>? = null
    /** Runs a command and waits for it. Called on a background thread. */
    fun runCommand(command: SavedCommand): CommandResult = CommandResult(command.id, -1, "Commands aren't available on this PC.")
    /** Whether [from] may open a terminal now. */
    fun terminalAllowed(from: String): Boolean = false
    /** Starts shells for the terminal, or null when this PC can't. */
    val pty: PtyFactory? get() = null
    /** A terminal was opened from [from]; the PC always shows it. */
    fun terminalOpened(from: String) {}

    // This PC's screen on a phone (`macscreen.*`).

    /** Null when [from] may see this PC's screen now, or why not. */
    fun screenRefusal(from: String): String? = "This PC can't show its screen on a phone yet."
    /** What captures this PC's screen, or null when there's none on this system. */
    fun screenSource(): PcScreenSource? = null
    /** A tap or drag from [from] on this PC's screen, at [x], [y] in desktop pixels. Only called while [canControl] says yes. */
    fun screenPointer(kind: String, x: Int, y: Int, from: String) {}
    /** [from] started viewing this screen; the PC always shows it. */
    fun screenViewed(from: String) {}

    // The phone as an extra display (`pc-display`, `macscreen.start` with `text: "extend"`).

    /** Null when [from] may use itself as an extra display of this PC now, or why not. */
    fun extendRefusal(from: String): String? = PcScreen.NO_EXTEND
    /** What adds a monitor for the phone, or null when this PC has no way to. */
    fun extraDisplays(): PcExtraDisplays? = null
    /** [from] is an extra display of [width] × [height] now; the PC always shows it. */
    fun extendStarted(from: String, width: Int, height: Int) {}
    /**
     * Whether phones may control this PC (the switch), even where only an extra display's own session can move the
     * pointer, such as on Wayland without the remote desktop portal.
     */
    val controlSwitch: Boolean get() = canControl

    // Another computer's mouse and keyboard (a Mac's, through `remote.*`).

    /** Whether the Mac [from] may move its pointer into this PC now. */
    fun remoteAllowed(from: String): Boolean = false
    /**
     * `remote.enter`, `remote.input`, or `remote.exit` from a Mac; [event] as it came. Also `remote.leave` from a Mac
     * this PC's own mouse and keyboard are on (this PC as the source): the pointer came back.
     */
    fun remote(type: String, event: RemoteEvent?, from: String) {}
    /** Anything at all arrived from the computer [from] (a `pong` included): it's still there. Called often; keep it cheap. */
    fun heard(from: String) {}

    /** A ping from a paired computer: one in control that pings can be timed out when it goes quiet. */
    fun pinged(from: String) {}
    /** [from] disconnected or was unpaired: anything it held goes. */
    fun remoteGone(from: String) {}

    // The notch tools (`pc-notch`): Todo, water, and Pomodoro on this PC.

    /** What phones get in `notch.state`, or null when this PC has no notch tools. */
    fun notchState(): NotchState? = null
    /** A change asked for by [from]. The app applies it and calls [ComputerHub.notchChanged] when something changed. */
    fun notchAction(action: NotchAction, from: String) {}
}

/**
 * The computer side of the DyskLink protocol, as the Mac does it, for Windows and Linux.
 * Phones dial this computer, send `hello`, and pair by typing the code shown here.
 * Phones get the clipboard, files, notifications, calls, ringing, control, and sound. Other computers (Macs and PCs)
 * only share the clipboard, including pictures, files, and ringing, and connect while [setComputersEnabled] is on.
 */
class ComputerHub(
    private val directory: File,
    private val scope: CoroutineScope,
    private val localHello: (paired: Boolean) -> Hello,
    private val events: HubEvents,
    fileSinks: FileSinkFactory,
    private val port: Int = PhoneLinkProtocol.PORT,
    /** A local DeviceInfo, such as battery, sent to phones after they connect. */
    private val localInfo: () -> DeviceInfo = { DeviceInfo() },
    /** How often each connection gets a ping, well inside the other side's 75-second read timeout. */
    private val pingIntervalMs: Long = PING_INTERVAL_MS,
    private val pairingGuard: PairingGuard = PairingGuard(),
    /** A device that connects must send hello this soon. */
    private val helloTimeoutMs: Long = ConnectionLimiter.HELLO_TIMEOUT_MS,
    /** An unpaired device with no pairing going on is hung up on after this long; it can dial again. */
    private val unpairedIdleMs: Long = PhoneLinkProtocol.PAIRING_TIMEOUT_MS,
) {
    /**
     * [platform] is stored at pairing and decides what the device may do from then on, whatever its hello says.
     * Records from before it was stored (written without it) take it from the first hello after the update.
     */
    @Serializable
    private data class Record(val name: String, val model: String, val platform: String? = null)

    private val lock = Any()
    private val identity = LocalIdentity.loadOrCreate(directory)
    private val sslContext: SSLContext = identity.sslContext()
    val deviceId: String get() = identity.deviceId

    private val recordsFile = File(directory, "phones.json")
    private val records: MutableMap<String, Record> = loadRecords()
    private val connections = HashMap<String, PeerConnection>()
    private val infos = ConcurrentHashMap<String, DeviceInfo>()
    private val features = ConcurrentHashMap<String, List<String>>()
    private var server: ServerSocket? = null
    /** The phone whose sound plays here. */
    @Volatile private var soundFrom: String? = null
    private var pairingTimeout: Job? = null
    /** CPace state of [pairing]: [shower] while the code shows here, [typer] while it's typed here. */
    private val shower = ShowerSlot()
    private var typer: Pake.Typer? = null
    /** A code typed before the other computer's `pair.pake` arrived. */
    private var pendingCode: String? = null

    /** Other computers found on the network, by device ID, and the loops that dial them. */
    private val computerAddresses = ConcurrentHashMap<String, Pair<String, Int>>()
    private var computerDialer: Job? = null
    @Volatile private var computersOn = false

    /** Pictures for the clipboard arriving from other computers, by transfer ID. */
    private class ClipImage(val peerId: String, val size: Long) {
        val bytes = java.io.ByteArrayOutputStream()
        val digest: java.security.MessageDigest = java.security.MessageDigest.getInstance("SHA-256")
    }
    private val clipImages = ConcurrentHashMap<String, ClipImage>()
    /** Pictures sent from here, by transfer ID, with the computer each went to. */
    private val sentClipImages = ConcurrentHashMap<String, String>()
    private var pinger: Job? = null

    private val _phones = MutableStateFlow<List<HubPhone>>(emptyList())
    val phones: StateFlow<List<HubPhone>> = _phones.asStateFlow()
    private val _pairing = MutableStateFlow<HubPairing?>(null)
    val pairing: StateFlow<HubPairing?> = _pairing.asStateFlow()
    private val _notifications = MutableStateFlow<Map<String, List<NotificationItem>>>(emptyMap())
    /** Notifications showing on each phone now, newest first. */
    val notifications: StateFlow<Map<String, List<NotificationItem>>> = _notifications.asStateFlow()
    private val _ringing = MutableStateFlow<Set<String>>(emptySet())
    /** Phones this computer is ringing now. */
    val ringing: StateFlow<Set<String>> = _ringing.asStateFlow()

    private val _screens = MutableStateFlow<Map<String, PhoneVideo>>(emptyMap())
    /** Phone screens asked for or showing here, by phone. */
    val screens: StateFlow<Map<String, PhoneVideo>> = _screens.asStateFlow()
    private val _cameras = MutableStateFlow<Map<String, PhoneVideo>>(emptyMap())
    /** Phone cameras asked for or showing here, by phone. */
    val cameras: StateFlow<Map<String, PhoneVideo>> = _cameras.asStateFlow()
    private val _galleries = MutableStateFlow<Map<String, PhoneGallery>>(emptyMap())
    val galleries: StateFlow<Map<String, PhoneGallery>> = _galleries.asStateFlow()
    /** Gallery pages asked for and not answered yet, by phone: the offset each one starts at. */
    private val galleryAsked = ConcurrentHashMap<String, Int>()
    private val _sms = MutableStateFlow<Map<String, PhoneSms>>(emptyMap())
    val sms: StateFlow<Map<String, PhoneSms>> = _sms.asStateFlow()
    private val _contacts = MutableStateFlow<Map<String, PhoneContacts>>(emptyMap())
    /** The phone's contacts, by phone. */
    val contacts: StateFlow<Map<String, PhoneContacts>> = _contacts.asStateFlow()
    /** Pages of `contacts` still arriving, by phone; the list is replaced whole when the last one comes. */
    private val incomingContacts = ConcurrentHashMap<String, MutableList<ContactItem>>()
    private val _folders = MutableStateFlow<Map<String, PhoneFolder>>(emptyMap())
    /** The folder of the phone's storage open on this PC, by phone. */
    val folders: StateFlow<Map<String, PhoneFolder>> = _folders.asStateFlow()
    /** Folders asked for and not fully answered yet, by phone: the next `fs.listing` for it starts the list over. */
    private val folderFresh = ConcurrentHashMap<String, String>()
    /** Phones whose next `sms.messages` starts a fresh list. */
    private val smsFresh = ConcurrentHashMap.newKeySet<String>()

    /** The phone that hears this PC now, and what sends to it. */
    private var soundOut: PcSoundSender? = null
    private val _soundTo = MutableStateFlow<String?>(null)
    val soundTo: StateFlow<String?> = _soundTo.asStateFlow()

    /** The phone viewing this PC's screen now, and what sends to it. */
    private var screenOut: PcScreenSender? = null
    private var screenSource: PcScreenSource? = null
    private val _screenTo = MutableStateFlow<String?>(null)
    val screenTo: StateFlow<String?> = _screenTo.asStateFlow()
    /** Counts every start and stop of the above, so an extra display that took long to add can tell it's not wanted. */
    private val screenGen = java.util.concurrent.atomic.AtomicInteger()
    /** The size of the extra display [screenOut] shows; null while it shows this PC's own screen. */
    private var extendSize: Pair<Int, Int>? = null
    private val _extend = MutableStateFlow<HubExtend?>(null)
    /** The phone that is (or is becoming) an extra display of this PC. */
    val extend: StateFlow<HubExtend?> = _extend.asStateFlow()

    /** Terminals opened from phones. */
    val terminals = PcTerminals(
        factory = { events.pty },
        send = { id, envelope -> send(id, envelope) },
        post = { id, envelope -> post(id, envelope) },
        onOpened = { id -> runCatching { events.terminalOpened(id) } },
    )

    val fileTransfers = FileTransfers(
        scope, fileSinks, File(directory, "transfers.json"),
        linkFor = ::pairedConnection,
        post = { id, envelope -> pairedConnection(id)?.post(envelope) ?: false },
        admit = ::admitFile,
    ) { id, envelope -> send(id, envelope) }

    /**
     * Where a file goes: a document scan from a phone (by its pairing record) to the scans folder, as `kind: "scan"`;
     * anything else, and anything from another computer, to Downloads as always.
     */
    private fun admitFile(id: String, offer: FileMessage): Admission {
        if (offer.kind != SCAN_KIND) return Admission.Accept()
        val record = synchronized(lock) { records[id] } ?: return Admission.Accept()
        if (record.platform in PhoneLinkProtocol.COMPUTERS) return Admission.Accept()
        return Admission.Accept(FileOrigin(id, FileNames.sanitize(record.name), SCAN_KIND))
    }

    /** The port actually listened on, which differs from [port] when that was 0. */
    @Volatile var boundPort: Int = 0
        private set

    init {
        publish()
    }

    fun start() {
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        server = socket
        boundPort = socket.localPort
        // Each connection reads on its own thread; strangers are capped and must pair or go.
        InboundServer(
            socket, sslContext, scope, "hub",
            isPaired = { id -> synchronized(lock) { records.containsKey(id) } },
            isPairing = { id -> _pairing.value?.takeIf { !it.outdated }?.phoneId == id },
            helloTimeoutMs = helloTimeoutMs,
            idleMs = unpairedIdleMs,
        ) { connection, _ -> serve(connection) }.start()
        CoreLog.i(TAG, "Listening on $boundPort")
        startPinging()
    }

    /** Phones ping by themselves, but Macs and PCs only answer, so without this a quiet computer link times out. */
    private fun startPinging() {
        if (pinger != null) return
        pinger = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(pingIntervalMs)
                val all = synchronized(lock) { connections.values.toList() }
                // Queued on each connection's own writer, so one peer that stopped reading doesn't hold up the others.
                all.forEach { connection -> connection.post(Envelope(MessageType.PING)) }
            }
        }
    }

    fun shutdown() {
        soundOut?.stop()
        screenOut?.stop()
        terminals.end(null, null)
        pinger?.cancel()
        pinger = null
        computerDialer?.cancel()
        runCatching { server?.close() }
        synchronized(lock) { connections.values.toList() }.forEach { it.close() }
    }

    private fun serve(connection: PeerConnection) {
        try {
            connection.readLoop { handle(connection, it) }
        } catch (_: Exception) {
            // The phone left or the network dropped. It dials again.
        } finally {
            connection.close()
            val id = connection.peerId
            // A connection that was replaced cleans up nothing: its replacement already did, and owns the rest.
            val current = synchronized(lock) {
                if (connections[id] !== connection) return@synchronized false
                connections.remove(id)
                _notifications.update { it - id }
                _ringing.update { it - id }
                dropPhoneMedia(id)
                if (soundFrom == id) { soundFrom = null; events.phoneSoundEnded(id) }
                endServices(id, connected = false)
                if (_pairing.value?.phoneId == id) clearPairing()
                publish()
                true
            }
            if (current) dropTransfers(id)
        }
    }

    /** Ends everything that was moving over [id]'s connection, which is gone or replaced. */
    private fun dropTransfers(id: String) {
        fileTransfers.onDisconnected(id)
        clipImages.entries.removeIf { it.value.peerId == id }
        sentClipImages.entries.removeIf { it.value == id }
    }

    // Incoming

    private fun handle(connection: PeerConnection, envelope: Envelope) {
        val id = connection.peerId
        val paired = synchronized(lock) { records.containsKey(id) && connections[id] === connection }
        when (envelope.type) {
            MessageType.HELLO -> {
                envelope.hello?.let { hello(connection, it) }
                return
            }
            MessageType.PING -> {
                connection.post(Envelope(MessageType.PONG))
                // A Mac in control here pings while crossed; that keeps held keys from being let go.
                if (paired && isComputer(connection)) runCatching { events.heard(id); events.pinged(id) }
                return
            }
            MessageType.PONG -> {
                if (paired && isComputer(connection)) runCatching { events.heard(id) }
                return
            }
            MessageType.PAIR_REQUEST, MessageType.PAIR_PAKE, MessageType.PAIR_ACCEPT, MessageType.PAIR_REJECT -> return pairingMessage(connection, envelope)
            MessageType.UNPAIR -> {
                synchronized(lock) { removeRecord(id); publish() }
                return
            }
            // Only lets go of control, so it's taken even from a computer that was unpaired meanwhile.
            MessageType.REMOTE_EXIT -> {
                if (isComputer(connection)) runCatching { events.remote(MessageType.REMOTE_EXIT, null, id) }
                return
            }
        }
        // Everything else needs a paired phone on its current connection.
        if (!paired) return
        if (isComputer(connection)) {
            runCatching { events.heard(id) }
            return computerMessage(connection, envelope)
        }
        if (envelope.type.startsWith("file.")) {
            fileTransfers.handle(id, envelope.type, envelope.file, envelope.raw)
            return
        }
        when (envelope.type) {
            MessageType.INFO -> envelope.info?.let { infos[id] = it; synchronized(lock) { publish() } }
            MessageType.FEATURES -> {
                val list = envelope.features.orEmpty()
                features[id] = list
                if ("notifications" !in list) _notifications.update { it - id }
                if ("sms" !in list) _sms.update { it - id }
                if ("gallery" !in list) _galleries.update { it - id }
                if ("contacts" !in list) { _contacts.update { it - id }; incomingContacts.remove(id) }
                if ("files" !in list) { _folders.update { it - id }; folderFresh.remove(id) }
                synchronized(lock) { publish() }
            }
            MessageType.CLIPBOARD -> {
                val text = envelope.text
                val error = when {
                    text.isNullOrEmpty() || text.length > PhoneLinkProtocol.MAX_CLIPBOARD_CHARS -> "Invalid clipboard text"
                    !runCatching { events.clipboard(text, id) }.getOrDefault(false) -> "The clipboard couldn't be set"
                    else -> null
                }
                connection.post(Envelope(MessageType.CLIPBOARD_ACK, key = envelope.key, error = error))
            }
            MessageType.RING -> when (envelope.ring) {
                "start" -> events.ring(true, id)
                "stop" -> events.ring(false, id)
                "stopped" -> _ringing.update { it - id }
            }
            MessageType.NOTIFICATION_POSTED -> {
                val item = envelope.notification ?: return
                if ("notifications" !in features[id].orEmpty()) return
                _notifications.update { all ->
                    val list = all[id].orEmpty().filter { it.key != item.key }
                    all + (id to (listOf(item) + list).take(MAX_NOTIFICATIONS))
                }
                if (item.silent != true) events.notification(item, id)
            }
            MessageType.NOTIFICATION_REMOVED -> {
                val key = envelope.key ?: return
                _notifications.update { all -> all + (id to all[id].orEmpty().filter { it.key != key }) }
            }
            MessageType.CALL -> envelope.call?.takeIf { "calls" in features[id].orEmpty() }?.let { events.call(it, id) }
            MessageType.INPUT -> envelope.input?.takeIf { events.canControl }?.let { events.input(it, id) }
            MessageType.MEDIA -> envelope.media?.let { events.media(it, id) }
            MessageType.AUDIO_STARTED -> {
                // Queued: this is the reading thread, which must not wait on another phone's connection.
                soundFrom?.takeIf { it != id }?.let { other -> pairedConnection(other)?.post(Envelope(MessageType.AUDIO_STOP)) }
                soundFrom = id
                events.phoneSound(id, envelope.audioRate ?: 48_000, envelope.audioChannels ?: 2)
            }
            MessageType.AUDIO_FRAME -> if (soundFrom == id) {
                envelope.payload()?.let { events.phoneSoundChunk(id, it) }
            }
            MessageType.AUDIO_STOPPED -> if (soundFrom == id) { soundFrom = null; events.phoneSoundEnded(id) }
            MessageType.AUDIO_LISTEN -> listen(connection)
            // The phone stops hearing this PC.
            MessageType.AUDIO_STOP -> if (_soundTo.value == id) stopSoundOut()
            MessageType.COMMAND_RUN -> envelope.commandId?.let { runCommand(id, it) }
            MessageType.MACSCREEN_START -> screenStart(connection, envelope)
            MessageType.MACSCREEN_STOP -> if (_screenTo.value == id) stopScreenOut(null)
            MessageType.MACSCREEN_SYNC -> if (_screenTo.value == id) synchronized(lock) { screenOut }?.resync()
            MessageType.MACSCREEN_POINTER -> screenPointer(id, envelope.control)
            MessageType.TERMINAL_OPEN, MessageType.TERMINAL_INPUT, MessageType.TERMINAL_RESIZE, MessageType.TERMINAL_CLOSE -> {
                val message = envelope.terminal ?: return
                terminals.handle(id, envelope.type, message, runCatching { events.terminalAllowed(id) }.getOrDefault(false))
            }
            MessageType.SCREEN_STARTED, MessageType.SCREEN_FRAME, MessageType.SCREEN_STOPPED -> video(connection, VideoKind.Screen, envelope)
            MessageType.CAMERA_STARTED, MessageType.CAMERA_FRAME, MessageType.CAMERA_STOPPED -> video(connection, VideoKind.Camera, envelope)
            MessageType.GALLERY -> galleryPage(id, envelope)
            MessageType.SMS_MESSAGES -> if ("sms" in features[id].orEmpty()) smsPage(id, envelope)
            MessageType.SMS_NEW -> if ("sms" in features[id].orEmpty()) smsNew(id, envelope.sms.orEmpty())
            MessageType.SMS_RESULT -> envelope.smsResult?.let { result -> smsResult(id, result) }
            MessageType.CONTACTS -> if ("contacts" in features[id].orEmpty()) contactsPage(id, envelope)
            MessageType.FS_LISTING -> if ("files" in features[id].orEmpty()) folderPage(id, envelope)
            MessageType.NOTCH_ACTION -> envelope.notchAction?.let { action -> runCatching { events.notchAction(action, id) } }
        }
    }

    // The notch tools

    private fun notchEnvelope(): Envelope? = runCatching { events.notchState() }.getOrNull()?.let { Envelope(MessageType.NOTCH_STATE, notch = it) }

    /** Sends the notch tools' state to every connected phone, after something changed here or from a phone. */
    fun notchChanged() {
        val envelope = notchEnvelope() ?: return
        pairedConnections().filter { (_, connection) -> !isComputer(connection) }.forEach { (_, connection) -> connection.post(envelope) }
    }

    // Contacts

    /** Reads the phone's contacts again. The list is replaced whole, so contacts deleted on the phone go here too. */
    fun loadContacts(phoneId: String) {
        incomingContacts.remove(phoneId)
        _contacts.update { all -> all + (phoneId to (all[phoneId] ?: PhoneContacts()).copy(loading = true)) }
        scope.launch(Dispatchers.IO) {
            if (!post(phoneId, Envelope(MessageType.CONTACTS_LIST))) {
                _contacts.update { all -> all + (phoneId to (all[phoneId] ?: PhoneContacts()).copy(loading = false)) }
            }
        }
    }

    private fun contactsPage(id: String, envelope: Envelope) {
        val pages = incomingContacts.getOrPut(id) { java.util.Collections.synchronizedList(ArrayList()) }
        if (pages.size + envelope.contacts.orEmpty().size > MAX_CONTACTS) return
        pages.addAll(envelope.contacts.orEmpty())
        if (envelope.more == true) return
        incomingContacts.remove(id)
        val sorted = synchronized(pages) { pages.toList() }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        _contacts.update { it + (id to PhoneContacts(sorted, loading = false, loaded = true)) }
    }

    // The phone's files

    /** Opens a folder of the phone's storage, "" for the top. Hidden files show with [hidden]. */
    fun openPhoneFolder(phoneId: String, path: String, hidden: Boolean? = null) {
        val clean = path.trim('/')
        val showHidden = hidden ?: _folders.value[phoneId]?.showHidden ?: false
        folderFresh[phoneId] = clean
        _folders.update { all ->
            val now = all[phoneId] ?: PhoneFolder()
            all + (phoneId to now.copy(folder = clean, entries = if (now.folder == clean) now.entries else emptyList(), loading = true, showHidden = showHidden, error = null))
        }
        scope.launch(Dispatchers.IO) {
            if (!post(phoneId, Envelope(MessageType.FS_LIST, path = clean, hidden = showHidden))) {
                folderFresh.remove(phoneId)
                _folders.update { all -> all + (phoneId to (all[phoneId] ?: PhoneFolder()).copy(loading = false, error = "The phone isn't connected")) }
            }
        }
    }

    /** Asks for files of the open folder; each one arrives as a file in the downloads folder. */
    fun downloadPhoneFiles(phoneId: String, paths: List<String>) {
        val wanted = paths.map { it.trim('/') }.filter { it.isNotEmpty() }.distinct().take(MAX_FILE_DOWNLOADS)
        if (wanted.isEmpty()) return
        scope.launch(Dispatchers.IO) { wanted.forEach { post(phoneId, Envelope(MessageType.FS_GET, path = it)) } }
    }

    private fun folderPage(id: String, envelope: Envelope) {
        val path = envelope.path.orEmpty().trim('/')
        val entries = envelope.entries.orEmpty().take(MAX_FOLDER_ENTRIES)
        // The first page after asking replaces the list (remove says it was the first); later pages add on.
        val first = _folders.value[id]?.folder == path && folderFresh.remove(id, path)
        _folders.update { all ->
            val now = all[id] ?: PhoneFolder()
            when {
                // An answer for the open folder: its first page starts the list over, the pages after it add on.
                path == now.folder -> {
                    val merged = if (first) entries else (now.entries + entries).distinctBy { it.name }.take(MAX_FOLDER_ENTRIES)
                    all + (id to now.copy(entries = merged, loading = envelope.more == true, error = envelope.error))
                }
                // A download that failed (fs.get is answered with an empty listing for its path): only the message changes.
                envelope.error != null -> all + (id to now.copy(error = envelope.error))
                else -> all
            }
        }
    }

    // This PC's sound, commands, and terminal, for phones

    /** `audio.listen`: one phone at a time hears this PC; a second one takes over. */
    private fun listen(connection: PeerConnection) {
        val id = connection.peerId
        val refusal = runCatching { events.soundRefusal(id) }.getOrElse { it.message ?: "Can't send the sound" }
        if (refusal != null) {
            connection.post(Envelope(MessageType.AUDIO_STOPPED, error = refusal))
            return
        }
        val capture = runCatching { events.soundCapture() }.getOrNull()
        if (capture == null) {
            connection.post(Envelope(MessageType.AUDIO_STOPPED, error = "This PC can't record its sound."))
            return
        }
        val previous = synchronized(lock) { soundOut.also { soundOut = null } }
        val before = _soundTo.value
        previous?.stop()
        if (before != null && before != id) post(before, Envelope(MessageType.AUDIO_STOPPED, error = "Another phone is hearing this PC now."))
        lateinit var sender: PcSoundSender
        sender = PcSoundSender(
            capture,
            send = { envelope -> connection.send(envelope) },
            binary = { connection.binary },
            onEnded = { why ->
                if (synchronized(lock) { (soundOut === sender).also { if (it) soundOut = null } }) {
                    _soundTo.value = null
                    connection.post(Envelope(MessageType.AUDIO_STOPPED, error = why))
                }
            },
        )
        synchronized(lock) { soundOut = sender }
        // Recording starts off the reading thread; WASAPI or parec can take a moment.
        scope.launch(Dispatchers.IO) {
            val error = sender.start()
            if (error != null) {
                synchronized(lock) { if (soundOut === sender) soundOut = null }
                connection.post(Envelope(MessageType.AUDIO_STOPPED, error = error))
            } else if (synchronized(lock) { soundOut === sender }) {
                _soundTo.value = id
                CoreLog.i(TAG, "SOUND_OUT_STARTED $id")
            } else {
                sender.stop()
            }
        }
    }

    /** Stops sending this PC's sound, and tells the phone. */
    fun stopSoundOut() {
        val sender = synchronized(lock) { soundOut.also { soundOut = null } } ?: return
        val id = _soundTo.value
        _soundTo.value = null
        sender.stop()
        if (id != null) post(id, Envelope(MessageType.AUDIO_STOPPED))
    }

    /**
     * `macscreen.start`: one phone at a time sees this PC's screen; a second one takes over. Asked again by the same
     * phone (it came back, or picked another display), the picture starts over on the display it asked for.
     */
    private fun screenStart(connection: PeerConnection, envelope: Envelope) {
        val id = connection.peerId
        fun refuse(why: String) { connection.post(Envelope(MessageType.MACSCREEN_STOPPED, error = why)) }
        if (envelope.text?.startsWith("extend") == true) return extendStart(connection, envelope)
        val refusal = runCatching { events.screenRefusal(id) }.getOrElse { it.message ?: "Can't show the screen" }
        if (refusal != null) return refuse(refusal)
        val source = runCatching { events.screenSource() }.getOrNull() ?: return refuse("This PC can't capture its screen.")
        runCatching { source.refusal() }.getOrNull()?.let { return refuse(it) }
        val display = PcScreen.pick(runCatching { source.displays() }.getOrDefault(emptyList()), envelope.displayId)
            ?: return refuse("No display was found on this PC.")
        synchronized(lock) {
            val current = screenOut
            if (current != null && current.isRunning && _screenTo.value == id && extendSize == null) {
                current.show(display)
                return
            }
        }
        screenGen.incrementAndGet()
        val previous = synchronized(lock) { screenOut.also { screenOut = null; extendSize = null } }
        _extend.value = null
        val before = _screenTo.value
        previous?.stop()
        if (before != null && before != id) post(before, Envelope(MessageType.MACSCREEN_STOPPED, error = PcScreen.ANOTHER_PHONE))
        lateinit var sender: PcScreenSender
        sender = PcScreenSender(
            source, display, scope,
            send = { message -> connection.send(message) },
            onEnded = { why ->
                if (synchronized(lock) { (screenOut === sender).also { if (it) { screenOut = null; screenSource = null } } }) {
                    _screenTo.value = null
                    connection.post(Envelope(MessageType.MACSCREEN_STOPPED, error = why))
                }
            },
        )
        synchronized(lock) {
            screenOut = sender
            screenSource = source
        }
        _screenTo.value = id
        sender.start()
        CoreLog.i(TAG, "SCREEN_OUT_STARTED $id ${display.width}x${display.height}")
        runCatching { events.screenViewed(id) }
    }

    /**
     * `macscreen.start` with `text: "extend"` (or "extend-large") and the phone's wanted `screen`: this PC adds a monitor
     * of that shape and streams it as it streams its own screen. Asked again with the same size, the picture starts
     * over; with another size (the phone turned), the monitor is made again. One phone at a time, as for viewing.
     */
    private fun extendStart(connection: PeerConnection, envelope: Envelope) {
        val id = connection.peerId
        fun refuse(why: String) { connection.post(Envelope(MessageType.MACSCREEN_STOPPED, error = why)) }
        val refusal = runCatching { events.extendRefusal(id) }.getOrElse { it.message ?: PcScreen.NO_EXTEND }
        if (refusal != null) return refuse(refusal)
        val displays = runCatching { events.extraDisplays() }.getOrNull() ?: return refuse(PcScreen.NO_EXTEND)
        runCatching { displays.refusal() }.getOrNull()?.let { return refuse(it) }
        val size = PcSecondDisplay.size(envelope.screen, large = envelope.text == "extend-large") ?: return refuse(PcSecondDisplay.ODD_SIZE)
        synchronized(lock) {
            val current = screenOut
            if (current != null && current.isRunning && _screenTo.value == id && extendSize == size) {
                current.show(current.display)
                return
            }
            // Still being added at this size: the answer comes when it's there.
            val pending = _extend.value
            if (current == null && pending != null && !pending.active && pending.phoneId == id && pending.width == size.first && pending.height == size.second) return
        }
        val gen = screenGen.incrementAndGet()
        val previous = synchronized(lock) { screenOut.also { screenOut = null; screenSource = null; extendSize = null } }
        val before = _screenTo.value
        previous?.stop()
        if (before != null && before != id) post(before, Envelope(MessageType.MACSCREEN_STOPPED, error = PcScreen.ANOTHER_PHONE))
        _screenTo.value = id
        _extend.value = HubExtend(id, size.first, size.second, active = false)
        val name = synchronized(lock) { records[id]?.name } ?: "Phone"
        CoreLog.i(TAG, "EXTEND_REQUESTED $id ${size.first}x${size.second}")
        scope.launch(Dispatchers.IO) {
            fun ended(why: String?) {
                if (screenGen.get() != gen) return
                _screenTo.value = null
                _extend.value = null
                connection.post(Envelope(MessageType.MACSCREEN_STOPPED, error = why))
            }
            val extra = try {
                displays.open(size.first, size.second, name)
            } catch (e: PcExtendFailed) {
                return@launch ended(e.message ?: PcSecondDisplay.FAILED)
            } catch (e: Exception) {
                CoreLog.w(TAG, "Extra display failed", e)
                return@launch ended(PcSecondDisplay.FAILED)
            }
            val display = runCatching { extra.displays() }.getOrNull()?.firstOrNull()
            if (display == null || screenGen.get() != gen) {
                runCatching { extra.release() }
                if (display == null) ended(PcSecondDisplay.FAILED)
                return@launch
            }
            lateinit var sender: PcScreenSender
            sender = PcScreenSender(
                extra, display, scope,
                send = { message -> connection.send(message) },
                onEnded = { why ->
                    if (synchronized(lock) { (screenOut === sender).also { if (it) { screenOut = null; screenSource = null; extendSize = null } } }) {
                        _screenTo.value = null
                        _extend.value = null
                        connection.post(Envelope(MessageType.MACSCREEN_STOPPED, error = why))
                    }
                },
                maxLong = PcSecondDisplay.MAX_LONG_SIDE,
                listDisplays = false,
            )
            val taken = synchronized(lock) {
                (screenGen.get() == gen).also { if (it) { screenOut = sender; screenSource = extra; extendSize = size } }
            }
            if (!taken) {
                runCatching { extra.release() }
                return@launch
            }
            _extend.value = HubExtend(id, display.width, display.height, active = true)
            sender.start()
            CoreLog.i(TAG, "EXTEND_STARTED $id ${display.width}x${display.height}")
            runCatching { events.extendStarted(id, display.width, display.height) }
        }
    }

    /** A tap or drag on this PC's screen, only from the phone viewing it and while phones may control this PC. */
    private fun screenPointer(id: String, control: ControlEvent?) {
        if (_screenTo.value != id || control == null || control.kind !in PcScreen.POINTER_KINDS) return
        val (sender, source) = synchronized(lock) { screenOut to screenSource }
        if (sender == null || source == null) return
        // An extra display on Wayland places taps through its own session, against its own picture.
        val extra = source as? PcExtraDisplay
        if (extra != null && control.x?.isFinite() == true && control.y?.isFinite() == true && runCatching { events.controlSwitch }.getOrDefault(false)) {
            if (runCatching { extra.pointer(control.kind, control.x.coerceIn(0f, 1f), control.y.coerceIn(0f, 1f)) }.getOrDefault(false)) return
        }
        if (!events.canControl) return
        // Read each time, since the resolution or the arrangement of displays can change while viewing.
        val display = runCatching { source.displays() }.getOrNull()?.firstOrNull { it.id == sender.display.id } ?: sender.display
        val (x, y) = PcScreen.toDesktop(display, control.x, control.y) ?: return
        runCatching { events.screenPointer(control.kind, x, y, id) }
    }

    /** Stops showing this PC's screen, and tells the phone, with [error] when it was stopped here. */
    fun stopScreenOut(error: String? = PcScreen.STOPPED_HERE) {
        screenGen.incrementAndGet()
        val pending = _extend.value
        val sender = synchronized(lock) { screenOut.also { screenOut = null; screenSource = null; extendSize = null } }
        _extend.value = null
        if (sender == null && pending == null) return
        val id = _screenTo.value
        _screenTo.value = null
        if (sender == null) {
            // An extra display still being added: it's released when it's there.
            if (id != null) post(id, Envelope(MessageType.MACSCREEN_STOPPED, error = error))
            return
        }
        sender.stop()
        CoreLog.i(TAG, "SCREEN_OUT_STOPPED sent=${sender.sent} unchanged=${sender.unchanged}")
        if (id != null) post(id, Envelope(MessageType.MACSCREEN_STOPPED, error = error))
    }

    /** Sends each connected phone the commands it may run now: after a change in the list or a phone's switch. */
    fun commandsChanged() {
        val phones = pairedConnections().filter { (_, connection) -> !isComputer(connection) }
        for ((id, connection) in phones) connection.post(commandList(id))
    }

    private fun commandList(id: String) = Envelope(
        MessageType.COMMANDS_LIST,
        commands = runCatching { events.commands(id) }.getOrNull().orEmpty().map { CommandItem(it.id, it.name) },
    )

    private fun runCommand(id: String, commandId: String) {
        val allowed = runCatching { events.commands(id) }.getOrNull()
        if (allowed == null) {
            post(id, Envelope(MessageType.COMMAND_RESULT, commandResult = CommandResult(commandId, -1, "Commands are turned off for this phone on the PC.")))
            return
        }
        // Only IDs from this PC's own list; the command line never comes from the phone.
        val command = allowed.firstOrNull { it.id == commandId }
        if (command == null) {
            post(id, Envelope(MessageType.COMMAND_RESULT, commandResult = CommandResult(commandId, -1, "Command not found")))
            return
        }
        CoreLog.i(TAG, "COMMAND_RUN ${command.name} for $id")
        scope.launch(Dispatchers.IO) {
            val result = runCatching { events.runCommand(command) }.getOrElse { CommandResult(command.id, -1, "The command couldn't run.") }
            post(id, Envelope(MessageType.COMMAND_RESULT, commandResult = result.copy(id = command.id)))
        }
    }

    /** Ends [phoneId]'s terminals, such as when its switch is turned off. It's told why. */
    fun endTerminals(phoneId: String) = terminals.end(phoneId)

    /** What [id] had going here ends: it disconnected, or (with [connected]) was unpaired. */
    private fun endServices(id: String, connected: Boolean) {
        if (_soundTo.value == id) {
            val sender = soundOut.also { soundOut = null }
            _soundTo.value = null
            sender?.stop()
        }
        if (_screenTo.value == id) {
            screenGen.incrementAndGet()
            val sender = synchronized(lock) { screenOut.also { screenOut = null; screenSource = null; extendSize = null } }
            _screenTo.value = null
            _extend.value = null
            sender?.stop()
        }
        terminals.end(id, if (connected) PcTerminals.ENDED_HERE else null)
        runCatching { events.remoteGone(id) }
    }

    // A Mac's mouse and keyboard

    /**
     * Sends `remote.leave` or `remote.exit` to the Mac in control, or, with this PC as the source, `remote.enter`,
     * `remote.input`, `remote.exit`, and `ping` (its `pong` is the heartbeat) to the Mac its pointer is on.
     */
    fun sendRemote(id: String, envelope: Envelope): Boolean = post(id, envelope)

    /** Sends this PC's info again to every paired device, after something in it changed (such as remoteControl). */
    fun infoChanged() {
        val info = localInfo()
        pairedConnections().forEach { (_, connection) -> connection.post(Envelope(MessageType.INFO, info = info)) }
    }

    // The phone's screen and camera, as JPEG frames

    private fun videos(kind: VideoKind) = if (kind == VideoKind.Screen) _screens else _cameras

    /** Only what this PC asked for is taken; H.264 from a phone app that doesn't know PCs is refused with a hint. */
    private fun video(connection: PeerConnection, kind: VideoKind, envelope: Envelope) {
        val id = connection.peerId
        val flow = videos(kind)
        val current = flow.value[id] ?: return
        val stopType = if (kind == VideoKind.Screen) MessageType.SCREEN_STOP else MessageType.CAMERA_STOP
        when (envelope.type) {
            MessageType.SCREEN_STARTED, MessageType.CAMERA_STARTED -> {
                if (envelope.format != JpegFrames.FORMAT) {
                    flow.update { it + (id to current.copy(waiting = false, live = false, frame = null, error = UPDATE_PHONE)) }
                    connection.post(Envelope(stopType))
                    return
                }
                val size = envelope.screen
                val state = envelope.cameraState
                flow.update {
                    it + (id to current.copy(
                        waiting = current.frame == null, error = null,
                        width = size?.width ?: current.width, height = size?.height ?: current.height,
                        rotation = envelope.rotation?.let { r -> Math.floorMod(r, 360) } ?: current.rotation,
                        lens = envelope.text ?: current.lens,
                        torch = state?.torch ?: current.torch, hasFlash = state?.flash ?: current.hasFlash,
                    ))
                }
            }
            MessageType.SCREEN_FRAME, MessageType.CAMERA_FRAME -> {
                val jpeg = envelope.payload()
                if (envelope.format != JpegFrames.FORMAT || !JpegFrames.looksLikeJpeg(jpeg)) return
                flow.update { all ->
                    val now = all[id] ?: return@update all
                    all + (id to now.copy(waiting = false, live = true, frame = jpeg, frames = now.frames + 1))
                }
            }
            MessageType.SCREEN_STOPPED, MessageType.CAMERA_STOPPED -> {
                val error = envelope.error?.takeIf { it.isNotBlank() }
                flow.update { if (error == null) it - id else it + (id to current.copy(waiting = false, live = false, frame = null, error = error)) }
            }
        }
    }

    /** Asks for the phone's screen. The phone shows a notification; Android asks before anything is shared. */
    fun requestScreen(phoneId: String) = requestVideo(phoneId, VideoKind.Screen)

    fun stopScreen(phoneId: String) = stopVideo(phoneId, VideoKind.Screen)

    /** Asks for the phone's camera. It starts after a tap on the phone's notification. */
    fun requestCamera(phoneId: String) = requestVideo(phoneId, VideoKind.Camera)

    fun stopCamera(phoneId: String) = stopVideo(phoneId, VideoKind.Camera)

    /** `switch` (the other lens) or `torch`, for this PC's own camera stream. */
    fun cameraControl(phoneId: String, action: String) {
        if (action !in setOf("switch", "torch") || _cameras.value[phoneId]?.live != true) return
        scope.launch(Dispatchers.IO) { post(phoneId, Envelope(MessageType.CAMERA_CONTROL, text = action)) }
    }

    /** A tap, swipe, key, or text on the phone's screen while it shows here. The phone checks its Control switch. */
    fun control(phoneId: String, event: ControlEvent) {
        if (_screens.value[phoneId]?.live != true) return
        val clean = ControlRules.sanitize(event) ?: return
        // Several fingers at once only go to a phone that takes them from a PC; an older one would drop them anyway.
        if (clean.kind in PcRules.MULTI_TOUCH && _phones.value.none { it.id == phoneId && it.servesGameKeys }) return
        // Queued in order on the connection, so a swipe's points arrive as they were made.
        post(phoneId, Envelope(MessageType.CONTROL, control = clean))
    }

    private fun requestVideo(phoneId: String, kind: VideoKind) {
        if (pairedConnection(phoneId) == null) return
        // Asking again (such as after the notification was swiped away) shows it on the phone again.
        videos(kind).update { it + (phoneId to PhoneVideo(kind)) }
        val type = if (kind == VideoKind.Screen) MessageType.SCREEN_REQUEST else MessageType.CAMERA_REQUEST
        scope.launch(Dispatchers.IO) { post(phoneId, Envelope(type)) }
    }

    private fun stopVideo(phoneId: String, kind: VideoKind) {
        val flow = videos(kind)
        if (flow.value[phoneId] == null) return
        flow.update { it - phoneId }
        val type = if (kind == VideoKind.Screen) MessageType.SCREEN_STOP else MessageType.CAMERA_STOP
        scope.launch(Dispatchers.IO) { post(phoneId, Envelope(type)) }
    }

    // Gallery

    /** The first page again with [fresh], or the next one. Files asked for arrive through the usual file transfer. */
    fun loadGallery(phoneId: String, fresh: Boolean = false) {
        val now = _galleries.value[phoneId] ?: PhoneGallery()
        if (!fresh && (now.loading || now.end)) return
        val offset = if (fresh) 0 else now.items.size
        galleryAsked[phoneId] = offset
        _galleries.update { it + (phoneId to (if (fresh) PhoneGallery() else now).copy(loading = true, error = null)) }
        scope.launch(Dispatchers.IO) { if (!post(phoneId, Envelope(MessageType.GALLERY_LIST, offset = offset))) galleryFailed(phoneId, "The phone isn't connected") }
    }

    /** Asks for the originals; each one arrives as a file in the downloads folder. */
    fun downloadGallery(phoneId: String, ids: List<Long>) {
        val known = _galleries.value[phoneId]?.items.orEmpty().map { it.id }.toSet()
        val wanted = ids.distinct().filter { it in known }.take(MAX_GALLERY_DOWNLOADS)
        if (wanted.isEmpty()) return
        scope.launch(Dispatchers.IO) { wanted.forEach { post(phoneId, Envelope(MessageType.GALLERY_GET, galleryId = it)) } }
    }

    private fun galleryFailed(phoneId: String, error: String) {
        galleryAsked.remove(phoneId)
        _galleries.update { all -> all + (phoneId to (all[phoneId] ?: PhoneGallery()).copy(loading = false, error = error)) }
    }

    private fun galleryPage(id: String, envelope: Envelope) {
        val items = envelope.gallery.orEmpty().take(PhoneBridge.GALLERY_PAGE * 2)
        val asked = galleryAsked[id]
        // An error with no page asked for, such as a photo that's gone: only the message changes.
        if (asked == null || envelope.offset != asked) {
            envelope.error?.let { error -> _galleries.update { all -> all[id]?.let { all + (id to it.copy(error = error)) } ?: all } }
            return
        }
        val more = envelope.more == true
        if (!more) galleryAsked.remove(id)
        _galleries.update { all ->
            val now = all[id] ?: PhoneGallery()
            val seen = now.items.map { it.id }.toHashSet()
            val merged = (now.items + items.filter { seen.add(it.id) }).take(MAX_GALLERY_ITEMS)
            all + (id to now.copy(
                items = merged, loading = more, error = envelope.error,
                end = envelope.galleryEnd == true || merged.size >= MAX_GALLERY_ITEMS,
            ))
        }
    }

    // SMS

    /** Reads the phone's recent SMS (the newest few hundred). New ones arrive by themselves after that. */
    fun loadSms(phoneId: String) {
        smsFresh.add(phoneId)
        _sms.update { all -> all + (phoneId to (all[phoneId] ?: PhoneSms()).copy(loading = true)) }
        scope.launch(Dispatchers.IO) {
            if (!post(phoneId, Envelope(MessageType.SMS_LIST))) {
                smsFresh.remove(phoneId)
                _sms.update { all -> all + (phoneId to (all[phoneId] ?: PhoneSms()).copy(loading = false)) }
            }
        }
    }

    /** Sends an SMS through the phone. Returns its ID, or null when the text or number isn't usable. */
    fun sendSms(phoneId: String, address: String, body: String): String? {
        val to = address.trim()
        val text = body.trim()
        if (to.isEmpty() || to.length > 64 || text.isEmpty() || text.length > 2_000) return null
        val id = java.util.UUID.randomUUID().toString()
        val entry = SmsOutgoing(id, to, text, System.currentTimeMillis())
        _sms.update { all -> all + (phoneId to (all[phoneId] ?: PhoneSms()).let { it.copy(sent = (listOf(entry) + it.sent).take(MAX_SMS_SENT)) }) }
        scope.launch(Dispatchers.IO) {
            if (!post(phoneId, Envelope(MessageType.SMS_SEND, smsSend = SmsSend(id, to, text)))) smsResult(phoneId, SmsResult(id, false, "The phone isn't connected"))
        }
        return id
    }

    /** The first page of the reply to [loadSms] starts the list over; the pages after it (`more`) add on. */
    private fun smsPage(id: String, envelope: Envelope) {
        val fresh = smsFresh.remove(id)
        val more = envelope.more == true
        _sms.update { all ->
            val now = all[id] ?: PhoneSms()
            all + (id to now.copy(messages = mergeSms(if (fresh) emptyList() else now.messages, envelope.sms.orEmpty()), loading = more, loaded = true))
        }
    }

    private fun smsNew(id: String, items: List<SmsItem>) {
        if (items.isEmpty()) return
        val known = _sms.value[id]?.messages.orEmpty().map { it.id }.toHashSet()
        _sms.update { all -> all + (id to (all[id] ?: PhoneSms()).let { it.copy(messages = mergeSms(it.messages, items)) }) }
        val incoming = items.filter { !it.outgoing && it.id !in known }
        if (incoming.isNotEmpty()) runCatching { events.sms(incoming, id) }
    }

    private fun smsResult(id: String, result: SmsResult) {
        _sms.update { all ->
            val now = all[id] ?: return@update all
            all + (id to now.copy(sent = now.sent.map { if (it.id == result.id) it.copy(ok = result.ok, error = result.error) else it }))
        }
    }

    private fun mergeSms(base: List<SmsItem>, add: List<SmsItem>): List<SmsItem> =
        (add + base).distinctBy { it.id }.sortedByDescending { it.time }.take(MAX_SMS)

    /** What showed from [id] ends with its connection. */
    private fun dropPhoneMedia(id: String) {
        _screens.update { it - id }
        _cameras.update { it - id }
        galleryAsked.remove(id)
        _galleries.update { all -> all[id]?.let { all + (id to it.copy(loading = false)) } ?: all }
        smsFresh.remove(id)
        _sms.update { all -> all[id]?.let { all + (id to it.copy(loading = false)) } ?: all }
        incomingContacts.remove(id)
        _contacts.update { all -> all[id]?.let { all + (id to it.copy(loading = false)) } ?: all }
        folderFresh.remove(id)
        _folders.update { all -> all[id]?.let { all + (id to it.copy(loading = false)) } ?: all }
    }

    private fun hello(connection: PeerConnection, hello: Hello) {
        val replaced = helloLocked(connection, hello)
        // The old connection's transfers can't go on over the new one.
        if (replaced != null) dropTransfers(connection.peerId)
    }

    /** Returns the connection this one replaced, if any. */
    private fun helloLocked(connection: PeerConnection, hello: Hello): PeerConnection? = locked {
        val id = connection.peerId
        // One hello per connection. A second one could claim to be a different kind of device.
        if (connection.hello != null) return@locked null
        val record = records[id]
        // Once paired, whether it's a phone or a computer comes from pairing. A device that now claims otherwise is hung up on.
        val stored = record?.platform
        if (stored != null && (stored in PhoneLinkProtocol.COMPUTERS) != (hello.platform in PhoneLinkProtocol.COMPUTERS)) {
            CoreLog.w(TAG, "HELLO_CLASS_CHANGED $id was $stored, says ${hello.platform}")
            close(connection)
            return@locked null
        }
        connection.hello = hello
        // Other computers only connect while that's turned on here, as with Other Macs on a Mac.
        if ((stored ?: hello.platform) in PhoneLinkProtocol.COMPUTERS && !computersOn) {
            close(connection)
            return@locked null
        }
        // The newest connection from a phone replaces the old one, as on the Mac. That is how USB takes over from Wi-Fi.
        if (connection.isUsb && (stored ?: hello.platform) !in PhoneLinkProtocol.COMPUTERS) CoreLog.i(TAG, "USB_SESSION ${hello.name}")
        val replaced = connections.put(id, connection)?.takeIf { it !== connection }
        replaced?.let { close(it) }
        if (record != null && !hello.paired) removeRecord(id)
        if (record != null && hello.paired) {
            records[id] = record.copy(name = hello.name, model = hello.model, platform = stored ?: hello.platform)
            saveRecords()
        }
        // The side that dialed has sent its hello already.
        if (connection.inbound) send(connection, Envelope(MessageType.HELLO, hello = localHello(records.containsKey(id))))
        if (records.containsKey(id)) send(connection, Envelope(MessageType.INFO, info = localInfo()))
        // As the Mac does: a paired phone gets the commands it may run (none while its switch is off), and the notch tools.
        if (records.containsKey(id) && (stored ?: hello.platform) !in PhoneLinkProtocol.COMPUTERS) {
            send(connection, commandList(id))
            notchEnvelope()?.let { send(connection, it) }
        }
        publish()
        replaced
    }

    /** Runs [block] under the lock and sends what it queued once the lock is released. */
    private fun <T> locked(block: Outgoing.() -> T): T {
        val out = Outgoing()
        val result = synchronized(lock) { out.block() }
        out.flush()
        return result
    }

    /** For calls from the window: the state changes right away, and the network part runs in the background. */
    private fun lockedInBackground(block: Outgoing.() -> Unit) {
        val out = Outgoing()
        synchronized(lock) { out.block() }
        if (!out.isEmpty) scope.launch(Dispatchers.IO) { out.flush() }
    }

    /** Came in from this computer itself, which only `adb reverse` does: a phone on the USB cable. */
    private val PeerConnection.isUsb: Boolean get() = inbound && isLoopback

    /** Hangs up on phones connected over USB, so they go back to Wi-Fi, after USB was turned off here. */
    fun closeUsbConnections() {
        synchronized(lock) { connections.values.filter { it.isUsb && !isComputer(it) } }.forEach { it.close() }
    }

    // Other computers: Macs and other PCs. Only the clipboard, files, and ringing, as between Macs.

    /** The paired record decides; the hello only for a device that isn't paired. */
    private fun isComputer(connection: PeerConnection) =
        synchronized(lock) { records[connection.peerId]?.platform ?: connection.hello?.platform } in PhoneLinkProtocol.COMPUTERS

    private fun computerMessage(connection: PeerConnection, envelope: Envelope) {
        val id = connection.peerId
        when (envelope.type) {
            MessageType.INFO -> envelope.info?.let { infos[id] = it; synchronized(lock) { publish() } }
            MessageType.CLIPBOARD -> {
                val text = envelope.text
                if (!text.isNullOrEmpty() && text.length <= PhoneLinkProtocol.MAX_CLIPBOARD_CHARS) runCatching { events.clipboard(text, id) }
            }
            MessageType.RING -> when (envelope.ring) {
                "start" -> events.ring(true, id)
                "stop" -> events.ring(false, id)
                "stopped" -> _ringing.update { it - id }
            }
            MessageType.FILE_OFFER, MessageType.FILE_CHUNK, MessageType.FILE_DONE, MessageType.FILE_CANCEL, MessageType.FILE_RESULT -> {
                val file = envelope.file ?: return
                if (!clipboardFile(id, envelope.type, file, envelope.raw)) fileTransfers.handle(id, envelope.type, file, envelope.raw)
            }
            // Only a Mac, by its pairing record, may use this PC's mouse and keyboard, and only while that's allowed here.
            MessageType.REMOTE_ENTER, MessageType.REMOTE_INPUT -> {
                if (synchronized(lock) { records[id]?.platform } != "macos") return
                if (envelope.type == MessageType.REMOTE_ENTER && !runCatching { events.remoteAllowed(id) }.getOrDefault(false)) {
                    connection.post(Envelope(MessageType.REMOTE_EXIT))
                    return
                }
                runCatching { events.remote(envelope.type, envelope.remote, id) }
            }
            // The Mac this PC's own mouse and keyboard are on hands the pointer back. Only from a Mac.
            MessageType.REMOTE_LEAVE -> {
                if (synchronized(lock) { records[id]?.platform } != "macos") return
                runCatching { events.remote(envelope.type, envelope.remote, id) }
            }
        }
    }

    /** Handles a picture for the clipboard. Returns false for an ordinary file. */
    private fun clipboardFile(id: String, type: String, file: FileMessage, raw: ByteArray?): Boolean {
        when (type) {
            MessageType.FILE_OFFER -> {
                if (file.kind != CLIPBOARD_KIND) return false
                val size = file.size ?: return true
                if (size !in 0..MAX_CLIP_IMAGE_BYTES) {
                    post(id, Envelope(MessageType.FILE_RESULT, file = FileMessage(file.id, ok = false, error = "The picture is too big")))
                    return true
                }
                // An ID another computer is using stays theirs.
                if (clipImages[file.id]?.let { it.peerId != id } == true) return true
                // One picture at a time from each computer: a newer copy replaces the one still arriving.
                clipImages.entries.removeIf { it.value.peerId == id }
                clipImages[file.id] = ClipImage(id, size)
            }
            MessageType.FILE_CHUNK -> {
                val item = clipImages[file.id]?.takeIf { it.peerId == id } ?: return false
                val bytes = raw ?: runCatching { java.util.Base64.getDecoder().decode(file.data.orEmpty()) }.getOrNull()
                if (bytes == null || item.bytes.size() + bytes.size > item.size) { clipImages.remove(file.id, item); return true }
                item.bytes.write(bytes)
                item.digest.update(bytes)
            }
            MessageType.FILE_DONE -> {
                val item = clipImages[file.id]?.takeIf { it.peerId == id } ?: return false
                clipImages.remove(file.id, item)
                val hash = item.digest.digest().joinToString("") { "%02x".format(it) }
                val ok = item.bytes.size().toLong() == item.size && hash == file.sha256
                if (ok) runCatching { events.clipboardImage(item.bytes.toByteArray(), id) }
                post(id, Envelope(MessageType.FILE_RESULT, file = FileMessage(file.id, ok = ok, error = if (ok) null else "The picture was damaged")))
            }
            MessageType.FILE_CANCEL -> {
                val item = clipImages[file.id]?.takeIf { it.peerId == id } ?: return false
                clipImages.remove(file.id, item)
            }
            MessageType.FILE_RESULT -> return sentClipImages.remove(file.id, id)
        }
        return true
    }

    /** Sends a copied picture to every paired computer, where it lands on the clipboard. */
    fun sendClipboardImage(png: ByteArray) {
        if (png.isEmpty() || png.size > MAX_CLIP_IMAGE_BYTES) return
        val targets = pairedConnections().filter { (_, connection) -> isComputer(connection) }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
        for ((id, connection) in targets) scope.launch(Dispatchers.IO) {
            val transfer = java.util.UUID.randomUUID().toString()
            sentClipImages[transfer] = id
            // Forgotten when it can't be sent; otherwise the answer (or the disconnect) removes it.
            if (!sendClipImage(connection, transfer, png, hash)) sentClipImages.remove(transfer)
            else CoreLog.i(TAG, "CLIPBOARD_IMAGE_SENT ${png.size} to $id")
        }
    }

    private fun sendClipImage(connection: PeerConnection, transfer: String, png: ByteArray, hash: String): Boolean {
        if (!connection.send(Envelope(MessageType.FILE_OFFER, file = FileMessage(transfer, name = "Clipboard.png", size = png.size.toLong(), kind = CLIPBOARD_KIND)))) return false
        // Straight into frames, behind live messages, without copying the picture or making Strings.
        if (connection.binary) {
            val header = FileChunkFrame.binaryPrefix(transfer)
            var at = 0
            while (at < png.size) {
                val count = minOf(CHUNK_BYTES, png.size - at)
                val frame = ByteArray(header.size + count)
                header.copyInto(frame)
                png.copyInto(frame, header.size, at, at + count)
                if (!connection.sendFrame(OutFrame(frame, frame.size, binary = true))) return false
                at += count
            }
            return connection.send(Envelope(MessageType.FILE_DONE, file = FileMessage(transfer, sha256 = hash)))
        }
        val prefix = FileChunkFrame.prefix(transfer)
        val chunk = ByteArray(CHUNK_BYTES)
        var offset = 0
        while (offset < png.size) {
            val count = minOf(CHUNK_BYTES, png.size - offset)
            png.copyInto(chunk, 0, offset, offset + count)
            val frame = ByteArray(FileChunkFrame.capacity(prefix, count))
            val length = FileChunkFrame.build(frame, prefix, chunk, count)
            if (!connection.sendFrame(OutFrame(frame, length))) return false
            offset += count
        }
        return connection.send(Envelope(MessageType.FILE_DONE, file = FileMessage(transfer, sha256 = hash)))
    }

    /** Turns syncing with other computers on or off. Off closes their connections. */
    fun setComputersEnabled(on: Boolean) {
        computersOn = on
        computerDialer?.cancel()
        computerDialer = null
        if (on) {
            // The network doesn't report every reconnect, so lost connections are retried on a timer.
            computerDialer = scope.launch(Dispatchers.IO) {
                while (true) {
                    dialComputers()
                    delay(COMPUTER_RETRY_MS)
                }
            }
        } else {
            synchronized(lock) { connections.values.filter { isComputer(it) }.toList() }.forEach { it.close() }
        }
    }

    /** A computer announced on the network, with the `id` and `platform` from its TXT record. */
    fun computerFound(id: String, host: String, port: Int) {
        if (id == deviceId || id.isEmpty()) return
        computerAddresses[id] = host to port
        if (computersOn) scope.launch(Dispatchers.IO) { dialComputers() }
    }

    fun computerLost(id: String) { computerAddresses.remove(id) }

    /** Only the computer with the smaller ID dials, so two computers hold one connection between them. */
    private fun dialComputers() {
        for ((id, address) in computerAddresses.toMap()) {
            if (!computersOn || deviceId >= id || synchronized(lock) { connections.containsKey(id) || dialing.contains(id) }) continue
            dialComputer(address.first, address.second, expectedId = id)
        }
    }

    /**
     * Connects to a computer at an address, such as one typed in or, in tests, one on this machine.
     * With [expectedId], a different computer answering at that address is hung up on.
     */
    fun dialComputer(host: String, port: Int, expectedId: String? = null) {
        if (!computersOn) return
        val key = expectedId ?: "$host:$port"
        if (!synchronized(lock) { dialing.add(key) }) return
        scope.launch(Dispatchers.IO) {
            try {
                val connection = PeerConnection.connect(sslContext, host, port)
                val id = connection.peerId
                if ((expectedId != null && id != expectedId) || id == deviceId || synchronized(lock) { connections.containsKey(id) }) {
                    connection.close()
                    return@launch
                }
                connection.send(Envelope(MessageType.HELLO, hello = localHello(synchronized(lock) { records.containsKey(id) })))
                CoreLog.i(TAG, "Connected to computer $host:$port")
                // Still "dialing" until this connection ends, so no second dial races its hello.
                serve(connection)
            } catch (error: Exception) {
                CoreLog.w(TAG, "Can't reach computer $host:$port", error)
            } finally {
                synchronized(lock) { dialing -= key }
            }
        }
    }

    private val dialing = HashSet<String>()

    /** Asks another computer to pair. It shows a code, which is typed here with [submitCode]. */
    fun requestPairing(id: String): Unit = lockedInBackground {
        val connection = connections[id] ?: return@lockedInBackground
        val hello = connection.hello ?: return@lockedInBackground
        if (records.containsKey(id) || !isComputer(connection)) return@lockedInBackground
        // An update notice isn't a pairing: it gives way.
        if (_pairing.value?.takeIf { !it.outdated } != null) return@lockedInBackground
        dropPake()
        if (hello.protocolVersion < Pairing.PAKE_SINCE) {
            // Its DyskLink only pairs the old way, which this one no longer accepts.
            _pairing.value = HubPairing(id, hello.name, code = "", typing = true, outdated = true, error = Pairing.updateMessage(hello.name))
        } else {
            val typing = Pake.Typer()
            typer = typing
            send(connection, Envelope(MessageType.PAIR_REQUEST, pake = typing.request))
            _pairing.value = HubPairing(id, hello.name, code = "", typing = true)
        }
        startPairingTimeout(id)
    }

    /** The code shown on the other computer. A code already on its way isn't sent again. */
    fun submitCode(typed: String): Unit = lockedInBackground {
        val prompt = _pairing.value?.takeIf { it.typing && !it.localAccepted && !it.outdated } ?: return@lockedInBackground
        val digits = typed.filter(Char::isDigit)
        if (digits.length != 6) return@lockedInBackground
        val connection = connections[prompt.phoneId] ?: return@lockedInBackground
        val typing = typer ?: return@lockedInBackground
        _pairing.value = prompt.copy(error = null, localAccepted = true)
        // The other side's share hasn't come yet: the code goes as soon as it does.
        if (!typing.ready) pendingCode = digits else sendCode(connection, prompt, digits)
    }

    /** The CPace answer for a typed code, with a fresh secret each time. */
    private fun Outgoing.sendCode(connection: PeerConnection, prompt: HubPairing, code: String) {
        val accept = typer?.answer(code, Pake.channel(identity.deviceId, prompt.phoneId))
        if (accept == null) {
            CoreLog.w(TAG, "PAIRING_BAD_PAKE ${prompt.phoneId}")
            return rejectCurrentPairing()
        }
        send(connection, Envelope(MessageType.PAIR_ACCEPT, pake = accept))
    }

    private fun startPairingTimeout(id: String) {
        pairingTimeout?.cancel()
        pairingTimeout = scope.launch(Dispatchers.IO) {
            delay(PhoneLinkProtocol.PAIRING_TIMEOUT_MS)
            locked { if (_pairing.value?.phoneId == id) rejectCurrentPairing() }
        }
    }

    /** The CPace math for a Shower runs once the lock is released; its `pair.pake` goes if the code still shows. */
    private fun Outgoing.buildShower(id: String, plan: ShowerSlot.Plan) = afterwards {
        val built = plan.build()
        locked {
            if (_pairing.value?.takeIf { !it.typing && !it.outdated }?.phoneId == id && shower.install(plan, built)) {
                send(connections[id], Envelope(MessageType.PAIR_PAKE, pake = built.message))
            } else {
                built.close()
            }
        }
    }

    private fun Outgoing.completePairing(connection: PeerConnection, hello: Hello) {
        records[connection.peerId] = Record(hello.name, hello.model, hello.platform)
        saveRecords()
        pairingGuard.succeeded(connection.peerId)
        clearPairing()
        send(connection, Envelope(MessageType.INFO, info = localInfo()))
        if (hello.platform !in PhoneLinkProtocol.COMPUTERS) {
            send(connection, commandList(connection.peerId))
            notchEnvelope()?.let { send(connection, it) }
        }
        CoreLog.i(TAG, "PAIRED ${hello.name}")
        publish()
    }

    // Pairing: the phone asks, this computer shows a code, and the phone sends proof that it was typed.

    private fun pairingMessage(connection: PeerConnection, envelope: Envelope): Unit = locked {
        val id = connection.peerId
        val hello = connection.hello ?: return@locked
        when (envelope.type) {
            MessageType.PAIR_REQUEST -> {
                if (records.containsKey(id)) return@locked
                // An update notice for another device doesn't hold the single pairing slot.
                val current = _pairing.value?.takeIf { !it.outdated || it.phoneId == id }
                if (current != null && current.phoneId != id) {
                    send(connection, Envelope(MessageType.PAIR_REJECT))
                    return@locked
                }
                // Asked again while the code shows: the same code stays, and so do the wrong tries. A new request
                // (a new nonce) gets a new share for that same code.
                if (current != null && !current.typing && !current.outdated) {
                    val nonce = Pake.requestNonce(envelope.pake) ?: return@locked
                    // A new nonce gets a new share only after a wrong code or now and then, so requests can't
                    // keep this computer doing the math.
                    when (val renewal = shower.renew(current.code, nonce, Pake.channel(id, identity.deviceId))) {
                        is ShowerSlot.Renewal.Resend -> send(connection, Envelope(MessageType.PAIR_PAKE, pake = renewal.message))
                        is ShowerSlot.Renewal.Build -> buildShower(id, renewal.plan)
                        ShowerSlot.Renewal.Ignore -> CoreLog.w(TAG, "PAIRING_REQUEST_IGNORED $id")
                    }
                    return@locked
                }
                // Both computers asked at once: the smaller ID shows the code, the other types it.
                if (current != null && current.typing && identity.deviceId > id) return@locked
                val wait = pairingGuard.waitMs(id)
                if (wait > 0) {
                    if (current != null) clearPairing()
                    send(connection, Envelope(MessageType.PAIR_REJECT, error = PairingGuard.waitMessage(wait)))
                    return@locked
                }
                // Older apps only know the old code check, which a device in the middle could brute-force. The
                // notice shows only while nothing else does, and gives way to a real pairing.
                val nonceT = Pake.requestNonce(envelope.pake)
                if (hello.protocolVersion < Pairing.PAKE_SINCE || nonceT == null) {
                    send(connection, Envelope(MessageType.PAIR_REJECT, error = Pairing.UPDATE_REQUIRED))
                    if (current == null && _pairing.value == null) {
                        dropPake()
                        _pairing.value = HubPairing(id, hello.name, "", outdated = true, error = Pairing.updateMessage(hello.name))
                        startPairingTimeout(id)
                    }
                    return@locked
                }
                dropPake()
                val code = Pairing.newCode()
                _pairing.value = HubPairing(id, hello.name, code)
                buildShower(id, shower.start(code, nonceT, Pake.channel(id, identity.deviceId)))
                startPairingTimeout(id)
            }
            MessageType.PAIR_PAKE -> {
                val prompt = _pairing.value?.takeIf { it.phoneId == id && it.typing && !it.outdated } ?: return@locked
                val typing = typer ?: return@locked
                if (!typing.received(envelope.pake)) {
                    CoreLog.w(TAG, "PAIRING_BAD_PAKE $id")
                    return@locked rejectCurrentPairing()
                }
                // Only a code still waiting to go: one that was answered or taken back isn't sent.
                val code = pendingCode
                pendingCode = null
                if (code != null && prompt.localAccepted) sendCode(connection, prompt, code)
            }
            MessageType.PAIR_ACCEPT -> {
                val prompt = _pairing.value?.takeIf { it.phoneId == id } ?: return@locked
                // Typed here: saved only once the other computer proves it derived the same key from our code.
                if (prompt.typing) {
                    if (prompt.localAccepted && pendingCode == null) {
                        if (typer?.confirms(envelope.pake) == true) {
                            completePairing(connection, hello)
                        } else {
                            CoreLog.w(TAG, "PAIRING_BAD_CONFIRM $id")
                            rejectCurrentPairing()
                        }
                    }
                    return@locked
                }
                if (prompt.outdated) return@locked
                val showing = shower.shower ?: return@locked
                // Only the old key: an older app, which can't pair any more. Not counted as a wrong code.
                val pake = envelope.pake ?: return@locked rejectCurrentPairing(Pairing.UPDATE_REQUIRED)
                val reply = showing.check(pake)
                if (reply != null) {
                    send(connection, Envelope(MessageType.PAIR_ACCEPT, pake = reply))
                    completePairing(connection, hello)
                } else if (pairingGuard.wrong(id)) {
                    CoreLog.w(TAG, "PAIRING_LOCKED $id")
                    rejectCurrentPairing(PairingGuard.waitMessage(pairingGuard.waitMs(id)))
                } else {
                    shower.wrongCode()
                    _pairing.value = prompt.copy(attempts = prompt.attempts + 1, error = "A wrong code was typed on ${prompt.phoneName}.")
                    send(connection, Envelope(MessageType.PAIR_REJECT, error = Pairing.WRONG_CODE))
                }
            }
            MessageType.PAIR_REJECT -> {
                val prompt = _pairing.value?.takeIf { it.phoneId == id } ?: return@locked
                if (prompt.typing && envelope.error == Pairing.WRONG_CODE) {
                    typer?.wrongCode()
                    pendingCode = null
                    _pairing.value = prompt.copy(attempts = prompt.attempts + 1, localAccepted = false, error = "That code is wrong. Check it on ${prompt.phoneName}.")
                } else if (prompt.typing && envelope.error == Pairing.UPDATE_REQUIRED) {
                    dropPake()
                    _pairing.value = prompt.copy(localAccepted = false, outdated = true, error = Pairing.updateMessage(prompt.phoneName))
                } else {
                    clearPairing()
                }
            }
        }
    }

    /** Closes the code without pairing. */
    fun rejectPairing(): Unit = lockedInBackground { rejectCurrentPairing() }

    private fun Outgoing.rejectCurrentPairing(error: String? = null) {
        val prompt = _pairing.value ?: return
        send(connections[prompt.phoneId], Envelope(MessageType.PAIR_REJECT, error = error))
        clearPairing()
    }

    private fun dropPake() {
        shower.clear()
        typer?.close()
        typer = null
        pendingCode = null
    }

    private fun clearPairing() {
        pairingTimeout?.cancel()
        pairingTimeout = null
        dropPake()
        _pairing.value = null
    }

    // Actions

    /** Sends text copied here to every paired phone that's connected, or only to [to]. */
    fun sendClipboard(text: String, to: String? = null) {
        if (text.isEmpty() || text.length > PhoneLinkProtocol.MAX_CLIPBOARD_CHARS) return
        val envelope = Envelope(MessageType.CLIPBOARD, text = text)
        pairedConnections().filter { (id, _) -> to == null || id == to }
            .forEach { (_, connection) -> connection.post(envelope) }
    }

    fun ring(phoneId: String, on: Boolean) {
        if (on) _ringing.update { it + phoneId } else _ringing.update { it - phoneId }
        scope.launch(Dispatchers.IO) { send(phoneId, Envelope(MessageType.RING, ring = if (on) "start" else "stop")) }
    }

    /** Asks the phone to stop sending its sound. */
    fun stopPhoneSound() {
        val id = soundFrom ?: return
        soundFrom = null
        events.phoneSoundEnded(id)
        scope.launch(Dispatchers.IO) { send(id, Envelope(MessageType.AUDIO_STOP)) }
    }

    fun dismissNotification(phoneId: String, key: String) {
        _notifications.update { all -> all + (phoneId to all[phoneId].orEmpty().filter { it.key != key }) }
        scope.launch(Dispatchers.IO) { send(phoneId, Envelope(MessageType.NOTIFICATION_DISMISS, key = key)) }
    }

    fun sendFile(phoneId: String, file: File, onFinished: ((Boolean) -> Unit)? = null) {
        fileTransfers.sendFile(phoneId, file.name, file.length(), onFinished = onFinished) { file.inputStream() }
    }

    fun unpair(phoneId: String): Unit = lockedInBackground {
        send(connections[phoneId], Envelope(MessageType.UNPAIR))
        removeRecord(phoneId)
        publish()
    }

    private fun pairedConnections(): List<Pair<String, PeerConnection>> = synchronized(lock) {
        connections.filterKeys { records.containsKey(it) }.toList()
    }

    private fun pairedConnection(id: String): PeerConnection? =
        synchronized(lock) { connections[id]?.takeIf { records.containsKey(id) } }

    /** Waits like a direct write (see [PeerConnection.send]). */
    private fun send(id: String, envelope: Envelope): Boolean = pairedConnection(id)?.send(envelope) ?: false

    /** Queues without waiting, for the reading threads. */
    private fun post(id: String, envelope: Envelope): Boolean = pairedConnection(id)?.post(envelope) ?: false

    // Records

    private fun removeRecord(id: String) {
        endServices(id, connected = true)
        if (records.remove(id) != null) saveRecords()
        infos.remove(id)
        features.remove(id)
        _notifications.update { it - id }
        dropPhoneMedia(id)
        _sms.update { it - id }
        _galleries.update { it - id }
        _contacts.update { it - id }
        _folders.update { it - id }
    }

    private fun loadRecords(): MutableMap<String, Record> =
        runCatching { protocolJson.decodeFromString(recordsSerializer, recordsFile.readText()).toMutableMap() }
            .getOrNull() ?: mutableMapOf()

    private fun saveRecords() {
        runCatching { recordsFile.writeText(protocolJson.encodeToString(recordsSerializer, records)) }
    }

    /** Paired phones first, then phones that are connected but not paired. */
    private fun publish() {
        val ids = (records.keys + connections.keys).distinct()
        _phones.value = ids.map { id ->
            val record = records[id]
            val hello = connections[id]?.hello
            val platform = record?.platform ?: hello?.platform ?: "android"
            HubPhone(
                id = id,
                name = hello?.name ?: record?.name ?: "Phone",
                model = hello?.model ?: record?.model ?: "",
                isPaired = record != null,
                isConnected = connections[id]?.hello != null,
                info = infos[id],
                features = features[id].orEmpty(),
                platform = platform,
                caps = hello?.caps.orEmpty(),
                viaUsb = platform !in PhoneLinkProtocol.COMPUTERS && connections[id]?.let { it.hello != null && it.isUsb } == true,
            )
        }.sortedWith(compareByDescending<HubPhone> { it.isPaired }.thenBy { it.name })
    }

    private companion object {
        const val TAG = "DyskLink"
        const val MAX_NOTIFICATIONS = 100
        const val MAX_GALLERY_ITEMS = 3_000
        const val MAX_GALLERY_DOWNLOADS = 200
        const val MAX_SMS = 2_000
        const val MAX_SMS_SENT = 50
        const val UPDATE_PHONE = "Update DyskLink on the phone to show it on this PC."
        const val CLIPBOARD_KIND = "clipboard"
        const val SCAN_KIND = "scan"
        const val MAX_CONTACTS = 20_000
        const val MAX_FOLDER_ENTRIES = 10_000
        const val MAX_FILE_DOWNLOADS = 200
        /** As on the Mac: bigger pictures stay where they were copied. */
        const val MAX_CLIP_IMAGE_BYTES = 24L * 1024 * 1024
        const val CHUNK_BYTES = 64 * 1024
        const val COMPUTER_RETRY_MS = 20_000L
        const val PING_INTERVAL_MS = 25_000L
        val recordsSerializer = MapSerializer(String.serializer(), Record.serializer())
    }
}
