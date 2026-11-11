package id.phonelink.android.core

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    /** Another phone, paired for files, clipboard, ringing, and battery only. */
    val isPhone: Boolean get() = platform != "macos"
    val isMac: Boolean get() = !isPhone
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
    /** The other device has an older DyskLink that can't type codes. */
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
    phoneData: PhoneData,
) {
    private val lock = Any()
    private val identity = LocalIdentity.loadOrCreate(directory).also {
        Log.i("PhoneLink", "IDENTITY keystore=${it.isHardwareBacked}")
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

    private val recordsFile = File(directory, "paired.json")
    private val manualFile = File(directory, "manual.json")
    private val declinedFile = File(directory, "declined.json")

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _prompt = MutableStateFlow<PairingPrompt?>(null)
    val prompt: StateFlow<PairingPrompt?> = _prompt.asStateFlow()

    private val _manualTargets = MutableStateFlow<List<ManualTarget>>(emptyList())
    val manualTargets: StateFlow<List<ManualTarget>> = _manualTargets.asStateFlow()

    // The queue keeps messages in order and moves sending off the main thread.
    private val outbox = Channel<Pair<String, Envelope>>(Channel.UNLIMITED)

    val fileTransfers = FileTransfers(scope, fileSinks, File(directory, "transfers.json"), ::sendTo)

    val phone = PhoneBridge(
        directory, scope, phoneData, fileTransfers,
        pairedPeers = { synchronized(lock) { connections.keys.filter { it in records && !isPhone(it) } } },
        send = { peerId, envelope -> outbox.trySend(peerId to envelope) },
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
        scope.launch(Dispatchers.IO) {
            for ((peerId, envelope) in outbox) sendTo(peerId, envelope)
        }
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
            var backoffMs = 2_000L
            while (isActive) {
                try {
                    val connection = PeerConnection.connect(sslContext, host, port)
                    _lastError.value = null
                    // Duplicate connections are refused and don't reset the backoff, to avoid reconnecting nonstop.
                    // While USB carries the connection, Wi-Fi checks back often so unplugging is quick to recover.
                    if (runConnection(connection, host)) backoffMs = 2_000L
                    else if (synchronized(lock) { connectionHosts.containsValue(PhoneLinkProtocol.USB_HOST) }) backoffMs = 2_500L
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w(TAG, "Failed to connect to $host:$port", error)
                    if (key.startsWith("manual:")) {
                        _lastError.value = "Can't connect to $host:$port"
                    }
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    /**
     * Keeps trying the USB port. Nothing listens there until a Mac runs `adb reverse`, so a failed try costs almost
     * nothing; once the cable is in, the phone moves to it within a few seconds.
     */
    fun startUsb() {
        if (targets.containsKey(USB_KEY)) return
        targets[USB_KEY] = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val connection = runCatching {
                    PeerConnection.connect(sslContext, PhoneLinkProtocol.USB_HOST, PhoneLinkProtocol.USB_PORT)
                }.getOrNull()
                if (connection != null) {
                    Log.i(TAG, "USB connection")
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
                .onFailure { Log.w(TAG, "Failed to check $host:$port", it) }
                .getOrNull() ?: return@launch
            Log.i(TAG, "Scan found $host")
            runConnection(connection, host)
        }
    }

    // Other phones

    /** Listens for phones that dial this one. Returns once listening; connections run on [scope]. */
    fun startServer() {
        if (!_phonesEnabled.value || server != null) return
        val socket = runCatching { java.net.ServerSocket(PhoneLinkProtocol.PHONE_PORT) }
            .recoverCatching { java.net.ServerSocket(0) }
            .onFailure { Log.w(TAG, "Can't listen for phones", it) }
            .getOrNull() ?: return
        server = socket
        _serverPort.value = socket.localPort
        scope.launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                val raw = runCatching { socket.accept() }.getOrNull() ?: break
                launch {
                    val host = raw.inetAddress.hostAddress ?: ""
                    val connection = runCatching { PeerConnection.accept(raw, sslContext) }
                        .onFailure { Log.w(TAG, "A phone failed to connect", it) }
                        .getOrNull() ?: return@launch
                    runConnection(connection, host)
                }
            }
        }
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

    /** Inbound connections are always phones, since Macs never dial a phone. */
    private fun isPhone(connection: PeerConnection): Boolean =
        connection.inbound || (connection.hello?.platform ?: records[connection.peerId]?.platform)?.let { it != "macos" } == true

    private fun isPhone(id: String): Boolean = connections[id]?.let(::isPhone) ?: (records[id]?.platform?.let { it != "macos" } == true)

    /** Returns false if the connection was refused because that device is already connected. */
    private fun runConnection(connection: PeerConnection, host: String): Boolean {
        val id = connection.peerId
        synchronized(lock) {
            // The same Mac can be found through NSD, scanning, and a manual address at once.
            // Duplicates are closed before hello, so the Mac doesn't replace the active connection.
            val existing = connections[id]
            if (existing != null) {
                // A cable beats Wi-Fi: the USB connection takes over, and Wi-Fi comes back when it's unplugged.
                val usbTakesOver = host == PhoneLinkProtocol.USB_HOST && connectionHosts[id] != PhoneLinkProtocol.USB_HOST
                if (!usbTakesOver) {
                    connection.close()
                    return false
                }
                connectionHosts[id]?.let(activeHosts::remove)
                connections.remove(id)
                existing.close()
            }
            connections[id] = connection
            connectionHosts[id] = host
            activeHosts.add(host)
            Log.i(TAG, "Connected to $host")
            connection.send(Envelope(MessageType.HELLO, hello = localHello(records.containsKey(id))))
        }
        try {
            connection.readLoop { handle(connection, it) }
        } catch (_: Exception) {
            // Connection lost. Retries are handled by startTarget.
        } finally {
            connection.close()
            fileTransfers.onDisconnected(id)
            synchronized(lock) {
                if (connections[id] === connection) {
                    connections.remove(id)
                    connectionHosts.remove(id)
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
        if (isPhone(connection) && !PHONE_MESSAGES.contains(envelope.type) && !envelope.type.startsWith("file.")) return
        if (isPhone(connection) && !_phonesEnabled.value) return connection.close()
        // Files are written outside the lock so disk writes don't hold up other messages and actions.
        if (envelope.type.startsWith("file.")) {
            if (synchronized(lock) { records.containsKey(connection.peerId) }) {
                fileTransfers.handle(connection.peerId, envelope.type, envelope.file)
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

    private fun handleControl(connection: PeerConnection, envelope: Envelope) = synchronized(lock) {
        val id = connection.peerId
        when (envelope.type) {
            MessageType.HELLO -> {
                val hello = envelope.hello ?: return@synchronized
                connection.hello = hello
                val record = records[id]
                if (record != null) {
                    if (hello.paired) {
                        records[id] = record.copy(name = hello.name, model = hello.model)
                        saveRecords()
                        connection.send(Envelope(MessageType.INFO, info = localInfo()))
                        pendingClipboard?.takeIf { id in it.waiting }?.let { connection.send(it.envelope()) }
                        if (!isPhone(connection)) phone.peerReady(id)
                    } else {
                        removeRecord(id)
                    }
                }
                publishPeers()
                autoPair()
            }
            MessageType.PAIR_REQUEST -> {
                if (!records.containsKey(id) && connection.hello != null) beginPairing(id, startedByPeer = true)
            }
            MessageType.PAIR_ACCEPT -> {
                val prompt = _prompt.value?.takeIf { it.peerId == id } ?: return@synchronized
                if (prompt.showsCode) {
                    if (envelope.key == Pairing.proof(prompt.code, identity.deviceId, id)) {
                        connection.send(Envelope(MessageType.PAIR_ACCEPT))
                        completePairing(id)
                    } else if (prompt.attempts + 1 >= Pairing.MAX_ATTEMPTS) {
                        rejectCurrentPairing()
                    } else {
                        _prompt.value = prompt.copy(attempts = prompt.attempts + 1, error = "A wrong code was typed on ${prompt.peerName}.")
                        connection.send(Envelope(MessageType.PAIR_REJECT, error = Pairing.WRONG_CODE))
                    }
                } else if (prompt.localAccepted) {
                    completePairing(id)
                }
            }
            MessageType.PAIR_REJECT -> {
                val prompt = _prompt.value?.takeIf { it.peerId == id } ?: return@synchronized
                if (envelope.error == Pairing.WRONG_CODE && !prompt.showsCode) {
                    _prompt.value = prompt.copy(localAccepted = false, error = "That code isn't right. Check the code on ${prompt.peerName} and try again.")
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
                    if (isPhone(connection)) connection.send(Envelope(MessageType.CLIPBOARD_ACK, key = envelope.key, error = if (ok) null else "failed"))
                } else if (records.containsKey(id) && isPhone(connection)) {
                    connection.send(Envelope(MessageType.CLIPBOARD_ACK, key = envelope.key, error = "Clipboard is off"))
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
                    "start" -> runCatching { actions.ring(connection.hello?.name ?: "Your Mac") }
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
            MessageType.PING -> connection.send(Envelope(MessageType.PONG))
            else -> Unit
        }
    }

    // Pairing

    fun requestPairing(id: String) = onIo {
        synchronized(lock) {
            val connection = connections[id] ?: return@synchronized
            if (records.containsKey(id)) return@synchronized
            if (declined.remove(id)) saveDeclined()
            connection.send(Envelope(MessageType.PAIR_REQUEST))
            beginPairing(id, startedByPeer = false)
        }
    }

    /** Sends the code typed on this phone. The other device checks it. */
    fun submitCode(typed: String) = onIo {
        val digits = typed.filter(Char::isDigit)
        synchronized(lock) {
            val prompt = _prompt.value ?: return@synchronized
            if (prompt.showsCode || prompt.outdated || digits.length != 6) return@synchronized
            val connection = connections[prompt.peerId] ?: return@synchronized
            _prompt.value = prompt.copy(localAccepted = true, error = null)
            connection.send(Envelope(MessageType.PAIR_ACCEPT, key = Pairing.proof(digits, identity.deviceId, prompt.peerId)))
        }
    }

    fun rejectPairing() = onIo {
        synchronized(lock) {
            _prompt.value?.let { decline(it.peerId) }
            rejectCurrentPairing()
        }
    }

    /** Called when the app UI appears, because the user needs to see the pairing code. */
    fun tryAutoPair() = onIo {
        synchronized(lock) { autoPair() }
    }

    /**
     * Opens the code screen automatically for a new Mac. The user still has to match the code, because
     * otherwise anyone on the same Wi-Fi could pair. Each Mac is only asked about once per
     * session, and never again if the user declined or unpaired it.
     */
    private fun autoPair() {
        if (!isUiVisible() || _prompt.value != null) return
        val id = connections.entries.firstOrNull { (id, connection) ->
            // Phones only pair when tapped, so a phone doesn't pop up the code on every phone around it.
            connection.hello != null && id !in records && id !in autoAsked && id !in declined && !isPhone(connection)
        }?.key ?: return
        autoAsked.add(id)
        connections[id]?.send(Envelope(MessageType.PAIR_REQUEST))
        beginPairing(id, startedByPeer = false)
    }

    private fun decline(id: String) {
        if (declined.add(id)) saveDeclined()
    }

    fun unpair(id: String) = onIo {
        synchronized(lock) {
            connections[id]?.send(Envelope(MessageType.UNPAIR))
            decline(id)
            removeRecord(id)
            publishPeers()
        }
    }

    private fun rejectCurrentPairing() {
        val prompt = _prompt.value ?: return
        connections[prompt.peerId]?.send(Envelope(MessageType.PAIR_REJECT))
        clearPairing()
    }

    private fun beginPairing(id: String, startedByPeer: Boolean) {
        val connection = connections[id] ?: return
        val hello = connection.hello ?: return
        val current = _prompt.value
        if (current != null) {
            // Only one request is active. Others are refused.
            if (current.peerId != id) {
                connection.send(Envelope(MessageType.PAIR_REJECT))
                return
            }
            // Both asked at once: the smaller ID shows the code, the other types it.
            if (!startedByPeer || current.showsCode || identity.deviceId >= id) return
        }
        val outdated = hello.protocolVersion < Pairing.TYPED_SINCE
        _prompt.value = PairingPrompt(
            id, hello.name, if (startedByPeer) Pairing.newCode() else "", startedByPeer,
            error = if (outdated) "${hello.name} has an older DyskLink. Update it there, then pair again." else null,
            outdated = outdated,
        )
        pairingTimeout?.cancel()
        pairingTimeout = scope.launch(Dispatchers.IO) {
            delay(PhoneLinkProtocol.PAIRING_TIMEOUT_MS)
            synchronized(lock) {
                if (_prompt.value?.peerId == id) rejectCurrentPairing()
            }
        }
    }

    private fun completePairing(id: String) {
        val connection = connections[id] ?: return
        val hello = connection.hello ?: return
        records[id] = PairedRecord(hello.name, hello.platform, hello.model, System.currentTimeMillis())
        saveRecords()
        clearPairing()
        connection.send(Envelope(MessageType.INFO, info = localInfo()))
        if (!isPhone(connection)) phone.peerReady(id)
        publishPeers()
    }

    private fun clearPairing() {
        pairingTimeout?.cancel()
        pairingTimeout = null
        _prompt.value = null
        autoPair()
    }

    // Status

    fun broadcastInfo() = onIo {
        val info = localInfo()
        val paired = synchronized(lock) { connections.filterKeys { it in records }.values.toList() }
        paired.forEach { it.send(Envelope(MessageType.INFO, info = info)) }
    }

    // Features

    private fun sendTo(peerId: String, envelope: Envelope): Boolean {
        val connection = synchronized(lock) { connections[peerId]?.takeIf { peerId in records } } ?: return false
        return connection.send(envelope)
    }

    /** Queues a message, for callers on Android's main thread, which can't write to the network. */
    fun post(peerId: String, envelope: Envelope) {
        outbox.trySend(peerId to envelope)
    }

    /** Sends directly, without the queue. Returns false if the device isn't connected. */
    fun sendDirect(peerId: String, envelope: Envelope): Boolean = sendTo(peerId, envelope)

    fun sendInput(peerId: String, event: InputEvent) {
        outbox.trySend(peerId to Envelope(MessageType.INPUT, input = event))
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
        outbox.trySend(peerId to Envelope(MessageType.MACSCREEN_START, displayId = display))
    }

    /** Makes this phone an extra display of the Mac, [width] by [height] points. */
    fun extendMacScreen(peerId: String, width: Int, height: Int, large: Boolean = false) {
        val mode = if (large) "extend-large" else "extend"
        outbox.trySend(peerId to Envelope(MessageType.MACSCREEN_START, text = mode, screen = ScreenInfo(width, height)))
    }
    fun stopMacScreen(peerId: String) { outbox.trySend(peerId to Envelope(MessageType.MACSCREEN_STOP)) }
    fun syncMacScreen(peerId: String) { outbox.trySend(peerId to Envelope(MessageType.MACSCREEN_SYNC)) }

    /** A tap or drag on the Mac's screen. `x` and `y` range from 0 to 1. */
    fun sendMacPointer(peerId: String, kind: String, x: Float, y: Float) {
        outbox.trySend(peerId to Envelope(MessageType.MACSCREEN_POINTER, control = ControlEvent(kind, x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))))
    }

    fun sendTerminal(peerId: String, type: String, message: TerminalMessage) {
        outbox.trySend(peerId to Envelope(type, terminal = message))
    }

    fun sendMedia(peerId: String, action: String) {
        outbox.trySend(peerId to Envelope(MessageType.MEDIA, media = action))
    }

    /** Asks every connected, paired Mac to lock, or to start or stop Keep Awake. */
    fun sendMacAction(action: String, peerId: String? = null) {
        val targets = peerId?.let { listOf(it) } ?: _peers.value.filter { it.isPaired && it.isConnected && it.isMac }.map { it.id }
        targets.forEach { outbox.trySend(it to Envelope(MessageType.MAC_ACTION, macAction = action)) }
    }

    /** Sends Wake-on-LAN to a paired Mac. False when its address isn't known or nothing could be sent. */
    suspend fun wakeMac(peerId: String): Boolean {
        val record = synchronized(lock) { records[peerId] } ?: return false
        if (record.wakeMacs.isEmpty()) return false
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { WakeOnLan.send(record.wakeMacs, record.wakeBroadcast) }
    }

    fun sendNotchAction(peerId: String, action: NotchAction) {
        outbox.trySend(peerId to Envelope(MessageType.NOTCH_ACTION, notchAction = action))
    }

    /** Makes the Mac play a loud sound for up to 30 seconds. */
    fun ringMac(peerId: String, on: Boolean) {
        outbox.trySend(peerId to Envelope(MessageType.RING, ring = if (on) "start" else "stop"))
        _ringingMacs.value = if (on) _ringingMacs.value + peerId else _ringingMacs.value - peerId
        if (on) scope.launch {
            delay(RING_MS)
            _ringingMacs.value = _ringingMacs.value - peerId
        }
    }

    /** Tells every Mac that asked for a ring that it ended here. */
    fun ringStopped() = onIo {
        val paired = synchronized(lock) { connections.filterKeys { it in records }.values.toList() }
        paired.forEach { it.send(Envelope(MessageType.RING, ring = "stopped")) }
    }

    fun runCommand(peerId: String, commandId: String) {
        _commandResult.value = null
        outbox.trySend(peerId to Envelope(MessageType.COMMAND_RUN, commandId = commandId))
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
        val pending = synchronized(lock) {
            PendingClipboard(UUID.randomUUID().toString(), text, records.keys.toMutableSet(), onResult).also { item ->
                pendingClipboard = item
                item.waiting.filter { it in connections }.forEach { outbox.trySend(it to item.envelope()) }
            }
        }
        scope.launch(Dispatchers.IO) {
            delay(CLIPBOARD_RECEIPT_MS)
            synchronized(lock) {
                if (pendingClipboard === pending) pending.waiting.forEach { connections[it]?.close() }
            }
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
        val all = synchronized(lock) { connections.values.toList() }
        all.forEach { it.send(Envelope(MessageType.PING)) }
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
                platform = hello?.platform ?: record?.platform ?: if (connections[id]?.inbound == true) "android" else "macos",
                model = hello?.model ?: record?.model ?: "",
                isPaired = record != null,
                isConnected = connections.containsKey(id),
                info = infos[id],
                canWake = record?.wakeMacs?.isNotEmpty() == true,
                viaUsb = connections.containsKey(id) && connectionHosts[id] == PhoneLinkProtocol.USB_HOST,
                wakeEnabled = record?.wakeEnabled,
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
            MessageType.HELLO, MessageType.PAIR_REQUEST, MessageType.PAIR_ACCEPT, MessageType.PAIR_REJECT, MessageType.UNPAIR,
            MessageType.INFO, MessageType.CLIPBOARD, MessageType.CLIPBOARD_ACK, MessageType.RING, MessageType.PING, MessageType.PONG,
        )
    }
}
