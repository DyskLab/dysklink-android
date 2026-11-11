package id.phonelink.android.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double

/**
 * Prayer times from equran.id and weather from Open-Meteo, the same sources as the Mac notch.
 * This file only parses and computes, so it can be tested without Android.
 */
enum class Prayer(val label: String) { Subuh("Subuh"), Dzuhur("Dzuhur"), Ashar("Ashar"), Maghrib("Maghrib"), Isya("Isya") }

@Serializable
data class PrayerLocation(val province: String, val city: String) {
    val label: String get() = city.removePrefix("Kota ").removePrefix("Kab. ")

    /** The schedule is in the place's own time, whatever zone the phone is set to. */
    val zone: ZoneId
        get() = ZoneId.of(
            when (province) {
                in WIT -> "Asia/Jayapura"
                in WITA -> "Asia/Makassar"
                else -> "Asia/Jakarta"
            },
        )

    private companion object {
        val WITA = setOf(
            "Bali", "Nusa Tenggara Barat", "Nusa Tenggara Timur", "Kalimantan Selatan", "Kalimantan Tengah",
            "Kalimantan Timur", "Kalimantan Utara", "Sulawesi Barat", "Sulawesi Selatan", "Sulawesi Tengah",
            "Sulawesi Tenggara", "Sulawesi Utara", "Gorontalo",
        )
        val WIT = setOf("Maluku", "Maluku Utara", "Papua", "Papua Barat")
    }
}

/** One day of times as "HH:mm", the way equran.id sends them. */
@Serializable
data class PrayerDay(
    val date: String,
    val subuh: String,
    val dzuhur: String,
    val ashar: String,
    val maghrib: String,
    val isya: String,
) {
    fun text(prayer: Prayer): String = when (prayer) {
        Prayer.Subuh -> subuh
        Prayer.Dzuhur -> dzuhur
        Prayer.Ashar -> ashar
        Prayer.Maghrib -> maghrib
        Prayer.Isya -> isya
    }
}

@Serializable
data class PrayerMonth(val location: PrayerLocation, val year: Int, val month: Int, val days: List<PrayerDay>)

data class PrayerTime(val prayer: Prayer, val time: ZonedDateTime)

data class UpcomingPrayer(val prayer: Prayer, val time: ZonedDateTime, val previous: ZonedDateTime?)

class PrayerSchedule(val location: PrayerLocation, months: List<PrayerMonth>) {
    private val days: Map<String, PrayerDay> =
        months.filter { it.location == location }.flatMap { it.days }.associateBy { it.date }

    val isEmpty: Boolean get() = days.isEmpty()

    /** The five times of the day `date` in the place's zone, or null if that day isn't loaded. */
    fun times(date: LocalDate): List<PrayerTime>? {
        val day = days[date.toString()] ?: return null
        val times = Prayer.entries.mapNotNull { prayer ->
            val clock = runCatching { LocalTime.parse(day.text(prayer)) }.getOrNull() ?: return@mapNotNull null
            PrayerTime(prayer, ZonedDateTime.of(date, clock, location.zone))
        }
        return times.takeIf { it.size == Prayer.entries.size }
    }

    fun today(now: Instant): List<PrayerTime>? = times(now.atZone(location.zone).toLocalDate())

    /** The next time after `now`, looking into tomorrow once today's are over. */
    fun upcoming(now: Instant): UpcomingPrayer? {
        val date = now.atZone(location.zone).toLocalDate()
        val today = times(date) ?: return null
        val index = today.indexOfFirst { it.time.toInstant().isAfter(now) }
        if (index >= 0) {
            val previous = if (index > 0) today[index - 1].time else times(date.minusDays(1))?.last()?.time
            return UpcomingPrayer(today[index].prayer, today[index].time, previous)
        }
        val tomorrow = times(date.plusDays(1)) ?: return null
        return UpcomingPrayer(tomorrow[0].prayer, tomorrow[0].time, today.last().time)
    }

    /** Which months to fetch: this one, and next month during the last week. */
    companion object {
        fun wanted(now: Instant, zone: ZoneId): List<Pair<Int, Int>> {
            val date = now.atZone(zone).toLocalDate()
            val result = mutableListOf(date.year to date.monthValue)
            if (date.dayOfMonth > date.lengthOfMonth() - 7) {
                val next = date.plusMonths(1)
                result += next.year to next.monthValue
            }
            return result
        }
    }
}

