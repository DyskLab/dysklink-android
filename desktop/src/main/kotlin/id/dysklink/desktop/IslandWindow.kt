package id.dysklink.desktop

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Offset
import id.dysklink.desktop.ui.IslandContent
import id.dysklink.desktop.ui.IslandUiState
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/**
 * The island's window: borderless, see-through, above everything, out of the taskbar and Alt+Tab, hanging from the
 * top edge of the chosen screen. It's only as big as the island's shape, grows before the island opens and shrinks
 * after it closes, and takes clicks only inside the shape. With "show as a small window" (for Wayland sessions where
 * an overlay can't be placed), it's an ordinary small window with the island open in it.
 */
class IslandWindow(private val service: IslandService) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val ui = IslandUiState()
    private var window: ComposeWindow? = null
    private var windowJob: Job? = null
    private var asWindow = false
    private var monitor: IslandMonitor? = null
    /** The shape the window is sized for now, and the one the island is heading to. */
    private var sizedFor = IslandShapeSpec.Resting
    private var target = IslandShapeSpec.Resting
    private var resize: Job? = null
    private var hovering = false
    private var hoverJob: Job? = null
    private var hiddenForFullscreen = false
    private var reducedMotion = false

    fun start() {
        scope.launch {
            service.settings.map { Triple(it.enabled, it.asWindow, it.monitor) }.distinctUntilChanged().collect { (on, small, _) ->
                close()
                if (on) open(small)
            }
        }
    }

    fun stop() {
        close()
        scope.cancel()
    }

    private fun open(small: Boolean) {
        asWindow = small
        reducedMotion = runCatching { service.native.reducedMotion() }.getOrDefault(false)
        val w = ComposeWindow()
        window = w
        w.title = "DyskLink island"
        w.isAutoRequestFocus = false
        if (small) {
            ui.open = true
            w.isAlwaysOnTop = true
            w.isResizable = false
            w.setSize(IslandShapeSpec.Open.frameWidth.toInt() + 16, IslandShapeSpec.Open.height.toInt() + 40)
            w.setLocationRelativeTo(null)
            w.addWindowListener(object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent) { service.update { it.copy(asWindow = false, enabled = false) } }
            })
        } else {
            ui.open = false
            w.isUndecorated = true
            w.isTransparent = true
            w.background = Color(0, 0, 0, 0)
            w.isAlwaysOnTop = true
            w.isResizable = false
            if (Platform.isWindows) w.type = Window.Type.UTILITY
            w.focusableWindowState = true
            w.defaultCloseOperation = javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE
        }
        w.setContent {
            IslandContent(
                service, ui, reducedMotion = reducedMotion,
                onHover = ::hover,
                onClosedClick = { openIsland(byClick = true) },
                onKeyboard = { wanted -> if (wanted) takeKeyboard() },
                onFilesOver = {
                    ui.page = IslandPage.Shelf
                    ui.shelfMode = ShelfMode.Files
                    openIsland(byClick = false)
                },
                onShape = { shape -> if (!small) shapeChanged(shape) },
            )
        }
        if (!small) {
            monitor = currentMonitor()
            sizedFor = IslandShapeSpec.Resting
            target = sizedFor
            place(sizedFor)
            w.addNotify()
            runCatching { service.native.prepare(w) }
        }
        w.isVisible = true
        if (!small) {
            // Some window managers read the window type only once it maps.
            runCatching { service.native.prepare(w) }
            applyInput(sizedFor)
            windowJob = scope.launch { watch(w) }
        }
    }

    private fun close() {
        windowJob?.cancel()
        hoverJob?.cancel()
        resize?.cancel()
        window?.let { it.isVisible = false; it.dispose() }
        window = null
        ui.open = false
        ui.typing = null
    }

    private fun currentMonitor(): IslandMonitor? = IslandGeometry.pick(monitors(), service.settings.value.monitor)

    private fun place(shape: IslandShapeSpec) {
        val w = window ?: return
        val m = monitor ?: return
        val r = IslandGeometry.frame(m, shape)
        if (w.x != r.x || w.y != r.y || w.width != r.width || w.height != r.height) w.setBounds(r.x, r.y, r.width, r.height)
    }

    private fun applyInput(shape: IslandShapeSpec) {
        val w = window ?: return
        val scale = monitor?.scale ?: 1.0
        runCatching { service.native.setInputRegion(w, IslandGeometry.inputRects(shape, w.width.toFloat(), scale)) }
    }

    /** The island heads to a new shape: grow the window now, shrink it once the animation's done. */
    private fun shapeChanged(shape: IslandShapeSpec) {
        if (shape == target) return
        target = shape
        resize?.cancel()
        sizedFor = IslandGeometry.union(sizedFor, shape)
        place(sizedFor)
        applyInput(shape)
        resize = scope.launch {
            delay(if (reducedMotion) 0 else 380)
            sizedFor = target
            place(sizedFor)
            applyInput(sizedFor)
        }
    }

    private fun hover(inside: Boolean) {
        hovering = inside
        hoverJob?.cancel()
        if (asWindow) return
        hoverJob = scope.launch {
            if (inside) {
                if (ui.open || service.settings.value.opensOnClick) return@launch
                delay(200)
                if (hovering) openIsland(byClick = false)
            } else {
                delay(250)
                if (!hovering && ui.typing == null) closeIsland()
            }
        }
    }

    private fun openIsland(byClick: Boolean) {
        ui.open = true
        if (byClick) takeKeyboard()
    }

    private fun closeIsland() {
        if (asWindow) return
        ui.open = false
        ui.typing = null
        window?.let { runCatching { service.native.setActivates(it, false) } }
    }

    /** Esc and typing a task need the keyboard; the island asks for it only when clicked or typed into. */
    private fun takeKeyboard() {
        val w = window ?: return
        runCatching { service.native.setActivates(w, true) }
        w.toFront()
        w.requestFocus()
    }

    /** Every second: hide under a full-screen app, follow screen changes. A few times a second: where the pointer is. */
    private suspend fun watch(w: ComposeWindow) {
        var ticks = 0
        while (scope.isActive && window === w) {
            if (ticks % 8 == 0) {
                val m = currentMonitor()
                if (m != null && m != monitor) { monitor = m; place(sizedFor); applyInput(target) }
                val full = m != null && withContext(Dispatchers.IO) {
                    runCatching { service.native.fullscreen(w, IslandGeometry.physical(m.bounds, m.scale)) }.getOrDefault(false)
                }
                if (full != hiddenForFullscreen) {
                    hiddenForFullscreen = full
                    if (full) closeIsland()
                    w.isVisible = !full
                    if (!full) { service.native.prepare(w); applyInput(target) }
                }
            }
            if (!reducedMotion && !ui.open) ui.glance = glance(w)
            ticks++
            delay(125)
        }
    }

    /** Where the pointer is from the eyes, -1..1 each way, when it's within reach; zero otherwise. */
    private fun glance(w: Window): Offset {
        val p = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: return Offset.Zero
        return IslandEyes.glance(p.x - (w.x + w.width / 2.0), p.y - (w.y + w.height / 2.0))
    }

    companion object {
        /** The screens as AWT sees them, with any panel along their top. */
        fun monitors(): List<IslandMonitor> = runCatching {
            val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val primary = env.defaultScreenDevice
            env.screenDevices.mapIndexed { i, device ->
                val gc = device.defaultConfiguration
                val b = gc.bounds
                val inset = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(gc).top }.getOrDefault(0)
                IslandMonitor(
                    id = device.iDstring, bounds = Rect(b.x, b.y, b.width, b.height), scale = gc.defaultTransform.scaleX,
                    primary = device == primary, name = "Screen ${i + 1} (${b.width} x ${b.height})" + if (device == primary) ", main" else "",
                    topInset = inset,
                )
            }
        }.getOrDefault(emptyList())
    }
}

object IslandEyes {
    const val REACH = 260.0

    /** The eyes look toward a pointer [dx], [dy] from them, more the nearer it is, and rest when it's out of reach. */
    fun glance(dx: Double, dy: Double): Offset {
        val distance = hypot(dx, dy)
        if (distance > REACH || distance < 1) return Offset.Zero
        val pull = (1 - distance / REACH).coerceIn(0.0, 1.0) * 0.6 + 0.4
        return Offset((dx / distance * pull).toFloat(), (dy / distance * pull).toFloat().coerceIn(-1f, 1f))
    }
}
