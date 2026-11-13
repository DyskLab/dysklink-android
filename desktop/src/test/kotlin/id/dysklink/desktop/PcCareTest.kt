package id.dysklink.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/** A PC made of a temporary folder, with programs answered from a table and a Trash that records what it got. */
class FakeCare(
    override val home: File,
    override val windows: Boolean = false,
    private val envs: Map<String, String> = emptyMap(),
    private val tools: Set<String> = emptySet(),
    val answers: MutableMap<String, ProcOut> = mutableMapOf(),
    var procs: List<Proc> = emptyList(),
) : CareSystem {
    val trashed = mutableListOf<File>()
    val ran = mutableListOf<List<String>>()
    val quits = mutableListOf<Pair<List<Long>, Boolean>>()
    override fun env(name: String) = envs[name]
    override fun onPath(program: String) = program in tools
    override fun run(command: List<String>, timeoutMs: Long, env: Map<String, String>): ProcOut? {
        ran += command
        return answers.entries.firstOrNull { command.joinToString(" ").startsWith(it.key) }?.value
    }
    override fun processes() = procs
    override fun quit(pids: List<Long>, force: Boolean): Boolean { quits += pids to force; return true }
    override fun trash(files: List<File>): TrashOutcome {
        trashed += files
        files.forEach { it.deleteRecursively() }
        return TrashOutcome(files, emptyList())
    }
}

/** A registry in memory, keyed like reg.exe's paths. */
class FakeRegistry : Registry {
    val keys = linkedMapOf<String, MutableList<RegValue>>()
    var failDelete = false
    override fun query(key: String, recursive: Boolean): List<RegKey>? {
        val found = keys.filterKeys { it.equals(key, true) || recursive && it.startsWith("$key\\", true) }
        return if (found.isEmpty()) null else found.map { RegKey(it.key, it.value.toList()) }
    }
    override fun set(key: String, name: String, type: String, data: String): Boolean {
        val values = keys.getOrPut(key) { mutableListOf() }
        values.removeAll { it.name.equals(name, true) }
        values += RegValue(name, type, data)
        return true
    }
    override fun delete(key: String, name: String): Boolean {
        if (failDelete) return false
        return keys[key]?.removeAll { it.name.equals(name, true) } == true
    }
}

class PcCareTest {
    private val now = 1_762_900_000_000L
    private val day = 86_400_000L

    private fun temp(): File = Files.createTempDirectory("pccare").toFile().canonicalFile

    private fun File.write(size: Int): File { parentFile.mkdirs(); writeBytes(ByteArray(size) { (it % 251).toByte() }); return this }

    private fun winPlaces(home: File) = CarePlaces(
        home, true, temp = File(home, "AppData/Local/Temp"), localAppData = File(home, "AppData/Local"), appData = File(home, "AppData/Roaming"),
        ownData = File(home, "AppData/Roaming/DyskLink"),
    )

    // Cleaner

    @Test
    fun cleanerSortsWindowsFilesIntoGroups() {
        val home = temp()
        val p = winPlaces(home)
        val oldTemp = File(p.temp, "setup-log.tmp").write(600_000)
        val newTemp = File(p.temp, "in-use.tmp").write(600_000)
        File(p.localAppData, "Google/Chrome/User Data/Default/Cache/data_1").write(300_000)
        File(p.localAppData, "Google/Chrome/User Data/Default/Code Cache/js/a").write(300_000)
        File(p.localAppData, "Google/Chrome/User Data/Default/History").write(900_000) // never offered
        File(p.localAppData, "Google/Chrome/User Data/Profile 2/GPUCache/b").write(600_000)
        File(p.appData, "discord/Cache/Cache_Data/f").write(700_000)
        File(p.localAppData, "CrashDumps/app.exe.123.dmp").write(800_000)
        File(home, ".gradle/caches/modules-2/x.jar").write(600_000)
        File(home, ".m2/repository/org/x.jar").write(600_000)
        File(p.localAppData, "npm-cache/_cacache/index").write(600_000)
        val downloads = p.downloads
        File(downloads, "Setup.exe").write(10)
        File(downloads, "tool.msi").write(10)
        File(downloads, "app-installer.zip").write(10)
        File(downloads, "photos.zip").write(10)
        File(downloads, "report.pdf").write(10)
        File(downloads, "old-report.pdf").write(10)
        File(downloads, "DyskLink/from-phone.jpg").write(10)
        val used = mapOf(oldTemp.name to now - 3 * day, newTemp.name to now - 3_600_000, "old-report.pdf" to now - 120 * day, "photos.zip" to now - 100 * day)
        val groups = Cleaner.scan(p, now, lastUsed = { used[it.name] ?: (now - day) })

        val caches = groups.getValue(CleanGroup.Caches)
        val tempItem = caches.single { it.title == "Temporary files" }
        assertEquals(listOf(oldTemp), tempItem.members, "Temporary files changed today may be in use")
        val chrome = caches.filter { it.title == "Chrome cache" }
        assertEquals(setOf("Profile: Default", "Profile: Profile 2"), chrome.map { it.detail }.toSet())
        assertEquals(600_000, chrome.single { it.detail == "Profile: Default" }.size, "Only the cache folders, never History")
        assertTrue(caches.any { it.title == "Discord cache" })
        assertEquals(listOf("Crash dumps"), groups.getValue(CleanGroup.Logs).map { it.name })
        val dev = groups.getValue(CleanGroup.Developer)
        assertEquals(setOf("Gradle caches", "Maven repository", "npm cache"), dev.map { it.name }.toSet())
        assertFalse(dev.single { it.name == "Maven repository" }.preselect, "Maven may hold your own artifacts")
        assertEquals(setOf("Setup.exe", "tool.msi", "app-installer.zip"), groups.getValue(CleanGroup.Installers).map { it.name }.toSet())
        assertEquals(setOf("old-report.pdf", "photos.zip"), groups.getValue(CleanGroup.OldDownloads).map { it.name }.toSet())
        assertTrue(groups.values.flatten().none { it.name == "DyskLink" }, "Files from phones are DyskLink's own")
        // Items under half a megabyte aren't worth a row in the cache groups.
        File(p.localAppData, "D3DSCache/tiny").write(1000)
        assertTrue(Cleaner.scan(p, now).getValue(CleanGroup.Caches).none { it.name == "DirectX shader cache" })
    }

