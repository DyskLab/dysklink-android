package id.dysklink.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class WindowTidyTest {
    /** A 1080p screen with a 40-pixel taskbar. */
    private val main = Monitor(Rect(0, 0, 1920, 1080), Rect(0, 0, 1920, 1040))
    /** To the left, so its coordinates are negative, with the taskbar on its left side. */
    private val left = Monitor(Rect(-1920, 0, 1920, 1080), Rect(-1872, 0, 1872, 1080))
    /** A 4K screen at 150 %, above and right, in physical pixels. */
    private val big = Monitor(Rect(1920, -600, 3840, 2160), Rect(1920, -600, 3840, 2100), scale = 1.5)
    /** An odd width, where thirds don't divide evenly. */
    private val laptop = Monitor(Rect(0, 0, 1366, 768), Rect(0, 27, 1366, 741))

    private fun inside(area: Rect, r: Rect) = r.x >= area.x && r.y >= area.y && r.right <= area.right && r.bottom <= area.bottom

    @Test
    fun everySpotOnEveryWorkArea() {
        val window = Rect(100, 100, 800, 600)
        for (m in listOf(main, left, big, laptop)) {
            val a = m.work
            val f = WindowSpot.entries.associateWith { it.frame(a, window) }
            f.values.forEach { assertTrue(inside(a, it), "$it is outside $a") }
            assertEquals(a, f[WindowSpot.Maximize])
            // Halves and quarters tile the area exactly.
            assertEquals(a.x, f[WindowSpot.LeftHalf]!!.x)
            assertEquals(f[WindowSpot.LeftHalf]!!.right, f[WindowSpot.RightHalf]!!.x)
            assertEquals(a.right, f[WindowSpot.RightHalf]!!.right)
            assertEquals(a.height, f[WindowSpot.LeftHalf]!!.height)
            assertEquals(f[WindowSpot.TopHalf]!!.bottom, f[WindowSpot.BottomHalf]!!.y)
            assertEquals(a.bottom, f[WindowSpot.BottomHalf]!!.bottom)
            assertEquals(a.area, listOf(WindowSpot.TopLeft, WindowSpot.TopRight, WindowSpot.BottomLeft, WindowSpot.BottomRight).sumOf { f[it]!!.area })
            // Thirds share their edges, whatever the rounding.
            assertEquals(f[WindowSpot.LeftThird]!!.right, f[WindowSpot.CenterThird]!!.x)
            assertEquals(f[WindowSpot.CenterThird]!!.right, f[WindowSpot.RightThird]!!.x)
            assertEquals(a.right, f[WindowSpot.RightThird]!!.right)
            assertEquals(f[WindowSpot.LeftTwoThirds]!!.right, f[WindowSpot.RightThird]!!.x)
            assertEquals(f[WindowSpot.LeftThird]!!.right, f[WindowSpot.RightTwoThirds]!!.x)
            assertEquals(a.width, f[WindowSpot.LeftThird]!!.width + f[WindowSpot.CenterThird]!!.width + f[WindowSpot.RightThird]!!.width)
            // Center keeps the size and sits in the middle.
            val c = f[WindowSpot.Center]!!
            assertEquals(800 to 600, c.width to c.height)
            assertTrue(kotlin.math.abs(c.centerX - a.centerX) <= 1 && kotlin.math.abs(c.centerY - a.centerY) <= 1)
        }
        assertEquals(Rect(-1872, 0, 936, 1080), WindowSpot.LeftHalf.frame(left.work, Rect(0, 0, 10, 10)))
        assertEquals(Rect(1920, -600, 1280, 2100), WindowSpot.LeftThird.frame(big.work, Rect(0, 0, 10, 10)))
        assertEquals(Rect(3200, -600, 1280, 2100), WindowSpot.CenterThird.frame(big.work, Rect(0, 0, 10, 10)))
        assertEquals(Rect(1920, 450, 3840, 1050), WindowSpot.BottomHalf.frame(big.work, Rect(0, 0, 10, 10)))
        assertEquals(Rect(0, 27, 455, 741), WindowSpot.LeftThird.frame(laptop.work, Rect(0, 0, 10, 10)))
        assertEquals(Rect(455, 27, 456, 741), WindowSpot.CenterThird.frame(laptop.work, Rect(0, 0, 10, 10)))
    }

    @Test
    fun centerShrinksAWindowBiggerThanTheScreen() {
        assertEquals(laptop.work, WindowSpot.Center.frame(laptop.work, Rect(0, 0, 2000, 1000)))
    }

    @Test
    fun invisibleBordersGrowWithTheScale() {
        // Windows 11 at 150 %: GetWindowRect is 11 pixels wider on the left, right and bottom than what's seen.
        val visible = Rect(1920, -600, 1920, 2100)
        val outer = Rect(1909, -600, 1942, 2111)
        val insets = WindowTidy.insets(outer, visible)
        assertEquals(Insets(11, 0, 11, 11), insets)
        assertEquals(outer, WindowTidy.outerFor(visible, insets))
        // At 100 % they're 7.
        assertEquals(Insets(7, 0, 7, 7), WindowTidy.insets(Rect(-7, 0, 974, 1047), Rect(0, 0, 960, 1040)))
        // Nonsense (a window that DWM reports bigger) is ignored.
        assertEquals(Insets.NONE, WindowTidy.insets(Rect(0, 0, 100, 100), Rect(-500, 0, 100, 100)))
    }

    @Test
    fun theMonitorAWindowIsOn() {
        val monitors = listOf(main, left, big)
        assertEquals(left, WindowTidy.monitorFor(monitors, Rect(-1000, 100, 400, 300)))
        // Mostly on the main screen, its middle there too.
        assertEquals(main, WindowTidy.monitorFor(monitors, Rect(-100, 100, 800, 300)))
        assertEquals(big, WindowTidy.monitorFor(monitors, Rect(2500, -500, 800, 300)))
        // Off every screen: the nearest.
        assertEquals(left, WindowTidy.monitorFor(monitors, Rect(-5000, 200, 100, 100)))
        assertNull(WindowTidy.monitorFor(emptyList(), Rect(0, 0, 1, 1)))
        assertEquals(listOf(left, main, big), WindowTidy.ordered(monitors))
    }

    @Test
    fun movingToTheNextScreenKeepsThePlaceAndShare() {
        val monitors = listOf(main, big, left)
        val half = WindowSpot.RightHalf.frame(main.work, Rect(0, 0, 1, 1))
        assertEquals(WindowSpot.RightHalf.frame(big.work, Rect(0, 0, 1, 1)), WindowTidy.onNextScreen(monitors, half, 1))
        assertEquals(WindowSpot.RightHalf.frame(left.work, Rect(0, 0, 1, 1)), WindowTidy.onNextScreen(monitors, half, -1))
        // From the last it goes round to the first.
        assertEquals(left, WindowTidy.monitorFor(monitors, WindowTidy.onNextScreen(monitors, Rect(2000, -500, 500, 500), 1)!!))
        assertNull(WindowTidy.onNextScreen(listOf(main), half, 1))
        // A window bigger than the target stays inside it.
        val scaled = WindowTidy.scaled(Rect(0, 0, 3000, 2000), main.work, laptop.work)
        assertTrue(inside(laptop.work, scaled))
    }

    @Test
    fun gridKeepsEveryWindowInsideAndCoversTheArea() {
        for (count in 1..9) {
            val cells = WindowTidy.grid(count, big.work)
            assertEquals(count, cells.size)
            cells.forEach { assertTrue(inside(big.work, it)) }
            assertEquals(big.work.area, cells.sumOf { it.area })
        }
        val five = WindowTidy.grid(5, main.work)
        assertEquals(640, five[0].width)
        assertEquals(960, five[3].width)
        assertTrue(WindowTidy.grid(0, main.work).isEmpty())
    }

    @Test
    fun x11ScreensAndWorkAreas() {
        // AWT gives 2x screens in halves; the X server counts pixels.
        assertEquals(Rect(3840, 0, 3840, 2160), WindowTidy.fromAwt(Rect(1920, 0, 1920, 1080), 2.0))
        val screens = listOf(Rect(0, 0, 1920, 1080), Rect(1920, 0, 2560, 1440))
        // GNOME: a top bar only on the first.
        val gtk = WindowTidy.x11WorkAreas(screens, Rect(0, 32, 4480, 1048), listOf(Rect(0, 32, 1920, 1048), Rect(1920, 0, 2560, 1440)))
        assertEquals(listOf(Rect(0, 32, 1920, 1048), Rect(1920, 0, 2560, 1440)), gtk.map { it.work })
        // Others: _NET_WORKAREA cut to each screen.
        val net = WindowTidy.x11WorkAreas(screens, Rect(0, 0, 4480, 1400), emptyList())
        assertEquals(listOf(Rect(0, 0, 1920, 1080), Rect(1920, 0, 2560, 1400)), net.map { it.work })
        assertEquals(screens, WindowTidy.x11WorkAreas(screens, null, emptyList()).map { it.work })
    }

    @Test
    fun x11ClientMessages() {
        val data = X11Moves.moveResize(Rect(-1920, 30, 960, 1050))
        assertEquals(10L or 0xF00L or 0x2000L, data[0])
        assertEquals(listOf(-1920L, 30L, 960L, 1050L), data.drop(1))
        assertEquals(56L, X11Moves.clientMessageOffsets(8).data)
        assertEquals(28L, X11Moves.clientMessageOffsets(4).data)
    }

    // Layouts

    private val chrome = OpenWindow(1, "chrome.exe", "Inbox - Gmail", Rect(0, 0, 960, 1040))
    private val code = OpenWindow(2, "code.exe", "project - Visual Studio Code", Rect(960, 0, 960, 1040))
    private val term = OpenWindow(3, "windowsterminal.exe", "PowerShell", Rect(-1872, 540, 1872, 540))

    @Test
    fun aLayoutGoesBackExactlyOnTheSameMonitors() {
        val monitors = listOf(main, left)
        val layout = Layouts.capture("Work", listOf(chrome, code, term), monitors, id = "w")
        assertEquals(Layouts.monitorKey(listOf(left, main)), layout.monitors)
        assertEquals(listOf(1, 1, 0), layout.windows.map { it.monitor })
        // The windows have moved since, and the titles changed a little.
        val now = listOf(
            OpenWindow(11, "code.exe", "other - Visual Studio Code", Rect(5, 5, 300, 300)),
            OpenWindow(12, "Chrome.exe", "Inbox - Gmail", Rect(5, 5, 300, 300)),
            OpenWindow(13, "chrome.exe", "News", Rect(5, 5, 300, 300)),
            OpenWindow(14, "windowsterminal.exe", "PowerShell", Rect(5, 5, 300, 300)),
        )
        val plan = Layouts.plan(layout, now, listOf(left, main)).associate { it.first.handle to it.second }
        assertEquals(mapOf(12L to chrome.rect, 11L to code.rect, 14L to term.rect), plan)
    }

    @Test
    fun onOtherMonitorsEachWindowTakesTheSameShare() {
        val layout = Layouts.capture("Work", listOf(chrome, code, term), listOf(main, left))
        val open = listOf(chrome.copy(handle = 21), code.copy(handle = 22), term.copy(handle = 23))
        // Only the laptop now: the left monitor's window goes to the last there is.
        val plan = Layouts.plan(layout, open, listOf(laptop)).associate { it.first.handle to it.second }
        assertEquals(WindowSpot.LeftHalf.frame(laptop.work, Rect(0, 0, 1, 1)), plan[21])
        assertEquals(WindowSpot.RightHalf.frame(laptop.work, Rect(0, 0, 1, 1)), plan[22])
        assertEquals(Rect(0, 398, 1366, 370), plan[23])
        plan.values.forEach { assertTrue(inside(laptop.work, it), "$it") }
        // Each open window is used once, even when two saved ones are of the same app.
        val twice = Layouts.capture("Two", listOf(chrome, chrome.copy(handle = 9, title = "News", rect = Rect(960, 0, 960, 1040))), listOf(main))
        assertEquals(1, Layouts.plan(twice, listOf(chrome.copy(title = "Other")), listOf(main)).size)
        assertTrue(Layouts.plan(layout, emptyList(), listOf(main)).isEmpty())
    }

    @Test
    fun layoutsForThisMonitorSetComeFirstAndAreKept() {
        val file = Files.createTempFile("layouts", ".json").toFile()
        val store = LayoutStore(file)
        val one = Layouts.capture("Laptop", listOf(chrome), listOf(laptop), id = "a")
        val two = Layouts.capture("Desk", listOf(chrome, term), listOf(main, left), id = "b")
        store.add(one); store.add(two)
        store.rename("a", "Laptop only")
        val again = LayoutStore(file).layouts.value
        assertEquals(listOf("Laptop only", "Desk"), again.map { it.name })
        assertEquals(two, again[1])
        assertEquals(listOf("b", "a"), Layouts.forMonitors(again, Layouts.monitorKey(listOf(left, main))).map { it.id })
        store.delete("a")
        assertEquals(listOf("b"), LayoutStore(file).layouts.value.map { it.id })
        assertEquals("2 monitors", Layouts.describe(listOf(main, left)))
        assertEquals("1 monitor, 1366x768", Layouts.describe(listOf(laptop)))
        file.delete()
    }

    // The service, with a pretend desktop

    private class FakeDesktop(val screens: List<Monitor>, var windows: MutableList<OpenWindow>) : WindowBackend {
        override val problem: String? = null
        val moves = mutableListOf<Pair<Long, Rect>>()
        override fun monitors() = screens
        override fun active() = windows.firstOrNull()
        override fun windows() = windows.toList()
        override fun move(window: OpenWindow, target: Rect) {
            moves += window.handle to target
            windows = windows.map { if (it.handle == window.handle) it.copy(rect = target) else it }.toMutableList()
        }
    }

    private fun service(backend: WindowBackend?, desktop: Desktop = Desktop.X11, hints: (WindowTidyView) -> List<HostHint> = { emptyList() }): Pair<WindowTidyService, File> {
        val dir = Files.createTempDirectory("tidy").toFile()
        return WindowTidyService(CoroutineScope(Dispatchers.Unconfined), Settings(File(dir, "s.properties")), dir, desktop, backend, hints) to dir
    }

    @Test
    fun theFrontWindowMovesOnItsOwnMonitor() {
        val fake = FakeDesktop(listOf(main, left), mutableListOf(term, chrome))
        val (tidy, dir) = service(fake)
        tidy.run(WindowSpot.RightTwoThirds.name)
        assertEquals(3L to WindowSpot.RightTwoThirds.frame(left.work, term.rect), fake.moves.single())
        tidy.run(WindowTidy.NEXT_SCREEN)
        assertEquals(main, WindowTidy.monitorFor(listOf(main, left), fake.moves.last().second))
        tidy.run("nonsense")
        assertEquals(2, fake.moves.size)
        dir.deleteRecursively()
    }

    @Test
    fun savingAndRestoringThroughTheService() {
        val fake = FakeDesktop(listOf(main), mutableListOf(chrome, code))
        val (tidy, dir) = service(fake)
        tidy.save(" Work ")
        val saved = tidy.store.layouts.value.single()
        assertEquals("Work", saved.name)
        fake.windows = mutableListOf(chrome.copy(rect = Rect(1, 1, 50, 50)), code.copy(rect = Rect(2, 2, 50, 50)))
        tidy.restore(saved.id)
        assertEquals(setOf(1L to chrome.rect, 2L to code.rect), fake.moves.toSet())
        assertTrue(tidy.view.value.message!!.startsWith("Restored Work: 2 of 2"))
        assertTrue(File(dir, "window-layouts.json").isFile)
        dir.deleteRecursively()
    }

    @Test
    fun waylandShowsTheDesktopsOwnKeysInstead() {
        val (tidy, dir) = service(null, Desktop.WAYLAND) { HostChecks.windowTidyHints(false, true, "GNOME", null, it.hotkey, it.mods) }
        tidy.start()
        tidy.act(TidyAction.SetEnabled(true))
        val view = tidy.view.value
        assertEquals(false, view.supported)
        assertTrue(view.hints.single().text.contains("Super+Left"))
        assertEquals(HintTone.Info, view.hints.single().tone)
        val kde = HostChecks.windowTidyHints(false, true, "KDE", null, view.hotkey, view.mods).single()
        assertTrue(kde.text.contains("Window Rules"))
        tidy.run(WindowSpot.LeftHalf.name)
        dir.deleteRecursively()
    }

    @Test
    fun windowTidyHintsElsewhere() {
        val ready = HotkeyStatus(HotkeyBackend.Windows, HotkeyPhase.Ready)
        assertEquals(listOf("On keyboards with AltGr, Ctrl+Alt types letters"), HostChecks.windowTidyHints(true, false, null, null, ready, TidyMods.CtrlAlt).map { it.title })
        assertTrue(HostChecks.windowTidyHints(true, false, null, null, ready, TidyMods.CtrlAltShift).isEmpty())
        val some = HostChecks.windowTidyHints(true, false, null, null, ready.copy(taken = listOf("Ctrl+Alt+Left")), TidyMods.CtrlAltShift)
        assertEquals("Ctrl+Alt+Left couldn't be used", some.single().title)
        assertEquals(HostChecks.ACTION_TIDY_HOTKEY, some.single().action?.id)
        val x11 = HostChecks.windowTidyHints(false, false, "GNOME", "The window manager doesn't let apps move windows (it lacks _NET_MOVERESIZE_WINDOW).", HotkeyStatus(HotkeyBackend.X11, HotkeyPhase.Ready), TidyMods.CtrlAlt)
        assertEquals(listOf("Windows can't be moved here", "The desktop may keep some of these keys"), x11.map { it.title })
    }

    @Test
    fun shortcutsAreTheMacsAndNeverRepeat() {
        for (mods in TidyMods.entries) {
            val list = WindowTidyService.shortcuts(mods)
            assertEquals(18, list.size)
            assertEquals(list.size, list.map { it.combo }.toSet().size)
            assertTrue(list.all { it.combo.ctrl && it.combo.alt })
        }
        val plain = WindowTidyService.shortcuts(TidyMods.CtrlAlt).associate { it.id to it.combo.label }
        assertEquals("Ctrl+Alt+Left", plain["LeftHalf"])
        assertEquals("Ctrl+Alt+D", plain["LeftThird"])
        assertEquals("Ctrl+Alt+Enter", plain["Maximize"])
        assertEquals("Ctrl+Alt+Super+Right", plain[WindowTidy.NEXT_SCREEN])
        assertEquals("Ctrl+Alt+Shift+G", plain[WindowTidy.GRID])
        assertEquals("Ctrl+Alt+Shift+Super+G", WindowTidyService.shortcuts(TidyMods.CtrlAltShift).first { it.id == WindowTidy.GRID }.combo.label)
        assertEquals("LeftHalf", WindowTidy.commandId("left-half"))
        assertEquals("grid", WindowTidy.commandId("GRID"))
        assertEquals("right-two-thirds", WindowTidy.commandName("RightTwoThirds"))
        assertEquals("next-screen", WindowTidy.commandName(WindowTidy.NEXT_SCREEN))
    }
}
