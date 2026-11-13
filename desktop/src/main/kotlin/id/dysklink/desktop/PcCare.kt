package id.dysklink.desktop

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

/*
 * PC care, as Mac care on the Mac: clean up space, find duplicate files, uninstall apps with their leftovers, and
 * manage what starts at login. Everything removed goes to the Recycle Bin or the Trash, never deleted for good, and
 * only from places [CareGuard] allows.
 */

/** A file or folder PC care can move to the Recycle Bin. [members] are what's really moved when it stands for several. */
data class CareItem(
    val file: File,
    val size: Long,
    /** Why it's listed, such as "Not opened for 5 months". */
    val detail: String = "",
    val date: Long? = null,
    /** A name people know, such as "Chrome cache", instead of the folder's name. */
    val title: String? = null,
    val members: List<File>? = null,
    /** Ticked when the list shows up. Things that may hold your own work start unticked. */
    val preselect: Boolean = true,
) {
    val id: String get() = file.path
    val name: String get() = title ?: file.name
    val files: List<File> get() = members ?: listOf(file)
}

/** A program's output. */
data class ProcOut(val code: Int, val out: String, val err: String = "")

/** A running process, for whether an app is open. */
data class Proc(val pid: Long, val command: String, val commandLine: String = "")

enum class TrashProblem { Cancelled, NoTrash, NotAllowed, Other }

data class TrashOutcome(val moved: List<File>, val failed: List<File>, val problem: TrashProblem? = null)

/** What PC care needs from the PC, so tests can fake it. */
interface CareSystem {
    val windows: Boolean
    val home: File
    fun env(name: String): String?
    fun onPath(program: String): Boolean
    /** Null when the program couldn't start or ran out of time. */
    fun run(command: List<String>, timeoutMs: Long = 60_000, env: Map<String, String> = emptyMap()): ProcOut?
    fun processes(): List<Proc>
    /** Asks the processes to close, or ends them with [force]. False when some couldn't be asked. */
    fun quit(pids: List<Long>, force: Boolean): Boolean
    fun trash(files: List<File>): TrashOutcome
    /** Opens the file manager at the file. */
    fun reveal(file: File) {}
}

/** Where things are on this PC. Built from the environment, or by hand in tests. */
data class CarePlaces(
    val home: File,
    val windows: Boolean,
    val temp: File? = null,
    val localAppData: File? = null,
    val appData: File? = null,
    val downloads: File = File(home, "Downloads"),
    val cache: File = File(home, ".cache"),
    val config: File = File(home, ".config"),
    val dataHome: File = File(home, ".local/share"),
    /** DyskLink's own folder, which is never offered. */
    val ownData: File? = null,
    val programData: File? = null,
) {
    companion object {
        fun of(sys: CareSystem, ownData: File? = null): CarePlaces {
            val home = sys.home
            fun dir(name: String) = sys.env(name)?.takeIf { it.isNotBlank() }?.let(::File)
            return if (sys.windows) {
                CarePlaces(
                    home, true, temp = dir("TEMP") ?: dir("TMP"), localAppData = dir("LOCALAPPDATA") ?: File(home, "AppData/Local"),
                    appData = dir("APPDATA") ?: File(home, "AppData/Roaming"), ownData = ownData, programData = dir("ProgramData"),
                )
            } else {
                CarePlaces(
                    home, false, downloads = Platform.downloadsDir.parentFile ?: File(home, "Downloads"),
                    cache = dir("XDG_CACHE_HOME") ?: File(home, ".cache"), config = dir("XDG_CONFIG_HOME") ?: File(home, ".config"),
                    dataHome = dir("XDG_DATA_HOME") ?: File(home, ".local/share"), ownData = ownData,
                )
            }
        }
    }
}

/**
 * The last check before anything is moved: only what's inside the home folder or the user's temporary and app data
 * folders, never one of those folders itself, never a folder that holds one, and never a system folder.
 */
class CareGuard(allowed: List<File>, protectedPaths: List<File>, private val ignoreCase: Boolean) {
    private val allowed = allowed.map(::key).distinct()
    private val protectedKeys = protectedPaths.map(::key).distinct()

    fun allows(file: File): Boolean {
        val name = file.name
        if (name.isEmpty() || name == "." || name == "..") return false
        val path = key(file)
        if (allowed.none { inside(path, it) }) return false
        // Not a protected folder, and not one that holds one (the home folder, Documents, AppData...).
        return protectedKeys.none { it == path || inside(it, path) }
    }

