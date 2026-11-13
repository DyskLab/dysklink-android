package id.dysklink.desktop

import java.io.File
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

// The registry, as `reg query` prints it

data class RegValue(val name: String, val type: String, val data: String)

data class RegKey(val path: String, val values: List<RegValue>) {
    operator fun get(name: String): String? = values.firstOrNull { it.name.equals(name, ignoreCase = true) }?.data?.takeIf { it.isNotEmpty() }
    val leaf: String get() = path.substringAfterLast('\\')
}

object RegParse {
    private val withData = Regex("^ {4}(.+?) {4}(REG_[A-Z_]+) {4}(.*)$")
    private val noData = Regex("^ {4}(.+?) {4}(REG_[A-Z_]+)\\s*$")

    /** Keys and their values from `reg query <key> [/s]`. Lines start with the key's path; values are indented 4 spaces. */
    fun parse(text: String): List<RegKey> {
        val keys = mutableListOf<RegKey>()
        var path: String? = null
        var values = mutableListOf<RegValue>()
        fun flush() { path?.let { keys += RegKey(it, values) }; values = mutableListOf() }
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isBlank()) continue
            if (line.startsWith("HKEY_") || line.startsWith("HKLM\\") || line.startsWith("HKCU\\")) { flush(); path = line.trim(); continue }
            val match = withData.find(line) ?: noData.find(line) ?: continue
            val name = match.groupValues[1].let { if (it == "(Default)" || it == "(Standard)") "" else it }
            values += RegValue(name, match.groupValues[2], match.groupValues.getOrNull(3).orEmpty())
        }
        flush()
        return keys
    }

    /** A REG_DWORD as `reg` prints it: 0x1a2b. */
    fun dword(text: String?): Long? = text?.trim()?.let { if (it.startsWith("0x", true)) it.drop(2).toLongOrNull(16) else it.toLongOrNull() }
}

/** Reading and writing the registry. The real one uses reg.exe and PowerShell; tests use a map. */
interface Registry {
    /** The key and its subkeys' values, or null when the key isn't there. */
    fun query(key: String, recursive: Boolean = false): List<RegKey>?
    fun set(key: String, name: String, type: String, data: String): Boolean
    fun delete(key: String, name: String): Boolean
}

class WindowsRegistry(private val sys: CareSystem) : Registry {
    override fun query(key: String, recursive: Boolean): List<RegKey>? {
        // UTF-8, so app names in any language come through.
        val reg = "reg query \"$key\"" + if (recursive) " /s" else ""
        val out = sys.run(listOf("cmd", "/d", "/c", "chcp 65001 >nul & $reg"), 60_000) ?: return null
        if (out.code != 0) return null
        return RegParse.parse(out.out)
    }

    override fun set(key: String, name: String, type: String, data: String): Boolean {
        val kind = if (type == "REG_EXPAND_SZ") "ExpandString" else "String"
        val script = "\$ErrorActionPreference='Stop'; if (-not (Test-Path -LiteralPath \$env:DL_KEY)) { New-Item -Path \$env:DL_KEY -Force | Out-Null }; " +
            "New-ItemProperty -LiteralPath \$env:DL_KEY -Name \$env:DL_NAME -Value \$env:DL_DATA -PropertyType $kind -Force | Out-Null"
        return PowerShell.run(sys, script, mapOf("DL_KEY" to psKey(key), "DL_NAME" to name, "DL_DATA" to data))?.code == 0
    }

    override fun delete(key: String, name: String): Boolean {
        val script = "\$ErrorActionPreference='Stop'; Remove-ItemProperty -LiteralPath \$env:DL_KEY -Name \$env:DL_NAME"
        return PowerShell.run(sys, script, mapOf("DL_KEY" to psKey(key), "DL_NAME" to name))?.code == 0
    }

    /** HKEY_CURRENT_USER\Software → Registry::HKEY_CURRENT_USER\Software, which PowerShell takes as is. */
    private fun psKey(key: String): String {
        val full = key.replaceFirst(Regex("^HKCU\\\\"), "HKEY_CURRENT_USER\\\\").replaceFirst(Regex("^HKLM\\\\"), "HKEY_LOCAL_MACHINE\\\\")
        return "Registry::$full"
    }
}

/** PowerShell with the values passed in the environment, never pasted into the script. */
object PowerShell {
    fun run(sys: CareSystem, script: String, env: Map<String, String> = emptyMap(), timeoutMs: Long = 60_000): ProcOut? =
        sys.run(
            listOf("powershell", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command",
                "[Console]::OutputEncoding = [Text.Encoding]::UTF8; $script"),
            timeoutMs, env,
        )

    /** A JSON value or array from ConvertTo-Json as a list: one item comes out as an object, none as nothing. */
    fun jsonList(text: String): List<JsonObject> {
        val trimmed = text.trim().ifEmpty { return emptyList() }
        val element = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() ?: return emptyList()
        return when (element) {
            is JsonArray -> element.filterIsInstance<JsonObject>()
            is JsonObject -> listOf(element)
            else -> emptyList()
        }
    }

