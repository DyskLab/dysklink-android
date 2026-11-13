@file:Suppress("FunctionName")

package id.dysklink.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.GraphicsEnvironment

/**
 * Windows: the window in front and the monitors through user32, in physical pixels (each call asks for per-monitor
 * DPI awareness on its thread). What's seen of a window is DWM's frame, without the invisible resize borders.
 */
class WindowsWindowBackend : WindowBackend {
    private interface User32 : Library {
        fun GetForegroundWindow(): Pointer?
        fun GetWindowRect(window: Pointer, rect: Pointer): Boolean
        fun MonitorFromWindow(window: Pointer, flags: Int): Pointer?
        fun GetMonitorInfoW(monitor: Pointer, info: Pointer): Boolean
        fun EnumDisplayMonitors(dc: Pointer?, clip: Pointer?, callback: MonitorProc, data: Pointer?): Boolean
        fun EnumWindows(callback: WindowProc, data: Pointer?): Boolean
        fun IsWindowVisible(window: Pointer): Boolean
        fun IsIconic(window: Pointer): Boolean
        fun IsZoomed(window: Pointer): Boolean
        fun ShowWindow(window: Pointer, command: Int): Boolean
        fun GetWindowTextW(window: Pointer, text: CharArray, max: Int): Int
        fun GetClassNameW(window: Pointer, text: CharArray, max: Int): Int
        fun GetWindow(window: Pointer, command: Int): Pointer?
        fun GetWindowLongW(window: Pointer, index: Int): Int
        fun GetWindowThreadProcessId(window: Pointer, pid: IntByReference): Int
        fun SetWindowPos(window: Pointer, after: Pointer?, x: Int, y: Int, width: Int, height: Int, flags: Int): Boolean
        fun SetThreadDpiAwarenessContext(context: Pointer): Pointer?
    }

    private interface Dwm : Library {
        fun DwmGetWindowAttribute(window: Pointer, attribute: Int, value: Pointer, size: Int): Int
    }

    private interface Shcore : Library {
        fun GetDpiForMonitor(monitor: Pointer, type: Int, x: IntByReference, y: IntByReference): Int
    }

    private interface Kernel32 : Library {
        fun OpenProcess(access: Int, inherit: Boolean, pid: Int): Pointer?
        fun QueryFullProcessImageNameW(process: Pointer, flags: Int, name: CharArray, size: IntByReference): Boolean
        fun CloseHandle(handle: Pointer): Boolean
        fun GetCurrentProcessId(): Int
    }

    fun interface MonitorProc : Callback { fun invoke(monitor: Pointer?, dc: Pointer?, rect: Pointer?, data: Pointer?): Boolean }
    fun interface WindowProc : Callback { fun invoke(window: Pointer?, data: Pointer?): Boolean }

    private val user32 = runCatching { Native.load("user32", User32::class.java) }.getOrNull()
    private val dwm = runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull()
    private val shcore = runCatching { Native.load("shcore", Shcore::class.java) }.getOrNull()
    private val kernel32 = runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull()

    override val problem: String? = if (user32 == null) "user32 couldn't be loaded." else null

    /** Runs [block] in per-monitor DPI awareness, so sizes are the screen's own pixels on every monitor. */
    private fun <T> aware(block: (User32) -> T): T? {
        val u = user32 ?: return null
        val old = runCatching { u.SetThreadDpiAwarenessContext(Pointer(-4)) }.getOrNull()
        try { return block(u) } finally { if (old != null) runCatching { u.SetThreadDpiAwarenessContext(old) } }
    }

    private fun rect(memory: Pointer, offset: Long = 0): Rect {
        val l = memory.getInt(offset); val t = memory.getInt(offset + 4); val r = memory.getInt(offset + 8); val b = memory.getInt(offset + 12)
        return Rect(l, t, r - l, b - t)
    }

    private fun outer(u: User32, window: Pointer): Rect? = Memory(16).let { if (u.GetWindowRect(window, it)) rect(it) else null }

