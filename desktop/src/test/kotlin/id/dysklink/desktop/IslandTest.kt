package id.dysklink.desktop

import id.phonelink.android.core.NotificationItem
import id.phonelink.android.core.PomodoroPhase
import id.phonelink.android.core.PomodoroSettings
import id.phonelink.android.core.PomodoroState
import id.phonelink.android.core.Prayer
import id.phonelink.android.core.PrayerDay
import id.phonelink.android.core.PrayerLocation
import id.phonelink.android.core.PrayerMonth
import id.phonelink.android.core.PrayerSchedule
import id.phonelink.android.core.PrayerWeatherApi
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OneTimeCodeTest {
    @Test
    fun findsCodesAsTheMacDoes() {
        assertEquals("482915", OneTimeCode.find("Kode OTP Anda 482915. Jangan berikan kode ini kepada siapa pun."))
        assertEquals("123456", OneTimeCode.find("<#> 123456 adalah kode verifikasi WhatsApp Anda"))
        assertEquals("731942", OneTimeCode.find("G-731942 is your Google verification code."))
        assertEquals("482915", OneTimeCode.find("Your code is 482-915"))
        assertEquals("4821", OneTimeCode.find("Gunakan PIN 4821 untuk masuk. Berlaku s/d 10/11/2026 12:30"))
        assertEquals("99812734", OneTimeCode.find("JANGAN BERIKAN kode ini. Transaksi Rp 250000 kode: 99812734"))
    }

    @Test
    fun leavesMoneyDatesPhonesAndPlainNumbersAlone() {
        assertNull(OneTimeCode.find("Pembayaran Rp 150000 berhasil pada 10/11/2026"))
        assertNull(OneTimeCode.find("Hubungi +628123456789 untuk info"))
        assertNull(OneTimeCode.find("Rapat jam 14:30 di ruang 2026"))
        assertNull(OneTimeCode.find("See you at 5"))
    }

    @Test
    fun spacesSixDigitsAndRemembersRecentCodes() {
        assertEquals("482 915", OneTimeCode.spaced("482915"))
        assertEquals("4821", OneTimeCode.spaced("4821"))
        val recent = RecentCodes(120_000)
        assertTrue(recent.isNew("482915", 0))
        // The same code by SMS and by notification shows once.
        assertFalse(recent.isNew("482915", 5_000))
        assertTrue(recent.isNew("482915", 200_000))
    }
}

class LyricsTest {
    @Test
    fun parsesLrcWithSeveralTimesPerLine() {
        val lines = LyricsParser.parse("[ar:Someone]\n[00:12.50]Hello\n[00:05.00][01:00.0]Again\n[00:20]")
        assertEquals(listOf(5_000L, 12_500L, 20_000L, 60_000L), lines.map { it.timeMs })
        assertEquals(listOf("Again", "Hello", "", "Again"), lines.map { it.text })
    }

    @Test
    fun followsTheSongALittleAheadAndDoesNotHopBack() {
        val lines = LyricsParser.parse("[00:01.00]a\n[00:05.00]b\n[00:09.00]c")
        assertNull(LyricsParser.index(lines, 500))
        assertEquals(0, LyricsParser.index(lines, 760))
        // A quarter second early, so the line lights up as it's sung.
        assertEquals(1, LyricsParser.index(lines, 4_800))
        val tracker = LyricLineTracker()
        assertEquals(1, tracker.line(lines, 5_200))
        // A small correction backwards keeps the line.
        assertEquals(1, tracker.line(lines, 4_500))
        // No position: the line stays.
        assertEquals(1, tracker.line(lines, null))
        // A real seek back moves it.
        assertEquals(0, tracker.line(lines, 1_500))
    }

