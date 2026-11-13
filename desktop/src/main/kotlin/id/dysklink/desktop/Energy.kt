package id.dysklink.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Apps using significant energy, as the Mac's Battery page. Windows and Linux don't report energy per app, so it's
// estimated from CPU time (and GPU use on Windows where the counters are there): every 5 seconds the CPU each process
// used is read, processes are grouped into apps, and the last 15 seconds are averaged. Only process names are read, and
// nothing leaves this PC.

/** One process at one moment. [cpuNanos] is all the CPU time it has used, user and kernel. */
data class ProcSample(
    val pid: Long,
    val ppid: Long,
    /** When it started, in the system's own units: tells a reused pid from the old process. */
    val start: Long,
    val cpuNanos: Long,
    /** The executable's file name, such as "chrome.exe" or "firefox". */
    val image: String,
    /** The sandboxed app it belongs to, from the Linux cgroup: "flatpak:org.mozilla.firefox", "snap:spotify". */
    val sandbox: String? = null,
    /** Kernel threads and the Windows kernel's own entries: never an app. */
    val kernel: Boolean = false,
    /** The Windows Idle process: its time is idle time, not load. */
    val idle: Boolean = false,
)

enum class ImpactLevel(val label: String) { High("High"), Medium("Medium"), Low("Low") }

data class EnergyApp(
    val key: String,
    val name: String,
    /** Averaged CPU use in percent of one core, plus GPU use where it's read: about what the Mac's impact shows. */
    val impact: Double,
    val level: ImpactLevel,
    /** The app's estimated share of the battery's power, only while on battery. */
    val watts: Double? = null,
)

data class EnergyView(
    /** The top apps above the threshold, highest first. */
    val apps: List<EnergyApp> = emptyList(),
    /** The whole PC's CPU use in percent of all cores, averaged. */
    val loadPercent: Double = 0.0,
    /** What the battery gives now (from Battery.kt), only on battery. */
    val batteryWatts: Double? = null,
    /** The part of [batteryWatts] estimated to go to the load. */
    val loadWatts: Double? = null,
    val onBattery: Boolean = false,
    val gpu: Boolean = false,
    /** False until two samples are in. */
    val ready: Boolean = false,
)

object EnergyMath {
    /** Apps above this impact (percent of one core) are worth showing, as the Mac's 10. */
    const val SIGNIFICANT = 10.0
    const val TOP = 5
    const val WINDOW = 3

    /**
     * CPU time used between two readings of a counter. A counter that goes back has wrapped when it's [wrapAt] wide
     * (a 32-bit counter), else it was reset and nothing is counted.
     */
    fun delta(old: Long, new: Long, wrapAt: Long? = null): Long = when {
        new >= old -> new - old
        wrapAt != null -> new + wrapAt - old
        else -> 0
    }

    /** CPU time over wall time, as percent of one core: 200 is two cores busy. */
    fun corePercent(cpuNanos: Long, wallNanos: Long): Double = if (wallNanos <= 0) 0.0 else cpuNanos * 100.0 / wallNanos

    /** Percent of all cores, 0 to 100. */
    fun machinePercent(cpuNanos: Long, wallNanos: Long, cores: Int): Double =
        (corePercent(cpuNanos, wallNanos) / cores.coerceAtLeast(1)).coerceIn(0.0, 100.0)

    fun level(impact: Double): ImpactLevel = when {
        impact >= 50 -> ImpactLevel.High
        impact >= 20 -> ImpactLevel.Medium
        else -> ImpactLevel.Low
    }

    /**
     * The share of the battery's power that goes to the load. CPUs draw a lot as soon as they leave idle (boost), so it
     * rises with the square root of the load: a tenth of the cores busy is about a third of the power, all of them all
     * of it. The rest is the screen, memory and the idle floor.
     */
    fun loadWatts(batteryWatts: Double, loadPercent: Double): Double = batteryWatts * sqrt((loadPercent / 100).coerceIn(0.0, 1.0))

