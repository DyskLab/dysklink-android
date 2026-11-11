package id.phonelink.android.core

import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Strangers on the network: how many may connect, and how long they may stay without pairing. */
class InboundLimitsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest
    fun cleanUp() {
        closers.forEach { runCatching(it) }
        scope.cancel()
    }

    @Test
    fun limiterCapsTotalAndPerAddress() {
        val limiter = ConnectionLimiter(maxTotal = 3, maxPerHost = 2)
        val a1 = assertNotNull(limiter.acquire("10.0.0.1"))
        assertNotNull(limiter.acquire("10.0.0.1"))
        assertNull(limiter.acquire("10.0.0.1"), "A third from the same address must be refused")
        val b1 = assertNotNull(limiter.acquire("10.0.0.2"))
        assertNull(limiter.acquire("10.0.0.3"), "The total cap must hold across addresses")
        a1.release()
        a1.release() // Twice gives back only one place.
        assertEquals(2, limiter.inUse)
        assertNotNull(limiter.acquire("10.0.0.3"))
        b1.release()
        assertNotNull(limiter.acquire("10.0.0.1"))
        assertNull(limiter.acquire("10.0.0.1"))
    }

    private fun hub(helloMs: Long, idleMs: Long): ComputerHub {
        val dir = Files.createTempDirectory("dysk-limits").toFile()
        return ComputerHub(
            dir, scope,
            localHello = { paired -> Hello("Test PC", "windows", "Windows 11", PhoneLinkProtocol.VERSION, paired, "1.0.0") },
            events = object : HubEvents {
                override fun clipboard(text: String, from: String) = true
                override fun ring(on: Boolean, from: String) {}
            },
            fileSinks = { _, _ -> error("unused") },
            port = 0,
            helloTimeoutMs = helloMs,
            unpairedIdleMs = idleMs,
        ).also { it.start(); closers += it::shutdown }
    }

    /** A stranger's connection, read on its own thread. [ended] is when the hub hung up, or null while it's open. */
    private inner class Stranger(port: Int) {
        val connection = PeerConnection.connect(LocalIdentity.loadOrCreate(Files.createTempDirectory("dysk-stranger").toFile()).sslContext(), "127.0.0.1", port)
        @Volatile var ended: Long? = null
        val started = System.currentTimeMillis()

        init {
            closers += connection::close
            thread(isDaemon = true) {
                runCatching { connection.readLoop { } }
                ended = System.currentTimeMillis()
            }
        }

        fun hello() = connection.send(Envelope(MessageType.HELLO, hello = Hello("Stranger", "android", "X", PhoneLinkProtocol.VERSION, false)))
    }

    private fun waitUntil(message: String, seconds: Int = 10, check: () -> Boolean) {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError(message)
            Thread.sleep(20)
        }
    }

    @Test
    fun aDeviceThatNeverSaysHelloIsHungUpOn() {
        val pc = hub(helloMs = 300, idleMs = 60_000)
        val silent = Stranger(pc.boundPort)
        waitUntil("A silent connection must be closed") { silent.ended != null }
        assertTrue(silent.ended!! - silent.started < 5_000)
    }

    @Test
    fun anUnpairedDeviceIsDroppedWhenNothingIsPairing() {
        val pc = hub(helloMs = 1_000, idleMs = 400)
        val stranger = Stranger(pc.boundPort)
        assertTrue(stranger.hello())
        waitUntil("The stranger should show up") { pc.phones.value.any { it.isConnected } }
        waitUntil("An unpaired, idle device must be dropped") { stranger.ended != null }
        waitUntil("It should leave the list") { pc.phones.value.none { it.isConnected } }
    }

    @Test
    fun onlyTwoUnpairedConnectionsFromOneAddress() {
        val pc = hub(helloMs = 5_000, idleMs = 60_000)
        val first = Stranger(pc.boundPort).also { it.hello() }
        val second = Stranger(pc.boundPort).also { it.hello() }
        waitUntil("Both should be connected") { pc.phones.value.count { it.isConnected } == 2 }
        val third = Stranger(pc.boundPort)
        waitUntil("A third from the same address must be refused") { third.ended != null }
        assertNull(first.ended)
        assertNull(second.ended)
        // A place frees up when one leaves.
        first.connection.close()
        waitUntil("The first should be gone") { pc.phones.value.count { it.isConnected } == 1 }
        val fourth = Stranger(pc.boundPort).also { it.hello() }
        waitUntil("A new one fits again") { pc.phones.value.count { it.isConnected } == 2 }
        assertFalse(fourth.ended != null)
    }
}
