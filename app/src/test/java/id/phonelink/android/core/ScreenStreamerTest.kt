package id.phonelink.android.core

import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class ScreenStreamerTest {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val config = byteArrayOf(0, 0, 0, 1, 103, 0, 0, 0, 1, 104)
    private val sent = CopyOnWriteArrayList<Envelope>()
    private val syncs = AtomicInteger()

    private fun bytes(envelope: Envelope) = Base64.getDecoder().decode(envelope.video)

    private fun waitFor(count: Int) {
        val deadline = System.currentTimeMillis() + 3_000
        while (sent.size < count && System.currentTimeMillis() < deadline) Thread.sleep(5)
    }

    private fun streamer(send: (Envelope) -> Boolean = { sent.add(it) }, closed: () -> Unit = {}) =
        ScreenStreamer(scope, send, { syncs.incrementAndGet() }, closed)

    @Test
    fun keyFramesCarryConfigAndOrderIsKept() {
        val stream = streamer()
        stream.onConfig(config)
        stream.onFrame(byteArrayOf(1), key = true)
        stream.onFrame(byteArrayOf(2), key = false)
        stream.onFrame(byteArrayOf(3), key = false)
        waitFor(3)

        assertEquals(3, sent.size)
        assertContentEquals(config + byteArrayOf(1), bytes(sent[0]))
        assertEquals(true, sent[0].keyFrame)
        assertContentEquals(byteArrayOf(2), bytes(sent[1]))
        assertEquals(null, sent[1].keyFrame)
        assertContentEquals(byteArrayOf(3), bytes(sent[2]))
    }

    @Test
    fun framesBeforeFirstKeyFrameAreDropped() {
        val stream = streamer()
        stream.onConfig(config)
        stream.onFrame(byteArrayOf(9), key = false)
        stream.onFrame(byteArrayOf(1), key = true)
        waitFor(1)
        Thread.sleep(50)
        assertEquals(1, sent.size)
        assertEquals(true, sent[0].keyFrame)
    }

    @Test
    fun keyFrameWithoutConfigCannotBeDecodedSoItIsDropped() {
        streamer().onFrame(byteArrayOf(1), key = true)
        Thread.sleep(50)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun slowNetworkDropsUntilNextKeyFrame() {
        val release = CountDownLatch(1)
        val stream = streamer(send = {
            release.await(5, TimeUnit.SECONDS)
            sent.add(it)
        })
        stream.onConfig(config)
        stream.onFrame(byteArrayOf(0), key = true)
        Thread.sleep(50)
        repeat(ScreenStreamer.QUEUE_SIZE + 5) { stream.onFrame(byteArrayOf(1), key = false) }
        assertTrue(syncs.get() >= 1, "A new key frame must be requested")

        // A regular frame after a drop must not be sent, because it would corrupt the picture.
        val before = syncs.get()
        stream.onFrame(byteArrayOf(2), key = false)
        assertEquals(before, syncs.get())

        Thread.sleep(50)
        release.countDown()
        waitFor(ScreenStreamer.QUEUE_SIZE + 1)
        Thread.sleep(100)
        assertTrue(sent.none { it.video != null && bytes(it).contentEquals(byteArrayOf(2)) })

        stream.onFrame(byteArrayOf(7), key = true)
        stream.onFrame(byteArrayOf(8), key = false)
        val count = sent.size
        waitFor(count + 2)
        assertTrue(sent.any { it.keyFrame == true && bytes(it).last() == 7.toByte() })
        assertTrue(sent.any { bytes(it).contentEquals(byteArrayOf(8)) })
    }

    @Test
    fun oversizedFrameIsDroppedAndNeverExceedsFrameLimit() {
        val stream = streamer()
        stream.onConfig(config)
        stream.onFrame(ByteArray(ScreenStreamer.MAX_RAW_BYTES + 1), key = true)
        Thread.sleep(50)
        assertTrue(sent.isEmpty())
        assertEquals(1, syncs.get())

        stream.onFrame(ByteArray(ScreenStreamer.MAX_RAW_BYTES - config.size), key = true)
        waitFor(1)
        val frame = protocolJson.encodeToString(Envelope.serializer(), sent.single())
        assertTrue(frame.encodeToByteArray().size < PhoneLinkProtocol.MAX_FRAME_BYTES)
    }

    @Test
    fun failedSendClosesStream() {
        val closed = CountDownLatch(1)
        val stream = streamer(send = { false }, closed = { closed.countDown() })
        stream.onConfig(config)
        stream.onFrame(byteArrayOf(1), key = true)
        assertTrue(closed.await(3, TimeUnit.SECONDS))
    }
}
