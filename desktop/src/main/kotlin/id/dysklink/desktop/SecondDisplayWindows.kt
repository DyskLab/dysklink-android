@file:Suppress("FunctionName")

package id.dysklink.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import id.phonelink.android.core.CoreLog
import id.phonelink.android.core.PcExtendFailed
import id.phonelink.android.core.PcExtraDisplay
import java.awt.Rectangle
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

// The phone as a second display on Windows. Windows adds a monitor only through an indirect display driver (IddCx).
// DyskLink uses the open-source, signed Virtual Display Driver (github.com/VirtualDrivers/Virtual-Display-Driver, MIT),
// release 25.7.23, installed the way that project's own silent-install.ps1 does it: its signing certificates from
// mttvdd.cat into Trusted Publishers, then `nefconw install MttVDD.inf Root\MttVDD` (nefcon v1.14.0, MIT). CI puts
// both, checked by SHA-256, in resources/windows-x64/vdd. The driver reads C:\VirtualDisplayDriver\vdd_settings.xml
// (its default place) and reloads it on RELOAD_DRIVER through its pipe \\.\pipe\MTTVirtualDisplayPipe, which anyone
// may write to. The first resolution in the file is the monitor's preferred mode. Between uses the monitor is taken
// off the desktop (ChangeDisplaySettingsEx with no size), so Windows doesn't extend onto a monitor nobody sees.

object Vdd {
    const val RELEASE = "25.7.23"
    const val HARDWARE_ID = "Root\\MttVDD"
    const val DISPLAY_CLASS = "4D36E968-E325-11CE-BFC1-08002BE10318"
    const val CONFIG_DIR = "C:\\VirtualDisplayDriver"
    const val SETTINGS = "vdd_settings.xml"
    const val PIPE = "\\\\.\\pipe\\MTTVirtualDisplayPipe"
    const val ADAPTER_NAME = "Virtual Display Driver"

    /** The driver's folder in the app's resources (jpackage's app folder), or null in a build without it. */
    fun bundled(resourcesDir: String? = System.getProperty("compose.application.resources.dir")): File? =
        resourcesDir?.let { File(it, "vdd") }?.takeIf { File(it, "nefconw.exe").isFile && File(it, "VirtualDisplayDriver/MttVDD.inf").isFile }

    /** Whether a display source belongs to the driver, by its adapter's name or hardware ID. */
    fun isOurs(source: DisplaySource): Boolean =
        source.adapter.equals(ADAPTER_NAME, ignoreCase = true) || source.deviceId.contains("MttVDD", ignoreCase = true)
}

/** vdd_settings.xml with one monitor and the phone's size as its only (so preferred) resolution; everything else kept. */
object VddSettings {
    fun write(existing: String?, width: Int, height: Int, refresh: Int = 60): String {
        val resolutions = "<resolutions>\n        <resolution>\n            <width>$width</width>\n            <height>$height</height>\n" +
            "            <refresh_rate>$refresh</refresh_rate>\n        </resolution>\n    </resolutions>"
        val base = existing?.takeIf { "<vdd_settings>" in it && "</vdd_settings>" in it } ?: TEMPLATE
        var out = Regex("""<count>\s*\d+\s*</count>""").replaceFirst(base, "<count>1</count>")
        out = if (Regex("""<resolutions>[\s\S]*?</resolutions>""").containsMatchIn(out)) {
            Regex("""<resolutions>[\s\S]*?</resolutions>""").replaceFirst(out, Regex.escapeReplacement(resolutions))
        } else {
            out.replace("</vdd_settings>", "    $resolutions\n</vdd_settings>")
        }
        // Global refresh rates repeat every resolution at each rate; one is kept so the mode list stays one size.
        out = Regex("""<global>[\s\S]*?</global>""").replaceFirst(out, Regex.escapeReplacement("<global>\n        <g_refresh_rate>$refresh</g_refresh_rate>\n    </global>"))
        return out
    }

    /** The resolutions in a settings file, in order. */
    fun resolutions(xml: String): List<Pair<Int, Int>> = Regex("""<resolution>\s*<width>\s*(\d+)\s*</width>\s*<height>\s*(\d+)\s*</height>""")
        .findAll(xml).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()