    /** What's seen: DWM's extended frame bounds, or the window rectangle without DWM. */
    private fun visible(u: User32, window: Pointer): Rect? {
        val frame = Memory(16)
        val d = dwm
        if (d != null && runCatching { d.DwmGetWindowAttribute(window, DWMWA_EXTENDED_FRAME_BOUNDS, frame, 16) }.getOrDefault(-1) == 0) return rect(frame)
        return outer(u, window)
    }

    private fun monitorInfo(u: User32, monitor: Pointer): Monitor? {
        val info = Memory(104).apply { clear(); setInt(0, 104) }
        if (!u.GetMonitorInfoW(monitor, info)) return null
        val x = IntByReference(); val y = IntByReference()
        val sh = shcore
        val dpi = if (sh != null && runCatching { sh.GetDpiForMonitor(monitor, 0, x, y) }.getOrDefault(-1) == 0) x.value else 96
        return Monitor(rect(info, 4), rect(info, 20), dpi / 96.0)
    }

    override fun monitors(): List<Monitor> = aware { u ->
        val found = mutableListOf<Monitor>()
        val proc = MonitorProc { monitor, _, _, _ -> monitor?.let { monitorInfo(u, it) }?.let { found += it }; true }
        u.EnumDisplayMonitors(null, null, proc, null)
        found.toList()
    }.orEmpty()

    private fun describe(u: User32, window: Pointer): OpenWindow? {
        val rect = visible(u, window) ?: return null
        val title = CharArray(512).let { buf -> String(buf, 0, u.GetWindowTextW(window, buf, buf.size).coerceAtLeast(0)) }
        return OpenWindow(Pointer.nativeValue(window), exe(u, window), title, rect)
    }

    override fun active(): OpenWindow? = aware { u ->
        val window = u.GetForegroundWindow() ?: return@aware null
        if (className(u, window) in SHELL) null else describe(u, window)
    }

    private fun className(u: User32, window: Pointer) = CharArray(256).let { buf -> String(buf, 0, u.GetClassNameW(window, buf, buf.size).coerceAtLeast(0)) }

    private fun exe(u: User32, window: Pointer): String {
        val k = kernel32 ?: return ""
        val pid = IntByReference()
        u.GetWindowThreadProcessId(window, pid)
        val process = k.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid.value) ?: return ""
        return try {
            val buf = CharArray(1024)
            val size = IntByReference(buf.size)
            if (k.QueryFullProcessImageNameW(process, 0, buf, size)) String(buf, 0, size.value).substringAfterLast('\\').lowercase() else ""
        } finally { k.CloseHandle(process) }
    }

    private fun cloaked(window: Pointer): Boolean {
        val d = dwm ?: return false
        val value = Memory(4).apply { setInt(0, 0) }
        return runCatching { d.DwmGetWindowAttribute(window, DWMWA_CLOAKED, value, 4) == 0 && value.getInt(0) != 0 }.getOrDefault(false)
    }

    /** As Alt+Tab lists them: visible, not minimized, with a title, not a tool window, not hidden by DWM. */
    override fun windows(): List<OpenWindow> = aware { u ->
        val found = mutableListOf<OpenWindow>()
        val proc = WindowProc { window, _ ->
            if (window != null && u.IsWindowVisible(window) && !u.IsIconic(window) && u.GetWindow(window, GW_OWNER) == null &&
                (u.GetWindowLongW(window, GWL_EXSTYLE) and WS_EX_TOOLWINDOW) == 0 && !cloaked(window) && className(u, window) !in SHELL
            ) {
                describe(u, window)?.takeIf { it.title.isNotBlank() && it.rect.width > 0 }?.let { found += it }
            }
            true
        }
        u.EnumWindows(proc, null)
        found.toList()
    }.orEmpty()

    override fun move(window: OpenWindow, target: Rect) {
        aware { u ->
            val hwnd = Pointer(window.handle)
            if (u.IsZoomed(hwnd) || u.IsIconic(hwnd)) u.ShowWindow(hwnd, SW_RESTORE)
            // Twice when it lands on a monitor with another scale: the window resizes itself on arriving, and the
            // invisible borders are bigger or smaller there.
            repeat(2) { attempt ->
                val insets = WindowTidy.insets(outer(u, hwnd) ?: return@aware, visible(u, hwnd) ?: return@aware)
                val goal = WindowTidy.outerFor(target, insets)
                if (attempt == 1 && outer(u, hwnd) == goal) return@aware
                u.SetWindowPos(hwnd, null, goal.x, goal.y, goal.width, goal.height, SWP_NOZORDER or SWP_NOACTIVATE)
            }
        }
    }

    private companion object {
        const val DWMWA_EXTENDED_FRAME_BOUNDS = 9
        const val DWMWA_CLOAKED = 14
        const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
        const val GW_OWNER = 4
        const val GWL_EXSTYLE = -20
        const val WS_EX_TOOLWINDOW = 0x80
        const val SW_RESTORE = 9
        const val SWP_NOZORDER = 0x4
        const val SWP_NOACTIVATE = 0x10
        /** The desktop and the taskbar, never moved. */
        val SHELL = setOf("Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd")
    }
}