    /** An app's part of [loadWatts], by its share of all the CPU in use. */
    fun appWatts(loadWatts: Double, appCorePercent: Double, loadPercent: Double, cores: Int): Double {
        val totalCore = loadPercent * cores.coerceAtLeast(1)
        return if (totalCore <= 0) 0.0 else loadWatts * (appCorePercent / totalCore).coerceIn(0.0, 1.0)
    }

    fun top(averaged: Map<String, Double>, threshold: Double = SIGNIFICANT, count: Int = TOP): List<Pair<String, Double>> =
        averaged.entries.filter { it.value >= threshold }.sortedByDescending { it.value }.take(count).map { it.key to it.value }
}

/** The last few samples per app; an app missing from a sample counts as 0 then. */
class MovingAverage(private val window: Int = EnergyMath.WINDOW) {
    private val samples = ArrayDeque<Map<String, Double>>()

    fun add(sample: Map<String, Double>): Map<String, Double> {
        samples.addLast(sample)
        while (samples.size > window) samples.removeFirst()
        return current()
    }

    /** The average of what's in, without adding. */
    fun current(): Map<String, Double> {
        val keys = samples.flatMap { it.keys }.toSet()
        return keys.associateWith { k -> samples.sumOf { it[k] ?: 0.0 } / samples.size }
    }

    val size: Int get() = samples.size

    fun clear() = samples.clear()
}

/** Which app a process belongs to. */
object EnergyGroups {
    /** Windows' own entries that aren't apps; their time counts as load, Idle's doesn't. */
    private val windowsKernel = setOf("system", "registry", "memory compression", "secure system", "system interrupts")

    /** Processes that work for the app that started them, so they're counted under it. */
    private val helperNames = setOf(
        "msedgewebview2.exe", "conhost.exe", "crashpad_handler", "crashpad_handler.exe", "chrome_crashpad_handler",
        "werfault.exe", "cefsharp.browsersubprocess.exe", "qtwebengineprocess", "qtwebengineprocess.exe", "webkitwebprocess",
        "webkitnetworkprocess", "electron_crashpad_handler",
    )

    /** Parents that start everything, so a helper under them stays on its own. */
    private val launchers = setOf(
        "explorer.exe", "svchost.exe", "services.exe", "wininit.exe", "winlogon.exe", "userinit.exe", "sihost.exe",
        "systemd", "init", "kthreadd", "gnome-shell", "plasmashell", "kwin_wayland", "kwin_x11", "xdg-desktop-portal",
    )

    fun isHelper(image: String): Boolean {
        val n = image.lowercase()
        return n in helperNames || n.endsWith("helper") || n.endsWith("helper.exe") || n.contains("crashpad")
    }

    fun isWindowsKernel(image: String, pid: Long) = pid == 4L || image.lowercase() in windowsKernel

    /** The app key of every process, or no entry for kernel ones. */
    fun keys(procs: List<ProcSample>, windows: Boolean): Map<Long, String> {
        val byPid = procs.associateBy { it.pid }
        val out = HashMap<Long, String>()
        for (p in procs) {
            if (p.kernel || p.idle) continue
            p.sandbox?.let { out[p.pid] = it; continue }
            var owner = p
            var hops = 0
            // A helper goes to the nearest parent that isn't a helper, unless that's a launcher or the system.
            while (isHelper(owner.image) && hops < 16) {
                val parent = byPid[owner.ppid] ?: break
                if (parent.kernel || parent.idle || parent.image.lowercase() in launchers || parent.pid == owner.pid) break
                owner = parent
                hops++
            }
            if (owner.sandbox != null) { out[p.pid] = owner.sandbox!!; continue }
            out[p.pid] = imageKey(owner.image, windows)
        }
        return out
    }

    fun imageKey(image: String, windows: Boolean): String = if (windows) image.lowercase() else image

