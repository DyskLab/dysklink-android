package id.dysklink.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import id.phonelink.android.core.CoreLog
import id.phonelink.android.core.Envelope
import id.phonelink.android.core.MessageType
import id.phonelink.android.core.RemoteEvent
import id.phonelink.android.core.RemoteLimits
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.event.InputEvent as AwtInput
import java.awt.event.KeyEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

// A Mac's mouse and keyboard on this PC: the Mac pushes its pointer past the edge it chose for this PC, and sends
// `remote.input` until the pointer comes back past the same edge here (see PROTOCOL.md, One mouse and keyboard).
// This PC is only ever the target; it doesn't push its own pointer into a Mac.

/** Mac virtual key codes (US key positions) to AWT key codes, as Robot presses them. */
object MacKeys {
    const val COMMAND = 55
    const val SHIFT = 56
    const val CAPS_LOCK = 57
    const val OPTION = 58
    const val CONTROL = 59
    const val RIGHT_COMMAND = 54
    const val RIGHT_SHIFT = 60
    const val RIGHT_OPTION = 61
    const val RIGHT_CONTROL = 62
    const val FN = 63
    /** Modifier keys: they come as `flags` and are pressed from the flags, never as keys. */
    val MODIFIERS = setOf(COMMAND, SHIFT, CAPS_LOCK, OPTION, CONTROL, RIGHT_COMMAND, RIGHT_SHIFT, RIGHT_OPTION, RIGHT_CONTROL, FN)

    private val table: Map<Int, Int> = buildMap {
        val letters = mapOf(
            0 to 'A', 1 to 'S', 2 to 'D', 3 to 'F', 4 to 'H', 5 to 'G', 6 to 'Z', 7 to 'X', 8 to 'C', 9 to 'V',
            11 to 'B', 12 to 'Q', 13 to 'W', 14 to 'E', 15 to 'R', 16 to 'Y', 17 to 'T', 31 to 'O', 32 to 'U',
            34 to 'I', 35 to 'P', 37 to 'L', 38 to 'J', 40 to 'K', 45 to 'N', 46 to 'M',
        )
        letters.forEach { (code, letter) -> put(code, KeyEvent.VK_A + (letter - 'A')) }
        val digits = mapOf(18 to 1, 19 to 2, 20 to 3, 21 to 4, 22 to 6, 23 to 5, 25 to 9, 26 to 7, 28 to 8, 29 to 0)
        digits.forEach { (code, digit) -> put(code, KeyEvent.VK_0 + digit) }
        put(24, KeyEvent.VK_EQUALS); put(27, KeyEvent.VK_MINUS); put(30, KeyEvent.VK_CLOSE_BRACKET); put(33, KeyEvent.VK_OPEN_BRACKET)
        put(39, KeyEvent.VK_QUOTE); put(41, KeyEvent.VK_SEMICOLON); put(42, KeyEvent.VK_BACK_SLASH); put(43, KeyEvent.VK_COMMA)
        put(44, KeyEvent.VK_SLASH); put(47, KeyEvent.VK_PERIOD); put(50, KeyEvent.VK_BACK_QUOTE)
        put(36, KeyEvent.VK_ENTER); put(48, KeyEvent.VK_TAB); put(49, KeyEvent.VK_SPACE); put(51, KeyEvent.VK_BACK_SPACE)
        put(53, KeyEvent.VK_ESCAPE); put(117, KeyEvent.VK_DELETE); put(114, KeyEvent.VK_INSERT)
        put(115, KeyEvent.VK_HOME); put(119, KeyEvent.VK_END); put(116, KeyEvent.VK_PAGE_UP); put(121, KeyEvent.VK_PAGE_DOWN)
        put(123, KeyEvent.VK_LEFT); put(124, KeyEvent.VK_RIGHT); put(125, KeyEvent.VK_DOWN); put(126, KeyEvent.VK_UP)
        val functions = mapOf(
            122 to 1, 120 to 2, 99 to 3, 118 to 4, 96 to 5, 97 to 6, 98 to 7, 100 to 8, 101 to 9, 109 to 10,
            103 to 11, 111 to 12, 105 to 13, 107 to 14, 113 to 15, 106 to 16, 64 to 17, 79 to 18, 80 to 19, 90 to 20,
        )
        functions.forEach { (code, n) -> put(code, if (n <= 12) KeyEvent.VK_F1 + n - 1 else KeyEvent.VK_F13 + n - 13) }
        val keypad = mapOf(82 to 0, 83 to 1, 84 to 2, 85 to 3, 86 to 4, 87 to 5, 88 to 6, 89 to 7, 91 to 8, 92 to 9)
        keypad.forEach { (code, n) -> put(code, KeyEvent.VK_NUMPAD0 + n) }
        put(65, KeyEvent.VK_DECIMAL); put(67, KeyEvent.VK_MULTIPLY); put(69, KeyEvent.VK_ADD); put(75, KeyEvent.VK_DIVIDE)
        put(78, KeyEvent.VK_SUBTRACT); put(76, KeyEvent.VK_ENTER); put(81, KeyEvent.VK_EQUALS); put(71, KeyEvent.VK_NUM_LOCK)
        // The key left of 1 on ISO keyboards, and the JIS keys, where a PC keyboard has them.
        put(10, KeyEvent.VK_LESS)
    }

