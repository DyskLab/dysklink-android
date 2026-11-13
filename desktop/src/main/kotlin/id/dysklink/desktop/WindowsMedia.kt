package id.dysklink.desktop

import id.phonelink.android.core.CoreLog
import java.io.OutputStreamWriter
import java.util.Base64
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// Music on Windows: the system media sessions (GlobalSystemMediaTransportControlsSessionManager, the same list as the
// volume flyout's player), which Spotify, the Media Player, VLC, and Chrome, Edge and Firefox for YouTube all fill.
//
// Java can't call WinRT, so one Windows PowerShell 5.1 stays open (as the media keys' does) and loads the WinRT types
// with `[...,ContentType=WindowsRuntime]`. It writes one JSON line when something changes and reads commands, one a
// line, on its stdin. Chosen over a small C# helper because PowerShell 5.1 and its WinRT bridge come with every
// Windows 10 and 11: nothing to build, ship, or sign, and no .NET SDK. PowerShell 7 can't load WinRT types, so
// powershell.exe is used, never pwsh.

/** One line from the helper. */
sealed interface SmtcMessage {
    data class Track(
        val app: String, val title: String, val artist: String, val album: String, val status: String,
        val positionMs: Long?, val endMs: Long?, val updatedAt: Long?, val canSeek: Boolean, val artKey: String?,
    ) : SmtcMessage
    data object None : SmtcMessage
    data class Art(val key: String, val bytes: ByteArray) : SmtcMessage
    data class Players(val list: List<PlayerInfo>) : SmtcMessage
    data class Problem(val text: String) : SmtcMessage
}

object SmtcProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(line: String): SmtcMessage? {
        val o = runCatching { json.parseToJsonElement(line.trim()).jsonObject }.getOrNull() ?: return null
        fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        fun l(k: String) = o[k]?.jsonPrimitive?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
        return when (s("t")) {
            "track" -> SmtcMessage.Track(
                app = s("app").orEmpty(), title = s("title").orEmpty(), artist = s("artist").orEmpty(), album = s("album").orEmpty(),
                status = s("status").orEmpty(), positionMs = l("pos"), endMs = l("end"), updatedAt = l("at"),
                canSeek = o["seek"]?.jsonPrimitive?.booleanOrNull ?: false, artKey = s("artKey")?.takeIf { it.isNotEmpty() },
            )
            "none" -> SmtcMessage.None
            "art" -> {
                val key = s("key") ?: return null
                val bytes = runCatching { Base64.getDecoder().decode(s("data").orEmpty()) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
                SmtcMessage.Art(key, bytes)
            }
            "players" -> SmtcMessage.Players(
                o["list"]?.jsonArray?.mapNotNull { item ->
                    val id = (item as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    PlayerInfo(id, appName(id))
                }.orEmpty(),
            )
            "problem" -> SmtcMessage.Problem(s("text").orEmpty())
            else -> null
        }
    }

    /** "Closed", "Opened", "Changing", "Stopped", "Playing", "Paused": only the last two have something to show. */
    fun track(m: SmtcMessage.Track, now: Long): NowPlaying? {
        if (m.title.isBlank() || m.status !in setOf("Playing", "Paused", "Changing")) return null
        val end = m.endMs?.takeIf { it > 0 }
        return NowPlaying(
            title = m.title, artist = m.artist, album = m.album, isPlaying = m.status == "Playing",
            durationMs = end,
            positionMs = if (end != null) m.positionMs?.coerceIn(0, end) else null,
            // The session says when it last set the position; it has moved on since while playing.
            positionAt = m.updatedAt?.takeIf { it in 1..now } ?: now,
            source = m.app, sourceName = appName(m.app), browser = isBrowser(m.app), canSeek = m.canSeek && end != null,
        )
    }

    private val BROWSERS = mapOf(
        "chrome" to "Chrome", "msedge" to "Edge", "brave" to "Brave", "opera" to "Opera", "vivaldi" to "Vivaldi",
        "firefox" to "Firefox", "308046b0af4a39cb" to "Firefox", "zen" to "Zen",
    )

    fun isBrowser(app: String): Boolean = BROWSERS.keys.any { app.lowercase().contains(it) }

    /** "Spotify.exe" -> Spotify; Firefox registers under its install hash, Media Player under a package name. */
    fun appName(app: String): String {
        val lower = app.lowercase()
        BROWSERS.entries.firstOrNull { lower.contains(it.key) }?.let { return it.value }
        return when {
            "zunemusic" in lower -> "Media Player"
            "spotify" in lower -> "Spotify"
            else -> app.substringAfterLast('!').substringAfterLast('\\').removeSuffix(".exe").removeSuffix(".EXE")
                .substringBefore('_').ifBlank { app }
        }
    }

    fun commandLine(command: MediaCommand): String = when (command) {
        MediaCommand.PlayPause -> "play_pause"
        MediaCommand.Next -> "next"
        MediaCommand.Previous -> "previous"
    }

    /** The helper. Kept to Windows PowerShell 5.1's language. */
    val SCRIPT = """
${'$'}ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
[Console]::InputEncoding = [Text.Encoding]::UTF8
function Say(${'$'}o) { [Console]::Out.WriteLine((${'$'}o | ConvertTo-Json -Compress -Depth 4)); [Console]::Out.Flush() }
try {
  Add-Type -AssemblyName System.Runtime.WindowsRuntime
  ${'$'}asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { ${'$'}_.Name -eq 'AsTask' -and ${'$'}_.GetParameters().Count -eq 1 -and ${'$'}_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' } | Select-Object -First 1
  [void][Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]
  [void][Windows.Storage.Streams.DataReader,Windows.Storage.Streams,ContentType=WindowsRuntime]
} catch { Say @{ t = 'problem'; text = 'Windows media sessions are not available here.' }; exit 2 }
function Await(${'$'}op, [Type]${'$'}type) { ${'$'}task = ${'$'}asTask.MakeGenericMethod(${'$'}type).Invoke(${'$'}null, @(${'$'}op)); [void]${'$'}task.Wait(5000); ${'$'}task.Result }
${'$'}ErrorActionPreference = 'SilentlyContinue'
${'$'}manager = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])
if (${'$'}manager -eq ${'$'}null) { Say @{ t = 'problem'; text = 'Windows did not hand over its media sessions.' }; exit 3 }
${'$'}preferred = ''
${'$'}shown = ''
${'$'}lastTrack = ''
${'$'}lastPlayers = ''
${'$'}artKey = ''
function Pick() {
  ${'$'}all = @(${'$'}manager.GetSessions())
  if (${'$'}preferred -ne '') { foreach (${'$'}s in ${'$'}all) { if (${'$'}s.SourceAppUserModelId -eq ${'$'}preferred) { return ${'$'}s } } }
  ${'$'}playing = @(${'$'}all | Where-Object { ${'$'}_.GetPlaybackInfo().PlaybackStatus.ToString() -eq 'Playing' })
  foreach (${'$'}s in ${'$'}playing) { if (${'$'}s.SourceAppUserModelId -eq ${'$'}script:shown) { return ${'$'}s } }
  if (${'$'}playing.Count -gt 0) { return ${'$'}playing[0] }
  foreach (${'$'}s in ${'$'}all) { if (${'$'}s.SourceAppUserModelId -eq ${'$'}script:shown) { return ${'$'}s } }
  return ${'$'}manager.GetCurrentSession()
}
function Art(${'$'}props, ${'$'}key) {
  if (${'$'}props.Thumbnail -eq ${'$'}null) { return }
  ${'$'}stream = Await (${'$'}props.Thumbnail.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
  if (${'$'}stream -eq ${'$'}null -or ${'$'}stream.Size -eq 0 -or ${'$'}stream.Size -gt 4000000) { return }
  ${'$'}reader = New-Object Windows.Storage.Streams.DataReader(${'$'}stream.GetInputStreamAt(0))
  [void](Await (${'$'}reader.LoadAsync([uint32]${'$'}stream.Size)) ([uint32]))
  ${'$'}bytes = New-Object byte[] ([int]${'$'}stream.Size)
  ${'$'}reader.ReadBytes(${'$'}bytes)
  Say @{ t = 'art'; key = ${'$'}key; data = [Convert]::ToBase64String(${'$'}bytes) }
}
function Command(${'$'}line) {
  ${'$'}parts = ${'$'}line.Split(' ', 2)
  if (${'$'}parts[0] -eq 'prefer') { ${'$'}script:preferred = if (${'$'}parts.Count -gt 1) { ${'$'}parts[1] } else { '' }; return }
  ${'$'}s = Pick
  if (${'$'}s -eq ${'$'}null) { return }
  switch (${'$'}parts[0]) {
    'play_pause' { [void](Await (${'$'}s.TryTogglePlayPauseAsync()) ([bool])) }
    'next' { [void](Await (${'$'}s.TrySkipNextAsync()) ([bool])) }
    'previous' { [void](Await (${'$'}s.TrySkipPreviousAsync()) ([bool])) }
    'seek' { [void](Await (${'$'}s.TryChangePlaybackPositionAsync([long]${'$'}parts[1] * 10000)) ([bool])) }
  }
}
${'$'}stdin = [Console]::In
${'$'}pending = ${'$'}stdin.ReadLineAsync()
while (${'$'}true) {
  while (${'$'}pending.IsCompleted) {
    ${'$'}line = ${'$'}pending.Result
    if (${'$'}line -eq ${'$'}null) { exit 0 }
    Command ${'$'}line
    ${'$'}pending = ${'$'}stdin.ReadLineAsync()
  }
  ${'$'}players = @(${'$'}manager.GetSessions() | ForEach-Object { @{ id = ${'$'}_.SourceAppUserModelId } })
  ${'$'}playersJson = (@{ t = 'players'; list = ${'$'}players } | ConvertTo-Json -Compress -Depth 4)
  if (${'$'}playersJson -ne ${'$'}lastPlayers) { [Console]::Out.WriteLine(${'$'}playersJson); [Console]::Out.Flush(); ${'$'}lastPlayers = ${'$'}playersJson }
  ${'$'}s = Pick
  if (${'$'}s -eq ${'$'}null) { ${'$'}json = '{"t":"none"}' } else {
    ${'$'}script:shown = ${'$'}s.SourceAppUserModelId
    ${'$'}props = Await (${'$'}s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])
    ${'$'}info = ${'$'}s.GetPlaybackInfo()
    ${'$'}tl = ${'$'}s.GetTimelineProperties()
    ${'$'}key = ${'$'}s.SourceAppUserModelId + '|' + ${'$'}props.Title + '|' + ${'$'}props.Artist
    ${'$'}json = (@{ t = 'track'; app = ${'$'}s.SourceAppUserModelId; title = ${'$'}props.Title; artist = ${'$'}props.Artist; album = ${'$'}props.AlbumTitle;
      status = ${'$'}info.PlaybackStatus.ToString(); pos = [long]${'$'}tl.Position.TotalMilliseconds; end = [long]${'$'}tl.EndTime.TotalMilliseconds;
      at = ${'$'}tl.LastUpdatedTime.ToUnixTimeMilliseconds(); seek = [bool]${'$'}info.Controls.IsPlaybackPositionEnabled;
      artKey = ${'$'}(if (${'$'}props.Thumbnail -ne ${'$'}null) { ${'$'}key } else { '' }) } | ConvertTo-Json -Compress -Depth 4)
    if (${'$'}key -ne ${'$'}artKey) { ${'$'}artKey = ${'$'}key; Art ${'$'}props ${'$'}key }
  }
  if (${'$'}json -ne ${'$'}lastTrack) { [Console]::Out.WriteLine(${'$'}json); [Console]::Out.Flush(); ${'$'}lastTrack = ${'$'}json }
  Start-Sleep -Milliseconds 500
}
""".trimStart()

    /** For -EncodedCommand: the script as UTF-16LE in base64, so it needs no file and no quoting. */
    fun encoded(): String = Base64.getEncoder().encodeToString(SCRIPT.toByteArray(Charsets.UTF_16LE))
}

/** The PowerShell helper, started when the island is on and started again if it ends. */
class WindowsMediaSource(private val clock: () -> Long = System::currentTimeMillis) : MusicBackend {
    private val _track = MutableStateFlow<NowPlaying?>(null)
    override val track: StateFlow<NowPlaying?> = _track.asStateFlow()
    private val _players = MutableStateFlow<List<PlayerInfo>>(emptyList())
    override val players: StateFlow<List<PlayerInfo>> = _players.asStateFlow()
    private val _art = MutableStateFlow<IslandArt?>(null)
    override val art: StateFlow<IslandArt?> = _art.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    override val problem: StateFlow<String?> = _problem.asStateFlow()
    @Volatile private var process: Process? = null
    @Volatile private var input: OutputStreamWriter? = null
    @Volatile private var running = false

