@file:Suppress("FunctionName")

package id.dysklink.desktop

import id.phonelink.android.core.CoreLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt64
import org.freedesktop.dbus.types.Variant

// Music on Linux: every player that speaks MPRIS on the session bus (Spotify, Rhythmbox, VLC, mpv with its plugin, and
// Firefox and Chrome-based browsers for YouTube and other sites). The member names keep their D-Bus spelling.

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MprisPlayer : DBusInterface {
    fun PlayPause()
    fun Next()
    fun Previous()
    fun SetPosition(trackId: DBusPath, position: Long)
}

/** What a player says about itself in one look, with D-Bus variants already unwrapped. */
data class MprisSnapshot(
    val busName: String,
    val identity: String?,
    val status: String?,
    val metadata: Map<String, Any?>,
    val positionUs: Long?,
    val canSeek: Boolean = false,
    val canControl: Boolean = true,
)

/** Reading MPRIS and choosing among players, without a bus. */
object MprisParse {
    const val PREFIX = "org.mpris.MediaPlayer2."
    const val PATH = "/org/mpris/MediaPlayer2"
    const val PLAYER = "org.mpris.MediaPlayer2.Player"
    const val ROOT = "org.mpris.MediaPlayer2"

    private val BROWSERS = listOf("firefox", "chromium", "chrome", "brave", "edge", "vivaldi", "opera", "zen", "librewolf", "epiphany", "falkon", "plasma-browser-integration")

    /** playerctld only repeats another player, so it's left out. */
    fun isPlayerName(name: String) = name.startsWith(PREFIX) && !name.startsWith(PREFIX + "playerctld")

    fun isBrowser(busName: String, identity: String?): Boolean {
        val text = (busName + " " + identity.orEmpty()).lowercase()
        return BROWSERS.any { it in text }
    }

    /** "Spotify", or "Firefox" for org.mpris.MediaPlayer2.firefox.instance_1_23. */
    fun displayName(busName: String, identity: String?): String =
        identity?.takeIf { it.isNotBlank() } ?: busName.removePrefix(PREFIX).substringBefore('.').replaceFirstChar { it.uppercase() }

    /** A D-Bus value as plain Kotlin: variants opened, unsigned numbers as Long, object paths as text. */
    fun unwrap(value: Any?): Any? = when (value) {
        is Variant<*> -> unwrap(value.value)
        is UInt64 -> value.toLong()
        is org.freedesktop.dbus.types.UInt32 -> value.toLong()
        is DBusPath -> value.path
        is List<*> -> value.map(::unwrap)
        is Array<*> -> value.map(::unwrap)
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to unwrap(v) }
        else -> value
    }

    private fun long(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    private fun text(value: Any?): String? = when (value) {
        is String -> value
        is List<*> -> value.filterIsInstance<String>().filter { it.isNotBlank() }.joinToString(", ")
        else -> null
    }?.trim()

    /** The song a player has, or null when it has none (stopped, or nothing loaded). */
    fun track(s: MprisSnapshot, now: Long): NowPlaying? {
        if (s.status == null || s.status == "Stopped") return null
        val meta = s.metadata
        val title = text(meta["xesam:title"])?.takeIf { it.isNotEmpty() }
            ?: text(meta["xesam:url"])?.substringAfterLast('/')?.takeIf { it.isNotEmpty() } ?: return null
        val length = long(meta["mpris:length"])?.takeIf { it > 0 }
        return NowPlaying(
            title = title,
            artist = text(meta["xesam:artist"]) ?: text(meta["xesam:albumArtist"]) ?: "",
            album = text(meta["xesam:album"]) ?: "",
            isPlaying = s.status == "Playing",
            durationMs = length?.div(1000),
            positionMs = s.positionUs?.takeIf { it >= 0 }?.div(1000),
            positionAt = now,
            source = s.busName,
            sourceName = displayName(s.busName, s.identity),
            browser = isBrowser(s.busName, s.identity),
            url = text(meta["xesam:url"]),
            artUrl = text(meta["mpris:artUrl"])?.takeIf { it.isNotEmpty() },
            trackId = text(meta["mpris:trackid"]),
            canSeek = s.canSeek && length != null,
            canControl = s.canControl,
        )
    }

    /**
     * The player to show: the one chosen in the settings while it's there; else what plays, keeping the one shown
     * before when several play; else the one shown before, paused; else any paused one.
     */
    fun choose(players: List<MprisSnapshot>, preferred: String?, shownBefore: String?): MprisSnapshot? {
        val live = players.filter { it.status != null && it.status != "Stopped" }
        preferred?.let { want -> players.firstOrNull { it.busName == want || it.busName.startsWith("$want.") }?.let { return it } }
        val playing = live.filter { it.status == "Playing" }
        playing.firstOrNull { it.busName == shownBefore }?.let { return it }
        playing.firstOrNull()?.let { return it }
        live.firstOrNull { it.busName == shownBefore }?.let { return it }
        return live.firstOrNull()
    }
}