    /** The PC key for a Mac key code, or null for a modifier or a key a PC doesn't have (volume keys, for one). */
    fun vk(code: Int): Int? = if (code in MODIFIERS) null else table[code]
}

/** CGEventFlags bits, as the Mac sends them in `flags`. */
object MacFlags {
    const val CAPS_LOCK = 0x10000L
    const val SHIFT = 0x20000L
    const val CONTROL = 0x40000L
    const val OPTION = 0x80000L
    const val COMMAND = 0x100000L

    /**
     * The PC modifier keys the Mac's [flags] hold down. With [commandAsCtrl] (the default), Command is Ctrl, so
     * Command-C copies here as it does on the Mac; otherwise Command is the Windows (Super) key. Control is always
     * Ctrl, and Option is Alt.
     */
    fun keys(flags: Long, commandAsCtrl: Boolean): Set<Int> = buildSet {
        if (flags and SHIFT != 0L) add(KeyEvent.VK_SHIFT)
        if (flags and CONTROL != 0L) add(KeyEvent.VK_CONTROL)
        if (flags and OPTION != 0L) add(KeyEvent.VK_ALT)
        if (flags and COMMAND != 0L) add(if (commandAsCtrl) KeyEvent.VK_CONTROL else KeyEvent.VK_WINDOWS)
    }
}

enum class ScreenEdge {
    LEFT, RIGHT, TOP, BOTTOM;

    val wire: String get() = name.lowercase()

    /** Whether a movement pushes toward this edge. */
    fun pushes(dx: Double, dy: Double): Boolean = when (this) {
        LEFT -> dx < 0
        RIGHT -> dx > 0
        TOP -> dy < 0
        BOTTOM -> dy > 0
    }

    companion object {
        fun of(wire: String?): ScreenEdge? = entries.firstOrNull { it.wire == wire }
    }
}

/** The Mac's EdgeMath, on this PC's displays (in AWT's virtual screen coordinates). */
object EdgeMath {
    /** The display whose outer [edge] [point] touches, with nothing beyond it, and how far along it is. */
    fun hit(point: Point, edge: ScreenEdge, displays: List<Rectangle>): Pair<Rectangle, Double>? {
        val display = displays.firstOrNull { Rectangle(it.x - 1, it.y - 1, it.width + 2, it.height + 2).contains(point) } ?: return null
        val (touching, beyond) = when (edge) {
            ScreenEdge.LEFT -> (point.x <= display.x) to Point(display.x - 2, point.y)
            ScreenEdge.RIGHT -> (point.x >= display.x + display.width - 1) to Point(display.x + display.width + 1, point.y)
            ScreenEdge.TOP -> (point.y <= display.y) to Point(point.x, display.y - 2)
            ScreenEdge.BOTTOM -> (point.y >= display.y + display.height - 1) to Point(point.x, display.y + display.height + 1)
        }
        if (!touching || displays.any { it.contains(beyond) }) return null
        return display to fraction(point, edge, display)
    }

    fun fraction(point: Point, edge: ScreenEdge, display: Rectangle): Double {
        val value = when (edge) {
            ScreenEdge.LEFT, ScreenEdge.RIGHT -> (point.y - display.y).toDouble() / maxOf(display.height, 1)
            ScreenEdge.TOP, ScreenEdge.BOTTOM -> (point.x - display.x).toDouble() / maxOf(display.width, 1)
        }
        return value.coerceIn(0.0, 1.0)
    }