    /** A Linux process's sandboxed app from its /proc/<pid>/cgroup, or null. */
    fun sandboxFromCgroup(cgroup: String): String? {
        for (line in cgroup.lineSequence()) {
            val path = line.substringAfter("::", line.substringAfterLast(':'))
            for (part in path.split('/').asReversed()) {
                // app-flatpak-org.mozilla.firefox-12345.scope
                Regex("""^app-flatpak-(.+?)-\d+\.scope$""").find(part)?.let { return "flatpak:" + it.groupValues[1] }
                // snap.spotify.spotify-<uuid>.scope or snap.foo.daemon.service
                Regex("""^snap\.([^.]+)\..+\.(scope|service)$""").find(part)?.let { return "snap:" + it.groupValues[1] }
            }
        }
        return null
    }
}

/** Reads every process now. */
interface ProcSource {
    fun read(): List<ProcSample>
    /** A readable name for an app key, from what the system knows. */
    fun name(key: String, sample: ProcSample?): String
    val cores: Int get() = Runtime.getRuntime().availableProcessors()
}

/** Names of well-known apps, by executable, where the file says little ("chrome" or "code"). */
object KnownApps {
    private val names = mapOf(
        "chrome" to "Google Chrome", "chrome.exe" to "Google Chrome", "msedge" to "Microsoft Edge", "msedge.exe" to "Microsoft Edge",
        "firefox" to "Firefox", "firefox.exe" to "Firefox", "firefox-bin" to "Firefox", "chromium" to "Chromium", "chromium-browser" to "Chromium",
        "brave" to "Brave", "brave.exe" to "Brave", "opera" to "Opera", "opera.exe" to "Opera", "vivaldi-bin" to "Vivaldi",
        "code" to "Visual Studio Code", "code.exe" to "Visual Studio Code", "slack" to "Slack", "slack.exe" to "Slack",
        "discord" to "Discord", "discord.exe" to "Discord", "spotify" to "Spotify", "spotify.exe" to "Spotify",
        "teams.exe" to "Microsoft Teams", "ms-teams.exe" to "Microsoft Teams", "zoom" to "Zoom", "zoom.exe" to "Zoom",
        "obs" to "OBS Studio", "obs64.exe" to "OBS Studio", "steam" to "Steam", "steam.exe" to "Steam",
        "explorer.exe" to "File Explorer", "dwm.exe" to "Desktop Window Manager", "msmpeng.exe" to "Microsoft Defender",
        "gnome-shell" to "GNOME Shell", "plasmashell" to "Plasma", "kwin_wayland" to "KWin", "kwin_x11" to "KWin",
        "xorg" to "X server", "pipewire" to "PipeWire", "pulseaudio" to "PulseAudio", "java" to "Java", "dysklink" to "DyskLink",
    )

    fun name(image: String): String? = names[image.lowercase()]

    /** "gnome-terminal-server" to "Gnome terminal server", "org.mozilla.firefox" to "Firefox". */
    fun pretty(raw: String): String {
        val base = raw.substringAfterLast('.').ifEmpty { raw }.removeSuffix(".exe")
        val words = base.replace('_', ' ').replace('-', ' ').trim()
        return words.replaceFirstChar { it.uppercase() }.ifEmpty { raw }
    }
}

/** Turns samples into the view: deltas, groups, the moving average, the top apps, and the watts. */
class EnergyTracker(private val windows: Boolean, private val cores: Int) {
    private var last: Map<Pair<Long, Long>, Long>? = null
    private var lastAt = 0L
    private val apps = MovingAverage()
    private val load = MovingAverage()

    /** One interval: percent of one core per app, and the whole PC's load in percent of all cores. Null the first time. */
    fun step(procs: List<ProcSample>, nowNanos: Long, keys: Map<Long, String> = EnergyGroups.keys(procs, windows)): EnergyStep? {
        val current = procs.associate { (it.pid to it.start) to it.cpuNanos }
        val before = last
        val wall = nowNanos - lastAt
        last = current
        lastAt = nowNanos
        if (before == null || wall <= 0) return null
        val cap = wall * cores
        val perApp = HashMap<String, Double>()
        var busy = 0L
        for (p in procs) {
            if (p.idle) continue
            val old = before[p.pid to p.start]
            // A process that started since the last sample counts all its time.
            val used = (if (old == null) p.cpuNanos else EnergyMath.delta(old, p.cpuNanos)).coerceIn(0, cap)
            busy += used
            val key = keys[p.pid] ?: continue
            perApp[key] = (perApp[key] ?: 0.0) + EnergyMath.corePercent(used, wall)
        }
        return EnergyStep(perApp, EnergyMath.machinePercent(busy.coerceAtMost(cap), wall, cores))
    }