/** The music source on each system. */
interface MusicBackend {
    val track: StateFlow<NowPlaying?>
    val players: StateFlow<List<PlayerInfo>>
    /** Album art the source hands over itself (Windows); MPRIS gives a URL in the track instead. */
    val art: StateFlow<IslandArt?>
    /** Why music can't be read, for a hint. */
    val problem: StateFlow<String?>
    var preferred: String?
    fun start(scope: CoroutineScope)
    fun stop()
    fun command(command: MediaCommand)
    fun seek(positionMs: Long)
}

/** MPRIS players on the session bus, looked at once a second. */
class MprisSource(private val connect: () -> DBusConnection?, private val clock: () -> Long = System::currentTimeMillis) : MusicBackend {
    private val _track = MutableStateFlow<NowPlaying?>(null)
    override val track: StateFlow<NowPlaying?> = _track.asStateFlow()
    private val _players = MutableStateFlow<List<PlayerInfo>>(emptyList())
    override val players: StateFlow<List<PlayerInfo>> = _players.asStateFlow()
    override val art: StateFlow<IslandArt?> = MutableStateFlow(null)
    private val _problem = MutableStateFlow<String?>(null)
    override val problem: StateFlow<String?> = _problem.asStateFlow()
    @Volatile override var preferred: String? = null
    @Volatile private var connection: DBusConnection? = null
    @Volatile private var shown: String? = null
    private var job: Job? = null

    override fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                runCatching { poll() }.onFailure { CoreLog.w("Island", "MPRIS look failed", it) }
                delay(POLL_MS)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        runCatching { connection?.close() }
        connection = null
        _track.value = null
    }

    private fun bus(): DBusConnection? {
        connection?.takeIf { it.isConnected }?.let { return it }
        val c = connect()
        connection = c
        _problem.value = if (c == null) "DyskLink can't reach the session bus (D-Bus), so it can't see what plays." else null
        return c
    }

    /** One look at every player. Public for tests. */
    fun poll() {
        val c = bus() ?: return
        val names = c.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java).ListNames()
            .filter(MprisParse::isPlayerName).sorted()
        val snapshots = names.mapNotNull { name -> runCatching { read(c, name) }.getOrNull() }
        _players.value = snapshots.map { PlayerInfo(it.busName, MprisParse.displayName(it.busName, it.identity)) }
        val chosen = MprisParse.choose(snapshots, preferred, shown)
        shown = chosen?.busName
        _track.value = chosen?.let { MprisParse.track(it, clock()) }
    }

    private fun read(c: DBusConnection, name: String): MprisSnapshot {
        val props = c.getRemoteObject(name, MprisParse.PATH, Properties::class.java)
        val all = runCatching { props.GetAll(MprisParse.PLAYER) }.getOrNull()
        fun get(key: String): Any? = all?.get(key)?.let(MprisParse::unwrap)
            ?: runCatching { MprisParse.unwrap(props.Get<Any>(MprisParse.PLAYER, key)) }.getOrNull()
        val identity = runCatching { props.Get<String>(MprisParse.ROOT, "Identity") }.getOrNull()
        @Suppress("UNCHECKED_CAST")
        val meta = (get("Metadata") as? Map<String, Any?>).orEmpty()
        return MprisSnapshot(
            busName = name,
            identity = identity,
            status = get("PlaybackStatus") as? String,
            metadata = meta,
            positionUs = (get("Position") as? Number)?.toLong(),
            canSeek = get("CanSeek") as? Boolean ?: false,
            canControl = get("CanControl") as? Boolean ?: true,
        )
    }

    private fun player(): MprisPlayer? {
        val c = bus() ?: return null
        val name = shown ?: return null
        return runCatching { c.getRemoteObject(name, MprisParse.PATH, MprisPlayer::class.java) }.getOrNull()
    }

    override fun command(command: MediaCommand) {
        val p = player() ?: return
        runCatching {
            when (command) {
                MediaCommand.PlayPause -> p.PlayPause()
                MediaCommand.Next -> p.Next()
                MediaCommand.Previous -> p.Previous()
            }
        }.onFailure { CoreLog.w("Island", "MPRIS $command failed", it) }
        _track.value = _track.value?.let { if (command == MediaCommand.PlayPause) it.toggled(clock()) else it }
    }

    override fun seek(positionMs: Long) {
        val t = _track.value ?: return
        val id = t.trackId ?: return
        val p = player() ?: return
        runCatching { p.SetPosition(DBusPath(id), positionMs * 1000) }
        _track.value = t.copy(positionMs = positionMs, positionAt = clock())
    }

    companion object {
        const val POLL_MS = 1_000L

        fun forSession(): MprisSource = MprisSource({
            LinuxTray.sessionAddress()?.let { address ->
                runCatching { DBusConnectionBuilder.forAddress(address).withShared(false).build() }.getOrNull()
            }
        })
    }
}
