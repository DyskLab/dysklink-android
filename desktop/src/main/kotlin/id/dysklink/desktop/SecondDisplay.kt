package id.dysklink.desktop

import id.phonelink.android.core.CoreLog
import id.phonelink.android.core.HubExtend
import id.phonelink.android.core.JpegFrames
import id.phonelink.android.core.PcDisplay
import id.phonelink.android.core.PcExtendFailed
import id.phonelink.android.core.PcExtraDisplay
import id.phonelink.android.core.PcExtraDisplays
import id.phonelink.android.core.PcScreen
import id.phonelink.android.core.ScreenGrab
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Robot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// The phone as a second display of this PC: a real monitor added to the desktop, which the phone shows and taps on
// with the same messages as this PC's own screen. Each way of adding one is an ExtendBackend; the first that works
// here is used. Wayland: a virtual monitor through the ScreenCast portal (GNOME, KDE Plasma 6), else through GNOME's
// own Mutter API. X11: a VIRTUAL output of the graphics driver, set up with xrandr. Windows: an indirect display driver.

/** The ways a PC can add a monitor, best first. */
enum class ExtendWay(val label: String) {
    PortalVirtual("a virtual monitor from the screen sharing portal"),
    MutterVirtual("a virtual monitor from GNOME"),
    X11Output("the graphics driver's virtual output"),
    WindowsDriver("the Virtual Display Driver"),
}

/** Whether a way can be used: now, after something is set up (shown with a hint), or not on this PC. */
enum class ExtendReadiness { Ready, NeedsSetup, Unavailable }

/** What stops a way, for the hints. */
enum class ExtendProblem {
    /** No ScreenCast portal at all. */
    NoPortal,
    /** The ScreenCast portal has no virtual source (KDE Plasma 5, wlroots, older GNOME portals). */
    PortalNoVirtual,
    NoGstreamer,
    NoPipewiresrc,
    /** GNOME's own API isn't there, or refused DyskLink. */
    NoMutter,
    MutterRefused,
    /** A GNOME without virtual monitors. */
    GnomeTooOld,
    NoXrandr,
    NoCvt,
    /** X11 without a VIRTUAL output (only some drivers have one). */
    X11NoVirtualOutput,
    /** Robot can't capture here. */
    NoCapture,
    /** Windows: the driver isn't installed (DyskLink can install it). */
    DriverMissing,
    /** Windows: this copy of DyskLink doesn't carry the driver. */
    DriverNotInCopy,
    /** Windows: the switch for the experimental driver path is off. */
    ExperimentOff,
}

data class ExtendProbe(val way: ExtendWay, val readiness: ExtendReadiness, val problem: ExtendProblem? = null, val detail: String? = null)

/** One way to add a monitor for the phone. */
interface ExtendBackend {
    val way: ExtendWay
    /** Looks at the system; may run a command or call D-Bus, so not on the UI thread. */
    fun probe(): ExtendProbe
    /** Adds a monitor of [width] × [height] pixels. Throws [PcExtendFailed], or [ExtendUnavailable] to try the next way. */
    fun open(width: Int, height: Int, phoneName: String): PcExtraDisplay
}

/** This way turned out not to work here after all (such as GNOME refusing its API): the next one is tried. */
class ExtendUnavailable(val problem: ExtendProblem, message: String) : PcExtendFailed(message)

object ExtendChoice {
    /** The way used now: the first one that's ready. */
    fun pick(probes: List<ExtendProbe>): ExtendProbe? = probes.firstOrNull { it.readiness == ExtendReadiness.Ready }

    /** Whether phones are told this PC can be extended (`pc-display`): a way works, or works after a setup DyskLink shows. */
    fun listed(probes: List<ExtendProbe>): Boolean = probes.any { it.readiness != ExtendReadiness.Unavailable }

