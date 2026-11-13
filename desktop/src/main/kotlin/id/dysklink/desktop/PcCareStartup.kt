package id.dysklink.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where a startup item comes from. */
enum class StartupKind(val label: String) {
    Run("Registry Run key"), RunOnce("Registry RunOnce key"), Folder("Startup folder"), Task("Scheduled task"), Service("Service"),
    Autostart("Autostart"), UserUnit("systemd user service"), SystemUnit("systemd service"),
}

data class StartupEntry(
    val id: String,
    /** The value, file, task, or unit name, shown small. */
    val technical: String,
    val displayName: String,
    val publisher: String = "",
    /** What it does in a few words: checks for updates, syncs files... */
    val purpose: String,
    /** The full command, shown on hover. */
    val command: String,
    val kind: StartupKind,
    val isOff: Boolean,
    /** DyskLink can turn it off and on. */
    val editable: Boolean,
    /** Only an administrator can change it. */
    val adminOnly: Boolean,
    /** Part of the desktop itself, such as the keyring or the sound server: shown, never offered to turn off. */
    val desktopPart: Boolean = false,
    /** The registry key, folder, or unit file it's in. */
    val location: String = "",
    val file: File? = null,
    val regType: String = "REG_SZ",
    /** Turned off in Windows' Startup apps or Task Manager, which DyskLink leaves to Windows. */
    val offInWindows: Boolean = false,
    /** Starts with the PC rather than at login. */
    val atBoot: Boolean = false,
)

/** Friendly names and a purpose line for startup items. */
object StartupNames {
    private val known = mapOf(
        "securityhealth" to "Windows Security", "securityhealthsystray" to "Windows Security", "onedrive" to "OneDrive",
        "onedrivesetup" to "OneDrive setup", "rtkauduservice" to "Realtek Audio", "rthdvcpl" to "Realtek Audio", "realtek hd audio universal service" to "Realtek Audio",
        "igfxtray" to "Intel Graphics", "igfxhk" to "Intel Graphics", "nvbackend" to "NVIDIA GeForce Experience", "steam" to "Steam",
        "discord" to "Discord", "spotify" to "Spotify", "com.squirrel.slack.slack" to "Slack", "com.squirrel.teams.teams" to "Microsoft Teams (classic)",
        "teams" to "Microsoft Teams", "msteams" to "Microsoft Teams", "googledrivefs" to "Google Drive", "dropbox" to "Dropbox",
        "googleupdate" to "Google Updater", "googleupdatetaskmachinecore" to "Google Updater", "googleupdatetaskmachineua" to "Google Updater",
        "microsoftedgeautolaunch" to "Microsoft Edge", "microsoftedgeupdatetaskmachinecore" to "Microsoft Edge Updater", "zoom" to "Zoom",
        "adobeaamupdater" to "Adobe Updater", "adobe acrobat update task" to "Adobe Acrobat Updater", "ccxprocess" to "Adobe Creative Cloud",
        "epicgameslauncher" to "Epic Games Launcher", "everything" to "Everything", "lghub" to "Logitech G HUB", "logioptionsplus" to "Logi Options+",
        "nextcloud" to "Nextcloud", "syncthing" to "Syncthing", "megasync" to "MEGA", "ulauncher" to "Ulauncher", "copyq" to "CopyQ",
        "org.gnome.software" to "Software updates", "gnome-software-service" to "Software updates", "update-notifier" to "Update notifier",
        "nm-applet" to "Network icon", "blueman" to "Bluetooth icon", "print-applet" to "Printer icon", "geoclue-demo-agent" to "Location helper",
        "tracker-miner-fs-3" to "File search indexer", "evolution-alarm-notify" to "Calendar reminders", "orca-autostart" to "Screen reader",
        "snap-userd-autostart" to "Snap helper",
    )

