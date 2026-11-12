package id.phonelink.android.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneToolsTest {
    private val zone = ZoneId.of("Asia/Jakarta")
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    // Todo

    @Test
    fun todoAddsCleanTitlesAndRefusesEmptyOrFull() {
        val list = TodoList().add("  Buy milk\nsecond line ", at(12, 9), "a")!!
        assertEquals("Buy milk", list.items.single().title)
        assertNull(list.add("   ", at(12, 9)))
        var full = TodoList()
        repeat(TodoList.MAX_ITEMS) { full = full.add("task $it", 0, "$it")!! }
        assertNull(full.add("one more", 0))
        assertEquals("x".repeat(TodoList.MAX_TITLE), TodoList.clean("x".repeat(500)))
    }

    @Test
    fun todoTogglesRenamesDeletesAndCounts() {
        var list = TodoList().add("a", 0, "a")!!.add("b", 0, "b")!!.add("c", 0, "c")!!
        list = list.toggle("b", at(12, 10))
        assertEquals(2, list.left)
        assertEquals("2 left · 1 done today", list.summary(at(12, 11), zone))
        assertEquals(listOf("a", "c", "b"), list.visible(at(12, 11), zone).map { it.id })
        list = list.rename("a", "Apples").rename("c", "  ")
        assertEquals(listOf("Apples", "b", "c"), list.items.map { it.title })
        list = list.delete("c").toggle("b", at(12, 12))
        assertEquals(listOf("a", "b"), list.open.map { it.id })
        assertEquals("No tasks. Tap to add one.", TodoList().summary(at(12, 11), zone))
    }

    @Test
    fun todoMovesOpenTasksAroundDoneOnes() {
        var list = TodoList()
        listOf("a", "b", "c", "d").forEach { list = list.add(it, 0, it)!! }
        list = list.toggle("b", at(12, 10))
        // Open: a, c, d. Move d to the top.
        list = list.moveOpen(2, 0)
        assertEquals(listOf("d", "a", "c"), list.open.map { it.id })
        assertEquals("b", list.items[1].id)
        assertEquals(list, list.moveOpen(0, 9))
    }

    @Test
    fun todoDropsTasksFinishedOnAnEarlierDay() {
        var list = TodoList().add("old", 0, "old")!!.add("today", 0, "today")!!.add("open", 0, "open")!!
        list = list.toggle("old", at(11, 23, 50)).toggle("today", at(12, 0, 10))
        // Still the 11th: everything stays.
        assertEquals(3, list.dropOld(at(11, 23, 59), zone).items.size)
        val next = list.dropOld(at(12, 8), zone)
        assertEquals(listOf("today", "open"), next.items.map { it.id })
        assertEquals(listOf("open"), next.dropOld(at(13, 0, 1), zone).items.map { it.id })
        assertEquals("All done · 1 today", TodoList(next.items.filter { it.isDone }).summary(at(12, 9), zone))
    }

    @Test
    fun todoSavesAndLoads() {
        val list = TodoList().add("a", 5, "a")!!.add("b", 6, "b")!!.toggle("a", 7)
        assertEquals(list, TodoList.decode(list.encode()))
        assertEquals(TodoList(), TodoList.decode(null))
        assertEquals(TodoList(), TodoList.decode("{broken"))
    }

    // Water

    @Test
    fun waterTotalsPerDayAndUndoesTodayOnly() {
        var log = WaterLog().add(250, at(11, 20)).add(250, at(12, 8)).add(300, at(12, 9)).add(0, at(12, 9)).add(5000, at(12, 9))
        assertEquals(550, log.totalToday(at(12, 10), zone))
        assertEquals(250, log.total(LocalDate.of(2026, 11, 11), zone))
        log = log.undo(at(12, 10), zone)
        assertEquals(250, log.totalToday(at(12, 10), zone))
        log = log.undo(at(12, 10), zone).undo(at(12, 10), zone)
        // Yesterday's drink is never taken back.
        assertEquals(0, log.totalToday(at(12, 10), zone))
        assertEquals(1, log.entries.size)
        val week = WaterLog().add(500, at(6, 9)).add(1000, at(12, 9)).week(at(12, 10), zone)
        assertEquals(7, week.size)
        assertEquals(LocalDate.of(2026, 11, 6) to 500, week.first())
        assertEquals(LocalDate.of(2026, 11, 12) to 1000, week.last())
        assertEquals(0.5f, WaterLog().add(1000, at(12, 9)).fraction(at(12, 10), zone))
    }

    @Test
    fun waterKeepsAWeek() {
        val log = WaterLog().add(100, at(4, 23)).add(100, at(5, 0, 30)).add(100, at(12, 9))
        assertEquals(2, log.dropOld(at(12, 10), zone).entries.size)
    }

    @Test
    fun waterRemindersStayInsideTheHours() {
        val on = WaterLog(WaterSettings(reminds = true, remindEveryMinutes = 60, fromHour = 8, toHour = 21))
        assertNull(WaterLog().nextReminder(at(12, 10), zone))
        // Before the hours: an interval after they begin.
        assertEquals(at(12, 9), on.nextReminder(at(12, 6), zone))
        // An interval after the last drink.
        assertEquals(at(12, 11, 30), on.add(250, at(12, 10, 30)).nextReminder(at(12, 10, 45), zone))
        // An interval after the last reminder, if that was later.
        assertEquals(at(12, 12), on.add(250, at(12, 10)).copy(lastReminder = at(12, 11)).nextReminder(at(12, 11), zone))
        // Overdue: now.
        assertEquals(at(12, 15), on.add(250, at(12, 10)).nextReminder(at(12, 15), zone))
        assertTrue(on.add(250, at(12, 10)).isReminderDue(at(12, 11), zone))
        assertTrue(on.add(250, at(12, 10)).isReminderDue(at(12, 10, 59) + 30_000, zone))
        assertFalse(on.add(250, at(12, 10)).isReminderDue(at(12, 10, 30), zone))
        // Past the end of the hours: tomorrow.
        assertEquals(at(13, 9), on.add(250, at(12, 20, 30)).nextReminder(at(12, 20, 40), zone))
        assertEquals(at(13, 9), on.nextReminder(at(12, 22), zone))
        assertFalse(on.isReminderDue(at(12, 22), zone))
        // An interval longer than the hours never fits.
        assertNull(WaterLog(WaterSettings(reminds = true, remindEveryMinutes = 120, fromHour = 8, toHour = 9)).nextReminder(at(12, 6), zone))
    }

    @Test
    fun waterRemindersStopOnceTheGoalIsReached() {
        val log = WaterLog(WaterSettings(reminds = true, goalMl = 1000)).add(500, at(12, 9)).add(500, at(12, 10))
        assertFalse(log.isReminderDue(at(12, 12), zone))
        assertEquals(at(13, 9), log.nextReminder(at(12, 12), zone))
        assertTrue(log.undo(at(12, 12), zone).isReminderDue(at(12, 12), zone))
    }

    @Test
    fun waterSavesAndLoads() {
        val log = WaterLog(WaterSettings(goalMl = 2500, glassMl = 300, reminds = true), lastReminder = 9).add(300, 10)
        assertEquals(log, WaterLog.decode(log.encode()))
        assertEquals(WaterLog(), WaterLog.decode("nope"))
        // Out-of-range settings fall back.
        val odd = WaterLog.decode("""{"settings":{"goalMl":7,"glassMl":9999,"remindEveryMinutes":1,"fromHour":22,"toHour":5}}""")
        assertEquals(WaterSettings(), odd.settings)
        assertEquals("1.5 L", WaterLog.amount(1500))
        assertEquals("2 L", WaterLog.amount(2000))
        assertEquals("750 ml", WaterLog.amount(750))
    }

    // Pomodoro

    private val settings = PomodoroSettings()

    @Test
    fun pomodoroRunsTheCycleWithALongBreakAfterFourFocusRounds() {
        var state = PomodoroState().start(0, settings)
        var now = 0L
        val seen = mutableListOf<String>()
        while (true) {
            seen += "${state.phase}${state.session}"
            now = state.endsAt!!
            val (next, finished) = state.advance(now, settings)
            assertNotNull(finished)
            state = next
            if (!state.isStarted) break
        }
        assertEquals(
            listOf("Focus1", "ShortBreak1", "Focus2", "ShortBreak2", "Focus3", "ShortBreak3", "Focus4", "LongBreak4"),
            seen,
        )
        assertEquals(4 * 25 * 60_000L + 3 * 5 * 60_000L + 15 * 60_000L, now)
        val two = PomodoroSettings(sessions = 2)
        val second = PomodoroState(PomodoroPhase.Focus, 2, endsAt = 10)
        assertEquals(PomodoroPhase.LongBreak, second.advance(10, two).first.phase)
    }

    @Test
    fun pomodoroPausesResumesAndCatchesUp() {
        var state = PomodoroState().start(1_000, settings)
        assertEquals(25 * 60_000L, state.remaining(1_000, settings))
        state = state.pause(61_000, settings)
        assertFalse(state.isRunning)
        assertEquals(24 * 60_000L, state.remaining(999_999, settings))
        state = state.start(100_000, settings)
        assertEquals(100_000 + 24 * 60_000L, state.endsAt)
        assertEquals(state to null, state.advance(state.endsAt!! - 1, settings))
        // An alarm 7 minutes late finishes the focus round and goes into the short break, timed from its real end.
        val end = state.endsAt!!
        val (late, finished) = state.catchUp(end + 7 * 60_000, settings)
        assertEquals(PomodoroPhase.ShortBreak, finished)
        assertEquals(PomodoroPhase.Focus, late.phase)
        assertEquals(2, late.session)
        assertEquals(end + 5 * 60_000 + 25 * 60_000, late.endsAt)
        assertEquals(PomodoroState(), state.reset())
    }

    @Test
    fun pomodoroSkipsToTheNextStep() {
        val running = PomodoroState().start(0, settings).skip(1_000, settings)
        assertEquals(PomodoroPhase.ShortBreak, running.phase)
        assertEquals(1_000 + 5 * 60_000L, running.endsAt)
        val paused = PomodoroState(PomodoroPhase.ShortBreak, 1, pausedRemaining = 10).skip(0, settings)
        assertEquals(PomodoroState(PomodoroPhase.Focus, 2, pausedRemaining = 25 * 60_000L), paused)
        assertEquals(PomodoroPhase.LongBreak, PomodoroState(PomodoroPhase.Focus, 4).skip(0, settings).phase)
        assertEquals(PomodoroState(), PomodoroState(PomodoroPhase.LongBreak, 4, endsAt = 9).skip(0, settings))
        assertEquals("Focus 2/4", paused.title(settings))
        assertEquals("24:59", PomodoroState.clock(24 * 60_000L + 59_000))
        assertEquals("1:00:00", PomodoroState.clock(3_600_000))
    }

    @Test
    fun pomodoroSavesAndLoads() {
        val saved = PomodoroSaved(PomodoroSettings(50, 10, 30, 3), PomodoroState(PomodoroPhase.ShortBreak, 2, endsAt = 123))
        assertEquals(saved, PomodoroSaved.decode(saved.encode()))
        assertEquals(PomodoroSaved(), PomodoroSaved.decode(null))
        assertEquals(PomodoroSettings(), PomodoroSaved.decode("""{"settings":{"focusMinutes":7,"sessions":99}}""").settings)
    }
}
