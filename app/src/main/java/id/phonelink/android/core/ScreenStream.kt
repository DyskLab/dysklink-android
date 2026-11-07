package id.phonelink.android.core

import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Sanitizes control commands from the Mac before they reach the Accessibility service. */
object ControlRules {
    private const val MAX_TEXT = 4_096
    private val keys = setOf("backspace", "enter", "left", "right", "back", "home", "recents")

    fun sanitize(event: ControlEvent): ControlEvent? = when (event.kind) {
        "down", "drag", "up" -> {
            val x = event.x
            val y = event.y
            if (x == null || y == null || !x.isFinite() || !y.isFinite()) {
                null
            } else {
                ControlEvent(event.kind, x = x.coerceIn(0f, 1f), y = y.coerceIn(0f, 1f))
            }
        }
        "text" -> event.text?.takeIf { it.isNotEmpty() }?.let { ControlEvent("text", text = it.take(MAX_TEXT)) }
        "key" -> event.key?.takeIf { it in keys }?.let { ControlEvent("key", key = it) }
        else -> null
    }
}

/** One stretch of a touch. A stroke with both [first] and [last] set is a complete gesture. */
class StrokeSegment(
    val points: List<Pair<Float, Float>>,
    val duration: Long,
    val first: Boolean,
    val last: Boolean,
)

/**
 * Turns the Mac's down, drag, and up events into stretches of one continuous touch. Only one stretch
 * runs at a time, and points that arrive meanwhile join the next one, so a slow phone never falls behind.
 * If the system cancels the touch, the movement is replayed as one gesture when the button is released.
 * Not thread safe: the Accessibility service calls it from the main thread.
 */
class StrokeQueue {
    private val pending = ArrayList<Pair<Float, Float>>()
    private val history = ArrayList<Pair<Float, Float>>()
    private var lastPoint: Pair<Float, Float>? = null
    private var downAt = 0L
    private var lastAt = 0L
    private var started = false
    private var ended = false
    private var inFlight = false
    private var broken = false

    /** Changes with each new touch, so that a late callback from an old touch can be ignored. */
    var generation = 0
        private set

    var isActive = false
        private set

    fun down(x: Float, y: Float, now: Long) {
        reset()
        generation++
        isActive = true
        downAt = now
        lastAt = now
        add(x to y)
    }

    fun drag(x: Float, y: Float) {
        if (isActive && !ended) add(x to y)
    }

    fun up(x: Float, y: Float) {
        if (!isActive || ended) return
        add(x to y)
        ended = true
    }

    /** Lifts the finger where it is. Used when the Mac goes quiet or the phone locks, and never replays. */
    fun release() {
        if (!isActive) return
        if (broken) reset() else ended = true
    }

    fun next(now: Long): StrokeSegment? {
        if (!isActive || inFlight) return null
        if (broken) {
            if (!ended) return null
            val whole = StrokeSegment(history.toList(), (now - downAt).coerceIn(MIN_WHOLE_MS, MAX_WHOLE_MS), first = true, last = true)
            reset()
            return whole
        }
        if (!started) {
            started = true
            inFlight = true
            lastPoint = pending.removeAt(0)
            return StrokeSegment(listOf(lastPoint!!), FIRST_MS, first = true, last = false)
        }
        if (pending.isEmpty() && !ended) return null
        val from = lastPoint ?: return null
        val points = listOf(from) + pending
        val elapsed = (now - lastAt).coerceIn(MIN_STEP_MS, MAX_STEP_MS)
        lastPoint = points.last()
        lastAt = now
        pending.clear()
        inFlight = true
        if (ended) isActive = false
        return StrokeSegment(points, if (ended) maxOf(elapsed, MIN_LAST_MS) else elapsed, first = false, last = ended)
    }

    fun completed() {
        inFlight = false
    }

    fun cancelled() {
        inFlight = false
        if (isActive) broken = true else reset()
    }

    private fun add(point: Pair<Float, Float>) {
        pending.add(point)
        if (history.size < MAX_HISTORY) history.add(point)
    }

    private fun reset() {
        pending.clear()
        history.clear()
        lastPoint = null
        started = false
        ended = false
        inFlight = false
        broken = false
        isActive = false
    }

    private companion object {
        const val FIRST_MS = 1L
        const val MIN_STEP_MS = 8L
        const val MAX_STEP_MS = 100L
        const val MIN_LAST_MS = 30L
        const val MIN_WHOLE_MS = 50L
        const val MAX_WHOLE_MS = 5_000L
        const val MAX_HISTORY = 200
    }
}

/**
 * Sends H.264 encoder output to the Mac without building up delay. If the network falls behind, frames
 * are dropped until the next key frame, because other frames can't be decoded without their predecessors.
 */
class ScreenStreamer(
    scope: CoroutineScope,
    private val send: (Envelope) -> Boolean,
    private val requestKeyFrame: () -> Unit,
    private val onClosed: () -> Unit,
) {
    private class Frame(val data: ByteArray, val key: Boolean)

    private val queue = Channel<Frame>(QUEUE_SIZE)
    private val closed = AtomicBoolean(false)
    @Volatile private var config: ByteArray? = null
    @Volatile private var waitingForKey = true

    init {
        scope.launch(Dispatchers.IO) {
            for (frame in queue) {
                val encoded = Base64.getEncoder().encodeToString(frame.data)
                if (!send(Envelope(MessageType.SCREEN_FRAME, video = encoded, keyFrame = frame.key.takeIf { it }))) {
                    close()
                    break
                }
            }
        }
    }

    /** SPS and PPS from the encoder, included with every key frame. */
    fun onConfig(data: ByteArray) {
        config = data
    }

    fun onFrame(data: ByteArray, key: Boolean) {
        if (closed.get()) return
        if (waitingForKey && !key) return
        val payload = if (key) config?.plus(data) ?: return else data
        if (payload.size > MAX_RAW_BYTES || queue.trySend(Frame(payload, key)).isFailure) {
            waitingForKey = true
            requestKeyFrame()
            return
        }
        waitingForKey = false
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            queue.close()
            onClosed()
        }
    }

    internal companion object {
        const val QUEUE_SIZE = 6
        /** Base64 adds a third to the size, and the result must fit in a 1 MiB frame. */
        const val MAX_RAW_BYTES = 760 * 1024
    }
}