    /** The path with its parent's links followed, so a link can't point the item somewhere else. The item itself isn't
     * followed: moving a link moves only the link. */
    private fun key(file: File): String {
        val abs = file.absoluteFile.toPath().normalize()
        val parent = abs.parent
        val real = parent?.let { runCatching { it.toRealPath() }.getOrNull() } ?: parent
        val full = (real?.resolve(abs.fileName.toString()) ?: abs).toString().replace('\\', '/').trimEnd('/')
        return if (ignoreCase) full.lowercase() else full
    }

    private fun inside(path: String, root: String) = path.startsWith("$root/") && path.length > root.length + 1

    companion object {
        fun forPlaces(p: CarePlaces): CareGuard {
            val home = p.home
            val allowed = listOfNotNull(home, p.temp, p.localAppData, p.appData)
            val userFolders = listOf(
                "Desktop", "Documents", "Downloads", "Pictures", "Music", "Videos", "Movies", "Public", "Templates", "OneDrive",
                ".config", ".local", ".local/share", ".local/state", ".local/bin", ".cache", ".ssh", ".gnupg", ".var", ".var/app", "snap",
                "AppData", "AppData/Local", "AppData/Roaming", "AppData/LocalLow", "AppData/Local/Programs", "AppData/Local/Packages",
                "AppData/Local/Microsoft", "AppData/Roaming/Microsoft", "AppData/Roaming/Microsoft/Windows",
                "AppData/Roaming/Microsoft/Windows/Start Menu", "AppData/Roaming/Microsoft/Windows/Start Menu/Programs",
                "AppData/Roaming/Microsoft/Windows/Start Menu/Programs/Startup", ".config/autostart", ".local/share/applications",
                ".local/share/Trash", ".gradle", ".m2", ".npm", ".cargo", ".android",
            ).map { File(home, it) }
            val system = if (p.windows) {
                listOf("C:/Windows", "C:/Program Files", "C:/Program Files (x86)", "C:/ProgramData", "C:/Users", "C:/").map(::File)
            } else {
                listOf("/", "/usr", "/etc", "/bin", "/sbin", "/lib", "/lib32", "/lib64", "/boot", "/var", "/opt", "/snap", "/root", "/home", "/srv", "/proc", "/sys", "/dev", "/run", "/tmp", "/System", "/Library", "/Users", "/Applications").map(::File)
            }
            val protectedPaths = listOf(home) + userFolders + system +
                listOfNotNull(p.temp, p.localAppData, p.appData, p.cache, p.config, p.dataHome, p.downloads, p.ownData, p.programData) +
                listOfNotNull(p.localAppData?.let { File(it, "Temp") })
            return CareGuard(allowed, protectedPaths, ignoreCase = p.windows)
        }
    }
}

/** Sizes, dates, and the like, on the real disk. */
object CareFiles {
    /** Bytes a file or folder holds. Links aren't followed. */
    fun size(file: File): Long {
        val path = file.toPath()
        val attrs = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull() ?: return 0
        if (attrs.isSymbolicLink || attrs.isOther) return 0
        if (!attrs.isDirectory) return attrs.size()
        var total = 0L
        runCatching {
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
            })
        }
        return total
    }

    fun children(folder: File): List<File> = folder.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun isDir(file: File) = runCatching { Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS) }.getOrDefault(false)

    fun isLink(file: File) = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    /** The newest of when it was changed, last opened, and made: a download that was never opened counts from when it came. */
    fun lastUsed(file: File): Long? = runCatching {
        val a = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        maxOf(a.lastModifiedTime().toMillis(), a.lastAccessTime().toMillis(), a.creationTime().toMillis())
    }.getOrNull()

    fun created(file: File): Long? = runCatching {
        val a = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        minOf(a.creationTime().toMillis().takeIf { it > 0 } ?: Long.MAX_VALUE, a.lastModifiedTime().toMillis())
    }.getOrNull()

    fun ago(date: Long?, now: Long = System.currentTimeMillis()): String {
        date ?: return ""
        val days = (now - date) / 86_400_000L
        return when {
            days < 1 -> "today"
            days < 60 -> "$days days ago"
            days < 730 -> "${days / 30} months ago"
            else -> "${days / 365} years ago"
        }
    }

    /** Paths in the home folder with ~, like in a terminal. */
    fun shortPath(file: File, home: File): String {
        val path = file.path
        val root = home.path.trimEnd('/', '\\')
        return if (path.startsWith(root) && path.length > root.length && (path[root.length] == '/' || path[root.length] == '\\')) {
            "~" + path.substring(root.length)
        } else path
    }

    fun bytes(n: Long): String {
        if (n < 1000) return "$n bytes"
        val units = listOf("KB", "MB", "GB", "TB")
        var value = n / 1000.0
        var unit = 0
        while (value >= 1000 && unit < units.lastIndex) { value /= 1000; unit++ }
        return if (value >= 100 || unit == 0) "${value.toLong()} ${units[unit]}" else "%.1f %s".format(java.util.Locale.ROOT, value, units[unit])
    }

    fun day(millis: Long): String = DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH).format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}

