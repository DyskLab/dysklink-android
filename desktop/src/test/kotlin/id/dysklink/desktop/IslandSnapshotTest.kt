package id.dysklink.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import id.dysklink.desktop.ui.IslandContent
import id.dysklink.desktop.ui.IslandUiState
import id.phonelink.android.core.DeviceInfo
import id.phonelink.android.core.HubPhone
import id.phonelink.android.core.NotificationItem
import id.phonelink.android.core.PcToolsState
import id.phonelink.android.core.PomodoroPhase
import id.phonelink.android.core.PomodoroSaved
import id.phonelink.android.core.PomodoroState
import id.phonelink.android.core.PrayerDay
import id.phonelink.android.core.PrayerLocation
import id.phonelink.android.core.PrayerMonth
import id.phonelink.android.core.TodoItem
import id.phonelink.android.core.TodoList
import id.phonelink.android.core.Transfer
import id.phonelink.android.core.TransferStatus
import id.phonelink.android.core.WaterEntry
import id.phonelink.android.core.WaterLog
import id.phonelink.android.core.WeatherDay
import id.phonelink.android.core.WeatherPlace
import id.phonelink.android.core.WeatherReport
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.EncodedImageFormat

/**
 * The island closed and open, page by page, over a light and a dark wallpaper. Only runs when DYSKLINK_SNAPSHOTS
 * names a folder: `DYSKLINK_SNAPSHOTS=/tmp/snaps ./gradlew :desktop:test --tests '*IslandSnapshotTest*'`
 */
class IslandSnapshotTest {
    private val zone = ZoneId.of("Asia/Jakarta")
    private val now = ZonedDateTime.of(2026, 11, 13, 17, 14, 0, 0, zone).toInstant().toEpochMilli()

    private class FakeMusic : MusicBackend {
        override val track = MutableStateFlow<NowPlaying?>(null)
        override val players: StateFlow<List<PlayerInfo>> = MutableStateFlow(listOf(PlayerInfo("org.mpris.MediaPlayer2.spotify", "Spotify")))
        override val art = MutableStateFlow<IslandArt?>(null)
        override val problem: StateFlow<String?> = MutableStateFlow(null)
        override var preferred: String? = null
        override fun start(scope: CoroutineScope) {}
        override fun stop() {}
        override fun command(command: MediaCommand) {}
        override fun seek(positionMs: Long) {}
    }

    private val wallpapers = mapOf(
        "dark" to Brush.linearGradient(listOf(Color(0xFF1B2140), Color(0xFF3A2456), Color(0xFF0E3B4A))),
        "light" to Brush.linearGradient(listOf(Color(0xFFF4EFE6), Color(0xFFD9E7F5), Color(0xFFF7D9D2))),
    )

