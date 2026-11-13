package id.dysklink.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What PC care says about things outside DyskLink, per tab. */
object CareHints {
    fun cleaner(windows: Boolean, distro: Distro, onPath: (String) -> Boolean, journalUsage: String? = null): List<HostHint> {
        if (windows) {
            return listOf(
                HostHint(
                    "Windows Update and system files",
                    "Old update files and other system files can only be cleaned by Windows itself. Open Disk Cleanup and choose " +
                        "'Clean up system files', or use Storage Sense in Settings.",
                    command = "cleanmgr", link = HintLink("Open Storage", "ms-settings:storagesense"), tone = HintTone.Info,
                ),
            )
        }
        val hints = mutableListOf<HostHint>()
        if (onPath("journalctl")) {
            hints += HostHint(
                "System logs",
                "The system journal" + (journalUsage?.let { " takes $it now. It" } ?: "") + " needs an administrator to shrink, so DyskLink leaves it to you. This keeps the last two weeks:",
                command = "sudo journalctl --vacuum-time=2weeks", tone = HintTone.Info,
            )
        }
        if (onPath("flatpak")) {
            hints += HostHint("Unused Flatpak runtimes", "Runtimes no app needs any more stay installed until you remove them:", command = "flatpak uninstall --unused", tone = HintTone.Info)
        }
        val cache = when (distro) {
            Distro.Debian -> "sudo apt clean"
            Distro.Fedora -> "sudo dnf clean all"
            Distro.Arch -> "sudo pacman -Sc"
            Distro.Suse -> "sudo zypper clean --all"
            Distro.Other -> null
        }
        cache?.let { hints += HostHint("Downloaded packages", "The package manager keeps every package it downloaded. Clearing them needs an administrator:", command = it, tone = HintTone.Info) }
        return hints
    }

    fun uninstall(windows: Boolean, distro: Distro, onPath: (String) -> Boolean): List<HostHint> {
        if (windows) {
            return listOf(
                HostHint(
                    "Uninstalling uses the app's own uninstaller",
                    "Its window opens so you can follow it. Apps installed for all users make Windows ask for permission first.",
                    tone = HintTone.Info,
                ),
            )
        }
        if (onPath("pkexec")) return emptyList()
        val install = when (distro) {
            Distro.Debian -> "sudo apt install pkexec"
            Distro.Fedora -> "sudo dnf install polkit"
            Distro.Arch -> "sudo pacman -S polkit"
            Distro.Suse -> "sudo zypper install polkit"
            Distro.Other -> null
        }
        return listOf(
            HostHint(
                "Removing packages needs pkexec",
                "DyskLink asks for your password through polkit's pkexec, which isn't installed. Flatpaks and AppImages still work. Install it, then log out and back in:",
                command = install,
            ),
        )
    }

    fun startup(windows: Boolean, entries: List<StartupEntry>): List<HostHint> {
        if (windows) {
            val hints = mutableListOf<HostHint>()
            if (entries.any { it.adminOnly && it.kind != StartupKind.Service } || entries.any { it.offInWindows }) {
                hints += HostHint(
                    "Items for all users, and ones turned off in Windows",
                    "These need an administrator, or were turned off in Windows' Startup apps, so they're shown here to check. Change them in Settings › Apps › Startup or in Task Manager.",
                    link = HintLink("Open Startup apps", "ms-settings:startupapps"), tone = HintTone.Info,
                )
            }
            if (entries.any { it.kind == StartupKind.Service }) {
                hints += HostHint("Services", "Services start with Windows. Change how one starts in Services, as an administrator:", command = "services.msc", tone = HintTone.Info)
            }
            return hints
        }
        return if (entries.any { it.kind == StartupKind.SystemUnit }) listOf(
            HostHint("System services", "These start with the PC for every user and need an administrator to change. To stop one from starting:", command = "sudo systemctl disable --now NAME.service", tone = HintTone.Info),
        ) else emptyList()
    }
}

