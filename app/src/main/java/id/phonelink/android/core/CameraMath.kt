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
}