    fun str(o: JsonObject, key: String): String = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull.orEmpty()
    fun bool(o: JsonObject, key: String): Boolean = (o[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull == "True") } ?: false
}

// Installed apps

enum class AppKind(val label: String) {
    Windows("Desktop app"), Store("Microsoft Store app"), Deb("Debian package"), Rpm("RPM package"), Pacman("Arch package"),
    Flatpak("Flatpak"), Snap("Snap"), AppImage("AppImage"),
}

data class PcApp(
    val id: String,
    val name: String,
    val kind: AppKind,
    val publisher: String = "",
    val version: String = "",
    val size: Long? = null,
    val installDate: LocalDate? = null,
    /** InstallLocation on Windows, the AppImage file, or the flatpak's folder. */
    val location: File? = null,
    /** The program it runs, for whether it's open. */
    val exe: File? = null,
    /** UninstallString on Windows. */
    val uninstall: String? = null,
    /** Installed for every user, so removing it needs an administrator. */
    val forAllUsers: Boolean = false,
    /** The package, flatpak ID, snap, or Store PackageFullName. */
    val packageId: String = "",
    /** The Store package's family name, the name of its data folder. */
    val family: String = "",
    /** The .desktop file's name without .desktop, such as org.gnome.Calculator. */
    val desktopId: String = "",
    val desktopFile: File? = null,
    /** user or system, for a flatpak. */
    val installation: String = "",
    /** The registry key, for checking it's gone after its uninstaller ran. */
    val registryKey: String = "",
)

object WindowsApps {
    val uninstallKeys = listOf(
        "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
        "HKLM\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
    )

    fun isOwn(name: String, publisher: String) = name.equals("DyskLink", true) || (publisher.equals("DyskLab", true) && name.startsWith("DyskLink", true))

    /** Apps from the Uninstall keys: no system components, updates, or entries without a name or a way to uninstall. */
    fun fromRegistry(keys: List<RegKey>, forAllUsers: Boolean): List<PcApp> = keys.mapNotNull { key ->
        val name = key["DisplayName"]?.trim() ?: return@mapNotNull null
        val uninstall = key["UninstallString"] ?: key["QuietUninstallString"] ?: return@mapNotNull null
        if (RegParse.dword(key["SystemComponent"]) == 1L) return@mapNotNull null
        if (key["ParentKeyName"] != null) return@mapNotNull null
        if (key["ReleaseType"]?.let { it.contains("Update", true) || it.contains("Hotfix", true) } == true) return@mapNotNull null
        val publisher = key["Publisher"]?.trim().orEmpty()
        if (isOwn(name, publisher)) return@mapNotNull null
        PcApp(
            id = key.path, name = name, kind = AppKind.Windows, publisher = publisher, version = key["DisplayVersion"].orEmpty(),
            size = RegParse.dword(key["EstimatedSize"])?.takeIf { it > 0 }?.let { it * 1024 },
            installDate = key["InstallDate"]?.let(::installDate),
            location = key["InstallLocation"]?.trim('"', ' ', '\\')?.takeIf { it.isNotEmpty() }?.let(::File),
            exe = key["DisplayIcon"]?.let(::iconExe), uninstall = uninstall, forAllUsers = forAllUsers, registryKey = key.path,
        )
    }

    /** InstallDate is yyyyMMdd. */
    fun installDate(text: String): LocalDate? = runCatching {
        val t = text.trim()
        LocalDate.of(t.substring(0, 4).toInt(), t.substring(4, 6).toInt(), t.substring(6, 8).toInt())
    }.getOrNull()

    /** DisplayIcon is a path with an icon index: "C:\Program Files\X\x.exe",0. Only an .exe says which program runs. */
    fun iconExe(text: String): File? {
        val path = text.trim().removePrefix("\"").substringBefore('"').substringBeforeLast(",").trim()
        return path.takeIf { it.endsWith(".exe", true) }?.let(::File)
    }

    /** The program and its arguments from a command line, quoted or not. */
    fun splitCommand(command: String): Pair<String, String> {
        val text = command.trim()
        if (text.startsWith("\"")) {
            val end = text.indexOf('"', 1)
            if (end > 0) return text.substring(1, end) to text.substring(end + 1).trim()
        }
        // Unquoted with spaces: C:\Program Files\X\uninst.exe /S. The first ".exe" ends the program.
        Regex("(?i)^(.+?\\.exe)(\\s|$)").find(text)?.let { return it.groupValues[1] to text.substring(it.groupValues[1].length).trim() }
        val space = text.indexOf(' ')
        return if (space < 0) text to "" else text.substring(0, space) to text.substring(space + 1).trim()
    }

    /** Windows Installer entries often say MsiExec.exe /I{...}, which opens Change; /X is uninstall. */
    fun uninstallCommand(app: PcApp): Pair<String, String>? {
        val (exe, args) = splitCommand(app.uninstall ?: return null)
        if (exe.substringAfterLast('\\').startsWith("msiexec", true)) {
            return exe to args.replace(Regex("(?i)/I\\s*(\\{)"), "/X$1")
        }
        return exe to args
    }

