package id.phonelink.android.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import id.phonelink.android.MainActivity
import id.phonelink.android.R
import id.phonelink.android.core.PomodoroPhase
import id.phonelink.android.core.PomodoroSaved
import id.phonelink.android.core.PomodoroSettings
import id.phonelink.android.core.PomodoroState
import id.phonelink.android.core.TodoList
import id.phonelink.android.core.WaterLog
import id.phonelink.android.core.WaterSettings
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Todo, Water, and Pomodoro on the phone itself: no Mac needed, and nothing leaves the phone. The rules live in
 * core (PhoneTodo, PhoneWater, PhonePomodoro); this file saves them, sets alarms, and posts notifications.
 * Everything runs on the main thread (screens and broadcast receivers), and the files are a few kilobytes.
 */

/** Asks the app to open one of these tools, from a notification. */
object PhoneToolsNav {
    const val TODO = "todo"
    const val WATER = "water"
    const val POMODORO = "pomodoro"
    val request = MutableStateFlow<Pair<String, Long>?>(null)

    fun openIntent(context: Context, tool: String, code: Int): PendingIntent = PendingIntent.getActivity(
        context,
        code,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_TOOL_PREFIX + tool)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

private object ToolFiles {
    fun read(context: Context, name: String): String? =
        runCatching { File(context.filesDir, name).takeIf { it.exists() }?.readText() }.getOrNull()

    /** Written next to the file first, so a crash mid-write never leaves half a list. */
    fun write(context: Context, name: String, text: String) {
        runCatching {
            val file = File(context.filesDir, name)
            val temp = File(context.filesDir, "$name.tmp")
            temp.writeText(text)
            if (!temp.renameTo(file)) {
                file.writeText(text)
                temp.delete()
            }
        }
    }
}

private fun canNotify(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun canAlarmExactly(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

private fun toolBroadcast(context: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
    context,
    code,
    Intent(context, PhoneToolsReceiver::class.java).setAction(action),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

// Todo

object TodoStore {
    private const val FILE = "tools_todo.json"
    private val _list = MutableStateFlow(TodoList())
    val list: StateFlow<TodoList> = _list.asStateFlow()
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (!loaded) {
            loaded = true
            _list.value = TodoList.decode(ToolFiles.read(context, FILE))
        }
        // Each day starts with only what's open.
        update(context) { it.dropOld(System.currentTimeMillis(), ZoneId.systemDefault()) }
    }

    @Synchronized
    private fun update(context: Context, change: (TodoList) -> TodoList) {
        val before = _list.value
        val after = change(before)
        if (after == before) return
        _list.value = after
        ToolFiles.write(context, FILE, after.encode())
    }

    /** False when the text is empty or the list is full. */
    fun add(context: Context, text: String): Boolean {
        load(context)
        val added = _list.value.add(text, System.currentTimeMillis()) ?: return false
        update(context) { added }
        return true
    }

    fun toggle(context: Context, id: String) = update(context) { it.toggle(id, System.currentTimeMillis()) }
    fun rename(context: Context, id: String, title: String) = update(context) { it.rename(id, title) }
    fun delete(context: Context, id: String) = update(context) { it.delete(id) }
    fun moveOpen(context: Context, from: Int, to: Int) = update(context) { it.moveOpen(from, to) }
    fun clearDone(context: Context) = update(context) { it.clearDone() }
}

// Water

object WaterStore {
    private const val FILE = "tools_water.json"
    private const val CHANNEL_ID = "water"
    private const val ALARM_CODE = 420
    private const val DRINK_CODE = 421
    private const val OPEN_CODE = 422
    private val _log = MutableStateFlow(WaterLog())
    val log: StateFlow<WaterLog> = _log.asStateFlow()
    private var loaded = false

    private val zone: ZoneId get() = ZoneId.systemDefault()

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _log.value = WaterLog.decode(ToolFiles.read(context, FILE)).dropOld(System.currentTimeMillis(), zone)
    }

    @Synchronized
    private fun update(context: Context, change: (WaterLog) -> WaterLog) {
        load(context)
        val after = change(_log.value).dropOld(System.currentTimeMillis(), zone)
        if (after == _log.value) return
        _log.value = after
        ToolFiles.write(context, FILE, after.encode())
        schedule(context)
    }

    /** A glass of the chosen size, or [ml]. */
    fun drink(context: Context, ml: Int? = null) {
        update(context) { it.add(ml ?: it.settings.glassMl, System.currentTimeMillis()) }
        // A drink answers the reminder.
        context.getSystemService(NotificationManager::class.java).cancel(NotificationIds.WATER)
    }

    fun undo(context: Context) = update(context) { it.undo(System.currentTimeMillis(), zone) }

    fun setSettings(context: Context, settings: WaterSettings) = update(context) { it.copy(settings = settings.checked()) }

    /** One inexact alarm for the next reminder, or none. Android may run it a few minutes late, which is fine here. */
    fun schedule(context: Context) {
        load(context)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = toolBroadcast(context, PhoneToolsReceiver.WATER_REMIND, ALARM_CODE)
        alarms.cancel(pending)
        val next = _log.value.nextReminder(System.currentTimeMillis(), zone) ?: return
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
    }

    internal fun reminderFired(context: Context) {
        load(context)
        val now = System.currentTimeMillis()
        val log = _log.value
        if (log.isReminderDue(now, zone)) {
            if (canNotify(context)) notifyReminder(context, log, now)
            update(context) { it.copy(lastReminder = now) }
        }
        schedule(context)
    }

    private fun notifyReminder(context: Context, log: WaterLog, now: Long) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Water reminders", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val glass = log.settings.glassMl
        val drink = Notification.Action.Builder(null, "Drank a glass ($glass ml)", toolBroadcast(context, PhoneToolsReceiver.WATER_DRINK, DRINK_CODE)).build()
        notifications.notify(
            NotificationIds.WATER,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_logo_glyph)
                .setContentTitle("Time to drink water")
                .setContentText("${log.totalToday(now, zone)} of ${log.settings.goalMl} ml today.")
                .setContentIntent(PhoneToolsNav.openIntent(context, PhoneToolsNav.WATER, OPEN_CODE))
                .addAction(drink)
                .setAutoCancel(true)
                .build(),
        )
    }
}

