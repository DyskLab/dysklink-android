package id.phonelink.android.service

import kotlin.test.Test
import kotlin.test.assertEquals

class UpdateScheduleTest {
    private val day = UpdateSchedule.DAY_MS
    private val hour = UpdateSchedule.HOUR_MS
    private val now = 1_000L * day

    @Test
    fun aDayAfterTheLastCheckThatWorked() {
        assertEquals(day - hour, UpdateSchedule.delayMs(now, lastSuccess = now - hour, lastAttempt = now - hour, failures = 0))
        // Overdue, or never checked: soon, but not at once.
        assertEquals(UpdateSchedule.FIRST_DELAY_MS, UpdateSchedule.delayMs(now, lastSuccess = 0, lastAttempt = 0, failures = 0))
    }

    @Test
    fun aFailureWaitsAnHourThenADay() {
        // The case that looped: never a success, so the old schedule tried every 15 seconds.
        assertEquals(hour, UpdateSchedule.delayMs(now, lastSuccess = 0, lastAttempt = now, failures = 1))
        assertEquals(day, UpdateSchedule.delayMs(now, lastSuccess = 0, lastAttempt = now, failures = 2))
        assertEquals(day, UpdateSchedule.delayMs(now, lastSuccess = 0, lastAttempt = now, failures = 9))
        assertEquals(hour - 10 * 60_000L, UpdateSchedule.delayMs(now, lastSuccess = 0, lastAttempt = now - 10 * 60_000L, failures = 1))
    }

    @Test
    fun neverMoreThanADayAway() {
        // A clock set back a week.
        assertEquals(day, UpdateSchedule.delayMs(now, lastSuccess = now + 7 * day, lastAttempt = now + 7 * day, failures = 0))
    }

    @Test
    fun discoveryRetriesBackOff() {
        assertEquals(listOf(5_000L, 30_000L, 300_000L, 300_000L), (0..3).map(NsdRetry::delayMs))
    }
}
