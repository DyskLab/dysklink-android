package id.dysklink.desktop

import id.phonelink.android.core.CoreLog
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// Window tidy, as the Mac's Window Layout tool: the window in front to a half, third, quarter, the whole screen or
// the middle with a shortcut, and whole layouts saved and restored for each set of monitors. All sizes are in the
// screen's own pixels: on Windows the thread asks for per-monitor DPI awareness, on X11 they're pixels anyway.

@Serializable
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2
    val area: Long get() = width.toLong() * height

    fun contains(px: Int, py: Int) = px in x until right && py in y until bottom

    fun intersect(other: Rect): Rect? {
        val l = maxOf(x, other.x); val t = maxOf(y, other.y)
        val r = minOf(right, other.right); val b = minOf(bottom, other.bottom)
        return if (r > l && b > t) Rect(l, t, r - l, b - t) else null
    }

    override fun toString() = "${x},${y} ${width}x${height}"
}

/** One screen: all of it, the part without the taskbar or panels, and its scale (1.5 for 150 %). */
data class Monitor(val bounds: Rect, val work: Rect, val scale: Double = 1.0)

/** How far the window's outer rectangle reaches past what's seen of it: Windows 10 and 11's invisible borders, X11's frame. */
data class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object { val NONE = Insets(0, 0, 0, 0) }
}

enum class WindowSpot(val label: String, val key: String) {
    LeftHalf("Left half", "Left"), RightHalf("Right half", "Right"), TopHalf("Top half", "Up"), BottomHalf("Bottom half", "Down"),
    LeftThird("Left third", "D"), CenterThird("Center third", "F"), RightThird("Right third", "G"),
    LeftTwoThirds("Left two thirds", "E"), RightTwoThirds("Right two thirds", "T"),
    TopLeft("Top left quarter", "U"), TopRight("Top right quarter", "I"), BottomLeft("Bottom left quarter", "J"), BottomRight("Bottom right quarter", "K"),
    Maximize("Fill the screen", "Enter"), Center("Center", "C");

    /** Where the window goes in [area]. Neighbours share their edge exactly, whatever the rounding. */
    fun frame(area: Rect, current: Rect): Rect {
        fun rect(fx: Double, fy: Double, fw: Double, fh: Double): Rect {
            val l = area.x + Math.round(area.width * fx).toInt()
            val r = area.x + Math.round(area.width * (fx + fw)).toInt()
            val t = area.y + Math.round(area.height * fy).toInt()
            val b = area.y + Math.round(area.height * (fy + fh)).toInt()
            return Rect(l, t, r - l, b - t)
        }
        val third = 1.0 / 3
        return when (this) {
            LeftHalf -> rect(0.0, 0.0, 0.5, 1.0)
            RightHalf -> rect(0.5, 0.0, 0.5, 1.0)
            TopHalf -> rect(0.0, 0.0, 1.0, 0.5)
            BottomHalf -> rect(0.0, 0.5, 1.0, 0.5)
            LeftThird -> rect(0.0, 0.0, third, 1.0)
            CenterThird -> rect(third, 0.0, third, 1.0)
            RightThird -> rect(2 * third, 0.0, third, 1.0)
            LeftTwoThirds -> rect(0.0, 0.0, 2 * third, 1.0)
            RightTwoThirds -> rect(third, 0.0, 2 * third, 1.0)
            TopLeft -> rect(0.0, 0.0, 0.5, 0.5)
            TopRight -> rect(0.5, 0.0, 0.5, 0.5)
            BottomLeft -> rect(0.0, 0.5, 0.5, 0.5)
            BottomRight -> rect(0.5, 0.5, 0.5, 0.5)
            Maximize -> area
            Center -> {
                val w = minOf(current.width, area.width)
                val h = minOf(current.height, area.height)
                Rect(area.x + (area.width - w) / 2, area.y + (area.height - h) / 2, w, h)
            }
        }
    }
}