// Pomodoro

/**
 * The timer itself is just an end time, so nothing needs to run while it counts down: an alarm wakes the app when a
 * step ends, and the notification counts down by itself with a chronometer. Exact when the app may set exact alarms
 * (allowed for prayer reminders), otherwise an inexact alarm that may come a little late in Doze; the next step is
 * still timed from when the last one should have ended.
 */
object PomodoroStore {
    private const val FILE = "tools_pomodoro.json"
    private const val CHANNEL_ID = "pomodoro"
    private const val ALERT_CHANNEL_ID = "pomodoro_alert"
    private const val ALARM_CODE = 410
    private const val PAUSE_CODE = 411
    private const val RESUME_CODE = 412
    private const val SKIP_CODE = 413
    private const val RESET_CODE = 414
    private const val OPEN_CODE = 415
    private val _saved = MutableStateFlow(PomodoroSaved())
    val saved: StateFlow<PomodoroSaved> = _saved.asStateFlow()
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _saved.value = PomodoroSaved.decode(ToolFiles.read(context, FILE))
    }

    /** Sets the new state, saves it, and brings the alarm and the notification along. */
    @Synchronized
    private fun apply(context: Context, saved: PomodoroSaved, finished: PomodoroPhase? = null) {
        val changed = saved != _saved.value
        _saved.value = saved
        if (changed) ToolFiles.write(context, FILE, saved.encode())
        schedule(context)
        if (changed || finished != null) notifyState(context, finished)
    }

    private fun change(context: Context, block: (PomodoroState, PomodoroSettings, Long) -> PomodoroState) {
        load(context)
        val now = System.currentTimeMillis()
        val (current, finished) = _saved.value.state.catchUp(now, _saved.value.settings)
        apply(context, _saved.value.copy(state = block(current, _saved.value.settings, now)), finished)
    }

    fun start(context: Context) = change(context) { state, settings, now -> state.start(now, settings) }
    fun pause(context: Context) = change(context) { state, settings, now -> state.pause(now, settings) }
    fun skip(context: Context) = change(context) { state, settings, now -> state.skip(now, settings) }
    fun reset(context: Context) = change(context) { state, _, _ -> state.reset() }

    fun setSettings(context: Context, settings: PomodoroSettings) {
        load(context)
        apply(context, _saved.value.copy(settings = settings.checked()))
    }

    /** Called every second while a screen shows the timer, and when an alarm fires: moves on past ended steps. */
    fun tick(context: Context) {
        load(context)
        val (state, finished) = _saved.value.state.catchUp(System.currentTimeMillis(), _saved.value.settings)
        if (finished != null) apply(context, _saved.value.copy(state = state), finished)
    }

    /** An alarm that came before the end, which inexact alarms shouldn't, still leaves the next one set. */
    internal fun alarmFired(context: Context) {
        tick(context)
        schedule(context)
    }

    /** After a reboot or an update: the alarm and the notification are gone, so both come back. */
    fun restore(context: Context) {
        load(context)
        val (state, finished) = _saved.value.state.catchUp(System.currentTimeMillis(), _saved.value.settings)
        // Steps that ended while the phone was off don't ring now.
        _saved.value = _saved.value.copy(state = state)
        ToolFiles.write(context, FILE, _saved.value.encode())
        schedule(context)
        if (finished != null || state.isStarted) notifyState(context, null)
    }

    private fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = toolBroadcast(context, PhoneToolsReceiver.POMODORO_END, ALARM_CODE)
        alarms.cancel(pending)
        val end = _saved.value.state.endsAt ?: return
        if (canAlarmExactly(context)) {
            runCatching { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pending) }
                .onFailure { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pending) }
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pending)
        }
    }

    private fun channels(notifications: NotificationManager) {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Pomodoro timer", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The countdown while a Pomodoro runs."
                setShowBadge(false)
            },
        )
        notifications.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "Pomodoro alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A sound and vibration when a focus round or break ends."
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
                )
            },
        )
    }

    /**
     * The countdown notification. When a step just ended ([finished]) it goes out on the alert channel, so it
     * rings and vibrates; pausing and resuming stay quiet.
     */
    private fun notifyState(context: Context, finished: PomodoroPhase?) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val saved = _saved.value
        val state = saved.state
        val settings = saved.settings
        if (!canNotify(context)) return
        channels(notifications)
        val open = PhoneToolsNav.openIntent(context, PhoneToolsNav.POMODORO, OPEN_CODE)
        val builder = Notification.Builder(context, if (finished != null) ALERT_CHANNEL_ID else CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_glyph)
            .setContentIntent(open)
            .setCategory(if (finished != null) Notification.CATEGORY_ALARM else Notification.CATEGORY_PROGRESS)
        if (!state.isStarted) {
            if (finished == null) {
                notifications.cancel(NotificationIds.POMODORO)
                return
            }
            notifications.notify(
                NotificationIds.POMODORO,
                builder.setContentTitle("Pomodoro cycle complete")
                    .setContentText("${settings.sessions} focus rounds done. Start again when you're ready.")
                    .setAutoCancel(true)
                    .build(),
            )
            return
        }
        val now = System.currentTimeMillis()
        val endedText = finished?.let { "${it.label} done. " }.orEmpty()
        val next = state.nextStep(settings)?.first?.label?.let { "then $it" } ?: "then the cycle ends"
        if (state.isRunning) {
            val end = state.endsAt!!
            val clock = Instant.ofEpochMilli(end).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
            builder.setContentTitle(state.title(settings))
                .setContentText("${endedText}Until $clock, $next.")
                .setWhen(end)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "Pause", toolBroadcast(context, PhoneToolsReceiver.POMODORO_PAUSE, PAUSE_CODE)).build())
                .addAction(Notification.Action.Builder(null, "Skip", toolBroadcast(context, PhoneToolsReceiver.POMODORO_SKIP, SKIP_CODE)).build())
        } else {
            builder.setContentTitle("${state.title(settings)} · Paused")
                .setContentText("$endedText${PomodoroState.clock(state.remaining(now, settings))} left, $next.")
                .setShowWhen(false)
                .addAction(Notification.Action.Builder(null, "Resume", toolBroadcast(context, PhoneToolsReceiver.POMODORO_RESUME, RESUME_CODE)).build())
                .addAction(Notification.Action.Builder(null, "Skip", toolBroadcast(context, PhoneToolsReceiver.POMODORO_SKIP, SKIP_CODE)).build())
                .addAction(Notification.Action.Builder(null, "Reset", toolBroadcast(context, PhoneToolsReceiver.POMODORO_RESET, RESET_CODE)).build())
        }
        notifications.notify(NotificationIds.POMODORO, builder.build())
    }
}

