package id.phonelink.android.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** One TLS connection with one Mac. [readLoop] blocks until the connection drops. */
class PeerConnection private constructor(
    private val socket: SSLSocket,
    val peerId: String,
) {
    private val input = DataInputStream(BufferedInputStream(socket.inputStream))
    private val output = DataOutputStream(BufferedOutputStream(socket.outputStream))

    @Volatile var hello: Hello? = null

    /** Returns false if sending failed, in which case the connection is closed right away. */
    fun send(envelope: Envelope): Boolean =
        runCatching { Frames.write(output, envelope) }.onFailure { close() }.isSuccess

    fun readLoop(onMessage: (Envelope) -> Unit) {
        while (true) {
            Frames.read(input)?.let(onMessage)
        }
    }

    fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000

        // Heartbeat every 25 seconds. With no data for this long, the connection counts as dead.
        private const val READ_TIMEOUT_MS = 75_000

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
