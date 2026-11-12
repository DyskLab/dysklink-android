package id.phonelink.android.core

/** One finger's part of a gesture step. [first] starts a new stroke; otherwise it continues the finger's last one. */
data class FingerStep(val finger: Int, val points: List<Pair<Float, Float>>, val first: Boolean, val last: Boolean)

/** Every finger that is down, moved together in one gesture step, as Android wants continued strokes sent together. */
data class TouchStep(val fingers: List<FingerStep>, val duration: Long)

/**
 * Several fingers at once, for game keys: a joystick held while skills are tapped. Each step carries every finger that
 * is down, since Android lifts any continuing finger left out of the next gesture. Only one step runs at a time, and
 * events that arrive meanwhile join the next one. Not thread safe: the Accessibility service calls it from the main thread.
 */
class MultiTouch {
    private class Finger(var at: Pair<Float, Float>) {
        val pending = ArrayList<Pair<Float, Float>>()
        var started = false
        /** Never sent yet: its first stroke starts where it went down, so a press and its moves both land. */
        var fresh = true
        var lifted = false
        /** Pressed again before the lift went out: a new touch starts here right after. */
        var again: Pair<Float, Float>? = null
    }

    private val fingers = LinkedHashMap<Int, Finger>()
    private var inFlight = false
    private var lastStepAt = 0L

    /** Changes when the system cancels the gesture, so a late callback can be ignored. */
    var generation = 0
        private set

    val isActive: Boolean get() = fingers.isNotEmpty()

    fun down(finger: Int, x: Float, y: Float) {
        if (finger !in 0 until MAX_FINGERS) return
        val existing = fingers[finger]
        if (existing != null && !existing.lifted) return move(finger, x, y)
        // A finger lifted but not yet sent is pressed again only after its lift goes out.
        if (existing != null) {
            existing.again = x to y
            return
        }
        fingers[finger] = Finger(x to y)
    }

    fun move(finger: Int, x: Float, y: Float) {
        val state = fingers[finger] ?: return
        if (state.lifted) return
        state.pending.add(x to y)
        if (state.pending.size > MAX_PENDING) state.pending.removeAt(0)
    }

    fun up(finger: Int, x: Float? = null, y: Float? = null) {
        val state = fingers[finger] ?: return
        if (x != null && y != null) state.pending.add(x to y)
        state.lifted = true
    }

    /** Lifts every finger, for a lost connection or a locked phone. */
    fun releaseAll() {
        fingers.values.forEach { it.lifted = true }
    }

    /** The next step, or null when nothing changed and no finger needs to be kept down yet. */
    fun next(now: Long): TouchStep? {
        if (inFlight || fingers.isEmpty()) return null
        val changed = fingers.values.any { !it.started || it.lifted || it.pending.isNotEmpty() }
        // Held fingers are refreshed now and then so the system keeps them down.
        if (!changed && now - lastStepAt < KEEP_ALIVE_MS) return null
        val steps = fingers.map { (id, state) ->
            val points = listOf(state.at) + state.pending
            state.at = points.last()
            state.pending.clear()
            val first = !state.started
            val fresh = state.fresh
            state.started = true
            state.fresh = false
            // A new finger starts at its down point and follows its moves: the skill key, joystick centre, or swipe
            // start is touched even when the down and the moves arrive together. One that starts and lifts at once
            // is a tap, or a short swipe if it moved. A finger restarted after a cancel starts where it is now.
            FingerStep(id, if (first && !fresh) points.takeLast(1) else points, first, state.lifted)
        }
        val again = fingers.filter { it.value.lifted && it.value.again != null }.mapValues { it.value.again!! }
        fingers.entries.removeAll { it.value.lifted }
        again.forEach { (id, at) -> fingers[id] = Finger(at) }
        inFlight = true
        lastStepAt = now
        val moving = steps.any { it.points.size > 1 }
        val tapOnly = steps.all { it.first && it.last }
        return TouchStep(steps, when {
            tapOnly -> TAP_MS
            moving -> MOVE_MS
            else -> HOLD_MS
        })
    }

    fun completed() {
        inFlight = false
    }

    /** The system dropped the gesture, for example after a real touch. Fingers still down start fresh strokes. */
    fun cancelled() {
        inFlight = false
        generation++
        fingers.values.forEach { it.started = false }
    }

    companion object {
        const val MAX_FINGERS = 10
        const val TAP_MS = 40L
        const val MOVE_MS = 16L
        const val HOLD_MS = 30L
        const val KEEP_ALIVE_MS = 250L
        private const val MAX_PENDING = 32
    }
}