    /** The outermost display on [edge], where a pointer coming from that side appears. */
    fun outermost(edge: ScreenEdge, displays: List<Rectangle>): Rectangle? = when (edge) {
        ScreenEdge.LEFT -> displays.minWithOrNull(compareBy<Rectangle> { it.x }.thenBy { it.y })
        ScreenEdge.RIGHT -> displays.maxWithOrNull(compareBy<Rectangle> { it.x + it.width }.thenBy { -it.y })
        ScreenEdge.TOP -> displays.minWithOrNull(compareBy<Rectangle> { it.y }.thenBy { it.x })
        ScreenEdge.BOTTOM -> displays.maxWithOrNull(compareBy<Rectangle> { it.y + it.height }.thenBy { -it.x })
    }

    /** The point just inside [edge] of [display], [at] of the way along it. */
    fun point(edge: ScreenEdge, display: Rectangle, at: Double, inset: Int = 2): Point {
        val along = at.coerceIn(0.0, 1.0)
        return when (edge) {
            ScreenEdge.LEFT -> Point(display.x + inset, display.y + (along * (display.height - 1)).roundToInt())
            ScreenEdge.RIGHT -> Point(display.x + display.width - 1 - inset, display.y + (along * (display.height - 1)).roundToInt())
            ScreenEdge.TOP -> Point(display.x + (along * (display.width - 1)).roundToInt(), display.y + inset)
            ScreenEdge.BOTTOM -> Point(display.x + (along * (display.width - 1)).roundToInt(), display.y + display.height - 1 - inset)
        }
    }

    /** [target] if it's on a display, else [from]'s display's nearest point to it, so the pointer never gets lost. */
    fun keepOnDisplays(target: Point, from: Point, displays: List<Rectangle>): Point {
        if (displays.isEmpty() || displays.any { it.contains(target) }) return target
        val display = displays.firstOrNull { it.contains(from) } ?: displays.minBy { distance(it, target) }
        return Point(target.x.coerceIn(display.x, display.x + display.width - 1), target.y.coerceIn(display.y, display.y + display.height - 1))
    }

    private fun distance(r: Rectangle, p: Point): Long {
        val dx = maxOf(r.x - p.x, 0, p.x - (r.x + r.width - 1)).toLong()
        val dy = maxOf(r.y - p.y, 0, p.y - (r.y + r.height - 1)).toLong()
        return dx * dx + dy * dy
    }
}

/** What posts the Mac's input on this PC. Robot in the app; a recorder in tests. */
interface InputInjector {
    val displays: List<Rectangle>
    val pointer: Point?
    fun moveTo(x: Int, y: Int)
    /** 0 left, 1 right, 2 middle, 3 and 4 back and forward. */
    fun button(button: Int, down: Boolean)
    /** Wheel notches; positive scrolls down, as a wheel turned toward you. */
    fun wheel(notches: Int)
    /** Positive scrolls right. */
    fun hwheel(notches: Int) {}
    fun key(vk: Int, down: Boolean)
}

/**
 * This PC used from a Mac. The Mac sends `remote.enter` with the edge where the pointer appears here, then
 * `remote.input`; pushing the pointer twice past that edge here sends `remote.leave` and hands control back.
 * Anything held (keys, buttons, modifiers) is let go when control ends, however it ends.
 */