    /** Adds a step to the averages and ranks the apps. [gpu] is the averaged GPU percent per app, where it's read. */
    fun view(step: EnergyStep, batteryWatts: Double?, onBattery: Boolean, gpu: Map<String, Double>? = null, name: (String) -> String): EnergyView {
        val cpu = apps.add(step.apps)
        val impact = HashMap(cpu)
        gpu?.forEach { (k, v) -> impact[k] = (impact[k] ?: 0.0) + v }
        val loadPct = load.add(mapOf("" to step.load))[""] ?: 0.0
        val watts = batteryWatts?.takeIf { onBattery && it > 0 }
        val loadW = watts?.let { EnergyMath.loadWatts(it, loadPct) }
        val top = EnergyMath.top(impact).map { (key, value) ->
            EnergyApp(key, name(key), value, EnergyMath.level(value), loadW?.let { EnergyMath.appWatts(it, cpu[key] ?: 0.0, loadPct, cores) })
        }
        return EnergyView(top, loadPct, watts, loadW, onBattery, gpu = gpu != null, ready = true)
    }

    fun reset() { last = null; apps.clear(); load.clear() }
}

/** One interval's CPU use: percent of one core per app, and [load] in percent of all cores. */
data class EnergyStep(val apps: Map<String, Double>, val load: Double)

// Linux: /proc.

class LinuxProcSource(private val root: File = File("/proc"), private val apps: DesktopApps = DesktopApps()) : ProcSource {
    /** USER_HZ: 100 on every Linux the app runs on. */
    private val tickNanos = 10_000_000L
    private val cache = HashMap<Pair<Long, Long>, Pair<String, String?>>()

    override fun read(): List<ProcSample> {
        val dirs = root.listFiles { f -> f.name.all(Char::isDigit) } ?: return emptyList()
        val seen = HashSet<Pair<Long, Long>>()
        val out = ArrayList<ProcSample>(dirs.size)
        for (dir in dirs) {
            val stat = runCatching { File(dir, "stat").readText() }.getOrNull() ?: continue
            val s = parseStat(stat, tickNanos) ?: continue
            val id = s.pid to s.start
            seen += id
            val (image, sandbox) = cache.getOrPut(id) {
                val exe = runCatching { java.nio.file.Files.readSymbolicLink(File(dir, "exe").toPath()).fileName.toString() }.getOrNull()
                    ?.removeSuffix(" (deleted)")
                val cmd = runCatching { File(dir, "cmdline").readText().substringBefore('\u0000').substringAfterLast('/') }.getOrNull()
                    ?.takeIf { it.isNotBlank() && !it.contains(' ') }
                val image = exe ?: cmd ?: s.image
                image to runCatching { EnergyGroups.sandboxFromCgroup(File(dir, "cgroup").readText()) }.getOrNull()
            }
            out += s.copy(image = image, sandbox = sandbox)
        }
        cache.keys.retainAll(seen)
        return out
    }

    override fun name(key: String, sample: ProcSample?): String = when {
        key.startsWith("flatpak:") -> key.removePrefix("flatpak:").let { id -> apps.byId(id) ?: KnownApps.name(id.substringAfterLast('.')) ?: KnownApps.pretty(id) }
        key.startsWith("snap:") -> key.removePrefix("snap:").let { n -> apps.byId(n) ?: KnownApps.name(n) ?: KnownApps.pretty(n) }
        else -> KnownApps.name(key) ?: apps.byExec(key) ?: KnownApps.pretty(key)
    }