/** State for the PC care page. Scans run in the background; everything removed goes to the Recycle Bin or the Trash. */
class PcCareModel(
    val sys: CareSystem,
    private val scope: CoroutineScope,
    ownData: File? = null,
    private val ui: CoroutineContext = Dispatchers.Main,
    private val io: CoroutineContext = Dispatchers.IO,
    places: CarePlaces? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Tab(val title: String) { Cleaner("Cleaner"), Duplicates("Duplicates"), Uninstall("Uninstall"), Startup("Startup") }

    sealed class Confirm {
        data object Clean : Confirm()
        data object Duplicates : Confirm()
        data class Uninstall(val app: PcApp, val plan: UninstallPlan) : Confirm()
        data object Leftovers : Confirm()
    }

    val places = places ?: CarePlaces.of(sys, ownData)
    val guard = CareGuard.forPlaces(this.places)
    private val registry: Registry? = if (sys.windows) WindowsRegistry(sys) else null
    val windows get() = sys.windows
    val bin get() = if (sys.windows) "Recycle Bin" else "Trash"
    private val distro by lazy { HostChecks.distro(runCatching { File("/etc/os-release").readText() }.getOrDefault("")) }

    /** Off in snapshots, so their sample lists stay. */
    var loadsOnShow = true
    var tab by mutableStateOf(Tab.Cleaner)
    var confirm by mutableStateOf<Confirm?>(null)
    /** A short line after each removal, such as "Moved 12 items, 3.4 GB, to the Recycle Bin." Never empty. */
    var message by mutableStateOf<String?>(null)
    var messageIsProblem by mutableStateOf(false)

    private fun say(text: String?, problem: Boolean = false) {
        message = text?.takeIf { it.isNotBlank() }
        messageIsProblem = problem
    }

    // Cleaner

    var cleanGroups by mutableStateOf<Map<CleanGroup, List<CareItem>>>(emptyMap())
    var cleanSelected by mutableStateOf<Set<String>>(emptySet())
    var isCleaning by mutableStateOf(false)
    var cleanStep by mutableStateOf<String?>(null)
    var cleanScanned by mutableStateOf(false)
    var expandedGroups by mutableStateOf<Set<CleanGroup>>(emptySet())
    var cleanHints by mutableStateOf<List<HostHint>>(emptyList())

    fun scanCleaner() {
        if (isCleaning) return
        isCleaning = true
        cleanStep = "app caches"
        scope.launch(io) {
            val groups = Cleaner.scan(places, clock(), progress = { step -> scope.launch(ui) { cleanStep = step } })
            val hints = CareHints.cleaner(sys.windows, distro, sys::onPath, journalUsage())
            withContext(ui) {
                cleanGroups = groups
                cleanSelected = groups.filterKeys { it.selectedByDefault }.values.flatten().filter { it.preselect }.map { it.id }.toSet()
                cleanHints = hints
                isCleaning = false
                cleanStep = null
                cleanScanned = true
            }
        }
    }

    private fun journalUsage(): String? {
        if (sys.windows || !sys.onPath("journalctl")) return null
        val out = sys.run(listOf("journalctl", "--disk-usage"), 10_000)?.out ?: return null
        return Regex("take up ([0-9.,]+\\s?[KMGT]?)").find(out)?.groupValues?.get(1)?.let { "${it.trim()}B" }
    }

    val cleanTotal: Long get() = cleanGroups.values.flatten().sumOf { it.size }
    val cleanSelection: List<CareItem> get() = cleanGroups.values.flatten().filter { it.id in cleanSelected }.distinctBy { it.id }

    fun toggleClean(id: String) { cleanSelected = if (id in cleanSelected) cleanSelected - id else cleanSelected + id }

    fun toggleGroup(group: CleanGroup) {
        val ids = cleanGroups[group].orEmpty().map { it.id }
        cleanSelected = if (ids.all { it in cleanSelected }) cleanSelected - ids.toSet() else cleanSelected + ids
    }

    fun cleanSelectedItems() {
        trash(cleanSelection) { moved ->
            cleanGroups = cleanGroups.mapValues { (_, items) -> items.filter { it.id !in moved } }
            cleanSelected = cleanSelected - moved
        }
    }

    // Duplicates

    var dupFolders by mutableStateOf(defaultFolders())
    var dupMinimum by mutableStateOf(1_000_000L)
    var keepRule by mutableStateOf(KeepRule.Oldest)
    var dupGroups by mutableStateOf<List<DuplicateGroup>>(emptyList())
    var dupSelected by mutableStateOf<Set<String>>(emptySet())
    var isFindingDups by mutableStateOf(false)
    var dupChecked by mutableStateOf(0)
    var dupScanned by mutableStateOf(false)
    private var dupJob: Job? = null

    fun defaultFolders(): List<File> = listOf(places.downloads, File(places.home, "Documents"), File(places.home, "Desktop"))

    val folderChoices: List<File> get() = listOf(places.downloads) + listOf("Documents", "Desktop", "Pictures", "Videos", "Music").map { File(places.home, it) }

    fun toggleFolder(folder: File) { dupFolders = if (folder in dupFolders) dupFolders - folder else dupFolders + folder }

    fun addFolders(folders: List<File>) { dupFolders = dupFolders + folders.filter { it !in dupFolders } }

    fun findDuplicates() {
        if (isFindingDups) {
            dupJob?.cancel()
            isFindingDups = false
            return
        }
        if (dupFolders.isEmpty()) return
        isFindingDups = true
        dupChecked = 0
        val folders = dupFolders
        val minimum = dupMinimum
        val keep = keepRule
        dupJob = scope.launch(io) {
            val job = coroutineContext[Job]
            val groups = Duplicates.find(folders, minimum, keep, progress = { n -> scope.launch(ui) { dupChecked = n } }, cancelled = { job?.isActive == false })
            if (!isActive) return@launch
            withContext(ui) {
                dupGroups = groups
                dupSelected = defaultDupSelection(groups)
                dupScanned = true
                isFindingDups = false
            }
        }
    }

    private fun defaultDupSelection(groups: List<DuplicateGroup>) = groups.flatMap { it.files.drop(1).map(CareItem::id) }.toSet()

    /** Keeps the oldest or the newest copy: each group is put in that order and the ticks start again. */
    fun setKeep(rule: KeepRule) {
        keepRule = rule
        dupGroups = dupGroups.map { it.copy(files = Duplicates.order(it.files, rule)) }
        dupSelected = defaultDupSelection(dupGroups)
    }

    /** Ticking every copy in a group isn't allowed, so one is always kept. */
    fun toggleDuplicate(file: CareItem, group: DuplicateGroup) {
        dupSelected = when {
            file.id in dupSelected -> dupSelected - file.id
            group.files.any { it.id != file.id && it.id !in dupSelected } -> dupSelected + file.id
            else -> dupSelected
        }
    }

    val dupSelection: List<CareItem> get() = dupGroups.flatMap { it.files }.filter { it.id in dupSelected }

    fun removeDuplicates() {
        trash(dupSelection) { moved ->
            dupGroups = dupGroups.mapNotNull { g -> g.files.filter { it.id !in moved }.takeIf { it.size > 1 }?.let { g.copy(files = it) } }
            dupSelected = dupSelected - moved
        }
    }

    // Uninstall

    var apps by mutableStateOf<List<PcApp>>(emptyList())
    var appQuery by mutableStateOf("")
    var sortBySize by mutableStateOf(false)
    var isLoadingApps by mutableStateOf(false)
    var chosen by mutableStateOf<PcApp?>(null)
    /** The chosen app was uninstalled, here or elsewhere: only its leftovers are left. */
    var chosenGone by mutableStateOf(false)
    var leftovers by mutableStateOf<List<Leftover>>(emptyList())
    var leftoverSelected by mutableStateOf<Set<String>>(emptySet())
    var isRunning by mutableStateOf(false)
    var quitPhase by mutableStateOf(QuitTracker.Phase.Idle)
    var uninstalling by mutableStateOf(false)
    var preparing by mutableStateOf(false)
    var uninstallHints by mutableStateOf<List<HostHint>>(emptyList())
    private val quitTracker = QuitTracker(clock)
    private var runningJob: Job? = null

    val shownApps: List<PcApp> get() {
        val query = appQuery.trim()
        val list = if (query.isEmpty()) apps else apps.filter { it.name.contains(query, true) || it.publisher.contains(query, true) || it.packageId.contains(query, true) }
        return if (sortBySize) list.sortedByDescending { it.size ?: -1 } else list
    }

    private fun listApps(): List<PcApp> = if (sys.windows) WindowsApps.list(sys, registry!!) else linuxApps()

    private fun linuxApps(): List<PcApp> {
        val tool = PackageOwners.tool(sys)
        val flatpaks = if (sys.onPath("flatpak")) sys.run(listOf("flatpak", "list", "--app", "--columns=application,name,version,size,installation"), 30_000)?.out?.let(LinuxApps::parseFlatpaks).orEmpty() else emptyList()
        val snaps = if (sys.onPath("snap")) sys.run(listOf("snap", "list"), 30_000)?.out?.let(LinuxApps::parseSnaps).orEmpty() else emptyList()
        return LinuxApps.list(
            places.home, LinuxApps.desktopDirs(places.home, places.dataHome), { runCatching { it.readText() }.getOrNull() },
            owners = { paths ->
                tool?.let { t -> paths.chunked(150).flatMap { chunk -> sys.run(PackageOwners.command(t, chunk), 60_000)?.let { PackageOwners.parse(t, chunk, it.out) }.orEmpty().entries }.associate { it.key to (t to it.value) } }.orEmpty()
            },
            flatpaks = flatpaks, snaps = snaps,
            details = { t, pkgs -> pkgs.chunked(150).flatMap { sys.run(PackageOwners.detailsCommand(t, it), 60_000)?.out?.let { o -> PackageOwners.parseDetails(t, o) }.orEmpty().entries }.associate { it.key to it.value } },
            appImages = LinuxApps.findAppImages(places.home),
        )
    }

    /** Lists the apps again, as after an uninstaller or Settings removed one. A chosen app that's gone keeps its leftovers. */
    fun refreshApps() {
        if (isLoadingApps) return
        isLoadingApps = true
        scope.launch(io) {
            val list = listApps()
            val hints = CareHints.uninstall(sys.windows, distro, sys::onPath)
            withContext(ui) {
                apps = list
                uninstallHints = hints
                isLoadingApps = false
                val app = chosen
                if (app != null && !chosenGone && !uninstalling && list.none { it.id == app.id }) {
                    chosenGone = true
                    say("${app.name} is no longer installed." + if (leftovers.isNotEmpty()) " Its leftovers are below." else "")
                }
            }
        }
    }

    fun choose(app: PcApp?) {
        chosen = app
        chosenGone = false
        leftovers = emptyList()
        leftoverSelected = emptySet()
        quitTracker.reset()
        quitPhase = QuitTracker.Phase.Idle
        isRunning = false
        runningJob?.cancel()
        app ?: return
        scope.launch(io) {
            val found = Leftovers.find(app, places, guard)
            withContext(ui) {
                if (chosen?.id != app.id) return@withContext
                leftovers = found
                leftoverSelected = found.filter { it.byId }.map { it.id }.toSet()
            }
        }
        if (!loadsOnShow) return
        runningJob = scope.launch(io) {
            while (isActive && chosen?.id == app.id) {
                pollRunning(app)
                delay(1_500)
            }
        }
    }

    private suspend fun pollRunning(app: PcApp) {
        val open = Running.pids(app, sys.processes(), sys.windows).isNotEmpty()
        withContext(ui) {
            if (chosen?.id != app.id) return@withContext
            isRunning = open
            quitPhase = quitTracker.update(app.id, open)
        }
    }

    /** Asks the app to close, as its window's close button does; after a few seconds still open, Force quit is offered. */
    fun quit(force: Boolean = false) {
        val app = chosen ?: return
        quitTracker.asked(app.id)
        quitPhase = QuitTracker.Phase.Quitting
        scope.launch(io) {
            val pids = Running.pids(app, sys.processes(), sys.windows)
            val asked = sys.quit(pids, force)
            if (!asked) withContext(ui) { say("Couldn't ask ${app.name} to quit. It may run as administrator: close it from its own window.", problem = true) }
            delay(400)
            pollRunning(app)
        }
    }

    /** Works out what removing does (other packages that go with it), then asks. */
    fun requestUninstall() {
        val app = chosen ?: return
        if (preparing) return
        preparing = true
        scope.launch(io) {
            val stillThere = listApps().also { list -> withContext(ui) { apps = list } }.any { it.id == app.id }
            val plan = if (stillThere) Uninstall.plan(app, sys) else null
            withContext(ui) {
                preparing = false
                when {
                    plan == null -> { chosenGone = true; say("${app.name} is already gone." + if (leftovers.isNotEmpty()) " Its leftovers are below." else "") }
                    plan.blocked != null -> say(plan.blocked, problem = true)
                    else -> confirm = Confirm.Uninstall(app, plan)
                }
            }
        }
    }

    fun uninstall(app: PcApp, plan: UninstallPlan) {
        if (uninstalling) return
        uninstalling = true
        scope.launch(io) {
            if (plan.trash != null) {
                withContext(ui) {
                    val items = listOf(CareItem(plan.trash, app.size ?: 0, title = app.name)) + leftovers.filter { it.id in leftoverSelected }.map { it.item }
                    trash(items) { moved ->
                        uninstalling = false
                        if (plan.trash.path in moved) {
                            apps = apps.filter { it.id != app.id }
                            chosenGone = true
                            leftovers = leftovers.filter { it.id !in moved }
                            leftoverSelected = leftoverSelected - moved
                        }
                    }
                }
                return@launch
            }
            // The uninstaller's own window may stay open as long as people need.
            val out = sys.run(plan.command!!, 60 * 60_000L, plan.env)
            val list = listApps()
            val gone = list.none { it.id == app.id }
            withContext(ui) {
                apps = list
                uninstalling = false
                if (gone) {
                    chosenGone = true
                    say("${app.name} was uninstalled." + if (leftovers.isNotEmpty()) " Its leftovers are below: move the ticked ones to the $bin." else "")
                    refreshLeftovers(app)
                } else if (out != null && out.code == 0) {
                    say("${app.name}'s uninstaller closed and ${app.name} is still installed. If it asked for a restart, it's removed after that.", problem = true)
                } else {
                    say(Uninstall.failure(app, out), problem = true)
                }
            }
        }
    }

    private fun refreshLeftovers(app: PcApp) {
        scope.launch(io) {
            val found = Leftovers.find(app, places, guard)
            withContext(ui) {
                if (chosen?.id != app.id) return@withContext
                // Ticks people set stay; newly found ones carrying the app's ID start ticked.
                val ticked = leftoverSelected
                val known = leftovers.map { it.id }.toSet()
                leftovers = found
                leftoverSelected = found.filter { it.id in ticked || (it.byId && it.id !in known) }.map { it.id }.toSet()
            }
        }
    }

    fun toggleLeftover(id: String) { leftoverSelected = if (id in leftoverSelected) leftoverSelected - id else leftoverSelected + id }

    val leftoverSelection: List<CareItem> get() = leftovers.filter { it.id in leftoverSelected }.map { it.item }

    fun trashLeftovers() {
        trash(leftoverSelection) { moved ->
            leftovers = leftovers.filter { it.id !in moved }
            leftoverSelected = leftoverSelected - moved
        }
    }

    // Startup

    var startup by mutableStateOf<List<StartupEntry>>(emptyList())
    var startupLoaded by mutableStateOf(false)
    var loadingStartup by mutableStateOf(false)
    var startupError by mutableStateOf<String?>(null)
    var startupBusy by mutableStateOf<Set<String>>(emptySet())
    var startupHints by mutableStateOf<List<HostHint>>(emptyList())

    fun loadStartup() {
        if (loadingStartup) return
        loadingStartup = true
        scope.launch(io) {
            val list = if (sys.windows) {
                val known = apps.ifEmpty { WindowsApps.uninstallKeys.flatMap { WindowsApps.fromRegistry(registry!!.query(it, true).orEmpty(), it.startsWith("HKLM")) } }
                WindowsStartup.read(sys, registry!!, places, known)
            } else LinuxStartup.read(sys, places)
            withContext(ui) {
                startup = list
                startupHints = CareHints.startup(sys.windows, list)
                startupLoaded = true
                loadingStartup = false
            }
        }
    }

    fun setStartup(entry: StartupEntry, on: Boolean) {
        if (entry.id in startupBusy || !entry.editable) return
        startupBusy = startupBusy + entry.id
        scope.launch(io) {
            val failure = when (entry.kind) {
                StartupKind.Run, StartupKind.RunOnce -> if (on) WindowsStartup.turnOn(entry, registry!!) else WindowsStartup.turnOff(entry, registry!!)
                StartupKind.Folder -> WindowsStartup.moveFolderItem(entry, places, on)
                StartupKind.Task -> WindowsStartup.setTask(sys, entry, on)
                StartupKind.Autostart -> LinuxStartup.setAutostart(entry, File(places.config, "autostart"), on)
                StartupKind.UserUnit -> LinuxStartup.setUnit(sys, places, entry, on)
                StartupKind.Service, StartupKind.SystemUnit -> "${entry.displayName} needs an administrator to change."
            }
            withContext(ui) {
                startupBusy = startupBusy - entry.id
                startupError = failure
                loadingStartup = false
                loadStartup()
            }
        }
    }

    // Shared

    fun showTab(next: Tab) {
        tab = next
        if (!loadsOnShow) return
        if (next == Tab.Uninstall) refreshApps()
        if (next == Tab.Startup) loadStartup()
    }

    /** Back from an uninstaller or Settings: show what's really there now. */
    fun onFocus() {
        if (!loadsOnShow) return
        if (tab == Tab.Uninstall && !uninstalling) refreshApps()
        if (tab == Tab.Startup && startupBusy.isEmpty()) loadStartup()
        if (tab == Tab.Uninstall) chosen?.let { app -> if (!chosenGone) scope.launch(io) { pollRunning(app) } }
    }

    fun reveal(file: File) { scope.launch(io) { sys.reveal(file) } }

    /** Moves items to the Recycle Bin or Trash, after [guard] checked each of them again. */
    private fun trash(items: List<CareItem>, done: (Set<String>) -> Unit) {
        if (items.isEmpty()) return
        scope.launch(io) {
            val (allowed, refused) = items.partition { item -> item.files.all(guard::allows) }
            val outcome = sys.trash(allowed.flatMap { it.files })
            val gone = outcome.moved.map { it.absolutePath }.toSet()
            val movedItems = allowed.filter { item -> item.files.any { it.absolutePath in gone } }
            val ids = movedItems.map { it.id }.toSet()
            val bytes = movedItems.sumOf { item ->
                val all = item.files.size
                val moved = item.files.count { it.absolutePath in gone }
                if (moved == all) item.size else item.size * moved / all.coerceAtLeast(1)
            }
            val failedCount = allowed.count { item -> item.id !in ids }
            val text = buildString {
                if (movedItems.isNotEmpty()) {
                    append("Moved ${movedItems.size} item${if (movedItems.size == 1) "" else "s"}")
                    if (bytes > 0) append(", ${CareFiles.bytes(bytes)},")
                    append(" to the $bin. Empty the $bin to free the space.")
                }
                if (failedCount > 0) {
                    if (isNotEmpty()) append(' ')
                    val count = "$failedCount item${if (failedCount == 1) "" else "s"}"
                    append(
                        when (outcome.problem) {
                            TrashProblem.Cancelled -> "$count stayed, because the question was answered No."
                            TrashProblem.NoTrash -> "$count stayed where they are: that drive has no $bin, and DyskLink never deletes for good."
                            TrashProblem.NotAllowed -> "$count couldn't be moved: they're in use or protected. Close the app using them and try again."
                            else -> "$count couldn't be moved, maybe because they're in use. Close the app using them and try again."
                        },
                    )
                }
                if (refused.isNotEmpty()) {
                    if (isNotEmpty()) append(' ')
                    append("${refused.size} item${if (refused.size == 1) " was" else "s were"} skipped, because ${if (refused.size == 1) "it's" else "they're"} in a folder DyskLink never touches.")
                }
            }
            withContext(ui) {
                say(text, problem = failedCount > 0 || refused.isNotEmpty())
                done(ids)
            }
        }
    }
}