    /** Get-AppxPackage for this user, as JSON, without frameworks, system parts, or packages that can't be removed. */
    const val APPX_SCRIPT = "Get-AppxPackage | Where-Object { -not \$_.IsFramework -and -not \$_.NonRemovable -and \$_.SignatureKind -ne 'System' -and -not \$_.IsResourcePackage } | " +
        "ForEach-Object { \$m = \$null; try { \$m = (Get-AppxPackageManifest -Package \$_.PackageFullName).Package.Properties } catch {}; " +
        "[pscustomobject]@{ Name = \$_.Name; PackageFullName = \$_.PackageFullName; PackageFamilyName = \$_.PackageFamilyName; " +
        "Version = \"\$(\$_.Version)\"; InstallLocation = \$_.InstallLocation; SignatureKind = \"\$(\$_.SignatureKind)\"; " +
        "DisplayName = \"\$(\$m.DisplayName)\"; PublisherDisplayName = \"\$(\$m.PublisherDisplayName)\" } } | ConvertTo-Json -Compress"

    fun parseAppx(json: String): List<PcApp> = PowerShell.jsonList(json).mapNotNull { o ->
        val full = PowerShell.str(o, "PackageFullName").ifEmpty { return@mapNotNull null }
        val raw = PowerShell.str(o, "Name")
        val shown = PowerShell.str(o, "DisplayName").takeIf { it.isNotBlank() && !it.startsWith("ms-resource:") } ?: tidyPackageName(raw)
        val publisher = PowerShell.str(o, "PublisherDisplayName").takeIf { !it.startsWith("ms-resource:") }.orEmpty()
        if (isOwn(shown, publisher)) return@mapNotNull null
        PcApp(
            id = "appx:$full", name = shown, kind = AppKind.Store, publisher = publisher, version = PowerShell.str(o, "Version"),
            location = PowerShell.str(o, "InstallLocation").takeIf { it.isNotBlank() }?.let(::File),
            packageId = full, family = PowerShell.str(o, "PackageFamilyName"),
        )
    }

    /** SpotifyAB.SpotifyMusic → Spotify Music; Microsoft.WindowsCalculator → Windows Calculator. */
    fun tidyPackageName(name: String): String {
        val product = name.substringAfter('.', name).ifEmpty { name }
        return product.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('.', ' ').replace('_', ' ').trim()
    }

    fun list(sys: CareSystem, registry: Registry): List<PcApp> {
        val desktop = uninstallKeys.flatMap { key ->
            fromRegistry(registry.query(key, recursive = true).orEmpty(), key.startsWith("HKLM"))
        }
        val store = PowerShell.run(sys, APPX_SCRIPT, timeoutMs = 120_000)?.takeIf { it.code == 0 }?.let { parseAppx(it.out) }.orEmpty()
        // The same app under both 32- and 64-bit keys shows once.
        return (desktop.distinctBy { (it.name + "|" + it.version).lowercase() } + store).sortedBy { it.name.lowercase() }
    }
}

// Linux apps

/** A .desktop file's [Desktop Entry] group. */
data class DesktopEntry(val file: File, val values: Map<String, String>) {
    operator fun get(key: String): String? = values[key]
    val id: String get() = file.name.removeSuffix(".desktop")
    val name: String get() = values["Name"] ?: id
    val shown: Boolean get() = values["Type"].let { it == null || it == "Application" } && values["NoDisplay"] != "true" && values["Hidden"] != "true"

    /** The program in Exec, without env, field codes, or quotes. */
    val program: String? get() {
        val parts = DesktopFiles.splitExec(values["Exec"] ?: return null)
        var i = 0
        if (parts.getOrNull(0) == "env") { i = 1; while (i < parts.size && '=' in parts[i]) i++ }
        return parts.getOrNull(i)
    }
}

