@file:Suppress("FunctionName")

package id.dysklink.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.Window

/**
 * What the island needs from the window system that AWT doesn't offer: kept out of the taskbar and Alt+Tab, clicks
 * passing through outside its shape, and whether a full-screen app has the screen. Every call is best effort: when a
 * library or the X server isn't there, the island still shows, only without that refinement.
 */
interface IslandNative {
    /** Before the window first shows: no taskbar button, no Alt+Tab, above other windows, on every workspace. */
    fun prepare(window: Window) {}
    /** Clicks outside these window-pixel rectangles go to what's under the island. */
    fun setInputRegion(window: Window, rects: List<Rect>) {}
    /** Whether clicking the island may take the keyboard (for Esc and typing a task); off, it never steals focus. */
    fun setActivates(window: Window, activates: Boolean) {}
    /** A full-screen app has the screen the island is on. */
    fun fullscreen(window: Window?, islandMonitor: Rect): Boolean = false
    /** X11 without a compositing manager draws transparent pixels black. Null where it doesn't apply. */
    fun hasCompositor(): Boolean? = null
    /** The desktop asks for less motion (Windows animation effects off, GNOME's or KDE's animations off). */
    fun reducedMotion(): Boolean = false

    companion object {
        fun forThisPc(): IslandNative = when {
            Platform.isWindows -> runCatching { WindowsIslandNative() }.getOrElse { object : IslandNative {} }
            Platform.isLinux -> runCatching { X11IslandNative() }.getOrElse { object : IslandNative {} }
            else -> object : IslandNative {}
        }

        fun windowId(window: Window): Long? = runCatching { Native.getWindowID(window) }.getOrNull()?.takeIf { it != 0L }
    }
}

private class WindowsIslandNative : IslandNative {
    @Suppress("unused")
    interface User32 : Library {
        fun GetWindowLongPtrW(hwnd: Pointer, index: Int): Long
        fun SetWindowLongPtrW(hwnd: Pointer, index: Int, value: Long): Long
        fun SetWindowPos(hwnd: Pointer, after: Pointer?, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun SetWindowRgn(hwnd: Pointer, region: Pointer?, redraw: Boolean): Int
        fun GetForegroundWindow(): Pointer?
        fun GetWindowRect(hwnd: Pointer, rect: IntArray): Boolean
        fun GetClassNameW(hwnd: Pointer, name: CharArray, max: Int): Int
        fun MonitorFromWindow(hwnd: Pointer, flags: Int): Pointer?
        fun GetMonitorInfoW(monitor: Pointer, info: IntArray): Boolean
        fun SystemParametersInfoW(action: Int, param: Int, value: IntArray, winIni: Int): Boolean
    }

    interface Gdi32 : Library {
        fun CreateRectRgn(left: Int, top: Int, right: Int, bottom: Int): Pointer?
        fun CombineRgn(dest: Pointer, a: Pointer, b: Pointer, mode: Int): Int
        fun DeleteObject(handle: Pointer): Boolean
    }

    interface Shell32 : Library {
        fun SHQueryUserNotificationState(state: IntArray): Int
    }

    private val user32 = Native.load("user32", User32::class.java)
    private val gdi32 = runCatching { Native.load("gdi32", Gdi32::class.java) }.getOrNull()
    private val shell32 = runCatching { Native.load("shell32", Shell32::class.java) }.getOrNull()

    private fun hwnd(window: Window): Pointer? = IslandNative.windowId(window)?.let(::Pointer)

    override fun prepare(window: Window) {
        val h = hwnd(window) ?: return
        val style = user32.GetWindowLongPtrW(h, GWL_EXSTYLE)
        user32.SetWindowLongPtrW(h, GWL_EXSTYLE, (style or WS_EX_TOOLWINDOW or WS_EX_NOACTIVATE) and WS_EX_APPWINDOW.inv())
        user32.SetWindowPos(h, Pointer(-1), 0, 0, 0, 0, SWP_NOMOVE or SWP_NOSIZE or SWP_NOACTIVATE or SWP_FRAMECHANGED)
    }

