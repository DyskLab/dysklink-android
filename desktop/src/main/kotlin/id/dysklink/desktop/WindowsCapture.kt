package id.dysklink.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import id.phonelink.android.core.CoreLog
import java.awt.Point
import java.awt.Rectangle
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Windows: WH_MOUSE_LL and WH_KEYBOARD_LL on a thread of their own that runs a message loop. The callbacks only
 * decide through the [InputGate] and queue the event, so they stay well inside LowLevelHooksTimeout; Windows removes
 * a hook that's too slow anyway, which the source finds out with [probe] and installs again. While the pointer is on
 * the Mac it's held in a 1-pixel clip a little inside the edge, and every move is measured from there.
 */
class WindowsCapture : CaptureBackend {
    @Suppress("FunctionName")
    private interface User32 : StdCallLibrary {
        fun SetWindowsHookExW(id: Int, proc: HookProc, module: Pointer?, threadId: Int): Pointer?
        fun UnhookWindowsHookEx(hook: Pointer): Boolean
        fun CallNextHookEx(hook: Pointer?, code: Int, wParam: Pointer?, lParam: Pointer?): Pointer?
        fun GetMessageW(msg: Pointer, window: Pointer?, min: Int, max: Int): Int
        fun PostThreadMessageW(thread: Int, msg: Int, wParam: Pointer?, lParam: Pointer?): Boolean
        fun SetCursorPos(x: Int, y: Int): Boolean
        fun ClipCursor(rect: Pointer?): Boolean
        fun SendInput(count: Int, inputs: Pointer, size: Int): Int
        fun GetAsyncKeyState(vk: Int): Short
        fun EnumDisplayMonitors(hdc: Pointer?, clip: Pointer?, proc: MonitorProc, data: Pointer?): Boolean
        fun GetLastInputInfo(info: Pointer): Boolean
    }

    @Suppress("FunctionName")
    private interface Kernel32 : Library {
        fun GetModuleHandleW(name: WString?): Pointer?
        fun GetCurrentThreadId(): Int
        fun GetLastError(): Int
    }

    interface HookProc : StdCallLibrary.StdCallCallback {
        fun callback(code: Int, wParam: Pointer?, lParam: Pointer?): Pointer?
    }

    interface MonitorProc : StdCallLibrary.StdCallCallback {
        fun callback(monitor: Pointer?, hdc: Pointer?, rect: Pointer?, data: Pointer?): Boolean
    }

    private val user32: User32? by lazy { runCatching { Native.load("user32", User32::class.java) }.getOrNull() }
    private val kernel32: Kernel32? by lazy { runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull() }

    @Volatile private var threadId = 0
    @Volatile private var hookThread: Thread? = null
    @Volatile private var park: Point? = null
    private var gate: InputGate? = null
    private var sink: (HookEvent) -> Unit = {}
    private var mouseHook: Pointer? = null
    private var keyHook: Pointer? = null
    private var lastX = Int.MIN_VALUE
    private var lastY = Int.MIN_VALUE
    // Kept in fields: JNA frees a callback whose object is collected, and Windows would call freed memory.
    private val mouseProc = object : HookProc {
        override fun callback(code: Int, wParam: Pointer?, lParam: Pointer?): Pointer? = mouse(code, wParam, lParam)
    }
    private val keyProc = object : HookProc {
        override fun callback(code: Int, wParam: Pointer?, lParam: Pointer?): Pointer? = keyboard(code, wParam, lParam)
    }

    override val installed: Boolean get() = hookThread?.isAlive == true && mouseHook != null && keyHook != null

    private val monitors = CachedDisplays { enumMonitors() }
    /** Every pointer move asks; the monitors are looked up at most every 2 s. */
    override val displays: List<Rectangle> get() = monitors.get()

    private fun enumMonitors(): List<Rectangle> {
        val lib = user32 ?: return awtDisplays()
        val found = mutableListOf<Rectangle>()
        val proc = object : MonitorProc {
            override fun callback(monitor: Pointer?, hdc: Pointer?, rect: Pointer?, data: Pointer?): Boolean {
                rect?.let {
                    val left = it.getInt(0); val top = it.getInt(4); val right = it.getInt(8); val bottom = it.getInt(12)
                    found += Rectangle(left, top, right - left, bottom - top)
                }
                return true
            }
        }
        runCatching { lib.EnumDisplayMonitors(null, null, proc, null) }
        return found.ifEmpty { awtDisplays() }
    }

