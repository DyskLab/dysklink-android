package id.dysklink.desktop

import id.phonelink.android.core.NotificationItem
import id.phonelink.android.core.PomodoroPhase
import id.phonelink.android.core.PomodoroSettings
import id.phonelink.android.core.PomodoroState
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// The island: the Mac notch on a Windows or Linux PC, as a dark shape hanging from the top centre of a screen. This
// file holds what it shows and decides, without any window or drawing, so it can be tested anywhere.

/** What plays now, from MPRIS on Linux or the media session (SMTC) on Windows. Times are epoch milliseconds. */
data class NowPlaying(
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
    val album: String = "",
    val durationMs: Long? = null,
    /** Where the song was at [positionAt]; it moves on by itself while playing. */
    val positionMs: Long? = null,
    val positionAt: Long = 0,
    /** The player: a D-Bus name such as org.mpris.MediaPlayer2.spotify, or a Windows app ID. */
    val source: String = "",
    /** The player as people know it, such as "Spotify" or "Firefox". */
    val sourceName: String = "",
    /** A web browser, where the "artist" is a YouTube channel. */
    val browser: Boolean = false,
    /** The page, when the player says (some browsers do). */
    val url: String? = null,
    /** MPRIS's mpris:artUrl: a file:// or https:// picture. */
    val artUrl: String? = null,
    /** MPRIS's mpris:trackid, which SetPosition needs. */
    val trackId: String? = null,
    val canSeek: Boolean = false,
    val canControl: Boolean = true,
) {
    /** Identifies the song or video, for matching album art and lyrics to it. */
    val key: String get() = "$source|$title"

    fun elapsedMs(now: Long): Long? {
        val position = positionMs ?: return null
        val moved = if (isPlaying) position + (now - positionAt).coerceAtLeast(0) else position
        return durationMs?.let { moved.coerceAtMost(it) } ?: moved
    }

    /** Flips playing and paused at once, keeping the song where it is, until the player confirms. */
    fun toggled(now: Long): NowPlaying = copy(positionMs = elapsedMs(now), positionAt = now, isPlaying = !isPlaying)

    /** The line under the title: the artist, and for a browser where it plays. */
    val subtitle: String get() = when {
        browser && artist.isNotEmpty() -> "$artist · $sourceName"
        browser -> sourceName
        else -> artist
    }
}

/** A player the music source can follow, for the setting. */
data class PlayerInfo(val id: String, val name: String)

enum class MediaCommand { PlayPause, Next, Previous }

/** Album art or a video thumbnail for the song with [key]. */
class IslandArt(val key: String, val bytes: ByteArray)

/** A page of the open island, in the order the user chose. */
enum class IslandPage(val title: String) {
    Music(""), Pomodoro("Pomodoro"), Shelf("Shelf"), Prayer("Prayer"), Weather("Weather"), Todo("Todo"), Water("Water"),
    Notifications("Notifications"),
}

/** What can be hidden, as on the Mac. Files and Clipboard share the Shelf page. */
enum class IslandSection(val label: String) {
    Music("Music"), Pomodoro("Pomodoro"), Files("Shelf files"), Clipboard("Clipboard"), Prayer("Prayer times"),
    Weather("Weather"), Todo("Todo list"), Water("Water reminder"), Alerts("Notifications");

    val page: IslandPage get() = when (this) {
        Music -> IslandPage.Music
        Pomodoro -> IslandPage.Pomodoro
        Files, Clipboard -> IslandPage.Shelf
        Prayer -> IslandPage.Prayer
        Weather -> IslandPage.Weather
        Todo -> IslandPage.Todo
        Water -> IslandPage.Water
        Alerts -> IslandPage.Notifications
    }
}

enum class ShelfMode { Files, Clipboard }