    /** What the phone is told when no way is ready. */
    fun refusal(probes: List<ExtendProbe>): String? {
        if (pick(probes) != null) return null
        val setup = probes.firstOrNull { it.readiness == ExtendReadiness.NeedsSetup } ?: return PcScreen.NO_EXTEND
        return when (setup.problem) {
            ExtendProblem.NoGstreamer -> "This PC needs GStreamer to be extended onto the phone. DyskLink on the PC shows the command to install it."
            ExtendProblem.NoPipewiresrc -> "This PC needs GStreamer's PipeWire plugin to be extended onto the phone. DyskLink on the PC shows the command to install it."
            ExtendProblem.DriverMissing -> "Install the virtual display driver on the PC first: DyskLink, Settings, Second display."
            else -> PcScreen.NO_EXTEND
        }
    }
}

/** What the Second display card shows. */
data class SecondDisplayView(
    val probes: List<ExtendProbe> = emptyList(),
    /** The phone that is (or is becoming) the second display, by id and name. */
    val phoneId: String? = null,
    val phoneName: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    /** False while the monitor is still being added. */
    val active: Boolean = false,
    /** "Let phones see this screen" is on. */
    val screenOn: Boolean = false,
    /** Windows: the experimental driver path is offered at all (a switch on the card). */
    val experimentShown: Boolean = false,
    val experimentOn: Boolean = false,
    /** Probing hasn't finished yet. */
    val checking: Boolean = true,
) {
    val way: ExtendProbe? get() = ExtendChoice.pick(probes)
    val state: SecondDisplayState get() = when {
        phoneId != null -> SecondDisplayState.Active
        checking -> SecondDisplayState.Checking
        way != null -> SecondDisplayState.Available
        probes.any { it.readiness == ExtendReadiness.NeedsSetup } -> SecondDisplayState.NeedsSetup
        else -> SecondDisplayState.Unavailable
    }

    companion object {
        fun of(probes: List<ExtendProbe>, checking: Boolean, extend: HubExtend?, name: (String) -> String?, screenOn: Boolean, experimentShown: Boolean, experimentOn: Boolean) =
            SecondDisplayView(
                probes, extend?.phoneId, extend?.phoneId?.let(name), extend?.width ?: 0, extend?.height ?: 0, extend?.active == true,
                screenOn, experimentShown, experimentOn, checking,
            )
    }
}

enum class SecondDisplayState { Checking, Available, NeedsSetup, Unavailable, Active }

/**
 * Every way this PC has, best first, as the hub's [PcExtraDisplays]. [recheck] looks at each again (on window focus and
 * after an install); [open] uses the first one ready, and goes on to the next when one turns out not to work here.
 */
class SecondDisplayService(private val backends: () -> List<ExtendBackend>) : PcExtraDisplays {
    private val _probes = MutableStateFlow<List<ExtendProbe>>(emptyList())
    val probes: StateFlow<List<ExtendProbe>> = _probes.asStateFlow()
    private val _checking = MutableStateFlow(true)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()
    /** Ways that failed at open() in a way that won't change by itself, kept until the next recheck. */
    private val broken = java.util.concurrent.ConcurrentHashMap<ExtendWay, ExtendProbe>()

    @Synchronized
    fun recheck(): List<ExtendProbe> {
        broken.clear()
        val probes = backends().map { backend ->
            runCatching { backend.probe() }.getOrElse {
                CoreLog.w("DyskLink", "Second display: ${backend.way} probe failed", it)
                ExtendProbe(backend.way, ExtendReadiness.Unavailable)
            }
        }
        _probes.value = probes
        _checking.value = false
        return probes
    }

    val listed: Boolean get() = ExtendChoice.listed(_probes.value)

    override fun refusal(): String? {
        val probes = if (_checking.value) recheck() else _probes.value
        return ExtendChoice.refusal(probes)
    }

    override fun open(width: Int, height: Int, phoneName: String): PcExtraDisplay {
        val ready = _probes.value.filter { it.readiness == ExtendReadiness.Ready }.map { it.way }.toSet()
        var last: PcExtendFailed? = null
        for (backend in backends().filter { it.way in ready }) {
            try {
                CoreLog.i("DyskLink", "Second display: ${width}x$height through ${backend.way}")
                return backend.open(width, height, phoneName)
            } catch (e: ExtendUnavailable) {
                CoreLog.w("DyskLink", "Second display: ${backend.way} doesn't work here: ${e.message}")
                broken[backend.way] = ExtendProbe(backend.way, ExtendReadiness.Unavailable, e.problem, e.message)
                _probes.value = _probes.value.map { broken[it.way] ?: it }
                last = e
            }
        }
        throw last ?: PcExtendFailed(ExtendChoice.refusal(_probes.value) ?: PcScreen.NO_EXTEND)
    }
}

