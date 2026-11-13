package id.dysklink.desktop

import id.phonelink.android.core.Prayer
import id.phonelink.android.core.PrayerLocation
import id.phonelink.android.core.PrayerMonth
import id.phonelink.android.core.PrayerSchedule
import id.phonelink.android.core.PrayerTime
import id.phonelink.android.core.PrayerWeatherApi
import id.phonelink.android.core.WeatherPlace
import id.phonelink.android.core.WeatherReport
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Prayer times and weather for the island, from the Mac notch's sources: equran.id (Indonesian cities, a month at a
// time, kept on this PC so it works offline) and Open-Meteo (no key; the chosen city's coordinates are sent).

data class PlacesView(
    val location: PrayerLocation? = null,
    val months: List<PrayerMonth> = emptyList(),
    val provinces: List<String> = emptyList(),
    val cities: List<String> = emptyList(),
    /** The province being picked in the settings, before a city is chosen. */
    val draftProvince: String? = null,
    val prayerProblem: String? = null,
    val prayerLoading: Boolean = false,
    /** A notification when a prayer time comes, or ten minutes before. Off by default, as on the Mac. */
    val prayerNotify: Boolean = false,
    val prayerEarly: Boolean = false,
    val weatherPlace: WeatherPlace? = null,
    val report: WeatherReport? = null,
    val weatherProblem: String? = null,
    val weatherLoading: Boolean = false,
    val results: List<WeatherPlace> = emptyList(),
    val searchNote: String? = null,
    val fahrenheit: Boolean = false,
) {
    val schedule: PrayerSchedule? get() = location?.let { PrayerSchedule(it, months) }

    fun degrees(celsius: Double): String {
        val value = if (fahrenheit) celsius * 9 / 5 + 32 else celsius
        return "${Math.round(value)}°"
    }
}

object PrayerRules {
    /** After failures in a row: 2 minutes, then 4, 8, ... up to an hour. */
    fun retryDelayMs(failures: Int): Long = if (failures <= 0) 0 else minOf(3_600_000L, 120_000L shl minOf(failures - 1, 10))

    /**
     * The notification due at [now], if any: with [early], the prayer ten minutes away; otherwise the one whose time has
     * just come. Only within [windowMs] after its moment, so a PC that slept doesn't announce old times. As the Mac's.
     */
    fun due(schedule: PrayerSchedule, now: Instant, early: Boolean, windowMs: Long = 90_000): PrayerTime? {
        fun fresh(at: Instant) = !now.isBefore(at) && now.toEpochMilli() - at.toEpochMilli() < windowMs
        if (early) {
            val next = schedule.upcoming(now) ?: return null
            return if (fresh(next.time.toInstant().minusSeconds(600))) PrayerTime(next.prayer, next.time) else null
        }
        return schedule.today(now)?.lastOrNull { !it.time.toInstant().isAfter(now) }?.takeIf { fresh(it.time.toInstant()) }
    }

    /** From the previous time to the next, for the progress bar. */
    fun progress(previous: Instant?, next: Instant, now: Instant): Float {
        previous ?: return 0f
        val span = next.toEpochMilli() - previous.toEpochMilli()
        if (span <= 0) return 0f
        return ((now.toEpochMilli() - previous.toEpochMilli()).toFloat() / span).coerceIn(0f, 1f)
    }

    fun names(): List<String> = Prayer.entries.map { it.label }
}