    /** The driver's own default, for when there's no file yet. */
    val TEMPLATE = """
        <?xml version='1.0' encoding='utf-8'?>
        <vdd_settings>
            <monitors>
                <count>1</count>
            </monitors>
            <gpu>
                <friendlyname>default</friendlyname>
            </gpu>
            <global>
                <g_refresh_rate>60</g_refresh_rate>
            </global>
            <resolutions>
            </resolutions>
            <options>
                <CustomEdid>false</CustomEdid>
                <PreventSpoof>false</PreventSpoof>
                <EdidCeaOverride>false</EdidCeaOverride>
                <HardwareCursor>true</HardwareCursor>
                <SDR10bit>false</SDR10bit>
                <HDRPlus>false</HDRPlus>
                <logging>false</logging>
                <debuglogging>false</debuglogging>
            </options>
        </vdd_settings>
    """.trimIndent() + "\n"
}

/** One display source from EnumDisplayDevices: \\.\DISPLAYn, its adapter's name and ID, and whether it's on the desktop. */
data class DisplaySource(val name: String, val adapter: String, val deviceId: String, val flags: Int) {
    val attached: Boolean get() = flags and ATTACHED != 0
    val primary: Boolean get() = flags and PRIMARY != 0

    companion object {
        const val ATTACHED = 0x1
        const val PRIMARY = 0x4
    }
}

/**
 * DISPLAY_DEVICEW and DEVMODEW as bytes, for EnumDisplayDevicesW, EnumDisplaySettingsExW and ChangeDisplaySettingsExW
 * through plain JNA: DISPLAY_DEVICEW is cb, DeviceName[32], DeviceString[128], StateFlags, DeviceID[128],
 * DeviceKey[128] (840 bytes); DEVMODEW is 220 bytes with dmSize at 68, dmFields at 72, dmPosition at 76 and 80,
 * dmBitsPerPel at 168, dmPelsWidth at 172, dmPelsHeight at 176, dmDisplayFrequency at 184.
 */
object WinStructs {
    const val DISPLAY_DEVICE_SIZE = 840
    const val DEVMODE_SIZE = 220
    const val DM_POSITION = 0x20
    const val DM_PELSWIDTH = 0x80000
    const val DM_PELSHEIGHT = 0x100000
    const val ENUM_CURRENT_SETTINGS = -1
    const val CDS_UPDATEREGISTRY = 0x1
    const val CDS_NORESET = 0x10000000
    const val DISP_CHANGE_SUCCESSFUL = 0

    private fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private fun wide(bytes: ByteArray, offset: Int, chars: Int): String {
        val b = le(bytes)
        val sb = StringBuilder()
        for (i in 0 until chars) {
            val c = b.getChar(offset + i * 2)
            if (c == '\u0000') break
            sb.append(c)
        }
        return sb.toString()
    }

    fun emptyDisplayDevice(): ByteArray = ByteArray(DISPLAY_DEVICE_SIZE).also { le(it).putInt(0, DISPLAY_DEVICE_SIZE) }

    fun displayDevice(bytes: ByteArray): DisplaySource =
        DisplaySource(wide(bytes, 4, 32), wide(bytes, 68, 128), wide(bytes, 328, 128), le(bytes).getInt(324))

    fun emptyDevMode(): ByteArray = ByteArray(DEVMODE_SIZE).also { le(it).putShort(68, DEVMODE_SIZE.toShort()) }

    /** A DEVMODEW that puts a display at [x], [y] with [width] × [height]; all zero takes it off the desktop. */
    fun devMode(x: Int, y: Int, width: Int, height: Int): ByteArray = emptyDevMode().also {
        val b = le(it)
        b.putInt(72, DM_POSITION or DM_PELSWIDTH or DM_PELSHEIGHT)
        b.putInt(76, x)
        b.putInt(80, y)
        b.putInt(172, width)
        b.putInt(176, height)
    }

    /** Where and how big a display is, from a DEVMODEW the system filled in. */
    fun bounds(bytes: ByteArray): Rectangle {
        val b = le(bytes)
        return Rectangle(b.getInt(76), b.getInt(80), b.getInt(172), b.getInt(176))
    }
}

/** What the Windows backend needs from Windows, so its steps can be tested on any system. */
interface VddSystem {
    fun sources(): List<DisplaySource>
    /** A source's place and size now, in device pixels; null when it's off the desktop. */
    fun current(source: String): Rectangle?
    fun hasMode(source: String, width: Int, height: Int): Boolean
    /** Puts [source] on the desktop at [x], [y] with that size; with 0 × 0 takes it off. True when Windows took it. */
    fun place(source: String, x: Int, y: Int, width: Int, height: Int): Boolean
    fun readSettings(): String?
    fun writeSettings(text: String): Boolean
    /** RELOAD_DRIVER through the driver's pipe. */
    fun reload(): Boolean
    fun awt(): List<AwtMonitor>
    fun sleep(ms: Long) = Thread.sleep(ms)
}

