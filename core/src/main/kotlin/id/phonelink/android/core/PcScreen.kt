package id.phonelink.android.core

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope

// A Windows or Linux PC's own screen on a phone, as the Mac's "This Mac's screen on a phone" does, with the same
// `macscreen.*` messages. The PC sends whole JPEG pictures (`format: "jpeg"`) instead of H.264, so the phone shows
// them with its picture decoder. The protocol side lives here so it can be tested without a PC; the desktop app gives
// the capture (java.awt.Robot) and the pointer.

/** One of the PC's displays, where it sits on the desktop, in the coordinates the pointer uses. */
data class PcDisplay(
    val id: Long,
    val name: String,
    val main: Boolean,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * One picture of a display, already scaled so the long side is at most what was asked, with the pointer drawn in.
 * [pixels] is [width] × [height] RGB, row by row, only used to tell whether anything changed. [encode] makes the JPEG at
 * a quality from 1 to 100, or null when it can't.
 */
class ScreenGrab(val width: Int, val height: Int, val pixels: IntArray, val encode: (quality: Int) -> ByteArray?)

/** What captures the PC's screen. */
interface PcScreenSource {
    /** Why this PC's screen can't be captured at all (Wayland, no display), or null. */
    fun refusal(): String?
    /** The displays now. Read again for each pointer event, since they can change while a phone watches. */
    fun displays(): List<PcDisplay>
    /** A picture of [display], long side at most [maxLong]. Null when it couldn't be taken this time. */
    fun grab(display: PcDisplay, maxLong: Int): ScreenGrab?
    /** Why grabbing keeps failing, for the phone, such as the user not allowing it; null for the usual words. */
    fun failure(): String? = null
    /** Nobody is viewing any more: anything kept running for the pictures can stop. */
    fun release() {}
}

object PcScreen {
    /** The picture's long side, as the Mac's H.264 for this screen. */
    const val MAX_LONG_SIDE = 1600
    /** Wayland without a screen sharing portal: the last resort is an X11 session. */
    const val WAYLAND_REFUSAL =
        "This PC uses Wayland and has no screen sharing portal (xdg-desktop-portal), so its screen can't be captured. " +
            "DyskLink on the PC shows what to install, or log in with an X11 session there."
    /** Wayland with the portal, but without the GStreamer pieces that turn its stream into pictures. */
    fun waylandMissing(what: String): String =
        "This PC uses Wayland and needs $what to show its screen. DyskLink on the PC shows the command to install it."
    const val WAYLAND_DENIED = "Sharing this PC's screen wasn't allowed on the PC. Allow it there, then try again."
    const val WAYLAND_NO_ANSWER = "Nobody allowed sharing the screen on the PC in time. Allow it there, then try again."
    const val TURNED_OFF = "Turn on \"Let phones see this screen\" in DyskLink on the PC first."
    const val NO_EXTEND = "This PC can't use the phone as an extra display. Windows and Linux need a display driver for that."
    const val ANOTHER_PHONE = "Another phone is viewing this PC now."
    const val STOPPED_HERE = "Viewing was stopped on the PC."
    const val SWITCHED_OFF = "Screen viewing was turned off on the PC."
    /** What `macscreen.pointer` may ask for, as on the Mac. */
    val POINTER_KINDS = setOf("move", "down", "drag", "up", "click", "double", "right")

    /**
     * A cheap fingerprint of a picture, to skip sending one that hasn't changed. Every pixel counts, so a caret or a
     * typed letter shows; on a scaled-down 1600 × 900 picture it takes about a millisecond.
     */
    fun hash(pixels: IntArray): Long {
        var h = -0x340d631b7bdddcdbL // FNV-1a offset basis
        for (p in pixels) {
            h = (h xor (p.toLong() and 0xFFFFFF)) * 0x100000001b3L
        }
        return h xor pixels.size.toLong()
    }

    /** A point from the phone (0 to 1 across the picture) on [display], in desktop pixels. Null for NaN or infinity. */
    fun toDesktop(display: PcDisplay, x: Float?, y: Float?): Pair<Int, Int>? {
        if (x == null || y == null || !x.isFinite() || !y.isFinite()) return null
        val px = display.x + (x.coerceIn(0f, 1f) * (display.width - 1).coerceAtLeast(0)).roundToInt()
        val py = display.y + (y.coerceIn(0f, 1f) * (display.height - 1).coerceAtLeast(0)).roundToInt()
        return px to py
    }

    /** The display the phone asked for, else the main one, else the first. */
    fun pick(displays: List<PcDisplay>, wanted: Long?): PcDisplay? =
        displays.firstOrNull { it.id == wanted } ?: displays.firstOrNull { it.main } ?: displays.firstOrNull()

    fun choices(displays: List<PcDisplay>): List<DisplayChoice> = displays.map { DisplayChoice(it.id, it.name, it.main) }
}

/**
 * Sends one display to one phone: `macscreen.started` (again whenever the picture's size changes), then JPEG pictures
 * as `macscreen.frame`, paced by the same [JpegPacer] as the phone's own JPEG frames to a PC. A picture that hasn't
 * changed since the last one that went out isn't sent; a still screen sends nothing, and the phone keeps the last one.
 * [onEnded] runs once when it ends by itself (the capture failed, the phone stopped taking frames), with why.
 */
class PcScreenSender(
    private val source: PcScreenSource,
    display: PcDisplay,
    scope: CoroutineScope,
    /** Waits like a direct write; false when it couldn't be sent. */
    private val send: (Envelope) -> Boolean,
    private val onEnded: (String) -> Unit,
    val pacer: JpegPacer = JpegPacer(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxLong: Int = PcScreen.MAX_LONG_SIDE,
) {
    private val running = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)
    /** Set by `macscreen.sync` and a new display: the next picture goes out even if it's the same. */
    private val fresh = AtomicBoolean(true)
    private val streamer = JpegStreamer(scope, send, MessageType.MACSCREEN_FRAME, pacer, clock) { end("The phone stopped taking the picture.") }

    @Volatile var display: PcDisplay = display
        private set
    @Volatile private var announce = true

    /** Pictures queued for the phone, and grabs skipped because nothing had changed. */
    @Volatile var sent = 0L
        private set
    @Volatile var unchanged = 0L
        private set

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(isDaemon = true, name = "dysk-screen-out") { loop() }
    }

    /** Shows another display, or says the size again (the phone came back): a new `macscreen.started` and picture. */
    fun show(next: PcDisplay) {
        display = next
        announce = true
        fresh.set(true)
    }

    /** `macscreen.sync`: the phone wants a picture now. */
    fun resync() = fresh.set(true)

    private fun loop() {
        var shown: Pair<Int, Int>? = null
        var last: Long? = null
        var failures = 0
        var still = 0
        while (running.get()) {
            val wait = streamer.waitMs()
            if (wait > 0) { pause(minOf(wait, 100)); continue }
            val target = display
            val grab = runCatching { source.grab(target, maxLong) }.onFailure { CoreLog.w(TAG, "Screen capture failed", it) }.getOrNull()
            if (!running.get()) break
            if (grab == null || grab.width <= 0 || grab.height <= 0) {
                if (++failures >= MAX_FAILURES) return end(runCatching { source.failure() }.getOrNull() ?: "This PC's screen couldn't be captured.")
                pacer.taken(clock())
                pause(RETRY_MS)
                continue
            }
            failures = 0
            val size = grab.width to grab.height
            if (announce || size != shown) {
                announce = false
                val started = Envelope(
                    MessageType.MACSCREEN_STARTED,
                    screen = ScreenInfo(grab.width, grab.height),
                    text = target.name,
                    displays = PcScreen.choices(runCatching { source.displays() }.getOrDefault(listOf(target))),
                    displayId = target.id,
                    format = JpegFrames.FORMAT,
                )
                if (!send(started)) return end("The phone isn't connected.")
                shown = size
                last = null
            }
            val hash = PcScreen.hash(grab.pixels)
            if (!fresh.getAndSet(false) && hash == last) {
                unchanged++
                pacer.taken(clock())
                // A screen that stays still is looked at less often, which saves the PC's time.
                if (++still > STILL_FRAMES) pause(STILL_MS)
                continue
            }
            still = 0
            val jpeg = runCatching { grab.encode(streamer.quality) }.getOrNull()
            if (jpeg == null || !JpegFrames.looksLikeJpeg(jpeg)) {
                pacer.taken(clock())
                continue
            }
            // Dropped (the network is behind, or too big): the same picture is tried again next time.
            last = if (streamer.offer(jpeg)) { sent++; hash } else null
        }
    }

    private fun pause(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    private fun end(why: String) {
        if (halt() && ended.compareAndSet(false, true)) onEnded(why)
    }

    /** Stops for good. [onEnded] doesn't run for a stop asked for here. */
    fun stop() {
        ended.set(true)
        halt()
    }

    /** True when it was running. */
    private fun halt(): Boolean {
        val was = running.getAndSet(false)
        streamer.close(notify = false)
        if (was) runCatching { source.release() }
        return was
    }

    private companion object {
        const val TAG = "DyskLink"
        const val MAX_FAILURES = 25
        const val RETRY_MS = 200L
        /** About two seconds at 15 frames a second. */
        const val STILL_FRAMES = 30
        const val STILL_MS = 120L
    }
}
