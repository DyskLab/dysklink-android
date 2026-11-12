package id.dysklink.desktop

import id.phonelink.android.core.CoreLog
import id.phonelink.android.core.UpdateCheck
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/** A file of a release, such as DyskLink-1.1.0.msi. */
data class ReleaseAsset(val name: String, val url: String)

/** A release of DyskLink for Windows and Linux. */
data class DesktopRelease(val version: String, val tag: String, val pageUrl: String, val assets: List<ReleaseAsset>)

/** Which installer suits this Linux: Debian and Ubuntu take .deb, Fedora, RHEL and openSUSE take .rpm. */
enum class LinuxFamily { Debian, RedHat, Other }

/**
 * Finds a newer DyskLink for this PC in the GitHub releases of DyskLab/dysklink-desktop. Tags are `desktop-v1.2.0` or
 * `v1.2.0`. Nothing is installed by itself: Download opens the installer that suits this system.
 */
object DesktopUpdates {
    const val LATEST_URL = "https://api.github.com/repos/DyskLab/dysklink-desktop/releases/latest"
    const val PAGE_URL = "https://github.com/DyskLab/dysklink-desktop/releases"

    private val json = Json { ignoreUnknownKeys = true }

    /** "desktop-v1.2.0", "v1.2.0", or "1.2.0" as "1.2.0"; null for a tag that isn't a version. */
    fun versionOf(tag: String): String? {
        val bare = UpdateCheck.clean(tag.trim().removePrefix("desktop-").removePrefix("Desktop-"))
        return bare.takeIf { UpdateCheck.version(it) != null && it.isNotEmpty() && it.first().isDigit() }
    }