    @Test
    fun cleanerOnLinuxListsEachCacheAndLargeFiles() {
        val home = temp()
        val p = CarePlaces(home, false, ownData = File(home, ".config/dysklink"))
        File(home, ".cache/mozilla/firefox/x").write(600_000)
        File(home, ".cache/pip/http/y").write(600_000)
        File(home, ".cache/dysklink/z").write(600_000)
        File(home, ".var/app/org.gimp.GIMP/cache/w").write(600_000)
        File(home, ".xsession-errors.old").write(600_000)
        File(home, "Videos/holiday.mkv").write(3000)
        File(home, ".hidden/big.bin").write(3000)
        File(home, "Downloads/ubuntu.iso").write(3000)
        val groups = Cleaner.scan(p, now)
        assertEquals(setOf("mozilla", "org.gimp.GIMP cache"), groups.getValue(CleanGroup.Caches).map { it.name }.toSet())
        assertEquals(listOf("pip cache"), groups.getValue(CleanGroup.Developer).map { it.name })
        assertEquals(listOf(".xsession-errors.old"), groups.getValue(CleanGroup.Logs).map { it.name })
        val large = Cleaner.largeFiles(p, now, minimum = 2000)
        assertEquals(setOf("holiday.mkv", "ubuntu.iso"), large.map { it.name }.toSet(), "Hidden folders are skipped")
        assertTrue(Cleaner.isInstaller("Obsidian-1.5.3.AppImage"))
        assertTrue(Cleaner.isInstaller("code_1.90_amd64.deb"))
        assertFalse(Cleaner.isInstaller("holiday-photos.zip"))
        assertTrue(Cleaner.isInstaller("NodeSetup.zip"))
    }

    // Safety

    @Test
    fun guardOnlyAllowsWhatsInsideTheUsersFolders() {
        val home = temp()
        val p = CarePlaces(home, false)
        val guard = CareGuard.forPlaces(p)
        assertTrue(guard.allows(File(home, ".cache/mozilla")))
        assertTrue(guard.allows(File(home, "Downloads/setup.deb")))
        assertTrue(guard.allows(File(home, ".config/someapp")))
        assertFalse(guard.allows(home), "the home folder itself")
        assertFalse(guard.allows(File(home, ".cache")), "the cache folder itself")
        assertFalse(guard.allows(File(home, "Documents")))
        assertFalse(guard.allows(File(home, ".config/autostart")))
        assertFalse(guard.allows(File("/etc/passwd")))
        assertFalse(guard.allows(File("/usr/share/applications")))
        assertFalse(guard.allows(File(home, "../other-user/file")), "no way out with ..")
        assertFalse(guard.allows(File(home, ".cache/..")))
        // A folder in the home that's a link to somewhere else doesn't open the way there.
        val outside = temp()
        File(outside, "precious.txt").write(10)
        Files.createSymbolicLink(File(home, "link").toPath(), outside.toPath())
        assertFalse(guard.allows(File(home, "link/precious.txt")))
        // The link itself may go: only the link moves.
        assertTrue(guard.allows(File(home, "link")))

        val win = temp()
        val wp = winPlaces(win)
        val wguard = CareGuard.forPlaces(wp)
        assertTrue(wguard.allows(File(wp.localAppData, "Google/Chrome/User Data/Default/Cache")))
        assertTrue(wguard.allows(File(wp.temp, "x.tmp")))
        assertFalse(wguard.allows(wp.temp!!))
        assertFalse(wguard.allows(wp.localAppData!!))
        assertFalse(wguard.allows(File(win, "APPDATA/ROAMING")), "Windows ignores case")
        assertFalse(wguard.allows(File(wp.appData, "DyskLink")), "DyskLink's own folder")
        assertFalse(wguard.allows(File(win, "appdata/local/programs")))
    }

    @Test
    fun removingSkipsAnythingOutsideTheAllowedFolders() {
        val home = temp()
        val sys = FakeCare(home)
        val care = PcCareModel(sys, CoroutineScope(Dispatchers.Unconfined), places = CarePlaces(home, false), ui = Dispatchers.Unconfined, io = Dispatchers.Unconfined)
        val inside = File(home, ".cache/app").also { File(it, "a").write(10) }
        val outside = File(temp(), "elsewhere").also { File(it, "b").write(10) }
        care.cleanGroups = mapOf(CleanGroup.Caches to listOf(CareItem(inside, 10), CareItem(outside, 10), CareItem(File(home, ".config"), 10)))
        care.cleanSelected = care.cleanGroups.values.flatten().map { it.id }.toSet()
        care.cleanSelectedItems()
        assertEquals(listOf(inside), sys.trashed)
        assertTrue(outside.exists())
        val text = care.message!!
        assertTrue("Moved 1 item" in text && "to the Trash" in text, text)
        assertTrue("2 items were skipped" in text, text)
        assertTrue(care.messageIsProblem)
    }

    @Test
    fun freedesktopTrashKeepsWhereTheFileCameFrom() {
        val home = temp()
        val trash = File(home, ".local/share/Trash")
        val file = File(home, "Downloads/my file.deb").write(10)
        val again = File(home, "Other/my file.deb").write(20)
        assertNull(FreedesktopTrash.move(file, trash))
        assertNull(FreedesktopTrash.move(again, trash))
        assertFalse(file.exists())
        assertTrue(File(trash, "files/my file.deb").exists())
        assertTrue(File(trash, "files/my file.2.deb").exists(), "a second file of the same name gets its own")
        val info = File(trash, "info/my file.deb.trashinfo").readText()
        assertTrue(info.startsWith("[Trash Info]\nPath=") && "my%20file.deb" in info && "DeletionDate=" in info, info)
    }

    // Duplicates