    fun tidy(raw: String): String {
        var name = raw.trim().removeSuffix(".desktop").removeSuffix(".service").removeSuffix(".timer").removeSuffix(".exe").removeSuffix(".lnk")
        val dots = name.split('.')
        if (dots.size >= 3 && dots[0].lowercase() in setOf("com", "org", "net", "io", "de", "us", "id", "app", "dev")) {
            name = dots.drop(2).firstOrNull { it.length > 2 && it.lowercase() !in setOf("desktop", "app", "client") } ?: dots[1]
        }
        name = name.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('-', ' ').replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        return name.split(' ').joinToString(" ") { w -> if (w.length <= 3 && w.all { it.isUpperCase() }) w else w.replaceFirstChar { it.uppercase() } }
    }

    /** The app's own name and publisher when its program is in an installed app's folder, a known name, or the name tidied. */
    fun windows(technical: String, command: String, apps: List<PcApp>): Pair<String, String> {
        val exe = WindowsApps.splitCommand(command).first.replace('/', '\\').lowercase()
        apps.firstOrNull { app ->
            val loc = app.location?.path?.replace('/', '\\')?.lowercase()?.trimEnd('\\')
            (loc != null && loc.count { it == '\\' } >= 2 && exe.startsWith("$loc\\")) || app.exe?.path?.lowercase()?.replace('/', '\\') == exe
        }?.let { return it.name to it.publisher }
        val key = technical.lowercase().removeSuffix(".lnk").removeSuffix(".exe").trim()
        known[key]?.let { return it to "" }
        val exeName = exe.substringAfterLast('\\').removeSuffix(".exe")
        known[exeName]?.let { return it to "" }
        return tidy(technical.ifBlank { exeName }) to ""
    }

    fun linux(id: String, name: String?): String = known[id.removeSuffix(".service").removeSuffix(".timer").lowercase()] ?: name?.takeIf { it.isNotBlank() } ?: tidy(id)

    /** What it does, from its name, command, and description. */
    fun purpose(name: String, command: String, comment: String = "", kind: StartupKind): String {
        val text = "$name $command".lowercase()
        return when {
            listOf("update", "updater", "keystone", "upgrade").any { it in text } -> "Checks for updates"
            listOf("sync", "onedrive", "dropbox", "drivefs", "google drive", "nextcloud", "megasync", "owncloud").any { it in text } -> "Syncs your files"
            listOf("tray", "systray", "applet", "indicator").any { it in text } -> "Shows an icon in the tray"
            listOf("notif", "alarm", "remind").any { it in text } -> "Shows notifications and reminders"
            listOf("licens", "activation").any { it in text } -> "Checks the license"
            listOf("helper", "agent", "daemon", "svc", "broker", "host").any { it in text } -> "Background helper"
            listOf("--minimized", "--hidden", "--background", "/background", "-silent", "--silent", "autostart", "/min", "--startup", "-startup", "--tray").any { it in text } ->
                "Opens in the background at login"
            comment.isNotBlank() -> comment.trim().removeSuffix(".")
            kind == StartupKind.Task -> "Runs a scheduled task"
            kind == StartupKind.Service || kind == StartupKind.SystemUnit -> "Runs as a service"
            kind == StartupKind.UserUnit -> "Runs in the background"
            else -> "Opens at login"
        }
    }
}

// Windows

object WindowsStartup {
    const val RUN = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    const val RUN_ONCE = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\RunOnce"
    const val BACKUP_RUN = "HKCU\\Software\\DyskLink\\DisabledRun"
    const val BACKUP_RUN_ONCE = "HKCU\\Software\\DyskLink\\DisabledRunOnce"
    private const val APPROVED = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved"
    val machineRun = listOf(
        "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\RunOnce",
        "HKLM\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Run",
    )

    fun userStartupFolder(p: CarePlaces) = p.appData?.let { File(it, "Microsoft/Windows/Start Menu/Programs/Startup") }
    fun commonStartupFolder(p: CarePlaces) = p.programData?.let { File(it, "Microsoft/Windows/Start Menu/Programs/StartUp") }
    fun disabledFolder(p: CarePlaces) = File(p.ownData ?: File(p.appData ?: p.home, "DyskLink"), "Disabled startup items")