    override val probeHooks: Set<String> = setOf(MOUSE, KEYBOARD)

    override fun install(gate: InputGate, sink: (HookEvent) -> Unit): Boolean {
        val lib = user32 ?: return false
        val k = kernel32 ?: return false
        if (installed) return true
        uninstall()
        this.gate = gate
        this.sink = sink
        val ready = CountDownLatch(1)
        var ok = false
        hookThread = thread(isDaemon = true, name = "dysk-input-hooks", priority = Thread.MAX_PRIORITY) {
            threadId = k.GetCurrentThreadId()
            val module = k.GetModuleHandleW(null)
            mouseHook = lib.SetWindowsHookExW(WH_MOUSE_LL, mouseProc, module, 0)
            keyHook = lib.SetWindowsHookExW(WH_KEYBOARD_LL, keyProc, module, 0)
            ok = mouseHook != null && keyHook != null
            if (!ok) CoreLog.w("DyskLink", "SetWindowsHookEx failed: error ${k.GetLastError()}")
            ready.countDown()
            val msg = Memory(48)
            try {
                while (ok) {
                    if (lib.GetMessageW(msg, null, 0, 0) <= 0) break
                }
            } catch (error: Throwable) {
                CoreLog.w("DyskLink", "Hook thread", error)
                gate.crossed = false
                runCatching { sink(HookEvent.Failed(error)) }
            } finally {
                mouseHook?.let { runCatching { lib.UnhookWindowsHookEx(it) } }
                keyHook?.let { runCatching { lib.UnhookWindowsHookEx(it) } }
                mouseHook = null
                keyHook = null
                runCatching { lib.ClipCursor(null) }
            }
        }
        ready.await(2, TimeUnit.SECONDS)
        return ok
    }

    override fun uninstall() {
        gate?.crossed = false
        val lib = user32
        runCatching { lib?.ClipCursor(null) }
        park = null
        val id = threadId
        if (id != 0) runCatching { lib?.PostThreadMessageW(id, WM_QUIT, null, null) }
        runCatching { hookThread?.join(1_000) }
        hookThread = null
        threadId = 0
    }

    override fun capture(park: Point): Boolean {
        val lib = user32 ?: return false
        if (!installed) return false
        this.park = park
        lib.SetCursorPos(park.x, park.y)
        clip(park)
        return true
    }

    override fun keepParked() {
        val p = park ?: return
        runCatching {
            user32?.SetCursorPos(p.x, p.y)
            // Windows drops the clip when another window takes the focus.
            clip(p)
        }
    }

    override fun release(to: Point?) {
        park = null
        val lib = user32 ?: return
        runCatching { lib.ClipCursor(null) }
        to?.let { runCatching { lib.SetCursorPos(it.x, it.y) } }
        lastX = Int.MIN_VALUE
        resetModifiers(lib)
    }

    /**
     * A Shift, Ctrl, or Alt Windows thinks is down though it isn't held (its press reached Windows, its release went
     * nowhere): released, so nothing types with a modifier after coming home. Not the Windows key, whose lone
     * release would open Start.
     */
    private fun resetModifiers(lib: User32) {
        val held = gate?.held().orEmpty()
        val keys = listOf(0xA0 to MacKeys.SHIFT, 0xA1 to MacKeys.RIGHT_SHIFT, 0xA2 to MacKeys.CONTROL, 0xA3 to MacKeys.RIGHT_CONTROL, 0xA4 to MacKeys.OPTION, 0xA5 to MacKeys.RIGHT_OPTION)
        for ((vk, mac) in keys) {
            val down = runCatching { lib.GetAsyncKeyState(vk).toInt() and 0x8000 != 0 }.getOrDefault(false)
            if (down && mac !in held) {
                val bytes = WinCapture.keyInput(vk, up = true, extra = RESET_MAGIC, pointerSize = Native.POINTER_SIZE)
                val memory = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
                runCatching { lib.SendInput(1, memory, WinInput.inputSize(Native.POINTER_SIZE)) }
            }
        }
    }

    /**
     * GetLastInputInfo's tick. Whether SendInput moves it isn't documented: the probe is marked (dwExtraInfo
     * [PROBE_MAGIC], and LLMHF_INJECTED / LLKHF_INJECTED as all injected input) and swallowed by the hooks, but the
     * system may count it as input all the same. SharedInputSource doesn't rely on either: it reads this tick after
     * each probe's echo and probes again only when the tick moved since (or the hooks saw real input), so a PC left
     * alone locks its screen on time either way.
     */
    override fun lastInputTick(): Long? {
        val lib = user32 ?: return null
        // LASTINPUTINFO { UINT cbSize; DWORD dwTime; }
        val info = Memory(8).apply { setInt(0, 8); setInt(4, 0) }
        if (!runCatching { lib.GetLastInputInfo(info) }.getOrDefault(false)) return null
        return info.getInt(4).toLong() and 0xFFFFFFFFL
    }

