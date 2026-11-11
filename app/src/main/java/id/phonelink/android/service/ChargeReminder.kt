package id.phonelink.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import id.phonelink.android.MainActivity
import id.phonelink.android.R
import id.phonelink.android.core.protocolJson
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One time the phone was plugged in, from plugging in to unplugging. */
@Serializable
data class ChargeSession(val start: Long, val startLevel: Int, val end: Long? = null, val endLevel: Int? = null)

/**
 * Reminds the user to unplug when the battery reaches a limit, once per charge, and keeps the last 20 charges.
 * Android doesn't let normal apps stop charging, so this can only remind. Off by default.
 */
object ChargeReminder {
    private const val CHANNEL_ID = "charge_limit"
    private const val NOTIFICATION_ID = 8
    private const val MAX_SESSIONS = 20
    val limitChoices = listOf(70, 75, 80, 85, 90, 95)

    data class Settings(val on: Boolean = false, val limit: Int = 80)

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val _sessions = MutableStateFlow<List<ChargeSession>>(emptyList())
    val sessions: StateFlow<List<ChargeSession>> = _sessions.asStateFlow()

    private var receiver: BroadcastReceiver? = null
    @Volatile private var started = false
    private var remindedThisCharge = false
    private var wasCharging: Boolean? = null
    /** Files are read and written here, off the main thread, in order. */
    private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun settingsFile(context: Context) = File(context.filesDir, "charge_settings.json")
    private fun sessionsFile(context: Context) = File(context.filesDir, "charge_sessions.json")

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        io.execute {
            val loaded = runCatching { protocolJson.decodeFromString(SettingsFile.serializer(), settingsFile(app).readText()) }
                .getOrNull()?.let { Settings(it.on, it.limit.takeIf { limit -> limit in limitChoices } ?: 80) } ?: Settings()
            val history = runCatching {
                protocolJson.decodeFromString(ListSerializer(ChargeSession.serializer()), sessionsFile(app).readText())
            }.getOrNull().orEmpty()
            main.post {
                _settings.value = loaded
                _sessions.value = history
                if (started) syncReceiver(app)
            }
        }
    }

    fun stop(context: Context) {
        started = false
        unregister(context.applicationContext)
    }

    /** Battery broadcasts wake the app often, so they're only listened to while the reminder (and its history) is on. */
    private fun syncReceiver(app: Context) {
        if (_settings.value.on && started) register(app) else unregister(app)
    }

    private fun register(app: Context) {
        if (receiver != null) return
        val created = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = handle(app, intent)
        }
        receiver = created
        // A sticky broadcast: the current state arrives at once, then every change.
        ContextCompat.registerReceiver(app, created, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun unregister(app: Context) {
        val current = receiver ?: return
        runCatching { app.unregisterReceiver(current) }
        receiver = null
        wasCharging = null
        // A charge left open would read "charging" forever: close it at the current level.
        val open = _sessions.value.firstOrNull()?.takeIf { it.end == null } ?: return
        val sticky = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = (sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100).coerceAtLeast(1)
        _sessions.value = listOf(open.copy(end = System.currentTimeMillis(), endLevel = if (level >= 0) level * 100 / scale else open.startLevel)) +
            _sessions.value.drop(1)
        saveSessions(app)
    }

    fun update(context: Context, settings: Settings) {
        _settings.value = settings
        remindedThisCharge = false
        val app = context.applicationContext
        io.execute { runCatching { settingsFile(app).writeText(protocolJson.encodeToString(SettingsFile.serializer(), SettingsFile(settings.on, settings.limit))) } }
        syncReceiver(app)
        if (!settings.on) context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    fun clearHistory(context: Context) {
        _sessions.value = _sessions.value.filter { it.end == null }
        saveSessions(context)
    }

    private fun handle(context: Context, intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (level < 0) return
        val percent = level * 100 / scale
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val now = System.currentTimeMillis()
        if (plugged != wasCharging) {
            val open = _sessions.value.firstOrNull()?.takeIf { it.end == null }
            if (plugged && open == null) {
                _sessions.value = (listOf(ChargeSession(now, percent)) + _sessions.value).take(MAX_SESSIONS)
                saveSessions(context)
            } else if (!plugged && open != null) {
                _sessions.value = listOf(open.copy(end = now, endLevel = percent)) + _sessions.value.drop(1)
                saveSessions(context)
            }
            if (!plugged) {
                remindedThisCharge = false
                context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
            }
            wasCharging = plugged
        }
        val settings = _settings.value
        if (plugged && settings.on && !remindedThisCharge && percent >= settings.limit) {
            remindedThisCharge = true
            notify(context, percent)
        }
    }

    private fun notify(context: Context, percent: Int) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Charge limit", NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(context, 8, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_logo_glyph)
                .setContentTitle("Battery at $percent%")
                .setContentText("You can unplug the charger now.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun saveSessions(context: Context) {
        val snapshot = _sessions.value
        val app = context.applicationContext
        io.execute {
            runCatching { sessionsFile(app).writeText(protocolJson.encodeToString(ListSerializer(ChargeSession.serializer()), snapshot)) }
        }
    }

    @Serializable
    private data class SettingsFile(val on: Boolean, val limit: Int)
}
