package id.dysklink.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import id.phonelink.android.core.CoreLog
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * X11: XInput2 raw events on the root window show where the pointer goes and which keys are held, without taking
 * anything from other apps. While the pointer is on the Mac, the pointer and keyboard are grabbed on the root window
 * with a blank cursor, the pointer is warped back to its place after every move, and the moves are measured from
 * there. One thread owns the display connection; other threads hand it work. The X server ends the grabs itself
 * when the connection closes, so a crash can't leave the PC without input.
 */
class X11Capture : CaptureBackend {
    @Suppress("FunctionName")
    private interface X11 : Library {
        fun XOpenDisplay(name: String?): Pointer?
        fun XCloseDisplay(display: Pointer): Int
        fun XDefaultRootWindow(display: Pointer): NativeLong
        fun XQueryExtension(display: Pointer, name: String, opcode: IntByReference, event: IntByReference, error: IntByReference): Int
        fun XPending(display: Pointer): Int
        fun XNextEvent(display: Pointer, event: Pointer): Int
        fun XGetEventData(display: Pointer, cookie: Pointer): Int
        fun XFreeEventData(display: Pointer, cookie: Pointer)
        fun XQueryPointer(
            display: Pointer, window: NativeLong, root: Pointer, child: Pointer, rootX: IntByReference, rootY: IntByReference,
            winX: IntByReference, winY: IntByReference, mask: IntByReference,
        ): Int
        fun XGrabPointer(
            display: Pointer, window: NativeLong, ownerEvents: Int, eventMask: Int, pointerMode: Int, keyboardMode: Int,
            confineTo: NativeLong, cursor: NativeLong, time: NativeLong,
        ): Int
        fun XUngrabPointer(display: Pointer, time: NativeLong): Int
        fun XGrabKeyboard(display: Pointer, window: NativeLong, ownerEvents: Int, pointerMode: Int, keyboardMode: Int, time: NativeLong): Int
        fun XUngrabKeyboard(display: Pointer, time: NativeLong): Int
        fun XWarpPointer(display: Pointer, src: NativeLong, dest: NativeLong, srcX: Int, srcY: Int, srcW: Int, srcH: Int, x: Int, y: Int): Int
        fun XCreateBitmapFromData(display: Pointer, drawable: NativeLong, data: Pointer, width: Int, height: Int): NativeLong
        fun XCreatePixmapCursor(display: Pointer, source: NativeLong, mask: NativeLong, fg: Pointer, bg: Pointer, x: Int, y: Int): NativeLong
        fun XFreePixmap(display: Pointer, pixmap: NativeLong): Int
        fun XFlush(display: Pointer): Int
        fun XConnectionNumber(display: Pointer): Int
    }

    @Suppress("FunctionName")
    private interface Xi : Library {
        fun XIQueryVersion(display: Pointer, major: IntByReference, minor: IntByReference): Int
        fun XISelectEvents(display: Pointer, window: NativeLong, masks: Pointer, count: Int): Int
    }

    private val x: X11? by lazy {
        runCatching { Native.load("X11", X11::class.java) }.recoverCatching { Native.load("libX11.so.6", X11::class.java) }.getOrNull()
    }
    private val xi: Xi? by lazy {
        runCatching { Native.load("Xi", Xi::class.java) }.recoverCatching { Native.load("libXi.so.6", Xi::class.java) }.getOrNull()
    }

    @Volatile private var running = false
    @Volatile private var loop: Thread? = null
    private val work = ConcurrentLinkedQueue<() -> Unit>()
    private var gate: InputGate? = null
    private var sink: (HookEvent) -> Unit = {}
    // Only touched on the X thread.
    private var display: Pointer? = null
    private var root = NativeLong(0)
    private var blank = NativeLong(0)
    private var opcode = -1
    private var park: Point? = null
    private var grabbed = false
    /** Where the pointer is, asked of the X server at most [PointerThrottle.QUERY_MS] apart. */
    private val pointerAt = PointerThrottle()
    /** Blocks the X thread while there's nothing to do; work handed over wakes it. */
    @Volatile private var idle: IdleWait? = null

    override val installed: Boolean get() = running && loop?.isAlive == true

    /** AWT's monitors in device pixels, as X reports the pointer. */
    private val monitors = CachedDisplays { awtMonitors() }
    override val displays: List<Rectangle> get() = monitors.get()