    @Test
    fun duplicatesNeedEveryByteTheSame() {
        val root = temp()
        val a = File(root, "a/photo.jpg").write(200_000)
        val b = File(root, "b/photo copy.jpg").write(200_000)
        val c = File(root, "c/IMG_1.jpg").write(200_000)
        val different = File(root, "d/other.jpg").apply { parentFile.mkdirs(); writeBytes(ByteArray(200_000) { 7 }) }
        Files.createLink(File(root, "a/hardlink.jpg").toPath(), a.toPath())
        File(root, "small1.txt").write(10); File(root, "small2.txt").write(10)
        a.setLastModified(now - 30 * day); b.setLastModified(now - 10 * day); c.setLastModified(now - 20 * day)
        val groups = Duplicates.find(listOf(root, File(root, "a")), minimum = 1000)
        assertEquals(1, groups.size)
        val group = groups.single()
        assertEquals(3, group.files.size, "a hard link and a folder picked twice don't count as copies")
        assertFalse(group.files.any { it.file == different })
        assertEquals(400_000, group.wasted)
        val dated = listOf(CareItem(a, 1, date = 30), CareItem(b, 1, date = 10), CareItem(c, 1, date = 20))
        assertEquals(b, Duplicates.order(dated, KeepRule.Oldest).first().file)
        assertEquals(a, Duplicates.order(dated, KeepRule.Newest).first().file)
    }

    @Test
    fun oneCopyIsAlwaysKept() {
        val home = temp()
        val care = PcCareModel(FakeCare(home), CoroutineScope(Dispatchers.Unconfined), places = CarePlaces(home, false), ui = Dispatchers.Unconfined, io = Dispatchers.Unconfined)
        val files = (1..3).map { CareItem(File(home, "f$it"), 5, date = it.toLong()) }
        val group = DuplicateGroup("x", 5, files)
        care.dupGroups = listOf(group)
        care.dupSelected = setOf(files[1].id, files[2].id)
        care.toggleDuplicate(files[0], group)
        assertEquals(setOf(files[1].id, files[2].id), care.dupSelected, "the last kept copy can't be ticked")
        care.setKeep(KeepRule.Newest)
        assertEquals(files[2].id, care.dupGroups.single().files.first().id)
        assertEquals(setOf(files[0].id, files[1].id), care.dupSelected)
    }

    // Windows apps

    private val regSample = """
HKEY_LOCAL_MACHINE\Software\Microsoft\Windows\CurrentVersion\Uninstall

HKEY_LOCAL_MACHINE\Software\Microsoft\Windows\CurrentVersion\Uninstall\7-Zip
    DisplayName    REG_SZ    7-Zip 23.01 (x64)
    DisplayVersion    REG_SZ    23.01
    Publisher    REG_SZ    Igor Pavlov
    InstallLocation    REG_SZ    C:\Program Files\7-Zip\
    DisplayIcon    REG_SZ    C:\Program Files\7-Zip\7zFM.exe
    UninstallString    REG_SZ    "C:\Program Files\7-Zip\Uninstall.exe"
    EstimatedSize    REG_DWORD    0x15d4
    NoModify    REG_DWORD    0x1

HKEY_LOCAL_MACHINE\Software\Microsoft\Windows\CurrentVersion\Uninstall\{90160000-008C-0000-1000-0000000FF1CE}
    DisplayName    REG_SZ    Office 16 Click-to-Run Extensibility Component
    SystemComponent    REG_DWORD    0x1
    UninstallString    REG_SZ    MsiExec.exe /X{90160000-008C-0000-1000-0000000FF1CE}

HKEY_LOCAL_MACHINE\Software\Microsoft\Windows\CurrentVersion\Uninstall\{23170F69-40C1-2702-2301-000001000000}
    (Default)    REG_SZ
    DisplayName    REG_SZ    Zoom Workplace
    Publisher    REG_SZ    Zoom Video Communications, Inc.
    DisplayVersion    REG_SZ    6.1.0
    InstallDate    REG_SZ    20240115
    UninstallString    REG_EXPAND_SZ    MsiExec.exe /I{23170F69-40C1-2702-2301-000001000000}
    WindowsInstaller    REG_DWORD    0x1

HKEY_LOCAL_MACHINE\Software\Microsoft\Windows\CurrentVersion\Uninstall\KB5034441
    DisplayName    REG_SZ    Security Update
    ParentKeyName    REG_SZ    OperatingSystem
    UninstallString    REG_SZ    x

HKEY_LOCAL_MACHINE\Software\Microsoft\Windows\CurrentVersion\Uninstall\DyskLink
    DisplayName    REG_SZ    DyskLink
    Publisher    REG_SZ    DyskLab
    UninstallString    REG_SZ    MsiExec.exe /X{1}
""".trimStart()

    @Test
    fun registryUninstallKeysBecomeApps() {
        val keys = RegParse.parse(regSample.replace("\n", "\r\n"))
        assertEquals(6, keys.size)
        assertEquals("", keys[3].values.first().name, "(Default) is the key's own value")
        val apps = WindowsApps.fromRegistry(keys, forAllUsers = true)
        assertEquals(listOf("7-Zip 23.01 (x64)", "Zoom Workplace"), apps.map { it.name }, "no system components, updates, or DyskLink")
        val zip = apps[0]
        assertEquals("Igor Pavlov", zip.publisher)
        assertEquals(0x15d4L * 1024, zip.size)
        assertEquals(File("C:\\Program Files\\7-Zip"), zip.location)
        assertEquals(File("C:\\Program Files\\7-Zip\\7zFM.exe"), zip.exe)
        assertEquals("C:\\Program Files\\7-Zip\\Uninstall.exe" to "", WindowsApps.uninstallCommand(zip))
        val zoom = apps[1]
        assertEquals(java.time.LocalDate.of(2024, 1, 15), zoom.installDate)
        assertEquals("MsiExec.exe" to "/X{23170F69-40C1-2702-2301-000001000000}", WindowsApps.uninstallCommand(zoom), "/I opens Change; /X uninstalls")
        assertEquals("C:\\Program Files\\App X\\uninst.exe" to "/S --x", WindowsApps.splitCommand("C:\\Program Files\\App X\\uninst.exe /S --x"))
        assertEquals(File("C:\\Apps\\a.exe"), WindowsApps.iconExe("\"C:\\Apps\\a.exe\",0"))
        assertNull(WindowsApps.iconExe("C:\\Apps\\a.ico"))
    }