    override fun setActivates(window: Window, activates: Boolean) {
        val h = hwnd(window) ?: return
        val style = user32.GetWindowLongPtrW(h, GWL_EXSTYLE)
        val next = if (activates) style and WS_EX_NOACTIVATE.inv() else style or WS_EX_NOACTIVATE
        if (next != style) user32.SetWindowLongPtrW(h, GWL_EXSTYLE, next)
    }

    override fun setInputRegion(window: Window, rects: List<Rect>) {
        val h = hwnd(window) ?: return
        val g = gdi32 ?: return
        if (rects.isEmpty()) { user32.SetWindowRgn(h, null, true); return }
        val region = g.CreateRectRgn(rects[0].x, rects[0].y, rects[0].right, rects[0].bottom) ?: return
        for (r in rects.drop(1)) {
            val part = g.CreateRectRgn(r.x, r.y, r.right, r.bottom) ?: continue
            g.CombineRgn(region, region, part, RGN_OR)
            g.DeleteObject(part)
        }
        // The window owns the region from here and frees it.
        if (user32.SetWindowRgn(h, region, true) == 0) g.DeleteObject(region)
    }

    override fun fullscreen(window: Window?, islandMonitor: Rect): Boolean {
        val state = shell32?.let { s -> IntArray(1).takeIf { s.SHQueryUserNotificationState(it) == 0 }?.get(0) }
        val fg = user32.GetForegroundWindow()
        val own = window?.let(::hwnd)
        if (fg == null || fg == own) return FullscreenRule.windows(state, null, null, null, null)
        val rect = IntArray(4).takeIf { user32.GetWindowRect(fg, it) }?.let { Rect(it[0], it[1], it[2] - it[0], it[3] - it[1]) }
        val name = CharArray(256).let { buffer -> String(buffer, 0, user32.GetClassNameW(fg, buffer, buffer.size).coerceAtLeast(0)) }
        val fgMonitor = monitorRect(user32.MonitorFromWindow(fg, MONITOR_DEFAULTTONEAREST))
        val ownMonitor = own?.let { monitorRect(user32.MonitorFromWindow(it, MONITOR_DEFAULTTONEAREST)) }
        return FullscreenRule.windows(state, rect, name, fgMonitor, ownMonitor)
    }

    private fun monitorRect(monitor: Pointer?): Rect? {
        monitor ?: return null
        // MONITORINFO: cbSize, rcMonitor (4), rcWork (4), dwFlags.
        val info = IntArray(10).also { it[0] = 40 }
        if (!user32.GetMonitorInfoW(monitor, info)) return null
        return Rect(info[1], info[2], info[3] - info[1], info[4] - info[2])
    }

    override fun reducedMotion(): Boolean {
        val value = IntArray(1)
        return user32.SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, value, 0) && value[0] == 0
    }

    private companion object {
        const val GWL_EXSTYLE = -20
        const val WS_EX_TOOLWINDOW = 0x80L
        const val WS_EX_APPWINDOW = 0x40000L
        const val WS_EX_NOACTIVATE = 0x08000000L
        const val SWP_NOSIZE = 0x1
        const val SWP_NOMOVE = 0x2
        const val SWP_NOACTIVATE = 0x10
        const val SWP_FRAMECHANGED = 0x20
        const val RGN_OR = 2
        const val MONITOR_DEFAULTTONEAREST = 2
        const val SPI_GETCLIENTAREAANIMATION = 0x1042
    }
}