// Cleaner

enum class CleanGroup(val title: String) {
    Caches("App caches"), Logs("Logs and crash reports"), Developer("Developer files"), Installers("Installers"),
    OldDownloads("Old downloads"), Large("Large files");

    fun detail(windows: Boolean): String = when (this) {
        Caches -> if (windows) "Temporary files, and browser and app caches. Apps make these again when they need them. Close an app before cleaning its cache."
        else "Each app's folder in ~/.cache. Apps make these again when they need them. Close an app before cleaning its cache."
        Logs -> if (windows) "Crash dumps and error reports Windows kept for apps." else "Session logs kept for troubleshooting."
        Developer -> "Gradle, npm, pip, Cargo, Maven, and Android build caches. Builds take longer the first time after."
        Installers -> if (windows) "Setup files in Downloads (.msi, .exe, .msix, .iso, setup .zip). Once an app is installed, these aren't needed."
        else "Packages in Downloads (.deb, .rpm, .AppImage, .iso, installer .zip). Once an app is installed, these aren't needed."
        OldDownloads -> "In Downloads and not opened for 3 months. Check before removing."
        Large -> "Files of 500 MB or more in your home folder. Check before removing."
    }

    /** Safe kinds start ticked. Your own files never do. */
    val selectedByDefault: Boolean get() = this == Caches || this == Logs || this == Developer
}

object Cleaner {
    const val MINIMUM_ITEM = 512L * 1024
    const val LARGE_FILE = 500L * 1_000_000
    const val OLD_AFTER = 90L * 86_400_000
    /** Temporary files changed in the last day may be in use. */
    const val TEMP_QUIET = 86_400_000L
    val installerTypes = setOf("msi", "exe", "msix", "msixbundle", "appx", "appxbundle", "deb", "rpm", "appimage", "iso", "flatpakref")
    private val installerZip = Regex("(setup|install|installer)", RegexOption.IGNORE_CASE)

    fun isInstaller(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in installerTypes || (ext == "zip" && installerZip.containsMatchIn(name.substringBeforeLast('.')))
    }

    /** Caches that belong in "Developer files" and not with the app caches, under ~/.cache. */
    val developerCacheNames = setOf("pip", "yarn", "go-build", "pypoetry", "node-gyp")

    /** [lastUsed] is when a file was last used; tests pass their own. */
    fun scan(
        p: CarePlaces,
        now: Long = System.currentTimeMillis(),
        lastUsed: (File) -> Long? = CareFiles::lastUsed,
        progress: (String) -> Unit = {},
    ): Map<CleanGroup, List<CareItem>> {
        val result = linkedMapOf<CleanGroup, List<CareItem>>()
        progress("app caches")
        result[CleanGroup.Caches] = sized(if (p.windows) windowsCaches(p, now, lastUsed) else linuxCaches(p))
        progress("logs")
        result[CleanGroup.Logs] = sized(if (p.windows) windowsLogs(p) else linuxLogs(p))
        progress("developer files")
        result[CleanGroup.Developer] = sized(developer(p))
        progress("Downloads")
        val inDownloads = CareFiles.children(p.downloads).filter { !it.name.startsWith(".") && it.name != "desktop.ini" }
            // Files from phones arrive in Downloads/DyskLink: that's DyskLink's own folder.
            .filter { it.name != "DyskLink" }
        val installers = inDownloads.filter { !CareFiles.isDir(it) && isInstaller(it.name) }
        result[CleanGroup.Installers] = sized(installers.map { CareItem(it, 0, "Added " + CareFiles.ago(lastUsed(it), now), lastUsed(it)) }, minimum = 1)
        val old = inDownloads.filter { it !in installers && (lastUsed(it)?.let { used -> now - used > OLD_AFTER } ?: false) }
        result[CleanGroup.OldDownloads] = sized(old.map { CareItem(it, 0, "Last used " + CareFiles.ago(lastUsed(it), now), lastUsed(it)) }, minimum = 1)
        progress("large files")
        val seen = result.values.flatten().map { it.id }.toSet()
        result[CleanGroup.Large] = largeFiles(p, now, lastUsed).filter { it.id !in seen }
        return result
    }