    override var preferred: String? = null
        set(value) { field = value; send("prefer ${value.orEmpty()}") }

    override fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        thread(isDaemon = true, name = "dysk-island-media") {
            var failures = 0
            while (running) {
                val started = System.currentTimeMillis()
                runCatching { runOnce() }.onFailure {
                    CoreLog.w("Island", "Media helper failed", it)
                    _problem.value = "Windows PowerShell couldn't start, so DyskLink can't see what plays."
                }
                if (!running) break
                failures = if (System.currentTimeMillis() - started > 60_000) 0 else failures + 1
                // A helper that keeps ending isn't started more than about once a minute.
                Thread.sleep(minOf(60_000L, 2_000L shl minOf(failures, 5)))
            }
        }
    }

    private fun runOnce() {
        val p = ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", SmtcProtocol.encoded())
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process = p
        input = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
        preferred?.let { send("prefer $it") }
        p.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line -> handle(line) }
        p.waitFor()
        _track.value = null
    }

    /** One line from the helper. Public for tests. */
    fun handle(line: String) {
        when (val m = SmtcProtocol.parse(line) ?: return) {
            is SmtcMessage.Track -> { _track.value = SmtcProtocol.track(m, clock()); _problem.value = null }
            SmtcMessage.None -> _track.value = null
            is SmtcMessage.Art -> _art.value = IslandArt(m.key, m.bytes)
            is SmtcMessage.Players -> _players.value = m.list
            is SmtcMessage.Problem -> _problem.value = m.text
        }
    }

    private fun send(line: String) {
        val w = input ?: return
        runCatching { synchronized(w) { w.write(line + "\n"); w.flush() } }
    }

    override fun stop() {
        running = false
        runCatching { input?.close() }
        process?.destroy()
        process = null
        _track.value = null
    }

    override fun command(command: MediaCommand) {
        send(SmtcProtocol.commandLine(command))
        if (command == MediaCommand.PlayPause) _track.value = _track.value?.toggled(clock())
    }

    override fun seek(positionMs: Long) {
        send("seek $positionMs")
        _track.value = _track.value?.copy(positionMs = positionMs, positionAt = clock())
    }
}
