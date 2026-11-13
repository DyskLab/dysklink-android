package id.dysklink.desktop

import java.awt.image.BufferedImage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.freedesktop.dbus.bin.EmbeddedDBusDaemon
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.connections.transports.TransportBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

class LinuxTrayTest {
    private val clicks = mutableListOf<String>()

    private fun sampleMenu(pomodoroRunning: Boolean = false, phones: Int = 2) = buildList {
        add(TrayEntry.Item("Open DyskLink") { clicks += "open" })
        add(TrayEntry.Separator)
        add(TrayEntry.Item(if (pomodoroRunning) "Pause focus" else "Start focus") { clicks += "pomodoro" })
        add(TrayEntry.Item("Pixel_8: 76%, charging") { clicks += "pixel" })
        add(TrayEntry.Item("Send file…", enabled = false) { clicks += "never" })
        if (phones > 1) add(TrayEntry.Submenu("Send file to", listOf(TrayEntry.Item("Pixel 8…") { clicks += "send-pixel" }, TrayEntry.Item("Tab…") { clicks += "send-tab" })))
        add(TrayEntry.Separator)
        add(TrayEntry.Item("Quit DyskLink") { clicks += "quit" })
    }

    // ---- dbusmenu layout ----

    @Test
    fun layoutGivesIdsInOrderLabelsEnabledSeparatorsAndChildren() {
        val tree = DbusMenuLayout.build(sampleMenu())
        val root = tree.root
        assertEquals(0, root.id)
        assertEquals("submenu", root.properties["children-display"])
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 9, 10), root.children.map { it.id })
        assertEquals("Open DyskLink", root.children[0].properties["label"])
        assertEquals("separator", root.children[1].properties["type"])
        assertNull(root.children[1].properties["label"])
        // Underscores are doubled, or dbusmenu would read them as mnemonics.
        assertEquals("Pixel__8: 76%, charging", root.children[3].properties["label"])
        assertEquals(false, root.children[4].properties["enabled"])
        assertEquals(true, root.children[0].properties["enabled"])
        val submenu = root.children[5]
        assertEquals("submenu", submenu.properties["children-display"])
        assertEquals(listOf(7, 8), submenu.children.map { it.id })
        assertEquals("Tab…", submenu.children[1].properties["label"])
        assertEquals(setOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10), tree.nodes.keys)
        // Disabled items and separators have no action.
        assertEquals(setOf(1, 3, 4, 7, 8, 10), tree.actions.keys)
        tree.actions.getValue(8)()
        tree.actions.getValue(10)()
        assertEquals(listOf("send-tab", "quit"), clicks)
    }

    @Test
    fun layoutToDbusHonoursDepthAndPropertyNames() {
        val tree = DbusMenuLayout.build(sampleMenu())
        val full = DbusMenuLayout.toDbus(tree.root, -1, emptyList())
        assertEquals(8, full.children.size)
        val sub = full.children[5].value as org.freedesktop.dbus.Struct
        assertEquals(DbusMenuLayout.LAYOUT_SIG, full.children[5].sig)
        assertEquals(2, (sub as MenuLayout).children.size)
        assertEquals("Send file to", sub.properties.getValue("label").value)

        val shallow = DbusMenuLayout.toDbus(tree.root, 1, emptyList())
        assertEquals(0, (shallow.children[5].value as MenuLayout).children.size)
        assertEquals(0, DbusMenuLayout.toDbus(tree.root, 0, emptyList()).children.size)

        val labelsOnly = DbusMenuLayout.toDbus(tree.nodes.getValue(5), 0, listOf("label"))
        assertEquals(setOf("label"), labelsOnly.properties.keys)
        assertEquals("Send file…", labelsOnly.properties.getValue("label").value)
    }

    @Test
    fun menuShapeIgnoresHandlersButNotLabelsOrEnabled() {
        assertEquals(sampleMenu().shape(), sampleMenu().shape())
        assertTrue(sampleMenu().shape() != sampleMenu(pomodoroRunning = true).shape())
        assertTrue(sampleMenu().shape() != sampleMenu(phones = 1).shape())
        val enabled = listOf(TrayEntry.Item("Send clipboard", enabled = true))
        val disabled = listOf(TrayEntry.Item("Send clipboard", enabled = false))
        assertTrue(enabled.shape() != disabled.shape())
    }

    // ---- icon ----

    @Test
    fun pixmapsAreArgbInNetworkOrderAtEverySize() {
        val image = BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0x80112233.toInt())
        image.setRGB(1, 0, 0xFFAABBCC.toInt())
        assertContentEquals(
            byteArrayOf(0x80.toByte(), 0x11, 0x22, 0x33, 0xFF.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()),
            IconPixmaps.argb32(image),
        )

        val source = BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB).apply {
            for (y in 0 until 256) for (x in 0 until 256) setRGB(x, y, 0xFF2080F0.toInt())
        }
        val pixmaps = IconPixmaps.pixmaps(source)
        assertEquals(listOf(16, 22, 24, 32, 48, 64), pixmaps.map { it.width })
        assertEquals(listOf(16, 22, 24, 32, 48, 64), pixmaps.map { it.height })
        pixmaps.forEach { assertEquals(it.width * it.height * 4, it.data.size) }
        val middle = pixmaps[0].data.copyOfRange((8 * 16 + 8) * 4, (8 * 16 + 8) * 4 + 4)
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0x20, 0x80.toByte(), 0xF0.toByte()), middle)
    }

    @Test
    fun theRealIconScales() {
        val icon = javax.imageio.ImageIO.read(DesktopApp::class.java.getResourceAsStream("/icon.png"))
        val pixmaps = IconPixmaps.pixmaps(icon)
        assertEquals(6, pixmaps.size)
        // Not see-through all over, so something shows in the panel.
        assertTrue(pixmaps.all { p -> (p.data.indices step 4).any { p.data[it] != 0.toByte() } })
    }

    // ---- choosing the tray ----

    private val fakeSni = object : TrayHost { override val kind = TrayKind.StatusNotifier }

    @Test
    fun watcherPresentPicksStatusNotifier() {
        assertSame(fakeSni, TrayHosts.choose(isLinux = true, statusNotifier = { fakeSni }, awtSupported = { true }))
    }

    @Test
    fun noWatcherFallsBackToAwt() {
        assertSame(AwtTrayHost, TrayHosts.choose(isLinux = true, statusNotifier = { null }, awtSupported = { true }))
        assertSame(AwtTrayHost, TrayHosts.choose(isLinux = true, statusNotifier = { error("no bus") }, awtSupported = { true }))
    }

    @Test
    fun neitherMeansNoTray() {
        assertNull(TrayHosts.choose(isLinux = true, statusNotifier = { null }, awtSupported = { false }))
        assertNull(TrayHosts.choose(isLinux = true, statusNotifier = { null }, awtSupported = { throw UnsupportedOperationException() }))
    }

    @Test
    fun windowsNeverTriesDbus() {
        var asked = false
        assertSame(AwtTrayHost, TrayHosts.choose(isLinux = false, statusNotifier = { asked = true; fakeSni }, awtSupported = { true }))
        assertFalse(asked)
    }

    @Test
    fun sessionAddressPrefersAPathAndSkipsAbstractSockets() {
        val env = mapOf("DBUS_SESSION_BUS_ADDRESS" to "unix:path=/run/user/1000/bus,guid=abc", "XDG_RUNTIME_DIR" to "/run/user/1000")
        assertEquals("unix:path=/run/user/1000/bus,guid=abc", LinuxTray.sessionAddress(env::get) { true })
        val abstract = mapOf("DBUS_SESSION_BUS_ADDRESS" to "unix:abstract=/tmp/dbus-x,guid=1", "XDG_RUNTIME_DIR" to "/run/user/1000")
        assertEquals("unix:path=/run/user/1000/bus", LinuxTray.sessionAddress(abstract::get) { it == "/run/user/1000/bus" })
        assertNull(LinuxTray.sessionAddress(abstract::get) { false })
        assertNull(LinuxTray.sessionAddress({ null }) { true })
        val list = mapOf("DBUS_SESSION_BUS_ADDRESS" to "unix:abstract=/tmp/a;unix:path=/tmp/b")
        assertEquals("unix:path=/tmp/b", LinuxTray.sessionAddress(list::get) { false })
    }

    // ---- the Settings hint ----

    @Test
    fun hintOnlyOnLinuxWithoutTray() {
        assertNull(TrayHints.pick(isLinux = true, hasTray = true, desktop = "GNOME"))
        assertNull(TrayHints.pick(isLinux = false, hasTray = false, desktop = "GNOME"))
    }

    @Test
    fun gnomeGetsTheExtension() {
        listOf("GNOME", "ubuntu:GNOME", "pop:GNOME", "GNOME-Classic:GNOME", "gnome").forEach { desktop ->
            val hint = assertNotNull(TrayHints.pick(true, false, desktop), desktop)
            assertEquals(TrayHints.EXTENSION_URL, hint.link)
            assertTrue(hint.text.contains("AppIndicator extension"))
            assertEquals("Until then, closing the window quits DyskLink.", hint.note)
        }
    }

    @Test
    fun otherDesktopsAreToldClosingQuits() {
        listOf("KDE", "XFCE", "sway", null, "").forEach { desktop ->
            val hint = assertNotNull(TrayHints.pick(true, false, desktop))
            assertEquals("Your desktop has no tray area; closing the window quits DyskLink.", hint.text)
            assertNull(hint.link)
        }
    }

    // ---- against a real bus: dbus-java's own daemon on a unix socket, with a fake panel ----

    private var daemon: EmbeddedDBusDaemon? = null
    private val connections = mutableListOf<DBusConnection>()

    @AfterTest
    fun stopBus() {
        connections.forEach { runCatching { it.close() } }
        runCatching { daemon?.close() }
    }

    private fun startBus(): String? {
        val listen = runCatching { TransportBuilder.createDynamicSession("UNIX", true) }.getOrNull() ?: return null
        val started = runCatching { EmbeddedDBusDaemon(listen).apply { startInBackgroundAndWait(5000) } }.getOrNull() ?: return null
        daemon = started
        return listen.replace(",listen=true", "")
    }

    private fun connect(address: String) = DBusConnectionBuilder.forAddress(address).withShared(false).build().also { connections += it }

    private class FakeWatcher(val registered: LinkedBlockingQueue<String>) : StatusNotifierWatcher, Properties {
        override fun getObjectPath() = LinuxTray.WATCHER_PATH
        override fun RegisterStatusNotifierItem(service: String) { registered.add(service) }
        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A = Variant(true) as A
        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {}
        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = mapOf("IsStatusNotifierHostRegistered" to Variant(true))
    }

    private fun startWatcher(address: String, registered: LinkedBlockingQueue<String>): DBusConnection = connect(address).apply {
        requestBusName(LinuxTray.WATCHER)
        exportObject(LinuxTray.WATCHER_PATH, FakeWatcher(registered))
    }

    @Test
    fun noWatcherOnTheBusMeansNoStatusNotifier() {
        val address = startBus() ?: return
        assertNull(LinuxTray.start(address, onActivate = {}, onUi = { it() }))
    }

    @Test
    fun registersServesTheMenuAndComesBackAfterThePanelRestarts() {
        val address = startBus() ?: return
        val registered = LinkedBlockingQueue<String>()
        val watcher = startWatcher(address, registered)
        val activations = AtomicInteger()
        val tray = assertNotNull(LinuxTray.start(address, onActivate = { activations.incrementAndGet() }, onUi = { it() }, pid = 4242))
        try {
            val busName = "org.kde.StatusNotifierItem-4242-1"
            assertEquals(busName, registered.poll(5, TimeUnit.SECONDS))

            val panel = connect(address)
            val layoutUpdates = LinkedBlockingQueue<Long>()
            panel.addSigHandler(DBusMenu.LayoutUpdated::class.java) { layoutUpdates.add(it.revision.toLong()) }
            val tooltips = LinkedBlockingQueue<String>()
            panel.addSigHandler(StatusNotifierItem.NewToolTip::class.java) { tooltips.add(it.path) }

            val props = panel.getRemoteObject(busName, LinuxTray.ITEM_PATH, Properties::class.java)
            assertEquals("dysklink", props.Get<Any>(LinuxTray.SNI, "Id").unwrap())
            assertEquals("DyskLink", props.Get<Any>(LinuxTray.SNI, "Title").unwrap())
            assertEquals("ApplicationStatus", props.Get<Any>(LinuxTray.SNI, "Category").unwrap())
            assertEquals("Active", props.Get<Any>(LinuxTray.SNI, "Status").unwrap())
            assertEquals(false, props.Get<Any>(LinuxTray.SNI, "ItemIsMenu").unwrap())
            assertEquals(LinuxTray.MENU_PATH, props.Get<Any>(LinuxTray.SNI, "Menu").unwrap().toString())
            val all = props.GetAll(LinuxTray.SNI)
            assertTrue(all.keys.containsAll(listOf("IconPixmap", "ToolTip", "Menu", "Status")))
            assertEquals(6, (all.getValue("IconPixmap").value as List<*>).size)

            tray.update(sampleMenu(), "DyskLink: Pixel 8 connected")
            val revision = assertNotNull(layoutUpdates.poll(5, TimeUnit.SECONDS))
            assertNotNull(tooltips.poll(5, TimeUnit.SECONDS))

            val menu = panel.getRemoteObject(busName, LinuxTray.MENU_PATH, DBusMenu::class.java)
            // What the panel's side reads from introspection matches the dbusmenu spec.
            val xml = panel.getRemoteObject(busName, LinuxTray.MENU_PATH, org.freedesktop.dbus.interfaces.Introspectable::class.java).Introspect()
            val getLayout = xml.substringAfter("<method name=\"GetLayout\"").substringBefore("</method>")
            assertTrue(getLayout.contains("type=\"u\" direction=\"out\"") && getLayout.contains("type=\"(ia{sv}av)\" direction=\"out\""), getLayout)
            assertTrue(xml.contains("<signal name=\"LayoutUpdated\""), xml)
            val reply = menu.GetLayout(0, -1, emptyList())
            assertEquals(revision, reply.first.toLong())
            assertEquals(0, reply.second.id)
            assertEquals(8, reply.second.children.size)
            val group = menu.GetGroupProperties(listOf(1, 5), listOf("label", "enabled"))
            assertEquals(listOf(1, 5), group.map { it.id })
            assertEquals("Open DyskLink", group[0].properties.getValue("label").value)
            assertEquals(false, group[1].properties.getValue("enabled").value)
            assertEquals("Quit DyskLink", menu.GetProperty(10, "label").value)
            assertFalse(menu.AboutToShow(0))
            assertEquals(emptyList(), menu.AboutToShowGroup(listOf(0)).first)

            // GLib, which the GNOME extension and many panels use, reads the same layout and properties.
            gdbus(address, busName, LinuxTray.MENU_PATH, "com.canonical.dbusmenu.GetLayout", "0", "-1", "[]")?.let { out ->
                assertTrue(out.contains("'Open DyskLink'") && out.contains("'separator'") && out.contains("'Send file to'"), out)
            }
            gdbus(address, busName, LinuxTray.ITEM_PATH, "org.freedesktop.DBus.Properties.GetAll", LinuxTray.SNI)?.let { out ->
                assertTrue(out.contains("'IconPixmap'") && out.contains("'ToolTip': <('', @a(iiay) [], 'DyskLink', 'Pixel 8 connected')>"), out)
                assertTrue(out.contains("'Menu': <objectpath '/MenuBar'>"), out)
            }

            // A click runs that item's handler; a disabled item does nothing.
            menu.Event(3, "clicked", Variant(""), UInt32(0))
            menu.Event(5, "clicked", Variant(""), UInt32(0))
            menu.Event(1, "hovered", Variant(""), UInt32(0))
            assertEquals(listOf("pomodoro"), clicks)
            panel.getRemoteObject(busName, LinuxTray.ITEM_PATH, StatusNotifierItem::class.java).Activate(0, 0)
            assertEquals(1, activations.get())

            // The same menu again sends nothing; a changed one a new revision.
            tray.update(sampleMenu(), "DyskLink: Pixel 8 connected")
            tray.update(sampleMenu(pomodoroRunning = true), "DyskLink: Pixel 8 connected")
            assertEquals(revision + 1, layoutUpdates.poll(5, TimeUnit.SECONDS))
            assertNull(layoutUpdates.poll(300, TimeUnit.MILLISECONDS))
            assertEquals("Pause focus", menu.GetProperty(3, "label").value)

            // The panel restarts: its watcher goes and comes back, and the icon registers again.
            watcher.releaseBusName(LinuxTray.WATCHER)
            watcher.close()
            startWatcher(address, registered)
            assertEquals(busName, registered.poll(5, TimeUnit.SECONDS))

            tray.close()
            val dbus = panel.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
            assertFalse(dbus.NameHasOwner(busName))
        } finally {
            tray.close()
        }
    }

    /** Calls through GLib's gdbus when it's installed, or null. */
    private fun gdbus(address: String, dest: String, path: String, method: String, vararg args: String): String? {
        val process = runCatching {
            ProcessBuilder(listOf("gdbus", "call", "--address", address, "--dest", dest, "--object-path", path, "--method", method, "--") + args)
                .redirectErrorStream(true).start()
        }.getOrNull() ?: return null
        val out = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0, out)
        return out
    }

    private fun Any?.unwrap(): Any? = if (this is Variant<*>) value else this
}