    /** StartupApproved holds a binary value per item; an odd first byte (03) means turned off in Windows' Startup apps. */
    fun approvedOff(keys: List<RegKey>?): Set<String> = keys.orEmpty().flatMap { it.values }
        .filter { it.type == "REG_BINARY" && it.data.length >= 2 && (it.data.substring(0, 2).toIntOrNull(16) ?: 0) % 2 == 1 }
        .map { it.name.lowercase() }.toSet()

    private fun entry(value: RegValue, kind: StartupKind, key: String, off: Boolean, editable: Boolean, admin: Boolean, apps: List<PcApp>, offInWindows: Boolean = false): StartupEntry {
        val (name, publisher) = StartupNames.windows(value.name, value.data, apps)
        return StartupEntry(
            id = "$key\\${value.name}", technical = value.name, displayName = name, publisher = publisher,
            purpose = StartupNames.purpose(value.name, value.data, kind = kind), command = value.data, kind = kind, isOff = off || offInWindows,
            editable = editable && !offInWindows, adminOnly = admin, location = key, regType = value.type, offInWindows = offInWindows,
        )
    }

    fun registryEntries(reg: Registry, apps: List<PcApp>): List<StartupEntry> {
        val userOff = approvedOff(reg.query("HKCU\\$APPROVED\\Run")) + approvedOff(reg.query("HKCU\\$APPROVED\\Run32"))
        val machineOff = approvedOff(reg.query("HKLM\\$APPROVED\\Run")) + approvedOff(reg.query("HKLM\\$APPROVED\\Run32"))
        fun values(key: String) = reg.query(key)?.firstOrNull { it.path.equals(key, true) || it.path.endsWith(key.substringAfter('\\'), true) }?.values.orEmpty()
            .filter { it.name.isNotEmpty() && (it.type == "REG_SZ" || it.type == "REG_EXPAND_SZ") }
        val list = mutableListOf<StartupEntry>()
        values(RUN).forEach { list += entry(it, StartupKind.Run, RUN, false, true, false, apps, offInWindows = it.name.lowercase() in userOff) }
        values(RUN_ONCE).forEach { list += entry(it, StartupKind.RunOnce, RUN_ONCE, false, true, false, apps) }
        values(BACKUP_RUN).forEach { list += entry(it, StartupKind.Run, BACKUP_RUN, true, true, false, apps) }
        values(BACKUP_RUN_ONCE).forEach { list += entry(it, StartupKind.RunOnce, BACKUP_RUN_ONCE, true, true, false, apps) }
        machineRun.forEach { key ->
            values(key).forEach { list += entry(it, if (key.endsWith("RunOnce")) StartupKind.RunOnce else StartupKind.Run, key, false, false, true, apps, offInWindows = it.name.lowercase() in machineOff) }
        }
        return list
    }

    /** Moves the value to DyskLink's own key, so it doesn't run at the next login and can come back as it was. */
    fun turnOff(entry: StartupEntry, reg: Registry): String? {
        val source = entry.location
        val backup = when (source) { RUN -> BACKUP_RUN; RUN_ONCE -> BACKUP_RUN_ONCE; else -> return "Only an administrator can change ${entry.displayName}." }
        // Kept first, then taken out, so a failure halfway never loses it.
        if (!reg.set(backup, entry.technical, entry.regType, entry.command)) return "Couldn't save ${entry.displayName}'s setting, so it stays on."
        if (!reg.delete(source, entry.technical)) {
            reg.delete(backup, entry.technical)
            return "Windows didn't let DyskLink change ${entry.displayName}."
        }
        return null
    }

    fun turnOn(entry: StartupEntry, reg: Registry): String? {
        val target = when (entry.location) { BACKUP_RUN -> RUN; BACKUP_RUN_ONCE -> RUN_ONCE; else -> return null }
        val there = reg.query(target)?.flatMap { it.values }?.any { it.name.equals(entry.technical, true) } == true
        if (there) {
            // The app put itself back meanwhile: the kept copy is no longer needed.
            reg.delete(entry.location, entry.technical)
            return null
        }
        if (!reg.set(target, entry.technical, entry.regType, entry.command)) return "Windows didn't let DyskLink turn ${entry.displayName} back on."
        reg.delete(entry.location, entry.technical)
        return null
    }

