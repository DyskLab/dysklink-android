package id.phonelink.android.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameQueueTest {
    @Test
    fun framesComeOutInOrder() {
        val queue = FrameQueue<String>(4)
        assertFalse(queue.offer("k", key = true))
        assertFalse(queue.offer("a", key = false))
        assertEquals("k" to true, queue.poll(0))
        assertEquals("a" to false, queue.poll(0))
        assertNull(queue.poll(0))
    }

    @Test
    fun aFullQueueDropsToTheNextKeyFrame() {
        val queue = FrameQueue<String>(2)
        queue.offer("k1", key = true)
        queue.offer("a", key = false)
        assertTrue(queue.offer("b", key = false), "Dropping must ask for a key frame")
        assertEquals(0, queue.size)
        // Until the next key frame, frames that can't be decoded are skipped, without asking again.
        assertFalse(queue.offer("c", key = false))
        assertEquals(0, queue.size)
        assertFalse(queue.offer("k2", key = true))
        assertFalse(queue.offer("d", key = false))
        assertEquals("k2" to true, queue.poll(0))
        assertEquals("d" to false, queue.poll(0))
    }

    @Test
    fun aKeyFrameReplacesAFullQueue() {
        val queue = FrameQueue<String>(2)
        queue.offer("k1", key = true)
        queue.offer("a", key = false)
        assertFalse(queue.offer("k2", key = true))
        assertEquals(1, queue.size)
        assertEquals("k2" to true, queue.poll(0))
    }
}