    @Test
    fun picksArtistAndTitleFromPlayersAndVideos() {
        val base = NowPlaying(title = "Song", artist = "Artist", isPlaying = true)
        assertEquals("Artist" to "Song", LyricsParser.song(base))
        val yt = base.copy(title = "Yoasobi - Idol (Official Music Video)", artist = "Ayase / YOASOBI", browser = true, url = "https://www.youtube.com/watch?v=x")
        assertEquals("Yoasobi" to "Idol", LyricsParser.song(yt))
        val topic = base.copy(title = "Idol", artist = "YOASOBI - Topic", browser = true)
        assertEquals("YOASOBI" to "Idol", LyricsParser.song(topic))
        val ytm = base.copy(title = "Idol", artist = "YOASOBI", browser = true, url = "https://music.youtube.com/watch?v=x")
        assertEquals("YOASOBI" to "Idol", LyricsParser.song(ytm))
    }

    @Test
    fun romanizesKanaAndHangulAndLeavesKanji() {
        assertEquals("sakura", Romanizer.romanize("さくら"))
        assertEquals("kitto", Romanizer.romanize("きっと"))
        assertEquals("toukyou", Romanizer.romanize("とうきょう"))
        assertEquals("kyou", Romanizer.romanize("キョウ"))
        assertEquals("saranghae", Romanizer.romanize("사랑해"))
        assertTrue(Romanizer.romanize("君の名は").startsWith("君"))
        assertFalse(Romanizer.canRomanize("Hello 世界"))
        assertNull(LyricsService.romanized(LyricsState.Plain(listOf("Just English"))))
        val roman = assertIs<LyricsState.Timed>(LyricsService.romanized(LyricsState.Timed(listOf(LyricLine(0, "さくら")))))
        assertEquals("sakura", roman.lines.single().text)
    }

    @Test
    fun looksUpExactlyThenSearchesAndRanksByLength() {
        val asked = mutableListOf<String>()
        val state = LrcLib.lookup("A", "B", 200_000) { url ->
            asked += url
            when {
                "/api/get" in url -> 404 to ""
                else -> 200 to """[{"plainLyrics":"far","duration":300},{"syncedLyrics":"[00:01.00]near","duration":203}]"""
            }
        }
        assertEquals("near", assertIs<LyricsState.Timed>(state).lines.single().text)
        assertTrue(asked.first().contains("duration=200"))
        assertEquals(LyricsState.Failed, LrcLib.lookup("A", "B", null) { null })
        assertEquals(LyricsState.NotFound, LrcLib.lookup("", "B", null) { 200 to "[]" })
    }

    @Test
    fun keepsFoundLyricsButNotFailures(): Unit = kotlinx.coroutines.runBlocking {
        var calls = 0
        var online = false
        val service = LyricsService(null) { url -> calls++; if (!online) null else if ("/api/get" in url) 200 to """{"plainLyrics":"la"}""" else 404 to "" }
        service.setOn(true)
        val a = NowPlaying(title = "B", artist = "A", isPlaying = true)
        service.load(a)
        assertEquals(LyricsState.Failed, service.state.value)
        online = true
        service.load(a.copy(title = "C")); service.load(a)
        assertIs<LyricsState.Plain>(service.state.value)
        val before = calls
        online = false
        service.load(a.copy(title = "C")); service.load(a)
        // Both come from the cache, without the internet.
        assertEquals(before, calls)
        assertIs<LyricsState.Plain>(service.state.value)
    }
}

