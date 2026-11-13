@file:Suppress("FunctionName", "unused")

package id.dysklink.desktop

import id.phonelink.android.core.CoreLog
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.errors.UnknownProperty
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

// The tray icon as Linux desktops want it today: an org.kde.StatusNotifierItem with a com.canonical.dbusmenu menu,
// over the session bus. KDE Plasma, GNOME with the AppIndicator extension (Ubuntu has it on), XFCE, Cinnamon,
// LXQt and most bars on Wayland show these, while the old XEmbed tray that AWT uses is gone from most of them.

// ---- D-Bus interfaces. Names and signatures follow the specs, so the members keep their D-Bus spelling. ----

@DBusInterfaceName("org.kde.StatusNotifierItem")
interface StatusNotifierItem : DBusInterface {
    fun ContextMenu(x: Int, y: Int)
    fun Activate(x: Int, y: Int)
    fun SecondaryActivate(x: Int, y: Int)
    fun Scroll(delta: Int, orientation: String)

    class NewToolTip(path: String) : DBusSignal(path)
    class NewIcon(path: String) : DBusSignal(path)
}

@DBusInterfaceName("org.kde.StatusNotifierWatcher")
interface StatusNotifierWatcher : DBusInterface {
    fun RegisterStatusNotifierItem(service: String)
}

@DBusInterfaceName("com.canonical.dbusmenu")
interface DBusMenu : DBusInterface {
    fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): Reply2<UInt32, MenuLayout>
    fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemProperties>
    fun GetProperty(id: Int, name: String): Variant<*>
    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)
    fun EventGroup(events: List<MenuEvent>): List<Int>
    fun AboutToShow(id: Int): Boolean
    fun AboutToShowGroup(ids: List<Int>): Reply2<List<Int>, List<Int>>

    class LayoutUpdated(path: String, val revision: UInt32, val parent: Int) : DBusSignal(path, revision, parent)
}

@DBusInterfaceName("org.freedesktop.Notifications")
interface DesktopNotifications : DBusInterface {
    fun Notify(
        appName: String, replacesId: UInt32, appIcon: String, summary: String, body: String,
        actions: List<String>, hints: Map<String, Variant<*>>, expireTimeout: Int,
    ): UInt32
}

/** (ia{sv}av): one menu item with its children, each child a variant holding the same struct. */
class MenuLayout(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val properties: Map<String, Variant<*>>,
    @field:Position(2) @JvmField val children: List<Variant<*>>,
) : Struct()

/**
 * A reply of two values. It has to be generic: dbus-java reads a reply's D-Bus types from the return type's
 * type arguments.
 */
class Reply2<A, B>(
    @field:Position(0) @JvmField val first: A,
    @field:Position(1) @JvmField val second: B,
) : Tuple()

class MenuItemProperties(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val properties: Map<String, Variant<*>>,
) : Struct()

class MenuEvent(
    @field:Position(0) @JvmField val id: Int,
    @field:Position(1) @JvmField val eventId: String,
    @field:Position(2) @JvmField val data: Variant<*>,
    @field:Position(3) @JvmField val timestamp: UInt32,
) : Struct()

/** (iiay): width, height, then ARGB32 pixels in network byte order. */
class IconPixmap(
    @field:Position(0) @JvmField val width: Int,
    @field:Position(1) @JvmField val height: Int,
    @field:Position(2) @JvmField val data: ByteArray,
) : Struct()

/** (sa(iiay)ss): icon name, icon pixmaps, title, text. */
class ToolTipStruct(
    @field:Position(0) @JvmField val iconName: String,
    @field:Position(1) @JvmField val pixmaps: List<IconPixmap>,
    @field:Position(2) @JvmField val title: String,
    @field:Position(3) @JvmField val text: String,
) : Struct()

// ---- The menu as dbusmenu items, kept apart from the bus so it can be tested. ----

class MenuNode(val id: Int, val properties: Map<String, Any>, val children: List<MenuNode>)

class MenuTree(val root: MenuNode, val nodes: Map<Int, MenuNode>, val actions: Map<Int, () -> Unit>)

object DbusMenuLayout {
    const val LAYOUT_SIG = "(ia{sv}av)"