    private fun png(): ByteArray {
        val image = java.awt.image.BufferedImage(96, 96, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.paint = java.awt.GradientPaint(0f, 0f, java.awt.Color(0xF2, 0x5C, 0x8A), 96f, 96f, java.awt.Color(0x38, 0x2A, 0xA8))
        g.fillRect(0, 0, 96, 96)
        g.color = java.awt.Color(255, 255, 255, 200)
        g.fillOval(30, 30, 36, 36)
        g.dispose()
        return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
    }

    @Test
    fun island() {
        val path = System.getenv("DYSKLINK_SNAPSHOTS") ?: return
        val dir = File(path).apply { mkdirs() }
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        val data = Files.createTempDirectory("dysk-island-snap").toFile()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val music = FakeMusic()
            val tools = MutableStateFlow(PcToolsState())
            val phone = HubPhone("p", "Pixel 8", "Pixel 8", isPaired = true, isConnected = true, info = DeviceInfo(batteryPercent = 64, isCharging = false), features = listOf("notifications"))
            val phones = MutableStateFlow(listOf(phone))
            val notifications = MutableStateFlow<Map<String, List<NotificationItem>>>(emptyMap())
            val transfers = MutableStateFlow<List<Transfer>>(emptyList())
            val clips = MutableStateFlow(ClipHistoryView())
            val service = IslandService(
                scope, Settings(File(data, "s.properties")), data, tools, { false }, phones, notifications, transfers, clips,
                pcBattery = MutableStateFlow(82 to false), musicFactory = { music }, native = object : IslandNative {}, clock = { now },
            )
            service.setEnabled(true)
            val ui = IslandUiState()

            fun shot(name: String, wallpaper: String = "dark", width: Int = 760, height: Int = 280) {
                val scene = ImageComposeScene(width * 3 / 2, height * 3 / 2, Density(1.5f)) {
                    Box(Modifier.fillMaxSize().background(wallpapers.getValue(wallpaper))) {
                        IslandContent(service, ui, clock = { now }, reducedMotion = true, scrolls = false)
                    }
                }
                scene.render(0)
                scene.render(1_000_000_000)
                val image = scene.render(2_000_000_000)
                File(dir, "$name.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                scene.close()
            }

            // Closed: resting, then live.
            shot("01-resting-dark", height = 120)
            shot("02-resting-light", "light", height = 120)
            ui.glance = Offset(0.9f, 0.6f)
            shot("03-resting-glance-right", "light", height = 120)
            ui.glance = Offset.Zero
            val song = NowPlaying(
                title = "Idol", artist = "YOASOBI", album = "THE BOOK 3", isPlaying = true, durationMs = 213_000, positionMs = 61_000,
                positionAt = now, source = "org.mpris.MediaPlayer2.spotify", sourceName = "Spotify", canSeek = true, trackId = "/t/1",
            )
            music.track.value = song
            shot("04-live-music-ring", height = 120)
            tools.value = PcToolsState(pomodoro = PomodoroSaved(state = PomodoroState(PomodoroPhase.Focus, 2, endsAt = now + 17 * 60_000 + 14_000)))
            shot("05-live-pomodoro", height = 120)
            shot("05b-live-pomodoro-light", "light", height = 120)
            music.track.value = song.copy(isPlaying = false)
            shot("06-live-pomodoro-no-music", height = 120)
            music.art.value = IslandArt(song.key, png())
            tools.value = PcToolsState()
            music.track.value = song.copy(positionAt = now)
            shot("07-live-music-art", height = 120)
            service.peek("Pixel 8 · 18% low", 0.18f, GlanceTone.Amber)
            shot("08-peek-phone-low", height = 120)
            service.showCode(CodeNotice("482915", "BCA", "Pixel 8"))
            shot("09-code", height = 140)
            service.hideCode()
            service.onNotification(NotificationItem("k1", "WhatsApp", "Ana", "Are we still on for tonight?", now), "p")
            shot("10-notice", height = 140)
            service.hideNotice()

            // Open, page by page.
            ui.open = true
            ui.page = IslandPage.Music
            service.lyrics.setOn(true)
            service.lyrics.show(
                LyricsState.Timed(
                    listOf(
                        LyricLine(52_000, "無敵の笑顔で荒らすメディア"), LyricLine(56_000, "知りたいその秘密ミステリアス"),
                        LyricLine(60_000, "抜けてるとこさえ彼女のエリア"), LyricLine(64_000, "完璧で嘘つきな君は"),
                        LyricLine(68_000, "天才的なアイドル様"),
                    ),
                ),
            )
            shot("20-open-music-lyrics")
            service.lyrics.setRomanizes(true)
            shot("21-open-music-romaji")
            service.lyrics.setRomanizes(false)
            service.lyrics.setOn(false)
            shot("22-open-music")
            music.track.value = null
            shot("23-open-music-empty")
            music.track.value = song

            ui.page = IslandPage.Pomodoro
            tools.value = PcToolsState(pomodoro = PomodoroSaved(state = PomodoroState(PomodoroPhase.Focus, 2, endsAt = now + 17 * 60_000 + 14_000)))
            shot("24-open-pomodoro")
            tools.value = PcToolsState()

            ui.page = IslandPage.Shelf
            shot("25-open-shelf-empty")
            val files = listOf("Proposal.pdf", "Photo 2026-11-12.jpg", "Budget.xlsx").map { File(data, it).apply { writeText("x") } }
            service.addToShelf(files)
            transfers.value = listOf(Transfer("t", "p", "Proposal.pdf", 1_000, 620, outgoing = true, status = TransferStatus.Active))
            ui.phoneTargeted = true
            shot("26-open-shelf-files-sending")
            ui.phoneTargeted = false
            ui.shelfMode = ShelfMode.Clipboard
            clips.value = ClipHistoryView(
                entries = listOf(
                    ClipEntry(text = "https://dysklink.id/download", date = now - 60_000),
                    ClipEntry(text = "Meeting at 3 with the design team, room 2", date = now - 3_600_000, pinned = true),
                    ClipEntry(text = "npm run build", date = now - 86_400_000),
                    ClipEntry(files = listOf("/home/me/Report.docx"), date = now - 90_000_000),
                ),
                settings = ClipSettings(enabled = true),
            )
            shot("27-open-clipboard")

            val jakarta = PrayerLocation("DKI Jakarta", "Kota Jakarta")
            val days = (1..30).map { d -> PrayerDay("2026-11-%02d".format(d), "04:09", "11:52", "15:12", "17:48", "19:01") }
            val report = WeatherReport(
                31.4, 2, true,
                listOf(WeatherDay("2026-11-13", 2, 33.0, 25.0, 20), WeatherDay("2026-11-14", 61, 31.0, 24.6, 70), WeatherDay("2026-11-15", 95, 30.2, 24.1, 85)),
                now,
            )
            service.places.show(
                PlacesView(
                    location = jakarta, months = listOf(PrayerMonth(jakarta, 2026, 11, days), PrayerMonth(jakarta, 2026, 12, days.map { it.copy(date = it.date.replace("-11-", "-12-")) })),
                    weatherPlace = WeatherPlace("Kota Jakarta", "DKI Jakarta", -6.2, 106.8), report = report,
                ),
            )
            ui.page = IslandPage.Prayer
            shot("28-open-prayer")
            ui.page = IslandPage.Weather
            shot("29-open-weather")

            ui.page = IslandPage.Todo
            tools.value = PcToolsState(
                todos = TodoList(
                    listOf(
                        TodoItem("1", "Send the invoice to Rina", now - 7_200_000),
                        TodoItem("2", "Review the island pull request", now - 3_600_000, doneAt = now - 600_000),
                        TodoItem("3", "Buy coffee beans", now - 1_800_000),
                    ),
                ),
            )
            shot("30-open-todo")
            ui.editingTodos = true
            shot("31-open-todo-edit")
            ui.editingTodos = false

            ui.page = IslandPage.Water
            val start = ZonedDateTime.of(2026, 11, 13, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
            tools.value = PcToolsState(water = WaterLog(entries = listOf(10, 11, 13, 13, 15, 16).map { h -> WaterEntry(start + h * 3_600_000L + 600_000, 250) }))
            shot("32-open-water")
            ui.waterSettings = true
            shot("33-open-water-settings")
            ui.waterSettings = false

            ui.page = IslandPage.Notifications
            notifications.value = mapOf(
                "p" to listOf(
                    NotificationItem("k1", "WhatsApp", "Ana", "Are we still on for tonight?", now - 120_000),
                    NotificationItem("k2", "Gmail", "Your order has shipped", "Arriving on Monday, 16 November", now - 3_600_000),
                ),
            )
            shot("34-open-notifications")
            shot("35-open-notifications-light", "light")
            assertTrue(File(dir, "01-resting-dark.png").length() > 0)
        } finally {
            scope.cancel()
            data.deleteRecursively()
        }
    }
}