class IslandPlaces(
    private val scope: CoroutineScope,
    private val settings: Settings,
    dataDir: File,
    private val http: (url: String, body: String?) -> String? = ::request,
    private val clock: () -> Long = System::currentTimeMillis,
    /** A notification when a prayer time comes. */
    private val notify: (String, String) -> Unit = { _, _ -> },
) {
    private val file = File(dataDir, "island-prayer.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val _view = MutableStateFlow(load())
    val view: StateFlow<PlacesView> = _view.asStateFlow()
    @Volatile private var failures = 0
    @Volatile private var retryAt = 0L

    private fun load(): PlacesView {
        val location = settings.string("island.prayer.location")?.split("|")?.takeIf { it.size == 2 && it.all(String::isNotBlank) }?.let { PrayerLocation(it[0], it[1]) }
        val place = settings.string("island.weather.place")?.let { runCatching { json.decodeFromString(WeatherPlace.serializer(), it) }.getOrNull() }
        val months = runCatching { json.decodeFromString(ListSerializer(PrayerMonth.serializer()), file.readText()) }.getOrDefault(emptyList())
        return PlacesView(
            location = location, months = months.filter { it.location == location }, draftProvince = location?.province,
            prayerNotify = settings.bool("island.prayer.notify", false), prayerEarly = settings.bool("island.prayer.early", false),
            weatherPlace = place, fahrenheit = settings.bool("island.weather.f", false),
        )
    }

    private fun saveMonths(months: List<PrayerMonth>) {
        runCatching { file.parentFile?.mkdirs(); file.writeText(json.encodeToString(ListSerializer(PrayerMonth.serializer()), months)) }
    }

    // Prayer times

    fun loadProvinces() {
        if (_view.value.provinces.isNotEmpty()) return
        scope.launch(Dispatchers.IO) {
            val list = http("${PrayerWeatherApi.PRAYER_BASE}/provinsi", null)?.let { runCatching { PrayerWeatherApi.names(it) }.getOrNull() }
            _view.update { if (list != null) it.copy(provinces = list, prayerProblem = null) else it.copy(prayerProblem = "Couldn't load the list of provinces.") }
        }
    }

    fun chooseProvince(province: String) {
        if (province.isBlank()) { _view.update { it.copy(draftProvince = null, cities = emptyList()) }; return }
        _view.update { it.copy(draftProvince = province, cities = emptyList()) }
        scope.launch(Dispatchers.IO) {
            val body = buildJsonObject { put("provinsi", province) }.toString()
            val list = http("${PrayerWeatherApi.PRAYER_BASE}/kabkota", body)?.let { runCatching { PrayerWeatherApi.names(it) }.getOrNull() }
            _view.update {
                if (it.draftProvince != province) it
                else if (list != null) it.copy(cities = list, prayerProblem = null) else it.copy(prayerProblem = "Couldn't load the list of cities.")
            }
        }
    }

    fun chooseCity(city: String) {
        val province = _view.value.draftProvince ?: return
        setLocation(PrayerLocation(province, city))
    }

    fun setLocation(location: PrayerLocation?) {
        if (location == _view.value.location) return
        if (location != null) settings.set("island.prayer.location", "${location.province}|${location.city}")
        else settings.set("island.prayer.location", "")
        failures = 0
        retryAt = 0
        _view.update { it.copy(location = location, months = emptyList(), prayerProblem = null) }
        saveMonths(emptyList())
        refreshPrayer()
    }

    fun setPrayerNotify(on: Boolean, early: Boolean) {
        settings.set("island.prayer.notify", on)
        settings.set("island.prayer.early", early)
        _view.update { it.copy(prayerNotify = on, prayerEarly = early) }
    }

    /** Fetches this month, and next month during the last week. After a failure, later tries wait longer each time. */
    fun refreshPrayer() {
        val v = _view.value
        val location = v.location ?: return
        if (v.prayerLoading || clock() < retryAt) return
        val now = Instant.ofEpochMilli(clock())
        val missing = PrayerSchedule.wanted(now, location.zone).filter { (year, month) ->
            v.months.none { it.location == location && it.year == year && it.month == month }
        }
        if (missing.isEmpty()) return
        _view.update { it.copy(prayerLoading = true) }
        scope.launch(Dispatchers.IO) {
            var failed = false
            for ((year, month) in missing) {
                val body = buildJsonObject { put("provinsi", location.province); put("kabkota", location.city); put("bulan", month); put("tahun", year) }.toString()
                val loaded = http(PrayerWeatherApi.PRAYER_BASE, body)?.let { runCatching { PrayerWeatherApi.month(it, location) }.getOrNull() }
                if (loaded == null) { failed = true; continue }
                // The place may have been changed while this was loading.
                if (_view.value.location != location) break
                _view.update { current ->
                    val months = (current.months.filter { !(it.year == year && it.month == month) } + loaded).filter { it.location == location }.takeLast(3)
                    saveMonths(months)
                    current.copy(months = months, prayerProblem = null)
                }
            }
            failures = if (failed) failures + 1 else 0
            retryAt = if (failed) clock() + PrayerRules.retryDelayMs(failures) else 0
            _view.update { it.copy(prayerLoading = false, prayerProblem = if (failed) "Couldn't load prayer times. They load again later." else it.prayerProblem) }
        }
    }

    // Weather

    fun search(text: String) {
        val query = text.trim()
        if (query.length < 2) { _view.update { it.copy(results = emptyList(), searchNote = null) }; return }
        scope.launch(Dispatchers.IO) {
            val found = http(PrayerWeatherApi.searchUrl(query), null)?.let { runCatching { PrayerWeatherApi.places(it) }.getOrNull() }
            _view.update {
                when {
                    found == null -> it.copy(searchNote = "Couldn't search. Check the connection.")
                    found.isEmpty() -> it.copy(results = emptyList(), searchNote = "No city found with that name.")
                    else -> it.copy(results = found, searchNote = null)
                }
            }
        }
    }

    fun chooseWeather(place: WeatherPlace?) {
        if (place != null) settings.set("island.weather.place", json.encodeToString(WeatherPlace.serializer(), place))
        else settings.set("island.weather.place", "")
        _view.update { it.copy(weatherPlace = place, report = null, results = emptyList(), searchNote = null, weatherProblem = null) }
        refreshWeather(force = true)
    }

    fun setFahrenheit(on: Boolean) {
        settings.set("island.weather.f", on)
        _view.update { it.copy(fahrenheit = on) }
    }

    /** Reloads when the last answer is older than half an hour, or when forced. */
    fun refreshWeather(force: Boolean = false) {
        val v = _view.value
        val place = v.weatherPlace ?: return
        if (v.weatherLoading) return
        if (!force && v.report != null && clock() - v.report.fetchedAt < PrayerWeatherApi.WEATHER_MAX_AGE_MS) return
        _view.update { it.copy(weatherLoading = true) }
        scope.launch(Dispatchers.IO) {
            val report = http(PrayerWeatherApi.forecastUrl(place), null)?.let { runCatching { PrayerWeatherApi.report(it, clock()) }.getOrNull() }
            _view.update {
                if (it.weatherPlace != place) it.copy(weatherLoading = false)
                else if (report != null) it.copy(report = report, weatherProblem = null, weatherLoading = false)
                else it.copy(weatherProblem = "Couldn't load the weather.", weatherLoading = false)
            }
        }
    }

    /** About twice a minute while the island is on: loads what's missing and gives a prayer notification when due. */
    fun tick() {
        refreshPrayer()
        val v = _view.value
        if (!v.prayerNotify) return
        val schedule = v.schedule ?: return
        val due = PrayerRules.due(schedule, Instant.ofEpochMilli(clock()), v.prayerEarly) ?: return
        val id = "${due.prayer.name}-${due.time.toEpochSecond()}-${if (v.prayerEarly) "early" else "now"}"
        if (settings.string("island.prayer.last") == id) return
        settings.set("island.prayer.last", id)
        val clockText = "%02d:%02d".format(due.time.hour, due.time.minute)
        notify(if (v.prayerEarly) "${due.prayer.label} in 10 minutes" else "Time for ${due.prayer.label}", "${schedule.location.label}, $clockText")
    }

    /** For snapshots. */
    fun show(view: PlacesView) { _view.value = view }

    companion object {
        /** A GET, or a POST with a JSON body. Null when the server can't be reached or doesn't answer 200. */
        fun request(url: String, body: String?): String? = runCatching {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "DyskLink-PC")
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
        }.getOrNull()
    }
}