    /** Sizes each item (adding up members), drops the small ones, biggest first. */
    private fun sized(items: List<CareItem>, minimum: Long = MINIMUM_ITEM): List<CareItem> =
        items.mapNotNull { item ->
            val size = item.files.sumOf(CareFiles::size)
            if (size >= minimum) item.copy(size = size) else null
        }.sortedByDescending { it.size }

    private fun item(file: File, title: String? = null, detail: String = "", preselect: Boolean = true) =
        if (file.exists()) CareItem(file, 0, detail, title = title, preselect = preselect) else null

    /** Chromium's cache folders inside a profile. */
    private val chromiumCaches = listOf("Cache", "Code Cache", "GPUCache", "Service Worker/CacheStorage", "DawnCache", "DawnGraphiteCache")

    fun windowsCaches(p: CarePlaces, now: Long, lastUsed: (File) -> Long?): List<CareItem> {
        val local = p.localAppData ?: return emptyList()
        val roaming = p.appData
        val found = mutableListOf<CareItem>()
        p.temp?.let { temp ->
            val quiet = CareFiles.children(temp).filter { lastUsed(it)?.let { used -> now - used > TEMP_QUIET } ?: false }
            if (quiet.isNotEmpty()) found += CareItem(temp, 0, "${quiet.size} items not changed today, in " + CareFiles.shortPath(temp, p.home), title = "Temporary files", members = quiet)
        }
        // Browsers: only their cache folders, never history, passwords, or settings.
        val browsers = listOf(
            "Chrome" to File(local, "Google/Chrome/User Data"), "Edge" to File(local, "Microsoft/Edge/User Data"),
            "Brave" to File(local, "BraveSoftware/Brave-Browser/User Data"), "Vivaldi" to File(local, "Vivaldi/User Data"),
        )
        for ((browser, data) in browsers) {
            val profiles = CareFiles.children(data).filter { it.name == "Default" || it.name.startsWith("Profile ") || it.name == "Guest Profile" }
            for (profile in profiles) {
                val parts = chromiumCaches.map { File(profile, it) }.filter { it.exists() }
                if (parts.isNotEmpty()) found += CareItem(File(profile, "Cache"), 0, "Profile: ${profile.name}", title = "$browser cache", members = parts)
            }
            listOf("ShaderCache", "GrShaderCache", "GraphiteDawnCache").map { File(data, it) }.filter { it.exists() }.takeIf { it.isNotEmpty() }?.let {
                found += CareItem(it.first(), 0, "Graphics shaders", title = "$browser shader cache", members = it)
            }
        }
        for (profile in CareFiles.children(File(local, "Mozilla/Firefox/Profiles"))) {
            item(File(profile, "cache2"), "Firefox cache", "Profile: ${profile.name.substringAfter('.')}")?.let(found::add)
        }
        // Electron apps keep Chromium's cache folders in their own data folder.
        val electron = listOfNotNull(
            roaming?.let { "Microsoft Teams (classic)" to File(it, "Microsoft/Teams") }, roaming?.let { "Discord" to File(it, "discord") },
            roaming?.let { "Slack" to File(it, "Slack") }, roaming?.let { "Visual Studio Code" to File(it, "Code") },
            roaming?.let { "WhatsApp" to File(it, "WhatsApp") },
        )
        for ((app, data) in electron) {
            val parts = chromiumCaches.map { File(data, it) }.filter { it.exists() }
            if (parts.isNotEmpty()) found += CareItem(File(data, "Cache"), 0, CareFiles.shortPath(data, p.home), title = "$app cache", members = parts)
        }
        item(File(local, "Spotify/Storage"), "Spotify cache", "Songs saved for streaming")?.let(found::add)
        item(File(local, "Spotify/Data"), "Spotify data cache")?.let(found::add)
        item(File(local, "D3DSCache"), "DirectX shader cache")?.let(found::add)
        item(File(local, "NVIDIA/DXCache"), "NVIDIA shader cache")?.let(found::add)
        item(File(local, "NVIDIA/GLCache"), "NVIDIA OpenGL cache")?.let(found::add)
        item(File(local, "AMD/DxCache"), "AMD shader cache")?.let(found::add)
        return found
    }