    fun folderEntries(p: CarePlaces, apps: List<PcApp>, approvedOff: Set<String> = emptySet()): List<StartupEntry> {
        fun read(folder: File?, off: Boolean, editable: Boolean, admin: Boolean): List<StartupEntry> = CareFiles.children(folder ?: return emptyList())
            .filter { it.isFile && it.name.lowercase() != "desktop.ini" }
            .map { file ->
                val (name, publisher) = StartupNames.windows(file.name.substringBeforeLast('.'), file.path, apps)
                val offInWindows = !off && file.name.lowercase() in approvedOff
                StartupEntry(
                    id = file.path, technical = file.name, displayName = name, publisher = publisher,
                    purpose = StartupNames.purpose(file.name, "", kind = StartupKind.Folder), command = file.path, kind = StartupKind.Folder,
                    isOff = off || offInWindows, editable = editable && !offInWindows, adminOnly = admin, location = folder.path, file = file, offInWindows = offInWindows,
                )
            }
        return read(userStartupFolder(p), false, true, false) + read(disabledFolder(p), true, true, false) + read(commonStartupFolder(p), false, false, true)
    }

    /** Moves a shortcut out of the Startup folder into DyskLink's, or back. */
    fun moveFolderItem(entry: StartupEntry, p: CarePlaces, on: Boolean): String? {
        val file = entry.file ?: return null
        val target = File((if (on) userStartupFolder(p) else disabledFolder(p)) ?: return "There's no Startup folder.", file.name)
        if (target.exists()) return "${target.name} is already there."
        return runCatching {
            target.parentFile.mkdirs()
            Files.move(file.toPath(), target.toPath())
            null
        }.getOrElse { "Couldn't move ${file.name}: ${it.message ?: "it may be in use"}." }
    }

    /** A CSV row, with quoted fields and doubled quotes. */
    fun csvRow(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }

    /**
     * `schtasks /query /fo csv /v`: tasks that run at logon or startup, outside Microsoft's own folder. Each trigger is
     * a row and the header repeats, so tasks are joined by name. Columns are found by their English names, or by
     * their place when Windows speaks another language.
     */
    fun parseTasks(csv: String, user: String, apps: List<PcApp>): List<StartupEntry> {
        val rows = csv.lines().filter { it.isNotBlank() }.map(::csvRow)
        val header = rows.firstOrNull() ?: return emptyList()
        fun col(name: String, fallback: Int) = header.indexOfFirst { it.equals(name, true) }.takeIf { it >= 0 } ?: fallback
        val cName = col("TaskName", 1); val cStatus = col("Status", 3); val cRun = col("Task To Run", 8); val cComment = col("Comment", 10)
        val cState = col("Scheduled Task State", 11); val cUser = col("Run As User", 14); val cType = col("Schedule Type", 18); val cAuthor = col("Author", 7)
        val tasks = linkedMapOf<String, MutableList<List<String>>>()
        for (row in rows) {
            if (row == header || row.size <= cType) continue
            val name = row[cName]
            if (name.startsWith("\\Microsoft\\", true) || name.isBlank()) continue
            tasks.getOrPut(name) { mutableListOf() } += row
        }
        return tasks.mapNotNull { (name, list) ->
            val types = list.map { it[cType].lowercase() }
            val logon = types.any { "logon" in it || "log on" in it || "anmeld" in it || "connexion" in it }
            val boot = types.any { "startup" in it || "system start" in it || "démarrage" in it }
            if (!logon && !boot) return@mapNotNull null
            val first = list.first()
            val runAs = first.getOrNull(cUser).orEmpty()
            val mine = user.isNotBlank() && runAs.substringAfterLast('\\').equals(user, true)
            val command = first.getOrNull(cRun).orEmpty()
            val short = name.substringAfterLast('\\')
            val (display, publisher) = StartupNames.windows(short.substringBefore(" {").substringBefore("-S-1-5"), command, apps)
            val off = first.getOrNull(cState).orEmpty().equals("Disabled", true) || first.getOrNull(cStatus).orEmpty().equals("Disabled", true)
            StartupEntry(
                id = "task:$name", technical = name, displayName = display, publisher = publisher.ifEmpty { first.getOrNull(cAuthor).orEmpty().substringAfterLast('\\').takeIf { !it.startsWith("S-1-") && !it.contains("$(") }.orEmpty() },
                purpose = StartupNames.purpose(short, command, first.getOrNull(cComment).orEmpty().takeIf { it.length < 80 }.orEmpty(), StartupKind.Task),
                command = command, kind = StartupKind.Task, isOff = off, editable = mine, adminOnly = !mine, location = "Task Scheduler", atBoot = boot && !logon,
            )
        }
    }