    companion object {
        private const val PF_KTHREAD = 0x00200000L

        /** /proc/<pid>/stat; the name sits in parentheses and may hold spaces or parentheses itself. */
        fun parseStat(text: String, tickNanos: Long = 10_000_000L): ProcSample? {
            val open = text.indexOf('(')
            val close = text.lastIndexOf(')')
            if (open < 0 || close < open) return null
            val pid = text.substring(0, open).trim().toLongOrNull() ?: return null
            val comm = text.substring(open + 1, close)
            // After the name: state(3) ppid(4) ... flags(9) ... utime(14) stime(15) ... starttime(22); index 0 is state.
            val f = text.substring(close + 1).trim().split(' ')
            if (f.size < 20) return null
            val ppid = f[1].toLongOrNull() ?: return null
            val flags = f[6].toLongOrNull() ?: 0
            val utime = f[11].toULongOrNull()?.toLong() ?: return null
            val stime = f[12].toULongOrNull()?.toLong() ?: return null
            val start = f[19].toLongOrNull() ?: 0
            val kernel = pid == 2L || ppid == 2L || flags and PF_KTHREAD != 0L
            return ProcSample(pid, ppid, start, (utime + stime) * tickNanos, comm, kernel = kernel)
        }
    }
}

/** Names from the .desktop files, by id and by the program they start. Read once, again after ten minutes. */
class DesktopApps(private val dirs: List<File> = defaultDirs()) {
    private var byId: Map<String, String> = emptyMap()
    private var byExec: Map<String, String> = emptyMap()
    private var loadedAt = 0L

    fun byId(id: String): String? { load(); return byId[id.lowercase()] }
    fun byExec(exe: String): String? { load(); return byExec[exe.lowercase()] }

    private fun load() {
        val now = System.currentTimeMillis()
        if (now - loadedAt < 600_000) return
        loadedAt = now
        val ids = HashMap<String, String>()
        val execs = HashMap<String, String>()
        for (dir in dirs) {
            dir.listFiles { f -> f.name.endsWith(".desktop") }?.forEach { file ->
                val entry = runCatching { parse(file.readText()) }.getOrNull() ?: return@forEach
                val id = file.name.removeSuffix(".desktop").lowercase()
                ids.putIfAbsent(id, entry.first)
                // A snap's desktop file is "spotify_spotify.desktop".
                ids.putIfAbsent(id.substringBefore('_'), entry.first)
                entry.second?.let { execs.putIfAbsent(it.lowercase(), entry.first) }
                entry.third?.let { execs.putIfAbsent(it.lowercase(), entry.first) }
            }
        }
        byId = ids
        byExec = execs
    }

    companion object {
        fun defaultDirs(): List<File> {
            val home = System.getProperty("user.home")
            val data = (System.getenv("XDG_DATA_DIRS")?.takeIf { it.isNotBlank() } ?: "/usr/local/share:/usr/share").split(':')
            return (listOf("$home/.local/share") + data + listOf("/var/lib/flatpak/exports/share", "$home/.local/share/flatpak/exports/share"))
                .map { File(it, "applications") } + File("/var/lib/snapd/desktop/applications")
        }

        /** Name, the program in Exec, and StartupWMClass, from the [Desktop Entry] group. */
        fun parse(text: String): Triple<String, String?, String?>? {
            var inEntry = false
            var name: String? = null
            var exec: String? = null
            var wmClass: String? = null
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.startsWith("[")) { inEntry = line == "[Desktop Entry]"; continue }
                if (!inEntry) continue
                when {
                    line.startsWith("Name=") -> name = name ?: line.removePrefix("Name=")
                    line.startsWith("Exec=") -> exec = exec ?: line.removePrefix("Exec=").trim().split(' ')
                        .firstOrNull { it.isNotEmpty() && it != "env" && !it.contains('=') }?.trim('"')?.substringAfterLast('/')
                    line.startsWith("StartupWMClass=") -> wmClass = line.removePrefix("StartupWMClass=")
                }
            }
            return name?.let { Triple(it, exec, wmClass) }
        }
    }
}

// Windows: one NtQuerySystemInformation call for every process, names from the executables' version info.