    private fun awtMonitors(): List<Rectangle> = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map {
            val c = it.defaultConfiguration
            val b = c.bounds
            val sx = c.defaultTransform.scaleX
            val sy = c.defaultTransform.scaleY
            Rectangle((b.x * sx).toInt(), (b.y * sy).toInt(), (b.width * sx).toInt(), (b.height * sy).toInt())
        }
    }.getOrDefault(emptyList())

    /** Whether XInput2 answers, for the hint. Opens and closes a display connection. */
    fun available(): Boolean {
        val x = x ?: return false
        val xi = xi ?: return false
        val d = runCatching { x.XOpenDisplay(null) }.getOrNull() ?: return false
        return try {
            x.XQueryExtension(d, "XInputExtension", IntByReference(), IntByReference(), IntByReference()) != 0 &&
                xi.XIQueryVersion(d, IntByReference(2), IntByReference(0)) == 0
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { x.XCloseDisplay(d) }
        }
    }

    override fun install(gate: InputGate, sink: (HookEvent) -> Unit): Boolean {
        val x = x ?: return false
        val xi = xi ?: return false
        if (installed) return true
        uninstall()
        this.gate = gate
        this.sink = sink
        val ready = CountDownLatch(1)
        var ok = false
        running = true
        loop = thread(isDaemon = true, name = "dysk-input-x11", priority = Thread.MAX_PRIORITY) {
            val d = runCatching { x.XOpenDisplay(null) }.getOrNull()
            try {
                if (d == null) return@thread
                display = d
                root = x.XDefaultRootWindow(d)
                val op = IntByReference()
                if (x.XQueryExtension(d, "XInputExtension", op, IntByReference(), IntByReference()) == 0) return@thread
                if (xi.XIQueryVersion(d, IntByReference(2), IntByReference(0)) != 0) return@thread
                opcode = op.value
                selectRaw(xi, d)
                blank = blankCursor(x, d)
                x.XFlush(d)
                ok = true
                ready.countDown()
                val event = Memory(24L * NativeLong.SIZE)
                val wait = IdleWait.open()
                idle = wait
                val fd = x.XConnectionNumber(d)
                while (running) {
                    var busy = false
                    while (true) { val job = work.poll() ?: break; busy = true; job() }
                    while (running && x.XPending(d) > 0) {
                        busy = true
                        x.XNextEvent(d, event)
                        handle(x, d, event)
                    }
                    // XPending just read the socket and found nothing queued: sleep in poll() until the X server
                    // writes, work is handed over, or 100 ms pass. Without libc, the old short sleep.
                    if (!busy) { if (wait == null) Thread.sleep(2) else wait.await(fd, IdleWait.TIMEOUT_MS) }
                }
            } catch (_: InterruptedException) {
            } catch (error: Throwable) {
                CoreLog.w("DyskLink", "X11 input thread", error)
                gate.crossed = false
                runCatching { sink(HookEvent.Failed(error)) }
            } finally {
                ready.countDown()
                if (d != null) {
                    runCatching { ungrab(x, d, null) }
                    runCatching { x.XCloseDisplay(d) }
                }
                display = null
                running = false
                idle?.close()
                idle = null
            }
        }
        ready.await(2, TimeUnit.SECONDS)
        return ok
    }

    override fun uninstall() {
        gate?.crossed = false
        running = false
        idle?.wake()
        runCatching { loop?.join(1_000) }
        loop = null
    }

    /** Runs [job] on the X thread and waits for it, at most half a second. */
    private fun <T> onX(fallback: T, job: () -> T): T {
        if (Thread.currentThread() === loop) return job()
        if (!installed) return fallback
        var result = fallback
        val done = CountDownLatch(1)
        work += { try { result = job() } finally { done.countDown() } }
        idle?.wake()
        done.await(500, TimeUnit.MILLISECONDS)
        return result
    }

    override fun capture(park: Point): Boolean = onX(false) {
        val x = x ?: return@onX false
        val d = display ?: return@onX false
        // Another app's grab (an open menu) can hold things for a moment.
        var pointer = -1
        for (i in 0 until 3) {
            pointer = x.XGrabPointer(d, root, 0, BUTTON_PRESS or BUTTON_RELEASE or POINTER_MOTION, GRAB_ASYNC, GRAB_ASYNC, NativeLong(0), blank, NativeLong(0))
            if (pointer == GRAB_SUCCESS) break
            Thread.sleep(10)
        }
        if (pointer != GRAB_SUCCESS) return@onX false
        var keyboard = -1
        for (i in 0 until 3) {
            keyboard = x.XGrabKeyboard(d, root, 0, GRAB_ASYNC, GRAB_ASYNC, NativeLong(0))
            if (keyboard == GRAB_SUCCESS) break
            Thread.sleep(10)
        }
        if (keyboard != GRAB_SUCCESS) {
            x.XUngrabPointer(d, NativeLong(0))
            x.XFlush(d)
            return@onX false
        }
        grabbed = true
        this.park = park
        x.XWarpPointer(d, NativeLong(0), root, 0, 0, 0, 0, park.x, park.y)
        x.XFlush(d)
        true
    }

    override fun release(to: Point?) {
        onX(Unit) {
            val x = x ?: return@onX
            val d = display ?: return@onX
            ungrab(x, d, to)
        }
    }

    private fun ungrab(x: X11, d: Pointer, to: Point?) {
        park = null
        if (grabbed) {
            x.XUngrabKeyboard(d, NativeLong(0))
            x.XUngrabPointer(d, NativeLong(0))
            grabbed = false
        }
        to?.let { x.XWarpPointer(d, NativeLong(0), root, 0, 0, 0, 0, it.x, it.y) }
        pointerAt.forget()
        x.XFlush(d)
    }

    private fun selectRaw(xi: Xi, d: Pointer) {
        val bits = Memory(3).apply { clear() }
        for (type in listOf(XI_RAW_KEY_PRESS, XI_RAW_KEY_RELEASE, XI_RAW_BUTTON_PRESS, XI_RAW_BUTTON_RELEASE, XI_RAW_MOTION)) {
            val i = type shr 3
            bits.setByte(i.toLong(), (bits.getByte(i.toLong()).toInt() or (1 shl (type and 7))).toByte())
        }
        // XIEventMask: deviceid, mask_len, then the pointer to the bits (pointer-aligned).
        val mask = Memory(if (Native.POINTER_SIZE == 8) 16L else 12L).apply {
            setInt(0, XI_ALL_MASTER_DEVICES)
            setInt(4, 3)
            setPointer(8, bits)
        }
        xi.XISelectEvents(d, root, mask, 1)
        keep = bits
    }
    // The mask must outlive the call only for the call, but keeping it costs nothing.
    private var keep: Memory? = null
    /** XQueryPointer's root and child windows, which aren't used; made once. */
    private val rootOut = Memory(8)
    private val childOut = Memory(8)

    private fun blankCursor(x: X11, d: Pointer): NativeLong {
        val data = Memory(1).apply { setByte(0, 0) }
        val pixmap = x.XCreateBitmapFromData(d, root, data, 1, 1)
        val color = Memory(16).apply { clear() }
        val cursor = x.XCreatePixmapCursor(d, pixmap, pixmap, color, color, 0, 0)
        x.XFreePixmap(d, pixmap)
        return cursor
    }

    private fun handle(x: X11, d: Pointer, event: Pointer) {
        val gate = gate ?: return
        val type = event.getInt(0)
        // Where XKeyEvent and XButtonEvent keep the state, after the window and time fields.
        val stateAt = if (NativeLong.SIZE == 8) 80L else 48L
        when (type) {
            GENERIC_EVENT -> raw(x, d, event, gate)
            KEY_PRESS, KEY_RELEASE -> {
                if (!grabbed) return
                val mac = PcKeys.fromX11(event.getInt(stateAt + 4))
                val keycode = event.getInt(stateAt + 4)
                val verdict = gate.key(mac ?: (InputGate.UNKNOWN_BASE + keycode), type == KEY_PRESS)
                when {
                    // Seen here before anything is passed on, and the grab ends at once, whatever the worker does.
                    verdict == Verdict.ESCAPE -> {
                        ungrab(x, d, null)
                        sink(HookEvent.Escape)
                    }
                    verdict.forward -> sink(HookEvent.Key(mac, type == KEY_PRESS, true))
                }
            }
            BUTTON_PRESS_EVENT, BUTTON_RELEASE_EVENT -> {
                if (!grabbed) return
                val down = type == BUTTON_PRESS_EVENT
                when (val b = event.getInt(stateAt + 4)) {
                    4, 5, 6, 7 -> if (down) {
                        val dy = if (b == 4) 1.0 else if (b == 5) -1.0 else 0.0
                        val dx = if (b == 6) -1.0 else if (b == 7) 1.0 else 0.0
                        if (gate.motion().forward) sink(HookEvent.Wheel(dx, dy, true))
                    }
                    else -> {
                        val button = X11Input.button(b) ?: return
                        val verdict = gate.button(button, down)
                        if (verdict.forward) sink(HookEvent.Button(button, down, true))
                    }
                }
            }
            MOTION_NOTIFY -> {
                val p = park ?: return
                if (!grabbed) return
                val rx = event.getInt(stateAt - 8)
                val ry = event.getInt(stateAt - 4)
                // The warp back comes as a move to the place itself.
                if (rx == p.x && ry == p.y) return
                x.XWarpPointer(d, NativeLong(0), root, 0, 0, 0, 0, p.x, p.y)
                if (gate.motion().forward) sink(HookEvent.Motion(rx, ry, (rx - p.x).toDouble(), (ry - p.y).toDouble(), true))
            }
        }
    }

    /** XInput2 raw events, while the pointer is on this PC: the edge, and which keys and buttons went down here. */
    private fun raw(x: X11, d: Pointer, event: Pointer, gate: InputGate) {
        val wide = NativeLong.SIZE == 8
        if (event.getInt(if (wide) 32L else 16L) != opcode) return
        if (x.XGetEventData(d, event) == 0) return
        try {
            if (grabbed) return
            val evtype = event.getInt(if (wide) 36L else 20L)
            val data = event.getPointer(if (wide) 48L else 28L) ?: return
            val detail = data.getInt(if (wide) 56L else 36L)
            when (evtype) {
                XI_RAW_MOTION -> {
                    val (rawDx, rawDy) = X11Input.rawDelta(data, wide)
                    // The raw deltas with the last known place; the X server is asked at most at 60 Hz.
                    val motion = pointerAt.next(rawDx, rawDy) {
                        val px = IntByReference(); val py = IntByReference()
                        val ok = x.XQueryPointer(d, root, rootOut, childOut, px, py, IntByReference(), IntByReference(), IntByReference()) != 0
                        if (ok) Point(px.value, py.value) else null
                    } ?: return
                    sink(motion)
                }
                // Kept for the gate, so a key held here when the pointer crosses is let go here.
                XI_RAW_KEY_PRESS, XI_RAW_KEY_RELEASE -> {
                    val mac = PcKeys.fromX11(detail)
                    gate.key(mac ?: (InputGate.UNKNOWN_BASE + detail), evtype == XI_RAW_KEY_PRESS)
                }
                XI_RAW_BUTTON_PRESS, XI_RAW_BUTTON_RELEASE -> X11Input.button(detail)?.let { gate.button(it, evtype == XI_RAW_BUTTON_PRESS) }
            }
        } finally {
            x.XFreeEventData(d, event)
        }
    }

    private companion object {
        const val KEY_PRESS = 2
        const val KEY_RELEASE = 3
        const val BUTTON_PRESS_EVENT = 4
        const val BUTTON_RELEASE_EVENT = 5
        const val MOTION_NOTIFY = 6
        const val GENERIC_EVENT = 35
        const val BUTTON_PRESS = 1 shl 2
        const val BUTTON_RELEASE = 1 shl 3
        const val POINTER_MOTION = 1 shl 6
        const val GRAB_ASYNC = 1
        const val GRAB_SUCCESS = 0
        const val XI_ALL_MASTER_DEVICES = 1
        const val XI_RAW_KEY_PRESS = 13
        const val XI_RAW_KEY_RELEASE = 14
        const val XI_RAW_BUTTON_PRESS = 15
        const val XI_RAW_BUTTON_RELEASE = 16
        const val XI_RAW_MOTION = 17
    }
}

