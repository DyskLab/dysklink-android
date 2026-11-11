package id.phonelink.android.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AirMouseTest {
    @Test
    fun turningLeftMovesLeftWhenFlat() {
        val mouse = AirMouse()
        mouse.onGravity(0f, 0f, 9.81f)
        repeat(10) { mouse.onRotation(0f, 0f, 1f, 0.01f) }
        val (dx, dy) = mouse.take(1_000)!!
        assertTrue(dx < 0, "dx $dx")
        assertEquals(0f, dy)
    }

    @Test
    fun turningLeftMovesLeftWhenUpright() {
        val mouse = AirMouse()
        // Upright: gravity along the phone's long side, so turning is around Y.
        mouse.onGravity(0f, 9.81f, 0f)
        repeat(10) { mouse.onRotation(0f, 1f, 0f, 0.01f) }
        assertTrue(mouse.take(1_000)!!.first < 0)
    }

    @Test
    fun tippingUpMovesUp() {
        val mouse = AirMouse()
        repeat(10) { mouse.onRotation(1f, 0f, 0f, 0.01f) }
        assertTrue(mouse.take(1_000)!!.second < 0)
    }

    @Test
    fun driftBelowTheDeadZoneIsIgnored() {
        val mouse = AirMouse()
        repeat(100) { mouse.onRotation(0.01f, 0.01f, 0.01f, 0.01f) }
        assertNull(mouse.take(1_000))
    }

    @Test
    fun fractionsCarryOverAndSendsAreSpaced() {
        val mouse = AirMouse(gain = 100f)
        mouse.onRotation(0f, 0f, -0.05f, 0.1f)
        // 0.05 × 0.1 × 100 × boost ≈ 0.5 points: not enough yet.
        assertNull(mouse.take(1_000))
        mouse.onRotation(0f, 0f, -0.05f, 0.1f)
        assertEquals(1f, mouse.take(1_100)!!.first)
        mouse.onRotation(0f, 0f, -1f, 0.1f)
        assertNull(mouse.take(1_105), "too soon after the last send")
    }

    @Test
    fun fastTurnsGoFurtherThanSlowOnesOverTheSameAngle() {
        val slow = AirMouse().apply { repeat(100) { onRotation(0f, 0f, -0.5f, 0.01f) } }.take(1_000)!!.first
        val fast = AirMouse().apply { repeat(10) { onRotation(0f, 0f, -5f, 0.01f) } }.take(1_000)!!.first
        assertTrue(fast > slow, "fast $fast slow $slow")
    }
}
