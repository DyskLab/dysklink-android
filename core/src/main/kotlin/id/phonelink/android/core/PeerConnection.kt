package id.phonelink.android.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One TLS connection with one Mac or phone. [readLoop] blocks until the connection drops.
 * [inbound] connections were opened by another phone to this one; Macs never open those.
 */
class PeerConnection private constructor(
    private val socket: SSLSocket,
    val peerId: String,
    val inbound: Boolean = false,
) : TransferLink {
    private val input = DataInputStream(BufferedInputStream(socket.inputStream))
    private val writer = FrameWriter(BufferedOutputStream(socket.outputStream), peerId.take(8)) {
        runCatching { socket.close() }
    }

    @Volatile var hello: Hello? = null

    /** When the other side last sent anything, its `pong` included. Set by [readLoop]. */
    @Volatile var lastHeardAt: Long = System.currentTimeMillis()
        private set

    /** The other side is this computer itself. A phone that dials in this way comes through `adb reverse`, over USB. */
    val isLoopback: Boolean = runCatching { socket.inetAddress?.isLoopbackAddress == true }.getOrDefault(false)

    /**
     * Waits like a direct write: a live message until it's written, a file message until the file window has room.
     * Returns false if the connection is closed or closing, or if the message was too big to send, in which case the
     * connection stays open. Writing happens on this connection's own thread, never the caller's.
     */
    override fun send(envelope: Envelope): Boolean = writer.send(envelope)

    /** Queues without waiting, for the main thread and for messages to several peers. False if it can't be queued. */
    fun post(envelope: Envelope): Boolean = writer.post(envelope)

    /** A pre-encoded `file.*` frame, behind live messages. Waits for room in the file window. */
    override fun sendFrame(frame: OutFrame): Boolean = writer.sendBulk(frame)

    /** False once [close] was called or the connection broke. */
    override val isOpen: Boolean get() = !writer.isClosing

    /** Frame messages with a payload go out binary: both hellos listed `bin1`. See [BinaryFrames]. */
    override val binary: Boolean get() = writer.binary

    fun readLoop(onMessage: (Envelope) -> Unit) {
        val reader = FrameReader(input)
        var helloSeen = false
        try {
            while (true) {
                val envelope = reader.read()
                lastHeardAt = System.currentTimeMillis()
                // Closed here: what still arrives isn't handled, as if the socket had closed at once.
                if (writer.isClosing) throw java.io.IOException("Connection closed")
                // Only the first hello counts, as everywhere else.
                if (envelope?.type == MessageType.HELLO && !helloSeen) {
                    helloSeen = true
                    writer.peerBinary = BinaryFrames.supports(envelope.hello)
                }
                envelope?.let(onMessage)
            }
        } finally {
            writer.close()
        }
    }

    /** Hangs up. Live messages already queued, such as a rejection, still go out first, for at most a second. */
    fun close() {
        writer.close()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000

        // Heartbeat every 25 seconds. With no data for this long, the connection counts as dead.
        private const val READ_TIMEOUT_MS = 75_000

        /** Finishes a connection another phone opened. It has to show its certificate, which is its ID. */
        fun accept(raw: Socket, context: SSLContext): PeerConnection {
            try {
                raw.tcpNoDelay = true
                raw.keepAlive = true
                raw.soTimeout = CONNECT_TIMEOUT_MS
                val host = raw.inetAddress.hostAddress
                val socket = context.socketFactory.createSocket(raw, host, raw.port, true) as SSLSocket
                socket.useClientMode = false
                socket.needClientAuth = true
                socket.startHandshake()
                socket.soTimeout = READ_TIMEOUT_MS
                val peerId = sha256Hex(socket.session.peerCertificates.first().encoded)
                return PeerConnection(socket, peerId, inbound = true)
            } catch (error: Exception) {
                runCatching { raw.close() }
                throw error
            }
        }

        fun connect(context: SSLContext, host: String, port: Int): PeerConnection {
            val raw = Socket()
            try {
                raw.tcpNoDelay = true
                raw.keepAlive = true
                raw.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                val socket = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
                socket.useClientMode = true
                socket.soTimeout = READ_TIMEOUT_MS
                socket.startHandshake()
                val peerId = sha256Hex(socket.session.peerCertificates.first().encoded)
                return PeerConnection(socket, peerId)
            } catch (error: Exception) {
                runCatching { raw.close() }
                throw error
            }
        }
    }
}

/**
 * Caps connections from devices that aren't paired, in total and from each address, so strangers on the network
 * can't tie up threads. Paired devices aren't counted once they're known.
 */
internal class ConnectionLimiter(private val maxTotal: Int, private val maxPerHost: Int) {
    private val perHost = HashMap<String, Int>()
    private var total = 0