/**
 * Where the pointer is for raw moves while it's on this PC. XQueryPointer is a round trip to the X server; a gaming
 * mouse sends a thousand raw events a second, so the place is asked at most every [QUERY_MS], and moves in between
 * carry the raw deltas with the place last asked. A move with no deltas (scroll valuators only) is measured from the
 * places asked, as before, and dropped when it isn't time to ask.
 */
class PointerThrottle(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var last: Point? = null
    private var askedAt = Long.MIN_VALUE / 2

    fun next(rawDx: Double?, rawDy: Double?, query: () -> Point?): HookEvent.Motion? {
        val now = clock()
        val due = last == null || now - askedAt >= QUERY_MS
        if (!due) {
            val at = last ?: return null
            if (rawDx == null && rawDy == null) return null
            return HookEvent.Motion(at.x, at.y, rawDx ?: 0.0, rawDy ?: 0.0, false)
        }
        val before = last
        val at = query() ?: return null
        askedAt = now
        last = at
        val dx = rawDx ?: if (before == null) 0.0 else (at.x - before.x).toDouble()
        val dy = rawDy ?: if (before == null) 0.0 else (at.y - before.y).toDouble()
        return HookEvent.Motion(at.x, at.y, dx, dy, false)
    }

    /** The pointer was put somewhere else (warped back from the Mac): asked again on the next move. */
    fun forget() { last = null }

    companion object {
        const val QUERY_MS = 16L
    }
}

