package id.phonelink.android.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.RemoteViews
import id.phonelink.android.MainActivity
import id.phonelink.android.R
import id.phonelink.android.core.PrayerLocation
import id.phonelink.android.core.PrayerMonth
import id.phonelink.android.core.PrayerSchedule
import id.phonelink.android.core.PrayerWeatherApi
import id.phonelink.android.core.WeatherPlace
import id.phonelink.android.core.WeatherReport
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Prayer times and weather on the phone, without the Mac. A month of times is kept on the phone so they work
 * offline. Only the chosen city goes to equran.id, and only its coordinates to Open-Meteo.
 */
object PrayerWeatherStore {
    @Serializable
    data class Saved(
        val location: PrayerLocation? = null,
        val place: WeatherPlace? = null,
        val notifies: Boolean = false,
        val early: Boolean = false,
        val months: List<PrayerMonth> = emptyList(),
        val report: WeatherReport? = null,
    )

    data class State(
        val saved: Saved = Saved(),
        val provinces: List<String> = emptyList(),
        val cities: List<String> = emptyList(),
        val places: List<WeatherPlace> = emptyList(),
        val searchNote: String? = null,
        val problem: String? = null,
        val loading: Boolean = false,
    ) {
        val schedule: PrayerSchedule? get() = saved.location?.let { PrayerSchedule(it, saved.months) }
    }

    private const val CHANNEL_ID = "prayer"
    private const val NOTIFICATION_ID = NotificationIds.PRAYER
    private const val ALARM_CODE = 30
    private const val EXTRA_PRAYER = "prayer"
    private const val EXTRA_TIME = "time"
    private const val EXTRA_EARLY = "early"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    @Volatile private var loaded = false

