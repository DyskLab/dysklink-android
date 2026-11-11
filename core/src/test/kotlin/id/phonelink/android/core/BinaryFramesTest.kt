package id.phonelink.android.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** Binary frames (`bin1`): the wire bytes, reading them back, malformed frames, and when they're used. */
class BinaryFramesTest {
    private val writers = mutableListOf<FrameWriter>()

    @AfterTest
    fun cleanUp() = writers.forEach { it.close() }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** Collects what a writer puts on the wire. */
    private class Wire : java.io.OutputStream() {
        private val bytes = ByteArrayOutputStream()
        override fun write(b: Int) = synchronized(this) { bytes.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) = synchronized(this) { bytes.write(b, off, len) }
        fun take(): ByteArray = synchronized(this) { bytes.toByteArray().also { bytes.reset() } }
    }

    private fun hello(caps: List<String>?) = Envelope(MessageType.HELLO, hello = Hello("x", "android", "m", PhoneLinkProtocol.VERSION, true, caps = caps))

    /** A writer that has sent its hello with [localCaps] and seen the peer's with [peerCaps]. Its wire starts empty. */
    private fun writer(localCaps: List<String>?, peerCaps: List<String>?): Pair<FrameWriter, Wire> {
        val wire = Wire()
        // Buffered like a real connection, so each flush hands over whole frames.
        val writer = FrameWriter(java.io.BufferedOutputStream(wire, 4 * 1024 * 1024), "test") {}.also { writers += it }
        assertTrue(writer.send(hello(localCaps)))
        writer.peerBinary = BinaryFrames.supports(peerCaps?.let { Hello("p", "macos", "m", 3, true, caps = it) })
        wire.take()
        return writer to wire
    }

    private fun readAll(bytes: ByteArray): List<Envelope?> {
        val reader = FrameReader(DataInputStream(ByteArrayInputStream(bytes)))
        val out = mutableListOf<Envelope?>()
        while (true) {
            out += try { reader.read() } catch (_: java.io.EOFException) { break }
        }
        return out
    }

    @Test
    fun binaryFramesMatchTheMac() {
        // The same vectors as BinaryFrameSelfTest in the Mac app.
        val chunk = "8000002f0029" + "7b2274797065223a2266696c652e6368756e6b222c2266696c65223a7b226964223a22616263227d7d" + "000102ff"
        val video = "80000031002a" + "7b2274797065223a226d616373637265656e2e6672616d65222c226b65794672616d65223a747275657d" + "0000000165"
        val audio = "8000001c0016" + "7b2274797065223a22617564696f2e6672616d65227d" + "0100ffff"
        val chunkMessage = Envelope(MessageType.FILE_CHUNK, file = FileMessage("abc"), raw = byteArrayOf(0, 1, 2, -1))
        val videoMessage = Envelope(MessageType.MACSCREEN_FRAME, keyFrame = true, raw = byteArrayOf(0, 0, 0, 1, 0x65))
        val audioMessage = Envelope(MessageType.AUDIO_FRAME, raw = byteArrayOf(1, 0, -1, -1))
        assertEquals(chunk, hex(BinaryFrames.encode(chunkMessage)!!))
        assertEquals(video, hex(BinaryFrames.encode(videoMessage)!!))
        assertEquals(audio, hex(BinaryFrames.encode(audioMessage)!!))

        // What the writer puts on the wire, from a message and from a pooled file frame.
        val (writer, wire) = writer(PhoneLinkProtocol.CAPS, PhoneLinkProtocol.CAPS)
        assertTrue(writer.binary)
        assertTrue(writer.send(videoMessage))
        assertEquals(video, hex(wire.take()))
        assertTrue(writer.send(audioMessage))
        assertEquals(audio, hex(wire.take()))
        assertTrue(writer.send(chunkMessage))
        waitFor { wire.peek().isNotEmpty() }
        assertEquals(chunk, hex(wire.take()))
        val prefix = FileChunkFrame.binaryPrefix("abc")
        val frame = prefix + byteArrayOf(0, 1, 2, -1)
        assertTrue(writer.sendBulk(OutFrame(frame, frame.size, binary = true)))
        waitFor { wire.peek().size >= 4 + frame.size }
        assertEquals(chunk, hex(wire.take()))
    }

