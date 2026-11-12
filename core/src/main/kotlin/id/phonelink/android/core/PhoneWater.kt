package id.phonelink.android.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable
data class WaterEntry(val at: Long, val ml: Int)

@Serializable
data class WaterSettings(
    val goalMl: Int = 2000,
    val glassMl: Int = 250,
    val remindEveryMinutes: Int = 60,
    val reminds: Boolean = false,
    /** Reminders only come between these hours, so none arrive at night. */
    val fromHour: Int = 8,
    val toHour: Int = 21,
) {
    /** Values outside the lists, from an older or edited file, fall back to the defaults. */
    fun checked(): WaterSettings {
        val standard = WaterSettings()
        val hoursOk = fromHour in 0..23 && toHour in 1..24 && fromHour < toHour
        return copy(
            goalMl = if (goalMl in GOAL_CHOICES) goalMl else standard.goalMl,
            glassMl = if (glassMl in GLASS_CHOICES) glassMl else standard.glassMl,
            remindEveryMinutes = if (remindEveryMinutes in INTERVAL_CHOICES) remindEveryMinutes else standard.remindEveryMinutes,
            fromHour = if (hoursOk) fromHour else standard.fromHour,
            toHour = if (hoursOk) toHour else standard.toHour,
        )
    }

    companion object {
        val GOAL_CHOICES = listOf(1000, 1500, 2000, 2500, 3000, 3500, 4000)
        val GLASS_CHOICES = listOf(100, 150, 200, 250, 300, 350, 400, 450, 500)
        val INTERVAL_CHOICES = listOf(30, 45, 60, 90, 120)
    }
}

/**
 * What was drunk on this phone, and when the next reminder is due. Same rules as the Mac notch's water page:
 * reminders come every N minutes after the last drink or reminder, only between the chosen hours, and stop once
 * today's goal is reached.
 */
@Serializable
data class WaterLog(
    val settings: WaterSettings = WaterSettings(),
    val entries: List<WaterEntry> = emptyList(),
    val lastReminder: Long? = null,
) {
    fun add(ml: Int, now: Long): WaterLog =
        if (ml !in 1..MAX_DRINK_ML) this else copy(entries = entries + WaterEntry(now, ml))

    /** Takes back the most recent drink of today. */
    fun undo(now: Long, zone: ZoneId): WaterLog {
        val start = startOfDay(now, zone)
        val index = entries.indices.lastOrNull { entries[it].at >= start } ?: return this
        return copy(entries = entries.filterIndexed { i, _ -> i != index })
    }

    /** Keeps the last [KEPT_DAYS] days before today, enough for the chart. */
    fun dropOld(now: Long, zone: ZoneId): WaterLog {
        val limit = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(KEPT_DAYS.toLong())
            .atStartOfDay(zone).toInstant().toEpochMilli()
        val kept = entries.filter { it.at >= limit }
        return if (kept.size == entries.size) this else copy(entries = kept)
    }

    fun total(day: LocalDate, zone: ZoneId): Int {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return entries.filter { it.at in start until end }.sumOf { it.ml }
    }

    fun totalToday(now: Long, zone: ZoneId): Int = total(day(now, zone), zone)

    fun fraction(now: Long, zone: ZoneId): Float =
        (totalToday(now, zone).toFloat() / settings.goalMl.coerceAtLeast(1)).coerceIn(0f, 1f)

    /** Today and the six days before it, oldest first. */
    fun week(now: Long, zone: ZoneId): List<Pair<LocalDate, Int>> {
        val today = day(now, zone)
        return (6 downTo 0).map { back -> today.minusDays(back.toLong()).let { it to total(it, zone) } }
    }

    val lastDrink: Long? get() = entries.maxOfOrNull { it.at }

    /**
     * When the next reminder should come, or null when reminders are off or can never fit in the chosen hours.
     * A reminder that is already overdue comes now.
     */
    fun nextReminder(now: Long, zone: ZoneId): Long? {
        if (!settings.reminds) return null
        val interval = settings.remindEveryMinutes * 60_000L
        val today = day(now, zone)
        if (totalToday(now, zone) < settings.goalMl) {
            val since = listOfNotNull(lastDrink, lastReminder).maxOrNull()
            reminderOn(today, since, interval, zone)?.let { (at, end) ->
                val due = maxOf(at, now)
                if (due < end) return due
            }
        }
        // Goal reached or past the hours: the first one tomorrow. Nothing is drunk tomorrow yet.
        return reminderOn(today.plusDays(1), null, interval, zone)?.first
    }

    /** Due now, allowing an alarm that fires a little early. */
    fun isReminderDue(now: Long, zone: ZoneId): Boolean =
        nextReminder(now, zone)?.let { it <= now + EARLY_SLACK_MS } == true

    /** The reminder time on [day] and the end of that day's hours, if the interval fits in them. */
    private fun reminderOn(day: LocalDate, since: Long?, interval: Long, zone: ZoneId): Pair<Long, Long>? {
        val start = day.atTime(settings.fromHour, 0).atZone(zone).toInstant().toEpochMilli()
        val end = if (settings.toHour >= 24) day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        else day.atTime(settings.toHour, 0).atZone(zone).toInstant().toEpochMilli()
        val at = maxOf(since ?: start, start) + interval
        return if (at < end) at to end else null
    }

    fun encode(): String = PhoneToolsJson.json.encodeToString(serializer(), this)

    companion object {
        const val KEPT_DAYS = 7
        const val MAX_DRINK_ML = 3000
        const val EARLY_SLACK_MS = 60_000L

        fun decode(text: String?): WaterLog {
            val log = text?.let { runCatching { PhoneToolsJson.json.decodeFromString(serializer(), it) }.getOrNull() } ?: WaterLog()
            return log.copy(settings = log.settings.checked())
        }

        private fun day(now: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

        /** "1.5 L" for 1500, "750 ml" for 750. */
        fun amount(ml: Int): String =
            if (ml < 1000) "$ml ml" else (ml / 1000.0).let { if (it == Math.floor(it)) "${it.toInt()} L" else String.format(java.util.Locale.US, "%.2f", it).trimEnd('0') + " L" }
    }
}