object DesktopFiles {
    /** The [Desktop Entry] group's keys, without other languages' names (Name[de]). */
    fun parse(file: File, text: String): DesktopEntry {
        val values = linkedMapOf<String, String>()
        var inEntry = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("[")) { inEntry = line == "[Desktop Entry]"; continue }
            if (!inEntry || line.startsWith("#") || '=' !in line) continue
            val key = line.substringBefore('=').trim()
            if ('[' in key) continue
            values.putIfAbsent(key, line.substringAfter('=').trim())
        }
        return DesktopEntry(file, values)
    }

    fun splitExec(exec: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < exec.length) {
            val c = exec[i]
            when {
                c == '\\' && i + 1 < exec.length -> { current.append(exec[i + 1]); i++ }
                c == '"' -> quoted = !quoted
                c == ' ' && !quoted -> { if (current.isNotEmpty()) parts += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts.filter { !(it.length == 2 && it[0] == '%') }
    }

    /**
     * Sets keys in the [Desktop Entry] group, keeping every other line as it was. A key already there is changed in
     * place; a new one goes right after the group's heading.
     */
    fun withKeys(text: String, keys: Map<String, String>): String {
        val lines = text.lines().toMutableList()
        val start = lines.indexOfFirst { it.trim() == "[Desktop Entry]" }
        if (start < 0) return "[Desktop Entry]\n" + keys.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n" + text
        val end = (start + 1 until lines.size).firstOrNull { lines[it].trim().startsWith("[") } ?: lines.size
        val missing = keys.toMutableMap()
        for (i in start + 1 until end) {
            val key = lines[i].substringBefore('=').trim()
            if ('=' in lines[i] && key in missing) lines[i] = "$key=${missing.remove(key)}"
        }
        lines.addAll(start + 1, missing.map { "${it.key}=${it.value}" })
        return lines.joinToString("\n")
    }
}

/** Which package a file belongs to: dpkg, rpm, or pacman, from their output. */
object PackageOwners {
    enum class Tool { Dpkg, Rpm, Pacman }

    fun tool(sys: CareSystem): Tool? = when {
        sys.onPath("dpkg") -> Tool.Dpkg
        sys.onPath("rpm") && !sys.onPath("pacman") -> Tool.Rpm
        sys.onPath("pacman") -> Tool.Pacman
        else -> null
    }

    fun command(tool: Tool, paths: List<String>): List<String> = when (tool) {
        Tool.Dpkg -> listOf("dpkg", "-S") + paths
        Tool.Rpm -> listOf("rpm", "-qf", "--qf", "%{NAME}\\n") + paths
        Tool.Pacman -> listOf("pacman", "-Qo") + paths
    }

    /** Path to package. Files no package owns are left out. */
    fun parse(tool: Tool, paths: List<String>, out: String): Map<String, String> = when (tool) {
        // "vlc-bin, vlc: /usr/share/applications/vlc.desktop", or "pkg:amd64: /path".
        Tool.Dpkg -> out.lines().mapNotNull { line ->
            val at = line.lastIndexOf(": /").takeIf { it > 0 } ?: return@mapNotNull null
            val path = line.substring(at + 2).trim()
            val pkg = line.substring(0, at).split(',').first().trim().substringBefore(':')
            if (pkg.isEmpty() || line.startsWith("dpkg-query") || line.startsWith("diversion")) null else path to pkg
        }.toMap()
        // One line per path, in order; "file /x is not owned by any package" for the others.
        Tool.Rpm -> out.lines().filter { it.isNotBlank() }.zip(paths).filter { (line, _) -> !line.contains(" ") }.associate { (line, path) -> path to line.trim() }
        // "/usr/share/applications/vlc.desktop is owned by vlc 3.0.20-1"
        Tool.Pacman -> out.lines().mapNotNull { line ->
            val at = line.indexOf(" is owned by ").takeIf { it > 0 } ?: return@mapNotNull null
            line.substring(0, at).trim() to line.substring(at + 13).trim().substringBefore(' ')
        }.toMap()
    }

    /** Versions and installed sizes for packages. */
    fun detailsCommand(tool: Tool, packages: List<String>): List<String> = when (tool) {
        Tool.Dpkg -> listOf("dpkg-query", "-W", "-f=\${Package}\\t\${Version}\\t\${Installed-Size}\\n") + packages
        Tool.Rpm -> listOf("rpm", "-q", "--qf", "%{NAME}\\t%{VERSION}-%{RELEASE}\\t%{SIZE}\\n") + packages
        Tool.Pacman -> listOf("pacman", "-Q") + packages
    }

    /** Package to (version, bytes). dpkg counts kilobytes. */
    fun parseDetails(tool: Tool, out: String): Map<String, Pair<String, Long?>> = out.lines().mapNotNull { line ->
        when (tool) {
            Tool.Pacman -> line.trim().split(' ').takeIf { it.size >= 2 }?.let { it[0] to (it[1] to null) }
            else -> line.split('\t').takeIf { it.size >= 3 }?.let { p ->
                val size = p[2].trim().toLongOrNull()?.let { if (tool == Tool.Dpkg) it * 1024 else it }
                p[0].trim() to (p[1].trim() to size)
            }
        }
    }.toMap()
}

object LinuxApps {
    fun desktopDirs(home: File, dataHome: File = File(home, ".local/share")): List<File> = listOf(
        File(dataHome, "applications"), File(dataHome, "flatpak/exports/share/applications"),
        File("/var/lib/flatpak/exports/share/applications"), File("/var/lib/snapd/desktop/applications"),
        File("/usr/local/share/applications"), File("/usr/share/applications"),
    )

    val appImageDirs = listOf("Applications", "AppImages", ".local/bin", "bin")

    /** `flatpak list --app --columns=application,name,version,size,installation`, tab-separated. */
    fun parseFlatpaks(out: String): List<PcApp> = out.lines().mapNotNull { line ->
        val p = line.split('\t')
        if (p.size < 2 || p[0].isBlank() || p[0] == "Application ID") return@mapNotNull null
        val id = p[0].trim()
        PcApp(
            id = "flatpak:$id", name = p[1].trim().ifEmpty { id }, kind = AppKind.Flatpak, version = p.getOrNull(2)?.trim().orEmpty(),
            size = p.getOrNull(3)?.let(::humanSize), packageId = id, desktopId = id, installation = p.getOrNull(4)?.trim().orEmpty().ifEmpty { "system" },
            forAllUsers = p.getOrNull(4)?.trim() != "user",
        )
    }

    /** "1.2 GB", "350.5 MB", "12 kB" as bytes. Flatpak prints sizes this way. */
    fun humanSize(text: String): Long? {
        val m = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*([kKMGT]?i?B)").find(text.replace('\u00a0', ' ')) ?: return null
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2].first().uppercaseChar()) { 'K' -> 1e3; 'M' -> 1e6; 'G' -> 1e9; 'T' -> 1e12; else -> 1.0 }
        return (n * mult).toLong()
    }

    /** `snap list`: Name Version Rev Tracking Publisher Notes. Bases, snapd, and the kernel aren't apps. */
    fun parseSnaps(out: String): List<PcApp> = out.lines().drop(1).mapNotNull { line ->
        val p = line.trim().split(Regex("\\s+"))
        if (p.size < 5) return@mapNotNull null
        val notes = p.getOrNull(5).orEmpty()
        if (listOf("base", "core", "snapd", "gadget", "kernel").any { it in notes.split(',') } || p[0] == "snapd" || p[0].startsWith("core")) return@mapNotNull null
        if (p[0].startsWith("gnome-") && p[0].count { it == '-' } >= 2 || p[0].startsWith("gtk-common-themes") || p[0].startsWith("kf5-") || p[0].startsWith("kf6-") || p[0].startsWith("mesa-")) return@mapNotNull null
        PcApp(id = "snap:${p[0]}", name = p[0], kind = AppKind.Snap, version = p[1], publisher = p[4].trimEnd('✓', '*', '✪').let { if (it == "-") "" else it },
            packageId = p[0], forAllUsers = true)
    }

    /** Obsidian-1.5.3.AppImage → Obsidian, 1.5.3. */
    fun appImageName(file: File): Pair<String, String> {
        val base = file.name.removeSuffix(".AppImage").removeSuffix(".appimage")
        val version = Regex("[-_ ]v?([0-9]+(?:\\.[0-9A-Za-z]+)+)").find(base)?.groupValues?.get(1).orEmpty()
        val name = base.substringBefore("-$version").substringBefore("_$version").replace(Regex("[-_](x86_64|amd64|aarch64|arm64|linux)$", RegexOption.IGNORE_CASE), "")
            .replace('-', ' ').replace('_', ' ').trim()
        return (name.ifEmpty { base }) to version
    }

    fun isOwn(name: String, pkg: String) = name.equals("DyskLink", true) || pkg == "dysklink" || pkg.startsWith("id.dysklink")

    /**
     * Apps people see in their menu, with what installed them. [owners] says which package a .desktop file belongs to;
     * [readText] reads a file. Both are passed so tests can fake a PC.
     */
    fun list(
        home: File,
        dirs: List<File>,
        readText: (File) -> String?,
        owners: (List<String>) -> Map<String, Pair<PackageOwners.Tool, String>>,
        flatpaks: List<PcApp>,
        snaps: List<PcApp>,
        details: (PackageOwners.Tool, List<String>) -> Map<String, Pair<String, Long?>> = { _, _ -> emptyMap() },
        appImages: List<File> = emptyList(),
    ): List<PcApp> {
        val entries = linkedMapOf<String, DesktopEntry>()
        for (dir in dirs) {
            for (file in CareFiles.children(dir).filter { it.name.endsWith(".desktop") }) {
                val text = readText(file) ?: continue
                val entry = DesktopFiles.parse(file, text)
                entries.putIfAbsent(entry.id, entry) // The user's own file overrides the system's.
            }
        }
        val shown = entries.values.filter { it.shown }
        val found = mutableListOf<PcApp>()
        val flatById = flatpaks.associateBy { it.packageId }
        val snapByName = snaps.associateBy { it.packageId }
        val usedFlatpaks = mutableSetOf<String>()
        val usedSnaps = mutableSetOf<String>()
        val packaged = mutableListOf<DesktopEntry>()
        for (entry in shown) {
            val path = entry.file.path
            val flatpak = entry["X-Flatpak"] ?: if ("/flatpak/exports/" in path) entry.id else null
            val snap = entry["X-SnapInstanceName"] ?: if (path.startsWith("/var/lib/snapd/desktop/")) entry.id.substringBefore('_') else null
            val appImage = entry.program?.takeIf { it.endsWith(".AppImage", true) }
            when {
                flatpak != null -> {
                    val base = flatById[flatpak] ?: PcApp("flatpak:$flatpak", entry.name, AppKind.Flatpak, packageId = flatpak, installation = "system", forAllUsers = true)
                    usedFlatpaks += flatpak
                    found += base.copy(name = entry.name, desktopId = entry.id, desktopFile = entry.file)
                }
                snap != null -> {
                    val base = snapByName[snap] ?: PcApp("snap:$snap", entry.name, AppKind.Snap, packageId = snap, forAllUsers = true)
                    if (snap !in usedSnaps) found += base.copy(name = entry.name, desktopId = entry.id, desktopFile = entry.file, exe = File("/snap/bin/$snap"))
                    usedSnaps += snap
                }
                appImage != null -> {
                    val file = File(appImage)
                    val (_, version) = appImageName(file)
                    found += PcApp("appimage:${file.path}", entry.name, AppKind.AppImage, version = version, size = file.length().takeIf { it > 0 },
                        location = file, exe = file, desktopId = entry.id, desktopFile = entry.file)
                }
                path.startsWith(home.path) -> Unit // Launchers made by hand or by an app, not from a package.
                else -> packaged += entry
            }
        }
        val byPath = owners(packaged.map { it.file.path })
        val tools = byPath.values.groupBy({ it.first }, { it.second })
        val info = tools.flatMap { (tool, pkgs) -> details(tool, pkgs.distinct()).entries }.associate { it.key to it.value }
        // A package with several launchers shows under the one named like the package, or the shortest.
        val ordered = packaged.sortedWith(compareBy<DesktopEntry> { e -> if (byPath[e.file.path]?.second == e.id) 0 else 1 }.thenBy { it.id.length })
        for (entry in ordered) {
            val (tool, pkg) = byPath[entry.file.path] ?: continue
            val kind = when (tool) { PackageOwners.Tool.Dpkg -> AppKind.Deb; PackageOwners.Tool.Rpm -> AppKind.Rpm; PackageOwners.Tool.Pacman -> AppKind.Pacman }
            found += PcApp(
                "pkg:$pkg:${entry.id}", entry.name, kind, version = info[pkg]?.first.orEmpty(), size = info[pkg]?.second,
                exe = entry.program?.let(::File), packageId = pkg, desktopId = entry.id, desktopFile = entry.file, forAllUsers = true,
            )
        }
        found += flatpaks.filter { it.packageId !in usedFlatpaks }
        found += snaps.filter { it.packageId !in usedSnaps }
        val listedImages = found.mapNotNull { it.location?.path }.toSet()
        for (file in appImages.filter { it.path !in listedImages }) {
            val (name, version) = appImageName(file)
            found += PcApp("appimage:${file.path}", name, AppKind.AppImage, version = version, size = file.length().takeIf { it > 0 }, location = file, exe = file)
        }
        return found.filter { !isOwn(it.name, it.packageId) }
            // One package with several launchers shows once, under its first name.
            .distinctBy { if (it.kind in setOf(AppKind.Deb, AppKind.Rpm, AppKind.Pacman)) "pkg:" + it.packageId else it.id }
            .sortedBy { it.name.lowercase() }
    }

    fun findAppImages(home: File): List<File> = appImageDirs.flatMap { CareFiles.children(File(home, it)) }
        .filter { it.isFile && it.name.endsWith(".AppImage", true) }
}

