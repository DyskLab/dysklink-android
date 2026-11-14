package id.phonelink.android.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

@Serializable
data class PairedRecord(
    val name: String,
    val platform: String,
    val model: String,
    val pairedAt: Long,
    /** Kept from the Mac's info, so it can be woken while it's away. */
    val wakeMacs: List<String> = emptyList(),
    val wakeBroadcast: String? = null,
    val wakeEnabled: Boolean? = null,
)

data class Peer(
    val id: String,
    val name: String,
    val platform: String,
    val model: String,
    val isPaired: Boolean,
    val isConnected: Boolean,
    val info: DeviceInfo?,
    /** Connected through a USB cable instead of Wi-Fi. */
    val viaUsb: Boolean = false,
    /** A Mac whose address is known, so Wake-on-LAN can be tried. */
    val canWake: Boolean = false,
    /** The Mac's "Wake for network access", when known. */
    val wakeEnabled: Boolean? = null,
    /** What its hello listed in `caps`, while connected. */
    val caps: List<String> = emptyList(),
    /**
     * Connected, but nothing came from it for two heartbeats: a Mac that went to sleep without closing the
     * connection. The connection stays until the read timeout; the page offers Wake Mac meanwhile.
     */
    val maybeAsleep: Boolean = false,
    /** The latest Wake Mac try for this device, while it matters. */
    val wake: WakeState? = null,
) {
    /** Connected and answering. */
    val isAwake: Boolean get() = isConnected && !maybeAsleep

    /** Another phone, paired for files, clipboard, ringing, and battery only. */
    val isPhone: Boolean get() = platform !in PhoneLinkProtocol.COMPUTERS
    /** A Mac, Windows, or Linux computer. */
    val isComputer: Boolean get() = !isPhone
    /** A Mac, with the Mac-only features such as its screen, notch, and charging. */
    val isMac: Boolean get() = platform == "macos"
    /** A phone whose screen this phone can view and control (1.3 and newer). */
    val canViewScreen: Boolean get() = isPhone && PhoneLinkProtocol.CAP_P2P_SCREEN in caps
    /** A phone whose camera this phone can use as a remote camera (1.3 and newer). */
    val canUseCamera: Boolean get() = isPhone && PhoneLinkProtocol.CAP_P2P_CAMERA in caps
    /** A phone that can forward its notifications and calls here, and send its own on request (1.4 and newer). */
    val canNotify: Boolean get() = isPhone && PhoneLinkProtocol.CAP_P2P_NOTIFY in caps
    /** A phone that plays push-to-talk voice from this one (1.4 and newer). */
    val canTalk: Boolean get() = isPhone && PhoneLinkProtocol.CAP_P2P_TALK in caps
    /** A phone that plays this phone's sound (1.4 and newer). */
    val canPlaySound: Boolean get() = isPhone && PhoneLinkProtocol.CAP_P2P_SOUND in caps
    /** A phone that takes links to open (1.5 and newer). */
    val canOpen: Boolean get() = isPhone && PhoneLinkProtocol.CAP_P2P_OPEN in caps
    /** A phone whose music this phone can control (1.5 and newer). */
    val canMedia: Boolean get() = isPhone && PhoneLinkProtocol.CAP_P2P_MEDIA in caps
    /** A phone that shares its screen time, and whose battery alerts show here (1.5 and newer). */
    val canMonitor: Boolean get() = isPhone && PhoneLinkProtocol.CAP_P2P_MONITOR in caps
    /** A phone this one can silence (1.5 and newer). */
    val canQuiet: Boolean get() = isPhone && PhoneLinkProtocol.CAP_P2P_QUIET in caps
    /** A phone that answers with its location and flashes while ringing (1.5 and newer). */
    val canLocate: Boolean get() = isPhone && PhoneLinkProtocol.CAP_P2P_LOCATE in caps
    /** A phone whose SMS this phone can read and send through (1.6 and newer). */
    val canSms: Boolean get() = isPhone && PhoneLinkProtocol.CAP_P2P_SMS in caps
    /** A phone this one can move to: contacts, photos, and files (1.6 and newer). */
    val canMove: Boolean get() = isPhone && PhoneLinkProtocol.CAP_P2P_MOVE in caps
    /** A phone that keeps this phone's photo backup (1.6 and newer). */
    val canBackup: Boolean get() = isPhone && PhoneLinkProtocol.CAP_P2P_BACKUP in caps
    /** A Windows or Linux PC that shows this phone's screen, sent to it as JPEG frames (see [PcRules]). */
    val pcScreen: Boolean get() = PcRules.jpeg(platform, caps, PhoneLinkProtocol.CAP_PC_SCREEN)
    /** A Windows or Linux PC that shows this phone's camera, sent to it as JPEG frames. */
    val pcCamera: Boolean get() = PcRules.jpeg(platform, caps, PhoneLinkProtocol.CAP_PC_CAMERA)
    /** A Mac, or a PC that sends its sound to this phone (`pc-sound-out`). */
    val canHear: Boolean get() = isComputer && PcRules.serves(platform, caps, PhoneLinkProtocol.CAP_PC_SOUND_OUT)
    /** A Mac, or a PC that lists commands to run (`pc-commands`). */
    val canRunCommands: Boolean get() = isComputer && PcRules.serves(platform, caps, PhoneLinkProtocol.CAP_PC_COMMANDS)
    /** A Mac, or a PC that opens a terminal (`pc-terminal`). */
    val canOpenTerminal: Boolean get() = isComputer && PcRules.serves(platform, caps, PhoneLinkProtocol.CAP_PC_TERMINAL)
    /** A Mac, or a PC that shows its own screen here as JPEG frames (`pc-screen-out`). */
    val canSeeComputerScreen: Boolean get() = isComputer && PcRules.serves(platform, caps, PhoneLinkProtocol.CAP_PC_SCREEN_OUT)
    /** A Mac, or a PC that uses this phone as an extra display (`pc-display`, listed only where the PC can add a monitor). */
    val canExtend: Boolean get() = isComputer && PcRules.serves(platform, caps, PhoneLinkProtocol.CAP_PC_DISPLAY)
    /** A Mac, or a PC that runs the Stream Deck's shortcuts as its own (`pc-deck`). */
    val canDeck: Boolean get() = isComputer && PcRules.serves(platform, caps, PhoneLinkProtocol.CAP_PC_DECK)
    /** A Mac, or a PC with the notch tools: Todo, water, and Pomodoro (`pc-notch`). */
    val canNotch: Boolean get() = isComputer && PcRules.serves(platform, caps, PhoneLinkProtocol.CAP_PC_NOTCH)
    /** A Mac, or a PC that types scanned codes and voice typing (`pc-type`). */
    val canType: Boolean get() = isComputer && PcRules.serves(platform, caps, PhoneLinkProtocol.CAP_PC_TYPE)
    /** A Mac, or a PC that keeps document scans (`pc-scan`). */
    val canTakeScans: Boolean get() = isComputer && PcRules.serves(platform, caps, PhoneLinkProtocol.CAP_PC_SCAN)
}

data class PairingPrompt(
    val peerId: String,
    val peerName: String,
    /** The code this phone shows. Empty when this phone is the one typing. */
    val code: String,
    /** True when the other device asked, so this phone shows the code and the other types it. */
    val startedByPeer: Boolean,
    /** This phone typed the code and waits for the answer. */
    val localAccepted: Boolean = false,
    val error: String? = null,
    val attempts: Int = 0,
    /** The other device has an older DyskLink that can't pair with CPace; [error] says to update it. */
    val outdated: Boolean = false,
) {
    val showsCode: Boolean get() = startedByPeer
}

data class ManualTarget(val host: String, val port: Int) {
    val key: String get() = "manual:$host:$port"
    val label: String get() = "$host:$port"
}

/** Actions run on the phone when the Mac asks for them. */
interface DeviceActions {
    fun setClipboard(text: String)
    fun media(action: String)
    /** Rings loudly so the phone can be found, until [stopRinging] or a timeout. */
    fun ring(from: String) {}
    /** Rings, and with [flash] also flashes the torch while it rings (asked by another phone). */
    fun ring(from: String, flash: Boolean) = ring(from)
    fun stopRinging() {}
}

/** The notch data from one Mac, with the time it arrived so the Pomodoro can count down locally. */
data class NotchSnapshot(val state: NotchState, val receivedAt: Long)

/**
 * Keeps connections to every Mac found. UI state comes from the StateFlows in this class.
 * All state changes hold [lock] because each connection has its own coroutine.
 */