    /** Services that start by themselves, from Get-CimInstance Win32_Service as JSON; Windows' own are left out. */
    const val SERVICES_SCRIPT = "Get-CimInstance Win32_Service -Filter \"StartMode='Auto'\" | Select-Object Name,DisplayName,PathName,State,StartName | ConvertTo-Json -Compress"

    fun parseServices(json: String, apps: List<PcApp>): List<StartupEntry> = PowerShell.jsonList(json).mapNotNull { o ->
        val name = PowerShell.str(o, "Name").ifEmpty { return@mapNotNull null }
        val path = PowerShell.str(o, "PathName")
        val lower = path.lowercase().replace('/', '\\')
        if (lower.isEmpty() || "\\windows\\" in lower || lower.startsWith("system32") || "svchost.exe" in lower) return@mapNotNull null
        val shown = PowerShell.str(o, "DisplayName").ifEmpty { name }
        val (fromApp, publisher) = StartupNames.windows(shown, path, apps)
        StartupEntry(
            id = "service:$name", technical = name, displayName = if (publisher.isNotEmpty()) fromApp else shown, publisher = publisher,
            purpose = StartupNames.purpose(shown, path, kind = StartupKind.Service) + if (PowerShell.str(o, "State") == "Running") " · running" else "",
            command = path, kind = StartupKind.Service, isOff = false, editable = false, adminOnly = true, location = "Services", atBoot = true,
        )
    }

    fun read(sys: CareSystem, reg: Registry, p: CarePlaces, apps: List<PcApp>): List<StartupEntry> {
        val folderOff = approvedOff(reg.query("HKCU\\$APPROVED\\StartupFolder")) + approvedOff(reg.query("HKLM\\$APPROVED\\StartupFolder"))
        val user = sys.env("USERNAME").orEmpty()
        val tasks = sys.run(listOf("schtasks", "/query", "/fo", "csv", "/v"), 90_000)?.takeIf { it.code == 0 }?.let { parseTasks(it.out, user, apps) }.orEmpty()
        val services = PowerShell.run(sys, SERVICES_SCRIPT)?.takeIf { it.code == 0 }?.let { parseServices(it.out, apps) }.orEmpty()
        return registryEntries(reg, apps) + folderEntries(p, apps, folderOff) + tasks + services
    }

    /** Turns a task off or on with schtasks, which only works for the user's own tasks. */
    fun setTask(sys: CareSystem, entry: StartupEntry, on: Boolean): String? {
        val out = sys.run(listOf("schtasks", "/change", "/tn", entry.technical, if (on) "/enable" else "/disable"), 30_000)
        return when {
            out == null -> "Task Scheduler didn't answer."
            out.code == 0 -> null
            (out.err + out.out).contains("denied", true) -> "${entry.displayName} needs an administrator to change."
            else -> "Couldn't change ${entry.displayName}: " + (out.err + out.out).lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty().ifEmpty { "exit code ${out.code}" }
        }
    }
}

// Linux

object LinuxStartup {
    const val OVERRIDE_KEY = "X-DyskLink-Override"
    private val desktopParts = listOf(
        "org.gnome.", "gnome-", "gsd-", "at-spi", "xdg-user-dirs", "pulseaudio", "pipewire", "wireplumber", "polkit", "geoclue", "ibus", "fcitx",
        "im-launch", "org.kde.", "kde", "plasma", "xembedsniproxy", "baloo", "user-dirs-update", "spice-vdagent", "tracker-", "xfce", "xfsettingsd",
        "lxqt", "lxsession", "mate-", "cinnamon", "nemo-autostart", "budgie", "gnome-keyring", "ssh-agent", "gpg-agent", "dbus", "xdg-", "p11-kit",
        "snapd.session-agent", "filter-chain", "obex", "evolution-data-server", "gvfs", "dconf", "xapp-", "zeitgeist",
    )

