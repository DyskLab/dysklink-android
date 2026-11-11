package id.phonelink.android.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** The per-connection writer, over in-memory streams that can be slow or stop reading altogether. */
class FrameWriterTest {
    /** Parses the frames written to it, with when each one finished. Can be slowed down, or held shut by [gate]. */
    private class Peer(private val bytesPerMs: Int = 0, private val gate: CountDownLatch? = null) : OutputStream() {
        val frames = LinkedBlockingQueue<Envelope>()
        val log = CopyOnWriteArrayList<String>()
        private var pending = ByteArrayOutputStream()

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            gate?.await()
            if (bytesPerMs > 0 && len > 1) Thread.sleep((len / bytesPerMs).toLong())
            synchronized(this) {
                pending.write(b, off, len)
                var bytes = pending.toByteArray()
                var at = 0
                while (bytes.size - at >= 4) {
                    val length = ByteBuffer.wrap(bytes, at, 4).int
                    if (bytes.size - at - 4 < length) break
                    val envelope = assertNotNull(Frames.decode(bytes.copyOfRange(at + 4, at + 4 + length), length))
                    log += envelope.type
                    frames.put(envelope)
                    at += 4 + length
                }
                pending = ByteArrayOutputStream().apply { write(bytes, at, bytes.size - at) }
                bytes = ByteArray(0)
            }
        }
    }

    private val writers = mutableListOf<FrameWriter>()
    private val gates = mutableListOf<CountDownLatch>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun cleanUp() {
        gates.forEach { it.countDown() }
        writers.forEach { it.close() }
        scope.cancel()
    }

    private fun writer(peer: OutputStream, onClose: () -> Unit = {}) = FrameWriter(peer, "test", onClose).also { writers += it }

    private fun stalled(): Pair<Peer, CountDownLatch> {
        val gate = CountDownLatch(1).also { gates += it }
        return Peer(gate = gate) to gate
    }

    private fun chunk(id: String, data: ByteArray, release: (() -> Unit)? = null): OutFrame {
        val prefix = FileChunkFrame.prefix(id)
        val frame = ByteArray(FileChunkFrame.capacity(prefix, data.size))
        return OutFrame(frame, FileChunkFrame.build(frame, prefix, data, data.size), release)
    }

    @Test
    fun aKeyPressDoesNotWaitBehindABigUpload() {
        // About 4 MB/s: each 64 KB chunk takes about 20 ms to write.
        val peer = Peer(bytesPerMs = 4_000)
        val writer = writer(peer)
        val data = Random(3).nextBytes(64 * 1024)
        val uploading = Thread {
            repeat(82) { if (!writer.sendBulk(chunk("t", data))) return@Thread }
            writer.send(Envelope(MessageType.FILE_DONE, file = FileMessage("t", sha256 = "x")))
        }.apply { start() }
        // Well into the upload.
        while (peer.log.count { it == MessageType.FILE_CHUNK } < 10) Thread.sleep(5)
        val chunksBefore = peer.log.count { it == MessageType.FILE_CHUNK }
        val started = System.nanoTime()
        assertTrue(writer.send(Envelope(MessageType.INPUT, input = InputEvent("key", key = "a"))))
        val waitedMs = (System.nanoTime() - started) / 1_000_000
        val chunksBetween = peer.log.indexOf(MessageType.INPUT) - chunksBefore
        // At most the chunk being written when the key came (and one more if it finished just then), while the
        // whole file still had over a second to go.
        assertTrue(chunksBetween <= 2, "Key waited behind $chunksBetween chunks")
        assertTrue(waitedMs < 150, "Key took $waitedMs ms")
        uploading.join(10_000)
        // Queued file messages are written in order after the last chunk.
        val deadline = System.currentTimeMillis() + 5_000
        while (peer.log.lastOrNull() != MessageType.FILE_DONE && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(MessageType.FILE_DONE, peer.log.last())
        assertEquals(82, peer.log.count { it == MessageType.FILE_CHUNK })
    }

    @Test
    fun aStalledPeerDoesNotHoldUpAnother() {
        val (stuck, _) = stalled()
        val healthy = Peer()
        val stuckWriter = writer(stuck)
        val healthyWriter = writer(healthy)
        stuckWriter.post(Envelope(MessageType.PING))
        Thread.sleep(100)
        val started = System.nanoTime()
        // Far more than the queue holds: the extra ones are refused at once instead of blocking the caller.
        var refused = 0
        repeat(FrameWriter.MAX_LIVE_MESSAGES + 50) { index ->
            if (!stuckWriter.post(Envelope(MessageType.CLIPBOARD, text = "copy $index"))) refused++
            healthyWriter.post(Envelope(MessageType.INPUT, input = InputEvent("key", key = "k$index")))
        }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        // The ping is being written (and stuck); the queue holds MAX_LIVE_MESSAGES more.
        assertEquals(50, refused)
        val first = healthy.frames.poll(2, TimeUnit.SECONDS)
        assertEquals("k0", first?.input?.key)
    }

    @Test
    fun theFileWindowIsBounded() {
        val (stuck, gate) = stalled()
        val writer = writer(stuck)
        val released = AtomicInteger()
        val data = ByteArray(64 * 1024)
        val queued = AtomicInteger()
        val sender = Thread {
            repeat(10) {
                if (!writer.sendBulk(chunk("t", data) { released.incrementAndGet() })) return@Thread
                queued.incrementAndGet()
            }
        }.apply { start() }
        Thread.sleep(300)
        // One taken by the stuck writer, about three waiting in the 256 KB window, and the sender blocked.
        val inFlight = queued.get()
        assertTrue(inFlight in 2..5, "Queued $inFlight chunks")
        assertTrue(sender.isAlive)
        writer.close()
        gate.countDown()
        sender.join(5_000)
        assertFalse(sender.isAlive)
        // Every buffer handed in comes back, written or dropped.
        val deadline = System.currentTimeMillis() + 2_000
        while (released.get() < queued.get() + 1 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(queued.get() + 1, released.get())
    }

    @Test
    fun pointerMovesAndTerminalSizesMergeWhileWaiting() {
        val (stuck, gate) = stalled()
        val writer = writer(stuck)
        writer.post(Envelope(MessageType.PING))
        Thread.sleep(100)
        repeat(50) { writer.post(Envelope(MessageType.INPUT, input = InputEvent("move", dx = 1f, dy = -2f))) }
        writer.post(Envelope(MessageType.INPUT, input = InputEvent("click")))
        repeat(5) { writer.post(Envelope(MessageType.INPUT, input = InputEvent("scroll", dy = 3f))) }
        repeat(5) { writer.post(Envelope(MessageType.TERMINAL_RESIZE, terminal = TerminalMessage("s", cols = 80 + it, rows = 24))) }
        gate.countDown()
        val got = (1..5).map { assertNotNull(stuck.frames.poll(2, TimeUnit.SECONDS)) }
        assertEquals(MessageType.PING, got[0].type)
        assertEquals(50f, got[1].input?.dx)
        assertEquals(-100f, got[1].input?.dy)
        assertEquals("click", got[2].input?.kind)
        assertEquals(15f, got[3].input?.dy)
        assertEquals(84, got[4].terminal?.cols)
        assertEquals(null, stuck.frames.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aRejectQueuedBeforeHangingUpStillGoesOut() {
        val peer = Peer(bytesPerMs = 1_000)
        val closed = CountDownLatch(1)
        val writer = writer(peer) { closed.countDown() }
        writer.post(Envelope(MessageType.CLIPBOARD, text = "x".repeat(50_000)))
        writer.post(Envelope(MessageType.PAIR_REJECT, error = "bye"))
        writer.close()
        assertFalse(writer.post(Envelope(MessageType.PING)))
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(MessageType.CLIPBOARD, MessageType.PAIR_REJECT), peer.log.toList())
    }

    @Test
    fun aStalledPeerIsHungUpOnWithinTheGrace() {
        val (stuck, _) = stalled()
        val closed = CountDownLatch(1)
        val writer = writer(stuck) { closed.countDown() }
        writer.post(Envelope(MessageType.PING))
        writer.post(Envelope(MessageType.UNPAIR))
        Thread.sleep(50)
        writer.close()
        assertTrue(closed.await(FrameWriter.CLOSE_GRACE_MS + 1_000, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aLiveSendWaitsUntilWritten() {
        val (stuck, gate) = stalled()
        val writer = writer(stuck)
        val done = AtomicBoolean()
        val sender = Thread { writer.send(Envelope(MessageType.SCREEN_FRAME, video = "AAAA")); done.set(true) }.apply { start() }
        Thread.sleep(200)
        // Video keeps its backpressure: the encoder waits instead of piling up frames.
        assertFalse(done.get())
        gate.countDown()
        sender.join(2_000)
        assertTrue(done.get())
    }

    @Test
    fun chunkFramesMatchTheEncoder() {
        val random = Random(7)
        val ids = listOf("0f8fad5b-d9cb-469f-a165-70867728950e", "with \"quote\" and \\ and é and \n")
        for (id in ids) for (size in listOf(0, 1, 2, 3, 4, 5, 100, 65_535, 65_536, 65_537, 192 * 1024)) {
            val data = random.nextBytes(size)
            val built = chunk(id, data)
            val expected = FrameWriter.encode(Envelope(MessageType.FILE_CHUNK, file = FileMessage(id, data = java.util.Base64.getEncoder().encodeToString(data))))!!
            assertContentEquals(expected, built.bytes.copyOf(built.length), "id=$id size=$size")
            val parsed = assertNotNull(Frames.decode(built.bytes, built.length))
            assertEquals(MessageType.FILE_CHUNK, parsed.type)
            assertEquals(id, parsed.file?.id)
            assertContentEquals(data, java.util.Base64.getDecoder().decode(parsed.file?.data))
        }
    }

    @Test
    fun aFileArrivesIntactThroughFrames() {
        val sinks = mutableMapOf<String, ByteArrayOutputStream>()
        lateinit var sender: FileTransfers
        lateinit var receiver: FileTransfers
        val outstanding = AtomicInteger()
        sender = FileTransfers(scope, { _, _ -> error("unused") }, sendFrame = { _, frame ->
            outstanding.incrementAndGet()
            val envelope = Frames.decode(frame.bytes, frame.length)!!
            frame.release?.invoke()
            outstanding.decrementAndGet()
            receiver.handle("sender", envelope.type, envelope.file)
            true
        }) { _, envelope ->
            receiver.handle("sender", envelope.type, envelope.file)
            true
        }
        receiver = FileTransfers(scope, { name, _ ->
            val out = ByteArrayOutputStream().also { sinks[name] = it }
            object : FileSink {
                override fun write(bytes: ByteArray) = out.write(bytes)
                override fun commit() = "memory"
                override fun abort() {}
            }
        }) { _, envelope ->
            sender.handle("receiver", envelope.type, envelope.file)
            true
        }
        val data = Random(11).nextBytes(1_000_003)
        sender.sendFile("receiver", "big.bin", data.size.toLong()) { ByteArrayInputStream(data) }
        val done = runBlocking {
            withTimeout(10_000) { sender.transfers.first { list -> list.isNotEmpty() && list.all { it.status != TransferStatus.Active } } }
        }
        assertEquals(TransferStatus.Done, done.single().status)
        assertContentEquals(data, sinks.getValue("big.bin").toByteArray())
        assertEquals(0, outstanding.get())
    }

    @Test
    fun longTerminalPastesGoInPieces() {
        val text = Random(5).nextBytes(100_000)
        val message = TerminalMessage("s", data = java.util.Base64.getEncoder().encodeToString(text))
        val pieces = ConnectionManager.terminalPieces(MessageType.TERMINAL_INPUT, message)
        assertEquals(7, pieces.size)
        val joined = ByteArrayOutputStream().apply { pieces.forEach { write(java.util.Base64.getDecoder().decode(it.data)) } }
        assertContentEquals(text, joined.toByteArray())
        val key = TerminalMessage("s", data = "YQ==")
        assertEquals(listOf(key), ConnectionManager.terminalPieces(MessageType.TERMINAL_INPUT, key))
    }
}