// What removing an app does

/** A command that removes an app, and what to say if it asks for a password. */
data class UninstallPlan(
    val command: List<String>? = null,
    val env: Map<String, String> = emptyMap(),
    /** The AppImage, which simply goes to the Trash. */
    val trash: File? = null,
    /** Other packages the package manager removes with it. */
    val alsoRemoves: List<String> = emptyList(),
    val needsPassword: Boolean = false,
    /** Why it can't be done here, with what to do instead. */
    val blocked: String? = null,
)

object Uninstall {
    /** `apt-get -s remove <pkg>`: the "Remv name [version]" lines are what would go. */
    fun parseAptSimulation(out: String): List<String> = out.lines().filter { it.startsWith("Remv ") }.map { it.removePrefix("Remv ").substringBefore(' ').trim() }

    /** `rpm -e --test <pkg>`: "x is needed by (installed) other-1.0.x86_64". */
    fun parseRpmTest(out: String): List<String> = out.lines().mapNotNull { line ->
        line.substringAfter("is needed by (installed) ", "").trim().takeIf { it.isNotEmpty() }
    }.distinct()

    /** Removing these takes the desktop with it on Ubuntu, Fedora, and the like. */
    fun takesDesktop(packages: List<String>) = packages.any { it.endsWith("-desktop") || it.endsWith("-desktop-minimal") || it == "gnome-shell" || it == "plasma-desktop" || it == "xorg" }

