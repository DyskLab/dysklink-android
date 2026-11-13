package id.dysklink.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import id.phonelink.android.core.CoreLog
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// The pointer and keyboard on Wayland, which doesn't let apps reach other apps' windows: through the desktop's
// RemoteDesktop portal after the user allows it once, or a virtual keyboard and mouse on /dev/uinput where there's no
// such portal (Sway, Hyprland and other wlroots desktops). X11 and Windows keep java.awt.Robot.

/** How this PC's pointer and keyboard are reached, in the order they're tried. */
enum class InputRoute { ROBOT, PORTAL, UINPUT, NONE }

object InputRoutes {
    /** X11 and Windows: Robot. Wayland: the portal, then /dev/uinput, then nothing (and a hint). */
    fun choose(wayland: Boolean, robot: Boolean, portal: () -> Boolean, uinput: () -> Boolean): InputRoute = when {
        !wayland -> if (robot) InputRoute.ROBOT else InputRoute.NONE
        portal() -> InputRoute.PORTAL
        uinput() -> InputRoute.UINPUT
        else -> InputRoute.NONE
    }
}

enum class PortalPhase { Idle, Waiting, Allowed, Denied, Failed }

/** Where asking the portal stands, for the hints on this PC. [detail] says why it failed. */
data class PortalStatus(val phase: PortalPhase = PortalPhase.Idle, val detail: String? = null)

/** Linux input event codes (linux/input-event-codes.h). */
object Evdev {
    const val BTN_LEFT = 0x110
    const val BTN_RIGHT = 0x111
    const val BTN_MIDDLE = 0x112
    const val BTN_SIDE = 0x113
    const val BTN_EXTRA = 0x114

    const val KEY_LEFTCTRL = 29
    const val KEY_LEFTSHIFT = 42
    const val KEY_LEFTALT = 56
    const val KEY_LEFTMETA = 125
    const val KEY_V = 47

    /** 0 left, 1 right, 2 middle, 3 back, 4 forward, as [InputInjector.button] numbers them. */
    fun button(number: Int): Int? = when (number) {
        0 -> BTN_LEFT
        1 -> BTN_RIGHT
        2 -> BTN_MIDDLE
        3 -> BTN_SIDE
        4 -> BTN_EXTRA
        else -> null
    }