class WindowsProcSource : ProcSource {
    @Suppress("FunctionName")
    private interface Ntdll : StdCallLibrary {
        fun NtQuerySystemInformation(cls: Int, buffer: Pointer?, length: Int, returned: IntByReference?): Int
    }

    @Suppress("FunctionName")
    private interface Kernel32 : StdCallLibrary {
        fun OpenProcess(access: Int, inherit: Boolean, pid: Int): Pointer?
        fun QueryFullProcessImageNameW(process: Pointer, flags: Int, name: CharArray, size: IntByReference): Boolean
        fun CloseHandle(handle: Pointer): Boolean
    }

    @Suppress("FunctionName")
    private interface Version : StdCallLibrary {
        fun GetFileVersionInfoSizeW(file: WString, handle: IntByReference?): Int
        fun GetFileVersionInfoW(file: WString, handle: Int, length: Int, data: Pointer): Boolean
        fun VerQueryValueW(block: Pointer, sub: WString, out: PointerByReference, length: IntByReference): Boolean
    }

    private val nt by lazy { runCatching { Native.load("ntdll", Ntdll::class.java) }.getOrNull() }
    private val k32 by lazy { runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull() }
    private val ver by lazy { runCatching { Native.load("version", Version::class.java) }.getOrNull() }
    private var buffer = Memory(512 * 1024)
    private val latest = HashMap<String, ProcSample>()
    private val names = HashMap<String, String>()

    override fun read(): List<ProcSample> {
        val api = nt ?: return emptyList()
        if (Native.POINTER_SIZE != 8) return emptyList()
        val needed = IntByReference()
        var status = api.NtQuerySystemInformation(5, buffer, buffer.size().toInt(), needed)
        var tries = 0
        // STATUS_INFO_LENGTH_MISMATCH: processes came and went; a bigger buffer and again.
        while (status == 0xC0000004.toInt() && tries++ < 4) {
            buffer = Memory(maxOf(buffer.size() * 2, needed.value.toLong() + 64 * 1024))
            status = api.NtQuerySystemInformation(5, buffer, buffer.size().toInt(), needed)
        }
        if (status != 0) return emptyList()
        val out = parseProcessInfo(buffer)
        latest.clear()
        out.forEach { latest.putIfAbsent(it.image.lowercase(), it) }
        return out
    }

    override fun name(key: String, sample: ProcSample?): String {
        names[key]?.let { return it }
        KnownApps.name(key)?.let { names[key] = it; return it }
        val pid = (sample ?: latest[key])?.pid
        val described = pid?.let { imagePath(it) }?.let { describe(it) }
        // Only remembered once read: a process that couldn't be opened may be readable next time.
        return described?.also { names[key] = it } ?: KnownApps.pretty(key)
    }

    private fun imagePath(pid: Long): String? = runCatching {
        val k = k32 ?: return null
        val handle = k.OpenProcess(0x1000 /* PROCESS_QUERY_LIMITED_INFORMATION */, false, pid.toInt()) ?: return null
        try {
            val chars = CharArray(1024)
            val size = IntByReference(chars.size)
            if (k.QueryFullProcessImageNameW(handle, 0, chars, size)) String(chars, 0, size.value) else null
        } finally { k.CloseHandle(handle) }
    }.getOrNull()