private class X11IslandNative : IslandNative {
    @Suppress("unused")
    interface X11 : Library {
        fun XOpenDisplay(name: String?): Pointer?
        fun XDefaultRootWindow(display: Pointer): NativeLong
        fun XInternAtom(display: Pointer, name: String, onlyIfExists: Int): NativeLong
        fun XChangeProperty(display: Pointer, window: NativeLong, property: NativeLong, type: NativeLong, format: Int, mode: Int, data: Pointer, count: Int): Int
        fun XGetWindowProperty(
            display: Pointer, window: NativeLong, property: NativeLong, offset: NativeLong, length: NativeLong, delete: Int, type: NativeLong,
            actualType: NativeLongByReference, actualFormat: IntByReference, items: NativeLongByReference, after: NativeLongByReference, data: PointerByReference,
        ): Int
        fun XGetSelectionOwner(display: Pointer, selection: NativeLong): NativeLong
        fun XGetGeometry(
            display: Pointer, drawable: NativeLong, root: NativeLongByReference, x: IntByReference, y: IntByReference,
            width: IntByReference, height: IntByReference, border: IntByReference, depth: IntByReference,
        ): Int
        fun XTranslateCoordinates(display: Pointer, from: NativeLong, to: NativeLong, x: Int, y: Int, outX: IntByReference, outY: IntByReference, child: NativeLongByReference): Int
        fun XFree(data: Pointer): Int
        fun XFlush(display: Pointer): Int
    }

    interface Xext : Library {
        fun XShapeCombineRectangles(display: Pointer, window: NativeLong, kind: Int, x: Int, y: Int, rects: Pointer?, count: Int, op: Int, ordering: Int)
    }

    private val x = runCatching { Native.load("X11", X11::class.java) }.recoverCatching { Native.load("libX11.so.6", X11::class.java) }.getOrThrow()
    private val xext = runCatching { Native.load("Xext", Xext::class.java) }.recoverCatching { Native.load("libXext.so.6", Xext::class.java) }.getOrNull()
    private val display = x.XOpenDisplay(null) ?: error("No X display")
    private val root = x.XDefaultRootWindow(display)
    private val atoms = HashMap<String, NativeLong>()

    private fun atom(name: String) = synchronized(atoms) { atoms.getOrPut(name) { x.XInternAtom(display, name, 0) } }

    private fun setAtoms(window: NativeLong, property: String, values: List<String>) {
        val data = Memory(NativeLong.SIZE.toLong() * values.size)
        values.forEachIndexed { i, v -> data.setNativeLong(i.toLong() * NativeLong.SIZE, atom(v)) }
        x.XChangeProperty(display, window, atom(property), atom("ATOM"), 32, PROP_MODE_REPLACE, data, values.size)
    }

    private fun longs(window: NativeLong, name: String): LongArray? {
        val type = NativeLongByReference(); val format = IntByReference(); val items = NativeLongByReference(); val after = NativeLongByReference()
        val data = PointerByReference()
        val status = x.XGetWindowProperty(display, window, atom(name), NativeLong(0), NativeLong(1024), 0, NativeLong(0), type, format, items, after, data)
        val p = data.value ?: return null
        return try { if (status != 0 || format.value != 32) null else LongArray(items.value.toInt()) { p.getNativeLong(it.toLong() * NativeLong.SIZE).toLong() } } finally { x.XFree(p) }
    }

    override fun prepare(window: Window) = synchronized(this) {
        val id = IslandNative.windowId(window)?.let(::NativeLong) ?: return
        // A dock: placed where it asks, kept above windows and out of the taskbar, the pager, and Alt+Tab, and not
        // pushed below a top panel the way a normal window would be.
        setAtoms(id, "_NET_WM_WINDOW_TYPE", listOf("_NET_WM_WINDOW_TYPE_DOCK"))
        setAtoms(id, "_NET_WM_STATE", listOf("_NET_WM_STATE_ABOVE", "_NET_WM_STATE_SKIP_TASKBAR", "_NET_WM_STATE_SKIP_PAGER", "_NET_WM_STATE_STICKY"))
        // On every workspace.
        val all = Memory(NativeLong.SIZE.toLong()).apply { setNativeLong(0, NativeLong(0xFFFFFFFFL)) }
        x.XChangeProperty(display, id, atom("_NET_WM_DESKTOP"), atom("CARDINAL"), 32, PROP_MODE_REPLACE, all, 1)
        x.XFlush(display)
        Unit
    }