class SharedInputTarget(
    private val injector: InputInjector,
    /** Sends to the Mac in control. */
    private val send: (String, Envelope) -> Unit,
    private val commandAsCtrl: () -> Boolean = { true },
    private val onChange: (String?) -> Unit = {},
) {
    @Volatile var controller: String? = null
        private set
    private var homeEdge = ScreenEdge.LEFT
    private val buttons = HashSet<Int>()
    private val keysDown = HashSet<Int>()
    private val modifiers = HashSet<Int>()
    private var pushes = 0
    private var restX = 0.0
    private var restY = 0.0
    private var scrollRestX = 0.0
    private var scrollRestY = 0.0

    @Synchronized
    fun enter(peer: String, edge: ScreenEdge, at: Double) {
        controller?.takeIf { it != peer }?.let { send(it, Envelope(MessageType.REMOTE_EXIT)) }
        releaseHeld()
        controller = peer
        homeEdge = edge
        pushes = 0
        val display = EdgeMath.outermost(edge, injector.displays)
        if (display != null) {
            val point = EdgeMath.point(edge, display, RemoteLimits.fraction(at))
            injector.moveTo(point.x, point.y)
        }
        CoreLog.i("DyskLink", "REMOTE_ENTER ${edge.wire}")
        onChange(peer)
    }

    @Synchronized
    fun handle(event: RemoteEvent, peer: String) {
        if (controller != peer) return
        val clean = RemoteLimits.sanitize(event) ?: return
        when (clean.kind) {
            "move" -> move(clean.dx ?: 0.0, clean.dy ?: 0.0)
            "down" -> button(clean.button ?: 0, true)
            "up" -> button(clean.button ?: 0, false)
            "scroll" -> scroll(clean.dx ?: 0.0, clean.dy ?: 0.0)
            "key" -> key(clean.keyCode ?: 0, clean.keyDown ?: true, clean.flags ?: 0)
            "flags" -> flags(clean.keyCode ?: 0, clean.flags ?: 0)
        }
    }

    /** Control ends: the Mac said so, the setting went off, or (with [notify]) it's ended here and the Mac is told. */
    @Synchronized
    fun release(notify: Boolean = false) {
        val peer = controller ?: return
        releaseHeld()
        controller = null
        if (notify) send(peer, Envelope(MessageType.REMOTE_EXIT))
        CoreLog.i("DyskLink", "REMOTE_RELEASED")
        onChange(null)
    }

    /** [peer] disconnected or was unpaired. */
    fun gone(peer: String) {
        if (controller == peer) release()
    }

    private fun releaseHeld() {
        buttons.toList().forEach { runCatching { injector.button(it, false) } }
        keysDown.toList().forEach { runCatching { injector.key(it, false) } }
        modifiers.toList().forEach { runCatching { injector.key(it, false) } }
        buttons.clear(); keysDown.clear(); modifiers.clear()
        restX = 0.0; restY = 0.0; scrollRestX = 0.0; scrollRestY = 0.0
    }

    private fun move(dx: Double, dy: Double) {
        val from = injector.pointer ?: return
        val displays = injector.displays
        // Pushing past the edge facing the Mac sends the pointer home.
        val hit = if (homeEdge.pushes(dx, dy)) EdgeMath.hit(from, homeEdge, displays) else null
        if (hit != null) {
            if (++pushes >= 2) {
                val peer = controller ?: return
                send(peer, Envelope(MessageType.REMOTE_LEAVE, remote = RemoteEvent("leave", edge = homeEdge.wire, at = hit.second)))
                releaseHeld()
                controller = null
                pushes = 0
                CoreLog.i("DyskLink", "REMOTE_LEAVE ${homeEdge.wire}")
                onChange(null)
                return
            }
        } else {
            pushes = 0
        }
        // Slow moves come in fractions of a pixel; the rest waits for the next one.
        restX += dx
        restY += dy
        val stepX = restX.roundToInt()
        val stepY = restY.roundToInt()
        restX -= stepX
        restY -= stepY
        if (stepX == 0 && stepY == 0) return
        val target = EdgeMath.keepOnDisplays(Point(from.x + stepX, from.y + stepY), from, displays)
        injector.moveTo(target.x, target.y)
    }

    private fun button(number: Int, down: Boolean) {
        if (down) buttons += number else if (!buttons.remove(number)) return
        injector.button(number, down)
    }

    private fun scroll(dx: Double, dy: Double) {
        // Pixels from the Mac, up as positive; a wheel notch is about [PIXELS_PER_NOTCH] of them.
        scrollRestY -= dy
        scrollRestX -= dx
        val notches = (scrollRestY / PIXELS_PER_NOTCH).toInt()
        if (notches != 0) {
            injector.wheel(notches)
            scrollRestY -= notches * PIXELS_PER_NOTCH
        }
        val side = (scrollRestX / PIXELS_PER_NOTCH).toInt()
        if (side != 0) {
            injector.hwheel(side)
            scrollRestX -= side * PIXELS_PER_NOTCH
        }
    }

    private fun key(code: Int, down: Boolean, flags: Long) {
        syncModifiers(flags)
        val vk = MacKeys.vk(code) ?: return
        if (down) {
            keysDown += vk
            injector.key(vk, true)
        } else if (keysDown.remove(vk)) {
            injector.key(vk, false)
        }
    }

    private fun flags(code: Int, flags: Long) {
        // Caps Lock toggles on the Mac with each press, and comes as one flags change: one tap here.
        if (code == MacKeys.CAPS_LOCK) {
            injector.key(KeyEvent.VK_CAPS_LOCK, true)
            injector.key(KeyEvent.VK_CAPS_LOCK, false)
        }
        syncModifiers(flags)
    }

    /** Presses and lets go of modifier keys until they match what the Mac holds. */
    private fun syncModifiers(flags: Long) {
        val wanted = MacFlags.keys(flags, commandAsCtrl())
        (modifiers - wanted).forEach { injector.key(it, false) }
        (wanted - modifiers).forEach { injector.key(it, true) }
        modifiers.clear()
        modifiers += wanted
    }

    companion object {
        const val PIXELS_PER_NOTCH = 24.0
    }
}