/** Windows itself, through user32 and files. */
class RealVddSystem : VddSystem {
    private interface User32Display : Library {
        fun EnumDisplayDevicesW(device: WString?, index: Int, info: Pointer, flags: Int): Boolean
        fun EnumDisplaySettingsExW(device: WString, mode: Int, devMode: Pointer, flags: Int): Boolean
        fun ChangeDisplaySettingsExW(device: WString?, devMode: Pointer?, hwnd: Pointer?, flags: Int, param: Pointer?): Int
    }

    private val user32: User32Display? by lazy { runCatching { Native.load("user32", User32Display::class.java) }.getOrNull() }

    override fun sources(): List<DisplaySource> {
        val api = user32 ?: return emptyList()
        val out = mutableListOf<DisplaySource>()
        var i = 0
        while (i < 64) {
            val mem = Memory(WinStructs.DISPLAY_DEVICE_SIZE.toLong()).apply { write(0, WinStructs.emptyDisplayDevice(), 0, WinStructs.DISPLAY_DEVICE_SIZE) }
            if (!api.EnumDisplayDevicesW(null, i, mem, 0)) break
            out += WinStructs.displayDevice(mem.getByteArray(0, WinStructs.DISPLAY_DEVICE_SIZE))
            i++
        }
        return out
    }

    private fun settings(source: String, mode: Int): ByteArray? {
        val api = user32 ?: return null
        val mem = Memory(WinStructs.DEVMODE_SIZE.toLong()).apply { write(0, WinStructs.emptyDevMode(), 0, WinStructs.DEVMODE_SIZE) }
        return if (api.EnumDisplaySettingsExW(WString(source), mode, mem, 0)) mem.getByteArray(0, WinStructs.DEVMODE_SIZE) else null
    }

    override fun current(source: String): Rectangle? = settings(source, WinStructs.ENUM_CURRENT_SETTINGS)?.let(WinStructs::bounds)?.takeIf { it.width > 0 && it.height > 0 }

    override fun hasMode(source: String, width: Int, height: Int): Boolean {
        for (i in 0 until 2_000) {
            val mode = settings(source, i)?.let(WinStructs::bounds) ?: return false
            if (mode.width == width && mode.height == height) return true
        }
        return false
    }

    override fun place(source: String, x: Int, y: Int, width: Int, height: Int): Boolean {
        val api = user32 ?: return false
        val bytes = WinStructs.devMode(x, y, width, height)
        val mem = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
        val staged = api.ChangeDisplaySettingsExW(WString(source), mem, null, WinStructs.CDS_UPDATEREGISTRY or WinStructs.CDS_NORESET, null)
        val applied = api.ChangeDisplaySettingsExW(null, null, null, 0, null)
        if (staged != 0 || applied != 0) CoreLog.w("DyskLink", "Second display: ChangeDisplaySettingsEx $source gave $staged, $applied")
        return staged == WinStructs.DISP_CHANGE_SUCCESSFUL && applied == WinStructs.DISP_CHANGE_SUCCESSFUL
    }

    private val settingsFile = File(Vdd.CONFIG_DIR, Vdd.SETTINGS)

    override fun readSettings(): String? = runCatching { settingsFile.readText() }.getOrNull()

    override fun writeSettings(text: String): Boolean = runCatching { settingsFile.writeText(text); true }.getOrElse {
        CoreLog.w("DyskLink", "Second display: can't write ${settingsFile.path}", it)
        false
    }

    /** The pipe takes UTF-16 text, as the driver reads it. */
    override fun reload(): Boolean = runCatching {
        RandomAccessFile(Vdd.PIPE, "rw").use { it.write("RELOAD_DRIVER".toByteArray(Charsets.UTF_16LE)) }
        true
    }.getOrElse {
        CoreLog.w("DyskLink", "Second display: the driver's pipe didn't answer", it)
        false
    }

    override fun awt(): List<AwtMonitor> = MonitorMatch.awt()
}

