package id.phonelink.android.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrayerWeatherTest {
    private val jakarta = PrayerLocation("DKI Jakarta", "Kota Jakarta")

    private fun monthJson(year: Int, month: Int, days: Int) = buildString {
        append("""{"code":200,"data":{"provinsi":"DKI Jakarta","kabkota":"Kota Jakarta","bulan":$month,"tahun":$year,"jadwal":[""")
        append((1..days).joinToString(",") { day ->
            val date = "%04d-%02d-%02d".format(year, month, day)
            """{"tanggal":$day,"tanggal_lengkap":"$date","imsak":"03:59","subuh":"04:09","terbit":"05:23","dzuhur":"11:40","ashar":"14:55","maghrib":"17:49","isya":"19:00"}"""
        })
        append("]}}")
    }

    private fun at(text: String, zone: String = "Asia/Jakarta"): Instant =
        ZonedDateTime.of(java.time.LocalDateTime.parse(text), ZoneId.of(zone)).toInstant()

    @Test
    fun parsesAMonthFromEquran() {
        val month = PrayerWeatherApi.month(monthJson(2026, 11, 30), jakarta)
        assertEquals(2026, month.year)
        assertEquals(11, month.month)
        assertEquals(30, month.days.size)
        assertEquals("14:55", month.days[0].ashar)
        assertEquals(listOf("Aceh", "Bali"), PrayerWeatherApi.names("""{"code":200,"data":["Aceh","Bali"]}"""))
    }

    @Test
    fun findsTheNextPrayer() {
        val schedule = PrayerSchedule(jakarta, listOf(PrayerWeatherApi.month(monthJson(2026, 11, 30), jakarta)))
        val afternoon = schedule.upcoming(at("2026-11-10T13:00"))
        assertEquals(Prayer.Ashar, afternoon?.prayer)
        assertEquals("11:40", afternoon?.previous?.toLocalTime().toString())
        // After Isya, the next one is tomorrow's Subuh.
        val night = schedule.upcoming(at("2026-11-10T21:00"))
        assertEquals(Prayer.Subuh, night?.prayer)
        assertEquals(11, night?.time?.dayOfMonth)
        // Before Subuh, the one before is yesterday's Isya.
        assertEquals(9, schedule.upcoming(at("2026-11-10T03:00"))?.previous?.dayOfMonth)
        // The last day of the month needs the next month loaded.
        assertNull(schedule.upcoming(at("2026-11-30T21:00")))
        assertEquals(5, schedule.today(at("2026-11-10T08:00"))?.size)
    }

    @Test
    fun usesThePlacesOwnTimeZone() {
        assertEquals(ZoneId.of("Asia/Makassar"), PrayerLocation("Bali", "Kota Denpasar").zone)
        assertEquals(ZoneId.of("Asia/Jayapura"), PrayerLocation("Papua", "Kota Jayapura").zone)
        assertEquals("Denpasar", PrayerLocation("Bali", "Kota Denpasar").label)
        val bali = PrayerLocation("Bali", "Kota Denpasar")
        val schedule = PrayerSchedule(bali, listOf(PrayerWeatherApi.month(monthJson(2026, 11, 30), bali)))
        // 13:00 in Jakarta is 14:00 in Bali, still before Ashar at 14:55 Bali time.
        assertEquals(Prayer.Ashar, schedule.upcoming(at("2026-11-10T13:00"))?.prayer)
        // 14:30 in Jakarta is 15:30 in Bali, after Ashar.
        assertEquals(Prayer.Maghrib, schedule.upcoming(at("2026-11-10T14:30"))?.prayer)
    }

    @Test
    fun asksForNextMonthNearTheEnd() {
        val zone = ZoneId.of("Asia/Jakarta")
        assertEquals(listOf(2026 to 11), PrayerSchedule.wanted(at("2026-11-10T08:00"), zone))
        assertEquals(listOf(2026 to 12, 2027 to 1), PrayerSchedule.wanted(at("2026-12-28T08:00"), zone))
    }

    @Test
    fun ignoresOtherCitiesAndBrokenTimes() {
        val bandung = PrayerLocation("Jawa Barat", "Kota Bandung")
        val schedule = PrayerSchedule(jakarta, listOf(PrayerWeatherApi.month(monthJson(2026, 11, 30), bandung)))
        assertTrue(schedule.isEmpty)
        val broken = PrayerMonth(jakarta, 2026, 11, listOf(PrayerDay("2026-11-10", "04:09", "xx", "14:55", "17:49", "19:00")))
        assertNull(PrayerSchedule(jakarta, listOf(broken)).upcoming(at("2026-11-10T08:00")))
    }

    @Test
    fun parsesWeather() {
        val body = """{"current":{"temperature_2m":30.6,"weather_code":2,"is_day":1},
            "daily":{"time":["2026-11-10","2026-11-11"],"weather_code":[2,61],"temperature_2m_max":[32.1,30.0],
            "temperature_2m_min":[24.4,23.9],"precipitation_probability_max":[20,null]}}"""
        val report = PrayerWeatherApi.report(body, 5L)
        assertEquals(30.6, report.temperature)
        assertEquals(2, report.days.size)
        assertEquals(0, report.days[1].rainChance)
        assertEquals("Rain", PrayerWeatherApi.describe(report.days[1].code))
        val places = PrayerWeatherApi.places("""{"results":[{"name":"Kota Bandung","admin1":"Jawa Barat","latitude":-6.9,"longitude":107.6}]}""")
        assertEquals("Bandung", places.single().shortName)
        assertEquals(emptyList(), PrayerWeatherApi.places("""{"generationtime_ms":0.5}"""))
        assertNotNull(PrayerWeatherApi.searchUrl("Banda Aceh").takeIf { "Banda+Aceh" in it })
    }

    @Test
    fun writesCountdowns() {
        assertEquals("35 min", PrayerWeatherApi.countdown(0, 35 * 60_000L))
        assertEquals("1 h 20 min", PrayerWeatherApi.countdown(0, 80 * 60_000L))
        assertEquals("1 min", PrayerWeatherApi.countdown(0, 10_000L))
    }
}