class WindowsMediaTest {
    @Test
    fun readsTheHelpersLines() {
        val now = 1_000_000L
        val m = assertIs<SmtcMessage.Track>(SmtcProtocol.parse("""{"t":"track","app":"Spotify.exe","title":"Idol","artist":"YOASOBI","album":"","status":"Playing","pos":61000,"end":213000,"at":999000,"seek":true,"artKey":"Spotify.exe|Idol|YOASOBI"}"""))
        val t = assertNotNull(SmtcProtocol.track(m, now))
        assertEquals("Spotify", t.sourceName)
        assertTrue(t.isPlaying && t.canSeek)
        assertEquals(61_000L + 1_000, t.elapsedMs(now))
        assertNull(SmtcProtocol.track(m.copy(status = "Closed"), now))
        assertEquals(SmtcMessage.None, SmtcProtocol.parse("""{"t":"none"}"""))
        val art = assertIs<SmtcMessage.Art>(SmtcProtocol.parse("""{"t":"art","key":"k","data":"${Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))}"}"""))
        assertEquals(3, art.bytes.size)
        val players = assertIs<SmtcMessage.Players>(SmtcProtocol.parse("""{"t":"players","list":[{"id":"308046B0AF4A39CB"},{"id":"Microsoft.ZuneMusic_8wekyb3d8bbwe!Microsoft.ZuneMusic"}]}"""))
        assertEquals(listOf("Firefox", "Media Player"), players.list.map { it.name })
        assertTrue(SmtcProtocol.isBrowser("308046B0AF4A39CB"))
        assertNull(SmtcProtocol.parse("not json"))
        assertNull(SmtcProtocol.parse("""{"t":"later"}"""))
    }

    @Test
    fun sendsCommandsAndRunsInWindowsPowerShell() {
        assertEquals("play_pause", SmtcProtocol.commandLine(MediaCommand.PlayPause))
        assertEquals("next", SmtcProtocol.commandLine(MediaCommand.Next))
        val script = String(Base64.getDecoder().decode(SmtcProtocol.encoded()), Charsets.UTF_16LE)
        assertTrue("GlobalSystemMediaTransportControlsSessionManager" in script)
        assertTrue("play_pause" in script && "seek" in script && "prefer" in script)
        val source = WindowsMediaSource { 5_000 }
        source.handle("""{"t":"track","app":"chrome.exe","title":"A","artist":"B","status":"Paused"}""")
        assertEquals("Chrome", source.track.value?.sourceName)
        source.handle("""{"t":"problem","text":"No media sessions"}""")
        assertEquals("No media sessions", source.problem.value)
        source.handle("""{"t":"none"}""")
        assertNull(source.track.value)
    }
}

class IslandGeometryTest {
    @Test
    fun centresAtTheTopOfAnyScreen() {
        val left = IslandMonitor("a", Rect(-1920, -200, 1920, 1080), 1.0, primary = false)
        assertEquals(Rect(-1920 + (1920 - 220) / 2, -200, 220, 40), IslandGeometry.frame(left, IslandShapeSpec.Resting))
        // Under GNOME's top bar, but never far down the screen.
        val gnome = IslandMonitor("b", Rect(0, 0, 1280, 800), 2.0, primary = true, topInset = 32)
        assertEquals(32, IslandGeometry.frame(gnome, IslandShapeSpec.Open).y)
        assertEquals(200, IslandGeometry.frame(Rect(0, 0, 1280, 800), IslandShapeSpec.Open, 5_000).y)
        assertEquals(gnome, IslandGeometry.pick(listOf(left, gnome), null))
        assertEquals(left, IslandGeometry.pick(listOf(left, gnome), "a"))
        assertEquals(gnome, IslandGeometry.pick(listOf(left, gnome), "gone"))
    }

    @Test
    fun takesClicksOnlyInsideTheShapeInScreenPixels() {
        val shape = IslandShapeSpec.Live
        for (scale in listOf(1.0, 1.5, 2.0)) {
            val rects = IslandGeometry.inputRects(shape, shape.frameWidth, scale)
            val width = (shape.frameWidth * scale).toInt()
            val height = Math.round(shape.height * scale).toInt()
            assertTrue(rects.all { it.x >= 0 && it.right <= width + 1 && it.bottom <= height }, "scale $scale")
            fun inside(x: Int, y: Int) = rects.any { it.contains(x, y) }
            assertTrue(inside(width / 2, height - 2))
            // The rounded bottom corners are left out, the top edge reaches into the shoulders.
            assertFalse(inside((10 * scale).toInt() + 1, height - 1))
            assertTrue(inside(2, 0))
            assertFalse(inside(2, height / 2))
        }
        assertEquals(Rect(-1920 * 2, 0, 440, 80), IslandGeometry.physical(Rect(-1920, 0, 220, 40), 2.0))
    }