/**
 * The Virtual Display Driver as a way to add a monitor. Experimental: it's only offered while its switch is on, since
 * it can't be tried here on a real Windows PC yet.
 */
class WindowsVddBackend(
    private val experimentOn: () -> Boolean,
    private val bundled: () -> File?,
    private val system: VddSystem = RealVddSystem(),
    /** Says the monitor is on the desktop because of DyskLink, so a crash doesn't leave it there after a restart. */
    private val marker: File? = null,
    private val waitMs: Long = 10_000,
) : ExtendBackend {
    override val way = ExtendWay.WindowsDriver

    fun source(): DisplaySource? = system.sources().filter(Vdd::isOurs).let { ours -> ours.firstOrNull { it.attached } ?: ours.firstOrNull() }

    val installed: Boolean get() = source() != null

    override fun probe(): ExtendProbe = when {
        !experimentOn() -> ExtendProbe(way, ExtendReadiness.Unavailable, ExtendProblem.ExperimentOff)
        installed -> ExtendProbe(way, ExtendReadiness.Ready)
        bundled() == null -> ExtendProbe(way, ExtendReadiness.Unavailable, ExtendProblem.DriverNotInCopy)
        else -> ExtendProbe(way, ExtendReadiness.NeedsSetup, ExtendProblem.DriverMissing)
    }

    override fun open(width: Int, height: Int, phoneName: String): PcExtraDisplay {
        val first = source() ?: throw PcExtendFailed("Install the virtual display driver on the PC first: DyskLink, Settings, Second display.")
        val before = system.readSettings()
        val wanted = VddSettings.write(before, width, height)
        if (before != wanted) {
            if (!system.writeSettings(wanted)) {
                throw PcExtendFailed("DyskLink can't change the virtual display's size. Install the driver again from DyskLink on the PC (Settings, Second display).")
            }
            if (!system.reload()) throw PcExtendFailed("The virtual display driver didn't answer. Restart the PC, or install the driver again from DyskLink.")
        } else if (!system.hasMode(first.name, width, height)) {
            // The file is right but the driver hasn't read it since.
            system.reload()
        }
        // The driver comes back with the new mode a moment after the reload; the source can get a new name then.
        var source: DisplaySource? = null
        val until = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < until) {
            source = source()?.takeIf { system.hasMode(it.name, width, height) }
            if (source != null) break
            system.sleep(250)
        }
        val name = (source ?: first).name
        if (source == null) throw PcExtendFailed("The virtual display didn't offer ${width}×$height.")
        val desktop = system.sources().filter { it.attached && it.name != name }.mapNotNull { system.current(it.name) }
        val (x, y) = MonitorMatch.rightOf(desktop)
        if (!system.place(name, x, y, width, height)) throw PcExtendFailed("Windows didn't turn on the extra display.")
        runCatching { marker?.writeText(name) }
        var monitor: AwtMonitor? = null
        val seen = System.currentTimeMillis() + waitMs
        while (monitor == null && System.currentTimeMillis() < seen) {
            val placed = system.current(name)
            monitor = placed?.let { MonitorMatch.pick(system.awt(), it) }
            if (monitor == null) system.sleep(200)
        }
        if (monitor == null) {
            takeOff(name)
            throw PcExtendFailed("Windows turned on the extra display, but DyskLink couldn't find it to show it.")
        }
        var bounds = monitor.bounds
        var lookedAt = System.currentTimeMillis()
        return RobotExtraDisplay(phoneName, {
            // Looked up again now and then: it can be moved in the display settings while it's shown.
            if (System.currentTimeMillis() - lookedAt > 2_000) {
                lookedAt = System.currentTimeMillis()
                system.current(name)?.let { placed -> MonitorMatch.pick(system.awt(), placed)?.let { bounds = it.bounds } }
            }
            bounds
        }) { takeOff(name) }
    }

    private fun takeOff(name: String) {
        system.place(name, 0, 0, 0, 0)
        runCatching { marker?.delete() }
    }

    /** At start: a monitor DyskLink left on the desktop (it was closed while extending) comes off. */
    fun tidyUp() {
        val left = marker?.takeIf { it.isFile }?.readText()?.trim() ?: return
        source()?.takeIf { it.attached && it.name == left }?.let { takeOff(it.name) } ?: runCatching { marker?.delete() }
    }

    /** Taken off the desktop right after an install: Windows extends onto a new monitor by itself. */
    fun detachAfterInstall() {
        val until = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < until) {
            val s = source()
            if (s != null) {
                if (s.attached) system.place(s.name, 0, 0, 0, 0)
                return
            }
            system.sleep(500)
        }
    }
}