/**
 * The X thread's idle wait: poll() on the X connection and a wake pipe, so the thread sleeps instead of waking 500
 * times a second, and [wake] (work handed over, or stopping) ends the wait at once. Linux and macOS share the poll()
 * and pipe() used here.
 */
class IdleWait private constructor(private val libc: Libc, private val readFd: Int, private val writeFd: Int) {
    @Suppress("FunctionName")
    interface Libc : Library {
        fun pipe(fds: IntArray): Int
        fun poll(fds: Pointer, count: NativeLong, timeoutMs: Int): Int
        fun read(fd: Int, buffer: ByteArray, count: NativeLong): NativeLong
        fun write(fd: Int, buffer: ByteArray, count: NativeLong): NativeLong
        fun close(fd: Int): Int
    }

    /** A byte is in the pipe already: more wakes don't write, so the pipe never fills. */
    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var closed = false
    private val fds = Memory(16)
    private val drain = ByteArray(64)

    /** Waits until [fd] can be read, [wake] is called, or [timeoutMs] pass. True when [fd] has data. */
    fun await(fd: Int, timeoutMs: Int): Boolean {
        if (closed) return false
        // struct pollfd { int fd; short events; short revents; }, twice.
        fds.setInt(0, fd); fds.setShort(4, POLLIN); fds.setShort(6, 0)
        fds.setInt(8, readFd); fds.setShort(12, POLLIN); fds.setShort(14, 0)
        val ready = libc.poll(fds, NativeLong(2), timeoutMs)
        // An error (a signal, or a bad descriptor) mustn't turn this into a busy loop.
        if (ready < 0) Thread.sleep(2)
        if (ready <= 0) return false
        if (fds.getShort(14).toInt() and POLLIN.toInt() != 0) {
            // Read first, then allow the next write: a wake in between leaves a byte, which only wakes once more.
            libc.read(readFd, drain, NativeLong(drain.size.toLong()))
            pending.set(false)
        }
        return fds.getShort(6).toInt() != 0
    }