object WindowTidy {
    /** The extras besides the spots: to the next or previous monitor, and every window in a grid. */
    const val NEXT_SCREEN = "nextScreen"
    const val PREVIOUS_SCREEN = "previousScreen"
    const val GRID = "grid"

    /** A command's id from "left-half", "leftHalf", "LeftHalf", "next-screen" or "grid"; null for anything else. */
    fun commandId(text: String): String? {
        val compact = text.replace("-", "").replace("_", "").lowercase()
        WindowSpot.entries.firstOrNull { it.name.lowercase() == compact }?.let { return it.name }
        return listOf(NEXT_SCREEN, PREVIOUS_SCREEN, GRID).firstOrNull { it.lowercase() == compact }
    }

    /** The command line for a spot, as `dysklink --window left-half`. */
    fun commandName(id: String): String = id.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()

    /** The monitor a window is on: the one with its middle, else the one it overlaps most, else the nearest. */
    fun monitorFor(monitors: List<Monitor>, window: Rect): Monitor? {
        if (monitors.isEmpty()) return null
        monitors.firstOrNull { it.bounds.contains(window.centerX, window.centerY) }?.let { return it }
        monitors.maxByOrNull { it.bounds.intersect(window)?.area ?: 0 }?.takeIf { (it.bounds.intersect(window)?.area ?: 0) > 0 }?.let { return it }
        return monitors.minByOrNull { m ->
            val dx = (window.centerX - m.bounds.centerX).toLong(); val dy = (window.centerY - m.bounds.centerY).toLong()
            dx * dx + dy * dy
        }
    }

    /** Monitors in a fixed order, left to right then top to bottom, so "monitor 2" means the same one each time. */
    fun ordered(monitors: List<Monitor>): List<Monitor> = monitors.sortedWith(compareBy({ it.bounds.x }, { it.bounds.y }))

    /** The window outside its border: what to give SetWindowPos for [visible] to be what's seen. */
    fun outerFor(visible: Rect, insets: Insets) =
        Rect(visible.x - insets.left, visible.y - insets.top, visible.width + insets.left + insets.right, visible.height + insets.top + insets.bottom)

    /** The insets from the outer rectangle (GetWindowRect) and what's seen (DWM's extended frame bounds). */
    fun insets(outer: Rect, visible: Rect) =
        Insets(visible.x - outer.x, visible.y - outer.y, outer.right - visible.right, outer.bottom - visible.bottom).takeIf { i ->
            listOf(i.left, i.top, i.right, i.bottom).all { it in 0..64 }
        } ?: Insets.NONE

    /** The window moved to [step] monitors further on, at the same place and share of the screen. */
    fun onNextScreen(monitors: List<Monitor>, window: Rect, step: Int): Rect? {
        val list = ordered(monitors)
        if (list.size < 2) return null
        val from = monitorFor(list, window) ?: return null
        val to = list[((list.indexOf(from) + step) % list.size + list.size) % list.size]
        return scaled(window, from.work, to.work)
    }

    /** [window]'s place in [from] carried over to [to]. */
    fun scaled(window: Rect, from: Rect, to: Rect): Rect {
        val fx = (window.x - from.x).toDouble() / from.width
        val fy = (window.y - from.y).toDouble() / from.height
        val fw = minOf(window.width.toDouble() / from.width, 1.0)
        val fh = minOf(window.height.toDouble() / from.height, 1.0)
        val x = to.x + Math.round(fx.coerceIn(0.0, 1.0 - fw) * to.width).toInt()
        val y = to.y + Math.round(fy.coerceIn(0.0, 1.0 - fh) * to.height).toInt()
        return Rect(x, y, Math.round(fw * to.width).toInt(), Math.round(fh * to.height).toInt())
    }