/**
 * X11: EWMH through Xlib, as pagers and tiling tools do. The window in front is _NET_ACTIVE_WINDOW, windows are
 * _NET_CLIENT_LIST_STACKING, and moves go to the window manager as _NET_MOVERESIZE_WINDOW. What's seen of a window
 * is its frame (_NET_FRAME_EXTENTS) or, for GTK windows that draw their own, without the shadow (_GTK_FRAME_EXTENTS).
 */
class X11WindowBackend : WindowBackend {
    private interface X11 : Library {
        fun XOpenDisplay(name: String?): Pointer?
        fun XDefaultRootWindow(display: Pointer): NativeLong
        fun XInternAtom(display: Pointer, name: String, onlyIfExists: Int): NativeLong
        fun XGetWindowProperty(
            display: Pointer, window: NativeLong, property: NativeLong, offset: NativeLong, length: NativeLong, delete: Int, type: NativeLong,
            actualType: NativeLongByReference, actualFormat: IntByReference, items: NativeLongByReference, after: NativeLongByReference, data: PointerByReference,
        ): Int
        fun XFree(data: Pointer): Int
        fun XSendEvent(display: Pointer, window: NativeLong, propagate: Int, mask: NativeLong, event: Pointer): Int
        fun XFlush(display: Pointer): Int
        fun XGetGeometry(
            display: Pointer, drawable: NativeLong, root: NativeLongByReference, x: IntByReference, y: IntByReference,
            width: IntByReference, height: IntByReference, border: IntByReference, depth: IntByReference,
        ): Int
        fun XTranslateCoordinates(display: Pointer, from: NativeLong, to: NativeLong, x: Int, y: Int, outX: IntByReference, outY: IntByReference, child: NativeLongByReference): Int
    }

    private val x = runCatching { Native.load("X11", X11::class.java) }.recoverCatching { Native.load("libX11.so.6", X11::class.java) }.getOrNull()
    private val display: Pointer? = x?.let { runCatching { it.XOpenDisplay(null) }.getOrNull() }
    private val root: NativeLong = display?.let { x!!.XDefaultRootWindow(it) } ?: NativeLong(0)
    private val atoms = mutableMapOf<String, NativeLong>()

    private fun atom(name: String): NativeLong = synchronized(atoms) { atoms.getOrPut(name) { x!!.XInternAtom(display!!, name, 0) } }

    override val problem: String? = when {
        x == null || display == null -> "DyskLink can't reach the X server."
        else -> synchronized(this) {
            val supported = (longs(root, "_NET_SUPPORTED") ?: LongArray(0)).toSet()
            if (atom("_NET_MOVERESIZE_WINDOW").toLong() !in supported || atom("_NET_ACTIVE_WINDOW").toLong() !in supported)
                "The window manager doesn't let apps move windows (it lacks _NET_MOVERESIZE_WINDOW)." else null
        }
    }

    /** A format-32 property: X hands those over as C longs. */
    private fun longs(window: NativeLong, name: String): LongArray? = property(window, name) { data, items, format ->
        if (format != 32) null else LongArray(items) { data.getNativeLong(it.toLong() * NativeLong.SIZE).toLong() }
    }