    /** FileDescription from the version info, as Task Manager shows it. */
    private fun describe(path: String): String? = runCatching {
        val v = ver ?: return null
        val size = v.GetFileVersionInfoSizeW(WString(path), null)
        if (size <= 0) return null
        val data = Memory(size.toLong())
        if (!v.GetFileVersionInfoW(WString(path), 0, size, data)) return null
        val out = PointerByReference()
        val len = IntByReference()
        val lang = if (v.VerQueryValueW(data, WString("\\VarFileInfo\\Translation"), out, len) && len.value >= 4) {
            val p = out.value
            "%04x%04x".format(p.getShort(0).toInt() and 0xFFFF, p.getShort(2).toInt() and 0xFFFF)
        } else "040904b0"
        if (!v.VerQueryValueW(data, WString("\\StringFileInfo\\$lang\\FileDescription"), out, len) || len.value <= 1) return null
        out.value.getWideString(0).trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    companion object {
        /** SYSTEM_PROCESS_INFORMATION entries on 64-bit Windows. CreateTime, UserTime and KernelTime are in 100 ns. */
        fun parseProcessInfo(buf: Pointer): List<ProcSample> {
            val out = ArrayList<ProcSample>(400)
            var offset = 0L
            while (true) {
                val next = buf.getInt(offset).toLong() and 0xFFFFFFFFL
                val create = buf.getLong(offset + 0x20)
                val user = buf.getLong(offset + 0x28)
                val kernel = buf.getLong(offset + 0x30)
                val nameLength = buf.getShort(offset + 0x38).toInt() and 0xFFFF
                val namePtr = buf.getPointer(offset + 0x40)
                val pid = buf.getLong(offset + 0x50)
                val ppid = buf.getLong(offset + 0x58)
                val image = if (namePtr == null || nameLength == 0) (if (pid == 0L) "Idle" else "System")
                else String(namePtr.getCharArray(0, nameLength / 2))
                out += ProcSample(pid, ppid, create, (user + kernel) * 100, image,
                    kernel = EnergyGroups.isWindowsKernel(image, pid), idle = pid == 0L)
                if (next == 0L) break
                offset += next
            }
            return out
        }
    }
}

/** GPU use per process on Windows, from the "GPU Engine" counters through PDH: in-process, no PowerShell. */
class WindowsGpu {
    @Suppress("FunctionName")
    private interface Pdh : StdCallLibrary {
        fun PdhOpenQueryW(source: WString?, user: Pointer?, query: PointerByReference): Int
        fun PdhAddEnglishCounterW(query: Pointer, path: WString, user: Pointer?, counter: PointerByReference): Int
        fun PdhCollectQueryData(query: Pointer): Int
        fun PdhGetFormattedCounterArrayW(counter: Pointer, format: Int, size: IntByReference, count: IntByReference, items: Pointer?): Int
    }

    private val pdh by lazy { runCatching { Native.load("pdh", Pdh::class.java) }.getOrNull() }
    private var query: Pointer? = null
    private var counter: Pointer? = null
    private var failed = false

    /** Percent per pid since the last call; null until the second call, or when the counters aren't there. */
    fun read(): Map<Long, Double>? = runCatching {
        if (failed || Native.POINTER_SIZE != 8) return null
        val api = pdh ?: run { failed = true; return null }
        if (query == null) {
            val q = PointerByReference()
            if (api.PdhOpenQueryW(null, null, q) != 0) { failed = true; return null }
            val c = PointerByReference()
            if (api.PdhAddEnglishCounterW(q.value, WString("\\GPU Engine(*)\\Utilization Percentage"), null, c) != 0) { failed = true; return null }
            query = q.value
            counter = c.value
            api.PdhCollectQueryData(q.value)
            return null
        }
        if (api.PdhCollectQueryData(query!!) != 0) return null
        val size = IntByReference(0)
        val count = IntByReference(0)
        val PDH_FMT_DOUBLE = 0x00000200
        api.PdhGetFormattedCounterArrayW(counter!!, PDH_FMT_DOUBLE, size, count, null)
        if (size.value <= 0) return emptyMap()
        val items = Memory(size.value.toLong())
        if (api.PdhGetFormattedCounterArrayW(counter!!, PDH_FMT_DOUBLE, size, count, items) != 0) return null
        // PDH_FMT_COUNTERVALUE_ITEM_W: name pointer, then status (4), padding (4), the double.
        val pairs = (0 until count.value).mapNotNull { i ->
            val base = i * 24L
            val name = items.getPointer(base)?.getWideString(0) ?: return@mapNotNull null
            val status = items.getInt(base + 8)
            if (status != 0 && status != 1) null else name to items.getDouble(base + 16)
        }
        gpuByPid(pairs)
    }.getOrNull()