    /** Rows and columns for [count] windows: as square as can be, the last row spread over the whole width. */
    fun grid(count: Int, area: Rect): List<Rect> {
        if (count <= 0) return emptyList()
        val columns = Math.ceil(Math.sqrt(count.toDouble())).toInt()
        val rows = Math.ceil(count.toDouble() / columns).toInt()
        return (0 until count).map { index ->
            val row = index / columns
            val inRow = if (row == rows - 1) count - row * columns else columns
            val column = index % columns
            val l = area.x + area.width * column / inRow
            val r = area.x + area.width * (column + 1) / inRow
            val t = area.y + area.height * row / rows
            val b = area.y + area.height * (row + 1) / rows
            Rect(l, t, r - l, b - t)
        }
    }

    /** A screen as AWT gives it (in its scaled units on Linux), in the X server's pixels. */
    fun fromAwt(bounds: Rect, scale: Double): Rect =
        Rect(Math.round(bounds.x * scale).toInt(), Math.round(bounds.y * scale).toInt(), Math.round(bounds.width * scale).toInt(), Math.round(bounds.height * scale).toInt())

    /**
     * X11 work areas per monitor: GNOME's _GTK_WORKAREAS_Dn has one per monitor; otherwise _NET_WORKAREA (one rectangle
     * for all screens) cut to each monitor; otherwise the whole monitor.
     */
    fun x11WorkAreas(monitors: List<Rect>, netWorkarea: Rect?, gtkWorkareas: List<Rect>): List<Monitor> = monitors.map { bounds ->
        val gtk = gtkWorkareas.filter { bounds.intersect(it) == it }.maxByOrNull { it.area }
        Monitor(bounds, gtk ?: netWorkarea?.let { bounds.intersect(it) } ?: bounds)
    }
}

// Saved layouts

@Serializable
data class SavedWindow(
    /** The program: chrome.exe on Windows, the WM_CLASS on Linux. */
    val app: String,
    val title: String,
    val rect: Rect,
    /** Which of the ordered monitors it was on, and where in that monitor's work area, for another monitor set. */
    val monitor: Int = 0,
    val fx: Double = 0.0, val fy: Double = 0.0, val fw: Double = 1.0, val fh: Double = 1.0,
)

@Serializable
data class SavedLayout(val id: String, val name: String, val monitors: String, val monitorCount: Int, val windows: List<SavedWindow>)

/** A window of another app, as the system lists it. [handle] is the HWND or X window. */
data class OpenWindow(val handle: Long, val app: String, val title: String, val rect: Rect)

object Layouts {
    /** The monitor set: every monitor's place and size, in order. The same set gives the same key. */
    fun monitorKey(monitors: List<Monitor>): String = WindowTidy.ordered(monitors).joinToString(";") { it.bounds.toString() }

    fun describe(monitors: List<Monitor>): String = when (monitors.size) {
        0 -> "no monitor"
        1 -> "1 monitor, ${monitors[0].bounds.width}x${monitors[0].bounds.height}"
        else -> "${monitors.size} monitors"
    }

    fun capture(name: String, windows: List<OpenWindow>, monitors: List<Monitor>, id: String = UUID.randomUUID().toString()): SavedLayout {
        val list = WindowTidy.ordered(monitors)
        return SavedLayout(id, name, monitorKey(list), list.size, windows.map { w ->
            val m = WindowTidy.monitorFor(list, w.rect)
            val area = m?.work ?: w.rect
            SavedWindow(
                w.app, w.title, w.rect, m?.let { list.indexOf(it) } ?: 0,
                (w.rect.x - area.x).toDouble() / area.width, (w.rect.y - area.y).toDouble() / area.height,
                w.rect.width.toDouble() / area.width, w.rect.height.toDouble() / area.height,
            )
        })
    }