    fun linuxCaches(p: CarePlaces): List<CareItem> {
        val own = setOf("dysklink", "DyskLink")
        val found = CareFiles.children(p.cache).filter { it.name !in own && it.name !in developerCacheNames }
            .map { CareItem(it, 0, CareFiles.shortPath(it.parentFile, p.home)) }
            .toMutableList()
        // Flatpak apps keep their cache inside their own folder.
        for (app in CareFiles.children(File(p.home, ".var/app"))) {
            item(File(app, "cache"), "${app.name} cache", CareFiles.shortPath(app, p.home))?.let(found::add)
        }
        return found
    }

    fun windowsLogs(p: CarePlaces): List<CareItem> {
        val local = p.localAppData ?: return emptyList()
        val dumps = File(local, "CrashDumps")
        val found = mutableListOf<CareItem>()
        CareFiles.children(dumps).takeIf { it.isNotEmpty() }?.let { found += CareItem(dumps, 0, "${it.size} crash dumps", title = "Crash dumps", members = it) }
        listOf("ReportArchive", "ReportQueue").map { File(local, "Microsoft/Windows/WER/$it") }.flatMap(CareFiles::children).takeIf { it.isNotEmpty() }?.let {
            found += CareItem(File(local, "Microsoft/Windows/WER"), 0, "${it.size} reports", title = "Windows error reports", members = it)
        }
        return found
    }

    fun linuxLogs(p: CarePlaces): List<CareItem> {
        val found = mutableListOf<CareItem>()
        CareFiles.children(p.home).filter { it.name.startsWith(".xsession-errors") }.forEach { found += CareItem(it, 0, "Desktop session log") }
        val xorg = CareFiles.children(File(p.dataHome, "xorg")).filter { it.name.endsWith(".log") || it.name.endsWith(".log.old") }
        if (xorg.isNotEmpty()) found += CareItem(File(p.dataHome, "xorg"), 0, "${xorg.size} logs", title = "X server logs", members = xorg)
        return found
    }

    fun developer(p: CarePlaces): List<CareItem> {
        val h = p.home
        val list = mutableListOf(
            item(File(h, ".gradle/caches"), "Gradle caches"),
            item(File(h, ".gradle/wrapper/dists"), "Gradle versions", "Downloaded again by projects that use them"),
            item(File(h, ".m2/repository"), "Maven repository", "Unticked: may hold artifacts you installed yourself", preselect = false),
            item(File(h, ".npm/_cacache"), "npm cache"),
            item(File(h, ".cargo/registry/cache"), "Cargo downloads"),
            item(File(h, ".cargo/registry/src"), "Cargo sources"),
            item(File(h, ".android/build-cache"), "Android build cache"),
            item(File(h, ".android/cache"), "Android SDK cache"),
            item(File(h, ".nuget/packages"), "NuGet packages"),
        )
        if (p.windows) {
            p.localAppData?.let { local ->
                list += item(File(local, "npm-cache/_cacache"), "npm cache")
                list += item(File(local, "pip/Cache"), "pip cache")
                list += item(File(local, "pip/cache"), "pip cache")
                list += item(File(local, "Yarn/Cache"), "Yarn cache")
            }
        } else {
            developerCacheNames.forEach { list += item(File(p.cache, it), "$it cache") }
        }
        return list.filterNotNull().map { it.copy(detail = it.detail.ifEmpty { CareFiles.shortPath(it.file, h) }) }
            .distinctBy { it.file.absolutePath.lowercase() }
    }

    /** Big files in the home folder, outside hidden folders, AppData, and DyskLink's own. */
    fun largeFiles(p: CarePlaces, now: Long, lastUsed: (File) -> Long? = CareFiles::lastUsed, minimum: Long = LARGE_FILE, limit: Int = 200): List<CareItem> {
        val found = mutableListOf<CareItem>()
        val skip = setOfNotNull(p.localAppData, p.appData, p.ownData, File(p.home, "AppData")).map { it.absolutePath.lowercase() }.toSet()
        runCatching {
            Files.walkFileTree(p.home.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (dir == p.home.toPath()) return FileVisitResult.CONTINUE
                    val name = dir.fileName?.toString().orEmpty()
                    if (name.startsWith(".") || dir.toFile().absolutePath.lowercase() in skip || name == "node_modules") return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile && attrs.size() >= minimum && !file.fileName.toString().startsWith(".")) {
                        val f = file.toFile()
                        val used = lastUsed(f)
                        found += CareItem(f, attrs.size(), CareFiles.shortPath(f.parentFile, p.home) + " · used " + CareFiles.ago(used, now), used)
                    }
                    return FileVisitResult.CONTINUE
                }
                override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
            })
        }
        return found.sortedByDescending { it.size }.take(limit)
    }
}