    @Test
    fun hidesUnderFullScreenAppsOnItsOwnScreen() {
        val screen = Rect(0, 0, 1920, 1080)
        val other = Rect(1920, 0, 1920, 1080)
        assertTrue(FullscreenRule.windows(FullscreenRule.QUNS_RUNNING_D3D_FULL_SCREEN, null, null, null, null))
        assertTrue(FullscreenRule.windows(FullscreenRule.QUNS_PRESENTATION_MODE, null, null, null, screen))
        assertTrue(FullscreenRule.windows(FullscreenRule.QUNS_BUSY, screen, "Chrome_WidgetWin_1", screen, screen))
        // On the other screen, maximized under a taskbar, or the desktop itself: it stays.
        assertFalse(FullscreenRule.windows(FullscreenRule.QUNS_BUSY, other, "Chrome_WidgetWin_1", other, screen))
        assertFalse(FullscreenRule.windows(FullscreenRule.QUNS_ACCEPTS_NOTIFICATIONS, Rect(0, 0, 1920, 1040), "Notepad", screen, screen))
        assertFalse(FullscreenRule.windows(FullscreenRule.QUNS_ACCEPTS_NOTIFICATIONS, screen, "Progman", screen, screen))
        assertTrue(FullscreenRule.x11(listOf("_NET_WM_STATE_FULLSCREEN"), Rect(0, 0, 1920, 1080), screen))
        assertFalse(FullscreenRule.x11(listOf("_NET_WM_STATE_FULLSCREEN"), other, screen))
        assertFalse(FullscreenRule.x11(listOf("_NET_WM_STATE_MAXIMIZED_VERT"), screen, screen))
        assertFalse(FullscreenRule.x11(null, null, screen))
    }

    @Test
    fun eyesLookTowardANearPointerOnly() {
        assertEquals(androidx.compose.ui.geometry.Offset.Zero, IslandEyes.glance(1_000.0, 0.0))
        val near = IslandEyes.glance(-50.0, 0.0)
        assertTrue(near.x < -0.5f && near.y == 0f)
        assertTrue(IslandEyes.glance(200.0, 0.0).x < near.x * -1)
    }
}

class IslandSettingsTest {
    private val dir: File = Files.createTempDirectory("dysk-island").toFile()

    @AfterTest
    fun clean() { dir.deleteRecursively() }

    @Test
    fun keepsPagesOrderAndSwitches() {
        val file = File(dir, "settings.properties")
        val order = IslandPages.moved(IslandPage.entries, IslandPage.Weather, -1)
        assertEquals(IslandPage.Weather, order[IslandPage.entries.indexOf(IslandPage.Prayer)])
        IslandSettings(enabled = true, monitor = "screen2", order = order, hidden = setOf(IslandSection.Todo, IslandSection.Clipboard), opensOnClick = true, player = "org.mpris.MediaPlayer2.spotify", batteryLimit = 15, asWindow = true)
            .save(Settings(file))
        val back = IslandSettings.load(Settings(file))
        assertEquals(true, back.enabled)
        assertEquals("screen2", back.monitor)
        assertEquals(order, back.order)
        assertEquals(setOf(IslandSection.Todo, IslandSection.Clipboard), back.hidden)
        assertTrue(back.opensOnClick && back.asWindow)
        assertEquals("org.mpris.MediaPlayer2.spotify", back.player)
        assertEquals(15, back.batteryLimit)
        assertFalse(IslandSettings.load(Settings(File(dir, "new.properties"))).enabled)
    }