    /** One place, given back with [Slot.release]. Null when either cap is reached. */
    @Synchronized
    fun acquire(host: String): Slot? {
        val count = perHost[host] ?: 0
        if (total >= maxTotal || count >= maxPerHost) return null
        perHost[host] = count + 1
        total++
        return Slot(host)
    }

    @get:Synchronized
    val inUse: Int get() = total

    @Synchronized
    private fun free(host: String) {
        val count = perHost[host] ?: return
        if (count <= 1) perHost.remove(host) else perHost[host] = count - 1
        total--
    }

    inner class Slot(private val host: String) {
        private val released = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Safe to call more than once; only the first gives the place back. */
        fun release() {
            if (released.compareAndSet(false, true)) free(host)
        }
    }

    companion object {
        /** TLS handshakes in progress, which strangers can start too. */
        const val MAX_HANDSHAKES = 16
        const val MAX_HANDSHAKES_PER_HOST = 4
        /** Connected but not paired. */
        const val MAX_UNPAIRED = 8
        const val MAX_UNPAIRED_PER_HOST = 2
        /** A device that connected and said nothing for this long is hung up on. */
        const val HELLO_TIMEOUT_MS = 10_000L
    }
}

/**
 * Takes the connections other devices open to this one, each on its own thread rather than the shared IO pool, so
 * strangers can't starve everything else. Handshakes and unpaired connections are capped in total and per address;
 * an unpaired device must say hello within [helloTimeoutMs], and one that sits unpaired with no pairing going on is
 * hung up on after [idleMs]. It can dial again.
 */
internal class InboundServer(
    private val socket: ServerSocket,
    private val sslContext: SSLContext,
    private val scope: CoroutineScope,
    private val name: String,
    private val isPaired: (peerId: String) -> Boolean,
    private val isPairing: (peerId: String) -> Boolean,
    private val helloTimeoutMs: Long = ConnectionLimiter.HELLO_TIMEOUT_MS,
    private val idleMs: Long = PhoneLinkProtocol.PAIRING_TIMEOUT_MS,
    private val serve: (connection: PeerConnection, host: String) -> Unit,
) {
    private val handshakes = ConnectionLimiter(ConnectionLimiter.MAX_HANDSHAKES, ConnectionLimiter.MAX_HANDSHAKES_PER_HOST)
    private val unpaired = ConnectionLimiter(ConnectionLimiter.MAX_UNPAIRED, ConnectionLimiter.MAX_UNPAIRED_PER_HOST)

    fun start() {
        thread(isDaemon = true, name = "dysklink-accept-$name") { acceptLoop() }
    }

    private fun acceptLoop() {
        while (!socket.isClosed) {
            val raw = try { socket.accept() } catch (_: Exception) { break }
            val host = raw.inetAddress?.hostAddress ?: ""
            val handshake = handshakes.acquire(host)
            if (handshake == null) {
                CoreLog.w(TAG, "TOO_MANY_HANDSHAKES $host")
                runCatching { raw.close() }
                continue
            }
            thread(isDaemon = true, name = "dysklink-peer-$name") {
                val connection = try {
                    PeerConnection.accept(raw, sslContext)
                } catch (error: Exception) {
                    CoreLog.w(TAG, "A device failed to connect", error)
                    null
                } finally {
                    handshake.release()
                }
                if (connection != null) run(connection, host)
            }
        }
    }

    private fun run(connection: PeerConnection, host: String) {
        var slot: ConnectionLimiter.Slot? = null
        if (!isPaired(connection.peerId)) {
            slot = unpaired.acquire(host)
            if (slot == null) {
                CoreLog.w(TAG, "TOO_MANY_UNPAIRED $host")
                connection.close()
                return
            }
        }
        val watch = slot?.let { place -> scope.launch { supervise(connection, place) } }
        try {
            serve(connection, host)
        } finally {
            watch?.cancel()
            slot?.release()
        }
    }

    private suspend fun supervise(connection: PeerConnection, slot: ConnectionLimiter.Slot) {
        delay(helloTimeoutMs)
        if (connection.hello == null) {
            CoreLog.w(TAG, "NO_HELLO ${connection.peerId.take(8)}")
            connection.close()
            return
        }
        while (connection.isOpen) {
            delay(idleMs)
            if (!connection.isOpen) return
            if (isPaired(connection.peerId)) {
                slot.release()
                return
            }
            if (!isPairing(connection.peerId)) {
                CoreLog.i(TAG, "UNPAIRED_IDLE ${connection.peerId.take(8)}")
                connection.close()
                return
            }
        }
    }

    private companion object {
        const val TAG = "PhoneLink"
    }
}