    /** Ids count up from 1 in menu order, so the same menu always gets the same ids. The root is 0. */
    fun build(entries: List<TrayEntry>): MenuTree {
        val nodes = HashMap<Int, MenuNode>()
        val actions = HashMap<Int, () -> Unit>()
        var next = 1
        fun walk(list: List<TrayEntry>): List<MenuNode> = list.map { entry ->
            val id = next++
            val node = when (entry) {
                is TrayEntry.Item -> {
                    if (entry.enabled) actions[id] = entry.onClick
                    MenuNode(id, mapOf("label" to escape(entry.label), "enabled" to entry.enabled, "visible" to true), emptyList())
                }
                is TrayEntry.Submenu -> MenuNode(
                    id, mapOf("label" to escape(entry.label), "enabled" to true, "visible" to true, "children-display" to "submenu"), walk(entry.children),
                )
                TrayEntry.Separator -> MenuNode(id, mapOf("type" to "separator", "visible" to true), emptyList())
            }
            nodes[id] = node
            node
        }
        val root = MenuNode(0, mapOf("children-display" to "submenu"), walk(entries))
        nodes[0] = root
        return MenuTree(root, nodes, actions)
    }

    /** dbusmenu reads a single underscore as a mnemonic mark. */
    fun escape(label: String) = label.replace("_", "__")

    fun variant(value: Any): Variant<*> = when (value) {
        is String -> Variant(value)
        is Boolean -> Variant(value)
        is Int -> Variant(value)
        else -> error("No D-Bus type for $value")
    }

    fun properties(node: MenuNode, names: List<String>): Map<String, Variant<*>> =
        node.properties.filterKeys { names.isEmpty() || it in names }.mapValues { variant(it.value) }

    /** [depth] -1 means every level, 0 just the item itself. */
    fun toDbus(node: MenuNode, depth: Int, names: List<String>): MenuLayout {
        val children = if (depth == 0) emptyList() else node.children.map { Variant(toDbus(it, if (depth < 0) -1 else depth - 1, names), LAYOUT_SIG) }
        return MenuLayout(node.id, properties(node, names), children)
    }
}

// ---- The icon. ----

object IconPixmaps {
    val SIZES = listOf(16, 22, 24, 32, 48, 64)

    /** Halves the picture until close, then draws the last step, which looks much better than one big jump. */
    fun scaled(source: BufferedImage, size: Int): BufferedImage {
        var current = source
        while (current.width / 2 >= size && current.height / 2 >= size) current = draw(current, current.width / 2, current.height / 2)
        return if (current.width == size && current.height == size) toArgb(current) else draw(current, size, size)
    }

    private fun draw(source: BufferedImage, width: Int, height: Int): BufferedImage {
        val out = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.drawImage(source, 0, 0, width, height, null)
        g.dispose()
        return out
    }

    private fun toArgb(source: BufferedImage): BufferedImage =
        if (source.type == BufferedImage.TYPE_INT_ARGB) source else draw(source, source.width, source.height)

    /** Each pixel as A, R, G, B bytes, as StatusNotifierItem wants it whatever the CPU's byte order. */
    fun argb32(image: BufferedImage): ByteArray {
        val out = ByteArray(image.width * image.height * 4)
        var i = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val p = image.getRGB(x, y)
            out[i++] = (p ushr 24).toByte()
            out[i++] = (p ushr 16).toByte()
            out[i++] = (p ushr 8).toByte()
            out[i++] = p.toByte()
        }
        return out
    }

    fun pixmaps(source: BufferedImage, sizes: List<Int> = SIZES): List<IconPixmap> =
        sizes.map { size -> scaled(source, size).let { IconPixmap(it.width, it.height, argb32(it)) } }
}

// ---- The tray itself. ----

