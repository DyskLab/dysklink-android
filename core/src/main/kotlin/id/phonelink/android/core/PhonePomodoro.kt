package id.phonelink.android.core

import kotlinx.serialization.Serializable

@Serializable
enum class PomodoroPhase(val label: String) {
    Focus("Focus"),
    ShortBreak("Short break"),
    LongBreak("Long break"),
}

@Serializable
data class PomodoroSettings(
    val focusMinutes: Int = 25,
    val shortBreakMinutes: Int = 5,
    val longBreakMinutes: Int = 15,
    /** Focus rounds before the long break. */
    val sessions: Int = 4,
) {
    fun lengthMs(phase: PomodoroPhase): Long = 60_000L * when (phase) {
        PomodoroPhase.Focus -> focusMinutes
        PomodoroPhase.ShortBreak -> shortBreakMinutes
        PomodoroPhase.LongBreak -> longBreakMinutes
    }

    /** Values outside the lists, from an older or edited file, fall back to the defaults. */
    fun checked(): PomodoroSettings {
        val standard = PomodoroSettings()
        return PomodoroSettings(
            focusMinutes = if (focusMinutes in FOCUS_CHOICES) focusMinutes else standard.focusMinutes,
            shortBreakMinutes = if (shortBreakMinutes in SHORT_CHOICES) shortBreakMinutes else standard.shortBreakMinutes,
            longBreakMinutes = if (longBreakMinutes in LONG_CHOICES) longBreakMinutes else standard.longBreakMinutes,
            sessions = if (sessions in SESSION_CHOICES) sessions else standard.sessions,
        )
    }

    companion object {
        val FOCUS_CHOICES = listOf(15, 20, 25, 30, 45, 50, 60)
        val SHORT_CHOICES = listOf(3, 5, 10, 15)
        val LONG_CHOICES = listOf(10, 15, 20, 30)
        val SESSION_CHOICES = listOf(2, 3, 4, 5, 6)
    }
}

/**
 * Focus rounds with short breaks between them, then one long break, like the Mac notch's Pomodoro. The cycle runs
 * on by itself until the long break ends or it is reset. A running step has [endsAt]; a paused one keeps
 * [pausedRemaining]. Times are epoch milliseconds.
 */
@Serializable
data class PomodoroState(
    val phase: PomodoroPhase = PomodoroPhase.Focus,
    val session: Int = 1,
    val endsAt: Long? = null,
    val pausedRemaining: Long? = null,
) {
    val isRunning: Boolean get() = endsAt != null

    /** False only before the first start or after a reset. */
    val isStarted: Boolean get() = this != PomodoroState()

    fun remaining(now: Long, settings: PomodoroSettings): Long =
        endsAt?.let { (it - now).coerceAtLeast(0) } ?: pausedRemaining ?: settings.lengthMs(phase)

    fun progress(now: Long, settings: PomodoroSettings): Float {
        val length = settings.lengthMs(phase).coerceAtLeast(1)
        return (1f - remaining(now, settings).toFloat() / length).coerceIn(0f, 1f)
    }

    fun start(now: Long, settings: PomodoroSettings): PomodoroState =
        if (isRunning) this else copy(endsAt = now + remaining(now, settings), pausedRemaining = null)

    fun pause(now: Long, settings: PomodoroSettings): PomodoroState =
        if (!isRunning) this else copy(pausedRemaining = remaining(now, settings), endsAt = null)

    fun reset(): PomodoroState = PomodoroState()

    /** The step after this one, or null after the long break, when the cycle is over. */
    fun nextStep(settings: PomodoroSettings): Pair<PomodoroPhase, Int>? = when (phase) {
        PomodoroPhase.Focus -> (if (session < settings.sessions) PomodoroPhase.ShortBreak else PomodoroPhase.LongBreak) to session
        PomodoroPhase.ShortBreak -> PomodoroPhase.Focus to session + 1
        PomodoroPhase.LongBreak -> null
    }

    /**
     * Moves on when the running step has ended, and says which step finished. Each step starts when the last one
     * ended, so a phone whose alarm came late catches up correctly. Call again until it returns no finished step.
     */
    fun advance(now: Long, settings: PomodoroSettings): Pair<PomodoroState, PomodoroPhase?> {
        val end = endsAt ?: return this to null
        if (now < end) return this to null
        val next = nextStep(settings) ?: return reset() to phase
        return PomodoroState(next.first, next.second, endsAt = end + settings.lengthMs(next.first)) to phase
    }

    /** Every step that has ended by [now], and the last one that finished. */
    fun catchUp(now: Long, settings: PomodoroSettings): Pair<PomodoroState, PomodoroPhase?> {
        var state = this
        var finished: PomodoroPhase? = null
        repeat(settings.sessions * 2 + 1) {
            val (next, ended) = state.advance(now, settings)
            if (ended == null) return state to finished
            state = next
            finished = ended
        }
        return state to finished
    }

    /** Ends this step now. A running timer goes straight on with the next step; a paused one stays paused. */
    fun skip(now: Long, settings: PomodoroSettings): PomodoroState {
        val next = nextStep(settings) ?: return reset()
        return if (isRunning) PomodoroState(next.first, next.second, endsAt = now + settings.lengthMs(next.first))
        else PomodoroState(next.first, next.second, pausedRemaining = settings.lengthMs(next.first))
    }

    /** "Focus 2/4", for the card and the notification. */
    fun title(settings: PomodoroSettings): String =
        if (phase == PomodoroPhase.LongBreak) phase.label else "${phase.label} $session/${settings.sessions}"

    companion object {
        /** "24:59", or "1:05:00" for an hour or more. */
        fun clock(ms: Long): String {
            val total = (ms + 999) / 1000
            val h = total / 3600
            val m = total % 3600 / 60
            val s = total % 60
            return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s) else String.format(java.util.Locale.US, "%02d:%02d", m, s)
        }
    }
}

@Serializable
data class PomodoroSaved(val settings: PomodoroSettings = PomodoroSettings(), val state: PomodoroState = PomodoroState()) {
    fun encode(): String = PhoneToolsJson.json.encodeToString(serializer(), this)

    companion object {
        fun decode(text: String?): PomodoroSaved {
            val saved = text?.let { runCatching { PhoneToolsJson.json.decodeFromString(serializer(), it) }.getOrNull() } ?: PomodoroSaved()
            return saved.copy(settings = saved.settings.checked())
        }
    }
}