    private val keys: Map<Int, Int> = buildMap {
        val letters = "qwertyuiop" to 16
        val home = "asdfghjkl" to 30
        val bottom = "zxcvbnm" to 44
        for ((row, start) in listOf(letters, home, bottom)) row.forEachIndexed { i, c -> put(KeyEvent.VK_A + (c - 'a'), start + i) }
        for (d in 1..9) put(KeyEvent.VK_0 + d, 1 + d)
        put(KeyEvent.VK_0, 11)
        put(KeyEvent.VK_ESCAPE, 1); put(KeyEvent.VK_MINUS, 12); put(KeyEvent.VK_EQUALS, 13); put(KeyEvent.VK_BACK_SPACE, 14)
        put(KeyEvent.VK_TAB, 15); put(KeyEvent.VK_OPEN_BRACKET, 26); put(KeyEvent.VK_CLOSE_BRACKET, 27); put(KeyEvent.VK_ENTER, 28)
        put(KeyEvent.VK_CONTROL, KEY_LEFTCTRL); put(KeyEvent.VK_SEMICOLON, 39); put(KeyEvent.VK_QUOTE, 40); put(KeyEvent.VK_BACK_QUOTE, 41)
        put(KeyEvent.VK_SHIFT, KEY_LEFTSHIFT); put(KeyEvent.VK_BACK_SLASH, 43); put(KeyEvent.VK_COMMA, 51); put(KeyEvent.VK_PERIOD, 52)
        put(KeyEvent.VK_SLASH, 53); put(KeyEvent.VK_MULTIPLY, 55); put(KeyEvent.VK_ALT, KEY_LEFTALT); put(KeyEvent.VK_SPACE, 57)
        put(KeyEvent.VK_CAPS_LOCK, 58)
        for (n in 1..10) put(KeyEvent.VK_F1 + n - 1, 58 + n)
        put(KeyEvent.VK_NUM_LOCK, 69); put(KeyEvent.VK_SCROLL_LOCK, 70)
        put(KeyEvent.VK_NUMPAD7, 71); put(KeyEvent.VK_NUMPAD8, 72); put(KeyEvent.VK_NUMPAD9, 73); put(KeyEvent.VK_SUBTRACT, 74)
        put(KeyEvent.VK_NUMPAD4, 75); put(KeyEvent.VK_NUMPAD5, 76); put(KeyEvent.VK_NUMPAD6, 77); put(KeyEvent.VK_ADD, 78)
        put(KeyEvent.VK_NUMPAD1, 79); put(KeyEvent.VK_NUMPAD2, 80); put(KeyEvent.VK_NUMPAD3, 81); put(KeyEvent.VK_NUMPAD0, 82)
        put(KeyEvent.VK_DECIMAL, 83); put(KeyEvent.VK_LESS, 86); put(KeyEvent.VK_F11, 87); put(KeyEvent.VK_F12, 88)
        put(KeyEvent.VK_DIVIDE, 98); put(KeyEvent.VK_PRINTSCREEN, 99); put(KeyEvent.VK_ALT_GRAPH, 100)
        put(KeyEvent.VK_HOME, 102); put(KeyEvent.VK_UP, 103); put(KeyEvent.VK_PAGE_UP, 104); put(KeyEvent.VK_LEFT, 105)
        put(KeyEvent.VK_RIGHT, 106); put(KeyEvent.VK_END, 107); put(KeyEvent.VK_DOWN, 108); put(KeyEvent.VK_PAGE_DOWN, 109)
        put(KeyEvent.VK_INSERT, 110); put(KeyEvent.VK_DELETE, 111); put(KeyEvent.VK_PAUSE, 119)
        put(KeyEvent.VK_WINDOWS, KEY_LEFTMETA); put(KeyEvent.VK_META, KEY_LEFTMETA); put(KeyEvent.VK_CONTEXT_MENU, 127)
        for (n in 13..24) put(KeyEvent.VK_F13 + n - 13, 183 + n - 13)
    }

    /** The evdev key for an AWT key code, as the phone's keys and the Mac's are kept, or null. */
    fun key(vk: Int): Int? = keys[vk]

    /** Every key code a virtual keyboard says it has. */
    val allKeys: Collection<Int> get() = keys.values.toSortedSet()
}

/** The pointer and keyboard on Wayland, one way or another. Each call does nothing while it isn't [ready]. */
interface WaylandBackend {
    val route: InputRoute
    /** True when input goes through now; may start asking for permission, and is false until it's given. */
    fun ready(): Boolean
    /** Whether [absolute] can place the pointer now. */
    val canAbsolute: Boolean
    /** Whether characters can be typed by keysym, whatever the layout. */
    val keysyms: Boolean
    fun relative(dx: Double, dy: Double)
    /** At desktop pixels; false when it can't be done this way now. */
    fun absolute(x: Int, y: Int): Boolean
    fun button(code: Int, down: Boolean)
    /** Wheel notches; positive is down or right. */
    fun scroll(horizontal: Boolean, steps: Int)
    fun key(code: Int, down: Boolean)
    fun keysym(sym: Int, down: Boolean)
    fun close() {}
}

/**
 * Through org.freedesktop.portal.RemoteDesktop. The first input after start asks the user (once, with a restore
 * token after that); until then input is dropped. While a phone views the screen with control, the screen's session
 * (RemoteDesktop with a ScreenCast stream) takes the input, which also lets the pointer be placed exactly.
 */