/** Everything the island's settings hold. Saved in settings.properties under "island.". */
data class IslandSettings(
    /** "Show the island at the top of the screen". Off until turned on. */
    val enabled: Boolean = false,
    /** The screen it sits on, by AWT's device ID; null for the primary screen. */
    val monitor: String? = null,
    val order: List<IslandPage> = IslandPage.entries,
    val hidden: Set<IslandSection> = emptySet(),
    /** Opens on a click instead of on hover. */
    val opensOnClick: Boolean = false,
    /** The player to follow; null follows whichever plays. */
    val player: String? = null,
    /** One-time codes from the phone's SMS and notifications, with Copy. */
    val codes: Boolean = true,
    /** The phone's battery: low (at [batteryLimit] or under) and fully charged. */
    val phoneBattery: Boolean = true,
    val batteryLimit: Int = 20,
    /** Shown as a small ordinary window, for desktops that don't let it sit at the top (some Wayland ones). */
    val asWindow: Boolean = false,
) {
    fun save(settings: Settings) {
        settings.set("island.on", enabled)
        settings.set("island.monitor", monitor.orEmpty())
        settings.set("island.order", order.joinToString(",") { it.name })
        settings.set("island.hidden", hidden.joinToString(",") { it.name })
        settings.set("island.click", opensOnClick)
        settings.set("island.player", player.orEmpty())
        settings.set("island.codes", codes)
        settings.set("island.battery", phoneBattery)
        settings.set("island.batteryLimit", batteryLimit.toString())
        settings.set("island.window", asWindow)
    }

    companion object {
        val BATTERY_LIMITS = listOf(10, 15, 20, 30)

        fun load(settings: Settings): IslandSettings {
            val stored = settings.string("island.order").orEmpty().split(',').mapNotNull { name -> IslandPage.entries.firstOrNull { it.name == name } }.distinct()
            // Pages added in a later version go at the end.
            val order = stored + IslandPage.entries.filter { it !in stored }
            val hidden = settings.string("island.hidden").orEmpty().split(',').mapNotNull { name -> IslandSection.entries.firstOrNull { it.name == name } }.toSet()
            return IslandSettings(
                enabled = settings.bool("island.on", false),
                monitor = settings.string("island.monitor")?.takeIf { it.isNotBlank() },
                order = order,
                // Everything hidden can't be: an edited file falls back to showing all.
                hidden = if (hidden.size == IslandSection.entries.size) emptySet() else hidden,
                opensOnClick = settings.bool("island.click", false),
                player = settings.string("island.player")?.takeIf { it.isNotBlank() },
                codes = settings.bool("island.codes", true),
                phoneBattery = settings.bool("island.battery", true),
                batteryLimit = settings.int("island.batteryLimit")?.takeIf { it in BATTERY_LIMITS } ?: 20,
                asWindow = settings.bool("island.window", false),
            )
        }
    }
}

/** Which pages show, as the Mac's NotchModel decides: hidden by the user, or with nothing to show yet. */
class IslandPages(val settings: IslandSettings, private val hasData: (IslandSection) -> Boolean) {
    fun isShown(section: IslandSection) = section !in settings.hidden && hasData(section)

    /** The last shown section can't be hidden, so the island always has a page. */
    fun canHide(section: IslandSection) = isShown(section) && IslandSection.entries.count(::isShown) > 1

    fun withShown(section: IslandSection, shown: Boolean): IslandSettings = when {
        shown -> settings.copy(hidden = settings.hidden - section)
        canHide(section) -> settings.copy(hidden = settings.hidden + section)
        else -> settings
    }

    val pages: List<IslandPage> get() = settings.order.filter { page -> IslandSection.entries.any { it.page == page && isShown(it) } }

    /** Both shelf modes show a switch. With one hidden, the shelf shows the other. */
    val hasShelfSwitch: Boolean get() = isShown(IslandSection.Files) && isShown(IslandSection.Clipboard)

    fun settle(page: IslandPage, mode: ShelfMode): Pair<IslandPage, ShelfMode> {
        val p = if (page in pages) page else pages.firstOrNull() ?: page
        val m = when {
            mode == ShelfMode.Files && !isShown(IslandSection.Files) -> ShelfMode.Clipboard
            mode == ShelfMode.Clipboard && !isShown(IslandSection.Clipboard) -> ShelfMode.Files
            else -> mode
        }
        return p to m
    }

    companion object {
        /** Moves [page] one place up (-1) or down (+1) in the order. */
        fun moved(order: List<IslandPage>, page: IslandPage, step: Int): List<IslandPage> {
            val from = order.indexOf(page)
            val to = from + step
            if (from < 0 || to !in order.indices) return order
            return order.toMutableList().apply { removeAt(from); add(to, page) }
        }
    }
}

/** A phone notification with the phone it came from. */
data class PhoneAlert(val peerId: String, val phone: String, val item: NotificationItem) {
    val id: String get() = "$peerId:${item.key}"
}

/** A short line shown for a few seconds, such as "Pixel 8 · 18% low" or "Time for a break". */
data class IslandPeek(val text: String, val ring: Float? = null, val tone: GlanceTone = GlanceTone.Green, val at: Long = 0)

enum class GlanceTone { Green, Amber, Blue, Violet, Mint }

/** The left slot of the closed island with something live. */
sealed interface GlanceLeft {
    data class Ring(val fraction: Float, val tone: GlanceTone) : GlanceLeft
    data class Art(val key: String) : GlanceLeft
}

/** What the closed island shows. */
sealed interface IslandFace {
    /** Nothing live: the two eyes. */
    data object Resting : IslandFace
    /** One row: a ring or album art, a big text in the middle, and the music bars. */
    data class Live(val left: GlanceLeft, val center: String, val small: Boolean, val playing: Boolean) : IslandFace
    data class Code(val notice: CodeNotice) : IslandFace
    data class Notice(val alert: PhoneAlert) : IslandFace
}

object IslandGlance {
    private val clock = DateTimeFormatter.ofPattern("H:mm")