    override fun setInputRegion(window: Window, rects: List<Rect>) = synchronized(this) {
        val ext = xext ?: return
        val id = IslandNative.windowId(window)?.let(::NativeLong) ?: return
        val data = Memory(8L * rects.size.coerceAtLeast(1))
        rects.forEachIndexed { i, r ->
            val o = i * 8L
            data.setShort(o, r.x.toShort()); data.setShort(o + 2, r.y.toShort())
            data.setShort(o + 4, r.width.toShort()); data.setShort(o + 6, r.height.toShort())
        }
        ext.XShapeCombineRectangles(display, id, SHAPE_INPUT, 0, 0, data, rects.size, SHAPE_SET, UNSORTED)
        // Without a compositor transparent pixels are black: the drawn shape is cut out too.
        if (hasCompositor() == false) ext.XShapeCombineRectangles(display, id, SHAPE_BOUNDING, 0, 0, data, rects.size, SHAPE_SET, UNSORTED)
        x.XFlush(display)
        Unit
    }

    override fun fullscreen(window: Window?, islandMonitor: Rect): Boolean = synchronized(this) {
        val active = longs(root, "_NET_ACTIVE_WINDOW")?.firstOrNull()?.takeIf { it != 0L } ?: return false
        val w = NativeLong(active)
        val full = atom("_NET_WM_STATE_FULLSCREEN").toLong()
        val states = longs(w, "_NET_WM_STATE")?.map { if (it == full) "_NET_WM_STATE_FULLSCREEN" else it.toString() }
        FullscreenRule.x11(states, bounds(w), islandMonitor)
    }

    private fun bounds(w: NativeLong): Rect? {
        val r = NativeLongByReference(); val gx = IntByReference(); val gy = IntByReference(); val gw = IntByReference(); val gh = IntByReference()
        if (x.XGetGeometry(display, w, r, gx, gy, gw, gh, IntByReference(), IntByReference()) == 0) return null
        val ax = IntByReference(); val ay = IntByReference()
        if (x.XTranslateCoordinates(display, w, root, 0, 0, ax, ay, NativeLongByReference()) == 0) return null
        return Rect(ax.value, ay.value, gw.value, gh.value)
    }

    override fun hasCompositor(): Boolean = synchronized(this) { x.XGetSelectionOwner(display, atom("_NET_WM_CM_S0")).toLong() != 0L }

    override fun reducedMotion(): Boolean = LinuxMotion.reduced()

    private companion object {
        const val PROP_MODE_REPLACE = 0
        const val SHAPE_BOUNDING = 0
        const val SHAPE_INPUT = 2
        const val SHAPE_SET = 0
        const val UNSORTED = 0
    }
}

/** GNOME's "enable-animations" and KDE's animation speed, read without any library. */
object LinuxMotion {
    fun reduced(run: (List<String>) -> String? = ::output, kdeGlobals: String? = readKde()): Boolean {
        if (run(listOf("gsettings", "get", "org.gnome.desktop.interface", "enable-animations"))?.trim() == "false") return true
        return kdeGlobals?.let(::kdeReduced) ?: false
    }

    /** kdeglobals' AnimationDurationFactor=0 turns animations off. */
    fun kdeReduced(text: String): Boolean = text.lines().any { it.trim().replace(" ", "") == "AnimationDurationFactor=0" }

    private fun readKde(): String? = runCatching {
        java.io.File(System.getenv("XDG_CONFIG_HOME") ?: (System.getProperty("user.home") + "/.config"), "kdeglobals").readText()
    }.getOrNull()

    private fun output(command: List<String>): String? = runCatching {
        val p = ProcessBuilder(command).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        if (p.waitFor() == 0) text else null
    }.getOrNull()
}