/** The display a virtual monitor shows as, with its own ID so the hub never mixes it with this PC's own screens. */
object ExtraDisplayIds {
    const val ID = 0x44595358L

    fun display(name: String, x: Int, y: Int, width: Int, height: Int) =
        PcDisplay(ID, "$name ($width×$height)", main = false, x = x, y = y, width = width, height = height)
}

/**
 * A virtual monitor's PipeWire stream as JPEG pictures, through gst-launch-1.0. The stream is asked for the phone's
 * size, which makes the compositor size the monitor; a compositor that keeps its own size is read as it comes.
 */
class VirtualMonitorFeed(
    private val node: Long,
    private val width: Int,
    private val height: Int,
    private val gst: GstCheck,
    private val launch: (List<String>) -> Process = { ProcessBuilder(it).start() },
    private val maxLong: Int = id.phonelink.android.core.PcSecondDisplay.MAX_LONG_SIDE,
) {
    @Volatile private var pipe: GstJpegPipe? = null
    @Volatile private var fixed = true
    @Volatile var size: Pair<Int, Int>? = null
        private set
    @Volatile var failure: String? = null
        private set
    private var deaths = 0

    /** Starts gst-launch and waits for the first picture. False when it can't be read at all. */
    fun start(waitMs: Long = FIRST_FRAME_MS): Boolean {
        val until = System.currentTimeMillis() + waitMs
        while (true) {
            val left = until - System.currentTimeMillis()
            if (left <= 0) return false
            if (grab(minOf(left, FRAME_WAIT_MS)) != null) return true
            if (failure != null) return false
            // gst-launch ended at once (such as a size the compositor didn't take): its words arrive, then it's tried again.
            Thread.sleep(150)
        }
    }

    @Synchronized
    fun grab(waitMs: Long = FRAME_WAIT_MS): ScreenGrab? {
        var current = pipe
        if (current == null || !current.isAlive) {
            current?.let { old ->
                val errors = old.errorText
                if (errors.isNotEmpty()) CoreLog.w("DyskLink", "gst-launch-1.0 ended: $errors")
                old.stop()
                // The compositor kept its own size: read it as it comes.
                if (fixed && GstVirtualPipeline.refusedSize(errors)) fixed = false
                else if (++deaths > 3) {
                    failure = "GStreamer couldn't read the extra display on the PC." + errors.lines().lastOrNull { it.isNotBlank() }?.let { " $it" }.orEmpty()
                    return null
                }
            }
            val command = GstVirtualPipeline.command(node, width, height, fixed, maxLong, gst.keepalive)
            CoreLog.i("DyskLink", "Second display: ${command.joinToString(" ")}")
            current = runCatching { GstJpegPipe(launch(command)) }.onFailure { failure = "gst-launch-1.0 couldn't start on the PC." }.getOrNull() ?: return null
            pipe = current
        }
        val (_, jpeg) = current.frame(waitMs) ?: return null
        deaths = 0
        val (w, h) = JpegSize.of(jpeg) ?: return null
        size = w to h
        return ScreenGrab(w, h, JpegSize.fingerprint(jpeg)) { quality -> JpegSize.reencode(jpeg, quality) }
    }

    fun stop() {
        pipe?.stop()
        pipe = null
    }

    companion object {
        const val FIRST_FRAME_MS = 8_000L
        const val FRAME_WAIT_MS = 3_000L
    }
}

/** An AWT screen: where it is in Java's coordinates, and how many device pixels one of them is. */
data class AwtMonitor(val id: String, val bounds: Rectangle, val scaleX: Double = 1.0, val scaleY: Double = 1.0)

/**
 * Finds the AWT screen of a monitor known by its place in device pixels (Windows), so Robot captures it and taps land
 * on it. Java scales each screen's bounds by its own scale, so they're compared in device pixels; with no exact match
 * the closest one of the same size wins, and nothing when none has that size.
 */
