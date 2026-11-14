package id.phonelink.android.service

import kotlinx.coroutines.launch

import android.app.Activity
import androidx.activity.compose.setContent
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import id.phonelink.android.ClipboardSendActivity
import id.phonelink.android.MainActivity
import id.phonelink.android.R
import id.phonelink.android.core.ConnectionManager
import id.phonelink.android.core.NotchAction
import id.phonelink.android.core.NotchSnapshot
import id.phonelink.android.core.Peer
import id.phonelink.android.core.WakeFlow
import id.phonelink.android.core.WakeOnLan
import id.phonelink.android.core.WakeState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Widgets fed by the Mac notch and the connected devices. They redraw when either changes. */
object Widgets {
    const val ACTION = "id.phonelink.android.WIDGET_ACTION"
    const val EXTRA_KIND = "kind"
    const val EXTRA_VALUE = "value"

    /** Redraws every DyskLink widget on the home screen. */
    fun renderAll(context: Context) {
        val connections = PhoneLinkService.connectionManager.value
        val peers = connections?.peers?.value.orEmpty()
        val notch = connections?.notch?.value.orEmpty()
        val mac = peers.firstOrNull { it.isMac && it.isPaired && it.isConnected && it.id in notch }
            ?: peers.firstOrNull { it.isMac && it.isPaired && it.isConnected }
        val snapshot = mac?.let { notch[it.id] }
        render<NoteWidget>(context) { note(context, mac, snapshot) }
        render<TodoWidget>(context) { todo(context, mac, snapshot) }
        render<FocusWidget>(context) { focus(context, mac, snapshot) }
        render<MediaWidget>(context) { media(context, mac, snapshot) }
        render<DevicesWidget>(context) { devices(context, peers) }
        val manager = AppWidgetManager.getInstance(context)
        manager.getAppWidgetIds(ComponentName(context, ShortcutsWidget::class.java)).forEach { id ->
            manager.updateAppWidget(id, shortcuts(context, id, mac))
        }
    }

