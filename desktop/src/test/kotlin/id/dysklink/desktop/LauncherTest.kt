package id.dysklink.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LauncherTest {
    private val now = 1_762_900_000_000L

    // Linux apps

    private val firefox = """
        [Desktop Entry]
        Version=1.0
        Type=Application
        Name=Firefox Web Browser
        Name[id]=Peramban Web Firefox
        Name[id_ID]=Firefox (Indonesia)
        GenericName=Web Browser
        Keywords=Internet;WWW;Browser;
        Exec=/usr/lib/firefox/firefox %u
        Terminal=false

        [Desktop Action new-window]
        Name=Open a New Window
        Exec=/usr/lib/firefox/firefox -new-window
    """.trimIndent()

    @Test
    fun desktopEntryTakesTheBestLocalName() {
        val id = DesktopEntries.parse(firefox, "firefox.desktop", "/usr/share/applications/firefox.desktop", listOf("id_ID", "id"), listOf("GNOME"))!!
        assertEquals("Firefox (Indonesia)", id.name)
        assertEquals("app:firefox.desktop", id.id)
        assertEquals(listOf("/usr/lib/firefox/firefox"), (id.launch as AppLaunch.DesktopFile).exec)
        assertTrue("Browser" in id.keywords && "Firefox Web Browser" in id.keywords)
        assertEquals("Peramban Web Firefox", DesktopEntries.parse(firefox, "f", "p", listOf("id"), emptyList())!!.name)
        assertEquals("Firefox Web Browser", DesktopEntries.parse(firefox, "f", "p", emptyList(), emptyList())!!.name)
        // Actions in other groups don't replace the entry's own Exec.
        assertEquals(listOf("/usr/lib/firefox/firefox"), (DesktopEntries.parse(firefox, "f", "p", emptyList(), emptyList())!!.launch as AppLaunch.DesktopFile).exec)
    }

    @Test
    fun desktopEntriesTheMenuWouldntShowAreLeftOut() {
        fun entry(extra: String) = "[Desktop Entry]\nType=Application\nName=Thing\nExec=thing\n$extra"
        assertNull(DesktopEntries.parse(entry("NoDisplay=true"), "a", "p", emptyList(), listOf("GNOME")))
        assertNull(DesktopEntries.parse(entry("Hidden=true"), "a", "p", emptyList(), listOf("GNOME")))
        assertNull(DesktopEntries.parse(entry("OnlyShowIn=KDE;"), "a", "p", emptyList(), listOf("ubuntu", "GNOME")))
        assertNotNull(DesktopEntries.parse(entry("OnlyShowIn=GNOME;Unity;"), "a", "p", emptyList(), listOf("ubuntu", "GNOME")))
        assertNull(DesktopEntries.parse(entry("NotShowIn=GNOME;"), "a", "p", emptyList(), listOf("GNOME")))
        assertNull(DesktopEntries.parse(entry("TryExec=not-installed"), "a", "p", emptyList(), emptyList()) { false })
        assertNotNull(DesktopEntries.parse(entry("TryExec=thing"), "a", "p", emptyList(), emptyList()) { it == "thing" })
        assertNull(DesktopEntries.parse("[Desktop Entry]\nType=Link\nName=Site\nURL=https://x", "a", "p", emptyList(), emptyList()))
        assertNull(DesktopEntries.parse("[Desktop Entry]\nType=Application\nName=No exec", "a", "p", emptyList(), emptyList()))
    }

    @Test
    fun execLinesAreSplitAsTheSpecQuotesThem() {
        assertEquals(listOf("flatpak", "run", "--branch=stable", "org.gimp.GIMP", "@@u", "@@"), DesktopEntries.exec("flatpak run --branch=stable org.gimp.GIMP @@u %U @@"))
        assertEquals(listOf("sh", "-c", "echo \"hi\" \$HOME"), DesktopEntries.exec("sh -c \"echo \\\"hi\\\" \\\$HOME\""))
        assertEquals(listOf("app", "--name", "My App", "--file", "/x.desktop"), DesktopEntries.exec("app --name %c --file %k %F", "My App", "/x.desktop"))
        assertEquals(listOf("app", "100%"), DesktopEntries.exec("app 100%% %i"))
        assertEquals(listOf("/opt/My Apps/run"), DesktopEntries.exec("\"/opt/My Apps/run\""))
        // Escapes in the value itself: \s is a space.
        assertEquals("a b", DesktopEntries.group("[Desktop Entry]\nName=a\\sb")["Name"])
    }

    @Test
    fun localesAndDataFolders() {
        assertEquals(listOf("id_ID", "id"), DesktopEntries.languages(mapOf("LANG" to "id_ID.UTF-8")::get))
        assertEquals(listOf("sr_RS@latin", "sr_RS", "sr@latin", "sr"), DesktopEntries.languages(mapOf("LC_ALL" to "sr_RS.UTF-8@latin")::get))
        assertEquals(emptyList(), DesktopEntries.languages(mapOf("LANG" to "C")::get))
        val dirs = DesktopEntries.dataDirs(mapOf("XDG_DATA_DIRS" to "/usr/share:/var/lib/snapd/desktop")::get, "/home/u")
        assertEquals("/home/u/.local/share", dirs.first())
        assertTrue("/var/lib/flatpak/exports/share" in dirs && "/home/u/.local/share/flatpak/exports/share" in dirs)
        assertEquals(1, dirs.count { it == "/var/lib/snapd/desktop" })
    }

    @Test
    fun aUsersOwnCopyHidesTheSystemOne() {
        val root = Files.createTempDirectory("apps").toFile()
        val user = File(root, "user/applications").apply { mkdirs() }
        val system = File(root, "system/applications").apply { mkdirs() }
        File(system, "org.gnome.Calculator.desktop").writeText("[Desktop Entry]\nType=Application\nName=Calculator\nExec=gnome-calculator")
        File(system, "vim.desktop").writeText("[Desktop Entry]\nType=Application\nName=Vim\nExec=vim %F\nTerminal=true")
        File(user, "vim.desktop").writeText("[Desktop Entry]\nType=Application\nName=Vim\nExec=vim\nHidden=true")
        File(system, "kde").mkdirs(); File(system, "kde/konsole.desktop").writeText("[Desktop Entry]\nType=Application\nName=Konsole\nExec=konsole")
        val apps = DesktopEntries.scan(listOf("$root/user", "$root/system"), emptyList(), listOf("KDE"), { true })
        assertEquals(listOf("Calculator", "Konsole"), apps.map { it.name })
        assertEquals("app:kde-konsole.desktop", apps[1].id)
        root.deleteRecursively()
    }

    // Windows apps

    @Test
    fun startAppsOutputInBothShapes() {
        val tabs = "Calculator\tMicrosoft.WindowsCalculator_8wekyb3d8bbwe!App\r\nVisual Studio Code\t{6D809377-6AF0-444B-8957-A3773F02200E}\\Microsoft VS Code\\Code.exe\r\n\r\n"
        assertEquals(
            listOf("Calculator" to "Microsoft.WindowsCalculator_8wekyb3d8bbwe!App", "Visual Studio Code" to "{6D809377-6AF0-444B-8957-A3773F02200E}\\Microsoft VS Code\\Code.exe"),
            StartMenuApps.parseStartApps(tabs),
        )
        val table = """

            Name                         AppID
            ----                         -----
            Microsoft Store              Microsoft.WindowsStore_8wekyb3d8bbwe!App
            Windows PowerShell ISE       {1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\WindowsPowerShell\v1.0\PowerShell_ISE.exe

        """.trimIndent()
        assertEquals(listOf("Microsoft Store", "Windows PowerShell ISE"), StartMenuApps.parseStartApps(table).map { it.first })
        assertEquals("Microsoft.WindowsStore_8wekyb3d8bbwe!App", StartMenuApps.parseStartApps(table)[0].second)
        assertEquals(emptyList(), StartMenuApps.parseStartApps("Get-StartApps : not recognized"))
    }

    @Test
    fun startMenuShortcutsByNameAndMergedWithStoreApps() {
        val root = Files.createTempDirectory("start").toFile()
        File(root, "Accessories").mkdirs()
        File(root, "Accessories/Notepad.lnk").writeBytes(byteArrayOf(0x4C, 0, 0, 0))
        File(root, "7-Zip").mkdirs()
        File(root, "7-Zip/7-Zip File Manager.lnk").writeBytes(byteArrayOf(0x4C))
        File(root, "7-Zip/Uninstall 7-Zip.lnk").writeBytes(byteArrayOf(0x4C))
        File(root, "Steam Game.url").writeText("[InternetShortcut]\nURL=steam://rungameid/1")
        val shortcuts = StartMenuApps.shortcuts(listOf(root))
        assertEquals(setOf("Notepad", "7-Zip File Manager", "Steam Game"), shortcuts.map { it.name }.toSet())
        assertEquals("Accessories", shortcuts.first { it.name == "Notepad" }.detail)
        val merged = StartMenuApps.merge(shortcuts, listOf("Notepad" to "{x}\\notepad.exe", "Calculator" to "Microsoft.WindowsCalculator_8wekyb3d8bbwe!App"))
        assertEquals(1, merged.count { it.name == "Notepad" })
        assertTrue(merged.first { it.name == "Notepad" }.launch is AppLaunch.Shortcut)
        assertEquals(AppLaunch.AppsFolder("Microsoft.WindowsCalculator_8wekyb3d8bbwe!App"), merged.first { it.name == "Calculator" }.launch)
        root.deleteRecursively()
    }

    // Answers

    @Test
    fun calculator() {
        assertEquals(14.0, LauncherMath.evaluate("2+3*4"))
        assertEquals(9.0, LauncherMath.evaluate("(1+2)^2"))
        assertEquals(2.5, LauncherMath.evaluate("10 / 4"))
        assertEquals(512.0, LauncherMath.evaluate("2^3^2"))
        assertEquals(1.0, LauncherMath.evaluate("7%3"))
        assertEquals(3.0, LauncherMath.evaluate("1,5×2"))
        assertEquals(2.0, LauncherMath.evaluate("-3+5"))
        assertEquals(-4.0, LauncherMath.evaluate("2*-2"))
        assertNull(LauncherMath.evaluate("1/0"))
        assertNull(LauncherMath.evaluate("42"))
        assertNull(LauncherMath.evaluate("calc"))
        assertNull(LauncherMath.evaluate("(1+2"))
        assertNull(LauncherMath.evaluate("2**3"))
        assertNull(LauncherMath.evaluate("(".repeat(60) + "1" + ")".repeat(60)))
        assertEquals("0.33333333", LauncherMath.format(1.0 / 3))
        assertEquals("12", LauncherMath.format(12.0))
        assertEquals("2.5", LauncherMath.format(2.5))
    }

    @Test
    fun units() {
        assertEquals("6.2137 mi", LauncherUnits.convert("10 km to mi"))
        assertEquals("212 f", LauncherUnits.convert("100 c to f"))
        assertEquals("21.1111 C", LauncherUnits.convert("70 f in C"))
        assertEquals("2267.9619 g", LauncherUnits.convert("5 lb ke g"))
        assertNull(LauncherUnits.convert("1 kg to mi"))
        assertNull(LauncherUnits.convert("10 parsec to km"))
    }

    @Test
    fun queriesBecomeTheRightRows() {
        val sum = LauncherQuery.results("12*7+3", emptyList(), Frecency(), now)
        assertEquals("87", sum.items.first().title)
        assertEquals(LauncherTarget.Copy("87"), sum.items.first().target)
        assertNull(sum.files)
        val files = LauncherQuery.results("f report", emptyList(), Frecency(), now)
        assertEquals(LauncherSearch.Scope("report", false), files.files)
        assertEquals(20, files.fileLimit)
        assertTrue(files.web.isEmpty())
        assertEquals(LauncherSearch.Scope("photos", true), LauncherQuery.results("d photos", emptyList(), Frecency(), now).files)
        val web = LauncherQuery.results("yt lofi beats", emptyList(), Frecency(), now).web
        assertEquals(LauncherTarget.Url("https://www.youtube.com/results?search_query=lofi%20beats"), web.single().target)
        assertEquals("Open github.com", LauncherQuery.results("github.com", emptyList(), Frecency(), now).web.first().title)
        assertNull(LauncherWeb.address("report.pdf"))
        assertNull(LauncherWeb.address("two words.com"))
        assertEquals("https://example.co.id/path", LauncherWeb.address("example.co.id/path"))
        assertEquals(LauncherSearch.Scope("calc", false), LauncherQuery.results("calc", emptyList(), Frecency(), now).files)
    }

    // Ranking

    private fun app(name: String, keywords: String = "") = LauncherItem("app:$name", LauncherKind.App, name, target = LauncherTarget.Copy(name), keywords = keywords)
    private fun action(id: String, title: String) = LauncherItem("action:$id", LauncherKind.Action, title, target = LauncherTarget.Action(id))

    @Test
    fun rankingByFitThenRecentUse() {
        val candidates = listOf(app("Calendar"), app("Calculator"), app("Visual Studio Code"), app("Files", "nautilus explorer"), action("pomodoro", "Start focus"))
        assertEquals(listOf("Calendar", "Calculator"), LauncherQuery.results("cal", candidates, Frecency(), now).items.map { it.title })
        assertEquals("Visual Studio Code", LauncherQuery.results("vsc", candidates, Frecency(), now).items.first().title)
        assertEquals("Files", LauncherQuery.results("nautilus", candidates, Frecency(), now).items.first().title)
        var used = Frecency()
        repeat(3) { used = used.used("app:Calculator", now - 1000) }
        assertEquals(listOf("Calculator", "Calendar"), LauncherQuery.results("cal", candidates, used, now).items.map { it.title })
        // Exactly typed still beats often used.
        assertEquals("Calendar", LauncherQuery.results("calendar", candidates, used, now).items.first().title)
    }

    @Test
    fun frecencyFadesAndFillsTheEmptyQuery() {
        val f = Frecency().used("a", now).used("a", now).used("b", now - 40 * Frecency.DAY)
        assertEquals(6, f.bonus("a", now))
        assertEquals(0, f.bonus("b", now))
        assertEquals(3, Frecency().used("b", now).bonus("b", now + 1000))
        assertTrue(f.bonus("a", now + 10 * Frecency.DAY) < f.bonus("a", now))
        assertEquals(listOf("a", "b"), f.top(now, 5))
        val candidates = listOf(app("Calculator"), action("open", "Open DyskLink"), action("pomodoro", "Start focus"))
        val empty = LauncherQuery.results("", candidates, Frecency().used("app:Calculator", now), now).items.map { it.title }
        assertEquals(listOf("Calculator", "Open DyskLink", "Start focus"), empty)
        var many = Frecency()
        repeat(Frecency.MAX + 50) { many = many.used("id$it", now + it) }
        assertEquals(Frecency.MAX, many.uses.size)
    }

    // Emoji

    @Test
    fun emojiByEnglishIndonesianFlagsAndTones() {
        assertEquals("🔥", LauncherEmoji.search("api").first().emoji)
        assertEquals("👍", LauncherEmoji.search("jempol").first().emoji)
        assertTrue(LauncherEmoji.search("grinning").any { it.emoji == "😀" })
        assertTrue(LauncherEmoji.search("smiling face sunglasses").any { it.emoji == "😎" })
        assertEquals("🇯🇵", LauncherEmoji.search("jepang").first().emoji)
        assertEquals("🇮🇩", LauncherEmoji.search("indonesia").first().emoji)
        assertTrue(LauncherEmoji.search("programmer").any { it.emoji == "🧑‍💻" })
        assertTrue(LauncherEmoji.search("zzqx").isEmpty())
        assertEquals("👍🏽" to 3, LauncherEmoji.splitTone("jempol sawo").let { (rest, tone) -> LauncherEmoji.toned(LauncherEmoji.search(rest).first().emoji, tone!!) to tone })
        assertEquals("✌🏿", LauncherEmoji.toned("✌️", 5))
        assertEquals("👨‍👩‍👧", LauncherEmoji.toned("👨‍👩‍👧", 2))
        assertEquals("🔥", LauncherEmoji.toned("🔥", 2))
        assertEquals("" to 0, LauncherEmoji.splitTone("kuning"))
        // Text-style emoji are found with or without the variation selector.
        assertEquals(LauncherEmoji.normalized("❤"), LauncherEmoji.normalized("❤️"))
        assertTrue(LauncherEmoji.all.size > 1_000)
        assertTrue(LauncherEmoji.all.none { it.emoji.codePoints().anyMatch { cp -> cp in 0x1F3FB..0x1F3FF } })
    }

    @Test
    fun emojiRowsRememberTheToneAndRecentOnes() {
        val (rows, tone) = LauncherEmoji.items("tepuk gelap", 0, emptyList(), canType = true)
        assertEquals(5, tone)
        assertEquals("👏🏿", rows.first().emoji)
        assertEquals(LauncherTarget.Emoji("👏🏿", "👏"), rows.first().target)
        val (recent, kept) = LauncherEmoji.items("", 5, listOf("🙏"), canType = false)
        assertEquals(5, kept)
        assertEquals("🙏🏿", recent.first().emoji)
        assertEquals("Enter copies it", recent.first().subtitle)
        val viaQuery = LauncherQuery.results(":api", emptyList(), Frecency(), now, emoji = { LauncherEmoji.items(it, 0, emptyList(), true).first })
        assertEquals("🔥", viaQuery.items.first().emoji)
        assertNull(viaQuery.files)
    }

    // What a row does

    @Test
    fun actionRouting() {
        val lnk = LauncherApp("lnk:notepad", "Notepad", AppLaunch.Shortcut("C:\\Start\\Notepad.lnk"))
        val store = LauncherApp("uwp:calc", "Calculator", AppLaunch.AppsFolder("Microsoft.WindowsCalculator_8wekyb3d8bbwe!App"))
        val r = LauncherRouter
        assertEquals(LauncherEffect.Launch(lnk), r.route(LauncherTarget.App(lnk), LauncherModifier.None, true))
        assertEquals(LauncherEffect.Reveal("C:\\Start\\Notepad.lnk"), r.route(LauncherTarget.App(lnk), LauncherModifier.Reveal, true))
        assertEquals(LauncherEffect.Launch(store), r.route(LauncherTarget.App(store), LauncherModifier.Reveal, true))
        assertEquals(LauncherEffect.Copy("shell:AppsFolder\\Microsoft.WindowsCalculator_8wekyb3d8bbwe!App"), r.route(LauncherTarget.App(store), LauncherModifier.CopyPath, true))
        val file = LauncherTarget.Path("/home/u/report.pdf", false)
        assertEquals(LauncherEffect.Open("/home/u/report.pdf"), r.route(file, LauncherModifier.None, true))
        assertEquals(LauncherEffect.Reveal("/home/u/report.pdf"), r.route(file, LauncherModifier.Reveal, true))
        assertEquals(LauncherEffect.Copy("/home/u/report.pdf"), r.route(file, LauncherModifier.CopyPath, true))
        assertEquals(LauncherEffect.Type("🔥"), r.route(LauncherTarget.Emoji("🔥", "🔥"), LauncherModifier.None, true))
        assertEquals(LauncherEffect.Copy("🔥"), r.route(LauncherTarget.Emoji("🔥", "🔥"), LauncherModifier.None, false))
        assertEquals(LauncherEffect.Copy("🔥"), r.route(LauncherTarget.Emoji("🔥", "🔥"), LauncherModifier.Reveal, true))
        assertEquals(LauncherEffect.Run("ring:p1"), r.route(LauncherTarget.Action("ring:p1"), LauncherModifier.CopyPath, true))
        assertEquals(LauncherEffect.PasteClip("c1"), r.route(LauncherTarget.Clip("c1"), LauncherModifier.None, true))
        assertEquals(LauncherEffect.CopyClip("c1"), r.route(LauncherTarget.Clip("c1"), LauncherModifier.None, false))
        assertEquals(LauncherEffect.Browse("https://x.org"), r.route(LauncherTarget.Url("https://x.org"), LauncherModifier.None, true))
        assertEquals(LauncherEffect.Copy("https://x.org"), r.route(LauncherTarget.Url("https://x.org"), LauncherModifier.CopyPath, true))
        assertEquals(LauncherEffect.Exec(listOf("explorer.exe", "ms-settings:display")), r.route(LauncherTarget.Command(listOf("explorer.exe", "ms-settings:display")), LauncherModifier.None, true))
    }

    @Test
    fun keysTypeMoveAndRun() {
        var state = LauncherState()
        fun press(key: LauncherKey, count: Int = 5): LauncherStep { val (s, step) = LauncherKeys.reduce(state, key, count); state = s; return step }
        press(LauncherKey.Type("ca")); press(LauncherKey.Type("l\n"))
        assertEquals("cal", state.query)
        press(LauncherKey.Down); press(LauncherKey.Down)
        assertEquals(2, state.selection)
        press(LauncherKey.PageDown)
        assertEquals(4, state.selection)
        assertEquals(LauncherStep.Run(4, LauncherModifier.Reveal), press(LauncherKey.Enter(LauncherModifier.Reveal)))
        press(LauncherKey.Backspace)
        assertEquals(LauncherState("ca", 0), state)
        assertEquals(LauncherStep.None, press(LauncherKey.Escape))
        assertEquals("", state.query)
        assertEquals(LauncherStep.Close, press(LauncherKey.Escape))
        assertEquals(LauncherStep.None, press(LauncherKey.Enter(), count = 0))
        press(LauncherKey.Type("x".repeat(300)))
        assertEquals(200, state.query.length)
        press(LauncherKey.Clear)
        assertEquals("", state.query)
    }

    // Files

    @Test
    fun fileIndexSkipsHiddenAndPackageFolders() {
        val home = Files.createTempDirectory("home").toFile()
        File(home, "Documents/Reports").mkdirs()
        File(home, "Documents/Reports/report-2026.pdf").writeText("x")
        File(home, "Documents/annual report.docx").writeText("x")
        File(home, ".cache/report-cache").mkdirs()
        File(home, "code/node_modules/report").mkdirs()
        File(home, "Downloads").mkdirs()
        File(home, "Downloads/Report Photos").mkdirs()
        val files = FileIndex.walk(listOf(home), cap = 1000, maxDepth = 6)
        assertTrue(files.none { ".cache" in it.path || "node_modules" in it.path })
        val found = FileIndex.rank(files, "report", 10)
        assertEquals(setOf("Reports", "report-2026.pdf", "annual report.docx", "Report Photos"), found.map { it.name }.toSet())
        assertEquals(setOf("Reports", "Report Photos"), FileIndex.rank(files, "report", 10, foldersOnly = true).map { it.name }.toSet())
        assertEquals(listOf("Report Photos"), FileIndex.rank(files, "photos report", 10).map { it.name })
        assertEquals(2, FileIndex.walk(listOf(home), cap = 2, maxDepth = 6).size)
        val rows = LauncherQuery.fileItems(found.filter { it.name == "Reports" }, home.path)
        assertEquals(LauncherKind.Folder, rows.single().kind)
        assertEquals("~${File.separator}Documents", rows.single().subtitle)
        home.deleteRecursively()
    }

    @Test
    fun locateResultsAreKeptToTheHomeFolder() {
        val out = "/home/u/Documents/report.pdf\n/home/u/.local/share/report\n/usr/share/doc/report\n/home/u/Old/report-gone.txt\n/home/u/Projects\n"
        val exists = mapOf("/home/u/Documents/report.pdf" to false, "/home/u/Projects" to true)
        val found = Locate.parse(out, "/home/u", "report", 10, false) { exists[it] }
        assertEquals(listOf("/home/u/Documents/report.pdf"), found.map { it.path })
        assertEquals(listOf("plocate", "-i", "-b", "-l", "60", "--", "report"), Locate.command("plocate", "report", 6))
    }

    // Shortcuts and commands

    @Test
    fun spaceAndArrowShortcuts() {
        val alt = HotkeyCombo.parse("Alt+Space")!!
        assertEquals(LauncherService.DEFAULT_COMBO, alt)
        assertEquals("Alt+Space", alt.label)
        assertEquals("ALT+space", alt.portalTrigger)
        assertEquals(0x20, alt.windowsKey)
        assertEquals(0x20L, alt.x11Keysym)
        val left = HotkeyCombo.parse("ctrl+alt+left")!!
        assertEquals(0x25, left.windowsKey)
        assertEquals(0xFF51L, left.x11Keysym)
        assertEquals("CTRL+ALT+Left", left.portalTrigger)
        assertEquals(0x0D, HotkeyCombo.parse("Ctrl+Alt+Return")!!.windowsKey)
        assertNull(HotkeyCombo.parse("Space"))
        assertNull(HotkeyCombo.parse("Ctrl+F13"))
        assertEquals('V'.code, HotkeyCombo.DEFAULT.windowsKey)
    }

    @Test
    fun severalShortcutsReportTheTakenOnes() {
        assertEquals(HotkeyPhase.Ready, Hotkeys.outcome(HotkeyBackend.Windows, listOf("A" to true, "B" to true)).phase)
        val some = Hotkeys.outcome(HotkeyBackend.Windows, listOf("A" to true, "B" to false))
        assertEquals(HotkeyPhase.Ready, some.phase)
        assertEquals(listOf("B"), some.taken)
        val one = Hotkeys.outcome(HotkeyBackend.Windows, listOf("Alt+Space" to false), "error 1409")
        assertEquals(HotkeyPhase.Taken, one.phase)
        assertEquals("Another app already uses Alt+Space (error 1409).", one.detail)
    }

    @Test
    fun commandLineAndInstanceCommands() {
        assertEquals(SingleInstance.LAUNCHER, SingleInstance.commandFor(arrayOf("--launcher")))
        assertEquals(SingleInstance.CLIPBOARD, SingleInstance.commandFor(arrayOf("--hidden", "--clipboard")))
        assertEquals("window:LeftHalf", SingleInstance.commandFor(arrayOf("--window", "left-half")))
        assertEquals("window:nextScreen", SingleInstance.commandFor(arrayOf("--window", "next-screen")))
        assertNull(SingleInstance.commandFor(arrayOf("--window", "sideways")))
        assertNull(SingleInstance.commandFor(arrayOf("--hidden")))
        assertEquals("launcher", SingleInstance.parse("t launcher", "t"))
        assertEquals("window:RightThird", SingleInstance.parse("t window:RightThird", "t"))
        assertNull(SingleInstance.parse("t window:rm-rf", "t"))
        assertEquals("dysklink --launcher", Hotkeys.command(appPath = null, flag = "--launcher"))
    }

    @Test
    fun launcherHints() {
        val ready = HotkeyStatus(HotkeyBackend.Windows, HotkeyPhase.Ready)
        val win = HostChecks.launcherHints(ready, LauncherService.DEFAULT_COMBO, "dysklink --launcher", windows = true, currentDesktop = null, canType = true)
        assertEquals(listOf("Alt+Space now opens the Launcher"), win.map { it.title })
        assertTrue(HostChecks.launcherHints(ready, LauncherService.CHOICES[1], "c", windows = true, currentDesktop = null, canType = true).isEmpty())
        val gnome = HostChecks.launcherHints(HotkeyStatus(HotkeyBackend.X11, HotkeyPhase.Ready), LauncherService.DEFAULT_COMBO, "c", false, "ubuntu:GNOME", canType = false)
        assertEquals("gsettings set org.gnome.desktop.wm.keybindings activate-window-menu \"[]\"", gnome.first().command)
        assertTrue(gnome.any { it.title.startsWith("Enter copies emoji") })
        assertTrue(HostChecks.launcherHints(HotkeyStatus(HotkeyBackend.X11, HotkeyPhase.Ready), LauncherService.DEFAULT_COMBO, "c", false, "KDE", true).single().text.contains("KRunner"))
        val wayland = HostChecks.launcherHints(HotkeyStatus(HotkeyBackend.Command, HotkeyPhase.Ready), LauncherService.DEFAULT_COMBO, "dysklink --launcher", false, "GNOME", true)
        assertEquals("dysklink --launcher", wayland.single().command)
        val taken = HostChecks.launcherHints(HotkeyStatus(HotkeyBackend.Windows, HotkeyPhase.Taken, "Another app already uses Alt+Space."), LauncherService.DEFAULT_COMBO, "c", true, null, true)
        assertEquals(HostChecks.ACTION_LAUNCHER_HOTKEY, taken.single().action?.id)
        assertFalse(taken.any { it.title.startsWith("Alt+Space now") })
    }

    @Test
    fun settingsPagesForEachDesktop() {
        val windows = LauncherSettingsPages.forThisPc(true, LoginDesktop.Other) { false }
        assertEquals(listOf("explorer.exe", "ms-settings:display"), (windows.first { it.title == "Display" }.target as LauncherTarget.Command).argv)
        val gnome = LauncherSettingsPages.forThisPc(false, LoginDesktop.Gnome) { it == "gnome-control-center" }
        assertEquals(listOf("gnome-control-center", "sound"), (gnome.first { it.title == "Sound" }.target as LauncherTarget.Command).argv)
        assertTrue(gnome.none { it.title == "Updates" })
        assertTrue(LauncherSettingsPages.forThisPc(false, LoginDesktop.Gnome) { false }.isEmpty())
        assertEquals("Display", LauncherQuery.results("resolusi", windows, Frecency(), now).items.first().title)
    }
}
