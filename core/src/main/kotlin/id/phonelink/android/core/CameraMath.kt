package id.phonelink.android.core

import kotlin.math.abs

/** Size and rotation choices for streaming the phone camera. Kept apart from Android so they can be tested. */
object CameraMath {
    /**
     * Clockwise degrees that turn a camera frame upright, from the sensor's mounting and how the phone is held.
     * [deviceDegrees] is the raw reading from Android's orientation sensor, 0 to 359.
     */
    fun rotation(sensorOrientation: Int, deviceDegrees: Int, front: Boolean): Int {
        val device = ((deviceDegrees + 45) / 90 * 90) % 360
        val signed = if (front) -device else device
        return ((sensorOrientation + signed) % 360 + 360) % 360
    }

    /** Prefers 16:9 near [targetWidth], no larger than 1920 wide. Sizes are (width, height) in sensor orientation. */
    fun pickSize(sizes: List<Pair<Int, Int>>, targetWidth: Int = 1280): Pair<Int, Int>? {
        val usable = sizes.filter { (w, h) -> w in 320..1920 && h > 0 && w % 2 == 0 && h % 2 == 0 }
        val wide = usable.filter { (w, h) -> w * 9 == h * 16 }
        val pool = wide.ifEmpty { usable }
        return pool.minByOrNull { (w, _) -> abs(w - targetWidth) * 2 + if (w > targetWidth) 1 else 0 }
            ?: sizes.minByOrNull { (w, h) -> w * h }
    }

    /** A zoom asked for by another phone, kept between 1 and what the camera can do. Not a number gives 1. */
    fun clampZoom(zoom: Float?, maxZoom: Float): Float {
        if (zoom == null || !zoom.isFinite()) return 1f
        return zoom.coerceIn(1f, maxOf(1f, maxZoom))
    }

    /**
     * The centered part of the sensor to read for [zoom], for cameras without a zoom ratio (before Android 11):
     * left, top, width, height inside the active array of [activeWidth] by [activeHeight].
     */
    fun cropRegion(activeWidth: Int, activeHeight: Int, zoom: Float): IntArray {
        val z = if (zoom.isFinite()) zoom.coerceAtLeast(1f) else 1f
        val width = (activeWidth / z).toInt().coerceIn(1, activeWidth)
        val height = (activeHeight / z).toInt().coerceIn(1, activeHeight)
        return intArrayOf((activeWidth - width) / 2, (activeHeight - height) / 2, width, height)
    }

    /** The biggest size by area, for full-size photos. */
    fun largest(sizes: List<Pair<Int, Int>>): Pair<Int, Int>? = sizes.maxByOrNull { (w, h) -> w.toLong() * h }
}