object MonitorMatch {
    fun pick(monitors: List<AwtMonitor>, device: Rectangle): AwtMonitor? {
        fun physical(m: AwtMonitor) = Rectangle(
            Math.round(m.bounds.x * m.scaleX).toInt(), Math.round(m.bounds.y * m.scaleY).toInt(),
            Math.round(m.bounds.width * m.scaleX).toInt(), Math.round(m.bounds.height * m.scaleY).toInt(),
        )
        fun near(a: Int, b: Int) = kotlin.math.abs(a - b) <= 2
        val sized = monitors.filter { physical(it).let { p -> near(p.width, device.width) && near(p.height, device.height) } }
        return sized.minByOrNull { physical(it).let { p -> kotlin.math.abs(p.x - device.x) + kotlin.math.abs(p.y - device.y) } }
    }

    fun awt(): List<AwtMonitor> = runCatching {
        if (GraphicsEnvironment.isHeadless()) emptyList()
        else GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map {
            val config = it.defaultConfiguration
            AwtMonitor(it.iDstring, config.bounds, config.defaultTransform.scaleX, config.defaultTransform.scaleY)
        }
    }.getOrDefault(emptyList())

    /** Where the right edge of the desktop is, to put a new monitor beside everything: x, and the top of the main one. */
    fun rightOf(monitors: List<Rectangle>): Pair<Int, Int> {
        if (monitors.isEmpty()) return 0 to 0
        val main = monitors.firstOrNull { it.x == 0 && it.y == 0 } ?: monitors.first()
        return monitors.maxOf { it.x + it.width } to main.y
    }
}

/**
 * A monitor captured with Robot (X11, Windows): [bounds] is read for every picture, since the user can move it in
 * the display settings while it's shown. Taps go through desktop pixels, as for this PC's own screen.
 */
class RobotExtraDisplay(
    private val name: String,
    private val bounds: () -> Rectangle?,
    private val onRelease: () -> Unit,
) : PcExtraDisplay {
    private val robot: Robot? by lazy { if (GraphicsEnvironment.isHeadless()) null else runCatching { Robot() }.getOrNull() }
    @Volatile private var released = false

    override fun refusal(): String? = null

    override fun displays(): List<PcDisplay> {
        val b = bounds() ?: return emptyList()
        return listOf(ExtraDisplayIds.display(name, b.x, b.y, b.width, b.height))
    }

    override fun grab(display: PcDisplay, maxLong: Int): ScreenGrab? {
        val robot = robot ?: return null
        val b = bounds() ?: return null
        val shot = robot.createScreenCapture(b)
        val pointer = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
        return ScreenPictures.grab(shot, display.copy(x = b.x, y = b.y, width = b.width, height = b.height), pointer, maxLong)
    }

    override fun failure(): String? = "The extra display on the PC couldn't be captured."

    @Synchronized
    override fun release() {
        if (released) return
        released = true
        runCatching(onRelease).onFailure { CoreLog.w("DyskLink", "Second display: removing it failed", it) }
    }
}

/** A tap on a virtual monitor, in the monitor's own pixels: moves, then left or right presses, as [PointerSteps] says. */
object VirtualTaps {
    fun at(kind: String, x: Float, y: Float, width: Int, height: Int, move: (Double, Double) -> Unit, button: (Int, Boolean) -> Unit) {
        val px = (x.coerceIn(0f, 1f) * (width - 1).coerceAtLeast(0)).toDouble()
        val py = (y.coerceIn(0f, 1f) * (height - 1).coerceAtLeast(0)).toDouble()
        for (step in PointerSteps.of(kind)) when (step) {
            PointerStep.MOVE -> move(px, py)
            PointerStep.LEFT_DOWN -> button(Evdev.BTN_LEFT, true)
            PointerStep.LEFT_UP -> button(Evdev.BTN_LEFT, false)
            PointerStep.RIGHT_DOWN -> button(Evdev.BTN_RIGHT, true)
            PointerStep.RIGHT_UP -> button(Evdev.BTN_RIGHT, false)
        }
    }
}