    @Test
    fun storeAppsFromGetAppxPackage() {
        val json = """[{"Name":"SpotifyAB.SpotifyMusic","PackageFullName":"SpotifyAB.SpotifyMusic_1.230.1135.0_x64__zpdnekdrzrea0","PackageFamilyName":"SpotifyAB.SpotifyMusic_zpdnekdrzrea0",
            "Version":"1.230.1135.0","InstallLocation":"C:\\Program Files\\WindowsApps\\SpotifyAB.SpotifyMusic_1.230.1135.0_x64__zpdnekdrzrea0","SignatureKind":"Store",
            "DisplayName":"ms-resource:AppName","PublisherDisplayName":"Spotify AB"},
            {"Name":"Microsoft.WindowsCalculator","PackageFullName":"Microsoft.WindowsCalculator_11.2405.2.0_x64__8wekyb3d8bbwe","PackageFamilyName":"Microsoft.WindowsCalculator_8wekyb3d8bbwe",
            "Version":"11.2405.2.0","InstallLocation":null,"SignatureKind":"Store","DisplayName":"Windows Calculator","PublisherDisplayName":"Microsoft Corporation"}]"""
        val apps = WindowsApps.parseAppx(json)
        assertEquals(listOf("Spotify Music", "Windows Calculator"), apps.map { it.name })
        assertEquals("Spotify AB", apps[0].publisher)
        assertEquals("SpotifyAB.SpotifyMusic_zpdnekdrzrea0", apps[0].family)
        assertEquals(AppKind.Store, apps[0].kind)
        val single = """{"Name":"A.B","PackageFullName":"A.B_1_x64__x","PackageFamilyName":"A.B_x","Version":"1","SignatureKind":"Developer","DisplayName":"Bee","PublisherDisplayName":""}"""
        assertEquals(listOf("Bee"), WindowsApps.parseAppx(single).map { it.name }, "ConvertTo-Json gives one object for one package")
        assertTrue(WindowsApps.parseAppx("").isEmpty())
    }

    @Test
    fun windowsLeftoversByInstallFolderAndName() {
        val home = temp()
        val p = winPlaces(home)
        val guard = CareGuard.forPlaces(p)
        val install = File(p.localAppData, "Programs/Obsidian").apply { mkdirs() }
        File(p.appData, "obsidian/settings.json").write(10)
        File(p.localAppData, "Obsidian Labs/Obsidian/cache").write(10)
        File(p.localAppData, "Packages/SpotifyAB.SpotifyMusic_zpdnekdrzrea0/LocalState/x").write(10)
        File(p.appData, "Microsoft/keep").write(10)
        val app = PcApp("k", "Obsidian", AppKind.Windows, publisher = "Obsidian Labs", location = install)
        val found = Leftovers.find(app, p, guard)
        assertEquals(listOf(true), found.filter { it.byId }.map { it.item.file == install })
        assertEquals(setOf(File(p.appData, "obsidian"), File(p.localAppData, "Obsidian Labs/Obsidian")), found.filter { !it.byId }.map { it.item.file }.toSet())
        assertTrue(found.none { it.item.file.name == "Microsoft" || it.item.file.name == "Obsidian Labs" }, "never a publisher's whole folder")
        val store = PcApp("s", "Spotify Music", AppKind.Store, family = "SpotifyAB.SpotifyMusic_zpdnekdrzrea0")
        assertEquals(listOf(true), Leftovers.find(store, p, guard).map { it.byId })
    }

    // Linux apps

    @Test
    fun desktopFilesParseAndPackageOwnersResolve() {
        val entry = DesktopFiles.parse(File("/usr/share/applications/vlc.desktop"), """
            [Desktop Entry]
            Name=VLC media player
            Name[de]=VLC Media-Player
            Exec=env GTK_THEME=x /usr/bin/vlc --started-from-file %U
            Type=Application
            [Desktop Action new]
            Name=New window
        """.trimIndent())
        assertEquals("VLC media player", entry.name)
        assertEquals("/usr/bin/vlc", entry.program)
        assertTrue(entry.shown)
        assertEquals(listOf("/opt/My App/app", "--x"), DesktopFiles.splitExec("\"/opt/My App/app\" --x %f"))

        val paths = listOf("/usr/share/applications/vlc.desktop", "/usr/share/applications/gimp.desktop", "/usr/share/applications/own.desktop")
        assertEquals(mapOf(paths[0] to "vlc-bin", paths[1] to "gimp"),
            PackageOwners.parse(PackageOwners.Tool.Dpkg, paths, "vlc-bin, vlc: ${paths[0]}\ngimp:amd64: ${paths[1]}\ndpkg-query: no path found matching pattern ${paths[2]}\n"))
        assertEquals(mapOf(paths[0] to "vlc", paths[1] to "gimp"),
            PackageOwners.parse(PackageOwners.Tool.Rpm, paths, "vlc\ngimp\nfile ${paths[2]} is not owned by any package\n"))
        assertEquals(mapOf(paths[1] to "gimp"),
            PackageOwners.parse(PackageOwners.Tool.Pacman, paths, "${paths[1]} is owned by gimp 2.10.36-4\n"))
        assertEquals(mapOf("vlc" to ("3.0.20-1" to 3_072L * 1024)), PackageOwners.parseDetails(PackageOwners.Tool.Dpkg, "vlc\t3.0.20-1\t3072\n"))
    }