    fun plan(app: PcApp, sys: CareSystem): UninstallPlan = when (app.kind) {
        AppKind.Windows -> {
            val (exe, args) = WindowsApps.uninstallCommand(app) ?: return UninstallPlan(blocked = "Windows doesn't say how to uninstall ${app.name}. Use Settings › Apps › Installed apps.")
            // Start-Process goes through the shell, so an uninstaller that needs an administrator gets Windows' prompt.
            val script = "\$ErrorActionPreference='Stop'; try { \$p = Start-Process -FilePath \$env:DL_EXE" +
                (if (args.isNotEmpty()) " -ArgumentList \$env:DL_ARGS" else "") + " -PassThru -Wait; exit \$p.ExitCode } catch { " +
                "\$e = \$_.Exception; while (\$e) { if (\$e -is [System.ComponentModel.Win32Exception] -and \$e.NativeErrorCode -eq 1223) { exit 1223 }; \$e = \$e.InnerException }; " +
                "[Console]::Error.WriteLine(\$_.Exception.Message); exit 9001 }"
            UninstallPlan(powershell(script), mapOf("DL_EXE" to exe, "DL_ARGS" to args))
        }
        AppKind.Store -> UninstallPlan(
            powershell("\$ErrorActionPreference='Stop'; try { Remove-AppxPackage -Package \$env:DL_PKG } catch { [Console]::Error.WriteLine(\$_.Exception.Message); exit 9001 }"),
            mapOf("DL_PKG" to app.packageId),
        )
        AppKind.Deb -> {
            val also = sys.run(listOf("apt-get", "-s", "remove", app.packageId), 60_000)?.out?.let(::parseAptSimulation).orEmpty() - app.packageId
            withPkexec(sys, listOf("env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "remove", "-y", app.packageId), also, "sudo apt remove ${app.packageId}")
        }
        AppKind.Rpm -> {
            val also = sys.run(listOf("rpm", "-e", "--test", app.packageId), 60_000)?.let { parseRpmTest(it.err + "\n" + it.out) }.orEmpty()
            val remove = when {
                sys.onPath("dnf") -> listOf("dnf", "remove", "-y", app.packageId)
                sys.onPath("zypper") -> listOf("zypper", "--non-interactive", "remove", app.packageId)
                else -> listOf("rpm", "-e", app.packageId)
            }
            withPkexec(sys, remove, also, "sudo ${remove.first()} remove ${app.packageId}")
        }
        // pacman -R refuses when other packages need it, so it never takes more than asked.
        AppKind.Pacman -> withPkexec(sys, listOf("pacman", "-R", "--noconfirm", app.packageId), emptyList(), "sudo pacman -R ${app.packageId}")
        AppKind.Flatpak -> UninstallPlan(listOf("flatpak", "uninstall", if (app.installation == "user") "--user" else "--system", "-y", "--noninteractive", app.packageId),
            needsPassword = app.installation != "user")
        AppKind.Snap -> withPkexec(sys, listOf("snap", "remove", app.packageId), emptyList(), "sudo snap remove ${app.packageId}")
        AppKind.AppImage -> UninstallPlan(trash = app.location)
    }

    private fun withPkexec(sys: CareSystem, command: List<String>, also: List<String>, manual: String): UninstallPlan =
        if (sys.onPath("pkexec")) UninstallPlan(listOf("pkexec") + command, alsoRemoves = also, needsPassword = true)
        else UninstallPlan(alsoRemoves = also, blocked = "DyskLink asks for your password through pkexec (polkit), which isn't on this PC. Remove it in a terminal: $manual")

    private fun powershell(script: String) = listOf("powershell", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script)

    /** What to tell people when removing didn't work. Never empty. */
    fun failure(app: PcApp, out: ProcOut?): String {
        out ?: return "${app.name}'s uninstaller didn't finish in time. Check whether its window is still open."
        val detail = (out.err.lines() + out.out.lines()).map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("at ") && !it.startsWith("+") }
        return when {
            app.kind == AppKind.Windows && out.code == 1223 -> "Windows' prompt was cancelled, so ${app.name} stays."
            app.kind == AppKind.Windows && out.code == 1602 -> "The uninstaller was cancelled, so ${app.name} stays."
            out.code == 126 && app.kind != AppKind.Windows -> "The password was cancelled, so ${app.name} stays."
            out.code == 127 && app.kind != AppKind.Windows -> "This account isn't allowed to remove apps. An administrator can remove ${app.name}."
            detail?.contains("access is denied", true) == true || detail?.contains("0x80070005") == true -> "Windows didn't allow it: ${app.name} needs an administrator to remove."
            detail?.contains("is not installed", true) == true || detail?.contains("not found", true) == true -> "${app.name} is already gone."
            detail != null -> "Couldn't remove ${app.name}: ${detail.take(240)}"
            else -> "Couldn't remove ${app.name} (exit code ${out.code})."
        }
    }
}

// Leftovers

data class Leftover(val item: CareItem, val byId: Boolean) {
    val id: String get() = item.id
}

object Leftovers {
    /** Names too general to match a folder by: Microsoft, Google, Programs... */
    private val generic = setOf("microsoft", "google", "programs", "packages", "temp", "windows", "intel", "nvidia", "amd", "apple", "mozilla", "cache",
        "common files", "package cache", "local", "roaming", "applications", "config", "data", "app", "apps", "user", "users", "default", "inc", "llc", "ltd", "software")