@Serializable
data class WeatherPlace(val name: String, val region: String? = null, val latitude: Double, val longitude: Double) {
    val shortName: String
        get() = listOf("Kota ", "Kabupaten ", "Kab. ").firstOrNull { name.startsWith(it) }?.let { name.removePrefix(it) } ?: name
    val label: String get() = listOfNotNull(name, region).joinToString(", ")
}

@Serializable
data class WeatherDay(val date: String, val code: Int, val high: Double, val low: Double, val rainChance: Int)

@Serializable
data class WeatherReport(
    val temperature: Double,
    val code: Int,
    val isDay: Boolean,
    val days: List<WeatherDay>,
    val fetchedAt: Long,
)

object PrayerWeatherApi {
    const val PRAYER_BASE = "https://equran.id/api/v2/shalat"
    const val FORECAST = "https://api.open-meteo.com/v1/forecast"
    const val GEOCODING = "https://geocoding-api.open-meteo.com/v1/search"
    const val WEATHER_MAX_AGE_MS = 30 * 60 * 1000L

    private val json = Json { ignoreUnknownKeys = true }

    fun names(body: String): List<String> =
        json.parseToJsonElement(body).jsonObject["data"]!!.jsonArray.map { it.jsonPrimitive.content }

    fun month(body: String, location: PrayerLocation): PrayerMonth {
        val data = json.parseToJsonElement(body).jsonObject["data"]!!.jsonObject
        val days = data["jadwal"]!!.jsonArray.map {
            val day = it.jsonObject
            fun text(key: String) = day[key]!!.jsonPrimitive.content
            PrayerDay(text("tanggal_lengkap"), text("subuh"), text("dzuhur"), text("ashar"), text("maghrib"), text("isya"))
        }
        require(days.isNotEmpty()) { "No days" }
        return PrayerMonth(location, data["tahun"]!!.jsonPrimitive.int, data["bulan"]!!.jsonPrimitive.int, days)
    }

    fun forecastUrl(place: WeatherPlace): String =
        "$FORECAST?latitude=${place.latitude}&longitude=${place.longitude}" +
            "&current=temperature_2m,weather_code,is_day" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=3"

    fun searchUrl(query: String): String =
        "$GEOCODING?name=${java.net.URLEncoder.encode(query, "UTF-8")}&count=6&language=id&countryCode=ID"

    fun places(body: String): List<WeatherPlace> {
        val results = json.parseToJsonElement(body).jsonObject["results"] as? JsonArray ?: return emptyList()
        return results.map {
            val item = it.jsonObject
            WeatherPlace(
                item["name"]!!.jsonPrimitive.content,
                item["admin1"]?.jsonPrimitive?.content,
                item["latitude"]!!.jsonPrimitive.double,
                item["longitude"]!!.jsonPrimitive.double,
            )
        }
    }

    fun report(body: String, now: Long): WeatherReport {
        val root = json.parseToJsonElement(body).jsonObject
        val current = root["current"]!!.jsonObject
        val daily = root["daily"]!!.jsonObject
        fun list(key: String): JsonArray = daily[key]!!.jsonArray
        val time = list("time")
        val codes = list("weather_code")
        val highs = list("temperature_2m_max")
        val lows = list("temperature_2m_min")
        val rain = list("precipitation_probability_max")
        val count = minOf(time.size, codes.size, highs.size, lows.size, rain.size)
        val days = (0 until count).map {
            WeatherDay(
                time[it].jsonPrimitive.content,
                codes[it].jsonPrimitive.int,
                highs[it].jsonPrimitive.double,
                lows[it].jsonPrimitive.double,
                rain[it].jsonPrimitive.intOrNull ?: 0,
            )
        }
        return WeatherReport(
            current["temperature_2m"]!!.jsonPrimitive.double,
            current["weather_code"]!!.jsonPrimitive.int,
            (current["is_day"]?.jsonPrimitive?.intOrNull ?: 1) == 1,
            days,
            now,
        )
    }

    /** WMO weather codes, as Open-Meteo sends them. */
    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Cloudy"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67 -> "Rain"
        71, 73, 75, 77, 85, 86 -> "Snow"
        80, 81, 82 -> "Showers"
        95, 96, 99 -> "Thunderstorm"
        else -> "Unknown"
    }

    /** "1 h 20 min" or "35 min" until the next prayer. */
    fun countdown(fromMs: Long, toMs: Long): String {
        val minutes = ((toMs - fromMs + 59_999) / 60_000).coerceAtLeast(0)
        return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
    }
}
