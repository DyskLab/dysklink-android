package id.phonelink.android.core

import kotlin.test.Test
import kotlin.test.assertEquals

class CameraMathTest {
    @Test
    fun backCameraHeldUpright() {
        // Most back sensors are mounted at 90 degrees, so a phone held upright needs a quarter turn.
        assertEquals(90, CameraMath.rotation(90, 0, front = false))
        assertEquals(180, CameraMath.rotation(90, 90, front = false))
        assertEquals(0, CameraMath.rotation(90, 270, front = false))
        // Readings round to the nearest quarter.
        assertEquals(90, CameraMath.rotation(90, 30, front = false))
        assertEquals(90, CameraMath.rotation(90, 350, front = false))
    }

    @Test
    fun frontCameraTurnsTheOtherWay() {
        assertEquals(270, CameraMath.rotation(270, 0, front = true))
        assertEquals(180, CameraMath.rotation(270, 90, front = true))
        assertEquals(0, CameraMath.rotation(270, 270, front = true))
    }

    @Test
    fun picks720pWhenOffered() {
        val sizes = listOf(4000 to 3000, 1920 to 1080, 1280 to 720, 640 to 480, 1440 to 1080)
        assertEquals(1280 to 720, CameraMath.pickSize(sizes))
    }

    @Test
    fun fallsBackToTheClosestSize() {
        assertEquals(1440 to 1080, CameraMath.pickSize(listOf(4000 to 3000, 1440 to 1080, 640 to 480)))
        assertEquals(1920 to 1080, CameraMath.pickSize(listOf(3840 to 2160, 1920 to 1080)))
        assertEquals(176 to 144, CameraMath.pickSize(listOf(176 to 144)))
    }
}