    @Test
    fun linuxAppListJoinsPackagesFlatpaksSnapsAndAppImages() {
        val home = temp()
        val user = File(home, ".local/share/applications").apply { mkdirs() }
        val system = temp()
        val flat = temp()
        File(system, "vlc.desktop").writeText("[Desktop Entry]\nName=VLC media player\nExec=/usr/bin/vlc %U\nType=Application\n")
        File(system, "vlc-open.desktop").writeText("[Desktop Entry]\nName=VLC (open)\nExec=/usr/bin/vlc --open\nType=Application\n")
        File(system, "hidden.desktop").writeText("[Desktop Entry]\nName=Helper\nExec=/usr/bin/helper\nNoDisplay=true\n")
        File(system, "dysklink.desktop").writeText("[Desktop Entry]\nName=DyskLink\nExec=/opt/dysklink/bin/DyskLink\n")
        File(flat, "org.gimp.GIMP.desktop").writeText("[Desktop Entry]\nName=GNU Image Manipulation Program\nExec=/usr/bin/flatpak run org.gimp.GIMP\nX-Flatpak=org.gimp.GIMP\n")
        val image = File(home, "Applications/Obsidian-1.5.3.AppImage").write(100)
        File(user, "obsidian.desktop").writeText("[Desktop Entry]\nName=Obsidian\nExec=${image.path} %u\n")
        val flatpaks = LinuxApps.parseFlatpaks("org.gimp.GIMP\tGNU Image Manipulation Program\t2.10.38\t1.1 GB\tsystem\ncom.spotify.Client\tSpotify\t1.2\t350.5 MB\tuser\n")
        val snaps = LinuxApps.parseSnaps("Name  Version  Rev  Tracking  Publisher   Notes\ncore22  2024  1380  latest/stable  canonical✓  base\nsnapd  2.63  21759  latest/stable  canonical✓  snapd\nfirefox  128.0  4650  latest/stable  mozilla✓  -\n")
        assertEquals(listOf("firefox"), snaps.map { it.packageId })
        assertEquals("mozilla", snaps[0].publisher)
        val apps = LinuxApps.list(
            home, listOf(user, flat, system), { runCatching { it.readText() }.getOrNull() },
            owners = { paths -> paths.filter { "vlc" in it || "dysklink" in it }.associateWith { PackageOwners.Tool.Dpkg to (if ("dysklink" in it) "dysklink" else "vlc") } },
            flatpaks = flatpaks, snaps = snaps,
            details = { _, _ -> mapOf("vlc" to ("3.0.20" to 50_000_000L)) },
            appImages = LinuxApps.findAppImages(home),
        )
        assertEquals(listOf("Firefox" to AppKind.Snap, "GNU Image Manipulation Program" to AppKind.Flatpak, "Obsidian" to AppKind.AppImage, "Spotify" to AppKind.Flatpak, "VLC media player" to AppKind.Deb),
            apps.map { it.name.replaceFirstChar(Char::uppercase) to it.kind })
        assertEquals(1_100_000_000L, apps.single { it.packageId == "org.gimp.GIMP" }.size)
        assertEquals("3.0.20", apps.single { it.kind == AppKind.Deb }.version)
        assertEquals(image, apps.single { it.kind == AppKind.AppImage }.location)
        assertEquals("Obsidian" to "1.5.3", LinuxApps.appImageName(image))
    }