    private fun usable(name: String) = name.length >= 3 && name.lowercase() !in generic && !name.contains('/') && !name.contains('\\') && name != ".."

    /** Child of [folder] whose name matches, exactly or ignoring case. Only direct children, never the folder itself. */
    private fun match(folder: File, name: String, exact: Boolean): List<File> =
        CareFiles.children(folder).filter { if (exact) it.name == name else it.name.equals(name, ignoreCase = true) }

    fun find(app: PcApp, p: CarePlaces, guard: CareGuard, now: Long = System.currentTimeMillis()): List<Leftover> {
        val byId = mutableListOf<File>()
        val byName = mutableListOf<File>()
        if (p.windows) {
            val roots = listOfNotNull(p.appData, p.localAppData, p.localAppData?.parentFile?.let { File(it, "LocalLow") })
            if (app.kind == AppKind.Store && app.family.isNotEmpty()) p.localAppData?.let { byId += match(File(it, "Packages"), app.family, exact = false) }
            // A per-user install folder left after its uninstaller ran surely belongs to it.
            app.location?.takeIf { loc -> roots.any { CareFiles.shortPath(loc, it) != loc.path } && app.kind == AppKind.Windows }?.let { if (it.exists()) byId += it }
            val names = listOf(app.name, app.name.substringBefore(" (").trim(), app.location?.name.orEmpty()).filter(::usable).distinct()
            for (root in roots) {
                for (name in names) {
                    byName += match(root, name, exact = false)
                    if (usable(app.publisher)) {
                        match(root, app.publisher, false).forEach { pub -> byName += match(pub, name, exact = false) }
                    }
                }
            }
        } else {
            val roots = listOf(p.config, p.dataHome, p.cache)
            when (app.kind) {
                AppKind.Flatpak -> byId += match(File(p.home, ".var/app"), app.packageId, exact = true)
                AppKind.Snap -> byId += match(File(p.home, "snap"), app.packageId, exact = true)
                AppKind.AppImage -> app.desktopFile?.takeIf { it.path.startsWith(p.home.path) }?.let { byId += it }
                else -> Unit
            }
            val ids = listOf(app.desktopId, app.packageId).filter { it.length >= 3 && usable(it) }.distinct()
            for (root in roots) ids.forEach { byId += match(root, it, exact = true) }
            val names = listOf(app.name, app.exe?.name.orEmpty(), app.packageId).filter(::usable).distinct()
            for (root in roots) names.forEach { byName += match(root, it, exact = false) }
        }
        val seen = HashSet<String>()
        fun make(files: List<File>, isId: Boolean) = files.filter { it.exists() && guard.allows(it) && seen.add(it.absolutePath.lowercase()) }.map {
            Leftover(CareItem(it, CareFiles.size(it), CareFiles.shortPath(it.parentFile, p.home), CareFiles.lastUsed(it)), isId)
        }
        return make(byId, true) + make(byName, false)
    }
}

// Whether an app is open

object Running {
    private fun norm(path: String, windows: Boolean) = path.replace('\\', '/').trimEnd('/').let { if (windows) it.lowercase() else it }

