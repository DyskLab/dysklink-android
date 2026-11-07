package id.phonelink.android.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
        assertEquals(TransferStatus.Done, waitFinished(receiver).single().status)
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
