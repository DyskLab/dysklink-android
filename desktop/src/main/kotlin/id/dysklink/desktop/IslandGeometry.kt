package id.dysklink.desktop

import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The island's shape: a body with rounded bottom corners, flat on the screen's top edge, flaring out into the edge
 * with two concave "shoulders". Sizes are in dp, which are AWT's user-space units on every OS (Compose draws one dp
 * per AWT unit times the screen's scale).
 */
data class IslandShapeSpec(val width: Float, val height: Float, val bottomRadius: Float, val shoulder: Float) {
    /** The window that holds it: the body plus a shoulder on each side. */
    val frameWidth: Float get() = width + 2 * shoulder

    companion object {
        /** Nothing live: the two eyes. About 200 by 40, as in the design. */
        val Resting = IslandShapeSpec(200f, 40f, 16f, 10f)
        /** A ring, a big text, and the music bars. */
        val Live = IslandShapeSpec(250f, 48f, 18f, 10f)
        /** A code with Copy, or a new notification. */
        val Banner = IslandShapeSpec(420f, 84f, 24f, 10f)
        /** Open, with its pages. The Mac's open notch is 600 wide. */
        val Open = IslandShapeSpec(620f, 214f, 30f, 10f)
    }
}

/** A screen as AWT reports it: bounds in user space (which can start left of or above the primary screen) and scale. */
data class IslandMonitor(
    val id: String,
    val bounds: Rect,
    val scale: Double,
    val primary: Boolean,
    val name: String = id,
    /** A panel or taskbar along the top edge: the island hangs below it instead of hiding behind it (GNOME's top bar). */
    val topInset: Int = 0,
)

object IslandGeometry {
    /** The chosen screen, the primary one when nothing or a screen that's gone is chosen. */
    fun pick(monitors: List<IslandMonitor>, wanted: String?): IslandMonitor? =
        monitors.firstOrNull { it.id == wanted } ?: monitors.firstOrNull { it.primary } ?: monitors.firstOrNull()

    /** The window's bounds in AWT user space: centred at the top of [monitor], whatever its offset. */
    fun frame(monitor: Rect, shape: IslandShapeSpec, topInset: Int = 0): Rect {
        val w = ceil(shape.frameWidth).toInt()
        val h = ceil(shape.height).toInt()
        return Rect(monitor.x + (monitor.width - w) / 2, monitor.y + topInset.coerceIn(0, monitor.height / 4), w, h)
    }

    fun frame(monitor: IslandMonitor, shape: IslandShapeSpec): Rect = frame(monitor.bounds, shape, monitor.topInset)

    /** A window big enough for both shapes, for while it animates from one to the other. */
    fun union(a: IslandShapeSpec, b: IslandShapeSpec): IslandShapeSpec =
        IslandShapeSpec(maxOf(a.width, b.width), maxOf(a.height, b.height), maxOf(a.bottomRadius, b.bottomRadius), maxOf(a.shoulder, b.shoulder))

    /** The same rectangle in the screen's own pixels, for the native calls. */
    fun physical(r: Rect, scale: Double): Rect = WindowTidy.fromAwt(r, scale)

    /**
     * Where the island takes the mouse, in the window's own pixels: the body, rounded at the bottom in steps of two
     * pixels, plus the top strip where the shoulders meet the edge. Clicks anywhere else go to what's under it
     * (SetWindowRgn on Windows, the XShape input region on X11). [frameWidth] is the window's width in dp.
     */
    fun inputRects(shape: IslandShapeSpec, frameWidth: Float, scale: Double): List<Rect> {
        val left = (frameWidth - shape.width) / 2
        val px = { v: Float -> (v * scale).roundToInt() }
        val rects = mutableListOf<Rect>()
        // The shoulders and the body's top.
        val strip = shape.shoulder / 2
        rects += Rect(px(left - shape.shoulder), 0, px(shape.width + 2 * shape.shoulder), px(strip).coerceAtLeast(1))
        val r = minOf(shape.bottomRadius, shape.height / 2, shape.width / 2)
        val straight = shape.height - r
        rects += Rect(px(left), 0, px(shape.width), px(straight))
        val step = (2 / scale).toFloat()
        var y = straight
        while (y < shape.height) {
            val next = minOf(shape.height, y + step)
            val dy = (y + next) / 2 - straight
            val inset = r - sqrt((r * r - dy * dy).coerceAtLeast(0f))
            val top = px(y)
            val bottom = px(next)
            if (bottom > top) rects += Rect(px(left + inset), top, px(shape.width - 2 * inset), bottom - top)
            y = next
        }
        return rects.filter { it.width > 0 && it.height > 0 }
    }
}

/**
 * Whether a full-screen app has the screen the island is on, so the island hides like the Mac notch does under a
 * full-screen video or game.
 */
object FullscreenRule {
    const val QUNS_NOT_PRESENT = 1
    const val QUNS_BUSY = 2
    const val QUNS_RUNNING_D3D_FULL_SCREEN = 3
    const val QUNS_PRESENTATION_MODE = 4
    const val QUNS_ACCEPTS_NOTIFICATIONS = 5

    /** The desktop and the taskbar cover the screen too, but aren't apps. */
    private val SHELL = setOf("Progman", "WorkerW", "Shell_TrayWnd", "Shell_SecondaryTrayWnd")

    /**
     * Windows: SHQueryUserNotificationState says a Direct3D game or presentation mode runs anywhere, or that an app
     * is full screen ("busy"); the window in front, covering all of the island's screen, says which one.
     */
    fun windows(state: Int?, foreground: Rect?, foregroundClass: String?, foregroundMonitor: Rect?, islandMonitor: Rect?): Boolean {
        if (state == QUNS_RUNNING_D3D_FULL_SCREEN || state == QUNS_PRESENTATION_MODE) return true
        // "Busy" alone may be another screen's app, and a borderless full-screen window may not say "busy" at all:
        // the window in front, covering the whole screen (a maximized one leaves the taskbar), tells.
        return foreground != null && foregroundMonitor != null && foregroundClass !in SHELL &&
            covers(foreground, foregroundMonitor) && (islandMonitor == null || foregroundMonitor == islandMonitor)
    }

    /** X11 (and XWayland): the active window has _NET_WM_STATE_FULLSCREEN and sits on the island's screen. */
    fun x11(activeStates: Collection<String>?, activeBounds: Rect?, islandMonitor: Rect): Boolean {
        if (activeStates == null || "_NET_WM_STATE_FULLSCREEN" !in activeStates) return false
        val b = activeBounds ?: return true
        return islandMonitor.contains(b.centerX, b.centerY)
    }

    fun covers(window: Rect, monitor: Rect): Boolean =
        window.x <= monitor.x && window.y <= monitor.y && window.right >= monitor.right && window.bottom >= monitor.bottom
}
