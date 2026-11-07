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
data class PairedRecord(val name: String, val platform: String, val model: String, val pairedAt: Long)

data class Peer(
    val id: String,
    val name: String,
    val platform: String,
    val model: String,
    val isPaired: Boolean,
    val isConnected: Boolean,
    val info: DeviceInfo?,
)

data class PairingPrompt(
    val peerId: String,
    val peerName: String,
    val code: String,
    val startedByPeer: Boolean,
    val localAccepted: Boolean = false,
)

data class ManualTarget(val host: String, val port: Int) {
    val key: String get() = "manual:$host:$port"
    val label: String get() = "$host:$port"
}

/** Actions run on the phone when the Mac asks for them. */
interface DeviceActions {
    fun setClipboard(text: String)
    fun media(action: String)
}

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
    private val identity = LocalIdentity.loadOrCreate(directory)
    private val sslContext = identity.sslContext()

    private val targets = ConcurrentHashMap<String, Job>()
    private val connections = HashMap<String, PeerConnection>()
    private val records = HashMap<String, PairedRecord>()
    private val infos = HashMap<String, DeviceInfo>()
    private val remoteAccepted = HashSet<String>()
    private val activeHosts = HashSet<String>()
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

    val fileTransfers = FileTransfers(scope, fileSinks, ::sendTo)

    val phone = PhoneBridge(
        directory, scope, phoneData, fileTransfers,
        pairedPeers = { synchronized(lock) { connections.keys.filter { it in records } } },
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

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    val localDeviceId: String get() = identity.deviceId

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

    fun addDiscovered(serviceName: String, host: String, port: Int) {
        val key = "nsd:$serviceName"
        targets.remove(key)?.cancel()
        startTarget(key, host, port)
    }

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
                    if (runConnection(connection, host)) backoffMs = 2_000L
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

    /** Returns false if the connection was refused because that device is already connected. */
    private fun runConnection(connection: PeerConnection, host: String): Boolean {
        val id = connection.peerId
        synchronized(lock) {
            // The same Mac can be found through NSD, scanning, and a manual address at once.
            // Duplicates are closed before hello, so the Mac doesn't replace the active connection.
            if (connections.containsKey(id)) {
                connection.close()
                return false
            }
            connections[id] = connection
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
                        phone.peerReady(id)
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
                if (_prompt.value?.peerId == id) {
                    remoteAccepted.add(id)
                    finishPairingIfReady(id)
                }
            }
            MessageType.PAIR_REJECT -> {
                if (_prompt.value?.peerId == id) clearPairing()
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
                    publishPeers()
                }
            }
            MessageType.CLIPBOARD -> {
                val text = envelope.text
                if (records.containsKey(id) && _clipboardEnabled.value && !text.isNullOrEmpty() &&
                    text.length <= PhoneLinkProtocol.MAX_CLIPBOARD_CHARS
                ) {
                    lastClipboard = text
                    runCatching { actions.setClipboard(text) }
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

    fun acceptPairing() = onIo {
        synchronized(lock) {
            val prompt = _prompt.value ?: return@synchronized
            val connection = connections[prompt.peerId] ?: return@synchronized
            _prompt.value = prompt.copy(localAccepted = true)
            connection.send(Envelope(MessageType.PAIR_ACCEPT))
            finishPairingIfReady(prompt.peerId)
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
            connection.hello != null && id !in records && id !in autoAsked && id !in declined
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
            if (current.peerId != id) connection.send(Envelope(MessageType.PAIR_REJECT))
            return
        }
        remoteAccepted.remove(id)
        _prompt.value = PairingPrompt(id, hello.name, Pairing.code(identity.deviceId, id), startedByPeer)
        pairingTimeout?.cancel()
        pairingTimeout = scope.launch(Dispatchers.IO) {
            delay(PhoneLinkProtocol.PAIRING_TIMEOUT_MS)
            synchronized(lock) {
                if (_prompt.value?.peerId == id) rejectCurrentPairing()
            }
        }
    }

    private fun finishPairingIfReady(id: String) {
        val prompt = _prompt.value ?: return
        if (prompt.peerId != id || !prompt.localAccepted || id !in remoteAccepted) return
        val connection = connections[id] ?: return
        val hello = connection.hello ?: return
        records[id] = PairedRecord(hello.name, hello.platform, hello.model, System.currentTimeMillis())
        saveRecords()
        clearPairing()
        connection.send(Envelope(MessageType.INFO, info = localInfo()))
        phone.peerReady(id)
        publishPeers()
    }

    private fun clearPairing() {
        pairingTimeout?.cancel()
        pairingTimeout = null
        _prompt.value?.let { remoteAccepted.remove(it.peerId) }
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

    /** Sends directly, without the queue. Returns false if the device isn't connected. */
    fun sendDirect(peerId: String, envelope: Envelope): Boolean = sendTo(peerId, envelope)

    fun sendInput(peerId: String, event: InputEvent) {
        outbox.trySend(peerId to Envelope(MessageType.INPUT, input = event))
    }

    fun sendMedia(peerId: String, action: String) {
        outbox.trySend(peerId to Envelope(MessageType.MEDIA, media = action))
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
                platform = hello?.platform ?: record?.platform ?: "macos",
                model = hello?.model ?: record?.model ?: "",
                isPaired = record != null,
                isConnected = connections.containsKey(id),
                info = infos[id],
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

    private companion object {
        const val TAG = "PhoneLink"
        const val CLIPBOARD_RECEIPT_MS = 4_000L
        const val CLIPBOARD_RETRY_MS = 26_000L
        val recordsSerializer = MapSerializer(String.serializer(), PairedRecord.serializer())
    }
}
