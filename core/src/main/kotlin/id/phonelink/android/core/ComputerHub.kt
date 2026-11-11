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
data class HubPhone(
    val id: String,
    val name: String,
    val model: String,
    val isPaired: Boolean,
    val isConnected: Boolean,
    val info: DeviceInfo? = null,
    val features: List<String> = emptyList(),
    val platform: String = "android",
) {
    /** A Mac, or another Windows or Linux PC. They share the clipboard, files, and ringing, and nothing more. */
    val isComputer: Boolean get() = platform in PhoneLinkProtocol.COMPUTERS
}

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
    /** Whether this computer can move the pointer and type. False on Linux under Wayland, which forbids it. */
    val canControl: Boolean get() = false
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

    val fileTransfers = FileTransfers(
        scope, fileSinks, File(directory, "transfers.json"),
        linkFor = ::pairedConnection,
        post = { id, envelope -> pairedConnection(id)?.post(envelope) ?: false },
    ) { id, envelope -> send(id, envelope) }

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
            isPairing = { id -> _pairing.value?.phoneId == id },
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
                if (soundFrom == id) { soundFrom = null; events.phoneSoundEnded(id) }
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
                return
            }
            MessageType.PONG -> return
            MessageType.PAIR_REQUEST, MessageType.PAIR_ACCEPT, MessageType.PAIR_REJECT -> return pairingMessage(connection, envelope)
            MessageType.UNPAIR -> {
                synchronized(lock) { removeRecord(id); publish() }
                return
            }
        }
        // Everything else needs a paired phone on its current connection.
        if (!paired) return
        if (isComputer(connection)) return computerMessage(connection, envelope)
        if (envelope.type.startsWith("file.")) {
            fileTransfers.handle(id, envelope.type, envelope.file)
            return
        }
        when (envelope.type) {
            MessageType.INFO -> envelope.info?.let { infos[id] = it; synchronized(lock) { publish() } }
            MessageType.FEATURES -> {
                val list = envelope.features.orEmpty()
                features[id] = list
                if ("notifications" !in list) _notifications.update { it - id }
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
                envelope.audio?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }?.let { events.phoneSoundChunk(id, it) }
            }
            MessageType.AUDIO_STOPPED -> if (soundFrom == id) { soundFrom = null; events.phoneSoundEnded(id) }
            // This computer can't send its own sound yet; the phone shows why.
            MessageType.AUDIO_LISTEN -> connection.post(Envelope(MessageType.AUDIO_STOPPED, error = "This PC can't send its sound yet. Sound from the phone to the PC works."))
        }
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
        // The newest connection from a phone replaces the old one, as on the Mac.
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
                if (!clipboardFile(id, envelope.type, file)) fileTransfers.handle(id, envelope.type, file)
            }
        }
    }

    /** Handles a picture for the clipboard. Returns false for an ordinary file. */
    private fun clipboardFile(id: String, type: String, file: FileMessage): Boolean {
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
                val bytes = runCatching { java.util.Base64.getDecoder().decode(file.data.orEmpty()) }.getOrNull()
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
        if (records.containsKey(id) || !isComputer(connection) || _pairing.value != null) return@lockedInBackground
        send(connection, Envelope(MessageType.PAIR_REQUEST))
        _pairing.value = HubPairing(id, hello.name, code = "", typing = true)
        startPairingTimeout(id)
    }

    /** The code shown on the other computer. A code already on its way isn't sent again. */
    fun submitCode(typed: String): Unit = lockedInBackground {
        val prompt = _pairing.value?.takeIf { it.typing && !it.localAccepted } ?: return@lockedInBackground
        val digits = typed.filter(Char::isDigit)
        if (digits.length != 6) return@lockedInBackground
        val connection = connections[prompt.phoneId] ?: return@lockedInBackground
        _pairing.value = prompt.copy(error = null, localAccepted = true)
        send(connection, Envelope(MessageType.PAIR_ACCEPT, key = Pairing.proof(digits, identity.deviceId, prompt.phoneId)))
    }

    private fun startPairingTimeout(id: String) {
        pairingTimeout?.cancel()
        pairingTimeout = scope.launch(Dispatchers.IO) {
            delay(PhoneLinkProtocol.PAIRING_TIMEOUT_MS)
            locked { if (_pairing.value?.phoneId == id) rejectCurrentPairing() }
        }
    }

    private fun Outgoing.completePairing(connection: PeerConnection, hello: Hello) {
        records[connection.peerId] = Record(hello.name, hello.model, hello.platform)
        saveRecords()
        pairingGuard.succeeded(connection.peerId)
        clearPairing()
        send(connection, Envelope(MessageType.INFO, info = localInfo()))
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
                val current = _pairing.value
                if (current != null && current.phoneId != id) {
                    send(connection, Envelope(MessageType.PAIR_REJECT))
                    return@locked
                }
                // Asked again while the code shows: the same code stays, and so do the wrong tries.
                if (current != null && !current.typing) return@locked
                // Both computers asked at once: the smaller ID shows the code, the other types it.
                if (current != null && current.typing && identity.deviceId > id) return@locked
                if (hello.protocolVersion < Pairing.TYPED_SINCE) {
                    send(connection, Envelope(MessageType.PAIR_REJECT, error = "Update DyskLink on the phone, then pair again."))
                    return@locked
                }
                val wait = pairingGuard.waitMs(id)
                if (wait > 0) {
                    if (current != null) clearPairing()
                    send(connection, Envelope(MessageType.PAIR_REJECT, error = PairingGuard.waitMessage(wait)))
                    return@locked
                }
                _pairing.value = HubPairing(id, hello.name, Pairing.newCode())
                startPairingTimeout(id)
            }
            MessageType.PAIR_ACCEPT -> {
                val prompt = _pairing.value?.takeIf { it.phoneId == id } ?: return@locked
                // Typed here: the other computer checked the code and agrees, but only once a code was actually sent.
                if (prompt.typing) {
                    if (prompt.localAccepted) completePairing(connection, hello)
                    return@locked
                }
                if (envelope.key == Pairing.proof(prompt.code, identity.deviceId, id)) {
                    send(connection, Envelope(MessageType.PAIR_ACCEPT))
                    completePairing(connection, hello)
                } else if (pairingGuard.wrong(id)) {
                    CoreLog.w(TAG, "PAIRING_LOCKED $id")
                    rejectCurrentPairing(PairingGuard.waitMessage(pairingGuard.waitMs(id)))
                } else {
                    _pairing.value = prompt.copy(attempts = prompt.attempts + 1, error = "A wrong code was typed on ${prompt.phoneName}.")
                    send(connection, Envelope(MessageType.PAIR_REJECT, error = Pairing.WRONG_CODE))
                }
            }
            MessageType.PAIR_REJECT -> {
                val prompt = _pairing.value?.takeIf { it.phoneId == id } ?: return@locked
                if (prompt.typing && envelope.error == Pairing.WRONG_CODE) {
                    _pairing.value = prompt.copy(attempts = prompt.attempts + 1, localAccepted = false, error = "That code is wrong. Check it on ${prompt.phoneName}.")
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

    private fun clearPairing() {
        pairingTimeout?.cancel()
        pairingTimeout = null
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
        if (records.remove(id) != null) saveRecords()
        infos.remove(id)
        features.remove(id)
        _notifications.update { it - id }
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
            HubPhone(
                id = id,
                name = hello?.name ?: record?.name ?: "Phone",
                model = hello?.model ?: record?.model ?: "",
                isPaired = record != null,
                isConnected = connections[id]?.hello != null,
                info = infos[id],
                features = features[id].orEmpty(),
                platform = record?.platform ?: hello?.platform ?: "android",
            )
        }.sortedWith(compareByDescending<HubPhone> { it.isPaired }.thenBy { it.name })
    }

    private companion object {
        const val TAG = "DyskLink"
        const val MAX_NOTIFICATIONS = 100
        const val CLIPBOARD_KIND = "clipboard"
        /** As on the Mac: bigger pictures stay where they were copied. */
        const val MAX_CLIP_IMAGE_BYTES = 24L * 1024 * 1024
        const val CHUNK_BYTES = 64 * 1024
        const val COMPUTER_RETRY_MS = 20_000L
        const val PING_INTERVAL_MS = 25_000L
        val recordsSerializer = MapSerializer(String.serializer(), Record.serializer())
    }
}