    companion object {
        /**
         * Instances are named "pid_1234_luid_0x0_0xD1E3_phys_0_eng_0_engtype_3D". A process's busiest engine type is
         * its use, as Task Manager's GPU column shows: engines of one type add up, types don't.
         */
        fun gpuByPid(values: List<Pair<String, Double>>): Map<Long, Double> {
            val perType = HashMap<Pair<Long, String>, Double>()
            for ((name, value) in values) {
                val pid = Regex("""pid_(\d+)_""").find(name)?.groupValues?.get(1)?.toLongOrNull() ?: continue
                val type = name.substringAfter("engtype_", "")
                perType[pid to type] = (perType[pid to type] ?: 0.0) + value.coerceAtLeast(0.0)
            }
            return perType.entries.groupBy({ it.key.first }, { it.value }).mapValues { (_, v) -> v.max().coerceAtMost(100.0) }
                .filterValues { it > 0 }
        }
    }
}

/** GPU percent per app from per-pid figures; engines of one app add up to at most 100. */
fun gpuByApp(byPid: Map<Long, Double>, keys: Map<Long, String>): Map<String, Double> {
    val out = HashMap<String, Double>()
    byPid.forEach { (pid, pct) -> keys[pid]?.let { out[it] = ((out[it] ?: 0.0) + pct).coerceAtMost(100.0) } }
    return out
}

/**
 * Samples every 5 seconds while someone looks (the Battery card is on screen) or, for the tray's line, while on
 * battery. Waits otherwise, so it costs nothing on the charger with the window closed.
 */
class EnergyMonitor(
    private val scope: CoroutineScope,
    private val battery: StateFlow<BatteryView?>,
    private val source: ProcSource? = when {
        Platform.isWindows -> WindowsProcSource()
        Platform.isLinux -> LinuxProcSource()
        else -> null
    },
) {
    private val _view = MutableStateFlow<EnergyView?>(null)
    val view: StateFlow<EnergyView?> = _view
    private val viewers = MutableStateFlow(0)
    private val tray = MutableStateFlow(false)
    private var job: Job? = null

    /** The Battery card coming on screen (true) and going (false). */
    fun watch(on: Boolean) = viewers.update { (it + if (on) 1 else -1).coerceAtLeast(0) }

    /** Whether the tray menu shows the top app, which keeps sampling on battery. */
    fun setTray(on: Boolean) { tray.value = on }

    private fun wanted(v: Int, t: Boolean, b: BatteryView?) = b != null && (v > 0 || (t && b.reading.state == ChargeState.Discharging))

    fun start() {
        val src = source ?: return
        if (job != null) return
        job = scope.launch(Dispatchers.IO) {
            val windows = Platform.isWindows
            val tracker = EnergyTracker(windows, src.cores)
            val gpu = if (windows) WindowsGpu() else null
            val gpuAverage = MovingAverage(2)
            var gpuAt = 0L
            while (true) {
                if (!wanted(viewers.value, tray.value, battery.value)) {
                    _view.value = null
                    tracker.reset()
                    gpuAverage.clear()
                    gpuAt = 0L
                    combine(viewers, tray, battery) { v, t, b -> wanted(v, t, b) }.first { it }
                }
                val reading = battery.value?.reading ?: continue
                val now = System.nanoTime()
                val procs = runCatching { src.read() }.getOrDefault(emptyList())
                val keys = EnergyGroups.keys(procs, windows)
                // The GPU every 15 seconds, and only while the card is on screen.
                if (gpu != null && viewers.value > 0 && now - gpuAt >= 15_000_000_000L) {
                    gpuAt = now
                    gpu.read()?.let { gpuAverage.add(gpuByApp(it, keys)) }
                }
                val gpuNow = if (gpuAverage.size > 0) gpuAverage.current() else null
                tracker.step(procs, now, keys)?.let { step ->
                    val byKey = procs.filter { keys[it.pid] != null }.associateBy { keys[it.pid]!! }
                    val onBattery = reading.state == ChargeState.Discharging
                    _view.value = tracker.view(step, reading.watts, onBattery, gpuNow) { key ->
                        runCatching { src.name(key, byKey[key]) }.getOrElse { KnownApps.pretty(key) }
                    }
                }
                delay(5_000)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}