    @Test
    fun hiddenAndUnsetPagesDropOutAndAtLeastOneStays() {
        val noPlaces: (IslandSection) -> Boolean = { it != IslandSection.Prayer && it != IslandSection.Weather }
        val pages = IslandPages(IslandSettings(hidden = setOf(IslandSection.Files)), noPlaces)
        assertFalse(IslandPage.Prayer in pages.pages)
        // Shelf stays for the clipboard, without the switch.
        assertTrue(IslandPage.Shelf in pages.pages)
        assertFalse(pages.hasShelfSwitch)
        val one = IslandSettings(hidden = IslandSection.entries.toSet() - IslandSection.Music)
        assertFalse(IslandPages(one, noPlaces).canHide(IslandSection.Music))
        assertEquals(IslandPage.Music, IslandPages(one, noPlaces).settle(IslandPage.Todo, ShelfMode.Files).first)
    }
}

class IslandGlanceTest {
    private val zone = ZoneId.of("Asia/Jakarta")
    private val now = ZonedDateTime.of(2026, 11, 13, 17, 14, 0, 0, zone).toInstant().toEpochMilli()
    private val settings = PomodoroSettings()

    @Test
    fun showsCodesThenNoticesThenPomodoroThenMusicThenRests() {
        val alert = PhoneAlert("p", "Pixel", NotificationItem(key = "k", app = "WhatsApp", title = "Ana", text = "Hi", time = now))
        val music = NowPlaying(title = "Idol", artist = "YOASOBI", isPlaying = true, durationMs = 200_000, positionMs = 50_000, positionAt = now)
        val running = PomodoroState(PomodoroPhase.Focus, 1, endsAt = now + 17 * 60_000 + 14_000)
        fun face(code: CodeNotice? = null, notice: PhoneAlert? = null, pomodoro: PomodoroState = PomodoroState(), playing: NowPlaying? = null) =
            IslandGlance.face(now, zone, code, notice, null, pomodoro, settings, playing, hasArt = false)
        assertIs<IslandFace.Code>(face(CodeNotice("1", "Bank", "Pixel"), alert, running, music))
        assertIs<IslandFace.Notice>(face(null, alert, running, music))
        val pomodoro = assertIs<IslandFace.Live>(face(pomodoro = running, playing = music))
        assertEquals("17:14", pomodoro.center)
        assertTrue(pomodoro.playing)
        val ring = assertIs<GlanceLeft.Ring>(pomodoro.left)
        assertEquals(GlanceTone.Green, ring.tone)
        val song = assertIs<IslandFace.Live>(face(playing = music))
        assertEquals("17:14", song.center)
        assertEquals(0.25f, assertIs<GlanceLeft.Ring>(song.left).fraction, 0.01f)
        assertEquals(IslandFace.Resting, face(playing = music.copy(isPlaying = false)))
        assertEquals(IslandFace.Resting, face())
    }

    @Test
    fun warnsOnceAboutThePhonesBattery() {
        val alerts = PhoneBatteryAlerts()
        assertEquals(PhoneBatteryAlerts.Alert.Low(20), alerts.update("p", 20, false, 20))
        assertNull(alerts.update("p", 18, false, 20))
        assertNull(alerts.update("p", 50, null, 20))
        assertNull(alerts.update("p", 60, true, 20))
        assertEquals(PhoneBatteryAlerts.Alert.Full, alerts.update("p", 100, true, 20))
        assertNull(alerts.update("p", 100, true, 20))
        assertEquals(PhoneBatteryAlerts.Alert.Low(19), alerts.update("p", 19, false, 20))
    }
}

class IslandPlacesTest {
    private val jakarta = PrayerLocation("DKI Jakarta", "Kota Jakarta")

    /** The day the Mac's snapshot shows: Subuh 04:09, Dzuhur 11:52, Ashar 15:12, Maghrib 17:48, Isya 19:01. */
    private fun schedule() = PrayerSchedule(
        jakarta,
        listOf(PrayerMonth(jakarta, 2026, 11, listOf(PrayerDay("2026-11-13", "04:09", "11:52", "15:12", "17:48", "19:01")))),
    )

