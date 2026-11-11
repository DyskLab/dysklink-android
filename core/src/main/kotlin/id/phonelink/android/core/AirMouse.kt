package id.phonelink.android.core

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns the phone's rotation into pointer movement on the Mac, like a laser pointer. Turning left or right is measured
 * around the direction of gravity, so it works whether the phone is held upright or flat. Tipping the top edge up or
 * down moves the pointer up or down. Not thread safe.
 */
class AirMouse(private val gain: Float = GAIN) {
    private var gravity = floatArrayOf(0f, 0f, 9.81f)
    private var carryX = 0f
    private var carryY = 0f
    private var pendingX = 0f
    private var pendingY = 0f
    private var lastSent = 0L

    fun onGravity(x: Float, y: Float, z: Float) {
        gravity = floatArrayOf(x, y, z)
    }

    /** Adds one gyroscope reading, in radians per second, over [seconds]. */
    fun onRotation(x: Float, y: Float, z: Float, seconds: Float) {
        if (seconds <= 0f || seconds > MAX_STEP_S) return
        val length = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2]).coerceAtLeast(0.1f)
        val turn = (x * gravity[0] + y * gravity[1] + z * gravity[2]) / length
        val tip = x
        pendingX += move(-turn, seconds)
        pendingY += move(-tip, seconds)
    }

    private fun move(rate: Float, seconds: Float): Float {
        val speed = abs(rate)
        if (speed < DEAD_ZONE) return 0f
        // Slow turns are precise, fast ones cross the screen.
        val boost = 1f + ACCELERATION * min(speed, MAX_SPEED)
        return rate * seconds * gain * boost
    }

    /** The whole points to move now, or null when it's too soon or there's nothing to send. Fractions carry over. */
    fun take(now: Long): Pair<Float, Float>? {
        if (now - lastSent < SEND_INTERVAL_MS) return null
        val totalX = pendingX + carryX
        val totalY = pendingY + carryY
        val dx = totalX.toInt().toFloat()
        val dy = totalY.toInt().toFloat()
        carryX = totalX - dx
        carryY = totalY - dy
        pendingX = 0f
        pendingY = 0f
        if (dx == 0f && dy == 0f) return null
        lastSent = now
        return dx to dy
    }

    /** Forgets movement, for when the finger lifts. */
    fun reset() {
        carryX = 0f
        carryY = 0f
        pendingX = 0f
        pendingY = 0f
    }

    companion object {
        /** Points per radian at slow speed: a 60° turn crosses about a laptop screen. */
        const val GAIN = 900f
        const val ACCELERATION = 0.5f
        const val MAX_SPEED = 6f
        const val DEAD_ZONE = 0.03f
        const val SEND_INTERVAL_MS = 15L
        const val MAX_STEP_S = 0.1f
        /** A press shorter than this, with little movement, is a click. */
        const val TAP_MS = 250L
        const val TAP_MOVE = 8f
    }
}