    /**
     * Where each open window goes. A saved window takes the open one of the same app and title, else one of the same
     * app; each open window is used once. On the monitor set it was saved on, windows go back exactly; on another,
     * each goes to the same share of its monitor's work area (or the last monitor, when there are fewer now).
     */
    fun plan(layout: SavedLayout, open: List<OpenWindow>, monitors: List<Monitor>): List<Pair<OpenWindow, Rect>> {
        val list = WindowTidy.ordered(monitors)
        val same = monitorKey(list) == layout.monitors
        val left = open.toMutableList()
        val chosen = arrayOfNulls<OpenWindow>(layout.windows.size)
        layout.windows.forEachIndexed { i, saved ->
            left.firstOrNull { it.app.equals(saved.app, true) && it.title == saved.title }?.let { chosen[i] = it; left.remove(it) }
        }
        layout.windows.forEachIndexed { i, saved ->
            if (chosen[i] == null) left.firstOrNull { it.app.equals(saved.app, true) }?.let { chosen[i] = it; left.remove(it) }
        }
        return layout.windows.mapIndexedNotNull { i, saved ->
            val window = chosen[i] ?: return@mapIndexedNotNull null
            val target = if (same || list.isEmpty()) saved.rect else {
                val area = list[saved.monitor.coerceIn(0, list.size - 1)].work
                // By edges, as the spots are, so neighbours meet; kept inside the work area.
                fun edge(start: Int, size: Int, f: Double) = start + Math.round(f.coerceIn(0.0, 1.0) * size).toInt()
                val l = edge(area.x, area.width, saved.fx); val r = edge(area.x, area.width, saved.fx + saved.fw)
                val t = edge(area.y, area.height, saved.fy); val b = edge(area.y, area.height, saved.fy + saved.fh)
                Rect(l, t, (r - l).coerceAtLeast(1), (b - t).coerceAtLeast(1))
            }
            window to target
        }
    }

    /** The layouts for [key] first, then the rest. */
    fun forMonitors(layouts: List<SavedLayout>, key: String): List<SavedLayout> = layouts.filter { it.monitors == key } + layouts.filter { it.monitors != key }
}

class LayoutStore(private val file: File?) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val _layouts = MutableStateFlow(load())
    val layouts: StateFlow<List<SavedLayout>> = _layouts.asStateFlow()

    private fun load(): List<SavedLayout> = runCatching {
        file?.takeIf { it.isFile }?.readText()?.let { json.decodeFromString(ListSerializer(SavedLayout.serializer()), it) }
    }.getOrNull() ?: emptyList()

    fun add(layout: SavedLayout) = update(_layouts.value + layout)
    fun delete(id: String) = update(_layouts.value.filterNot { it.id == id })
    fun rename(id: String, name: String) = update(_layouts.value.map { if (it.id == id) it.copy(name = name.take(40)) else it })

    private fun update(list: List<SavedLayout>) {
        _layouts.value = list
        runCatching { file?.writeText(json.encodeToString(ListSerializer(SavedLayout.serializer()), list)) }
    }
}

// The system's side

/** Moving other apps' windows on this desktop. */
interface WindowBackend {
    /** Why it can't work here, or null. */
    val problem: String?
    fun monitors(): List<Monitor>
    /** The window in front, with what's seen of it. */
    fun active(): OpenWindow?
    /** Windows on this desktop that aren't minimized, front first. */
    fun windows(): List<OpenWindow>
    /** Puts [window] so that what's seen of it is [target]. */
    fun move(window: OpenWindow, target: Rect)
}

enum class TidyMods(val label: String, val ctrl: Boolean, val alt: Boolean, val shift: Boolean) {
    CtrlAlt("Ctrl+Alt", true, true, false),
    CtrlAltShift("Ctrl+Alt+Shift", true, true, true),
}

data class TidyShortcut(val id: String, val label: String, val combo: HotkeyCombo)

data class WindowTidyView(
    val enabled: Boolean = false,
    /** False on Wayland, where no app may move another's windows. */
    val supported: Boolean = true,
    val mods: TidyMods = TidyMods.CtrlAlt,
    val shortcuts: List<TidyShortcut> = WindowTidyService.shortcuts(TidyMods.CtrlAlt),
    val hotkey: HotkeyStatus = HotkeyStatus(HotkeyBackend.Command),
    val layouts: List<SavedLayout> = emptyList(),
    val monitorKey: String = "",
    val monitors: String = "",
    val message: String? = null,
    val hints: List<HostHint> = emptyList(),
)