    private fun text(window: NativeLong, name: String): String? = property(window, name) { data, items, format ->
        if (format != 8) null else String(data.getByteArray(0, items), Charsets.UTF_8)
    }

    private fun <T> property(window: NativeLong, name: String, read: (Pointer, Int, Int) -> T?): T? {
        val xl = x ?: return null
        val d = display ?: return null
        val type = NativeLongByReference(); val format = IntByReference(); val items = NativeLongByReference(); val after = NativeLongByReference()
        val data = PointerByReference()
        val status = xl.XGetWindowProperty(d, window, atom(name), NativeLong(0), NativeLong(4096), 0, NativeLong(0), type, format, items, after, data)
        val pointer = data.value
        if (status != 0 || pointer == null) return null
        return try { if (items.value.toInt() == 0) null else read(pointer, items.value.toInt(), format.value) } finally { xl.XFree(pointer) }
    }

    private fun currentDesktop(): Long? = longs(root, "_NET_CURRENT_DESKTOP")?.firstOrNull()

    override fun monitors(): List<Monitor> = synchronized(this) {
        val screens = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
                val gc = device.defaultConfiguration
                val b = gc.bounds
                WindowTidy.fromAwt(Rect(b.x, b.y, b.width, b.height), gc.defaultTransform.scaleX)
            }
        }.getOrNull().orEmpty()
        val desktop = currentDesktop() ?: 0
        val net = longs(root, "_NET_WORKAREA")?.toList()?.chunked(4)?.getOrNull(desktop.toInt())?.takeIf { it.size == 4 }?.let { Rect(it[0].toInt(), it[1].toInt(), it[2].toInt(), it[3].toInt()) }
        val gtk = longs(root, "_GTK_WORKAREAS_D$desktop")?.toList()?.chunked(4)?.filter { it.size == 4 }?.map { Rect(it[0].toInt(), it[1].toInt(), it[2].toInt(), it[3].toInt()) }.orEmpty()
        WindowTidy.x11WorkAreas(screens, net, gtk)
    }

    /** The client window's place on the root window. */
    private fun client(window: NativeLong): Rect? {
        val xl = x ?: return null
        val d = display ?: return null
        val r = NativeLongByReference(); val gx = IntByReference(); val gy = IntByReference(); val w = IntByReference(); val h = IntByReference()
        if (xl.XGetGeometry(d, window, r, gx, gy, w, h, IntByReference(), IntByReference()) == 0) return null
        val ax = IntByReference(); val ay = IntByReference()
        if (xl.XTranslateCoordinates(d, window, root, 0, 0, ax, ay, NativeLongByReference()) == 0) return null
        return Rect(ax.value, ay.value, w.value, h.value)
    }

    /** From the client window to what's seen: plus the frame the window manager draws, minus a GTK shadow. */
    private fun frame(window: NativeLong): Insets {
        longs(window, "_GTK_FRAME_EXTENTS")?.takeIf { it.size == 4 }?.let { return Insets(-it[0].toInt(), -it[2].toInt(), -it[1].toInt(), -it[3].toInt()) }
        longs(window, "_NET_FRAME_EXTENTS")?.takeIf { it.size == 4 }?.let { return Insets(it[0].toInt(), it[2].toInt(), it[1].toInt(), it[3].toInt()) }
        return Insets.NONE
    }

    private fun describe(window: NativeLong): OpenWindow? {
        val c = client(window) ?: return null
        val seen = WindowTidy.outerFor(c, frame(window))
        val title = text(window, "_NET_WM_NAME") ?: text(window, "WM_NAME") ?: ""
        val app = text(window, "WM_CLASS")?.split('\u0000')?.filter { it.isNotEmpty() }?.lastOrNull() ?: ""
        return OpenWindow(window.toLong(), app, title, seen)
    }

    override fun active(): OpenWindow? = synchronized(this) {
        val id = longs(root, "_NET_ACTIVE_WINDOW")?.firstOrNull()?.takeIf { it != 0L } ?: return null
        describe(NativeLong(id))
    }

    override fun windows(): List<OpenWindow> = synchronized(this) {
        val list = (longs(root, "_NET_CLIENT_LIST_STACKING") ?: longs(root, "_NET_CLIENT_LIST") ?: return emptyList()).reversed()
        val desktop = currentDesktop()
        val normal = atom("_NET_WM_WINDOW_TYPE_NORMAL").toLong()
        val skip = setOf(atom("_NET_WM_STATE_HIDDEN").toLong(), atom("_NET_WM_STATE_SKIP_TASKBAR").toLong())
        list.mapNotNull { id ->
            val w = NativeLong(id)
            val types = longs(w, "_NET_WM_WINDOW_TYPE")
            if (types != null && normal !in types) return@mapNotNull null
            if ((longs(w, "_NET_WM_STATE") ?: LongArray(0)).any { it in skip }) return@mapNotNull null
            val on = longs(w, "_NET_WM_DESKTOP")?.firstOrNull()
            if (desktop != null && on != null && on != desktop && on != 0xFFFFFFFFL) return@mapNotNull null
            describe(w)
        }
    }

    override fun move(window: OpenWindow, target: Rect) = synchronized(this) {
        val w = NativeLong(window.handle)
        // A maximized window wouldn't move; it's un-maximized first.
        send(w, "_NET_WM_STATE", 0, atom("_NET_WM_STATE_MAXIMIZED_VERT").toLong(), atom("_NET_WM_STATE_MAXIMIZED_HORZ").toLong(), 2)
        val insets = frame(w)
        val c = Rect(target.x + insets.left, target.y + insets.top, target.width - insets.left - insets.right, target.height - insets.top - insets.bottom)
        val data = X11Moves.moveResize(c)
        send(w, "_NET_MOVERESIZE_WINDOW", data[0], data[1], data[2], data[3], data[4])
        display?.let { x?.XFlush(it) }
        Unit
    }

    /** A client message to the root window, as EWMH asks for requests to the window manager. */
    private fun send(window: NativeLong, type: String, vararg data: Long) {
        val xl = x ?: return
        val d = display ?: return
        val event = Memory(24L * NativeLong.SIZE).apply { clear() }
        val o = X11Moves.clientMessageOffsets(NativeLong.SIZE)
        event.setInt(0, CLIENT_MESSAGE)
        event.setInt(o.sendEvent, 1)
        event.setPointer(o.display, d)
        event.setNativeLong(o.window, window)
        event.setNativeLong(o.messageType, atom(type))
        event.setInt(o.format, 32)
        data.forEachIndexed { i, v -> event.setNativeLong(o.data + i.toLong() * NativeLong.SIZE, NativeLong(v)) }
        xl.XSendEvent(d, root, 0, NativeLong(SUBSTRUCTURE_REDIRECT or SUBSTRUCTURE_NOTIFY), event)
    }

    private companion object {
        const val CLIENT_MESSAGE = 33
        const val SUBSTRUCTURE_NOTIFY = 1L shl 19
        const val SUBSTRUCTURE_REDIRECT = 1L shl 20
    }
}

object X11Moves {
    data class Offsets(val sendEvent: Long, val display: Long, val window: Long, val messageType: Long, val format: Long, val data: Long)

    /** Where XClientMessageEvent's fields are, for C longs of [longSize] bytes (8 on 64-bit Linux). */
    fun clientMessageOffsets(longSize: Int): Offsets =
        if (longSize == 8) Offsets(16, 24, 32, 40, 48, 56) else Offsets(8, 12, 16, 20, 24, 28)

    /** _NET_MOVERESIZE_WINDOW's data: static gravity (x, y are the client's own), all four set, from a pager-like tool. */
    fun moveResize(client: Rect): LongArray =
        longArrayOf(10L or (0xFL shl 8) or (2L shl 12), client.x.toLong(), client.y.toLong(), client.width.coerceAtLeast(1).toLong(), client.height.coerceAtLeast(1).toLong())
}