    /** The release in GitHub's `releases/latest` answer, or null when it isn't one (a draft, a tag that isn't a version). */
    fun parse(body: String): DesktopRelease? {
        val item = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        fun text(element: JsonElement?) = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
        if ((item["draft"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val tag = text(item["tag_name"]) ?: return null
        val version = versionOf(tag) ?: return null
        val assets = (item["assets"] as? JsonArray).orEmpty().mapNotNull { asset ->
            val fields = asset as? JsonObject ?: return@mapNotNull null
            val name = text(fields["name"]) ?: return@mapNotNull null
            val url = text(fields["browser_download_url"]) ?: return@mapNotNull null
            ReleaseAsset(name, url)
        }
        return DesktopRelease(version, tag, text(item["html_url"]) ?: PAGE_URL, assets)
    }

    /** Whether [release] is newer than [current]. A build without a version ("dev") never asks to update. */
    fun isNewer(release: DesktopRelease, current: String): Boolean =
        UpdateCheck.version(current) != null && UpdateCheck.isNewer(release.version, current)

    /** From the text of /etc/os-release: its ID and ID_LIKE. */
    fun family(osRelease: String): LinuxFamily {
        val values = osRelease.lines().mapNotNull { line ->
            val key = line.substringBefore('=', "").trim()
            if (key == "ID" || key == "ID_LIKE") line.substringAfter('=').trim().trim('"', '\'').lowercase() else null
        }.flatMap { it.split(' ', ',') }.filter { it.isNotEmpty() }
        return when {
            values.any { it in setOf("debian", "ubuntu", "linuxmint", "pop", "elementary", "raspbian", "kali", "zorin", "neon") } -> LinuxFamily.Debian
            values.any { it in setOf("fedora", "rhel", "centos", "rocky", "almalinux", "suse", "opensuse", "opensuse-leap", "opensuse-tumbleweed", "sles", "mageia", "ol", "amzn") } -> LinuxFamily.RedHat
            else -> LinuxFamily.Other
        }
    }

    /**
     * The installer for this system: .msi, else .exe on Windows; on Linux .deb or .rpm by [family], else the
     * AppImage, which runs anywhere. Null when the release has none, and the release page opens instead.
     */
    fun asset(release: DesktopRelease, windows: Boolean, family: LinuxFamily): ReleaseAsset? {
        fun with(ext: String) = release.assets.firstOrNull { it.name.lowercase().endsWith(ext) }
        if (windows) return with(".msi") ?: with(".exe")
        return when (family) {
            LinuxFamily.Debian -> with(".deb") ?: with(".appimage")
            LinuxFamily.RedHat -> with(".rpm") ?: with(".appimage")
            LinuxFamily.Other -> with(".appimage")
        }
    }

    /** What a check found. */
    sealed interface Result {
        data class Found(val release: DesktopRelease) : Result
        data object Current : Result
        /** The repository or its first release isn't there yet (404). */
        data object NoReleases : Result
        data class Failed(val why: String) : Result
    }

    /** One check, with [fetch] giving the HTTP status and body. */
    fun check(current: String, fetch: (String) -> Pair<Int, String>): Result {
        val (status, body) = runCatching { fetch(LATEST_URL) }.getOrElse { return Result.Failed("Couldn't reach GitHub. Check the connection.") }
        return when {
            status == 404 -> Result.NoReleases
            status == 403 || status == 429 -> Result.Failed("GitHub is busy. Try again in an hour.")
            status !in 200..299 -> Result.Failed("GitHub answered $status.")
            else -> {
                val release = parse(body) ?: return Result.NoReleases
                if (isNewer(release, current)) Result.Found(release) else Result.Current
            }
        }
    }
}

/** What the window shows about updates. [available] is a newer release that wasn't skipped. */
data class UpdateState(
    val available: DesktopRelease? = null,
    val checking: Boolean = false,
    val lastChecked: Long? = null,
    /** The outcome of the last check asked for with Check now. */
    val message: String? = null,
)

/** Checks once a day and on Check now, remembers a skipped version, and opens the right installer. */
class Updater(
    private val settings: Settings,
    private val scope: CoroutineScope,
    private val current: String = Platform.version,
    private val fetch: (String) -> Pair<Int, String> = ::httpGet,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(UpdateState(lastChecked = settings.string("update.checked")?.toLongOrNull()))
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Checks now if a day has passed since the last check, then once an hour sees whether another day has. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            delay(15_000)
            while (true) {
                val last = _state.value.lastChecked ?: 0
                if (clock() - last >= DAY_MS) check(manual = false)
                delay(HOUR_MS)
            }
        }
    }

    fun checkNow() {
        if (_state.value.checking) return
        _state.value = _state.value.copy(checking = true, message = null)
        scope.launch(Dispatchers.IO) { check(manual = true) }
    }

    @Synchronized
    fun check(manual: Boolean) {
        _state.value = _state.value.copy(checking = true)
        val result = DesktopUpdates.check(current, fetch)
        val now = clock()
        // A failure isn't counted as a check, so the next hour tries again.
        if (result !is DesktopUpdates.Result.Failed) settings.set("update.checked", now.toString())
        val skipped = settings.string("update.skip")
        _state.value = when (result) {
            is DesktopUpdates.Result.Found -> {
                // Asked for by hand, a skipped version shows again.
                if (manual && skipped == result.release.version) settings.set("update.skip", "")
                val shown = result.release.takeIf { manual || it.version != skipped }
                UpdateState(shown, false, now, if (manual) "DyskLink ${result.release.version} is available." else null)
            }
            DesktopUpdates.Result.Current -> UpdateState(null, false, now, if (manual) "DyskLink $current is the newest version." else null)
            DesktopUpdates.Result.NoReleases -> UpdateState(null, false, now, if (manual) "No DyskLink for Windows and Linux has been released on GitHub yet." else null)
            is DesktopUpdates.Result.Failed -> _state.value.copy(checking = false, message = if (manual) result.why else null)
        }
        if (result is DesktopUpdates.Result.Failed) CoreLog.w("DyskLink", "Update check failed: ${result.why}")
    }

    /** Opens the installer for this system in the browser, or the release page when there's none. */
    fun download() {
        val release = _state.value.available ?: return
        val family = if (Platform.isWindows) LinuxFamily.Other else DesktopUpdates.family(runCatching { File("/etc/os-release").readText() }.getOrDefault(""))
        val url = DesktopUpdates.asset(release, Platform.isWindows, family)?.url ?: release.pageUrl
        browse(url)
    }

    /** Hides this version until a newer one comes, or Check now is pressed. */
    fun skip() {
        val release = _state.value.available ?: return
        settings.set("update.skip", release.version)
        _state.value = _state.value.copy(available = null, message = null)
    }

    companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val HOUR_MS = 60 * 60 * 1000L

        private val client by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build() }

        fun httpGet(url: String): Pair<Int, String> {
            val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json").header("User-Agent", "DyskLink-Desktop").GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            return response.statusCode() to response.body().orEmpty()
        }

        /** Only https links on GitHub are opened. */
        fun browse(url: String) {
            if (!url.startsWith("https://github.com/") && !url.startsWith("https://objects.githubusercontent.com/")) return
            runCatching {
                if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                    java.awt.Desktop.getDesktop().browse(URI(url))
                } else if (Platform.isWindows) {
                    ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start()
                } else {
                    ProcessBuilder("xdg-open", url).start()
                }
            }.onFailure { CoreLog.w("DyskLink", "Can't open $url", it) }
        }
    }
}
