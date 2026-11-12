package id.phonelink.android.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MultiTouchTest {
    private fun MultiTouch.step(now: Long = 0): TouchStep = assertNotNull(next(now)).also { completed() }

    @Test
    fun heldJoystickStaysInEveryStepWhileATapComesAndGoes() {
        val touch = MultiTouch()
        touch.down(0, 0.2f, 0.7f)
        val first = touch.step()
        assertEquals(listOf(FingerStep(0, listOf(0.2f to 0.7f), first = true, last = false)), first.fingers)

        touch.move(0, 0.25f, 0.7f)
        touch.down(1, 0.8f, 0.6f)
        touch.up(1)
        val second = touch.step()
        assertEquals(2, second.fingers.size)
        assertEquals(FingerStep(0, listOf(0.2f to 0.7f, 0.25f to 0.7f), first = false, last = false), second.fingers[0])
        // The skill key was tapped: it starts and ends in this one step.
        assertEquals(FingerStep(1, listOf(0.8f to 0.6f), first = true, last = true), second.fingers[1])

        touch.up(0)
        val third = touch.step()
        assertEquals(listOf(FingerStep(0, listOf(0.25f to 0.7f), first = false, last = true)), third.fingers)
        assertTrue(!touch.isActive)
    }

    @Test
    fun aNewFingerStartsAtItsDownPointEvenWhenItsMovesCameInTheSameStep() {
        val touch = MultiTouch()
        touch.down(0, 0.2f, 0.7f)
        touch.step()
        // The skill key is pressed and dragged to aim before the next step goes out.
        touch.move(0, 0.25f, 0.7f)
        touch.down(1, 0.8f, 0.6f)
        touch.move(1, 0.82f, 0.55f)
        touch.move(1, 0.84f, 0.5f)
        val step = touch.step(2)
        assertEquals(FingerStep(1, listOf(0.8f to 0.6f, 0.82f to 0.55f, 0.84f to 0.5f), first = true, last = false), step.fingers[1])
        assertEquals(MultiTouch.MOVE_MS, step.duration)
        // The next stroke continues from where the first one ended.
        touch.move(1, 0.85f, 0.5f)
        assertEquals(FingerStep(1, listOf(0.84f to 0.5f, 0.85f to 0.5f), first = false, last = false), touch.step(3).fingers[1])
    }

    @Test
    fun aDownMovesAndLiftInOneStepIsAShortSwipeAndWithoutMovesATap() {
        val touch = MultiTouch()
        touch.down(4, 0.5f, 0.5f)
        touch.move(4, 0.6f, 0.5f)
        touch.up(4, 0.7f, 0.5f)
        val swipe = touch.step()
        assertEquals(listOf(FingerStep(4, listOf(0.5f to 0.5f, 0.6f to 0.5f, 0.7f to 0.5f), first = true, last = true)), swipe.fingers)
        assertTrue(!touch.isActive)

        touch.down(5, 0.3f, 0.3f)
        touch.up(5)
        val tap = touch.step(1)
        assertEquals(listOf(FingerStep(5, listOf(0.3f to 0.3f), first = true, last = true)), tap.fingers)
        assertEquals(MultiTouch.TAP_MS, tap.duration)
    }

    @Test
    fun onlyOneStepRunsAtATime() {
        val touch = MultiTouch()
        touch.down(0, 0.5f, 0.5f)
        assertNotNull(touch.next(0))
        touch.move(0, 0.6f, 0.5f)
        assertNull(touch.next(1), "A step is still running")
        touch.completed()
        assertEquals(listOf(0.5f to 0.5f, 0.6f to 0.5f), touch.step(2).fingers[0].points)
    }

    @Test
    fun heldFingersAreRefreshed() {
        val touch = MultiTouch()
        touch.down(3, 0.1f, 0.1f)
        touch.step(0)
        assertNull(touch.next(100))
        val refresh = touch.step(MultiTouch.KEEP_ALIVE_MS + 1)
        assertEquals(FingerStep(3, listOf(0.1f to 0.1f), first = false, last = false), refresh.fingers[0])
    }

    @Test
    fun cancelledFingersStartOver() {
        val touch = MultiTouch()
        touch.down(0, 0.3f, 0.3f)
        assertNotNull(touch.next(0))
        touch.cancelled()
        touch.move(0, 0.4f, 0.3f)
        val again = touch.step(1).fingers[0]
        assertTrue(again.first)
        assertEquals(listOf(0.4f to 0.3f), again.points)
    }

    @Test
    fun pressedAgainBeforeTheLiftWentOut() {
        val touch = MultiTouch()
        touch.down(2, 0.5f, 0.5f)
        touch.step(0)
        touch.up(2)
        touch.down(2, 0.5f, 0.5f)
        assertTrue(touch.step(1).fingers.single().last)
        val pressed = touch.step(2).fingers.single()
        assertTrue(pressed.first && !pressed.last)
    }

    @Test
    fun rulesKeepFingerIdsInRange() {
        assertNull(ControlRules.sanitize(ControlEvent("tdown", 0.5f, 0.5f, key = "12")))
        assertNull(ControlRules.sanitize(ControlEvent("tdown", 0.5f, 0.5f)))
        assertEquals(ControlEvent("tmove", 1f, 0f, key = "4"), ControlRules.sanitize(ControlEvent("tmove", 3f, -1f, key = "4")))
    }
}