class PortalBackend(
    private val start: (onClosed: (PortalSession) -> Unit) -> PortalSession,
    private val onStatus: (PortalStatus) -> Unit = {},
) : WaylandBackend {
    override val route = InputRoute.PORTAL
    @Volatile private var session: PortalSession? = null
    @Volatile private var phase = PortalPhase.Idle
    @Volatile private var failedAt = 0L
    /** The screen's session with input, while a phone views this screen and may control it. */
    @Volatile var screenSession: PortalSession? = null

    private fun active(): PortalSession? =
        screenSession?.takeIf { !it.closed && it.hasPointer } ?: session?.takeIf { !it.closed }

    override fun ready(): Boolean {
        if (active() != null) return true
        when (phase) {
            PortalPhase.Waiting, PortalPhase.Denied -> return false
            // A dialog that timed out or a portal that failed is asked again, but not on every pointer move.
            PortalPhase.Failed -> if (System.currentTimeMillis() - failedAt < RETRY_MS) return false
            else -> {}
        }
        ask()
        return false
    }

    /** Asks again, such as from "Ask again" on the hint after the user said no. */
    @Synchronized
    fun ask() {
        if (phase == PortalPhase.Waiting) return
        set(PortalStatus(PortalPhase.Waiting))
        thread(isDaemon = true, name = "dysk-portal-input") {
            try {
                val started = start { closed ->
                    if (session === closed) { session = null; set(PortalStatus(PortalPhase.Idle)) }
                }
                if (!started.hasPointer && !started.hasKeyboard) {
                    started.close()
                    set(PortalStatus(PortalPhase.Denied, "The pointer and keyboard weren't shared."))
                } else {
                    session = started
                    set(PortalStatus(PortalPhase.Allowed))
                }
            } catch (e: PortalRefused) {
                if (!e.cancelled) failedAt = System.currentTimeMillis()
                set(PortalStatus(if (e.cancelled) PortalPhase.Denied else PortalPhase.Failed, e.message))
            } catch (e: Exception) {
                CoreLog.w("DyskLink", "Remote desktop portal failed", e)
                failedAt = System.currentTimeMillis()
                set(PortalStatus(PortalPhase.Failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun set(status: PortalStatus) {
        phase = status.phase
        onStatus(status)
    }

    private inline fun on(block: (PortalSession) -> Unit) {
        val s = active() ?: return
        runCatching { block(s) }.onFailure { CoreLog.w("DyskLink", "Portal input failed", it) }
    }

    override val canAbsolute: Boolean get() = screenSession?.takeIf { !it.closed && it.hasPointer }?.streams?.isNotEmpty() == true
    override val keysyms = true

    override fun relative(dx: Double, dy: Double) = on { it.motion(dx, dy) }

    override fun absolute(x: Int, y: Int): Boolean {
        val s = screenSession?.takeIf { !it.closed && it.hasPointer } ?: return false
        val stream = s.streams.firstOrNull() ?: return false
        val (sx, sy) = stream.position ?: (0 to 0)
        val size = stream.size
        val lx = (x - sx).toDouble().let { if (size != null) it.coerceIn(0.0, size.first - 1.0) else it }
        val ly = (y - sy).toDouble().let { if (size != null) it.coerceIn(0.0, size.second - 1.0) else it }
        return runCatching { s.motionAbsolute(stream.node, lx, ly); true }.getOrDefault(false)
    }

    override fun button(code: Int, down: Boolean) = on { it.button(code, down) }
    override fun scroll(horizontal: Boolean, steps: Int) = on { it.axisDiscrete(horizontal, steps) }
    override fun key(code: Int, down: Boolean) = on { it.keycode(code, down) }
    override fun keysym(sym: Int, down: Boolean) = on { it.keysym(sym, down) }

    override fun close() {
        session?.close()
        session = null
    }

    private companion object {
        const val RETRY_MS = 30_000L
    }
}

// ---- /dev/uinput ----

/** The parts of linux/uinput.h and linux/input.h a virtual keyboard and mouse need, on 64-bit and 32-bit Linux. */
object Uinput {
    const val EV_SYN = 0
    const val EV_KEY = 1
    const val EV_REL = 2
    const val EV_ABS = 3
    const val SYN_REPORT = 0
    const val REL_X = 0
    const val REL_Y = 1
    const val REL_HWHEEL = 6
    const val REL_WHEEL = 8
    const val ABS_X = 0
    const val ABS_Y = 1
    const val BUS_VIRTUAL = 0x06
    const val ABS_MAX = 65535
    const val NAME_SIZE = 80
    /** struct uinput_setup: struct input_id (4 × u16), char name[80], u32 ff_effects_max. */
    const val SETUP_SIZE = 8 + NAME_SIZE + 4
    /** struct uinput_abs_setup: u16 code, 2 bytes padding, struct input_absinfo (6 × s32). */
    const val ABS_SETUP_SIZE = 4 + 6 * 4

    private const val IOC_WRITE = 1
    private const val TYPE = 'U'.code

    private fun io(nr: Int) = (TYPE shl 8) or nr
    private fun iow(nr: Int, size: Int) = (IOC_WRITE shl 30) or (size shl 16) or (TYPE shl 8) or nr

    val UI_DEV_CREATE = io(1)
    val UI_DEV_DESTROY = io(2)
    val UI_DEV_SETUP = iow(3, SETUP_SIZE)
    val UI_ABS_SETUP = iow(4, ABS_SETUP_SIZE)
    val UI_SET_EVBIT = iow(100, 4)
    val UI_SET_KEYBIT = iow(101, 4)
    val UI_SET_RELBIT = iow(102, 4)
    val UI_SET_ABSBIT = iow(103, 4)

    const val O_WRONLY = 1
    const val O_NONBLOCK = 0x800

    /** struct input_event: struct timeval (two longs), u16 type, u16 code, s32 value. The kernel fills in the time. */
    fun eventSize(longSize: Int) = 2 * longSize + 8

    fun event(type: Int, code: Int, value: Int, longSize: Int = Native.LONG_SIZE): ByteArray {
        val buffer = ByteBuffer.allocate(eventSize(longSize)).order(ByteOrder.nativeOrder())
        buffer.position(2 * longSize)
        buffer.putShort(type.toShort()).putShort(code.toShort()).putInt(value)
        return buffer.array()
    }

    /** Events then a SYN_REPORT, in one write. */
    fun frame(events: List<Triple<Int, Int, Int>>, longSize: Int = Native.LONG_SIZE): ByteArray {
        val size = eventSize(longSize)
        val out = ByteArray(size * (events.size + 1))
        (events + Triple(EV_SYN, SYN_REPORT, 0)).forEachIndexed { i, (t, c, v) -> event(t, c, v, longSize).copyInto(out, i * size) }
        return out
    }

    fun setup(name: String, vendor: Int = 0x1209, product: Int = 0xD15C, version: Int = 1): ByteArray {
        val buffer = ByteBuffer.allocate(SETUP_SIZE).order(ByteOrder.nativeOrder())
        buffer.putShort(BUS_VIRTUAL.toShort()).putShort(vendor.toShort()).putShort(product.toShort()).putShort(version.toShort())
        val bytes = name.toByteArray(Charsets.UTF_8).copyOf(NAME_SIZE - 1)
        buffer.put(bytes)
        return buffer.array()
    }

    fun absSetup(code: Int, max: Int = ABS_MAX): ByteArray {
        val buffer = ByteBuffer.allocate(ABS_SETUP_SIZE).order(ByteOrder.nativeOrder())
        buffer.putShort(code.toShort()).putShort(0)
        // value, minimum, maximum, fuzz, flat, resolution
        buffer.putInt(0).putInt(0).putInt(max).putInt(0).putInt(0).putInt(0)
        return buffer.array()
    }

    /** Desktop pixel [x], [y] on 0..ABS_MAX across the box around every display. */
    fun scale(x: Int, y: Int, displays: List<Rectangle>): Pair<Int, Int>? {
        if (displays.isEmpty()) return null
        val box = displays.reduce { a, b -> a.union(b) }
        if (box.width <= 1 || box.height <= 1) return null
        val ax = ((x - box.x).toDouble() / (box.width - 1) * ABS_MAX).roundToInt().coerceIn(0, ABS_MAX)
        val ay = ((y - box.y).toDouble() / (box.height - 1) * ABS_MAX).roundToInt().coerceIn(0, ABS_MAX)
        return ax to ay
    }

    fun writable(node: File = File("/dev/uinput")): Boolean = node.exists() && node.canWrite()
}

/** A virtual keyboard with a mouse, and a second device for placing the pointer, made on /dev/uinput. */
class UinputBackend private constructor(
    private val lib: LibC,
    private val relative: Int,
    private val absolutes: Int,
    private val displays: () -> List<Rectangle>,
) : WaylandBackend {
    @Suppress("FunctionName")
    interface LibC : Library {
        fun open(path: String, flags: Int): Int
        fun ioctl(fd: Int, request: NativeLong, value: Int): Int
        fun ioctl(fd: Int, request: NativeLong, data: ByteArray): Int
        fun write(fd: Int, data: ByteArray, count: NativeLong): NativeLong
        fun close(fd: Int): Int
    }

    override val route = InputRoute.UINPUT
    override fun ready() = true
    override val canAbsolute = absolutes >= 0
    override val keysyms = false

    @Synchronized
    private fun send(fd: Int, vararg events: Triple<Int, Int, Int>) {
        if (fd < 0) return
        val bytes = Uinput.frame(events.toList())
        lib.write(fd, bytes, NativeLong(bytes.size.toLong()))
    }

    override fun relative(dx: Double, dy: Double) {
        val x = dx.roundToInt()
        val y = dy.roundToInt()
        if (x != 0 || y != 0) send(relative, Triple(Uinput.EV_REL, Uinput.REL_X, x), Triple(Uinput.EV_REL, Uinput.REL_Y, y))
    }

    override fun absolute(x: Int, y: Int): Boolean {
        if (absolutes < 0) return false
        val (ax, ay) = Uinput.scale(x, y, displays()) ?: return false
        send(absolutes, Triple(Uinput.EV_ABS, Uinput.ABS_X, ax), Triple(Uinput.EV_ABS, Uinput.ABS_Y, ay))
        return true
    }

    override fun button(code: Int, down: Boolean) = send(relative, Triple(Uinput.EV_KEY, code, if (down) 1 else 0))

    // A wheel turned away from you is positive on evdev, which scrolls up.
    override fun scroll(horizontal: Boolean, steps: Int) =
        if (horizontal) send(relative, Triple(Uinput.EV_REL, Uinput.REL_HWHEEL, steps))
        else send(relative, Triple(Uinput.EV_REL, Uinput.REL_WHEEL, -steps))

    override fun key(code: Int, down: Boolean) = send(relative, Triple(Uinput.EV_KEY, code, if (down) 1 else 0))
    override fun keysym(sym: Int, down: Boolean) {}

    override fun close() {
        for (fd in listOf(relative, absolutes)) if (fd >= 0) {
            runCatching { lib.ioctl(fd, NativeLong(Uinput.UI_DEV_DESTROY.toLong()), 0); lib.close(fd) }
        }
    }

    companion object {
        /** Both devices, or null when /dev/uinput can't be opened. */
        fun create(displays: () -> List<Rectangle>, node: String = "/dev/uinput"): UinputBackend? = runCatching {
            val lib = Native.load("c", LibC::class.java)
            fun call(fd: Int, request: Int, value: Int) {
                check(lib.ioctl(fd, NativeLong(request.toLong()), value) >= 0) { "ioctl ${Integer.toHexString(request)} failed" }
            }
            fun device(name: String, abs: Boolean): Int {
                val fd = lib.open(node, Uinput.O_WRONLY or Uinput.O_NONBLOCK)
                check(fd >= 0) { "Can't open $node" }
                try {
                    call(fd, Uinput.UI_SET_EVBIT, Uinput.EV_SYN)
                    call(fd, Uinput.UI_SET_EVBIT, Uinput.EV_KEY)
                    for (b in Evdev.BTN_LEFT..Evdev.BTN_EXTRA) call(fd, Uinput.UI_SET_KEYBIT, b)
                    if (abs) {
                        call(fd, Uinput.UI_SET_EVBIT, Uinput.EV_ABS)
                        for (code in listOf(Uinput.ABS_X, Uinput.ABS_Y)) {
                            call(fd, Uinput.UI_SET_ABSBIT, code)
                            check(lib.ioctl(fd, NativeLong(Uinput.UI_ABS_SETUP.toLong()), Uinput.absSetup(code)) >= 0) { "UI_ABS_SETUP failed" }
                        }
                    } else {
                        Evdev.allKeys.forEach { call(fd, Uinput.UI_SET_KEYBIT, it) }
                        call(fd, Uinput.UI_SET_EVBIT, Uinput.EV_REL)
                        for (code in listOf(Uinput.REL_X, Uinput.REL_Y, Uinput.REL_WHEEL, Uinput.REL_HWHEEL)) call(fd, Uinput.UI_SET_RELBIT, code)
                    }
                    check(lib.ioctl(fd, NativeLong(Uinput.UI_DEV_SETUP.toLong()), Uinput.setup(name)) >= 0) { "UI_DEV_SETUP failed" }
                    call(fd, Uinput.UI_DEV_CREATE, 0)
                    return fd
                } catch (e: Exception) {
                    lib.close(fd)
                    throw e
                }
            }
            val relative = device("DyskLink keyboard and mouse", abs = false)
            val absolute = runCatching { device("DyskLink pointer", abs = true) }.getOrDefault(-1)
            // The desktop takes a moment to pick up new devices; events before that are lost.
            Thread.sleep(300)
            UinputBackend(lib, relative, absolute, displays)
        }.onFailure { CoreLog.w("DyskLink", "No /dev/uinput devices", it) }.getOrNull()
    }
}

/**
 * The phone's and a Mac's input on Wayland, by AWT key codes as everywhere else. Wayland doesn't tell apps where the
 * pointer is, so it's followed here; where it can't be placed exactly, it's first pushed into the top left corner
 * ([sync]) and moved from there.
 */
class WaylandInjector(
    private val backend: () -> WaylandBackend?,
    private val screens: () -> List<Rectangle> = ::awtDisplays,
) : InputInjector {
    private var tracked: Point? = null

    override val displays: List<Rectangle> get() = screens().ifEmpty { listOf(Rectangle(0, 0, 1920, 1080)) }

    override val pointer: Point
        @Synchronized get() = tracked ?: displays.first().let { Point(it.x + it.width / 2, it.y + it.height / 2) }.also { tracked = it }

    private fun ready(): WaylandBackend? = backend()?.takeIf { it.ready() }

    @Synchronized
    override fun sync() {
        val b = ready() ?: return
        if (b.canAbsolute) return
        b.relative(-HOME_PUSH, -HOME_PUSH)
        val box = displays.reduce { a, r -> a.union(r) }
        tracked = Point(box.x, box.y)
    }

    @Synchronized
    override fun moveTo(x: Int, y: Int) {
        val b = ready() ?: return
        if (!b.absolute(x, y)) {
            val from = pointer
            b.relative((x - from.x).toDouble(), (y - from.y).toDouble())
        }
        tracked = Point(x, y)
    }

    @Synchronized
    override fun moveBy(dx: Int, dy: Int) {
        val b = ready() ?: return
        b.relative(dx.toDouble(), dy.toDouble())
        val from = pointer
        tracked = EdgeMath.keepOnDisplays(Point(from.x + dx, from.y + dy), from, displays)
    }

    override fun button(button: Int, down: Boolean) {
        val code = Evdev.button(button) ?: return
        ready()?.button(code, down)
    }

    override fun wheel(notches: Int) { if (notches != 0) ready()?.scroll(false, notches) }
    override fun hwheel(notches: Int) { if (notches != 0) ready()?.scroll(true, notches) }

    override fun key(vk: Int, down: Boolean) {
        val code = Evdev.key(vk) ?: return
        ready()?.key(code, down)
    }

    /** Whether text can be typed by keysym now (the portal); otherwise it's pasted or typed by US keys. */
    val keysyms: Boolean get() = backend()?.keysyms == true

    /** Types [text] character by character by keysym. [Typed.Stopped] while permission is still asked for. */
    fun typeKeysyms(text: String): Typed {
        val b = ready() ?: return Typed.Stopped
        if (!b.keysyms) return Typed.Nothing
        var typed = 0
        return try {
            text.codePoints().forEach { cp ->
                val sym = X11Keys.keysymFor(cp).toInt()
                b.keysym(sym, true)
                b.keysym(sym, false)
                typed += Character.charCount(cp)
            }
            Typed.Done
        } catch (e: Exception) {
            CoreLog.w("DyskLink", "Keysym typing failed after $typed", e)
            if (typed == 0) Typed.Nothing else Typed.Partly(typed)
        }
    }

    companion object {
        const val HOME_PUSH = 100_000.0

        fun awtDisplays(): List<Rectangle> = runCatching {
            if (GraphicsEnvironment.isHeadless()) emptyList()
            else GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
        }.getOrDefault(emptyList())
    }
}

/**
 * Picks and keeps the Wayland way in: the portal when the desktop has RemoteDesktop for the pointer and keyboard,
 * else /dev/uinput when it can be written, else none. Probed once off the UI thread; [retry] asks the portal again.
 */
class WaylandInput(
    private val portal: () -> PortalClient?,
    private val uinputWritable: () -> Boolean = { Uinput.writable() },
    private val makeUinput: () -> WaylandBackend? = { UinputBackend.create(WaylandInjector::awtDisplays) },
    private val onChange: () -> Unit = {},
) {
    private val _route = MutableStateFlow<InputRoute?>(null)
    /** Null until probed. */
    val route: StateFlow<InputRoute?> = _route.asStateFlow()
    private val _status = MutableStateFlow(PortalStatus())
    val status: StateFlow<PortalStatus> = _status.asStateFlow()

    @Volatile private var backend: WaylandBackend? = null
    @Volatile var remoteDesktop: PortalOffer = PortalOffer(null)
        private set

    val injector = WaylandInjector({ backend })

    /** The portal backend, so the screen's session can lend it a stream for placing the pointer. */
    val portalBackend: PortalBackend? get() = backend as? PortalBackend

    @Synchronized
    fun probe(): InputRoute {
        _route.value?.let { return it }
        val client = portal()
        remoteDesktop = client?.offer(PortalBits.REMOTE_DESKTOP) ?: PortalOffer(null)
        val portalOk = remoteDesktop.version != null &&
            (remoteDesktop.types == 0 || remoteDesktop.types and (PortalBits.KEYBOARD or PortalBits.POINTER) == (PortalBits.KEYBOARD or PortalBits.POINTER))
        var route = InputRoutes.choose(wayland = true, robot = false, portal = { portalOk }, uinput = uinputWritable)
        backend = when (route) {
            InputRoute.PORTAL -> PortalBackend({ onClosed -> client!!.startRemoteDesktop(null, onClosed) }) { _status.value = it; onChange() }
            InputRoute.UINPUT -> makeUinput() ?: run { route = InputRoute.NONE; null }
            else -> null
        }
        CoreLog.i("DyskLink", "Wayland input: $route")
        _route.value = route
        onChange()
        return route
    }

    /** "Ask again" on the hint. */
    fun retry() { (backend as? PortalBackend)?.ask() }

    /** Checks /dev/uinput again after the user set it up, when nothing worked before. */
    fun recheck() {
        if (_route.value == InputRoute.NONE && uinputWritable()) {
            synchronized(this) { _route.value = null }
            probe()
        }
    }

    fun close() { backend?.close() }
}