// Duplicates

data class DuplicateGroup(val id: String, val size: Long, val files: List<CareItem>) {
    val wasted: Long get() = size * (files.size - 1)
}

enum class KeepRule(val label: String) { Oldest("Keep the oldest"), Newest("Keep the newest") }

object Duplicates {
    /** Same size, then the same first 64 KB, then the same SHA-256 of the whole file. The first copy in a group is kept. */
    fun find(
        folders: List<File>, minimum: Long, keep: KeepRule = KeepRule.Oldest, limit: Int = 300_000,
        progress: (Int) -> Unit = {}, cancelled: () -> Boolean = { false },
    ): List<DuplicateGroup> {
        val bySize = HashMap<Long, MutableList<File>>()
        val seen = HashSet<String>()
        val seenKeys = HashSet<Any>()
        var count = 0
        outer@ for (folder in folders) {
            var stop = false
            runCatching {
                Files.walkFileTree(folder.toPath(), object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (cancelled()) { stop = true; return FileVisitResult.TERMINATE }
                        return if (dir != folder.toPath() && dir.fileName.toString().startsWith(".")) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                    }
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (!attrs.isRegularFile || attrs.size() < minimum || file.fileName.toString().startsWith(".")) return FileVisitResult.CONTINUE
                        // A folder picked twice, or one inside another, must not make a file its own copy.
                        val real = runCatching { file.toRealPath().toString() }.getOrDefault(file.toString())
                        if (!seen.add(real)) return FileVisitResult.CONTINUE
                        // Hard links are one file under several names.
                        attrs.fileKey()?.let { if (!seenKeys.add(it)) return FileVisitResult.CONTINUE }
                        bySize.getOrPut(attrs.size()) { mutableListOf() } += file.toFile()
                        count++
                        if (count % 500 == 0) progress(count)
                        if (count >= limit) { stop = true; return FileVisitResult.TERMINATE }
                        return FileVisitResult.CONTINUE
                    }
                    override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
                })
            }
            if (stop) { if (cancelled()) return emptyList(); break@outer }
        }
        progress(count)
        val groups = mutableListOf<DuplicateGroup>()
        for ((size, files) in bySize) {
            if (files.size < 2) continue
            if (cancelled()) return emptyList()
            val byHead = files.groupBy { hash(it, 64 * 1024) ?: UUID.randomUUID().toString() }
            for (same in byHead.values) {
                if (same.size < 2) continue
                val byFull = if (size <= 64 * 1024) mapOf(hash(same[0], 64 * 1024)!! to same) else same.groupBy { hash(it) ?: UUID.randomUUID().toString() }
                for ((digest, copies) in byFull) {
                    if (copies.size < 2) continue
                    val items = copies.map { CareItem(it, size, date = CareFiles.created(it)) }
                    groups += DuplicateGroup(digest, size, order(items, keep))
                }
            }
        }
        return groups.sortedByDescending { it.wasted }
    }

    /** The copy to keep first: the oldest or the newest, then by path so it's always the same one. */
    fun order(items: List<CareItem>, keep: KeepRule): List<CareItem> = when (keep) {
        KeepRule.Oldest -> items.sortedWith(compareBy<CareItem> { it.date ?: Long.MAX_VALUE }.thenBy { it.file.path })
        KeepRule.Newest -> items.sortedWith(compareByDescending<CareItem> { it.date ?: Long.MIN_VALUE }.thenBy { it.file.path })
    }

    fun hash(file: File, upTo: Int? = null): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            var read = 0L
            while (true) {
                val want = upTo?.let { minOf(buffer.size.toLong(), it - read).toInt() } ?: buffer.size
                if (want <= 0) break
                val n = input.read(buffer, 0, want)
                if (n <= 0) break
                digest.update(buffer, 0, n)
                read += n
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}

// The real PC

/** Runs programs, lists processes, and moves files to the Recycle Bin or the Trash. */
class RealCareSystem : CareSystem {
    override val windows = Platform.isWindows
    override val home = File(System.getProperty("user.home"))
    override fun env(name: String): String? = System.getenv(name)

