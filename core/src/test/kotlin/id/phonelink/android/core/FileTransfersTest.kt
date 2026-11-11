package id.phonelink.android.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class FileTransfersTest {
    private class MemorySink : FileSink {
        val bytes = ByteArrayOutputStream()
        var committed = false
        var aborted = false
        override fun write(bytes: ByteArray) = this.bytes.write(bytes)
        override fun commit(): String { committed = true; return "memori" }
        override fun abort() { aborted = true }
        override val openUri: String? get() = if (committed) "memori://saved" else null
    }

    private val sinks = mutableMapOf<String, MemorySink>()
    private val scope = CoroutineScope(Dispatchers.IO)

    /** Connects two sides directly in memory. [tamper] can alter messages in transit. */
    private fun link(tamper: (Envelope) -> Envelope = { it }): Pair<FileTransfers, FileTransfers> {
        lateinit var sender: FileTransfers
        lateinit var receiver: FileTransfers
        sender = FileTransfers(scope, { _, _ -> error("unused") }) { _, envelope ->
            val message = tamper(envelope)
            receiver.handle("pengirim", message.type, message.file)
            true
        }
        receiver = FileTransfers(scope, { name, _ -> MemorySink().also { sinks[name] = it } }) { _, envelope ->
            sender.handle("penerima", envelope.type, envelope.file)
            true
        }
        return sender to receiver
    }

    private fun waitFinished(transfers: FileTransfers) = runBlocking {
        withTimeout(10_000) {
            transfers.transfers.first { list -> list.isNotEmpty() && list.all { it.status != TransferStatus.Active } }
        }
    }

    @Test
    fun fileArrivesIntact() {
        val (sender, receiver) = link()
        val data = Random(1).nextBytes(1_000_000)
        sender.sendFile("penerima", "foto.jpg", data.size.toLong()) { ByteArrayInputStream(data) }

        assertEquals(TransferStatus.Done, waitFinished(sender).single().status)
        val received = waitFinished(receiver).single()
        assertEquals(TransferStatus.Done, received.status)
        assertEquals("memori://saved", received.uri)
        assertEquals(null, waitFinished(sender).single().uri)
        val sink = sinks.getValue("foto.jpg")
        assertTrue(sink.committed)
        assertContentEquals(data, sink.bytes.toByteArray())
    }

    @Test
    fun emptyFileIsAccepted() {
        val (sender, _) = link()
        sender.sendFile("penerima", "kosong.txt", 0) { ByteArrayInputStream(ByteArray(0)) }
        assertEquals(TransferStatus.Done, waitFinished(sender).single().status)
        assertTrue(sinks.getValue("kosong.txt").committed)
    }

    @Test
    fun corruptedDataIsDiscarded() {
        val (sender, receiver) = link { envelope ->
            if (envelope.type == MessageType.FILE_DONE) {
                envelope.copy(file = envelope.file?.copy(sha256 = "0".repeat(64)))
            } else {
                envelope
            }
        }
        val data = Random(2).nextBytes(10_000)
        sender.sendFile("penerima", "rusak.bin", data.size.toLong()) { ByteArrayInputStream(data) }

        assertEquals(TransferStatus.Failed, waitFinished(receiver).single().status)
        assertEquals(TransferStatus.Failed, waitFinished(sender).single().status)
        val sink = sinks.getValue("rusak.bin")
        assertFalse(sink.committed)
        assertTrue(sink.aborted)
    }

    @Test
    fun dataBeyondDeclaredSizeIsRejected() = runBlocking {
        val receiver = FileTransfers(scope, { name, _ -> MemorySink().also { sinks[name] = it } }) { _, _ -> true }
        receiver.handle("p", MessageType.FILE_OFFER, FileMessage("1", name = "a.bin", size = 1))
        val tooMuch = java.util.Base64.getEncoder().encodeToString(ByteArray(10))
        receiver.handle("p", MessageType.FILE_CHUNK, FileMessage("1", data = tooMuch))
        delay(10)
        assertEquals(TransferStatus.Failed, receiver.transfers.value.single().status)
        assertTrue(sinks.getValue("a.bin").aborted)
    }

    @Test
    fun anotherPeerCannotWriteIntoATransfer() {
        val receiver = FileTransfers(scope, { name, _ -> MemorySink().also { sinks[name] = it } }) { _, _ -> true }
        receiver.handle("asli", MessageType.FILE_OFFER, FileMessage("1", name = "a.bin", size = 5))
        val data = java.util.Base64.getEncoder().encodeToString(ByteArray(5))
        receiver.handle("penyusup", MessageType.FILE_CHUNK, FileMessage("1", data = data))
        assertEquals(0, sinks.getValue("a.bin").bytes.size())
    }

    @Test
    fun disconnectDiscardsPartialFile() {
        val receiver = FileTransfers(scope, { name, _ -> MemorySink().also { sinks[name] = it } }) { _, _ -> true }
        receiver.handle("p", MessageType.FILE_OFFER, FileMessage("1", name = "a.bin", size = 5))
        receiver.onDisconnected("p")
        assertTrue(sinks.getValue("a.bin").aborted)
        assertEquals(TransferStatus.Failed, receiver.transfers.value.single().status)
    }

    /** One connection, as an upload sees it. Records what went over it. */
    private class FakeLink : TransferLink {
        val sent = CopyOnWriteArrayList<String>()
        @Volatile var open = true
        override fun send(envelope: Envelope): Boolean {
            if (!open) return false
            sent += envelope.type
            return true
        }
        override fun sendFrame(frame: OutFrame): Boolean {
            frame.release?.invoke()
            if (!open) return false
            sent += MessageType.FILE_CHUNK
            return true
        }
        override val isOpen get() = open
    }

    /** [total] bytes, 64 KB at a time, each read waiting for [gate] and then [pauseMs]. */
    private fun slowStream(total: Int, pauseMs: Long = 10, gate: CountDownLatch? = null) = object : InputStream() {
        var left = total
        override fun read(): Int = error("unused")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            gate?.await()
            if (left <= 0) return -1
            Thread.sleep(pauseMs)
            val count = minOf(len, left, 64 * 1024)
            left -= count
            return count
        }
    }

    private fun waitUntil(message: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError(message)
            Thread.sleep(10)
        }
    }

    @Test
    fun anUploadNeverMovesToAReplacementConnection() {
        val first = FakeLink()
        val second = FakeLink()
        val current = java.util.concurrent.atomic.AtomicReference<TransferLink>(first)
        val sender = FileTransfers(scope, { _, _ -> error("unused") }, linkFor = { current.get() }) { _, _ -> error("sent by peer ID") }
        sender.sendFile("mac", "big.bin", 4L * 1024 * 1024) { slowStream(4 * 1024 * 1024) }
        waitUntil("Chunks should flow on the first connection") { first.sent.count { it == MessageType.FILE_CHUNK } >= 2 }

        // The connection is replaced: the old one closes, a new one takes its place.
        first.open = false
        current.set(second)
        waitUntil("The upload must fail with its connection") { sender.transfers.value.single().status == TransferStatus.Failed }
        Thread.sleep(100)
        assertTrue(second.sent.isEmpty(), "Nothing of the old upload may go over the new connection: ${second.sent}")
        assertEquals("Connection lost", sender.transfers.value.single().detail)
    }

    @Test
    fun uploadsWaitingTheirTurnDontStartAfterADisconnect() {
        val first = FakeLink()
        val second = FakeLink()
        val current = java.util.concurrent.atomic.AtomicReference<TransferLink>(first)
        val gate = CountDownLatch(1)
        val sender = FileTransfers(scope, { _, _ -> error("unused") }, linkFor = { current.get() }) { _, _ -> error("sent by peer ID") }
        // Two upload at a time; the third waits for a turn.
        repeat(3) { n -> sender.sendFile("mac", "f$n.bin", 64L * 1024) { slowStream(64 * 1024, gate = gate) } }
        waitUntil("Two should have offered") { first.sent.count { it == MessageType.FILE_OFFER } == 2 }

        sender.onDisconnected("mac")
        current.set(second)
        gate.countDown()
        assertTrue(sender.transfers.value.all { it.status == TransferStatus.Failed })
        Thread.sleep(300)
        assertTrue(second.sent.isEmpty(), "A failed upload must not start on the next connection: ${second.sent}")
        assertEquals(0, first.sent.count { it == MessageType.FILE_DONE })
    }

    @Test
    fun onlyTheReceiverCanCancelOrAnswerItsUpload() {
        val link = FakeLink()
        val sender = FileTransfers(scope, { _, _ -> error("unused") }, linkFor = { link }) { _, _ -> error("sent by peer ID") }
        sender.sendFile("mac", "a.bin", 3L * 64 * 1024) { slowStream(3 * 64 * 1024, pauseMs = 50) }
        val id = sender.transfers.value.single().id
        sender.handle("someone-else", MessageType.FILE_CANCEL, FileMessage(id))
        sender.handle("someone-else", MessageType.FILE_RESULT, FileMessage(id, ok = false, error = "no"))
        waitUntil("The upload should finish sending") { MessageType.FILE_DONE in link.sent }
        assertEquals(TransferStatus.Active, sender.transfers.value.single().status)
        assertFalse(MessageType.FILE_CANCEL in link.sent)
        sender.handle("mac", MessageType.FILE_RESULT, FileMessage(id, ok = true))
        assertEquals(TransferStatus.Done, sender.transfers.value.single().status)
    }

    @Test
    fun theSameTransferIdFromOneDeviceIsListedOnce() {
        val receiver = FileTransfers(scope, { name, _ -> MemorySink().also { sinks[name] = it } }) { _, _ -> true }
        receiver.handle("p", MessageType.FILE_OFFER, FileMessage("1", name = "a.bin", size = 5))
        receiver.handle("p", MessageType.FILE_CANCEL, FileMessage("1"))
        receiver.handle("p", MessageType.FILE_OFFER, FileMessage("1", name = "a.bin", size = 5))
        assertEquals(1, receiver.transfers.value.count { it.peerId == "p" && it.id == "1" })
        receiver.handle("p", MessageType.FILE_CANCEL, FileMessage("1"))
        // Another device's transfer with the same ID is its own entry.
        receiver.handle("q", MessageType.FILE_OFFER, FileMessage("1", name = "b.bin", size = 5))
        assertEquals(2, receiver.transfers.value.size)
    }

    @Test
    fun fileNamesCannotEscapeTheFolder() {
        assertEquals("passwd", FileNames.sanitize("../../etc/passwd"))
        assertEquals("evil.exe", FileNames.sanitize("..\\..\\evil.exe"))
        assertEquals("bashrc", FileNames.sanitize(".bashrc"))
        assertEquals("file", FileNames.sanitize("../.."))
        assertEquals("file", FileNames.sanitize(null))
        assertEquals("file", FileNames.sanitize("   "))
        assertEquals("ab.txt", FileNames.sanitize("a\u0000b.txt"))
        assertEquals("foto liburan.jpg", FileNames.sanitize("foto liburan.jpg"))
    }
}