sealed interface TidyAction {
    data class SetEnabled(val on: Boolean) : TidyAction
    data class SetMods(val mods: TidyMods) : TidyAction
    data class Move(val id: String) : TidyAction
    data class Save(val name: String) : TidyAction
    data class Restore(val id: String) : TidyAction
    data class Delete(val id: String) : TidyAction
    data object RetryHotkey : TidyAction
    /** The settings are showing: monitors are read again. */
    data object Refresh : TidyAction
}

/**
 * Window tidy on this PC: the shortcuts while it's on, and the saved layouts. [backend] is null on Wayland.
 * [hints] words what the desktop needs from outside DyskLink.
 */
class WindowTidyService(
    private val scope: CoroutineScope,
    private val settings: Settings,
    dataDir: File?,
    private val desktop: Desktop,
    private val backend: WindowBackend?,
    private val hints: (WindowTidyView) -> List<HostHint> = { emptyList() },
) {
    val store = LayoutStore(dataDir?.let { File(it, "window-layouts.json") })
    private var hotkey: GlobalHotkey? = null
    private val _view = MutableStateFlow(
        WindowTidyView(
            enabled = settings.bool("windowTidy", false), supported = backend != null && desktop != Desktop.WAYLAND,
            mods = TidyMods.entries.firstOrNull { it.name == settings.string("windowTidy.mods") } ?: TidyMods.CtrlAlt,
            hotkey = HotkeyStatus(Hotkeys.choose(desktop, false)),
        ),
    )
    val view: StateFlow<WindowTidyView> = _view.asStateFlow()

    fun start() {
        store.layouts.onEach { list -> publish { it.copy(layouts = list) } }.launchIn(scope)
        refreshMonitors()
        apply()
    }

    fun stop() {
        hotkey?.close()
        hotkey = null
    }

    private fun publish(change: (WindowTidyView) -> WindowTidyView) {
        val next = change(_view.value)
        _view.value = next.copy(shortcuts = shortcuts(next.mods), hints = hints(next))
    }

    /** Monitors can come and go, so they're read again when the settings show and before a layout is used. */
    fun refreshMonitors(): List<Monitor> {
        val monitors = runCatching { backend?.monitors() }.getOrNull().orEmpty()
        publish { it.copy(monitorKey = Layouts.monitorKey(monitors), monitors = Layouts.describe(monitors)) }
        return monitors
    }

    private fun apply() {
        val v = _view.value
        hotkey?.close()
        hotkey = null
        if (!v.enabled || !v.supported) { publish { it.copy(hotkey = HotkeyStatus(it.hotkey.backend)) }; return }
        val bindings = shortcuts(v.mods).map { s -> HotkeyBinding(s.combo) { run(s.id) } }
        val key: GlobalHotkey = when (desktop) {
            Desktop.WINDOWS -> WindowsHotkey(bindings)
            Desktop.X11 -> X11Hotkey(bindings)
            else -> CommandHotkey()
        }
        hotkey = key
        key.status.onEach { status -> publish { it.copy(hotkey = status) } }.launchIn(scope)
        scope.launch(Dispatchers.IO) { runCatching { key.start() }.onFailure { CoreLog.w("DyskLink", "Window tidy shortcuts failed", it) } }
    }

    fun act(action: TidyAction) {
        when (action) {
            is TidyAction.SetEnabled -> { settings.set("windowTidy", action.on); publish { it.copy(enabled = action.on, message = null) }; apply() }
            is TidyAction.SetMods -> { settings.set("windowTidy.mods", action.mods.name); publish { it.copy(mods = action.mods) }; apply() }
            is TidyAction.Move -> run(action.id)
            is TidyAction.Save -> scope.launch(Dispatchers.IO) { save(action.name) }
            is TidyAction.Restore -> scope.launch(Dispatchers.IO) { restore(action.id) }
            is TidyAction.Delete -> store.delete(action.id)
            TidyAction.RetryHotkey -> apply()
            TidyAction.Refresh -> scope.launch(Dispatchers.IO) { refreshMonitors() }
        }
    }

    /** A spot's id, or one of the extras. Runs on the caller's thread. */
    fun run(id: String) {
        val b = backend ?: return
        if (!_view.value.supported) return
        runCatching {
            val window = b.active() ?: return
            val monitors = b.monitors()
            val target = when (id) {
                WindowTidy.NEXT_SCREEN -> WindowTidy.onNextScreen(monitors, window.rect, 1)
                WindowTidy.PREVIOUS_SCREEN -> WindowTidy.onNextScreen(monitors, window.rect, -1)
                WindowTidy.GRID -> { grid(b, window, monitors); null }
                else -> {
                    val spot = WindowSpot.entries.firstOrNull { it.name == id } ?: return
                    val monitor = WindowTidy.monitorFor(monitors, window.rect) ?: return
                    spot.frame(monitor.work, window.rect)
                }
            }
            target?.let { b.move(window, it) }
        }.onFailure { CoreLog.w("DyskLink", "Window tidy failed", it) }
    }

    private fun grid(b: WindowBackend, front: OpenWindow, monitors: List<Monitor>) {
        val monitor = WindowTidy.monitorFor(monitors, front.rect) ?: return
        val windows = b.windows().filter { WindowTidy.monitorFor(monitors, it.rect) == monitor }
        windows.zip(WindowTidy.grid(windows.size, monitor.work)).forEach { (w, r) -> b.move(w, r) }
    }

    fun save(name: String) {
        val b = backend ?: return
        val monitors = refreshMonitors()
        val windows = runCatching { b.windows() }.getOrNull().orEmpty()
        if (windows.isEmpty()) { publish { it.copy(message = "No windows to save.") }; return }
        val title = name.trim().take(40).ifEmpty { "Layout ${store.layouts.value.size + 1}" }
        store.add(Layouts.capture(title, windows, monitors))
        publish { it.copy(message = "Saved $title: ${windows.size} windows on ${Layouts.describe(monitors)}.") }
    }

    fun restore(id: String) {
        val b = backend ?: return
        val layout = store.layouts.value.firstOrNull { it.id == id } ?: return
        val monitors = refreshMonitors()
        val plan = Layouts.plan(layout, runCatching { b.windows() }.getOrNull().orEmpty(), monitors)
        plan.forEach { (w, r) -> runCatching { b.move(w, r) } }
        publish { it.copy(message = if (plan.isEmpty()) "None of ${layout.name}'s apps are open." else "Restored ${layout.name}: ${plan.size} of ${layout.windows.size} windows.") }
    }

    companion object {
        /** The shortcuts for [mods]: the Mac's keys; Super with the arrows for the other monitor; G for the grid. */
        fun shortcuts(mods: TidyMods): List<TidyShortcut> {
            val base = HotkeyCombo(ctrl = mods.ctrl, shift = mods.shift, alt = mods.alt, meta = false, key = "A")
            val spots = WindowSpot.entries.map { TidyShortcut(it.name, it.label, base.with(key = it.key)) }
            val gridCombo = if (mods.shift) base.with(meta = true, key = "G") else base.with(shift = true, key = "G")
            return spots + listOf(
                TidyShortcut(WindowTidy.NEXT_SCREEN, "Next monitor", base.with(meta = true, key = "Right")),
                TidyShortcut(WindowTidy.PREVIOUS_SCREEN, "Previous monitor", base.with(meta = true, key = "Left")),
                TidyShortcut(WindowTidy.GRID, "All windows in a grid", gridCombo),
            )
        }
    }
}