    fun isDesktopPart(id: String, entry: DesktopEntry? = null): Boolean {
        val lower = id.lowercase()
        if (desktopParts.any { lower.startsWith(it) }) return true
        entry ?: return false
        return entry["X-GNOME-AutoRestart"] == "true" || entry["X-GNOME-Autostart-Phase"] != null || entry["X-KDE-autostart-phase"] != null
    }

    private fun off(e: DesktopEntry) = e["Hidden"] == "true" || e["X-GNOME-Autostart-enabled"] == "false"

    /** ~/.config/autostart over /etc/xdg/autostart: a file of the same name in the user's folder replaces the system's. */
    fun autostart(userDir: File, systemDirs: List<File>, readText: (File) -> String? = { runCatching { it.readText() }.getOrNull() }): List<StartupEntry> {
        val user = CareFiles.children(userDir).filter { it.name.endsWith(".desktop") }.mapNotNull { f -> readText(f)?.let { DesktopFiles.parse(f, it) } }.associateBy { it.file.name }
        val system = systemDirs.flatMap { dir -> CareFiles.children(dir).filter { it.name.endsWith(".desktop") } }
            .mapNotNull { f -> readText(f)?.let { DesktopFiles.parse(f, it) } }.associateBy { it.file.name }
        val names = (user.keys + system.keys).sorted()
        return names.mapNotNull { name ->
            val mine = user[name]
            val sys = system[name]
            val shown = mine ?: sys!!
            if (mine == null && sys!!["Hidden"] == "true") return@mapNotNull null
            val isOff = off(shown)
            val part = isDesktopPart(shown.id, sys ?: mine)
            val command = shown["Exec"].orEmpty()
            StartupEntry(
                id = "autostart:$name", technical = name, displayName = StartupNames.linux(shown.id, shown["Name"]),
                purpose = StartupNames.purpose(shown.id + " " + shown.name, command, shown["Comment"].orEmpty(), StartupKind.Autostart), command = command,
                kind = StartupKind.Autostart, isOff = isOff, editable = !part || isOff, adminOnly = false, desktopPart = part,
                location = (mine ?: sys)!!.file.parent, file = mine?.file ?: sys!!.file,
            )
        }
    }

    /**
     * Off: Hidden=true and X-GNOME-Autostart-enabled=false in the user's file, as GNOME Tweaks and KDE do. A system item
     * gets a copy in the user's folder first, marked as DyskLink's, so the system's file is never touched.
     */
    fun setAutostart(entry: StartupEntry, userDir: File, on: Boolean, readText: (File) -> String? = { runCatching { it.readText() }.getOrNull() }): String? {
        val file = entry.file ?: return null
        val inUser = file.parentFile?.absolutePath == userDir.absolutePath
        val text = readText(file) ?: return "Couldn't read ${file.name}."
        val keys = mutableMapOf("Hidden" to (!on).toString(), "X-GNOME-Autostart-enabled" to on.toString())
        if (!inUser) keys[OVERRIDE_KEY] = "true"
        val target = File(userDir, file.name)
        return runCatching {
            userDir.mkdirs()
            val temp = File(userDir, ".${file.name}.dysklink-tmp")
            temp.writeText(DesktopFiles.withKeys(text, keys))
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            null
        }.getOrElse { "Couldn't change ${entry.displayName}: ${it.message ?: "the folder isn't writable"}." }
    }

    /** `systemctl list-unit-files --state=enabled --no-legend`: unit, state, and the preset. */
    fun parseUnitFiles(out: String): List<String> = out.lines().mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        parts.firstOrNull()?.takeIf { it.endsWith(".service") || it.endsWith(".timer") }?.takeIf { '@' !in it || !it.contains("@.") }
    }

