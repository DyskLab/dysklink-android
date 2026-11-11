package id.phonelink.android.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenTimeMathTest {
    private val min = 60_000L

    @Test
    fun countsEachAppOnceAcrossItsActivities() {
        val math = ScreenTimeMath(0)
        math.add(ScreenTimeMath.Kind.Resumed, "chat", "List", 0)
        math.add(ScreenTimeMath.Kind.Resumed, "chat", "Thread", 2 * min)
        math.add(ScreenTimeMath.Kind.Paused, "chat", "List", 3 * min)
        math.add(ScreenTimeMath.Kind.Paused, "chat", "Thread", 10 * min)
        assertEquals(listOf("chat" to 10 * min), math.result(20 * min))
    }

    @Test
    fun screenOffStopsTheClockAndOpenAppsRunUntilNow() {
        val math = ScreenTimeMath(0)
        math.add(ScreenTimeMath.Kind.Resumed, "video", "Main", 0)
        math.add(ScreenTimeMath.Kind.ScreenOff, "", "", 5 * min)
        math.add(ScreenTimeMath.Kind.Unlocked, "", "", 30 * min)
        math.add(ScreenTimeMath.Kind.Resumed, "maps", "Main", 30 * min)
        assertEquals(listOf("maps" to 10 * min, "video" to 5 * min), math.result(40 * min))
        assertEquals(1, math.unlocks)
    }

    @Test
    fun appOpenAtMidnightCountsFromMidnight() {
        val math = ScreenTimeMath(100 * min)
        math.add(ScreenTimeMath.Kind.Paused, "book", "Read", 104 * min)
        assertEquals(listOf("book" to 4 * min), math.result(110 * min))
    }

    @Test
    fun skipsHomeAndVeryShortUse() {
        val math = ScreenTimeMath(0)
        math.add(ScreenTimeMath.Kind.Resumed, "home", "Launcher", 0)
        math.add(ScreenTimeMath.Kind.Paused, "home", "Launcher", 5 * min)
        math.add(ScreenTimeMath.Kind.Resumed, "calc", "Main", 5 * min)
        math.add(ScreenTimeMath.Kind.Paused, "calc", "Main", 5 * min + 10_000)
        assertEquals(emptyList(), math.result(10 * min) { it == "home" })
    }
}
