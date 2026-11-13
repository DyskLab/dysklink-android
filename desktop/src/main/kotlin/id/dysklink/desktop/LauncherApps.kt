package id.dysklink.desktop

import java.io.File

/**
 * The apps the Launcher finds. Linux: .desktop files in the XDG data folders, Flatpak's and Snap's exports included,
 * as the desktop's own menu shows them. Windows: Start menu shortcuts, and every app Get-StartApps lists (Store apps too).
 */
object DesktopEntries {
    /** The locale names to look for, best first: "id_ID.UTF-8" gives id_ID, id. */
    fun languages(env: (String) -> String?): List<String> {
        val raw = listOf("LC_ALL", "LC_MESSAGES", "LANG").firstNotNullOfOrNull { env(it)?.takeIf { v -> v.isNotBlank() && v != "C" && v != "POSIX" } } ?: return emptyList()
        val noEncoding = raw.substringBefore('.').let { base -> if ('@' in raw) base.substringBefore('@') + "@" + raw.substringAfter('@') else base }
        val modifier = noEncoding.substringAfter('@', "")
        val langCountry = noEncoding.substringBefore('@')
        val lang = langCountry.substringBefore('_')
        return buildList {
            if (modifier.isNotEmpty() && '_' in langCountry) add("$langCountry@$modifier")
            if ('_' in langCountry) add(langCountry)
            if (modifier.isNotEmpty()) add("$lang@$modifier")
            add(lang)
        }.distinct()
    }

    /** The data folders, most important first: the user's own, the system's, and Flatpak's and Snap's exports. */
    fun dataDirs(env: (String) -> String?, home: String): List<String> {
        val user = env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share"
        val system = env("XDG_DATA_DIRS")?.takeIf { it.isNotBlank() }?.split(':')?.filter { it.isNotBlank() } ?: listOf("/usr/local/share", "/usr/share")
        val exports = listOf("$home/.local/share/flatpak/exports/share", "/var/lib/flatpak/exports/share", "/var/lib/snapd/desktop")
        return (listOf(user) + system + exports).map { it.trimEnd('/') }.distinct()
    }

    /** One [Desktop Entry] group, keys as written (localized ones like Name[id] included). */
    fun group(text: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        var inEntry = false
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            if (line.startsWith("[")) { inEntry = line == "[Desktop Entry]"; return@forEach }
            if (!inEntry) return@forEach
            val eq = line.indexOf('=')
            if (eq <= 0) return@forEach
            val key = line.substring(0, eq).trim()
            if (key !in values) values[key] = unescape(line.substring(eq + 1).trim())
        }
        return values
    }

    private fun unescape(value: String): String {
        if ('\\' !in value) return value
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    's' -> out.append(' '); 'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r'); '\\' -> out.append('\\')
                    else -> out.append(c).append(value[i + 1])
                }
                i += 2
            } else { out.append(c); i++ }
        }
        return out.toString()
    }

    private fun localized(values: Map<String, String>, key: String, langs: List<String>): String? =
        langs.firstNotNullOfOrNull { values["$key[$it]"]?.takeIf { v -> v.isNotBlank() } } ?: values[key]

    /**
     * The app a .desktop file describes, or null when the menu wouldn't show it: not an application, NoDisplay or
     * Hidden, not meant for this desktop (OnlyShowIn, NotShowIn), or its TryExec isn't installed.
     */
    fun parse(text: String, id: String, path: String, langs: List<String>, desktops: List<String>, onPath: (String) -> Boolean = { true }): LauncherApp? {
        val v = group(text)
        if (v["Type"] != "Application") return null
        if (v["NoDisplay"].equals("true", true) || v["Hidden"].equals("true", true)) return null
        val mine = desktops.map { it.lowercase() }
        v["OnlyShowIn"]?.let { only -> if (only.split(';').filter { it.isNotBlank() }.none { it.lowercase() in mine }) return null }
        v["NotShowIn"]?.let { not -> if (not.split(';').any { it.isNotBlank() && it.lowercase() in mine }) return null }
        v["TryExec"]?.takeIf { it.isNotBlank() }?.let { tryExec -> if (!(if (tryExec.startsWith("/")) File(tryExec).canExecute() else onPath(tryExec))) return null }
        val name = localized(v, "Name", langs)?.takeIf { it.isNotBlank() } ?: return null
        val exec = v["Exec"]?.let { exec(it, name, path) }?.takeIf { it.isNotEmpty() } ?: return null
        val generic = localized(v, "GenericName", langs).orEmpty()
        val keywords = listOfNotNull(localized(v, "Keywords", langs), v["Keywords"], generic, v["Name"].takeIf { it != name }, id.removeSuffix(".desktop"))
            .joinToString(" ").replace(';', ' ')
        return LauncherApp(
            "app:$id", name,
            AppLaunch.DesktopFile(path, exec, terminal = v["Terminal"].equals("true", true), workDir = v["Path"]?.takeIf { it.isNotBlank() }),
            keywords = keywords, detail = generic.ifEmpty { v["Comment"].orEmpty() },
        )
    }

    /**
     * Exec as a command line: split as the spec quotes it, field codes such as %U taken out (the Launcher opens
     * apps without files), %c the name, %k the file, %% a percent sign.
     */
    fun exec(line: String, name: String = "", path: String = ""): List<String> {
        val args = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var started = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '\\' && i + 1 < line.length && line[i + 1] in "\"`$\\" -> { current.append(line[i + 1]); i++ }
                c == '"' -> { quoted = !quoted; started = true }
                !quoted && c == ' ' -> { if (started || current.isNotEmpty()) args += current.toString(); current.clear(); started = false }
                else -> current.append(c)
            }
            i++
        }
        if (started || current.isNotEmpty()) args += current.toString()
        return args.mapNotNull { arg ->
            when (arg) {
                "%f", "%F", "%u", "%U", "%d", "%D", "%n", "%N", "%v", "%m", "%i" -> null
                "%c" -> name
                "%k" -> path
                else -> arg.replace(Regex("%[fFuUdDnNvmi]"), "").replace("%c", name).replace("%k", path).replace("%%", "%").takeIf { it.isNotEmpty() }
            }
        }
    }

    /** Every app in [dataDirs]: the first file with an id wins, so a user's own copy (even a Hidden one) hides the system's. */
    fun scan(dataDirs: List<String>, langs: List<String>, desktops: List<String>, onPath: (String) -> Boolean): List<LauncherApp> {
        val seen = mutableSetOf<String>()
        val apps = mutableListOf<LauncherApp>()
        for (dir in dataDirs) {
            val root = File(dir, "applications")
            if (!root.isDirectory) continue
            root.walkTopDown().maxDepth(3).filter { it.isFile && it.name.endsWith(".desktop") }.forEach { file ->
                val id = file.relativeTo(root).path.replace(File.separatorChar, '-')
                if (!seen.add(id)) return@forEach
                runCatching { parse(file.readText(), id, file.path, langs, desktops, onPath) }.getOrNull()?.let { apps += it }
            }
        }
        return apps.sortedBy { it.name.lowercase() }
    }
}