    /** The processes that belong to the app: its program, anything inside its install folder, its flatpak or snap. */
    fun pids(app: PcApp, procs: List<Proc>, windows: Boolean, resolve: (File) -> String? = { runCatching { it.toPath().toRealPath().toString() }.getOrNull() }): List<Long> {
        val location = app.location?.takeIf { app.kind != AppKind.AppImage }?.path?.let { norm(it, windows) }?.takeIf { it.count { c -> c == '/' } >= 2 }
        val exe = app.exe?.path?.let { norm(it, windows) }
        val exeReal = app.exe?.let(resolve)?.let { norm(it, windows) }
        val exeName = app.exe?.name?.let { if (windows) it.lowercase() else it }?.takeIf { it.length >= 3 && it !in setOf("env", "sh", "bash", "python3", "python", "java", "electron", "flatpak", "snap") }
        return procs.filter { proc ->
            val cmd = norm(proc.command, windows)
            when (app.kind) {
                AppKind.Flatpak -> proc.commandLine.split(' ').any { it == app.packageId } || cmd.startsWith("/app/") && proc.commandLine.contains(app.packageId)
                AppKind.Snap -> cmd.startsWith("/snap/${app.packageId}/")
                AppKind.AppImage -> app.location != null && (cmd == norm(app.location.path, windows) || proc.commandLine.startsWith(app.location.path))
                else -> (location != null && cmd.startsWith("$location/")) || (exe != null && cmd == exe) || (exeReal != null && cmd == exeReal) ||
                    (!windows && exeName != null && cmd.substringAfterLast('/') == exeName)
            }
        }.map { it.pid }
    }
}

/**
 * Quit, then Force quit: after asking an app to quit, it gets [patienceMs] to close. Still open after that, it's stuck
 * and Force quit is offered. Driven by polls of whether it runs, so tests run it with a fake clock.
 */
class QuitTracker(private val clock: () -> Long = System::currentTimeMillis, private val patienceMs: Long = 6_000) {
    enum class Phase { Idle, Quitting, Stuck }

    var phase = Phase.Idle
        private set
    var appId: String? = null
        private set
    private var askedAt = 0L

    fun asked(id: String) {
        appId = id
        phase = Phase.Quitting
        askedAt = clock()
    }

    /** The app's state now; returns the new phase for it. */
    fun update(id: String, running: Boolean): Phase {
        if (id != appId) return Phase.Idle
        phase = when {
            !running -> Phase.Idle
            phase == Phase.Quitting && clock() - askedAt >= patienceMs -> Phase.Stuck
            else -> phase
        }
        if (phase == Phase.Idle) appId = null
        return phase
    }

    fun phaseFor(id: String): Phase = if (id == appId) phase else Phase.Idle

    fun reset() { phase = Phase.Idle; appId = null }
}
