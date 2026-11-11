package id.phonelink.android.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * One TLS connection with one Mac or phone. [readLoop] blocks until the connection drops.
 * [inbound] connections were opened by another phone to this one; Macs never open those.
 */
class PeerConnection private constructor(
    private val socket: SSLSocket,
    val peerId: String,
    val inbound: Boolean = false,
) {
    private val input = DataInputStream(BufferedInputStream(socket.inputStream))
    private val writer = FrameWriter(BufferedOutputStream(socket.outputStream), peerId.take(8)) {
        runCatching { socket.close() }
    }

    @Volatile var hello: Hello? = null

    /**
     * Waits like a direct write: a live message until it's written, a file message until the file window has room.
     * Returns false if the connection is closed or closing, or if the message was too big to send, in which case the
     * connection stays open. Writing happens on this connection's own thread, never the caller's.
     */
    fun send(envelope: Envelope): Boolean = writer.send(envelope)

    /** Queues without waiting, for the main thread and for messages to several peers. False if it can't be queued. */
    fun post(envelope: Envelope): Boolean = writer.post(envelope)

    /** A pre-encoded `file.*` frame, behind live messages. Waits for room in the file window. */
    fun sendFrame(frame: OutFrame): Boolean = writer.sendBulk(frame)

    fun readLoop(onMessage: (Envelope) -> Unit) {
        val reader = FrameReader(input)
        try {
            while (true) {
                val envelope = reader.read()
                // Closed here: what still arrives isn't handled, as if the socket had closed at once.
                if (writer.isClosing) throw java.io.IOException("Connection closed")
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