/** Installing and removing the driver, as administrator (Windows asks first). */
object VddInstall {
    private fun quote(path: String) = "'" + path.replace("'", "''") + "'"

    /** The script run elevated: the project's own silent-install.ps1 steps, from the bundled files. */
    fun installScript(dir: String, userSid: String?): String = buildString {
        appendLine("\$ErrorActionPreference = 'Stop'")
        appendLine("\$src = ${quote(dir)}")
        appendLine("\$driver = Join-Path \$src 'VirtualDisplayDriver'")
        appendLine(CERTS)
        appendLine("foreach (\$c in \$certs) { \$store.Add(\$c) }")
        appendLine("\$store.Close()")
        appendLine("\$conf = ${quote(Vdd.CONFIG_DIR)}")
        appendLine("New-Item -ItemType Directory -Force -Path \$conf | Out-Null")
        appendLine("\$settings = Join-Path \$conf '${Vdd.SETTINGS}'")
        appendLine("if (-not (Test-Path \$settings)) { Copy-Item (Join-Path \$driver '${Vdd.SETTINGS}') \$settings }")
        // Only this user may change the size afterwards, without asking for administrator rights each time.
        if (userSid != null && Regex("""S-1-[0-9-]+""").matches(userSid)) appendLine("& icacls \$conf /grant '*$userSid:(OI)(CI)M' | Out-Null")
        appendLine("& (Join-Path \$src 'nefconw.exe') install (Join-Path \$driver 'MttVDD.inf') '${Vdd.HARDWARE_ID}'")
        appendLine("exit \$LASTEXITCODE")
    }

    fun removeScript(dir: String): String = buildString {
        appendLine("\$src = ${quote(dir)}")
        appendLine("\$driver = Join-Path \$src 'VirtualDisplayDriver'")
        appendLine("& (Join-Path \$src 'nefconw.exe') --remove-device-node --hardware-id '${Vdd.HARDWARE_ID}' --class-guid '${Vdd.DISPLAY_CLASS}'")
        appendLine("\$code = \$LASTEXITCODE")
        appendLine(CERTS)
        appendLine("foreach (\$c in \$certs) { \$store.Remove(\$c) }")
        appendLine("\$store.Close()")
        appendLine("exit \$code")
    }

    private val CERTS = """
        ${'$'}cat = Join-Path ${'$'}driver 'mttvdd.cat'
        ${'$'}certs = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2Collection
        ${'$'}certs.Import([System.IO.File]::ReadAllBytes(${'$'}cat))
        ${'$'}store = New-Object System.Security.Cryptography.X509Certificates.X509Store('TrustedPublisher', 'LocalMachine')
        ${'$'}store.Open('ReadWrite')
    """.trimIndent()

    /** The PowerShell line that runs [script] as administrator and waits for it. */
    fun elevated(script: String): String {
        val path = script.replace("'", "''")
        return "\$p = Start-Process powershell -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','\"$path\"' -Verb RunAs -Wait -PassThru; exit \$p.ExitCode"
    }

    /** This user's SID, for the settings folder's permission. */
    fun userSid(): String? = runCatching {
        // com.sun.security.auth.module.NTSystem is only in Windows' JDK, so it's looked up when it runs.
        val nt = Class.forName("com.sun.security.auth.module.NTSystem").getDeclaredConstructor().newInstance()
        nt.javaClass.getMethod("getUserSID").invoke(nt) as String
    }.getOrNull()

    /** Runs the install or the removal. False when it failed or the user said no to Windows. */
    fun run(dir: File, remove: Boolean): Boolean = runCatching {
        val script = File.createTempFile("dysklink-vdd-", ".ps1")
        try {
            script.writeText(if (remove) removeScript(dir.absolutePath) else installScript(dir.absolutePath, userSid()))
            val process = ProcessBuilder("powershell", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", elevated(script.absolutePath))
                .redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            val done = process.waitFor(180, TimeUnit.SECONDS)
            if (!done || process.exitValue() != 0) CoreLog.w("DyskLink", "Virtual display driver ${if (remove) "removal" else "install"} failed: $out")
            done && process.exitValue() == 0
        } finally {
            script.delete()
        }
    }.getOrDefault(false)
}