class LinuxTray private constructor(
    private val conn: DBusConnection,
    private val busName: String,
    private val worker: ExecutorService,
    private val icons: List<IconPixmap>,
    private val iconFile: File?,
    private val onActivate: () -> Unit,
    private val onUi: (() -> Unit) -> Unit,
) : TrayHost {
    override val kind = TrayKind.StatusNotifier

    private val lock = Any()
    private var tree = DbusMenuLayout.build(emptyList())
    private var shape = ""
    private var revision = 1
    private var tooltip = ""
    @Volatile private var closed = false

    private val item = object : StatusNotifierItem, Properties {
        override fun getObjectPath() = ITEM_PATH
        override fun ContextMenu(x: Int, y: Int) {}
        override fun Activate(x: Int, y: Int) = onUi(onActivate)
        override fun SecondaryActivate(x: Int, y: Int) = onUi(onActivate)
        override fun Scroll(delta: Int, orientation: String) {}

        private fun all(): Map<String, Variant<*>> {
            val tip = synchronized(lock) { tooltip }
            val none = emptyList<IconPixmap>()
            return linkedMapOf(
                "Category" to Variant("ApplicationStatus"),
                "Id" to Variant("dysklink"),
                "Title" to Variant("DyskLink"),
                "Status" to Variant("Active"),
                "WindowId" to Variant(0),
                "IconName" to Variant(""),
                "IconThemePath" to Variant(""),
                "IconPixmap" to Variant(icons, PIXMAPS_SIG),
                "OverlayIconName" to Variant(""),
                "OverlayIconPixmap" to Variant(none, PIXMAPS_SIG),
                "AttentionIconName" to Variant(""),
                "AttentionIconPixmap" to Variant(none, PIXMAPS_SIG),
                "AttentionMovieName" to Variant(""),
                "ToolTip" to Variant(ToolTipStruct("", none, "DyskLink", tip), "(sa(iiay)ss)"),
                "ItemIsMenu" to Variant(false),
                "Menu" to Variant(DBusPath(MENU_PATH)),
            )
        }

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
            (all()[propertyName] ?: throw UnknownProperty("No property $propertyName")) as A

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) =
            throw DBusExecutionException("$propertyName can't be changed")

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = if (interfaceName == SNI) all() else emptyMap()
    }

    private val menu = object : DBusMenu, Properties {
        override fun getObjectPath() = MENU_PATH

        override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): Reply2<UInt32, MenuLayout> {
            val (current, rev) = synchronized(lock) { tree to revision }
            val node = current.nodes[parentId] ?: throw DBusExecutionException("No menu item $parentId")
            return Reply2(UInt32(rev.toLong()), DbusMenuLayout.toDbus(node, recursionDepth, propertyNames))
        }

        override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemProperties> {
            val current = synchronized(lock) { tree }
            return ids.ifEmpty { current.nodes.keys.sorted() }.mapNotNull { id ->
                current.nodes[id]?.let { MenuItemProperties(id, DbusMenuLayout.properties(it, propertyNames)) }
            }
        }

        override fun GetProperty(id: Int, name: String): Variant<*> {
            val value = synchronized(lock) { tree }.nodes[id]?.properties?.get(name) ?: throw UnknownProperty("No $name on $id")
            return DbusMenuLayout.variant(value)
        }

        override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            if (eventId != "clicked") return
            synchronized(lock) { tree }.actions[id]?.let(onUi)
        }

        override fun EventGroup(events: List<MenuEvent>): List<Int> {
            val current = synchronized(lock) { tree }
            events.forEach { Event(it.id, it.eventId, it.data, it.timestamp) }
            return events.map { it.id }.filter { it !in current.nodes }
        }

        override fun AboutToShow(id: Int) = false
        override fun AboutToShowGroup(ids: List<Int>) = Reply2<List<Int>, List<Int>>(emptyList(), emptyList())

        private fun all(): Map<String, Variant<*>> = linkedMapOf(
            "Version" to Variant(UInt32(3)),
            "TextDirection" to Variant("ltr"),
            "Status" to Variant("normal"),
            "IconThemePath" to Variant(emptyList<String>(), "as"),
        )

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
            (all()[propertyName] ?: throw UnknownProperty("No property $propertyName")) as A

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) =
            throw DBusExecutionException("$propertyName can't be changed")

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = if (interfaceName == MENU) all() else emptyMap()
    }

    override fun update(menu: List<TrayEntry>, tooltip: String) {
        if (closed) return
        val newShape = menu.shape()
        val text = tooltip.removePrefix("DyskLink: ").takeIf { it != "DyskLink" }.orEmpty()
        val (layoutRevision, tipChanged) = synchronized(lock) {
            // Rebuilt every time so clicks run the newest handlers; the ids stay the same for the same menu.
            tree = DbusMenuLayout.build(menu)
            val layoutChanged = newShape != shape
            if (layoutChanged) { shape = newShape; revision++ }
            val tipChanged = text != this.tooltip
            this.tooltip = text
            (if (layoutChanged) revision else null) to tipChanged
        }
        if (layoutRevision != null) send { DBusMenu.LayoutUpdated(MENU_PATH, UInt32(layoutRevision.toLong()), 0) }
        if (tipChanged) send { StatusNotifierItem.NewToolTip(ITEM_PATH) }
    }

    override fun notify(title: String, message: String): Boolean {
        if (closed) return false
        worker.execute {
            runCatching {
                conn.getRemoteObject(NOTIFICATIONS, "/org/freedesktop/Notifications", DesktopNotifications::class.java)
                    .Notify("DyskLink", UInt32(0), iconFile?.path.orEmpty(), title, message, emptyList(), emptyMap(), -1)
            }.onFailure { CoreLog.w(TAG, "Can't show a notification", it) }
        }
        return true
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            worker.submit {
                runCatching { conn.releaseBusName(busName) }
                runCatching { conn.close() }
            }.get(2, TimeUnit.SECONDS)
        }
        worker.shutdownNow()
    }

    private fun send(signal: () -> DBusSignal) {
        if (closed) return
        runCatching { worker.execute { runCatching { conn.sendMessage(signal()) }.onFailure { CoreLog.w(TAG, "Can't update the tray", it) } } }
    }

    private fun register() {
        conn.getRemoteObject(WATCHER, WATCHER_PATH, StatusNotifierWatcher::class.java).RegisterStatusNotifierItem(busName)
    }

    companion object {
        private const val TAG = "LinuxTray"
        const val WATCHER = "org.kde.StatusNotifierWatcher"
        const val WATCHER_PATH = "/StatusNotifierWatcher"
        const val SNI = "org.kde.StatusNotifierItem"
        const val MENU = "com.canonical.dbusmenu"
        const val NOTIFICATIONS = "org.freedesktop.Notifications"
        const val ITEM_PATH = "/StatusNotifierItem"
        const val MENU_PATH = "/MenuBar"
        private const val PIXMAPS_SIG = "a(iiay)"

        /**
         * The session bus as a unix socket path. The JDK's unix sockets can't reach abstract sockets, so for those
         * the systemd user bus in XDG_RUNTIME_DIR is tried, which nearly every desktop today has.
         */
        fun sessionAddress(env: (String) -> String? = System::getenv, exists: (String) -> Boolean = { File(it).exists() }): String? {
            env("DBUS_SESSION_BUS_ADDRESS")?.split(';')?.map { it.trim() }
                ?.firstOrNull { it.startsWith("unix:") && it.substringAfter("unix:").split(',').any { part -> part.startsWith("path=") } }
                ?.let { return it }
            val runtime = env("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() } ?: return null
            return "$runtime/bus".takeIf(exists)?.let { "unix:path=$it" }
        }

        /**
         * Connects and registers, or null when there is no bus or nothing shows StatusNotifierItems. All of it runs
         * on the tray's own thread; a bus that doesn't answer in time counts as none.
         */
        fun start(
            address: String?,
            onActivate: () -> Unit,
            onUi: (() -> Unit) -> Unit = { SwingUtilities.invokeLater(it) },
            iconSource: () -> BufferedImage? = { DesktopApp::class.java.getResourceAsStream("/icon.png")?.use(ImageIO::read) },
            pid: Long = ProcessHandle.current().pid(),
            timeoutMillis: Long = 3000,
        ): LinuxTray? {
            address ?: return null
            val worker = Executors.newSingleThreadExecutor { Thread(it, "dysklink-tray").apply { isDaemon = true } }
            val future = worker.submit<LinuxTray?> { connect(address, worker, onActivate, onUi, iconSource, pid) }
            return try {
                future.get(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                CoreLog.w(TAG, "No StatusNotifierItem tray", e)
                future.cancel(true)
                worker.shutdownNow()
                null
            }.also { if (it == null) worker.shutdown() }
        }

        private fun connect(
            address: String, worker: ExecutorService, onActivate: () -> Unit, onUi: (() -> Unit) -> Unit,
            iconSource: () -> BufferedImage?, pid: Long,
        ): LinuxTray? {
            val conn = DBusConnectionBuilder.forAddress(address).withShared(false).build()
            try {
                val dbus = conn.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
                if (!dbus.NameHasOwner(WATCHER) || !hostRegistered(conn)) {
                    conn.close()
                    return null
                }
                val source = runCatching(iconSource).getOrNull()
                val icons = source?.let { IconPixmaps.pixmaps(it) }.orEmpty()
                val iconFile = source?.let { image ->
                    runCatching {
                        File.createTempFile("dysklink-", ".png").apply { deleteOnExit(); ImageIO.write(IconPixmaps.scaled(image, 64), "png", this) }
                    }.getOrNull()
                }
                val busName = "$SNI-$pid-1"
                conn.requestBusName(busName)
                val tray = LinuxTray(conn, busName, worker, icons, iconFile, onActivate, onUi)
                conn.exportObject(ITEM_PATH, tray.item)
                conn.exportObject(MENU_PATH, tray.menu)
                // When the panel restarts, its watcher comes back empty, so the icon registers again.
                conn.addSigHandler(DBus.NameOwnerChanged::class.java) { signal ->
                    if (signal.name == WATCHER && signal.newOwner.isNotEmpty() && !tray.closed) {
                        worker.execute { runCatching { tray.register() }.onFailure { CoreLog.w(TAG, "Can't register the tray again", it) } }
                    }
                }
                tray.register()
                return tray
            } catch (e: Exception) {
                runCatching { conn.close() }
                throw e
            }
        }

        /** A watcher with no host (GNOME with the extension turned off can leave one) shows nothing. */
        private fun hostRegistered(conn: DBusConnection): Boolean = runCatching {
            val value: Any? = conn.getRemoteObject(WATCHER, WATCHER_PATH, Properties::class.java).Get<Any?>(WATCHER, "IsStatusNotifierHostRegistered")
            ((value as? Variant<*>)?.value ?: value) as? Boolean ?: true
        }.getOrDefault(true)
    }
}