    /**
     * What the closed island shows, in the Mac's order: a code, then a new notification, then a short message, then
     * the running Pomodoro, then music that plays. With none of these it rests.
     */
    fun face(
        now: Long,
        zone: ZoneId,
        code: CodeNotice?,
        notice: PhoneAlert?,
        peek: IslandPeek?,
        pomodoro: PomodoroState,
        pomodoroSettings: PomodoroSettings,
        playing: NowPlaying?,
        hasArt: Boolean,
    ): IslandFace {
        code?.let { return IslandFace.Code(it) }
        notice?.let { return IslandFace.Notice(it) }
        val music = playing?.isPlaying == true
        peek?.let { return IslandFace.Live(GlanceLeft.Ring(it.ring ?: 1f, it.tone), it.text, small = true, playing = music) }
        if (pomodoro.isRunning) {
            val tone = if (pomodoro.phase == PomodoroPhase.Focus) GlanceTone.Green else GlanceTone.Mint
            return IslandFace.Live(
                GlanceLeft.Ring(pomodoro.progress(now, pomodoroSettings), tone),
                IslandText.clock(pomodoro.remaining(now, pomodoroSettings)),
                small = false, playing = music,
            )
        }
        if (music && playing != null) {
            val left = if (hasArt) GlanceLeft.Art(playing.key) else GlanceLeft.Ring(playing.durationMs?.let { d -> (playing.elapsedMs(now) ?: 0).toFloat() / d.coerceAtLeast(1) } ?: 1f, GlanceTone.Green)
            return IslandFace.Live(left, clock.format(Instant.ofEpochMilli(now).atZone(zone)), small = false, playing = true)
        }
        return IslandFace.Resting
    }
}

/** Decides when the phone's battery deserves a warning, once, as the Mac's PhoneBatteryAlerts. */
class PhoneBatteryAlerts {
    sealed interface Alert {
        data class Low(val percent: Int) : Alert
        data object Full : Alert
    }

    private val lowSent = HashSet<String>()
    private val fullSent = HashSet<String>()

    /** Unknown battery or charging state gives no warning, rather than a wrong one. */
    fun update(peer: String, percent: Int?, charging: Boolean?, limit: Int): Alert? {
        if (percent == null || charging == null) return null
        if (charging || percent > limit + LOW_GAP) lowSent.remove(peer)
        if (!charging || percent < FULL_BELOW) fullSent.remove(peer)
        if (!charging && percent <= limit && lowSent.add(peer)) return Alert.Low(percent)
        if (charging && percent >= 100 && fullSent.add(peer)) return Alert.Full
        return null
    }

    fun forget(peer: String) { lowSent.remove(peer); fullSent.remove(peer) }

    private companion object {
        /** How far back from the limit the battery must recover before the low warning can come again. */
        const val LOW_GAP = 5
        const val FULL_BELOW = 95
    }
}

@Serializable
data class ShelfItem(val path: String) {
    val name: String get() = File(path).name
}

/** The shelf only remembers where files are. Nothing is copied or moved. */
object ShelfStore {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(file: File): List<ShelfItem> = runCatching {
        json.decodeFromString(ListSerializer(ShelfItem.serializer()), file.readText())
    }.getOrDefault(emptyList()).filter { File(it.path).exists() }

    fun save(items: List<ShelfItem>, file: File) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(ListSerializer(ShelfItem.serializer()), items))
        }
    }

    fun adding(files: List<File>, items: List<ShelfItem>): List<ShelfItem> {
        val result = items.toMutableList()
        for (f in files) {
            val item = ShelfItem(f.absolutePath)
            if (item !in result) result.add(0, item)
        }
        return result
    }

    /** Folders and missing files are left out, because only single files can be sent. */
    fun sendable(files: List<File>): List<File> = files.filter { it.isFile }
}

/** Short times, as the notch writes them. */
object IslandText {
    fun clock(ms: Long): String {
        val whole = ((ms + 999) / 1000).coerceAtLeast(0)
        return "%d:%02d".format(whole / 60, whole % 60)
    }

    fun duration(ms: Long): String {
        val minutes = ((ms + 59_999) / 60_000).coerceAtLeast(0)
        if (minutes < 60) return "$minutes min"
        return if (minutes % 60 == 0L) "${minutes / 60} h" else "${minutes / 60} h ${minutes % 60} min"
    }

    fun ago(then: Long, now: Long): String {
        val seconds = (now - then) / 1000
        return when {
            seconds < 60 -> "Just now"
            seconds < 3600 -> "${seconds / 60} min ago"
            seconds < 86_400 -> "${seconds / 3600} h ago"
            else -> DateTimeFormatter.ofPattern("d MMM").format(Instant.ofEpochMilli(then).atZone(ZoneId.systemDefault()))
        }
    }

    fun age(then: Long, now: Long): String {
        val seconds = (now - then) / 1000
        return when {
            seconds < 60 -> "now"
            seconds < 3600 -> "${seconds / 60} min"
            seconds < 86_400 -> "${seconds / 3600} h"
            else -> "${seconds / 86_400} d"
        }
    }
}