    private fun Wire.peek(): ByteArray = synchronized(this) { take().also { write(it, 0, it.size) } }

    private fun waitFor(check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check()) {
            assertTrue(System.currentTimeMillis() < until, "Timed out")
            Thread.sleep(5)
        }
    }

    @Test
    fun everyFrameMessageReadsBackTheSameBothWays() {
        val data = Random(9).nextBytes(70_001)
        val messages = listOf(
            Envelope(MessageType.SCREEN_FRAME, keyFrame = true, raw = data),
            Envelope(MessageType.SCREEN_FRAME, raw = data),
            Envelope(MessageType.CAMERA_FRAME, keyFrame = true, raw = data),
            Envelope(MessageType.MACSCREEN_FRAME, raw = data),
            Envelope(MessageType.AUDIO_FRAME, raw = data.copyOf(7680)),
            Envelope(MessageType.FILE_CHUNK, file = FileMessage("with \"quote\" and é"), raw = data),
            Envelope(MessageType.FILE_CHUNK, file = FileMessage("empty"), raw = ByteArray(0)),
        )
        for (binary in listOf(true, false)) {
            val (writer, wire) = writer(PhoneLinkProtocol.CAPS, if (binary) PhoneLinkProtocol.CAPS else null)
            assertEquals(binary, writer.binary)
            for (message in messages) {
                assertTrue(writer.send(message))
                if (message.type == MessageType.FILE_CHUNK) waitFor { wire.peek().isNotEmpty() }
                val bytes = wire.take()
                assertEquals(binary, bytes[0].toInt() and 0x80 != 0, "${message.type} binary=$binary")
                val read = assertNotNull(readAll(bytes).single())
                assertEquals(message.type, read.type)
                assertEquals(message.keyFrame, read.keyFrame)
                assertEquals(message.file?.id, read.file?.id)
                assertContentEquals(message.raw, read.payload(), "${message.type} binary=$binary")
                // Binary frames carry raw bytes; JSON frames the base64 field, as older apps expect.
                if (binary) assertNotNull(read.raw) else assertNull(read.raw)
            }
        }
        // Raw is never in the JSON itself.
        val json = FrameWriter.encode(Envelope(MessageType.AUDIO_FRAME, raw = byteArrayOf(1, 2, 3)))!!.decodeToString()
        assertEquals("""{"type":"audio.frame","audio":"AQID"}""", json)
    }

    @Test
    fun aBinaryFrameCanCarryWhatBase64Cannot() {
        val (writer, wire) = writer(PhoneLinkProtocol.CAPS, PhoneLinkProtocol.CAPS)
        val big = ByteArray(1000 * 1024)
        assertTrue(writer.send(Envelope(MessageType.SCREEN_FRAME, keyFrame = true, raw = big)))
        assertEquals(big.size, readAll(wire.take()).single()?.raw?.size)
        assertFalse(writer.send(Envelope(MessageType.SCREEN_FRAME, raw = ByteArray(PhoneLinkProtocol.MAX_FRAME_BYTES))))
        val (old, _) = writer(PhoneLinkProtocol.CAPS, null)
        assertFalse(old.send(Envelope(MessageType.SCREEN_FRAME, raw = big)), "Too big as base64")
    }

    @Test
    fun binaryOnlyWhenBothHellosListIt() {
        assertFalse(writer(null, null).first.binary)
        assertFalse(writer(PhoneLinkProtocol.CAPS, null).first.binary, "An older peer gets JSON")
        assertFalse(writer(null, PhoneLinkProtocol.CAPS).first.binary, "Caps off here sends JSON")
        assertFalse(writer(PhoneLinkProtocol.CAPS, listOf("bin2")).first.binary)
        assertTrue(writer(PhoneLinkProtocol.CAPS, listOf("future", "bin1")).first.binary)
        // Before any hello, everything is JSON.
        val fresh = FrameWriter(Wire(), "fresh") {}.also { writers += it }
        assertFalse(fresh.binary)
        // A pooled binary frame is refused by a connection that doesn't use them, instead of confusing the peer.
        var released = 0
        val (old, _) = writer(PhoneLinkProtocol.CAPS, null)
        assertFalse(old.sendBulk(OutFrame(ByteArray(10), 10, release = { released++ }, binary = true)))
        assertEquals(1, released)
        // Older apps decode a hello with caps (they ignore unknown keys), and new ones one without.
        val withCaps = protocolJson.encodeToString(Envelope.serializer(), hello(PhoneLinkProtocol.CAPS))
        assertTrue(withCaps.contains(""""caps":["bin1"]"""))
        val oldJson = Json { ignoreUnknownKeys = true }
        assertEquals("x", oldJson.decodeFromString(OldEnvelope.serializer(), withCaps).hello?.name)
        assertNull(protocolJson.decodeFromString(Envelope.serializer(), """{"type":"hello","hello":{"name":"a","platform":"macos","model":"m","protocolVersion":3,"paired":true}}""").hello?.caps)
    }

    /** The hello as apps before 1.2 knew it. */
    @kotlinx.serialization.Serializable
    private data class OldHello(val name: String, val platform: String, val model: String, val protocolVersion: Int, val paired: Boolean, val appVersion: String? = null)

    @kotlinx.serialization.Serializable
    private data class OldEnvelope(val type: String, val hello: OldHello? = null)

    private fun frame(word: Int, vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(4).putInt(word).array())
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun u16(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())

    @Test
    fun malformedBinaryFramesEndTheConnection() {
        val header = """{"type":"audio.frame"}""".encodeToByteArray()
        val flag = BinaryFrames.FLAG
        // Good, for reference.
        assertContentEquals(byteArrayOf(7), readAll(frame(flag or (2 + header.size + 1), u16(header.size), header, byteArrayOf(7))).single()?.raw)
        val bad = mapOf(
            "header past the end" to frame(flag or (2 + header.size + 1), u16(header.size + 2), header, byteArrayOf(7)),
            "header length 0" to frame(flag or 3, u16(0), byteArrayOf(7)),
            "length over 1 MiB" to frame(flag or (PhoneLinkProtocol.MAX_FRAME_BYTES + 1), u16(header.size), header),
            "length 0" to frame(flag),
            "length 1" to frame(flag or 1, byteArrayOf(0)),
            "all ones" to frame(-1),
            "header not JSON" to frame(flag or 8, u16(5), "nope!".encodeToByteArray(), byteArrayOf(1)),
            "header not a message" to frame(flag or 5, u16(2), "{}".encodeToByteArray(), byteArrayOf(1)),
            "truncated payload" to frame(flag or (2 + header.size + 100), u16(header.size), header, byteArrayOf(7)),
            "truncated header" to frame(flag or (2 + header.size), u16(header.size), header.copyOf(5)),
        )
        for ((name, bytes) in bad) {
            assertFailsWith<IOException>(name) { FrameReader(DataInputStream(ByteArrayInputStream(bytes))).read() }
        }
        // A JSON message that doesn't parse stays a dropped message, as before.
        val junk = "{nope".encodeToByteArray()
        assertNull(FrameReader(DataInputStream(ByteArrayInputStream(frame(junk.size, junk)))).read())
    }

    @Test
    fun silenceIsHeldAfter200Ms() {
        val quiet = ByteArray(7680)
        val loud = ByteArray(7680).also { it[101] = 1 }
        val tiny = ByteArray(7680).also { it[0] = 0xF8.toByte(); it[1] = 0xFF.toByte() }
        assertTrue(SilenceGate.isSilent(quiet))
        assertTrue(SilenceGate.isSilent(tiny))
        assertFalse(SilenceGate.isSilent(loud))
        val gate = SilenceGate()
        val pattern = listOf(loud) + List(8) { quiet } + listOf(loud, quiet)
        // The same answers as the Mac's gate.
        assertEquals(listOf(true, true, true, true, true, true, false, false, false, true, true), pattern.map { gate.shouldSend(it) })
        assertEquals(3, gate.held)
    }
}
