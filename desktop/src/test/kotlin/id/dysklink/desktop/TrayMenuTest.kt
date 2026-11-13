package id.dysklink.desktop

import id.phonelink.android.core.DeviceInfo
import id.phonelink.android.core.HubPhone
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrayMenuTest {
    private val pixel = HubPhone("p1", "Pixel 8", "Pixel 8", isPaired = true, isConnected = true, info = DeviceInfo(batteryPercent = 76, isCharging = true))
    private val tab = HubPhone("p2", "Galaxy Tab", "SM-X710", isPaired = true, isConnected = false)
    private val state = TrayMenuState(listOf(pixel, tab), battery = "54%", clipboardHistory = true, pcCare = true, launcher = true)

    private fun labels(entries: List<TrayEntry>): List<String> = entries.map {
        when (it) {
            is TrayEntry.Item -> it.label
            is TrayEntry.Submenu -> "${it.label}>" + labels(it.children).joinToString(",")
            TrayEntry.Separator -> "-"
        }
    }

    @Test
    fun theDefaultIsTheMenuAsItWas() {
        assertEquals(
            listOf(
                "Open DyskLink", "-", "Battery: 54%", "Start focus", "Drink a glass of water", "Tools…", "-",
                "Pixel 8: 76%, charging", "Galaxy Tab: offline", "-", "Send file to Pixel 8…", "Send clipboard", "Clipboard history", "-", "Quit DyskLink",
            ),
            labels(TrayMenu.build(TrayMenuConfig(), state, TrayMenuActions())),
        )
    }

    @Test
    fun sectionsCanBeHiddenMovedAndAdded() {
        val config = TrayMenuConfig()
            .setShown(TraySection.Water, false).setShown(TraySection.Tools, false).setShown(TraySection.Battery, false)
            .setShown(TraySection.Launcher, true).setShown(TraySection.PcCare, true)
            .move(TraySection.SendClipboard, -1).move(TraySection.Launcher, -100)
        assertEquals(TraySection.SendClipboard, config.visible[config.visible.indexOf(TraySection.SendFile) - 1])
        val menu = labels(TrayMenu.build(config.move(TraySection.Launcher, -1).move(TraySection.Launcher, -1), state.copy(trayStatus = "Focus 1/4: 24:59", pomodoroStarted = true, pomodoroRunning = true), TrayMenuActions()))
        assertEquals("Quit DyskLink", menu.last())
        assertTrue("Focus 1/4: 24:59" in menu && "Pause focus" in menu)
        assertTrue("Drink a glass of water" !in menu && "Tools…" !in menu)
        assertTrue(menu.indexOf("Send clipboard") < menu.indexOf("Send file to Pixel 8…"))
        assertTrue("Launcher" in menu && "PC care…" in menu)
        // Never two lines in a row, nor one at the top.
        assertTrue(menu.zipWithNext().none { it.first == "-" && it.second == "-" })
        assertTrue(menu.first() != "-")
    }

    @Test
    fun emptySectionsStayHiddenAndQuitStays() {
        val all = TrayMenuConfig(shown = TraySection.entries.toSet())
        val bare = labels(TrayMenu.build(all, TrayMenuState(), TrayMenuActions()))
        assertEquals(listOf("Open DyskLink", "-", "Start focus", "Drink a glass of water", "Tools…", "-", "Send file…", "Send clipboard", "-", "Quit DyskLink"), bare)
        assertEquals(listOf("Quit DyskLink"), labels(TrayMenu.build(TrayMenuConfig(shown = emptySet()), state, TrayMenuActions())))
        val layouts = labels(TrayMenu.build(TrayMenuConfig(listOf(TraySection.Layouts), setOf(TraySection.Layouts)), TrayMenuState(layouts = listOf("a" to "Work", "b" to "Home")), TrayMenuActions()))
        assertEquals(listOf("Window layouts>Work,Home", "-", "Quit DyskLink"), layouts)
        val two = labels(TrayMenu.build(TrayMenuConfig(listOf(TraySection.SendFile), setOf(TraySection.SendFile)), TrayMenuState(listOf(pixel, tab.copy(isConnected = true))), TrayMenuActions()))
        assertEquals("Send file to>Pixel 8…,Galaxy Tab…", two.first())
    }

    @Test
    fun theMenuItemsDoTheirThing() {
        var restored: String? = null
        var opened = false
        val actions = TrayMenuActions(open = { opened = true }, restoreLayout = { restored = it })
        val menu = TrayMenu.build(TrayMenuConfig(listOf(TraySection.Open, TraySection.Layouts), setOf(TraySection.Open, TraySection.Layouts)), TrayMenuState(layouts = listOf("w" to "Work")), actions)
        (menu.first { it is TrayEntry.Item && it.label == "Open DyskLink" } as TrayEntry.Item).onClick()
        (menu.first { it is TrayEntry.Item && it.label == "Restore Work" } as TrayEntry.Item).onClick()
        assertTrue(opened)
        assertEquals("w", restored)
    }

    @Test
    fun theChoiceIsKeptAndNewSectionsAreAddedAtTheEnd() {
        val dir = Files.createTempDirectory("tray").toFile()
        val settings = Settings(File(dir, "settings.properties"))
        assertEquals(TrayMenuConfig(), TrayMenuConfig.load(settings))
        val config = TrayMenuConfig().move(TraySection.Devices, -5).setShown(TraySection.Water, false).setShown(TraySection.Launcher, true)
        config.save(settings)
        assertEquals(config, TrayMenuConfig.load(Settings(File(dir, "settings.properties"))))
        // An older version knew fewer sections.
        settings.set("trayMenu.order", "Devices,Open,Bogus")
        settings.set("trayMenu.shown", "Devices,Open")
        val old = TrayMenuConfig.load(Settings(File(dir, "settings.properties")))
        assertEquals(listOf(TraySection.Devices, TraySection.Open), old.order.take(2))
        assertEquals(TraySection.entries.size, old.order.size)
        assertEquals(setOf(TraySection.Devices, TraySection.Open), old.shown)
        dir.deleteRecursively()
    }
}
