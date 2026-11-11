package id.phonelink.android.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MacScreenMathTest {
    private fun near(expected: Float, actual: Float?) = assertEquals(expected, actual!!, 0.001f)

    @Test
    fun fitKeepsTheShape() {
        // A 16:10 picture in a portrait phone view: full width, centered vertically.
        val box = MacScreenMath.fit(1000f, 2000f, 1600f, 1000f)
        near(0f, box[0]); near(687.5f, box[1]); near(1000f, box[2]); near(625f, box[3])
    }

    @Test
    fun touchesMapToTheMacWithoutZoom() {
        val center = MacScreenMath.toMac(500f, 1000f, 1000f, 2000f, 1600f, 1000f, 1f, 0f, 0f)!!
        near(0.5f, center.first); near(0.5f, center.second)
        val corner = MacScreenMath.toMac(0f, 687.5f, 1000f, 2000f, 1600f, 1000f, 1f, 0f, 0f)!!
        near(0f, corner.first); near(0f, corner.second)
        // The black bars above and below the picture aren't on the Mac.
        assertNull(MacScreenMath.toMac(500f, 100f, 1000f, 2000f, 1600f, 1000f, 1f, 0f, 0f))
    }

    @Test
    fun zoomAndPanAreUndone() {
        // Zoomed 2x around the center: a touch at the view's center is still the picture's center.
        val center = MacScreenMath.toMac(500f, 1000f, 1000f, 2000f, 1600f, 1000f, 2f, 0f, 0f)!!
        near(0.5f, center.first)
        // Touching the right edge at 2x reaches three quarters across.
        val right = MacScreenMath.toMac(1000f, 1000f, 1000f, 2000f, 1600f, 1000f, 2f, 0f, 0f)!!
        near(0.75f, right.first)
        // Panned right by 500 at 2x: the view's center shows a quarter across.
        val panned = MacScreenMath.toMac(500f, 1000f, 1000f, 2000f, 1600f, 1000f, 2f, 500f, 0f)!!
        near(0.25f, panned.first)
    }

    @Test
    fun panStaysOnThePicture() {
        assertEquals(500f, MacScreenMath.clampPan(900f, 2f, 1000f))
        assertEquals(0f, MacScreenMath.clampPan(300f, 1f, 1000f))
    }

    @Test
    fun extraDisplayFollowsThePhoneShape() {
        assertEquals(1332 to 600, MacScreenMath.extraDisplaySize(2800f, 1260f))
        assertEquals(1066 to 600, MacScreenMath.extraDisplaySize(1600f, 900f))
        assertEquals(600 to 1332, MacScreenMath.extraDisplaySize(2800f, 1260f, portrait = true))
        assertEquals(1000 to 450, MacScreenMath.extraDisplaySize(2800f, 1260f, large = true))
    }
}