    /** `systemctl show -p Id -p Description -p ExecStart units…`: blocks with a blank line between. */
    fun parseShow(out: String): Map<String, Pair<String, String>> {
        val result = mutableMapOf<String, Pair<String, String>>()
        for (block in out.split(Regex("\\r?\\n\\s*\\r?\\n"))) {
            val values = block.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            val id = values["Id"] ?: continue
            val exec = values["ExecStart"].orEmpty().let { Regex("argv\\[]=([^;]*)").find(it)?.groupValues?.get(1)?.trim() ?: it }
            result[id] = values["Description"].orEmpty() to exec
        }
        return result
    }

    fun units(enabled: List<String>, disabledByUs: List<String>, show: Map<String, Pair<String, String>>, system: Boolean): List<StartupEntry> {
        val all = (enabled.map { it to false } + disabledByUs.filter { it !in enabled }.map { it to true })
        return all.map { (unit, off) ->
            val (description, exec) = show[unit] ?: ("" to "")
            val part = isDesktopPart(unit)
            val kind = if (system) StartupKind.SystemUnit else StartupKind.UserUnit
            StartupEntry(
                id = (if (system) "system:" else "user:") + unit, technical = unit, displayName = StartupNames.linux(unit, description.takeIf { it.length in 1..48 }),
                purpose = StartupNames.purpose(unit, exec, description.takeIf { it.length > 48 }.orEmpty(), kind), command = exec.ifEmpty { unit }, kind = kind,
                isOff = off, editable = !system && (!part || off), adminOnly = system, desktopPart = part, location = if (system) "systemd" else "systemd --user", atBoot = system,
            )
        }
    }

    fun disabledRecord(p: CarePlaces) = File(p.ownData ?: File(p.config, "dysklink"), "care-disabled-units.txt")

    fun read(sys: CareSystem, p: CarePlaces, systemAutostart: List<File> = listOf(File("/etc/xdg/autostart"))): List<StartupEntry> {
        val list = autostart(File(p.config, "autostart"), systemAutostart).toMutableList()
        if (sys.onPath("systemctl")) {
            val record = runCatching { disabledRecord(p).readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())
            val enabled = sys.run(listOf("systemctl", "--user", "list-unit-files", "--state=enabled", "--no-legend", "--no-pager"), 20_000)?.out?.let(::parseUnitFiles).orEmpty()
            val user = (enabled + record).distinct()
            val userShow = if (user.isEmpty()) emptyMap() else sys.run(listOf("systemctl", "--user", "show", "-p", "Id", "-p", "Description", "-p", "ExecStart") + user, 20_000)?.out?.let(::parseShow).orEmpty()
            list += units(enabled, record, userShow, system = false)
            val systemUnits = sys.run(listOf("systemctl", "list-unit-files", "--type=service", "--state=enabled", "--no-legend", "--no-pager"), 20_000)?.out?.let(::parseUnitFiles).orEmpty()
            val systemShow = if (systemUnits.isEmpty()) emptyMap() else sys.run(listOf("systemctl", "show", "-p", "Id", "-p", "Description", "-p", "ExecStart") + systemUnits, 20_000)?.out?.let(::parseShow).orEmpty()
            list += units(systemUnits, emptyList(), systemShow, system = true)
        }
        return list
    }

    fun setUnit(sys: CareSystem, p: CarePlaces, entry: StartupEntry, on: Boolean): String? {
        val out = sys.run(listOf("systemctl", "--user", if (on) "enable" else "disable", "--now", entry.technical), 30_000)
        if (out == null || out.code != 0) {
            val why = out?.let { (it.err + it.out).lines().lastOrNull { l -> l.isNotBlank() }?.trim() }.orEmpty()
            return "Couldn't change ${entry.displayName}" + if (why.isNotEmpty()) ": $why" else "."
        }
        val file = disabledRecord(p)
        val record = runCatching { file.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList()).toMutableList()
        if (on) record.remove(entry.technical) else if (entry.technical !in record) record += entry.technical
        runCatching { file.parentFile.mkdirs(); file.writeText(record.joinToString("\n")) }
        return null
    }
}