    override fun onPath(program: String): Boolean {
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }
        val names = if (windows) listOf("$program.exe", "$program.cmd", "$program.bat", program) else listOf(program)
        return path.any { dir -> names.any { File(dir, it).let { f -> f.isFile && f.canExecute() } } }
    }

    override fun run(command: List<String>, timeoutMs: Long, env: Map<String, String>): ProcOut? = runCatching {
        val builder = ProcessBuilder(command)
        builder.environment().putAll(env)
        val process = builder.start()
        process.outputStream.close()
        var out = ""
        var err = ""
        val readOut = Thread { out = runCatching { String(process.inputStream.readBytes(), Charsets.UTF_8) }.getOrDefault("") }.apply { isDaemon = true; start() }
        val readErr = Thread { err = runCatching { String(process.errorStream.readBytes(), Charsets.UTF_8) }.getOrDefault("") }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return null
        }
        readOut.join(2_000)
        readErr.join(2_000)
        ProcOut(process.exitValue(), out, err)
    }.getOrNull()

    override fun processes(): List<Proc> {
        val own = ProcessHandle.current().pid()
        return ProcessHandle.allProcesses().use { stream ->
            stream.filter { it.pid() != own }.map { handle ->
                val info = handle.info()
                Proc(handle.pid(), info.command().orElse(""), info.commandLine().orElse(""))
            }.filter { it.command.isNotEmpty() }.toList()
        }
    }

    override fun quit(pids: List<Long>, force: Boolean): Boolean {
        if (pids.isEmpty()) return true
        if (windows && !force) {
            // Without /f, taskkill asks the app's windows to close, as clicking X does, so it can ask to save.
            val args = listOf("taskkill") + pids.flatMap { listOf("/pid", it.toString()) }
            return run(args, 15_000)?.code == 0
        }
        var all = true
        for (pid in pids) {
            val handle = ProcessHandle.of(pid).orElse(null) ?: continue
            val asked = if (force) handle.destroyForcibly() else handle.destroy()
            if (!asked) all = false
        }
        return all
    }

    override fun trash(files: List<File>): TrashOutcome {
        val moved = mutableListOf<File>()
        val failed = mutableListOf<File>()
        var problem: TrashProblem? = null
        for (file in files) {
            if (!file.exists() && !CareFiles.isLink(file)) { moved += file; continue }
            val result = if (windows) WindowsRecycleBin.recycle(file) else trashHere(file)
            when {
                result == null && !file.exists() && !CareFiles.isLink(file) -> moved += file
                result == TrashProblem.Cancelled -> { failed += file; problem = TrashProblem.Cancelled }
                else -> { failed += file; problem = problem ?: result ?: TrashProblem.Other }
            }
            if (problem == TrashProblem.Cancelled) {
                failed += files.drop(files.indexOf(file) + 1)
                break
            }
        }
        return TrashOutcome(moved, failed, problem)
    }

    /** Linux, and a Mac while developing: the desktop's own tool, then the Trash as freedesktop.org describes it. Never deletes. */
    private fun trashHere(file: File): TrashProblem? {
        val path = file.absolutePath
        if (onPath("gio") && run(listOf("gio", "trash", "--", path), 120_000)?.code == 0) return null
        if (!file.exists()) return null
        for (kio in listOf("kioclient6", "kioclient5", "kioclient")) {
            if (onPath(kio) && run(listOf(kio, "move", path, "trash:/"), 120_000)?.code == 0) return null
        }
        if (onPath("trash-put") && run(listOf("trash-put", "--", path), 120_000)?.code == 0) return null
        if (!file.exists()) return null
        runCatching {
            val desktop = java.awt.Desktop.getDesktop()
            if (java.awt.Desktop.isDesktopSupported() && desktop.isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH) && desktop.moveToTrash(file)) return null
        }
        return FreedesktopTrash.move(file, FreedesktopTrash.homeTrash(this))
    }

    override fun reveal(file: File) {
        runCatching {
            if (windows) {
                ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
            } else {
                val target = if (file.isDirectory) file else file.parentFile
                // The FileManager1 D-Bus call selects the file in Nautilus, Dolphin, and Nemo; xdg-open shows its folder.
                val selected = onPath("dbus-send") && run(
                    listOf("dbus-send", "--session", "--dest=org.freedesktop.FileManager1", "--type=method_call", "/org/freedesktop/FileManager1",
                        "org.freedesktop.FileManager1.ShowItems", "array:string:${file.toURI()}", "string:"), 5_000,
                )?.code == 0
                if (!selected) Platform.open(target)
            }
        }
    }
}

/** The Trash in the home folder, as the freedesktop.org Trash specification says: the file in files/, a note in info/. */
object FreedesktopTrash {
    fun homeTrash(sys: CareSystem): File =
        File(sys.env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: File(sys.home, ".local/share").path, "Trash")