    private fun at(h: Int, m: Int, s: Int = 0): Instant = ZonedDateTime.of(2026, 11, 13, h, m, s, 0, ZoneId.of("Asia/Jakarta")).toInstant()

    @Test
    fun nextPrayerMatchesTheMac() {
        val next = assertNotNull(schedule().upcoming(at(8, 41)))
        assertEquals(Prayer.entries.first { it.label == "Dzuhur" }, next.prayer)
        assertEquals("3 h 11 min", IslandText.duration(next.time.toInstant().toEpochMilli() - at(8, 41).toEpochMilli()))
        assertEquals(listOf("04:09", "11:52", "15:12", "17:48", "19:01"), schedule().today(at(8, 41))!!.map { "%02d:%02d".format(it.time.hour, it.time.minute) })
    }

    @Test
    fun notifiesOnlyJustAtTheTimeOrTenMinutesBefore() {
        val s = schedule()
        assertEquals("Dzuhur", PrayerRules.due(s, at(11, 52, 30), early = false)?.prayer?.label)
        assertNull(PrayerRules.due(s, at(11, 55), early = false))
        assertEquals("Dzuhur", PrayerRules.due(s, at(11, 42, 10), early = true)?.prayer?.label)
        assertNull(PrayerRules.due(s, at(11, 30), early = true))
        assertEquals(0.5f, PrayerRules.progress(at(10, 0), at(12, 0), at(11, 0)), 0.001f)
        assertEquals(120_000L, PrayerRules.retryDelayMs(1))
        assertEquals(3_600_000L, PrayerRules.retryDelayMs(20))
    }

    @Test
    fun readsTheWeatherAndSearch() {
        val body = """{"current":{"temperature_2m":31.4,"weather_code":2,"is_day":1},"daily":{"time":["2026-11-13","2026-11-14","2026-11-15"],
            "weather_code":[2,61,95],"temperature_2m_max":[33.0,31.2,30.0],"temperature_2m_min":[25.1,24.8,24.0],"precipitation_probability_max":[20,70,null]}}"""
        val report = PrayerWeatherApi.report(body, 5)
        assertEquals(3, report.days.size)
        assertEquals(0, report.days[2].rainChance)
        assertEquals("31°", PlacesView().degrees(report.temperature))
        assertEquals("89°", PlacesView(fahrenheit = true).degrees(report.temperature))
        val found = PrayerWeatherApi.places("""{"results":[{"name":"Kota Bandung","admin1":"Jawa Barat","latitude":-6.9,"longitude":107.6}]}""")
        assertEquals("Bandung", found.single().shortName)
    }

    @Test
    fun keepsTheChosenPlacesAndLoadsWhatsMissing() {
        val dir = Files.createTempDirectory("dysk-places").toFile()
        try {
            val settings = Settings(File(dir, "s.properties"))
            val posted = mutableListOf<String?>()
            val month = """{"data":{"tahun":2026,"bulan":11,"jadwal":[{"tanggal_lengkap":"2026-11-13","subuh":"04:09","dzuhur":"11:52","ashar":"15:12","maghrib":"17:48","isya":"19:01"}]}}"""
            kotlinx.coroutines.runBlocking {
                val places = IslandPlaces(this, settings, dir, http = { _, body -> posted += body; month }, clock = { at(8, 41).toEpochMilli() })
                places.setLocation(jakarta)
                places.refreshPrayer()
            }
            assertTrue(posted.isNotEmpty() && posted.all { it!!.contains("Kota Jakarta") })
            val again = IslandPlaces(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), Settings(File(dir, "s.properties")), dir, http = { _, _ -> null })
            assertEquals(jakarta, again.view.value.location)
            // Offline: the months kept on this PC still give the times.
            assertEquals("Dzuhur", again.view.value.schedule?.upcoming(at(8, 41))?.prayer?.label)
        } finally { dir.deleteRecursively() }
    }
}