object StartMenuApps {
    /** The Start menu folders, the machine's and this user's. */
    fun startMenus(env: (String) -> String?): List<File> = listOfNotNull(
        env("ProgramData")?.let { File(it, "Microsoft\\Windows\\Start Menu\\Programs") },
        env("APPDATA")?.let { File(it, "Microsoft\\Windows\\Start Menu\\Programs") },
    )

    /** Uninstallers and read-mes are left out, as the Start menu's search does. */
    private val skip = Regex("""\b(uninstall|uninstaller|readme|read me|release notes|website|help)\b""", RegexOption.IGNORE_CASE)

    /** The shortcuts (.lnk, and .url for things like Steam games) under the Start menu folders, by their names. */
    fun shortcuts(roots: List<File>): List<LauncherApp> {
        val seen = mutableSetOf<String>()
        return roots.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().maxDepth(4).filter { it.isFile && (it.name.endsWith(".lnk", true) || it.name.endsWith(".url", true)) }.mapNotNull { file ->
                val name = file.name.substringBeforeLast('.')
                if (skip.containsMatchIn(name) || !seen.add(name.lowercase())) return@mapNotNull null
                val folder = file.parentFile.relativeTo(root).path.takeIf { it.isNotEmpty() }.orEmpty()
                LauncherApp("lnk:${name.lowercase()}", name, AppLaunch.Shortcut(file.path), keywords = folder, detail = folder)
            }.toList()
        }
    }

    /**
     * PowerShell that lists every app as "Name<TAB>AppID", in UTF-8. Get-StartApps has Store apps the Start menu
     * folders don't.
     */
    const val START_APPS_SCRIPT = "[Console]::OutputEncoding = [Text.Encoding]::UTF8; Get-StartApps | ForEach-Object { \$_.Name + \"`t\" + \$_.AppID }"

    /** Get-StartApps' output: "Name<TAB>AppID" lines, or the default table with a dashed line under Name and AppID. */
    fun parseStartApps(output: String): List<Pair<String, String>> {
        val lines = output.lines().map { it.trimEnd('\r') }.filter { it.isNotBlank() }
        if (lines.any { '\t' in it }) {
            return lines.mapNotNull { line ->
                val name = line.substringBefore('\t').trim()
                val id = line.substringAfter('\t', "").trim()
                (name to id).takeIf { name.isNotEmpty() && id.isNotEmpty() }
            }
        }
        val dashes = lines.indexOfFirst { it.trim().startsWith("----") }
        if (dashes < 0) return emptyList()
        val column = Regex("""-+\s+(-)""").find(lines[dashes])?.groups?.get(1)?.range?.first ?: return emptyList()
        return lines.drop(dashes + 1).mapNotNull { line ->
            if (line.length <= column) return@mapNotNull null
            val name = line.substring(0, column).trim()
            val id = line.substring(column).trim()
            (name to id).takeIf { name.isNotEmpty() && id.isNotEmpty() }
        }
    }

    /** Shortcuts first (they can be shown in their folder); then Get-StartApps' apps the shortcuts don't have. */
    fun merge(shortcuts: List<LauncherApp>, startApps: List<Pair<String, String>>): List<LauncherApp> {
        val names = shortcuts.map { it.name.lowercase() }.toMutableSet()
        val extra = startApps.filter { (name, _) -> !skip.containsMatchIn(name) && names.add(name.lowercase()) }.map { (name, id) ->
            LauncherApp("uwp:$id", name, AppLaunch.AppsFolder(id), detail = if ('!' in id) "Microsoft Store app" else "")
        }
        return (shortcuts + extra).sortedBy { it.name.lowercase() }
    }

    /** Runs a PowerShell script, handed over as Base64 so no quoting can break it. */
    fun powershell(script: String, timeoutSeconds: Long = 20): String? = runCatching {
        val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder("powershell", "-NoLogo", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
            .redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) out else null
    }.getOrNull()
}