/** Robot for the pointer and keys, with the side wheel through SendInput on Windows and XTest on X11. */
class RobotInjector(private val robot: Robot) : InputInjector {
    override val displays: List<Rectangle>
        get() = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
        }.getOrDefault(emptyList())

    override val pointer: Point? get() = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()

    override fun moveTo(x: Int, y: Int) = robot.mouseMove(x, y)

    override fun button(button: Int, down: Boolean) {
        val mask = when (button) {
            0 -> AwtInput.BUTTON1_DOWN_MASK
            1 -> AwtInput.BUTTON3_DOWN_MASK
            2 -> AwtInput.BUTTON2_DOWN_MASK
            else -> runCatching { AwtInput.getMaskForButton(button + 1) }.getOrNull() ?: return
        }
        runCatching { if (down) robot.mousePress(mask) else robot.mouseRelease(mask) }
    }

    override fun wheel(notches: Int) = robot.mouseWheel(notches)

    override fun hwheel(notches: Int) {
        if (Platform.isWindows) SideWheel.windows(notches) else SideWheel.x11(notches)
    }

    override fun key(vk: Int, down: Boolean) {
        // A key this keyboard layout doesn't have is skipped rather than ending the whole event.
        runCatching { if (down) robot.keyPress(vk) else robot.keyRelease(vk) }
    }
}

/** The side wheel, which Robot doesn't have. */
private object SideWheel {
    @Suppress("FunctionName")
    private interface User32 : Library { fun SendInput(count: Int, inputs: Pointer, size: Int): Int }

    @Suppress("FunctionName")
    private interface XLib : Library {
        fun XOpenDisplay(name: String?): Pointer?
        fun XFlush(display: Pointer): Int
    }

    @Suppress("FunctionName")
    private interface XTest : Library { fun XTestFakeButtonEvent(display: Pointer, button: Int, press: Int, delay: NativeLong): Int }

    private val user32: User32? by lazy { runCatching { Native.load("user32", User32::class.java) }.getOrNull() }
    private val x11: Triple<XLib, XTest, Pointer>? by lazy {
        runCatching {
            val x = Native.load("X11", XLib::class.java)
            val t = Native.load("Xtst", XTest::class.java)
            Triple(x, t, x.XOpenDisplay(null) ?: error("No X display"))
        }.onFailure { CoreLog.w("DyskLink", "No side wheel", it) }.getOrNull()
    }

    fun windows(notches: Int) {
        val lib = user32 ?: return
        val bytes = WinInput.mouseWheel(notches * 120, horizontal = true, pointerSize = Native.POINTER_SIZE)
        val memory = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
        lib.SendInput(1, memory, WinInput.inputSize(Native.POINTER_SIZE))
    }

    @Synchronized
    fun x11(notches: Int) {
        val (x, t, display) = x11 ?: return
        // Buttons 6 and 7 are the side wheel, left and right.
        val button = if (notches < 0) 6 else 7
        repeat(minOf(kotlin.math.abs(notches), 20)) {
            t.XTestFakeButtonEvent(display, button, 1, NativeLong(0))
            t.XTestFakeButtonEvent(display, button, 0, NativeLong(0))
        }
        x.XFlush(display)
    }
}

/** A mouse wheel INPUT for SendInput. */
internal fun WinInput.mouseWheel(delta: Int, horizontal: Boolean, pointerSize: Int): ByteArray {
    val buffer = ByteBuffer.allocate(inputSize(pointerSize)).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(0, 0) // INPUT_MOUSE
    val union = pointerSize
    // MOUSEINPUT: dx, dy, mouseData, dwFlags, time, dwExtraInfo.
    buffer.putInt(union + 8, delta)
    buffer.putInt(union + 12, if (horizontal) 0x1000 else 0x0800)
    return buffer.array()
}