    override fun probe() {
        val lib = user32 ?: return
        val size = WinInput.inputSize(Native.POINTER_SIZE)
        val bytes = WinCapture.mouseProbe(PROBE_MAGIC, Native.POINTER_SIZE) + WinCapture.keyInput(PROBE_VK, up = true, extra = PROBE_MAGIC, pointerSize = Native.POINTER_SIZE)
        val memory = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
        runCatching { lib.SendInput(2, memory, size) }
    }

    private fun clip(p: Point) {
        val rect = Memory(16).apply { setInt(0, p.x); setInt(4, p.y); setInt(8, p.x + 1); setInt(12, p.y + 1) }
        user32?.ClipCursor(rect)
    }

    private fun next(code: Int, wParam: Pointer?, lParam: Pointer?, hook: Pointer?): Pointer? =
        runCatching { user32?.CallNextHookEx(hook, code, wParam, lParam) }.getOrNull()

    private fun extraInfo(lParam: Pointer, offset64: Long, offset32: Long): Long =
        if (Native.POINTER_SIZE == 8) lParam.getLong(offset64) else lParam.getInt(offset32).toLong() and 0xFFFFFFFFL

    private fun mouse(code: Int, wParam: Pointer?, lParam: Pointer?): Pointer? {
        val gate = gate
        if (code < 0 || lParam == null || gate == null) return next(code, wParam, lParam, mouseHook)
        try {
            val msg = Pointer.nativeValue(wParam).toInt()
            val x = lParam.getInt(0)
            val y = lParam.getInt(4)
            val data = lParam.getInt(8)
            val flags = lParam.getInt(12)
            val extra = extraInfo(lParam, 24, 20)
            if (extra == PROBE_MAGIC) {
                sink(HookEvent.Probe(MOUSE))
                return SWALLOW
            }
            // Input posted by programs (DyskLink's own, as a Mac's target, among them) never crosses the edge.
            if (flags and LLMHF_INJECTED != 0 && !gate.crossed) return next(code, wParam, lParam, mouseHook)
            val verdict = when (msg) {
                WM_MOUSEMOVE -> {
                    val v = gate.motion()
                    val p = park
                    if (v.forward && p != null) {
                        sink(HookEvent.Motion(x, y, (x - p.x).toDouble(), (y - p.y).toDouble(), true))
                    } else if (!v.forward) {
                        val dx = if (lastX == Int.MIN_VALUE) 0 else x - lastX
                        val dy = if (lastX == Int.MIN_VALUE) 0 else y - lastY
                        lastX = x; lastY = y
                        sink(HookEvent.Motion(x, y, dx.toDouble(), dy.toDouble(), false))
                    }
                    v
                }
                WM_MOUSEWHEEL, WM_MOUSEHWHEEL -> {
                    val v = gate.motion()
                    val notches = (data shr 16).toShort() / 120.0
                    if (v.forward) sink(if (msg == WM_MOUSEWHEEL) HookEvent.Wheel(0.0, notches, true) else HookEvent.Wheel(notches, 0.0, true))
                    v
                }
                else -> {
                    val (button, down) = WinCapture.button(msg, data) ?: return next(code, wParam, lParam, mouseHook)
                    val v = gate.button(button, down)
                    if (v.forward) sink(HookEvent.Button(button, down, true))
                    v
                }
            }
            return if (verdict.swallow) SWALLOW else next(code, wParam, lParam, mouseHook)
        } catch (error: Throwable) {
            gate.crossed = false
            runCatching { sink(HookEvent.Failed(error)) }
            return next(code, wParam, lParam, mouseHook)
        }
    }