    private fun file(context: Context) = File(context.filesDir, "prayer_weather.json")

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val saved = runCatching { json.decodeFromString(Saved.serializer(), file(context).readText()) }.getOrNull() ?: Saved()
        _state.update { it.copy(saved = saved) }
    }

    private fun save(context: Context) {
        runCatching { file(context).writeText(json.encodeToString(Saved.serializer(), _state.value.saved)) }
        changed(context)
    }

    /** After anything changes: the widget and the next alarm follow. */
    private fun changed(context: Context) {
        PrayerWidget.render(context)
        schedule(context)
    }

    // Settings

    fun setLocation(context: Context, location: PrayerLocation) {
        load(context)
        _state.update { it.copy(saved = it.saved.copy(location = location, months = emptyList()), cities = emptyList()) }
        save(context)
        refresh(context, force = true)
    }

    fun setPlace(context: Context, place: WeatherPlace) {
        load(context)
        _state.update { it.copy(saved = it.saved.copy(place = place, report = null), places = emptyList(), searchNote = null) }
        save(context)
        refresh(context, force = true)
    }

    fun setNotifies(context: Context, on: Boolean, early: Boolean) {
        load(context)
        _state.update { it.copy(saved = it.saved.copy(notifies = on, early = early)) }
        save(context)
    }

    fun clear(context: Context) {
        _state.update { State() }
        save(context)
    }

    // Network

    private fun get(url: String): String = request(url, null)

    private fun post(url: String, body: String): String = request(url, body)

    private fun request(url: String, body: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("User-Agent", "DyskLink-Android")
        try {
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    fun loadProvinces() {
        if (_state.value.provinces.isNotEmpty()) return
        scope.launch {
            runCatching { PrayerWeatherApi.names(get("${PrayerWeatherApi.PRAYER_BASE}/provinsi")) }
                .onSuccess { list -> _state.update { it.copy(provinces = list, problem = null) } }
                .onFailure { _state.update { it.copy(problem = "Couldn't load the list of provinces.") } }
        }
    }

    fun loadCities(province: String) {
        _state.update { it.copy(cities = emptyList()) }
        scope.launch {
            val body = buildJsonObject { put("provinsi", province) }.toString()
            runCatching { PrayerWeatherApi.names(post("${PrayerWeatherApi.PRAYER_BASE}/kabkota", body)) }
                .onSuccess { list -> _state.update { it.copy(cities = list, problem = null) } }
                .onFailure { _state.update { it.copy(problem = "Couldn't load the list of cities.") } }
        }
    }

    fun search(query: String) {
        val text = query.trim()
        if (text.length < 2) {
            _state.update { it.copy(places = emptyList(), searchNote = null) }
            return
        }
        scope.launch {
            runCatching { PrayerWeatherApi.places(get(PrayerWeatherApi.searchUrl(text))) }
                .onSuccess { list ->
                    _state.update { it.copy(places = list, searchNote = if (list.isEmpty()) "No city found with that name." else null) }
                }
                .onFailure { _state.update { it.copy(searchNote = "Couldn't search. Check the connection.") } }
        }
    }

    fun refresh(context: Context, force: Boolean = false) {
        load(context)
        scope.launch { refreshNow(context.applicationContext, force) }
    }

    /** Loads missing months and weather older than 30 minutes. Safe to call often. */
    suspend fun refreshNow(context: Context, force: Boolean = false) = lock.withLock {
        load(context)
        _state.update { it.copy(loading = true) }
        var problem: String? = null
        val now = Instant.now()
        _state.value.saved.location?.let { location ->
            val missing = PrayerSchedule.wanted(now, location.zone).filter { (year, month) ->
                _state.value.saved.months.none { it.location == location && it.year == year && it.month == month }
            }
            for ((year, month) in missing) {
                val body = buildJsonObject {
                    put("provinsi", location.province)
                    put("kabkota", location.city)
                    put("bulan", month)
                    put("tahun", year)
                }.toString()
                runCatching { PrayerWeatherApi.month(post(PrayerWeatherApi.PRAYER_BASE, body), location) }
                    .onSuccess { loadedMonth ->
                        _state.update { state ->
                            // The city may have changed while this was loading.
                            if (state.saved.location != location) return@update state
                            val months = (state.saved.months.filterNot { it.location == location && it.year == year && it.month == month } + loadedMonth)
                                .filter { it.location == location }.takeLast(3)
                            state.copy(saved = state.saved.copy(months = months))
                        }
                    }
                    .onFailure { problem = "Couldn't load prayer times. They load again later." }
            }
        }
        _state.value.saved.place?.let { place ->
            val report = _state.value.saved.report
            if (force || report == null || System.currentTimeMillis() - report.fetchedAt > PrayerWeatherApi.WEATHER_MAX_AGE_MS) {
                runCatching { PrayerWeatherApi.report(get(PrayerWeatherApi.forecastUrl(place)), System.currentTimeMillis()) }
                    .onSuccess { loadedReport ->
                        _state.update {
                            if (it.saved.place != place) it else it.copy(saved = it.saved.copy(report = loadedReport))
                        }
                    }
                    .onFailure { problem = problem ?: "Couldn't load the weather." }
            }
        }
        _state.update { it.copy(loading = false, problem = problem) }
        save(context)
    }

    // Alarms and notifications

    fun canAlarmExactly(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun exactAlarmSettings(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${context.packageName}"))

    /**
     * One alarm for the next thing that matters: the next prayer, for the widget, or 10 minutes before it when
     * early reminders are on. Each alarm schedules the following one.
     */
    fun schedule(context: Context) {
        load(context)
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, PrayerAlarmReceiver::class.java)
        val existing = PendingIntent.getBroadcast(context, ALARM_CODE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)
        existing?.let { alarms.cancel(it) }
        val saved = _state.value.saved
        val schedule = _state.value.schedule ?: return
        if (!saved.notifies && !PrayerWidget.hasWidgets(context)) return
        val now = Instant.now()
        // A little ahead, so an alarm that fires a moment early doesn't schedule the same prayer again.
        val upcoming = schedule.upcoming(now.plusSeconds(30)) ?: return
        val prayerAt = upcoming.time.toInstant().toEpochMilli()
        val earlyAt = prayerAt - 10 * 60_000
        val useEarly = saved.notifies && saved.early && earlyAt > now.toEpochMilli()
        val fireAt = if (useEarly) earlyAt else prayerAt
        intent.putExtra(EXTRA_PRAYER, upcoming.prayer.name).putExtra(EXTRA_TIME, prayerAt).putExtra(EXTRA_EARLY, useEarly)
        val pending = PendingIntent.getBroadcast(context, ALARM_CODE, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (saved.notifies && canAlarmExactly(context)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
        } else {
            // Without the exact alarm permission Android may run this a few minutes late.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pending)
        }
    }

    internal fun fired(context: Context, intent: Intent) {
        load(context)
        val saved = _state.value.saved
        val prayer = intent.getStringExtra(EXTRA_PRAYER)?.let { name -> id.phonelink.android.core.Prayer.entries.firstOrNull { it.name == name } }
        val time = intent.getLongExtra(EXTRA_TIME, 0)
        val early = intent.getBooleanExtra(EXTRA_EARLY, false)
        // A phone that was off doesn't announce times that passed long ago.
        val late = System.currentTimeMillis() - (if (early) time - 10 * 60_000 else time) > 5 * 60_000
        if (saved.notifies && prayer != null && !late && (early == saved.early || !early)) {
            val clock = Instant.ofEpochMilli(time).atZone(saved.location?.zone ?: java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm"))
            notify(context, if (early) "${prayer.label} in 10 minutes" else "Time for ${prayer.label}", "${saved.location?.label ?: ""} · $clock")
        }
        changed(context)
    }

    private fun notify(context: Context, title: String, text: String) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Prayer times", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 31, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_logo_glyph)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}

class PrayerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        PrayerWeatherStore.fired(app, intent)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                PrayerWeatherStore.refreshNow(app)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Shows the next prayer and today's weather. */
class PrayerWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        val app = context.applicationContext
        render(app)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                PrayerWeatherStore.refreshNow(app)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun hasWidgets(context: Context): Boolean =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, PrayerWidget::class.java)).isNotEmpty()

        fun render(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, PrayerWidget::class.java))
            if (ids.isEmpty()) return
            PrayerWeatherStore.load(context)
            val state = PrayerWeatherStore.state.value
            val saved = state.saved
            val views = RemoteViews(context.packageName, R.layout.widget_prayer)
            val upcoming = state.schedule?.upcoming(Instant.now())
            if (upcoming != null) {
                views.setTextViewText(R.id.prayer_name, upcoming.prayer.label)
                views.setTextViewText(R.id.prayer_time, upcoming.time.format(DateTimeFormatter.ofPattern("HH:mm")))
            } else {
                views.setTextViewText(R.id.prayer_name, if (saved.location == null) "Prayer times" else "Loading…")
                views.setTextViewText(R.id.prayer_time, "")
            }
            views.setTextViewText(R.id.prayer_place, saved.location?.label ?: "Choose a city in DyskLink")
            val report = saved.report
            views.setTextViewText(
                R.id.prayer_weather,
                if (report != null) "${report.temperature.toInt()}° · ${PrayerWeatherApi.describe(report.code)}"
                else if (saved.place == null) "" else "Weather loading…",
            )
            val open = Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_PRAYER)
            views.setOnClickPendingIntent(
                R.id.prayer_root,
                PendingIntent.getActivity(context, 32, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            manager.updateAppWidget(ids, views)
        }
    }
}