    @Test
    fun linuxLeftoversAndUninstallPlans() {
        val home = temp()
        val p = CarePlaces(home, false)
        File(home, ".var/app/org.gimp.GIMP/config/x").write(10)
        File(home, ".config/GIMP/2.10/x").write(10)
        File(home, ".config/org.gimp.GIMP/x").write(10)
        val gimp = PcApp("flatpak:org.gimp.GIMP", "GIMP", AppKind.Flatpak, packageId = "org.gimp.GIMP", desktopId = "org.gimp.GIMP", installation = "user")
        val found = Leftovers.find(gimp, p, CareGuard.forPlaces(p))
        assertEquals(setOf(".var/app/org.gimp.GIMP", ".config/org.gimp.GIMP"), found.filter { it.byId }.map { it.item.file.relativeTo(home).path }.toSet())
        assertEquals(listOf(".config/GIMP"), found.filter { !it.byId }.map { it.item.file.relativeTo(home).path })

        val sys = FakeCare(home, tools = setOf("pkexec", "dnf"), answers = mutableMapOf(
            "apt-get -s remove vlc" to ProcOut(0, "Remv vlc [3.0.20]\nRemv vlc-plugin-base [3.0.20]\nRemv ubuntu-desktop [1.5]\n"),
            "rpm -e --test vlc" to ProcOut(1, "", "error: Failed dependencies:\n\tvlc is needed by (installed) vlc-extras-1.0.x86_64\n"),
        ))
        val deb = Uninstall.plan(PcApp("d", "VLC", AppKind.Deb, packageId = "vlc", forAllUsers = true), sys)
        assertEquals(listOf("pkexec", "env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "remove", "-y", "vlc"), deb.command)
        assertEquals(listOf("vlc-plugin-base", "ubuntu-desktop"), deb.alsoRemoves)
        assertTrue(Uninstall.takesDesktop(deb.alsoRemoves))
        val rpm = Uninstall.plan(PcApp("r", "VLC", AppKind.Rpm, packageId = "vlc"), sys)
        assertEquals(listOf("pkexec", "dnf", "remove", "-y", "vlc"), rpm.command)
        assertEquals(listOf("vlc-extras-1.0.x86_64"), rpm.alsoRemoves)
        assertEquals(listOf("flatpak", "uninstall", "--user", "-y", "--noninteractive", "org.gimp.GIMP"), Uninstall.plan(gimp, sys).command)
        val image = File(home, "Applications/x.AppImage")
        assertEquals(image, Uninstall.plan(PcApp("a", "X", AppKind.AppImage, location = image), sys).trash)
        val noPkexec = Uninstall.plan(PcApp("d", "VLC", AppKind.Deb, packageId = "vlc"), FakeCare(home))
        assertNull(noPkexec.command)
        assertTrue(noPkexec.blocked!!.contains("sudo apt remove vlc"))
        // Failures always say something.
        val app = PcApp("d", "VLC", AppKind.Deb, packageId = "vlc")
        assertEquals("The password was cancelled, so VLC stays.", Uninstall.failure(app, ProcOut(126, "", "")))
        assertTrue(Uninstall.failure(app, ProcOut(127, "", "")).contains("isn't allowed"))
        assertEquals("Couldn't remove VLC (exit code 3).", Uninstall.failure(app, ProcOut(3, "", "")))
        assertEquals("Windows' prompt was cancelled, so Zoom stays.", Uninstall.failure(PcApp("w", "Zoom", AppKind.Windows), ProcOut(1223, "", "")))
        assertTrue(Uninstall.failure(app, null).isNotBlank())
    }

    @Test
    fun windowsUninstallGoesThroughStartProcess() {
        val plan = Uninstall.plan(PcApp("w", "7-Zip", AppKind.Windows, uninstall = "\"C:\\Program Files\\7-Zip\\Uninstall.exe\" /S"), FakeCare(temp(), windows = true))
        assertEquals("powershell", plan.command!!.first())
        assertTrue("Start-Process -FilePath \$env:DL_EXE -ArgumentList \$env:DL_ARGS" in plan.command!!.last())
        assertEquals(mapOf("DL_EXE" to "C:\\Program Files\\7-Zip\\Uninstall.exe", "DL_ARGS" to "/S"), plan.env, "paths go in the environment, never into the script")
        assertNotNull(Uninstall.plan(PcApp("w", "X", AppKind.Windows), FakeCare(temp(), windows = true)).blocked)
    }

    // Running and quitting

    @Test
    fun runningAppsAndTheQuitStateMachine() {
        val zip = PcApp("k", "7-Zip", AppKind.Windows, location = File("C:\\Program Files\\7-Zip"), exe = File("C:\\Program Files\\7-Zip\\7zFM.exe"))
        val procs = listOf(Proc(10, "C:\\Program Files\\7-Zip\\7zFM.exe"), Proc(11, "c:\\program files\\7-zip\\7zG.exe"), Proc(12, "C:\\Program Files\\7-Zip Other\\x.exe"))
        assertEquals(listOf(10L, 11L), Running.pids(zip, procs, windows = true))
        val gimp = PcApp("f", "GIMP", AppKind.Flatpak, packageId = "org.gimp.GIMP")
        assertEquals(listOf(5L), Running.pids(gimp, listOf(Proc(5, "/usr/bin/bwrap", "bwrap --args 40 -- org.gimp.GIMP"), Proc(6, "/usr/bin/gimp")), windows = false))
        val vlc = PcApp("d", "VLC", AppKind.Deb, exe = File("/usr/bin/vlc"))
        assertEquals(listOf(7L), Running.pids(vlc, listOf(Proc(7, "/usr/lib/vlc/vlc"), Proc(8, "/usr/bin/vim")), windows = false, resolve = { null }))

        var time = 0L
        val tracker = QuitTracker({ time }, patienceMs = 6_000)
        assertEquals(QuitTracker.Phase.Idle, tracker.update("a", true), "nothing asked yet")
        tracker.asked("a")
        time = 2_000
        assertEquals(QuitTracker.Phase.Quitting, tracker.update("a", true))
        time = 6_500
        assertEquals(QuitTracker.Phase.Stuck, tracker.update("a", true), "Force quit is offered")
        assertEquals(QuitTracker.Phase.Idle, tracker.phaseFor("b"))
        tracker.asked("a")
        time = 7_000
        assertEquals(QuitTracker.Phase.Idle, tracker.update("a", false), "it quit")
        assertEquals(QuitTracker.Phase.Idle, tracker.phaseFor("a"))
    }

    @Test
    fun quitAsksTheAppsProcessesAndWatchesThem() {
        val home = temp()
        val sys = FakeCare(home, procs = listOf(Proc(42, "/usr/bin/vlc")))
        var time = 0L
        val care = PcCareModel(sys, CoroutineScope(Dispatchers.Unconfined), places = CarePlaces(home, false), ui = Dispatchers.Unconfined, io = Dispatchers.Unconfined, clock = { time })
        care.loadsOnShow = false
        care.choose(PcApp("d", "VLC", AppKind.Deb, exe = File("/usr/bin/vlc")))
        care.quit()
        assertEquals(listOf(listOf(42L) to false), sys.quits)
        assertEquals(QuitTracker.Phase.Quitting, care.quitPhase)
        // The check after asking comes a moment later.
        val until = System.currentTimeMillis() + 3_000
        while (!care.isRunning && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue(care.isRunning)
        time = 10_000
        care.quit(force = true)
        assertEquals(listOf(42L) to true, sys.quits.last())
        sys.procs = emptyList()
        care.onFocus()
    }

    // Startup

    @Test
    fun windowsRunKeysTurnOffAndBackOn() {
        val reg = FakeRegistry()
        reg.set(WindowsStartup.RUN, "Discord", "REG_SZ", "\"C:\\Users\\f\\AppData\\Local\\Discord\\Update.exe\" --processStart Discord.exe")
        reg.set(WindowsStartup.RUN, "OneDrive", "REG_EXPAND_SZ", "\"%LOCALAPPDATA%\\Microsoft\\OneDrive\\OneDrive.exe\" /background")
        reg.set("HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Run", "SecurityHealth", "REG_EXPAND_SZ", "%windir%\\system32\\SecurityHealthSystray.exe")
        reg.set("HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run", "OneDrive", "REG_BINARY", "030000000000000000000000")
        val apps = listOf(PcApp("d", "Discord", AppKind.Windows, publisher = "Discord Inc.", location = File("C:\\Users\\f\\AppData\\Local\\Discord")))
        val entries = WindowsStartup.registryEntries(reg, apps)
        val discord = entries.single { it.technical == "Discord" }
        assertEquals("Discord" to "Discord Inc.", discord.displayName to discord.publisher)
        assertTrue(discord.editable && !discord.isOff)
        val onedrive = entries.single { it.technical == "OneDrive" }
        assertTrue(onedrive.offInWindows && onedrive.isOff && !onedrive.editable, "turned off in Windows' Startup apps")
        assertEquals("Syncs your files", onedrive.purpose)
        val health = entries.single { it.technical == "SecurityHealth" }
        assertEquals("Windows Security", health.displayName)
        assertTrue(health.adminOnly && !health.editable)

        assertNull(WindowsStartup.turnOff(discord, reg))
        assertTrue(reg.keys[WindowsStartup.RUN]!!.none { it.name == "Discord" })
        val off = WindowsStartup.registryEntries(reg, apps).single { it.technical == "Discord" }
        assertTrue(off.isOff && off.editable)
        assertEquals(discord.command, off.command)
        assertNull(WindowsStartup.turnOn(off, reg))
        assertEquals(RegValue("Discord", "REG_SZ", discord.command), reg.keys[WindowsStartup.RUN]!!.single { it.name == "Discord" })
        assertTrue(reg.keys[WindowsStartup.BACKUP_RUN]!!.isEmpty())
        // A failure halfway leaves it on, as it was.
        reg.failDelete = true
        assertNotNull(WindowsStartup.turnOff(discord, reg))
        assertTrue(reg.keys[WindowsStartup.RUN]!!.any { it.name == "Discord" })
        assertNotNull(WindowsStartup.turnOff(health, reg), "machine-wide items need an administrator")
    }

    @Test
    fun windowsStartupFolderTasksAndServices() {
        val home = temp()
        val p = winPlaces(home).copy(programData = File(home, "ProgramData"))
        val shortcut = File(WindowsStartup.userStartupFolder(p), "Spotify.lnk").write(10)
        File(WindowsStartup.commonStartupFolder(p), "Logitech Options.lnk").write(10)
        val items = WindowsStartup.folderEntries(p, emptyList())
        val spotify = items.single { it.technical == "Spotify.lnk" }
        assertEquals("Spotify", spotify.displayName)
        assertTrue(spotify.editable && !spotify.isOff)
        assertTrue(items.single { it.technical == "Logitech Options.lnk" }.adminOnly)
        assertNull(WindowsStartup.moveFolderItem(spotify, p, on = false))
        assertFalse(shortcut.exists())
        val off = WindowsStartup.folderEntries(p, emptyList()).single { it.technical == "Spotify.lnk" }
        assertTrue(off.isOff)
        assertNull(WindowsStartup.moveFolderItem(off, p, on = true))
        assertTrue(shortcut.exists())

        val header = "\"HostName\",\"TaskName\",\"Next Run Time\",\"Status\",\"Logon Mode\",\"Last Run Time\",\"Last Result\",\"Author\",\"Task To Run\",\"Start In\",\"Comment\",\"Scheduled Task State\",\"Idle Time\",\"Power Management\",\"Run As User\",\"Delete Task If Not Rescheduled\",\"Stop Task If Runs X Hours and X Mins\",\"Schedule\",\"Schedule Type\""
        fun row(name: String, status: String, run: String, state: String, user: String, type: String) =
            "\"PC\",\"$name\",\"N/A\",\"$status\",\"Interactive only\",\"N/A\",\"0\",\"Someone\",\"$run\",\"N/A\",\"N/A\",\"$state\",\"\",\"\",\"$user\",\"\",\"\",\"\",\"$type\""
        val csv = listOf(
            header,
            row("\\OneDrive Standalone Update Task-S-1-5-21-1", "Ready", "%localappdata%\\Microsoft\\OneDrive\\OneDriveStandaloneUpdater.exe", "Enabled", "fanny", "At logon time"),
            row("\\OneDrive Standalone Update Task-S-1-5-21-1", "Ready", "%localappdata%\\Microsoft\\OneDrive\\OneDriveStandaloneUpdater.exe", "Enabled", "fanny", "Daily"),
            header,
            row("\\GoogleUpdateTaskMachineCore", "Disabled", "C:\\Program Files (x86)\\Google\\Update\\GoogleUpdate.exe /c", "Disabled", "SYSTEM", "At system start up"),
            row("\\Microsoft\\Windows\\Defrag\\ScheduledDefrag", "Ready", "x", "Enabled", "SYSTEM", "At logon time"),
            row("\\Backup", "Ready", "C:\\backup.exe, \"quoted\"", "Enabled", "fanny", "Weekly"),
        ).joinToString("\r\n")
        val tasks = WindowsStartup.parseTasks(csv, "fanny", emptyList())
        assertEquals(2, tasks.size, "logon and startup tasks only, once each, none of Microsoft's")
        val update = tasks[0]
        assertEquals("Checks for updates", update.purpose)
        assertTrue(update.editable && !update.isOff)
        val google = tasks[1]
        assertEquals("Google Updater", google.displayName)
        assertTrue(google.isOff && google.adminOnly && google.atBoot)
        assertEquals(listOf("a", "b, \"c\"", ""), WindowsStartup.csvRow("a,\"b, \"\"c\"\"\","))

        val services = WindowsStartup.parseServices(
            """[{"Name":"wuauserv","DisplayName":"Windows Update","PathName":"C:\\WINDOWS\\system32\\svchost.exe -k netsvcs","State":"Running","StartName":"LocalSystem"},
               {"Name":"TeamViewer","DisplayName":"TeamViewer","PathName":"\"C:\\Program Files\\TeamViewer\\TeamViewer_Service.exe\"","State":"Running","StartName":"LocalSystem"}]""",
            emptyList(),
        )
        assertEquals(listOf("TeamViewer"), services.map { it.displayName })
        assertTrue(services[0].adminOnly && !services[0].editable)
    }

    @Test
    fun linuxAutostartTurnsOffInTheUsersCopy() {
        val home = temp()
        val user = File(home, ".config/autostart")
        val system = temp()
        File(user, "syncthing-start.desktop").write(0).writeText("[Desktop Entry]\nName=Start Syncthing\nExec=/usr/bin/syncthing serve --no-browser\nType=Application\n# a comment\n")
        val sysFile = File(system, "org.gnome.SettingsDaemon.Power.desktop").also { it.writeText("[Desktop Entry]\nName=Power\nExec=/usr/libexec/gsd-power\nX-GNOME-AutoRestart=true\n") }
        File(system, "nextcloud.desktop").writeText("[Desktop Entry]\nName=Nextcloud\nExec=nextcloud --background\nComment=Desktop sync client\n")
        File(system, "removed.desktop").writeText("[Desktop Entry]\nName=Removed\nExec=x\nHidden=true\n")
        val entries = LinuxStartup.autostart(user, listOf(system))
        assertEquals(listOf("nextcloud.desktop", "org.gnome.SettingsDaemon.Power.desktop", "syncthing-start.desktop"), entries.map { it.technical })
        assertTrue(entries[1].desktopPart && !entries[1].editable, "part of the desktop is shown, not offered")
        assertEquals("Syncs your files", entries[0].purpose)
        val sync = entries[2]
        assertEquals("Syncs your files", sync.purpose)

        assertNull(LinuxStartup.setAutostart(sync, user, on = false))
        val text = File(user, "syncthing-start.desktop").readText()
        assertTrue("Hidden=true" in text && "X-GNOME-Autostart-enabled=false" in text && "# a comment" in text, text)
        assertTrue(LinuxStartup.autostart(user, listOf(system)).single { it.technical == "syncthing-start.desktop" }.isOff)
        val offAgain = LinuxStartup.autostart(user, listOf(system)).single { it.technical == "syncthing-start.desktop" }
        assertNull(LinuxStartup.setAutostart(offAgain, user, on = true))
        assertFalse(LinuxStartup.autostart(user, listOf(system)).single { it.technical == "syncthing-start.desktop" }.isOff)

        // A system item gets the user's own copy; /etc stays untouched.
        val before = File(system, "nextcloud.desktop").readText()
        assertNull(LinuxStartup.setAutostart(entries[0], user, on = false))
        assertEquals(before, File(system, "nextcloud.desktop").readText())
        val copy = File(user, "nextcloud.desktop").readText()
        assertTrue("Hidden=true" in copy && "${LinuxStartup.OVERRIDE_KEY}=true" in copy && "Comment=Desktop sync client" in copy, copy)
        val off = LinuxStartup.autostart(user, listOf(system)).single { it.technical == "nextcloud.desktop" }
        assertTrue(off.isOff && off.editable)
        assertTrue(sysFile.exists())
    }

    @Test
    fun systemdUnitsParseAndToggle() {
        assertEquals(listOf("syncthing.service", "backup.timer", "pipewire.service"),
            LinuxStartup.parseUnitFiles("syncthing.service enabled enabled\nbackup.timer enabled enabled\npipewire.socket enabled enabled\npipewire.service enabled enabled\n"))
        val show = LinuxStartup.parseShow("Id=syncthing.service\nDescription=Syncthing - Open Source Continuous File Synchronization\nExecStart={ path=/usr/bin/syncthing ; argv[]=/usr/bin/syncthing serve --no-browser ; ignore_errors=no }\n\nId=pipewire.service\nDescription=PipeWire Multimedia Service\nExecStart={ path=/usr/bin/pipewire ; argv[]=/usr/bin/pipewire ; }\n")
        assertEquals("/usr/bin/syncthing serve --no-browser", show["syncthing.service"]!!.second)
        val units = LinuxStartup.units(listOf("syncthing.service", "pipewire.service"), listOf("old.service"), show, system = false)
        assertEquals(listOf(false, false, true), units.map { it.isOff })
        assertTrue(units[0].editable)
        assertTrue(units[1].desktopPart && !units[1].editable, "the sound server isn't offered")
        assertTrue(units[2].editable, "turned off by DyskLink, so it can come back")
        val home = temp()
        val p = CarePlaces(home, false, ownData = File(home, ".config/dysklink"))
        val sys = FakeCare(home, answers = mutableMapOf("systemctl --user" to ProcOut(0, "")))
        assertNull(LinuxStartup.setUnit(sys, p, units[0], on = false))
        assertEquals(listOf("systemctl", "--user", "disable", "--now", "syncthing.service"), sys.ran.last())
        assertEquals("syncthing.service", LinuxStartup.disabledRecord(p).readText())
        assertNull(LinuxStartup.setUnit(sys, p, units[0], on = true))
        assertEquals("", LinuxStartup.disabledRecord(p).readText())
        assertNotNull(LinuxStartup.setUnit(FakeCare(home), p, units[0], on = true), "says why when systemctl fails")
    }

    @Test
    fun friendlyNamesAndPurposes() {
        assertEquals("Slack", StartupNames.windows("com.squirrel.slack.slack", "C:\\x\\slack.exe", emptyList()).first)
        assertEquals("Google Drive", StartupNames.windows("GoogleDriveFS", "C:\\Program Files\\Google\\Drive File Stream\\launch.bat", emptyList()).first)
        assertEquals("Realtek Audio", StartupNames.windows("RtkAudUService", "C:\\x\\RtkAudUService64.exe", emptyList()).first)
        assertEquals("My Tool Helper", StartupNames.windows("MyToolHelper", "C:\\x\\h.exe", emptyList()).first)
        assertEquals("Aldente", StartupNames.tidy("com.apphousekitchen.aldente"))
        assertEquals("Checks for updates", StartupNames.purpose("GoogleUpdater", "", kind = StartupKind.Run))
        assertEquals("Shows an icon in the tray", StartupNames.purpose("nm-applet", "", kind = StartupKind.Autostart))
        assertEquals("Background helper", StartupNames.purpose("Adobe Helper", "", kind = StartupKind.Run))
        assertEquals("Opens in the background at login", StartupNames.purpose("Steam", "steam.exe -silent", kind = StartupKind.Run))
        assertEquals("Opens at login", StartupNames.purpose("Notes", "notes.exe", kind = StartupKind.Run))
        assertEquals("Take notes", StartupNames.purpose("Notes", "notes", "Take notes.", StartupKind.Autostart))
        assertEquals("Network icon", StartupNames.linux("nm-applet", "Network"))
    }

    @Test
    fun hintsForWhatDyskLinkLeavesToTheSystem() {
        val win = CareHints.cleaner(true, Distro.Other, { false })
        assertEquals("cleanmgr", win.single().command)
        val ubuntu = CareHints.cleaner(false, Distro.Debian, { it in setOf("journalctl", "flatpak") }, "1.2GB")
        assertEquals(listOf("sudo journalctl --vacuum-time=2weeks", "flatpak uninstall --unused", "sudo apt clean"), ubuntu.map { it.command })
        assertTrue("1.2GB" in ubuntu[0].text)
        assertEquals("sudo dnf install polkit", CareHints.uninstall(false, Distro.Fedora, { false }).single().command)
        assertTrue(CareHints.uninstall(false, Distro.Fedora, { it == "pkexec" }).isEmpty())
        val startup = CareHints.startup(true, listOf(StartupEntry("s", "x", "X", purpose = "", command = "", kind = StartupKind.Service, isOff = false, editable = false, adminOnly = true)))
        assertEquals(listOf("services.msc"), startup.map { it.command })
    }
}