    private fun keyboard(code: Int, wParam: Pointer?, lParam: Pointer?): Pointer? {
        val gate = gate
        if (code < 0 || lParam == null || gate == null) return next(code, wParam, lParam, keyHook)
        try {
            val vk = lParam.getInt(0)
            val scan = lParam.getInt(4)
            val flags = lParam.getInt(8)
            val extra = extraInfo(lParam, 16, 16)
            if (extra == PROBE_MAGIC) {
                sink(HookEvent.Probe(KEYBOARD))
                return SWALLOW
            }
            if (extra == RESET_MAGIC) return next(code, wParam, lParam, keyHook)
            if (flags and LLKHF_INJECTED != 0 && !gate.crossed) return next(code, wParam, lParam, keyHook)
            val down = flags and LLKHF_UP == 0
            val mac = PcKeys.fromWindows(vk, scan, flags and LLKHF_EXTENDED != 0)
            if (mac == PcKeys.IGNORE) return if (gate.crossed) SWALLOW else next(code, wParam, lParam, keyHook)
            // The escape combo is seen here, before anything is swallowed, so it works even if the worker is stuck.
            val verdict = gate.key(mac ?: (InputGate.UNKNOWN_BASE + vk), down)
            when {
                verdict == Verdict.ESCAPE -> {
                    runCatching { user32?.ClipCursor(null) }
                    sink(HookEvent.Escape)
                }
                verdict.forward -> sink(HookEvent.Key(mac, down, true))
            }
            return if (verdict.swallow) SWALLOW else next(code, wParam, lParam, keyHook)
        } catch (error: Throwable) {
            gate.crossed = false
            runCatching { sink(HookEvent.Failed(error)) }
            return next(code, wParam, lParam, keyHook)
        }
    }

    private companion object {
        const val WH_KEYBOARD_LL = 13
        const val WH_MOUSE_LL = 14
        const val WM_QUIT = 0x0012
        const val WM_MOUSEMOVE = 0x0200
        const val WM_MOUSEWHEEL = 0x020A
        const val WM_MOUSEHWHEEL = 0x020E
        const val LLMHF_INJECTED = 0x01
        const val LLKHF_EXTENDED = 0x01
        const val LLKHF_INJECTED = 0x10
        const val LLKHF_UP = 0x80
        const val MOUSE = "mouse"
        const val KEYBOARD = "keyboard"
        /** dwExtraInfo of DyskLink's probes ("DYKP") and of its modifier resets ("DYKR"); 32 bits, as on 32-bit Windows. */
        const val PROBE_MAGIC = 0x44594B50L
        const val RESET_MAGIC = 0x44594B52L
        /** An unassigned virtual key, released only, for the keyboard probe. */
        const val PROBE_VK = 0xE8
        val SWALLOW: Pointer = Pointer(1)

        fun awtDisplays(): List<Rectangle> = runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { it.defaultConfiguration.bounds }
        }.getOrDefault(emptyList())
    }
}

/** The pure parts of [WindowsCapture], tested on any OS. */
object WinCapture {
    /** A mouse message to a Mac button number and whether it went down, or null for a move or the wheel. */
    fun button(msg: Int, mouseData: Int): Pair<Int, Boolean>? = when (msg) {
        0x0201 -> 0 to true
        0x0202 -> 0 to false
        0x0204 -> 1 to true
        0x0205 -> 1 to false
        0x0207 -> 2 to true
        0x0208 -> 2 to false
        0x020B, 0x020C -> {
            // XBUTTON1 is back, XBUTTON2 forward: 3 and 4 on a Mac.
            val which = (mouseData shr 16) and 0xFFFF
            val number = if (which == 2) 4 else 3
            number to (msg == 0x020B)
        }
        else -> null
    }

    /** A relative move of nothing, marked with [extra], which only the hooks see. */
    fun mouseProbe(extra: Long, pointerSize: Int): ByteArray {
        val buffer = ByteBuffer.allocate(WinInput.inputSize(pointerSize)).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, 0) // INPUT_MOUSE
        val union = pointerSize
        buffer.putInt(union + 12, 0x0001) // MOUSEEVENTF_MOVE, dx and dy 0
        if (pointerSize == 8) buffer.putLong(union + 24, extra) else buffer.putInt(union + 20, extra.toInt())
        return buffer.array()
    }

    /** A key INPUT for [vk], marked with [extra]. */
    fun keyInput(vk: Int, up: Boolean, extra: Long, pointerSize: Int): ByteArray {
        val buffer = ByteBuffer.allocate(WinInput.inputSize(pointerSize)).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0, WinInput.INPUT_KEYBOARD)
        val union = pointerSize
        buffer.putShort(union, vk.toShort())
        buffer.putInt(union + 4, if (up) WinInput.KEYEVENTF_KEYUP else 0)
        if (pointerSize == 8) buffer.putLong(union + 16, extra) else buffer.putInt(union + 12, extra.toInt())
        return buffer.array()
    }
}