    private inline fun <reified T : AppWidgetProvider> render(context: Context, views: () -> RemoteViews) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, T::class.java))
        if (ids.isNotEmpty()) manager.updateAppWidget(ids, views())
    }

    // Each widget's request codes are kept apart, so their PendingIntents don't replace each other.

    private fun openApp(context: Context, code: Int, feature: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        feature?.let { intent.putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_FEATURE_PREFIX + it) }
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(context: Context, code: Int, kind: String, value: String? = null): PendingIntent =
        PendingIntent.getBroadcast(
            context, code,
            Intent(context, WidgetActionReceiver::class.java).setAction(ACTION).putExtra(EXTRA_KIND, kind).putExtra(EXTRA_VALUE, value),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun waiting(mac: Peer?, snapshot: NotchSnapshot?): String? = when {
        mac == null -> "Connect to your Mac"
        snapshot == null -> "Waiting for ${mac.name}"
        else -> null
    }

    private fun note(context: Context, mac: Peer?, snapshot: NotchSnapshot?) = RemoteViews(context.packageName, R.layout.widget_note).apply {
        setTextViewText(R.id.w_title, "QUICK NOTE")
        setTextViewText(R.id.w_action, "Edit")
        val text = snapshot?.state?.note
        setTextViewText(R.id.w_body, text?.ifBlank { "Empty. Tap to write something." } ?: waiting(mac, snapshot))
        setTextViewText(R.id.w_sub, mac?.name.orEmpty())
        setOnClickPendingIntent(R.id.w_root, openApp(context, 100, "Notch"))
        setOnClickPendingIntent(R.id.w_action, openApp(context, 101, "Notch"))
    }

    private val rows = listOf(R.id.w_row0, R.id.w_row1, R.id.w_row2, R.id.w_row3, R.id.w_row4)
    private val checks = listOf(R.id.w_check0, R.id.w_check1, R.id.w_check2, R.id.w_check3, R.id.w_check4)
    private val tasks = listOf(R.id.w_task0, R.id.w_task1, R.id.w_task2, R.id.w_task3, R.id.w_task4)

    private fun todo(context: Context, mac: Peer?, snapshot: NotchSnapshot?) = RemoteViews(context.packageName, R.layout.widget_todo).apply {
        setTextViewText(R.id.w_title, "TODO")
        setTextViewText(R.id.w_action, "+ Add")
        setOnClickPendingIntent(R.id.w_action, openApp(context, 110, "Notch"))
        setOnClickPendingIntent(R.id.w_root, openApp(context, 111, "Notch"))
        // Open tasks first, so the ones left to do fit.
        val list = snapshot?.state?.todos.orEmpty().sortedBy { it.done }
        val done = list.count { it.done }
        setTextViewText(
            R.id.w_sub,
            waiting(mac, snapshot) ?: if (list.isEmpty()) "No tasks. Tap + Add." else "$done of ${list.size} done" +
                if (list.size > rows.size) " · ${list.size - rows.size} more in the app" else "",
        )
        rows.indices.forEach { i ->
            val task = list.getOrNull(i)
            setViewVisibility(rows[i], if (task == null) View.GONE else View.VISIBLE)
            if (task == null) return@forEach
            setTextViewText(checks[i], if (task.done) "✓" else "")
            setTextViewText(tasks[i], task.title)
            setInt(tasks[i], "setPaintFlags", if (task.done) android.graphics.Paint.STRIKE_THRU_TEXT_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG else android.graphics.Paint.ANTI_ALIAS_FLAG)
            setOnClickPendingIntent(rows[i], action(context, 120 + i, "todo.toggle", task.id))
        }
    }

    private fun focus(context: Context, mac: Peer?, snapshot: NotchSnapshot?) = RemoteViews(context.packageName, R.layout.widget_focus).apply {
        setTextViewText(R.id.w_title, "POMODORO & WATER")
        setViewVisibility(R.id.w_action, View.GONE)
        setOnClickPendingIntent(R.id.w_root, openApp(context, 130, "Notch"))
        val state = snapshot?.state
        if (state == null) {
            setChronometer(R.id.w_timer, SystemClock.elapsedRealtime(), "--:--", false)
            setTextViewText(R.id.w_phase, waiting(mac, snapshot))
            setTextViewText(R.id.w_water, "")
            setTextViewText(R.id.w_water_sub, "")
            setTextViewText(R.id.w_btn1, "Start")
            setTextViewText(R.id.w_btn2, "+ Glass")
            return@apply
        }
        val focus = state.focus
        val left = if (focus.running) (focus.remainingMs - (System.currentTimeMillis() - snapshot.receivedAt)).coerceAtLeast(0) else focus.remainingMs
        // The launcher counts down by itself, so the widget doesn't need to redraw every second.
        setChronometer(R.id.w_timer, SystemClock.elapsedRealtime() + left, null, focus.running)
        setChronometerCountDown(R.id.w_timer, true)
        val phase = when (focus.phase) { "shortBreak" -> "Short break"; "longBreak" -> "Long break"; else -> "Focus" }
        setTextViewText(R.id.w_phase, "$phase · ${focus.session} of ${focus.sessions}")
        val water = state.water
        setTextViewText(R.id.w_water, "%.1f L".format(water.totalMl / 1000.0))
        setTextViewText(R.id.w_water_sub, "of %.1f L".format(water.goalMl / 1000.0))
        setTextViewText(R.id.w_btn1, if (focus.running) "Pause" else "Start")
        setTextViewText(R.id.w_btn2, "+ ${water.glassMl} ml")
        setOnClickPendingIntent(R.id.w_btn1, action(context, 131, "focus.toggle"))
        setOnClickPendingIntent(R.id.w_btn2, action(context, 132, "water.drink"))
    }

    private fun media(context: Context, mac: Peer?, snapshot: NotchSnapshot?) = RemoteViews(context.packageName, R.layout.widget_media).apply {
        setTextViewText(R.id.w_title, "ON THE MAC")
        setViewVisibility(R.id.w_action, View.GONE)
        setOnClickPendingIntent(R.id.w_root, openApp(context, 140, "Media"))
        val music = snapshot?.state?.music
        setTextViewText(R.id.w_song, music?.title?.ifBlank { null } ?: if (mac == null) "Not connected" else "Nothing playing")
        setTextViewText(R.id.w_sub, music?.artist?.ifBlank { null } ?: mac?.name.orEmpty())
        setTextViewText(R.id.w_prev, "⏮")
        setTextViewText(R.id.w_play, if (music?.playing == true) "⏸" else "▶")
        setTextViewText(R.id.w_next, "⏭")
        setOnClickPendingIntent(R.id.w_prev, action(context, 141, "media", "previous"))
        setOnClickPendingIntent(R.id.w_play, action(context, 142, "media", "play_pause"))
        setOnClickPendingIntent(R.id.w_next, action(context, 143, "media", "next"))
    }

    private val devRows = listOf(R.id.w_dev0, R.id.w_dev1, R.id.w_dev2, R.id.w_dev3)
    private val devNames = listOf(R.id.w_dev_name0, R.id.w_dev_name1, R.id.w_dev_name2, R.id.w_dev_name3)
    private val devBars = listOf(R.id.w_dev_bar0, R.id.w_dev_bar1, R.id.w_dev_bar2, R.id.w_dev_bar3)
    private val devValues = listOf(R.id.w_dev_value0, R.id.w_dev_value1, R.id.w_dev_value2, R.id.w_dev_value3)

    /** One row: name, percent, and whether it charges. */
    data class BatteryRow(val name: String, val percent: Int?, val charging: Boolean)

    /** This phone first, then connected devices, then the rest. */
    fun batteryRows(own: BatteryRow, peers: List<Peer>): List<BatteryRow> =
        listOf(own) + peers.filter { it.isPaired }
            .sortedWith(compareByDescending<Peer> { it.isConnected }.thenBy { !it.isMac }.thenBy { it.name })
            .map { BatteryRow(it.name, it.info?.batteryPercent?.takeIf { _ -> it.isConnected }, it.info?.isCharging == true && it.isConnected) }

    private fun devices(context: Context, peers: List<Peer>) = RemoteViews(context.packageName, R.layout.widget_devices).apply {
        setTextViewText(R.id.w_title, "BATTERY")
        setViewVisibility(R.id.w_action, View.GONE)
        setOnClickPendingIntent(R.id.w_root, openApp(context, 150))
        val sticky = context.registerReceiver(null, IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val own = BatteryRow(
            "This phone",
            if (level >= 0 && scale > 0) level * 100 / scale else null,
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
        )
        val list = batteryRows(own, peers)
        devRows.indices.forEach { i ->
            val row = list.getOrNull(i)
            setViewVisibility(devRows[i], if (row == null) View.GONE else View.VISIBLE)
            if (row == null) return@forEach
            setTextViewText(devNames[i], row.name)
            setProgressBar(devBars[i], 100, row.percent ?: 0, false)
            setTextViewText(devValues[i], row.percent?.let { (if (row.charging) "⚡" else "") + "$it%" } ?: "—")
        }
    }

    private val shortcutViews = listOf(R.id.w_sc0, R.id.w_sc1, R.id.w_sc2, R.id.w_sc3)

    private fun shortcuts(context: Context, widgetId: Int, mac: Peer?) = RemoteViews(context.packageName, R.layout.widget_shortcuts).apply {
        val chosen = WidgetShortcut.load(context, widgetId)
        shortcutViews.indices.forEach { i ->
            val shortcut = chosen.getOrNull(i)
            setViewVisibility(shortcutViews[i], if (shortcut == null) View.GONE else View.VISIBLE)
            if (shortcut == null) return@forEach
            val label = if (shortcut == WidgetShortcut.KeepAwake && mac?.info?.keepAwake == "active") "Awake: on" else shortcut.label
            setTextViewText(shortcutViews[i], label)
            val code = 1000 + widgetId * 10 + i
            val intent = when (shortcut) {
                WidgetShortcut.Clipboard -> PendingIntent.getActivity(
                    context, code, Intent(context, ClipboardSendActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                )
                WidgetShortcut.Touchpad, WidgetShortcut.Terminal, WidgetShortcut.Commands, WidgetShortcut.Deck ->
                    openApp(context, code, shortcut.feature)
                else -> action(context, code, shortcut.name)
            }
            setOnClickPendingIntent(shortcutViews[i], intent)
        }
    }

    private val wakeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    private fun toast(context: Context, text: String) =
        android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()

    /**
     * The Wake Mac button. Starts the service when it isn't running and sends either way; [pending] is finished
     * within about 8 seconds. Says what really happened: sent first, then whether the Mac woke within 45 seconds.
     */
    fun wake(context: Context, pending: android.content.BroadcastReceiver.PendingResult) {
        val app = context.applicationContext
        wakeScope.launch {
            try {
                val connections = if (PhoneLinkService.start(app)) {
                    kotlinx.coroutines.withTimeoutOrNull(SERVICE_WAIT_MS) {
                        PhoneLinkService.connectionManager.first { it != null }
                    }
                } else {
                    null
                }
                if (connections == null) return@launch toast(app, wakeWithoutService(app))
                val target = connections.peers.value.firstOrNull { it.isMac && it.isPaired && it.canWake }
                when {
                    target == null -> toast(app, "No Mac to wake yet. Connect once so DyskLink learns its address.")
                    target.isAwake -> toast(app, "${target.name} is already awake")
                    else -> {
                        val sent = connections.wakeMac(target.id)
                        val state = connections.peers.value.firstOrNull { it.id == target.id }?.wake ?: WakeState.NotSent
                        toast(app, WakeFlow.summary(if (sent) state else WakeState.NotSent, target.name))
                        if (sent) followWake(app, connections, target)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** The second toast, once the Mac connected or the wait ran out. The running service keeps the app alive. */
    private fun followWake(context: Context, connections: ConnectionManager, target: Peer) {
        wakeScope.launch {
            val result = kotlinx.coroutines.withTimeoutOrNull(WakeFlow.WAIT_MS + 15_000) {
                connections.peers.map { list -> list.firstOrNull { it.id == target.id }?.wake }
                    .first { it is WakeState.Woke || it is WakeState.DidntWake }
            } ?: return@launch
            toast(context, WakeFlow.summary(result, target.name))
        }
    }

    /** Android refused to start the service: the packet is sent from the saved address, without waiting for the Mac. */
    private suspend fun wakeWithoutService(context: Context): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val record = ConnectionManager.storedRecords(context.filesDir).values
            .firstOrNull { it.platform == "macos" && it.wakeMacs.isNotEmpty() }
            ?: return@withContext "No Mac to wake yet. Open DyskLink and connect once so it learns the Mac's address."
        val route = runCatching { WifiWake.route(context) }.getOrNull()
        val sent = WakeOnLan.send(record.wakeMacs, listOf(route?.broadcast, record.wakeBroadcast), route?.bind)
        if (sent) "Wake signal sent to ${record.name}. Open DyskLink to see whether it connects."
        else "Couldn't send the wake signal. Check that this phone is on Wi-Fi."
    }

    private const val SERVICE_WAIT_MS = 6_000L

    /** Runs a widget button. True when it was one of these. */
    fun handle(context: Context, kind: String?, value: String?): Boolean {
        val connections: ConnectionManager = PhoneLinkService.connectionManager.value ?: return false
        val mac = connections.peers.value.firstOrNull { it.isMac && it.isPaired && it.isConnected }
        when (kind) {
            "todo.toggle", "focus.toggle", "water.drink" -> mac?.let { connections.sendNotchAction(it.id, NotchAction(kind, id = value)) }
            "media" -> mac?.let { connections.sendMedia(it.id, value ?: return false) }
            WidgetShortcut.Lock.name -> connections.sendMacAction("lock")
            WidgetShortcut.KeepAwake.name ->
                connections.sendMacAction(if (mac?.info?.keepAwake == "active") "awake.stop" else "awake.start")
            WidgetShortcut.RingMac.name -> mac?.let { connections.ringMac(it.id, true) }
            WidgetShortcut.PlayPause.name -> mac?.let { connections.sendMedia(it.id, "play_pause") }
            else -> return false
        }
        if (mac == null) android.widget.Toast.makeText(context, "Not connected to a Mac", android.widget.Toast.LENGTH_SHORT).show()
        return true
    }
}

/** What the shortcuts widget can hold. */
enum class WidgetShortcut(val label: String, val feature: String? = null) {
    Clipboard("Send clipboard"),
    Lock("Lock Mac"),
    KeepAwake("Keep awake"),
    RingMac("Ring Mac"),
    PlayPause("Play / pause"),
    WakeMac("Wake Mac"),
    Touchpad("Touchpad", "Touchpad"),
    Terminal("Terminal", "Terminal"),
    Commands("Commands", "Commands"),
    Deck("Stream Deck", "Deck");

    companion object {
        val defaults = listOf(Clipboard, Lock, KeepAwake, PlayPause)
        private const val PREFS = "widget_shortcuts"

        fun load(context: Context, widgetId: Int): List<WidgetShortcut> {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(widgetId.toString(), null)
                ?: return defaults
            return saved.split(',').mapNotNull { name -> entries.firstOrNull { it.name == name } }.take(4)
        }

        fun save(context: Context, widgetId: Int, list: List<WidgetShortcut>) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(widgetId.toString(), list.take(4).joinToString(",") { it.name }).apply()
        }
    }
}

abstract class NotchWidgetBase : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        PhoneLinkService.start(context)
        Widgets.renderAll(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        Widgets.renderAll(context)
    }
}

class NoteWidget : NotchWidgetBase()
class TodoWidget : NotchWidgetBase()
class FocusWidget : NotchWidgetBase()
class MediaWidget : NotchWidgetBase()
class DevicesWidget : NotchWidgetBase()
class ShortcutsWidget : NotchWidgetBase()

/** Picks up to four buttons for the shortcuts widget. */
class ShortcutsConfigActivity : androidx.activity.ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        val start = WidgetShortcut.load(this, widgetId)
        setContent {
            id.phonelink.android.ui.PhoneLinkTheme {
                id.phonelink.android.ui.ShortcutsConfigScreen(start) { chosen ->
                    WidgetShortcut.save(this, widgetId, chosen)
                    Widgets.renderAll(this)
                    setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                    finish()
                }
            }
        }
    }
}