class ConnectionManager(
    private val directory: File,
    private val scope: CoroutineScope,
    private val localHello: (paired: Boolean) -> Hello,
    private val localInfo: () -> DeviceInfo,
    private val isUiVisible: () -> Boolean,
    private val actions: DeviceActions,
    fileSinks: FileSinkFactory,
    private val phoneData: PhoneData,
    /** How the platform keystore protects the pairing key, such as Android Keystore's KeyProtection. Null keeps it in a file. */
    keyStoreProtection: java.security.KeyStore.ProtectionParameter? = null,
) {
    private val lock = Any()
    private val identity = LocalIdentity.loadOrCreate(directory, keyStoreProtection).also {
        CoreLog.i("PhoneLink", "IDENTITY keystore=${it.isHardwareBacked}")
    }
    private val sslContext = identity.sslContext()

    private val targets = ConcurrentHashMap<String, Job>()
    private val connections = HashMap<String, PeerConnection>()
    private val records = HashMap<String, PairedRecord>()
    private val infos = HashMap<String, DeviceInfo>()
    private val activeHosts = HashSet<String>()
    /** The address each connection came from, so a USB connection can take over from Wi-Fi. */
    private val connectionHosts = HashMap<String, String>()
    private val autoAsked = HashSet<String>()
    private val declined = HashSet<String>()
    private var pairingTimeout: Job? = null
    private val pairingGuard = PairingGuard()
    /** CPace state of the prompt: [shower] while this phone shows the code, [typer] while it types it. */
    private val shower = ShowerSlot()
    private var typer: Pake.Typer? = null
    /** A code typed before the other side's `pair.pake` arrived. */
    private var pendingCode: String? = null
    /** Connections that went quiet for [QUIET_MS]: see [Peer.maybeAsleep]. Replaced whole, never changed in place. */
    @Volatile private var quiet: Set<String> = emptySet()
    /** The latest wake try of each Mac, and what watches it. */
    private val wakes = HashMap<String, WakeState>()
    private val wakeWatchers = HashMap<String, Job>()
    private val redial = RedialKicks()

    /** The phone's Wi-Fi for Wake-on-LAN, set by the app. Null, or returning null, sends without binding. */
    @Volatile var wakeRoute: (() -> WakeRoute?)? = null

    /** Asks the app to look for devices on the network again now, from its shortest wait. */
    @Volatile var onRescanWanted: (() -> Unit)? = null

    /** Devices connected over the USB cable now. Their Wi-Fi dialing waits until the cable is pulled. */
    private val usbPeers = MutableStateFlow<Set<String>>(emptySet())

    private val recordsFile = File(directory, "paired.json")
    private val manualFile = File(directory, "manual.json")
    private val declinedFile = File(directory, "declined.json")

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _prompt = MutableStateFlow<PairingPrompt?>(null)
    val prompt: StateFlow<PairingPrompt?> = _prompt.asStateFlow()

    private val _manualTargets = MutableStateFlow<List<ManualTarget>>(emptyList())
    val manualTargets: StateFlow<List<ManualTarget>> = _manualTargets.asStateFlow()

    private val fileSinks = fileSinks

    val fileTransfers = FileTransfers(
        scope, fileSinks, File(directory, "transfers.json"),
        linkFor = ::pairedConnection, post = ::postTo, send = ::sendTo,
        admit = { peerId, offer -> admitFile(peerId, offer) },
        saved = { peerId, offer, sha -> fileSaved(peerId, offer, sha) },
    )

    /** What each phone sent here for backup or a move, so a copy isn't taken twice. */
    private val received = ReceivedIndex(directory)

    /** What each paired phone may do with this phone: screen, camera, voice, sound, and what this phone sends it. */
    val phoneAccess = PhoneAccess(directory)

    /** This phone's notifications and calls, sent to the phones whose switch is on here. */
    val relay = NotificationRelay(
        scope, phoneAccess, phoneData,
        phones = { phonesWith(PhoneLinkProtocol.CAP_P2P_NOTIFY) },
        send = { peerId, envelope -> postTo(peerId, envelope) },
    )

    /** Other phones' notifications and calls, shown here while they're connected. */
    val mirror = NotificationMirror()

    /** The app's side of links, music, quiet, and location for another phone. Null: answered as not available. */
    @Volatile var phoneRemote: PhoneRemote? = null

    /** A phone asks for a link, music, screen time, quiet, or location the first time: ask the user here. */
    @Volatile var phoneRequestAskSink: ((String, PeerAsk) -> Unit)? = null

    private val answers = RemoteAnswers(phoneData, { phoneRemote }, { localHello(true).name })

    /** The SMS other phones sent through this one. */
    val smsLog = SmsSendLog(directory)

    /** This phone's SMS for the phones allowed here (1.6). */
    val smsShare = SmsShare(phoneAccess, phoneData, smsLog, send = { peerId, envelope -> postTo(peerId, envelope) }, localName = { localHello(true).name })

    /** Other phones' SMS, read from here. */
    val smsRemote = SmsRemote(directory)

    /** A move from this phone to a new one (1.6). */
    val mover by lazy {
        PhoneMover(
            directory, scope, fileTransfers,
            ask = { peerId, envelope -> askPhone(peerId, envelope) },
            post = ::postTo,
            isConnected = { peerId -> pairedConnection(peerId) != null },
        )
    }

    /** An old phone finished moving here: show what came. */
    @Volatile var phoneMoveSink: ((String, MoveSummary) -> Unit)? = null

    /** A phone's photo backup or move stopped because this phone is nearly full: say so here. */
    @Volatile var lowSpaceSink: ((String, String?) -> Unit)? = null
    private val awaited = AwaitedReplies()

    /** This phone's battery alerts for each paired phone. */
    val monitor = PhoneMonitor(directory)

    private val _batteryAlerts = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, BatteryAlert>>(extraBufferCapacity = 16)
    /** A paired phone ran low or charged full, by this phone's alert settings for it. */
    val batteryAlerts: kotlinx.coroutines.flow.SharedFlow<Pair<String, BatteryAlert>> = _batteryAlerts

    private val _remoteReplies = MutableStateFlow<Map<String, Map<PeerAsk, Envelope>>>(emptyMap())
    /** The last answer from each phone to each kind of request sent from here: `link.result`, `media.state`, ... */
    val remoteReplies: StateFlow<Map<String, Map<PeerAsk, Envelope>>> = _remoteReplies.asStateFlow()

    val phone = PhoneBridge(
        directory, scope, phoneData, fileTransfers,
        pairedPeers = { synchronized(lock) { connections.keys.filter { it in records && !isPhone(it) } } },
        // New SMS go to Macs, and to PCs that read SMS (pc-sms); older PC builds never asked for them.
        smsPeers = {
            synchronized(lock) {
                connections.filter { (id, connection) ->
                    id in records && !isPhone(connection) && PcRules.receives(records[id]?.platform, connection.hello?.caps, PhoneLinkProtocol.CAP_PC_SMS)
                }.keys.toList()
            }
        },
        relay = relay,
        smsShare = smsShare,
        send = { peerId, envelope -> postTo(peerId, envelope) },
    )

    private val _commands = MutableStateFlow<Map<String, List<CommandItem>>>(emptyMap())
    val commands: StateFlow<Map<String, List<CommandItem>>> = _commands.asStateFlow()

    private val _commandResult = MutableStateFlow<CommandResult?>(null)
    val commandResult: StateFlow<CommandResult?> = _commandResult.asStateFlow()

    private val clipboardOffFile = File(directory, "clipboard_off")
    private val _clipboardEnabled = MutableStateFlow(!clipboardOffFile.exists())
    val clipboardEnabled: StateFlow<Boolean> = _clipboardEnabled.asStateFlow()
    private val clipboardAutoFile = File(directory, "clipboard_auto")
    private val _clipboardAuto = MutableStateFlow(clipboardAutoFile.exists())

    /** Off until the user turns it on, because it needs the Accessibility service to watch for copy actions. */
    val clipboardAuto: StateFlow<Boolean> = _clipboardAuto.asStateFlow()
    @Volatile private var lastClipboard: String? = null
    private var pendingClipboard: PendingClipboard? = null

    private val _notch = MutableStateFlow<Map<String, NotchSnapshot>>(emptyMap())
    val notch: StateFlow<Map<String, NotchSnapshot>> = _notch.asStateFlow()

    /** Macs that are ringing because this phone asked them to. */
    private val _ringingMacs = MutableStateFlow<Set<String>>(emptySet())
    val ringingMacs: StateFlow<Set<String>> = _ringingMacs.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    val localDeviceId: String get() = identity.deviceId

    /** The device this phone's screen is shared with now, set by the app. Taps from a phone need this to be it. */
    @Volatile var screenSharedWith: String? = null

    /** What this phone asked each other phone for: only those phones' video and photos are taken. */
    private val watching = HashMap<String, MutableSet<P2pKind>>()

    /** Another phone's screen and camera messages, on the reading thread, while this phone watches it. */
    @Volatile var phoneViewSink: ((String, Envelope) -> Unit)? = null

    /** What this phone plays from each phone now: its voice, its sound. */
    private val hearing = HashMap<String, MutableSet<AudioKind>>()
    /** Phones this phone talks to now. */
    private val talkingTo = HashSet<String>()

    /**
     * Voice and sound between phones, on the reading thread: `talk.*` and `audio.*` from a phone that plays here
     * (once allowed), and `talk.stopped` to this phone as the one talking. The sound sent from here is ended through
     * [audioSink] with `audio.stop`, as for a Mac.
     */
    @Volatile var phoneAudioSink: ((String, Envelope) -> Unit)? = null

    /** A phone wants to talk or play sound here for the first time: ask the user (Allow, Not now, Never). */
    @Volatile var phoneAskSink: ((String, AudioKind) -> Unit)? = null

    private val phonesOffFile = File(directory, "phones_off")
    private val _phonesEnabled = MutableStateFlow(!phonesOffFile.exists())
    /** Whether other phones can find and connect to this one. */
    val phonesEnabled: StateFlow<Boolean> = _phonesEnabled.asStateFlow()
    private var server: java.net.ServerSocket? = null
    private val _serverPort = MutableStateFlow<Int?>(null)
    /** The port other phones connect to, or null while not listening. Advertised over NSD. */
    val serverPort: StateFlow<Int?> = _serverPort.asStateFlow()

    init {
        loadRecords()
        loadManualTargets()
        loadDeclined()
        synchronized(lock) { publishPeers() }
        _manualTargets.value.forEach { startTarget(it.key, it.host, it.port) }
    }

    // Connection targets

    fun addDiscovered(serviceName: String, host: String, port: Int, attributes: Map<String, String> = emptyMap()) {
        val key = "nsd:$serviceName"
        if (!shouldDial(identity.deviceId, attributes, _phonesEnabled.value)) return
        targets.remove(key)?.cancel()
        if (attributes["platform"] == "android") phoneTargets.add(key) else phoneTargets.remove(key)
        startTarget(key, host, port)
    }

    /** Phones this one dials, closed when phone connections are turned off. */
    private val phoneTargets = ConcurrentHashMap.newKeySet<String>()

    fun removeDiscovered(serviceName: String) {
        targets.remove("nsd:$serviceName")?.cancel()
    }

    fun addManual(host: String, port: Int) {
        val target = ManualTarget(host.trim(), port)
        if (target.host.isEmpty() || _manualTargets.value.contains(target)) return
        _manualTargets.value = _manualTargets.value + target
        saveManualTargets()
        startTarget(target.key, target.host, target.port)
    }

    fun removeManual(target: ManualTarget) {
        _manualTargets.value = _manualTargets.value - target
        saveManualTargets()
        targets.remove(target.key)?.cancel()
    }

    private fun startTarget(key: String, host: String, port: Int) {
        targets[key] = scope.launch(Dispatchers.IO) {
            var backoffMs = MIN_BACKOFF_MS
            var peerId: String? = null
            while (isActive) {
                // A wake signal sent from here to this device while it dials counts too.
                val kickedAfter = redial.latest
                // While the cable carries this device, dialing it over Wi-Fi only gets refused. Wait for the cable to go.
                val known = peerId
                if (known != null && known in usbPeers.value) {
                    usbPeers.first { known !in it }
                    delay(USB_HANDBACK_MS)
                    backoffMs = MIN_BACKOFF_MS
                }
                var connectedFor: Long? = null
                try {
                    val connection = PeerConnection.connect(sslContext, host, port)
                    peerId = connection.peerId
                    _lastError.value = null
                    val started = System.currentTimeMillis()
                    // Duplicate connections are refused and don't reset the backoff, to avoid reconnecting nonstop.
                    if (runConnection(connection, host)) connectedFor = System.currentTimeMillis() - started
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    CoreLog.w(TAG, "Failed to connect to $host:$port", error)
                    if (key.startsWith("manual:")) {
                        _lastError.value = "Can't connect to $host:$port"
                    }
                }
                if (peerId?.let { it in usbPeers.value } == true) continue
                backoffMs = backoffAfter(backoffMs, connectedFor)
                // A wake signal to this device ends the wait, and the next ones start again from the shortest.
                val kicked = redial.wait(backoffMs, kickedAfter, peerId)
                backoffMs = nextBackoff(backoffMs, kicked)
            }
        }
    }

    /**
     * Keeps trying the USB port. Nothing listens there until a Mac runs `adb reverse`, so a failed try costs almost
     * nothing; once the cable is in, the phone moves to it within a few seconds. The computer may be a Mac or a
     * Windows or Linux PC: whichever ran `adb reverse` last gets the port. [port] is only changed by tests.
     */
    fun startUsb(port: Int = PhoneLinkProtocol.USB_PORT) {
        if (targets.containsKey(USB_KEY)) return
        targets[USB_KEY] = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val connection = runCatching {
                    PeerConnection.connect(sslContext, PhoneLinkProtocol.USB_HOST, port)
                }.getOrNull()
                if (connection != null) {
                    CoreLog.i(TAG, "USB connection")
                    runConnection(connection, PhoneLinkProtocol.USB_HOST)
                }
                delay(USB_RETRY_MS)
            }
        }
    }

    /** Tries an address from the network scan once, with no retry on failure. */
    fun probe(host: String, port: Int) {
        if (synchronized(lock) { host in activeHosts }) return
        scope.launch(Dispatchers.IO) {
            val connection = runCatching { PeerConnection.connect(sslContext, host, port) }
                .onFailure { CoreLog.w(TAG, "Failed to check $host:$port", it) }
                .getOrNull() ?: return@launch
            CoreLog.i(TAG, "Scan found $host")
            runConnection(connection, host)
        }
    }

    // Other phones

    /** Listens for phones that dial this one. Returns once listening; connections run on [scope]. */
    fun startServer() {
        if (!_phonesEnabled.value || server != null) return
        val socket = runCatching { java.net.ServerSocket(PhoneLinkProtocol.PHONE_PORT) }
            .recoverCatching { java.net.ServerSocket(0) }
            .onFailure { CoreLog.w(TAG, "Can't listen for phones", it) }
            .getOrNull() ?: return
        server = socket
        _serverPort.value = socket.localPort
        // Each phone reads on its own thread, not the shared IO pool; strangers are capped and must pair or go.
        InboundServer(
            socket, sslContext, scope, "phones",
            isPaired = { id -> synchronized(lock) { records.containsKey(id) } },
            isPairing = { id -> _prompt.value?.peerId == id },
        ) { connection, host -> runConnection(connection, host) }.start()
    }

    private fun stopServer() {
        runCatching { server?.close() }
        server = null
        _serverPort.value = null
    }

    fun setPhonesEnabled(enabled: Boolean) = onIo {
        _phonesEnabled.value = enabled
        if (enabled) {
            phonesOffFile.delete()
            startServer()
        } else {
            phonesOffFile.createNewFile()
            stopServer()
            phoneTargets.forEach { targets.remove(it)?.cancel() }
            phoneTargets.clear()
            synchronized(lock) { connections.values.filter { isPhone(it) }.toList() }.forEach { it.close() }
        }
    }

    /**
     * Inbound connections are always phones, since Macs never dial a phone. Otherwise a paired device is what it was
     * when it paired, whatever its hello says now; only an unpaired one goes by its hello.
     */
    private fun isPhone(connection: PeerConnection): Boolean = synchronized(lock) {
        if (connection.inbound) return@synchronized true
        val platform = records[connection.peerId]?.platform ?: connection.hello?.platform
        platform != null && platform !in PhoneLinkProtocol.COMPUTERS
    }

    /** Macs get everything they always did; a PC needs the `pc-*` cap for the messages [PcRules] lists. */
    private fun computerAllows(connection: PeerConnection, envelope: Envelope): Boolean {
        val type = envelope.type
        if (PcRules.capFor(type) == null) return true
        val platform = synchronized(lock) { records[connection.peerId]?.platform ?: connection.hello?.platform }
        if (!PcRules.allows(platform, connection.hello?.caps, type)) return false
        // Several fingers at once (game keys) also need `pc-gamekeys` from a PC.
        return type != MessageType.CONTROL || PcRules.allowsControl(platform, connection.hello?.caps, envelope.control?.kind)
    }

    private fun isPhone(id: String): Boolean = synchronized(lock) {
        connections[id]?.let(::isPhone) ?: (records[id]?.platform?.let { it !in PhoneLinkProtocol.COMPUTERS } == true)
    }

    /** Runs [block] under the lock and sends what it queued once the lock is released. */
    private fun <T> locked(block: Outgoing.() -> T): T {
        val out = Outgoing()
        val result = synchronized(lock) { out.block() }
        out.flush()
        return result
    }

    /** Returns false if the connection was refused because that device is already connected. */
    private fun runConnection(connection: PeerConnection, host: String): Boolean {
        val id = connection.peerId
        var replaced: PeerConnection? = null
        val greeting = synchronized(lock) {
            // The same Mac can be found through NSD, scanning, and a manual address at once.
            // Duplicates are closed before hello, so the Mac doesn't replace the active connection.
            val existing = connections[id]
            if (existing != null) {
                // A cable beats Wi-Fi: the USB connection takes over, and Wi-Fi comes back when it's unplugged.
                val usbTakesOver = host == PhoneLinkProtocol.USB_HOST && connectionHosts[id] != PhoneLinkProtocol.USB_HOST
                if (!usbTakesOver) return@synchronized null
                connectionHosts[id]?.let(activeHosts::remove)
                connections.remove(id)
                replaced = existing
            }
            connections[id] = connection
            connectionHosts[id] = host
            activeHosts.add(host)
            if (host == PhoneLinkProtocol.USB_HOST) usbPeers.value = usbPeers.value + id
            CoreLog.i(TAG, "Connected to $host")
            Envelope(MessageType.HELLO, hello = localHello(records.containsKey(id)))
        }
        if (greeting == null) {
            connection.close()
            return false
        }
        replaced?.let { old ->
            old.close()
            // What moved over the old connection ends with it; its own cleanup below skips, as it's no longer current.
            fileTransfers.onDisconnected(id)
        }
        connection.send(greeting)
        try {
            connection.readLoop { handle(connection, it) }
        } catch (_: Exception) {
            // Connection lost. Retries are handled by startTarget.
        } finally {
            connection.close()
            val current = synchronized(lock) { connections[id] === connection }
            if (current) fileTransfers.onDisconnected(id)
            // Between phones a lost connection ends the screen and camera at once, instead of waiting to resume.
            if (current && isPhone(connection)) endPhoneSessions(id)
            locked {
                if (connections[id] === connection) {
                    connections.remove(id)
                    connectionHosts.remove(id)
                    if (host == PhoneLinkProtocol.USB_HOST) usbPeers.value = usbPeers.value - id
                    _ringingMacs.value = _ringingMacs.value - id
                    activeHosts.remove(host)
                    if (_prompt.value?.peerId == id) clearPairing()
                    publishPeers()
                }
            }
        }
        return true
    }

    // Incoming messages

    private fun handle(connection: PeerConnection, envelope: Envelope) {
        // Phones only share files, clipboard, ringing, and status with each other. SMS, notifications,
        // control, and the terminal are for Macs.
        // Screen and camera between phones only pass the per-phone switches (see PhoneRules).
        if (connection.peerId in quiet) locked {
            quiet = quiet - connection.peerId
            publishPeers()
        }
        val fromPhone = isPhone(connection)
        if (fromPhone && !PHONE_MESSAGES.contains(envelope.type) && !envelope.type.startsWith("file.") && envelope.type !in PhoneRules.ALL) return
        if (fromPhone && !_phonesEnabled.value) return connection.close()
        if (fromPhone && envelope.type in PhoneRules.ALL) return handlePhoneShare(connection, envelope)
        // A Windows or Linux PC only gets the screen, camera, gallery, and SMS, and only sends commands and the
        // terminal, when its hello listed that cap.
        if (!fromPhone && !computerAllows(connection, envelope)) return
        // Files are written outside the lock so disk writes don't hold up other messages and actions.
        if (envelope.type.startsWith("file.")) {
            if (synchronized(lock) { records.containsKey(connection.peerId) }) {
                fileTransfers.handle(connection.peerId, envelope.type, envelope.file, envelope.raw)
            }
            return
        }
        if (PhoneBridge.handles(envelope.type)) {
            if (synchronized(lock) { records.containsKey(connection.peerId) }) {
                phone.handle(connection.peerId, envelope)
            }
            return
        }
        handleControl(connection, envelope)
    }

    private fun handleControl(connection: PeerConnection, envelope: Envelope): Unit = locked {
        val id = connection.peerId
        when (envelope.type) {
            MessageType.HELLO -> {
                val hello = envelope.hello ?: return@locked
                // One hello per connection. A second one could claim to be a different kind of device.
                if (connection.hello != null) return@locked
                // Computers never dial a phone, so whatever dialed in and calls itself one is hung up on.
                if (connection.inbound && hello.platform in PhoneLinkProtocol.COMPUTERS) {
                    CoreLog.w(TAG, "INBOUND_COMPUTER $id says ${hello.platform}")
                    close(connection)
                    return@locked
                }
                val record = records[id]
                // Once paired, whether it's a phone or a computer comes from pairing. A device that now claims otherwise is hung up on.
                if (record != null && (record.platform in PhoneLinkProtocol.COMPUTERS) != (hello.platform in PhoneLinkProtocol.COMPUTERS)) {
                    CoreLog.w(TAG, "HELLO_CLASS_CHANGED $id was ${record.platform}, says ${hello.platform}")
                    close(connection)
                    return@locked
                }
                connection.hello = hello
                if (record != null) {
                    if (hello.paired) {
                        records[id] = record.copy(name = hello.name, model = hello.model)
                        saveRecords()
                        send(connection, Envelope(MessageType.INFO, info = localInfo()))
                        pendingClipboard?.takeIf { id in it.waiting }?.let { send(connection, it.envelope()) }
                        if (!isPhone(connection)) phone.peerReady(id) else phoneReady(id)
                    } else {
                        removeRecord(id)
                    }
                }
                publishPeers()
                autoPair()
            }
            MessageType.PAIR_REQUEST -> {
                if (!records.containsKey(id) && connection.hello != null) beginPairing(id, startedByPeer = true, request = envelope.pake)
            }
            MessageType.PAIR_PAKE -> {
                val prompt = _prompt.value?.takeIf { it.peerId == id && !it.showsCode && !it.outdated } ?: return@locked
                val typing = typer ?: return@locked
                if (!typing.received(envelope.pake)) {
                    CoreLog.w(TAG, "PAIRING_BAD_PAKE $id")
                    return@locked rejectCurrentPairing()
                }
                // Only a code still waiting to go: one the user took back or that was answered isn't sent.
                val code = pendingCode
                pendingCode = null
                if (code != null && prompt.localAccepted) sendCode(connection, prompt, code)
            }
            MessageType.PAIR_ACCEPT -> {
                val prompt = _prompt.value?.takeIf { it.peerId == id } ?: return@locked
                if (prompt.showsCode) {
                    if (prompt.outdated) return@locked
                    val showing = shower.shower ?: return@locked
                    // Only the old key: an older app, which can't pair any more. Not counted as a wrong code.
                    val pake = envelope.pake ?: return@locked rejectCurrentPairing(Pairing.UPDATE_REQUIRED)
                    val reply = showing.check(pake)
                    if (reply != null) {
                        send(connection, Envelope(MessageType.PAIR_ACCEPT, pake = reply))
                        completePairing(id)
                    } else if (pairingGuard.wrong(id)) {
                        CoreLog.w(TAG, "PAIRING_LOCKED $id")
                        rejectCurrentPairing(PairingGuard.waitMessage(pairingGuard.waitMs(id)))
                    } else {
                        shower.wrongCode()
                        _prompt.value = prompt.copy(attempts = prompt.attempts + 1, error = "A wrong code was typed on ${prompt.peerName}.")
                        send(connection, Envelope(MessageType.PAIR_REJECT, error = Pairing.WRONG_CODE))
                    }
                } else if (prompt.localAccepted && pendingCode == null) {
                    // Saved only once the other side proves it derived the same key.
                    if (typer?.confirms(envelope.pake) == true) {
                        completePairing(id)
                    } else {
                        CoreLog.w(TAG, "PAIRING_BAD_CONFIRM $id")
                        rejectCurrentPairing()
                    }
                }
            }
            MessageType.PAIR_REJECT -> {
                val prompt = _prompt.value?.takeIf { it.peerId == id } ?: return@locked
                if (envelope.error == Pairing.WRONG_CODE && !prompt.showsCode) {
                    typer?.wrongCode()
                    pendingCode = null
                    _prompt.value = prompt.copy(localAccepted = false, error = "That code isn't right. Check the code on ${prompt.peerName} and try again.")
                } else if (envelope.error == Pairing.UPDATE_REQUIRED && !prompt.showsCode) {
                    dropPake()
                    _prompt.value = prompt.copy(localAccepted = false, outdated = true, error = Pairing.updateMessage(prompt.peerName))
                } else {
                    clearPairing()
                }
            }
            MessageType.UNPAIR -> {
                decline(id)
                removeRecord(id)
                publishPeers()
            }
            MessageType.INFO -> {
                val info = envelope.info
                if (records.containsKey(id) && info != null) {
                    infos[id] = info
                    if (isPhone(connection)) monitor.battery(id, info)?.let { _batteryAlerts.tryEmit(id to it) }
                    val record = records.getValue(id)
                    val macs = info.wakeMacs.orEmpty()
                    if (macs.isNotEmpty() && (record.wakeMacs != macs || record.wakeBroadcast != info.wakeBroadcast || record.wakeEnabled != info.wakeEnabled)) {
                        records[id] = record.copy(wakeMacs = macs, wakeBroadcast = info.wakeBroadcast, wakeEnabled = info.wakeEnabled)
                        saveRecords()
                    }
                    publishPeers()
                }
            }
            MessageType.CLIPBOARD -> {
                val text = envelope.text
                if (records.containsKey(id) && _clipboardEnabled.value && !text.isNullOrEmpty() &&
                    text.length <= PhoneLinkProtocol.MAX_CLIPBOARD_CHARS
                ) {
                    lastClipboard = text
                    val ok = runCatching { actions.setClipboard(text) }.isSuccess
                    // A phone sending waits for this receipt, like the Mac sends one.
                    if (isPhone(connection)) send(connection, Envelope(MessageType.CLIPBOARD_ACK, key = envelope.key, error = if (ok) null else "failed"))
                } else if (records.containsKey(id) && isPhone(connection)) {
                    send(connection, Envelope(MessageType.CLIPBOARD_ACK, key = envelope.key, error = "Clipboard is off"))
                }
            }
            MessageType.CLIPBOARD_ACK -> {
                val pending = pendingClipboard
                if (pending != null && pending.id == envelope.key && pending.waiting.remove(id)) {
                    if (!pending.reported) {
                        pending.reported = true
                        pending.onResult?.invoke(envelope.error == null)
                    }
                    if (pending.waiting.isEmpty()) pendingClipboard = null
                }
            }
            MessageType.MEDIA -> {
                val action = envelope.media
                if (records.containsKey(id) && action != null) runCatching { actions.media(action) }
            }
            MessageType.COMMANDS_LIST -> {
                if (records.containsKey(id)) {
                    _commands.value = _commands.value + (id to envelope.commands.orEmpty())
                }
            }
            MessageType.COMMAND_RESULT -> {
                if (records.containsKey(id)) _commandResult.value = envelope.commandResult
            }
            MessageType.NOTCH_STATE -> {
                val state = envelope.notch
                if (records.containsKey(id) && state != null) {
                    _notch.value = _notch.value + (id to NotchSnapshot(state, System.currentTimeMillis()))
                }
            }
            MessageType.RING -> {
                if (records.containsKey(id)) when (envelope.ring) {
                    "start" -> runCatching { actions.ring(connection.hello?.name ?: "Your Mac", flash = envelope.text == RING_FLASH) }
                    "stop" -> runCatching { actions.stopRinging() }
                    "stopped" -> _ringingMacs.value = _ringingMacs.value - id
                }
            }
            MessageType.CAMERA_REQUEST, MessageType.CAMERA_SYNC, MessageType.CAMERA_STOP, MessageType.CAMERA_CONTROL -> {
                if (records.containsKey(id) && !isPhone(connection)) cameraSink?.invoke(id, envelope)
            }
            MessageType.AUDIO_STARTED, MessageType.AUDIO_FRAME, MessageType.AUDIO_STOPPED, MessageType.AUDIO_STOP -> {
                if (records.containsKey(id) && !isPhone(connection)) audioSink?.invoke(id, envelope)
            }
            MessageType.MACSCREEN_STARTED, MessageType.MACSCREEN_FRAME, MessageType.MACSCREEN_STOPPED -> {
                if (records.containsKey(id) && !isPhone(connection)) macScreenSink?.invoke(id, envelope)
            }
            MessageType.TERMINAL_OPENED, MessageType.TERMINAL_OUTPUT, MessageType.TERMINAL_CLOSED -> {
                if (records.containsKey(id) && envelope.terminal != null) _terminalEvents.tryEmit(id to envelope)
            }
            MessageType.PING -> send(connection, Envelope(MessageType.PONG))
            else -> Unit
        }
    }

    // Another phone's screen and camera

    /** A screen or camera message from a paired phone, either as the phone being viewed or as the one viewing. */
    private fun handlePhoneShare(connection: PeerConnection, envelope: Envelope) {
        val id = connection.peerId
        if (!synchronized(lock) { records.containsKey(id) }) return
        val type = envelope.type
        when (type) {
            in PhoneRules.FROM_SOURCE -> return mirror.handle(id, envelope)
            in PhoneRules.TO_SOURCE -> return handleToSource(connection, envelope)
            in PhoneRules.FROM_SPEAKER -> return handleSpeaker(connection, envelope)
            in PhoneRules.FROM_CONTROLLER -> return handleController(connection, envelope)
            in PhoneRules.TO_CONTROLLER -> {
                val kind = PhoneRules.TO_CONTROLLER.getValue(type)
                if (awaited.take(id, kind)) {
                    _remoteReplies.value = _remoteReplies.value.let { all -> all + (id to (all[id].orEmpty() + (kind to envelope))) }
                    if (kind == PeerAsk.Move) mover.answered(id, envelope)
                }
                return
            }
            in PhoneRules.FROM_SMS_READER -> return handleSmsReader(connection, envelope)
            in PhoneRules.TO_SMS_READER -> {
                smsRemote.handle(id, envelope)
                return
            }
            in PhoneRules.FROM_MOVER -> {
                val summary = envelope.move?.takeIf { it.valid() }
                if (summary != null && phoneAccess.of(id).move == true) runCatching { phoneMoveSink?.invoke(id, summary) }
                return
            }
            in PhoneRules.TO_SPEAKER -> {
                // Only ever stops what this phone sends there.
                if (type == MessageType.TALK_STOPPED) {
                    if (synchronized(lock) { talkingTo.remove(id) }) phoneAudioSink?.invoke(id, envelope)
                } else {
                    audioSink?.invoke(id, envelope)
                }
                return
            }
        }
        val kind = PhoneRules.TO_VIEWER[type]
        if (kind != null) {
            val asked = synchronized(lock) {
                val kinds = watching[id].orEmpty()
                val ok = PhoneRules.toViewer(type, kinds)
                // Stopped means stopped: more video needs a new request.
                if (ok && (type == MessageType.SCREEN_STOPPED || type == MessageType.CAMERA_STOPPED)) unwatch(id, kind)
                ok
            }
            if (asked) phoneViewSink?.invoke(id, envelope)
            return
        }
        val verdict = PhoneRules.fromViewer(type, phoneAccess.of(id), screenSharedWith == id, controlReady())
        when (verdict) {
            PhoneRules.Verdict.Drop, PhoneRules.Verdict.Ask -> Unit
            PhoneRules.Verdict.Refuse -> connection.post(PhoneRules.refusal(type, localHello(true).name))
            PhoneRules.Verdict.Deliver -> when (type) {
                MessageType.SCREEN_REQUEST -> scope.launch(Dispatchers.IO) { runCatching { phoneData.requestScreen(id) } }
                MessageType.SCREEN_SYNC -> runCatching { phoneData.syncScreen(id) }
                MessageType.SCREEN_STOP -> runCatching { phoneData.stopScreen(id) }
                // In order, on the reading thread, like control from a Mac.
                MessageType.CONTROL -> envelope.control?.let(ControlRules::sanitize)?.let { runCatching { phoneData.control(it) } }
                else -> cameraSink?.invoke(id, envelope)
            }
        }
    }

    /** A link, music, screen time, quiet, or location request: carried out only by this phone's switch for the sender. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val mediaAnswers = Dispatchers.IO.limitedParallelism(1)

    private fun handleController(connection: PeerConnection, envelope: Envelope) {
        val id = connection.peerId
        val kind = PhoneRules.FROM_CONTROLLER.getValue(envelope.type)
        when (val verdict = PhoneRules.fromController(envelope.type, phoneAccess.of(id))) {
            // Music buttons run one at a time in arrival order (next, next, volume…); others may be slow (location).
            PhoneRules.Verdict.Deliver -> scope.launch(if (kind == PeerAsk.Media) mediaAnswers else Dispatchers.IO) {
                val name = synchronized(lock) { connection.hello?.name ?: records[id]?.name } ?: "Another phone"
                answers.answer(id, name, envelope)?.let { connection.post(it) }
            }
            PhoneRules.Verdict.Drop -> Unit
            else -> {
                connection.post(PhoneRules.requestRefusal(kind, localHello(true).name, verdict, envelope.key))
                if (verdict == PhoneRules.Verdict.Ask) phoneRequestAskSink?.invoke(id, kind)
            }
        }
    }

    /** An SMS request from a phone that reads this phone's SMS: carried out only by this phone's switch for it. */
    private fun handleSmsReader(connection: PeerConnection, envelope: Envelope) {
        val id = connection.peerId
        when (val verdict = PhoneRules.fromSmsReader(envelope.type, phoneAccess.of(id))) {
            PhoneRules.Verdict.Deliver -> scope.launch(Dispatchers.IO) {
                val name = synchronized(lock) { connection.hello?.name ?: records[id]?.name } ?: "Another phone"
                smsShare.handle(id, name, envelope)
            }
            PhoneRules.Verdict.Drop -> Unit
            else -> {
                val why = PhoneRules.refusalText(PeerAsk.Sms, localHello(true).name, verdict)
                val answer = PhoneRules.smsAnswer(envelope.type) ?: return
                connection.post(
                    if (answer == MessageType.PSMS_RESULT) {
                        val smsId = envelope.smsSend?.id ?: return
                        Envelope(answer, smsResult = SmsResult(smsId, false, why))
                    } else {
                        Envelope(answer, key = envelope.key, smsThread = envelope.smsThread, error = why)
                    },
                )
                if (verdict == PhoneRules.Verdict.Ask) phoneRequestAskSink?.invoke(id, PeerAsk.Sms)
            }
        }
    }

    /** Lists another phone's SMS threads, or one thread; the answer lands in [SmsRemote.views]. */
    fun listPhoneSms(peerId: String, thread: Long? = null): Boolean = isPhone(peerId) && postTo(peerId, smsRemote.list(peerId, thread))

    /**
     * Sends an SMS through another phone's SIM. Returns its ID, whose answer lands in [SmsView.results]; null when the
     * number or text can't go, or that phone isn't connected.
     */
    fun sendPhoneSms(peerId: String, address: String, body: String): String? {
        if (!isPhone(peerId)) return null
        val envelope = smsRemote.send(peerId, address, body) ?: return null
        return envelope.smsSend?.id?.takeIf { postTo(peerId, envelope) }
    }

    /** New SMS from that phone show here while connected (and again after reconnecting, while [on]). */
    fun followPhoneSms(peerId: String, on: Boolean) {
        if (!isPhone(peerId)) return
        smsRemote.setFollow(peerId, on)
        postTo(peerId, if (on) smsRemote.watch(peerId) else smsRemote.unwatch(peerId))
    }

    /** A phone connected or was paired: what it gets from here, and what this phone was doing with it. */
    private fun phoneReady(id: String) {
        relay.peerReady(id)
        if (id in smsRemote.follow.value) postTo(id, smsRemote.watch(id))
        scope.launch(Dispatchers.IO) { mover.connected(id) }
    }

    /**
     * A `file.offer`. From a computer, or a plain file from a phone: taken, to Downloads. Photo backup and moves from
     * a phone need this phone's switch for it, and 1 GB free after the file; a copy already here is skipped.
     */
    private fun admitFile(peerId: String, offer: FileMessage): Admission {
        if (!isPhone(peerId)) return Admission.Accept()
        val kind = offer.kind
        if (kind != PhoneKinds.PHOTO && kind !in PhoneKinds.MOVE) return Admission.Accept()
        val ask = if (kind == PhoneKinds.PHOTO) PeerAsk.Backup else PeerAsk.Move
        val local = localHello(true).name
        when (val verdict = PhoneRules.fileFromPhone(kind, phoneAccess.of(peerId))) {
            PhoneRules.Verdict.Deliver -> Unit
            else -> {
                if (verdict == PhoneRules.Verdict.Ask) phoneRequestAskSink?.invoke(peerId, ask)
                return Admission.Refuse(PhoneRules.refusalText(ask, local, verdict), if (verdict == PhoneRules.Verdict.Ask) PhoneKinds.ASK else PhoneKinds.OFF)
            }
        }
        val name = synchronized(lock) { connections[peerId]?.hello?.name ?: records[peerId]?.name } ?: "Phone"
        val origin = FileOrigin(peerId, FileNames.sanitize(name), kind, FileNames.folder(offer.folder))
        val clean = FileNames.sanitize(offer.name)
        val size = offer.size ?: 0L
        if (received.has(peerId, origin.folder, clean, size, offer.sha256) && runCatching { fileSinks.exists(origin, clean, size) }.getOrDefault(false)) {
            return Admission.Skip
        }
        val free = runCatching { phoneData.freeBytes() }.getOrDefault(Long.MAX_VALUE)
        if (!StorageGuard.enough(free, size)) {
            runCatching { lowSpaceSink?.invoke(peerId, kind) }
            return Admission.Refuse(StorageGuard.lowSpaceText(local, free), PhoneKinds.LOW_SPACE)
        }
        return Admission.Accept(origin)
    }

    private fun fileSaved(peerId: String, offer: FileMessage, sha256: String) {
        if (offer.kind != PhoneKinds.PHOTO && offer.kind !in PhoneKinds.MOVE) return
        if (!isPhone(peerId)) return
        received.add(peerId, FileNames.folder(offer.folder), FileNames.sanitize(offer.name), offer.size ?: 0L, sha256)
    }

    /** Sends a request to another phone and waits for its answer, which lands in [remoteReplies]. */
    private fun askPhone(peerId: String, envelope: Envelope): Boolean {
        val kind = PhoneRules.FROM_CONTROLLER[envelope.type] ?: return false
        if (!isPhone(peerId)) return false
        awaited.expect(peerId, kind)
        return postTo(peerId, envelope)
    }

    /** Offers a web, map, or phone link on another phone. False when it isn't one, or that phone isn't connected. */
    fun sendLink(peerId: String, text: String): Boolean {
        val link = SafeLink.find(text) ?: return false
        return askPhone(peerId, Envelope(MessageType.LINK_OPEN, text = link.uri, key = UUID.randomUUID().toString()))
    }

    /** A music button on another phone, or [RemoteAnswers.STATE] to only see what plays. */
    fun phoneMedia(peerId: String, action: String): Boolean =
        action in RemoteAnswers.MEDIA_ACTIONS && askPhone(peerId, Envelope(MessageType.MEDIA_REMOTE, media = action))

    fun requestScreenTime(peerId: String): Boolean = askPhone(peerId, Envelope(MessageType.MONITOR_REQUEST))

    fun setPhoneQuiet(peerId: String, request: QuietRequest): Boolean =
        QuietMath.valid(request) && askPhone(peerId, Envelope(MessageType.QUIET_SET, quiet = request.copy(until = null)))

    fun locatePhone(peerId: String): Boolean = askPhone(peerId, Envelope(MessageType.LOCATE_REQUEST))

    /** Changes this phone's battery alerts for another phone. */
    fun setMonitor(peerId: String, change: (MonitorSettings) -> MonitorSettings) = monitor.set(peerId, change)

    /** A dismiss, reply, or "mark as read" for one of this phone's notifications, from the phone it was sent to. */
    private fun handleToSource(connection: PeerConnection, envelope: Envelope) {
        val id = connection.peerId
        when (PhoneRules.toSource(envelope.type, phoneAccess.of(id), relay.forwarded(id, envelope.key))) {
            PhoneRules.Verdict.Deliver -> scope.launch(Dispatchers.IO) { relay.handle(id, envelope) }
            PhoneRules.Verdict.Refuse -> connection.post(NotificationRelay.refusal(envelope.key, localHello(true).name))
            else -> Unit
        }
    }

    /** Voice or sound from a phone: played here only once allowed, from one phone of each kind at a time. */
    private fun handleSpeaker(connection: PeerConnection, envelope: Envelope) {
        val id = connection.peerId
        val type = envelope.type
        val kind = PhoneRules.FROM_SPEAKER.getValue(type)
        val starting = type == MessageType.TALK_START || type == MessageType.AUDIO_STARTED
        val (verdict, busy) = synchronized(lock) {
            val busy = if (starting) hearing.entries.firstOrNull { it.key != id && kind in it.value }?.key else null
            val verdict = PhoneRules.fromSpeaker(type, phoneAccess.of(id), hearing[id].orEmpty(), busy)
            if (verdict == PhoneRules.Verdict.Deliver) {
                if (starting) hearing.getOrPut(id) { HashSet() }.add(kind)
                else if (type == MessageType.TALK_STOP || type == MessageType.AUDIO_STOPPED) unhear(id, kind)
            }
            verdict to busy
        }
        when (verdict) {
            PhoneRules.Verdict.Deliver -> phoneAudioSink?.invoke(id, envelope)
            PhoneRules.Verdict.Drop -> Unit
            else -> {
                val local = localHello(true).name
                val busyName = busy?.let { other -> synchronized(lock) { connections[other]?.hello?.name ?: records[other]?.name } ?: "Another phone" }
                connection.post(PhoneRules.audioRefusal(kind, PhoneRules.audioRefusalText(kind, local, verdict, busyName)))
                if (verdict == PhoneRules.Verdict.Ask) phoneAskSink?.invoke(id, kind)
            }
        }
    }

    private fun unhear(peerId: String, kind: AudioKind): Boolean = synchronized(lock) {
        val kinds = hearing[peerId] ?: return@synchronized false
        val had = kinds.remove(kind)
        if (kinds.isEmpty()) hearing.remove(peerId)
        had
    }

    /** The end of a stream played here, as the player sees it. */
    private fun endedLocally(kind: AudioKind) = Envelope(if (kind == AudioKind.Talk) MessageType.TALK_STOP else MessageType.AUDIO_STOPPED)

    /** Stops playing that phone's voice or sound here, and tells it to stop sending. */
    fun stopHearing(peerId: String, kind: AudioKind, why: String? = null) = onIo {
        if (!unhear(peerId, kind)) return@onIo
        runCatching { phoneAudioSink?.invoke(peerId, endedLocally(kind)) }
        postTo(peerId, PhoneRules.audioRefusal(kind, why))
    }

    /** What this phone plays from [peerId] now. */
    fun hearingFrom(peerId: String): Set<AudioKind> = synchronized(lock) { hearing[peerId]?.toSet().orEmpty() }

    /** Starts push-to-talk to another phone: `talk.start`, then [sendTalk] for each chunk, then [stopTalk]. */
    fun startTalk(peerId: String, rate: Int, channels: Int): Boolean {
        synchronized(lock) { talkingTo.add(peerId) }
        return sendTo(peerId, Envelope(MessageType.TALK_START, audioRate = rate, audioChannels = channels))
    }

    /** One chunk of 16-bit PCM, binary to a `bin1` phone. Returns false when it couldn't go. */
    fun sendTalk(peerId: String, pcm: ByteArray): Boolean = sendTo(peerId, Envelope(MessageType.TALK_FRAME, raw = pcm))

    fun stopTalk(peerId: String) {
        if (synchronized(lock) { talkingTo.remove(peerId) }) postTo(peerId, Envelope(MessageType.TALK_STOP))
    }

    /** Dismisses a notification on the phone that forwarded it, and drops it here. */
    fun dismissMirrored(peerId: String, key: String) {
        mirror.remove(peerId, key)
        postTo(peerId, Envelope(MessageType.NOTIFICATION_DISMISS, key = key))
    }

    /** Answers a forwarded notification; `notification.result` comes back in [NotificationMirror.results]. */
    fun replyMirrored(peerId: String, key: String, text: String) {
        postTo(peerId, Envelope(MessageType.NOTIFICATION_REPLY, key = key, text = text))
    }

    fun markMirroredRead(peerId: String, key: String) {
        postTo(peerId, Envelope(MessageType.NOTIFICATION_READ, key = key))
    }

    /** Paired phones connected now whose hello lists [cap]. */
    private fun phonesWith(cap: String): List<String> = synchronized(lock) {
        connections.entries.filter { (id, connection) -> id in records && isPhone(connection) && PhoneRules.supports(connection.hello, cap) }.map { it.key }
    }

    private fun controlReady(): Boolean = runCatching { phoneData.isGranted(PhoneFeature.Control) }.getOrDefault(false)

    /** Ends this phone's screen and camera sessions with [peerId], and forgets what this phone asked it for. */
    private fun endPhoneSessions(peerId: String) {
        val (heard, talking) = synchronized(lock) {
            watching.remove(peerId)
            (hearing.remove(peerId)?.toList().orEmpty()) to talkingTo.remove(peerId)
        }
        runCatching { phoneData.stopScreen(peerId) }
        runCatching { cameraSink?.invoke(peerId, Envelope(MessageType.CAMERA_STOP)) }
        // Voice and sound from it end here; voice from here to it ends too. Sound sent from here ends through
        // the app, which watches who is connected.
        heard.forEach { runCatching { phoneAudioSink?.invoke(peerId, endedLocally(it)) } }
        if (talking) runCatching { phoneAudioSink?.invoke(peerId, Envelope(MessageType.TALK_STOPPED, error = "The connection was lost")) }
        mirror.clear(peerId)
        relay.disconnected(peerId)
        smsShare.disconnected(peerId)
        smsRemote.disconnected(peerId)
        mover.disconnected(peerId)
        // What plays there is only known while connected.
        _remoteReplies.value[peerId]?.let { replies -> _remoteReplies.value = _remoteReplies.value + (peerId to (replies - PeerAsk.Media)) }
    }

    private fun watch(peerId: String, kind: P2pKind) = synchronized(lock) { watching.getOrPut(peerId) { HashSet() }.add(kind) }

    private fun unwatch(peerId: String, kind: P2pKind) = synchronized(lock) {
        watching[peerId]?.let { kinds ->
            kinds.remove(kind)
            if (kinds.isEmpty()) watching.remove(peerId)
        }
    }

    /**
     * Changes what [peerId] may do here. Taking screen or camera away ends that session now; the app also resends
     * `screen.started` so the viewer learns whether its taps still go through.
     */
    fun setPhoneAccess(peerId: String, change: (PeerAccess) -> PeerAccess) = onIo {
        val (before, after) = phoneAccess.update(peerId, change)
        if (before.screen == true && after.screen != true) runCatching { phoneData.stopScreen(peerId) }
        if (before.camera == true && after.camera != true) runCatching { cameraSink?.invoke(peerId, Envelope(MessageType.CAMERA_STOP)) }
        val local = localHello(true).name
        if (before.talk == true && after.talk != true) stopHearing(peerId, AudioKind.Talk, "Talking to $local is turned off there.")
        if (before.sound == true && after.sound != true) stopHearing(peerId, AudioKind.Sound, "Playing sound on $local is turned off there.")
        relay.changed(peerId, before, after)
        if (before.sms != after.sms) smsShare.accessChanged(peerId, after)
    }

    /** For `screen.started`: whether taps from that phone go through. Null for a computer, which doesn't need it. */
    fun phoneControlAllowed(peerId: String): Boolean? =
        if (!isPhone(peerId)) null else PhoneRules.canControl(phoneAccess.of(peerId), controlReady())

    /** Asks another phone for its screen. It shows a notification there, and the user must agree there. */
    fun viewPhoneScreen(peerId: String) {
        watch(peerId, P2pKind.Screen)
        postTo(peerId, Envelope(MessageType.SCREEN_REQUEST))
    }

    fun stopPhoneScreen(peerId: String) {
        unwatch(peerId, P2pKind.Screen)
        postTo(peerId, Envelope(MessageType.SCREEN_STOP))
    }

    fun syncPhoneScreen(peerId: String) { postTo(peerId, Envelope(MessageType.SCREEN_SYNC)) }

    /** A touch or key on the other phone's screen, `x` and `y` from 0 to 1. It only acts if that phone allows it. */
    fun sendPhoneControl(peerId: String, event: ControlEvent) {
        postTo(peerId, Envelope(MessageType.CONTROL, control = event))
    }

    /** Asks another phone for its camera. It shows a notification there, and the camera starts after a tap. */
    fun usePhoneCamera(peerId: String) {
        watch(peerId, P2pKind.Camera)
        postTo(peerId, Envelope(MessageType.CAMERA_REQUEST))
    }

    fun stopPhoneCamera(peerId: String) {
        unwatch(peerId, P2pKind.Camera)
        postTo(peerId, Envelope(MessageType.CAMERA_STOP))
    }

    /** `camera.sync`, `camera.control`, `camera.zoom`, or `camera.shutter` for the other phone's camera. */
    fun sendPhoneCamera(peerId: String, envelope: Envelope) {
        if (envelope.type !in PhoneRules.FROM_VIEWER || !envelope.type.startsWith("camera.")) return
        postTo(peerId, envelope)
    }

    // Pairing

    fun requestPairing(id: String) = onIo {
        locked {
            val connection = connections[id] ?: return@locked
            if (records.containsKey(id)) return@locked
            if (declined.remove(id)) saveDeclined()
            beginPairing(id, startedByPeer = false)
        }
    }

    /** Sends the code typed on this phone. The other device checks it. */
    fun submitCode(typed: String) = onIo {
        val digits = typed.filter(Char::isDigit)
        locked {
            val prompt = _prompt.value ?: return@locked
            // A code already on its way isn't sent again: a second answer would replace the first one's
            // expected confirmation and use up another try.
            if (prompt.showsCode || prompt.outdated || prompt.localAccepted || digits.length != 6) return@locked
            val connection = connections[prompt.peerId] ?: return@locked
            val typing = typer ?: return@locked
            _prompt.value = prompt.copy(localAccepted = true, error = null)
            // The other side's share hasn't come yet: the code goes as soon as it does.
            if (!typing.ready) pendingCode = digits else sendCode(connection, prompt, digits)
        }
    }

    /** The CPace answer for a typed code. A fresh secret each time, so the same code can be typed again. */
    private fun Outgoing.sendCode(connection: PeerConnection, prompt: PairingPrompt, code: String) {
        val accept = typer?.answer(code, Pake.channel(identity.deviceId, prompt.peerId))
        if (accept == null) {
            CoreLog.w(TAG, "PAIRING_BAD_PAKE ${prompt.peerId}")
            return rejectCurrentPairing()
        }
        send(connection, Envelope(MessageType.PAIR_ACCEPT, pake = accept))
    }

    fun rejectPairing() = onIo {
        locked {
            _prompt.value?.let { decline(it.peerId) }
            rejectCurrentPairing()
        }
    }

    /** Called when the app UI appears, because the user needs to see the pairing code. */
    fun tryAutoPair() = onIo {
        locked { autoPair() }
    }

    /**
     * Opens the code screen automatically for a new Mac. The user still has to match the code, because
     * otherwise anyone on the same Wi-Fi could pair. Each Mac is only asked about once per
     * session, and never again if the user declined or unpaired it.
     */
    private fun Outgoing.autoPair() {
        if (!isUiVisible() || _prompt.value != null) return
        val id = connections.entries.firstOrNull { (id, connection) ->
            // Phones only pair when tapped, so a phone doesn't pop up the code on every phone around it.
            connection.hello != null && id !in records && id !in autoAsked && id !in declined && !isPhone(connection)
        }?.key ?: return
        autoAsked.add(id)
        beginPairing(id, startedByPeer = false)
    }

    private fun decline(id: String) {
        if (declined.add(id)) saveDeclined()
    }

    fun unpair(id: String) = onIo {
        locked {
            send(connections[id], Envelope(MessageType.UNPAIR))
            decline(id)
            removeRecord(id)
            publishPeers()
        }
    }

    private fun Outgoing.rejectCurrentPairing(error: String? = null) {
        val prompt = _prompt.value ?: return
        send(connections[prompt.peerId], Envelope(MessageType.PAIR_REJECT, error = error))
        clearPairing()
    }

    /**
     * [startedByPeer]: the other side sent `pair.request` ([request] is its `pake`), so this phone shows the code.
     * Otherwise this phone asks, and types the code.
     */
    private fun Outgoing.beginPairing(id: String, startedByPeer: Boolean, request: PakeMessage? = null) {
        val connection = connections[id] ?: return
        val hello = connection.hello ?: return
        val current = _prompt.value
        if (current != null) {
            // Only one request is active. Others are refused.
            if (current.peerId != id) {
                send(connection, Envelope(MessageType.PAIR_REJECT))
                return
            }
            // Asked again while the code shows: the same code stays, and so do the wrong tries. A new request
            // (a new nonce) gets a new share for that same code, but only after a wrong code or now and then.
            if (startedByPeer && current.showsCode && !current.outdated) {
                val nonce = Pake.requestNonce(request) ?: return
                when (val renewal = shower.renew(current.code, nonce, Pake.channel(id, identity.deviceId))) {
                    is ShowerSlot.Renewal.Resend -> send(connection, Envelope(MessageType.PAIR_PAKE, pake = renewal.message))
                    is ShowerSlot.Renewal.Build -> buildShower(id, renewal.plan)
                    ShowerSlot.Renewal.Ignore -> CoreLog.w(TAG, "PAIRING_REQUEST_IGNORED $id")
                }
                return
            }
            // Both asked at once: the smaller ID shows the code, the other types it.
            if (!startedByPeer || current.showsCode || identity.deviceId >= id) return
        }
        // Too many wrong codes lately: the code isn't shown for a while.
        val wait = if (startedByPeer) pairingGuard.waitMs(id) else 0L
        if (wait > 0) {
            if (current != null) {
                pairingTimeout?.cancel()
                pairingTimeout = null
                dropPake()
                _prompt.value = null
            }
            send(connection, Envelope(MessageType.PAIR_REJECT, error = PairingGuard.waitMessage(wait)))
            return
        }
        // Older apps only know the old code check, which a device in the middle could brute-force. They must update.
        val nonceT = if (startedByPeer) Pake.requestNonce(request) else null
        val outdated = hello.protocolVersion < Pairing.PAKE_SINCE || (startedByPeer && nonceT == null)
        dropPake()
        val code = if (startedByPeer && !outdated) Pairing.newCode() else ""
        _prompt.value = PairingPrompt(
            id, hello.name, code, startedByPeer,
            error = if (outdated) Pairing.updateMessage(hello.name) else null,
            outdated = outdated,
        )
        when {
            outdated -> if (startedByPeer) send(connection, Envelope(MessageType.PAIR_REJECT, error = Pairing.UPDATE_REQUIRED))
            startedByPeer -> buildShower(id, shower.start(code, nonceT!!, Pake.channel(id, identity.deviceId)))
            else -> {
                val typing = Pake.Typer()
                typer = typing
                send(connection, Envelope(MessageType.PAIR_REQUEST, pake = typing.request))
            }
        }
        pairingTimeout?.cancel()
        pairingTimeout = scope.launch(Dispatchers.IO) {
            delay(PhoneLinkProtocol.PAIRING_TIMEOUT_MS)
            locked {
                if (_prompt.value?.peerId == id) rejectCurrentPairing()
            }
        }
    }

    /** The CPace math for a Shower runs once the lock is released; its `pair.pake` goes if the prompt is still the same. */
    private fun Outgoing.buildShower(id: String, plan: ShowerSlot.Plan) = afterwards {
        val built = plan.build()
        locked {
            if (_prompt.value?.peerId == id && shower.install(plan, built)) {
                send(connections[id], Envelope(MessageType.PAIR_PAKE, pake = built.message))
            } else {
                built.close()
            }
        }
    }

    private fun Outgoing.completePairing(id: String) {
        val connection = connections[id] ?: return
        val hello = connection.hello ?: return
        records[id] = PairedRecord(hello.name, hello.platform, hello.model, System.currentTimeMillis())
        saveRecords()
        pairingGuard.succeeded(id)
        clearPairing()
        send(connection, Envelope(MessageType.INFO, info = localInfo()))
        if (!isPhone(connection)) phone.peerReady(id) else phoneReady(id)
        publishPeers()
    }

    private fun dropPake() {
        shower.clear()
        typer?.close()
        typer = null
        pendingCode = null
    }

    private fun Outgoing.clearPairing() {
        pairingTimeout?.cancel()
        pairingTimeout = null
        dropPake()
        _prompt.value = null
        autoPair()
    }

    // Status

    fun broadcastInfo() {
        // Nothing connected: nothing to tell, and reading the battery and the rest isn't needed.
        if (synchronized(lock) { connections.keys.none { it in records } }) return
        onIo {
            val paired = synchronized(lock) { connections.filterKeys { it in records }.values.toList() }
            if (paired.isEmpty()) return@onIo
            val info = localInfo()
            paired.forEach { it.post(Envelope(MessageType.INFO, info = info)) }
        }
    }

    // Features

    private fun pairedConnection(peerId: String): PeerConnection? =
        synchronized(lock) { connections[peerId]?.takeIf { peerId in records } }

    /** Waits like a direct write (see [PeerConnection.send]). */
    private fun sendTo(peerId: String, envelope: Envelope): Boolean = pairedConnection(peerId)?.send(envelope) ?: false

    /**
     * Queues on that device's own connection without waiting. Live messages go before file data, touchpad moves that
     * pile up merge into one, and a device that stopped reading holds up nobody else.
     */
    private fun postTo(peerId: String, envelope: Envelope): Boolean = pairedConnection(peerId)?.post(envelope) ?: false

    /** Queues a message, for callers on Android's main thread, which can't write to the network. */
    fun post(peerId: String, envelope: Envelope) {
        postTo(peerId, envelope)
    }

    /** Sends directly, without the queue. Returns false if the device isn't connected. */
    fun sendDirect(peerId: String, envelope: Envelope): Boolean = sendTo(peerId, envelope)

    /** The device's connection takes binary frames: both hellos listed `bin1` (see [BinaryFrames]). */
    fun binaryTo(peerId: String): Boolean = pairedConnection(peerId)?.binary == true

    fun sendInput(peerId: String, event: InputEvent) {
        postTo(peerId, Envelope(MessageType.INPUT, input = event))
    }

    private val _terminalEvents = kotlinx.coroutines.flow.MutableSharedFlow<Pair<String, Envelope>>(extraBufferCapacity = 1024)
    /** Terminal output and state from Macs, as (Mac id, message). */
    val terminalEvents: kotlinx.coroutines.flow.SharedFlow<Pair<String, Envelope>> = _terminalEvents

    /** Queued like input, so keys reach the Mac in the order they were typed. */
    /** Camera requests and controls from a paired Mac, handled by the service. */
    @Volatile var cameraSink: ((String, Envelope) -> Unit)? = null

    /** Gets the Mac screen's messages on the reading thread, so video goes straight to the decoder. */
    @Volatile var macScreenSink: ((String, Envelope) -> Unit)? = null
    /** Audio messages from Macs: their sound to play here, or a request to stop sending ours. */
    @Volatile var audioSink: ((String, Envelope) -> Unit)? = null

    fun startMacScreen(peerId: String, display: Long? = null) {
        postTo(peerId, Envelope(MessageType.MACSCREEN_START, displayId = display))
    }

    /** Makes this phone an extra display of the Mac, [width] by [height] points. */
    fun extendMacScreen(peerId: String, width: Int, height: Int, large: Boolean = false) {
        val mode = if (large) "extend-large" else "extend"
        postTo(peerId, Envelope(MessageType.MACSCREEN_START, text = mode, screen = ScreenInfo(width, height)))
    }
    fun stopMacScreen(peerId: String) { postTo(peerId, Envelope(MessageType.MACSCREEN_STOP)) }
    fun syncMacScreen(peerId: String) { postTo(peerId, Envelope(MessageType.MACSCREEN_SYNC)) }

    /** A tap or drag on the Mac's screen. `x` and `y` range from 0 to 1. */
    fun sendMacPointer(peerId: String, kind: String, x: Float, y: Float) {
        postTo(peerId, Envelope(MessageType.MACSCREEN_POINTER, control = ControlEvent(kind, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))))
    }

    /** Live, in typing order. A long paste goes in pieces, so no frame is too big and keys still get through between them. */
    fun sendTerminal(peerId: String, type: String, message: TerminalMessage) {
        terminalPieces(type, message).forEach { postTo(peerId, Envelope(type, terminal = it)) }
    }

    fun sendMedia(peerId: String, action: String) {
        postTo(peerId, Envelope(MessageType.MEDIA, media = action))
    }

    /** Asks every connected, paired Mac to lock, or to start or stop Keep Awake. */
    fun sendMacAction(action: String, peerId: String? = null) {
        val targets = peerId?.let { listOf(it) } ?: _peers.value.filter { it.isPaired && it.isConnected && it.isMac }.map { it.id }
        targets.forEach { postTo(it, Envelope(MessageType.MAC_ACTION, macAction = action)) }
    }

    /**
     * Sends Wake-on-LAN to a paired Mac over the phone's Wi-Fi, then follows it in [Peer.wake] for [WakeFlow.WAIT_MS]:
     * that device is dialed again at once and from the shortest wait, and the network is scanned again. False when
     * its address isn't known or nothing could be sent. Sent doesn't mean woken: [Peer.wake] says that.
     */
    suspend fun wakeMac(peerId: String): Boolean {
        val record = synchronized(lock) { records[peerId] } ?: return false
        if (record.wakeMacs.isEmpty()) return false
        val route = runCatching { wakeRoute?.invoke() }.getOrNull()
        val sent = kotlinx.coroutines.withContext(Dispatchers.IO) {
            WakeOnLan.send(record.wakeMacs, listOf(route?.broadcast, record.wakeBroadcast), route?.bind)
        }
        val state = WakeFlow.sent(sent, System.currentTimeMillis(), viaWifi = route != null)
        val stale = locked {
            wakes[peerId] = state
            publishPeers()
            // A connection that went quiet is most likely the Mac asleep. It's dropped, so the new connection of the
            // woken Mac isn't refused as a duplicate of it.
            if (sent && peerId in quiet) connections[peerId] else null
        }
        stale?.close()
        if (sent) watchWake(peerId)
        return sent
    }

    private fun watchWake(peerId: String) {
        val job = scope.launch(Dispatchers.IO) {
            onRescanWanted?.invoke()
            var lastKick = 0L
            while (isActive) {
                val now = System.currentTimeMillis()
                if (now - lastKick >= WAKE_REDIAL_MS) {
                    redial.kick(peerId)
                    lastKick = now
                }
                val done = locked {
                    val current = wakes[peerId] ?: return@locked true
                    // Something heard from it after the signal: a new connection's hello, or a pong on the old one.
                    val sentAt = (current as? WakeState.Waiting)?.sentAt ?: 0L
                    val awake = connections[peerId]?.let { it.lastHeardAt >= sentAt && peerId !in quiet } == true
                    val next = WakeFlow.step(current, awake, now, records[peerId]?.wakeEnabled, infos[peerId]?.isCharging?.not())
                    if (next != current) {
                        wakes[peerId] = next
                        publishPeers()
                    }
                    next !is WakeState.Waiting
                }
                if (done) break
                delay(WAKE_CHECK_MS)
            }
        }
        synchronized(lock) { wakeWatchers.put(peerId, job) }?.cancel()
    }

    fun sendNotchAction(peerId: String, action: NotchAction) {
        postTo(peerId, Envelope(MessageType.NOTCH_ACTION, notchAction = action))
    }

    /** Makes the Mac or phone play a loud sound for up to 30 seconds. [flash]: a phone also flashes its torch. */
    fun ringMac(peerId: String, on: Boolean, flash: Boolean = false) {
        postTo(peerId, Envelope(MessageType.RING, ring = if (on) "start" else "stop", text = if (on && flash) RING_FLASH else null))
        _ringingMacs.value = if (on) _ringingMacs.value + peerId else _ringingMacs.value - peerId
        if (on) scope.launch {
            delay(RING_MS)
            _ringingMacs.value = _ringingMacs.value - peerId
        }
    }

    /** Tells every Mac that asked for a ring that it ended here. */
    fun ringStopped() = onIo {
        val paired = synchronized(lock) { connections.filterKeys { it in records }.values.toList() }
        paired.forEach { it.post(Envelope(MessageType.RING, ring = "stopped")) }
    }

    fun runCommand(peerId: String, commandId: String) {
        _commandResult.value = null
        postTo(peerId, Envelope(MessageType.COMMAND_RUN, commandId = commandId))
    }

    /**
     * Sends clipboard text to every paired Mac, unless the text just came from a Mac. A dead connection
     * can still accept data, so only the Mac's receipt counts. Without one, the connection is closed
     * and the text goes again after reconnecting. [onResult] runs once, on any thread.
     */
    fun shareClipboard(text: String, force: Boolean = false, onResult: ((Boolean) -> Unit)? = null) {
        if (!_clipboardEnabled.value || text.length > PhoneLinkProtocol.MAX_CLIPBOARD_CHARS) {
            onResult?.invoke(false)
            return
        }
        if (!force && text == lastClipboard) return
        lastClipboard = text
        val (pending, targets) = synchronized(lock) {
            val item = PendingClipboard(UUID.randomUUID().toString(), text, records.keys.toMutableSet(), onResult)
            pendingClipboard = item
            item to item.waiting.mapNotNull { connections[it] }
        }
        // Queued after the lock is released.
        val envelope = pending.envelope()
        targets.forEach { it.post(envelope) }
        scope.launch(Dispatchers.IO) {
            delay(CLIPBOARD_RECEIPT_MS)
            val silent = synchronized(lock) {
                if (pendingClipboard === pending) pending.waiting.mapNotNull { connections[it] } else emptyList()
            }
            silent.forEach { it.close() }
            delay(CLIPBOARD_RETRY_MS)
            val failed = synchronized(lock) {
                if (pendingClipboard !== pending) return@synchronized false
                pendingClipboard = null
                if (!pending.reported && lastClipboard == text) lastClipboard = null
                !pending.reported
            }
            if (failed) onResult?.invoke(false)
        }
    }

    fun setClipboardAuto(enabled: Boolean) {
        _clipboardAuto.value = enabled
        if (enabled) clipboardAutoFile.createNewFile() else clipboardAutoFile.delete()
    }

    fun setClipboardEnabled(enabled: Boolean) {
        _clipboardEnabled.value = enabled
        if (enabled) clipboardOffFile.delete() else clipboardOffFile.createNewFile()
    }

    /** Heartbeat: without a reply, the read timeout closes the dead connection. */
    fun ping() = onIo {
        val all = locked {
            // Before this ping: two heartbeats without an answer mark the device as maybe asleep.
            val now = System.currentTimeMillis()
            val silent = connections.filterValues { now - it.lastHeardAt > QUIET_MS }.keys.toSet()
            if (silent != quiet) {
                quiet = silent
                publishPeers()
            }
            connections.values.toList()
        }
        all.forEach { it.post(Envelope(MessageType.PING)) }
    }

    fun shutdown() {
        stopServer()
        targets.values.forEach { it.cancel() }
        targets.clear()
        synchronized(lock) { connections.values.toList() }.forEach { it.close() }
    }

    private fun onIo(block: () -> Unit) {
        // Socket sends must not run on the main thread.
        scope.launch(Dispatchers.IO) { block() }
    }

    private fun removeRecord(id: String) {
        if (records.remove(id) != null) {
            if (phoneAccess.of(id) != PeerAccess() || watching.containsKey(id) || hearing.containsKey(id) || id in talkingTo ||
                mirror.feeds.value.containsKey(id)
            ) {
                phoneAccess.forget(id)
                watching.remove(id)
                scope.launch(Dispatchers.IO) { endPhoneSessions(id) }
            }
            monitor.forget(id)
            awaited.forget(id)
            smsShare.forget(id)
            smsRemote.forget(id)
            received.forget(id)
            if (mover.job.plan.value?.peerId == id) scope.launch(Dispatchers.IO) { mover.cancel() }
            _remoteReplies.value = _remoteReplies.value - id
            infos.remove(id)
            _notch.value = _notch.value - id
            saveRecords()
        }
    }

    private fun publishPeers() {
        val ids = records.keys + connections.keys
        _peers.value = ids.mapNotNull { id ->
            val hello = connections[id]?.hello
            val record = records[id]
            if (hello == null && record == null) return@mapNotNull null
            Peer(
                id = id,
                name = hello?.name ?: record?.name ?: "Device",
                platform = record?.platform ?: hello?.platform ?: if (connections[id]?.inbound == true) "android" else "macos",
                model = hello?.model ?: record?.model ?: "",
                isPaired = record != null,
                isConnected = connections.containsKey(id),
                info = infos[id],
                canWake = record?.wakeMacs?.isNotEmpty() == true,
                viaUsb = connections.containsKey(id) && connectionHosts[id] == PhoneLinkProtocol.USB_HOST,
                wakeEnabled = record?.wakeEnabled,
                caps = hello?.caps.orEmpty(),
                maybeAsleep = connections.containsKey(id) && id in quiet,
                // A try that failed stops mattering once the Mac is back.
                wake = wakes[id]?.takeUnless { it is WakeState.DidntWake && connections.containsKey(id) && id !in quiet },
            )
        }.sortedWith(compareBy({ !it.isConnected }, { it.name.lowercase() }))
    }

    private fun loadRecords() {
        val stored = runCatching {
            protocolJson.decodeFromString(recordsSerializer, recordsFile.readText())
        }.getOrNull() ?: return
        records.putAll(stored)
    }

    private fun saveRecords() {
        recordsFile.writeText(protocolJson.encodeToString(recordsSerializer, records))
    }

    private fun loadDeclined() {
        val stored = runCatching {
            protocolJson.decodeFromString(ListSerializer(String.serializer()), declinedFile.readText())
        }.getOrNull() ?: return
        declined.addAll(stored)
    }

    private fun saveDeclined() {
        declinedFile.writeText(protocolJson.encodeToString(ListSerializer(String.serializer()), declined.toList()))
    }

    private fun loadManualTargets() {
        val stored = runCatching {
            protocolJson.decodeFromString(ListSerializer(String.serializer()), manualFile.readText())
        }.getOrNull() ?: return
        _manualTargets.value = stored.mapNotNull { entry ->
            val host = entry.substringBeforeLast(':', "")
            val port = entry.substringAfterLast(':').toIntOrNull()
            if (host.isEmpty() || port == null) null else ManualTarget(host, port)
        }
    }

    private fun saveManualTargets() {
        val values = _manualTargets.value.map { it.label }
        manualFile.writeText(protocolJson.encodeToString(ListSerializer(String.serializer()), values))
    }

    private class PendingClipboard(
        val id: String,
        val text: String,
        val waiting: MutableSet<String>,
        val onResult: ((Boolean) -> Unit)?,
    ) {
        var reported = false

        fun envelope() = Envelope(MessageType.CLIPBOARD, text = text, key = id)
    }

    companion object {
        private const val TAG = "PhoneLink"
        private const val USB_KEY = "usb"
        private const val USB_RETRY_MS = 3_000L
        private const val CLIPBOARD_RECEIPT_MS = 4_000L
        private const val CLIPBOARD_RETRY_MS = 26_000L
        private const val RING_MS = 30_000L
        /** On `ring` `start` (`text`): also flash the torch. Older phones ignore it and only ring. */
        const val RING_FLASH = "flash"
        private const val MIN_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 30_000L
        /** A connection that lasted this long was a real one, so the next reconnect starts quickly again. */
        private const val STABLE_CONNECTION_MS = 30_000L
        /** After the cable is pulled, Wi-Fi is tried again this soon. */
        private const val USB_HANDBACK_MS = 300L

        /** Two heartbeats (25 s each) without anything from a device: it's probably asleep. */
        const val QUIET_MS = 45_000L
        /** While a wake try is followed: how often it's checked, and how often the Mac is dialed again at once. */
        private const val WAKE_CHECK_MS = 1_000L
        private const val WAKE_REDIAL_MS = 10_000L

        /** The wait after this one: the shortest after a wake signal (a kick), otherwise twice as long, up to 30 s. */
        fun nextBackoff(currentMs: Long, kicked: Boolean): Long =
            if (kicked) MIN_BACKOFF_MS else (currentMs * 2).coerceAtMost(MAX_BACKOFF_MS)

        /**
         * The paired devices kept in [directory], read without starting a manager: the widget can wake a Mac this way
         * when the service can't be started.
         */
        fun storedRecords(directory: File): Map<String, PairedRecord> = runCatching {
            protocolJson.decodeFromString(recordsSerializer, File(directory, "paired.json").readText())
        }.getOrDefault(emptyMap())

        /** The wait before the next dial: back to the shortest only after a connection that held for a while. */
        fun backoffAfter(currentMs: Long, connectedForMs: Long?): Long =
            if (connectedForMs != null && connectedForMs > STABLE_CONNECTION_MS) MIN_BACKOFF_MS else currentMs

        /** Two queued touchpad moves, or two scrolls, for the same device as one; null when they can't merge. */
        fun coalesce(first: Pair<String, Envelope>, second: Pair<String, Envelope>): Pair<String, Envelope>? {
            if (first.first != second.first || !FrameWriter.isPointer(first.second) || !FrameWriter.isPointer(second.second)) return null
            return FrameWriter.merge(first.second, second.second)?.let { first.first to it }
        }

        /** Raw bytes of terminal input per message: well inside a frame once in Base64. */
        const val TERMINAL_PIECE_BYTES = 16 * 1024

        /** Terminal input longer than [TERMINAL_PIECE_BYTES] as several messages in order; anything else as it is. */
        fun terminalPieces(type: String, message: TerminalMessage): List<TerminalMessage> {
            val data = message.data
            if (type != MessageType.TERMINAL_INPUT || data == null || data.length <= Base64Bytes.encodedLength(TERMINAL_PIECE_BYTES)) return listOf(message)
            val bytes = runCatching { java.util.Base64.getDecoder().decode(data) }.getOrNull() ?: return listOf(message)
            return (bytes.indices step TERMINAL_PIECE_BYTES).map { start ->
                val piece = bytes.copyOfRange(start, minOf(bytes.size, start + TERMINAL_PIECE_BYTES))
                message.copy(data = java.util.Base64.getEncoder().encodeToString(piece))
            }
        }
        private val recordsSerializer = MapSerializer(String.serializer(), PairedRecord.serializer())
        /**
         * Macs are always dialed. Between two phones only the one with the smaller ID dials,
         * so they don't connect twice, and never itself.
         */
        fun shouldDial(ownId: String, attributes: Map<String, String>, phonesEnabled: Boolean): Boolean {
            if (attributes["platform"] != "android") return true
            val id = attributes["id"] ?: return false
            return phonesEnabled && ownId < id
        }

        val PHONE_MESSAGES = setOf(
            MessageType.HELLO, MessageType.PAIR_REQUEST, MessageType.PAIR_PAKE, MessageType.PAIR_ACCEPT, MessageType.PAIR_REJECT, MessageType.UNPAIR,
            MessageType.INFO, MessageType.CLIPBOARD, MessageType.CLIPBOARD_ACK, MessageType.RING, MessageType.PING, MessageType.PONG,
        )
    }
}