    fun wake() {
        if (closed || !pending.compareAndSet(false, true)) return
        runCatching { libc.write(writeFd, byteArrayOf(1), NativeLong(1)) }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { libc.close(readFd); libc.close(writeFd) }
    }

    companion object {
        const val TIMEOUT_MS = 100
        private const val POLLIN: Short = 1

        private val libc: Libc? by lazy { runCatching { Native.load("c", Libc::class.java) }.getOrNull() }

        /** Null where libc can't be loaded or the pipe can't be made: the X thread then sleeps briefly instead. */
        fun open(): IdleWait? = runCatching {
            val c = libc ?: return null
            val pair = IntArray(2)
            if (c.pipe(pair) != 0) return null
            IdleWait(c, pair[0], pair[1])
        }.getOrNull()
    }
}

/** The pure parts of [X11Capture]. */
object X11Input {
    /** An X button to a Mac button number: 1 left, 2 middle, 3 right, 8 and 9 back and forward. Null for the wheel. */
    fun button(x: Int): Int? = when (x) {
        1 -> 0
        2 -> 2
        3 -> 1
        8 -> 3
        9 -> 4
        in 10..31 -> x - 5
        else -> null
    }

    /** Which of the [mask] bytes' valuators are set, in order: the raw values come packed in that order. */
    fun setValuators(mask: ByteArray): List<Int> = buildList {
        for (i in 0 until mask.size * 8) if (mask[i shr 3].toInt() and (1 shl (i and 7)) != 0) add(i)
    }

    /** Valuators 0 and 1 (x and y) of an XIRawEvent, unaccelerated, or null where the event has none. */
    fun rawDelta(data: Pointer, wide: Boolean): Pair<Double?, Double?> {
        val maskLen = data.getInt(if (wide) 64L else 44L)
        if (maskLen <= 0 || maskLen > 64) return null to null
        val maskPtr = data.getPointer(if (wide) 72L else 48L) ?: return null to null
        val raw = data.getPointer(if (wide) 88L else 56L) ?: return null to null
        val set = setValuators(maskPtr.getByteArray(0, maskLen))
        val xi = set.indexOf(0).takeIf { it >= 0 }
        val yi = set.indexOf(1).takeIf { it >= 0 }
        return xi?.let { raw.getDouble(it * 8L) } to yi?.let { raw.getDouble(it * 8L) }
    }
}