/** Alarms and notification buttons for the phone's own tools. */
class PhoneToolsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            WATER_REMIND -> WaterStore.reminderFired(app)
            WATER_DRINK -> WaterStore.drink(app)
            POMODORO_END -> PomodoroStore.alarmFired(app)
            POMODORO_PAUSE -> PomodoroStore.pause(app)
            POMODORO_RESUME -> PomodoroStore.start(app)
            POMODORO_SKIP -> PomodoroStore.skip(app)
            POMODORO_RESET -> PomodoroStore.reset(app)
        }
    }

    companion object {
        const val WATER_REMIND = "id.phonelink.android.tools.WATER_REMIND"
        const val WATER_DRINK = "id.phonelink.android.tools.WATER_DRINK"
        const val POMODORO_END = "id.phonelink.android.tools.POMODORO_END"
        const val POMODORO_PAUSE = "id.phonelink.android.tools.POMODORO_PAUSE"
        const val POMODORO_RESUME = "id.phonelink.android.tools.POMODORO_RESUME"
        const val POMODORO_SKIP = "id.phonelink.android.tools.POMODORO_SKIP"
        const val POMODORO_RESET = "id.phonelink.android.tools.POMODORO_RESET"

        /** After a reboot, an update, or a clock change. */
        fun restore(context: Context) {
            WaterStore.schedule(context)
            PomodoroStore.restore(context)
        }
    }
}