    /** Null when moved. Only within the same drive as the Trash: a copy across drives isn't done, so nothing is lost halfway. */
    fun move(file: File, trash: File, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): TrashProblem? = runCatching {
        val files = File(trash, "files").apply { mkdirs() }
        val info = File(trash, "info").apply { mkdirs() }
        if (Files.getFileStore(files.toPath()) != Files.getFileStore(file.toPath().toAbsolutePath().parent)) return TrashProblem.NoTrash
        val base = file.name
        var name = base
        var n = 1
        var note: Path
        while (true) {
            note = File(info, "$name.trashinfo").toPath()
            // Made first, and only when it doesn't exist, so two moves can't pick the same name.
            val made = runCatching { Files.createFile(note); true }.getOrDefault(false)
            if (made && !File(files, name).exists()) break
            if (made) Files.deleteIfExists(note)
            n++
            name = "${base.substringBeforeLast('.', base)}.$n" + (if ('.' in base) "." + base.substringAfterLast('.') else "")
        }
        val encoded = file.absoluteFile.toPath().normalize().toString().split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20")
        }
        Files.writeString(note, "[Trash Info]\nPath=$encoded\nDeletionDate=${now.withNano(0)}\n")
        try {
            Files.move(file.toPath(), File(files, name).toPath(), StandardCopyOption.ATOMIC_MOVE)
            null
        } catch (e: Exception) {
            Files.deleteIfExists(note)
            TrashProblem.Other
        }
    }.getOrElse { TrashProblem.Other }
}

/**
 * The Recycle Bin through SHFileOperationW with FOF_ALLOWUNDO, as Explorer's Delete. FOF_WANTNUKEWARNING makes Windows
 * ask before deleting for good where there's no Recycle Bin (some USB and network drives).
 */
object WindowsRecycleBin {
    private const val FO_DELETE = 3
    private const val FOF_SILENT = 0x4
    private const val FOF_NOCONFIRMATION = 0x10
    private const val FOF_ALLOWUNDO = 0x40
    private const val FOF_NOERRORUI = 0x400
    private const val FOF_WANTNUKEWARNING = 0x4000

    @com.sun.jna.Structure.FieldOrder("hwnd", "wFunc", "pFrom", "pTo", "fFlags", "fAnyOperationsAborted", "hNameMappings", "lpszProgressTitle")
    class FileOp : com.sun.jna.Structure() {
        @JvmField var hwnd: com.sun.jna.Pointer? = null
        @JvmField var wFunc: Int = 0
        @JvmField var pFrom: com.sun.jna.Pointer? = null
        @JvmField var pTo: com.sun.jna.Pointer? = null
        @JvmField var fFlags: Short = 0
        @JvmField var fAnyOperationsAborted: Int = 0
        @JvmField var hNameMappings: com.sun.jna.Pointer? = null
        @JvmField var lpszProgressTitle: com.sun.jna.WString? = null
    }

    private interface Shell32 : com.sun.jna.Library {
        fun SHFileOperationW(op: FileOp): Int
    }

    private val shell: Shell32? by lazy { runCatching { com.sun.jna.Native.load("shell32", Shell32::class.java) }.getOrNull() }

    /** Null when the file is in the Recycle Bin. */
    fun recycle(file: File): TrashProblem? {
        val lib = shell ?: return fallback(file)
        return runCatching {
            // A list of paths, each ending in a zero, with one more zero at the end.
            val chars = file.absolutePath + "\u0000\u0000"
            val memory = com.sun.jna.Memory((chars.length * 2).toLong()).apply { clear(); setString(0, chars.dropLast(2), "UTF-16LE") }
            val op = FileOp().apply {
                wFunc = FO_DELETE
                pFrom = memory
                fFlags = (FOF_ALLOWUNDO or FOF_NOCONFIRMATION or FOF_SILENT or FOF_NOERRORUI or FOF_WANTNUKEWARNING).toShort()
            }
            val code = lib.SHFileOperationW(op)
            when {
                op.fAnyOperationsAborted != 0 -> TrashProblem.Cancelled
                code == 0 -> null
                code == 5 || code == 0x78 -> TrashProblem.NotAllowed
                else -> TrashProblem.Other
            }
        }.getOrElse { fallback(file) }
    }

    private fun fallback(file: File): TrashProblem? = runCatching {
        val desktop = java.awt.Desktop.getDesktop()
        if (desktop.isSupported(java.awt.Desktop.Action.MOVE_TO_TRASH) && desktop.moveToTrash(file)) null else TrashProblem.NoTrash
    }.getOrDefault(TrashProblem.NoTrash)
}
