package id.dysklink.desktop

import id.dysklink.desktop.ui.VideoMath
import id.phonelink.android.core.CoreLog
import id.phonelink.android.core.PhoneVideo
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// The phone camera as a webcam of this PC: "DyskLink Camera" in Zoom, Meet, Teams, and OBS's Video Capture Device.
// v4l2loopback on Linux, softcam on Windows. One fixed size whatever the phone sends, so apps never see it change.

/** Where frames go: a v4l2loopback device or softcam. One writer at a time. */
interface WebcamSink {
    val width: Int
    val height: Int
    /** Such as /dev/video10. */
    val name: String
    /** Throws [WebcamOpenException]. */
    fun open()
    /** One frame of 0xRRGGBB pixels, [width] × [height], top row first. Throws [WebcamOpenException]. */
    fun send(pixels: IntArray)
    fun close()
}

/** Turning the phone's pictures into webcam frames: fitted, turned, mirrored, and converted for the driver. */
object WebcamFrames {
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val OFF_TEXT = "DyskLink camera is off"

    /** A canvas whose pixels are an IntArray of 0xRRGGBB, reused frame after frame. */
    class Canvas(val width: Int = WIDTH, val height: Int = HEIGHT) {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val pixels: IntArray = (image.raster.dataBuffer as DataBufferInt).data
    }

    /** Draws [picture] fitted in the canvas with black bars, turned [rotation] degrees clockwise, then mirrored. */
    fun draw(canvas: Canvas, picture: BufferedImage, rotation: Int, mirror: Boolean) {
        val g = canvas.image.createGraphics()
        try {
            g.color = Color.BLACK
            g.fillRect(0, 0, canvas.width, canvas.height)
            val picW = picture.width.toFloat()
            val picH = picture.height.toFloat()
            val (left, top, w, h) = VideoMath.fit(canvas.width.toFloat(), canvas.height.toFloat(), picW, picH, rotation).toList()
            if (w <= 0) return
            val turned = Math.floorMod(rotation, 180) == 90
            val drawW = if (turned) h else w
            val drawH = if (turned) w else h
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            // As the camera window draws it: to the box's middle, mirror, turn, then the picture around the middle.
            val transform = AffineTransform()
            transform.translate((left + w / 2).toDouble(), (top + h / 2).toDouble())
            if (mirror) transform.scale(-1.0, 1.0)
            transform.rotate(Math.toRadians(rotation.toDouble()))
            transform.translate((-drawW / 2).toDouble(), (-drawH / 2).toDouble())
            transform.scale((drawW / picW).toDouble(), (drawH / picH).toDouble())
            g.drawImage(picture, transform, null)
        } finally {
            g.dispose()
        }
    }

    /** What apps show while the phone's camera is off: dark, with [OFF_TEXT]. */
    fun placeholder(canvas: Canvas) {
        val g = canvas.image.createGraphics()
        try {
            g.color = Color(0x12, 0x14, 0x18)
            g.fillRect(0, 0, canvas.width, canvas.height)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val big = Font(Font.SANS_SERIF, Font.BOLD, canvas.height / 16)
            val small = Font(Font.SANS_SERIF, Font.PLAIN, canvas.height / 30)
            // A small camera mark above the text.
            val cx = canvas.width / 2
            val cy = canvas.height / 2 - canvas.height / 8
            val s = canvas.height / 14
            g.color = Color(0x3a, 0x40, 0x4a)
            g.fillRoundRect(cx - s, cy - s * 2 / 3, s * 2, s * 4 / 3, s / 2, s / 2)
            g.color = Color(0x12, 0x14, 0x18)
            g.fillOval(cx - s / 2, cy - s / 2, s, s)
            g.color = Color(255, 255, 255, 220)
            g.font = big
            val line1 = g.fontMetrics
            g.drawString(OFF_TEXT, cx - line1.stringWidth(OFF_TEXT) / 2, canvas.height / 2 + line1.ascent / 2 + canvas.height / 24)
            g.color = Color(255, 255, 255, 130)
            g.font = small
            val hint = "Start the phone camera in DyskLink to send its picture."
            g.drawString(hint, cx - g.fontMetrics.stringWidth(hint) / 2, canvas.height / 2 + canvas.height / 8 + canvas.height / 24)
        } finally {
            g.dispose()
        }
    }

    // BT.601, limited range, as webcams send.
    private fun y(r: Int, g: Int, b: Int) = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
    private fun u(r: Int, g: Int, b: Int) = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
    private fun v(r: Int, g: Int, b: Int) = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

    /** YUYV (4:2:2): Y0 U Y1 V for every two pixels, the colour of the pair averaged. [width] must be even. */
    fun toYuyv(pixels: IntArray, width: Int, height: Int, out: ByteArray) {
        var o = 0
        var i = 0
        val count = width * height
        while (i < count) {
            val p0 = pixels[i]
            val p1 = pixels[i + 1]
            val r0 = (p0 shr 16) and 0xff; val g0 = (p0 shr 8) and 0xff; val b0 = p0 and 0xff
            val r1 = (p1 shr 16) and 0xff; val g1 = (p1 shr 8) and 0xff; val b1 = p1 and 0xff
            val r = (r0 + r1 + 1) shr 1; val g = (g0 + g1 + 1) shr 1; val b = (b0 + b1 + 1) shr 1
            out[o] = y(r0, g0, b0).toByte()
            out[o + 1] = u(r, g, b).toByte()
            out[o + 2] = y(r1, g1, b1).toByte()
            out[o + 3] = v(r, g, b).toByte()
            o += 4
            i += 2
        }
    }

    /** I420 (4:2:0): the Y plane, then U and V at half size each way, each 2 × 2 block averaged. Even sizes only. */
    fun toI420(pixels: IntArray, width: Int, height: Int, out: ByteArray) {
        for (i in 0 until width * height) {
            val p = pixels[i]
            out[i] = y((p shr 16) and 0xff, (p shr 8) and 0xff, p and 0xff).toByte()
        }
        val uBase = width * height
        val vBase = uBase + width * height / 4
        val half = width / 2
        for (by in 0 until height / 2) for (bx in 0 until half) {
            var r = 0; var g = 0; var b = 0
            val top = by * 2 * width + bx * 2
            for (k in intArrayOf(top, top + 1, top + width, top + width + 1)) {
                val p = pixels[k]
                r += (p shr 16) and 0xff; g += (p shr 8) and 0xff; b += p and 0xff
            }
            r = (r + 2) shr 2; g = (g + 2) shr 2; b = (b + 2) shr 2
            out[uBase + by * half + bx] = u(r, g, b).toByte()
            out[vBase + by * half + bx] = v(r, g, b).toByte()
        }
    }

    /** BGR24 with the bottom row first, as a DIB with a positive height (softcam). [width] × 3 must be a multiple of 4. */
    fun toBgrBottomUp(pixels: IntArray, width: Int, height: Int, out: ByteArray) {
        var o = 0
        for (row in height - 1 downTo 0) {
            var i = row * width
            val end = i + width
            while (i < end) {
                val p = pixels[i]
                out[o] = p.toByte()
                out[o + 1] = (p shr 8).toByte()
                out[o + 2] = (p shr 16).toByte()
                o += 3
                i++
            }
        }
    }
}

/**
 * When to send: a new picture at most [maxFps], and the last one again every [keepAliveMs] when nothing new comes,
 * so video apps don't think the camera froze or went away.
 */
class FramePacer(maxFps: Int = 30, val keepAliveMs: Long = 1000L / 15) {
    private val minMs = 1000L / maxFps
    private var lastAt = Long.MIN_VALUE / 2

    fun canSendNew(now: Long) = now - lastAt >= minMs
    fun keepAliveDue(now: Long) = now - lastAt >= keepAliveMs
    /** How long to sleep before anything could be due. */
    fun waitMs(now: Long): Long = (lastAt + minMs - now).coerceIn(1, keepAliveMs)
    fun sent(now: Long) { lastAt = now }
}

enum class WebcamStatus { Off, Starting, Active, NeedsSetup, Busy }

/** The webcam switch and what came of it, for the camera tab, its window, and Settings. */
data class WebcamState(
    val on: Boolean = false,
    val status: WebcamStatus = WebcamStatus.Off,
    /** Where it goes, such as /dev/video10, and what apps call it. */
    val device: String? = null,
    val label: String = Loopback.LABEL,
    /** The phone's picture goes out now, not the "camera is off" frame. */
    val live: Boolean = false,
    val problem: String? = null,
    /** What to do so it can start: shown under the switch while it's on. */
    val hints: List<HostHint> = emptyList(),
    /** In Settings, whether or not the switch is on: such as removing the Windows driver. */
    val settings: List<HostHint> = emptyList(),
)

/** What a webcam needs on this PC: the device it can go to, or the hints to get there. */
data class WebcamPlan(
    val device: String?,
    val hints: List<HostHint> = emptyList(),
    val settings: List<HostHint> = emptyList(),
    /** What video apps call it. */
    val label: String = Loopback.LABEL,
)

/** The system side of the webcam: Linux or Windows. */
interface WebcamBackend {
    fun plan(): WebcamPlan
    fun sink(device: String): WebcamSink
    fun busyHint(device: String, problem: String): HostHint
    /** A hint's button, such as installing the Windows driver. Runs off the UI thread. */
    fun action(id: String): Boolean = false
    /** Whether the plan is worth checking while the switch is off (for Settings). */
    val planWhileOff: Boolean get() = false
}

class LinuxWebcam(private val osRelease: () -> String) : WebcamBackend {
    override fun plan(): WebcamPlan = HostChecks.linuxWebcam(Loopback.probe(), HostChecks.distro(osRelease()))
    override fun sink(device: String): WebcamSink = V4l2Sink(device, WebcamFrames.WIDTH, WebcamFrames.HEIGHT)
    override fun busyHint(device: String, problem: String) = HostChecks.webcamBusy(windows = false, device, problem)
}

class WindowsWebcam(private val dll: () -> File? = { Softcam.dll() }) : WebcamBackend {
    override val planWhileOff = true
    override fun plan(): WebcamPlan = HostChecks.windowsWebcam(dll()?.absolutePath, Softcam.registered())
    override fun sink(device: String): WebcamSink = SoftcamSink(File(device), WebcamFrames.WIDTH, WebcamFrames.HEIGHT)
    override fun busyHint(device: String, problem: String) = HostChecks.webcamBusy(windows = true, device, problem)
    override fun action(id: String): Boolean {
        val file = dll() ?: return false
        return when (id) {
            HostChecks.ACTION_WEBCAM_INSTALL -> Softcam.register(file, remove = false)
            HostChecks.ACTION_WEBCAM_REMOVE -> Softcam.register(file, remove = true)
            else -> false
        }
    }
}

/**
 * Sends the phone's camera to the webcam while the switch is on: its newest picture, the last one again when the
 * phone pauses, and "DyskLink camera is off" when no camera runs, so a call app keeps the device. [step] does one
 * round and says how long to wait, so tests can drive it with their own clock.
 */
class VirtualCamera(
    private val cameras: StateFlow<Map<String, PhoneVideo>>,
    private val backend: WebcamBackend?,
    on: Boolean,
    private val onSwitch: (Boolean) -> Unit = {},
    private val decode: (ByteArray) -> BufferedImage? = { runCatching { javax.imageio.ImageIO.read(it.inputStream()) }.getOrNull() },
    /** Off in tests, which call [step] themselves. */
    private val runThread: Boolean = true,
) {
    private val _state = MutableStateFlow(WebcamState(on = on, status = if (on) WebcamStatus.Starting else WebcamStatus.Off))
    val state: StateFlow<WebcamState> = _state.asStateFlow()
    val available: Boolean get() = backend != null

    @Volatile private var rotation = 0
    @Volatile private var mirror = false
    private val lock = Object()
    private var plan: WebcamPlan? = null
    private var sink: WebcamSink? = null
    private var retryAt = 0L
    private val canvas = WebcamFrames.Canvas()
    private val pacer = FramePacer()
    private var source: String? = null
    private var lastJpeg: ByteArray? = null
    private var lastPicture: BufferedImage? = null
    private var drawn: Pair<Int, Boolean>? = null
    private var showingPlaceholder = false
    private var thread: Thread? = null

    /** The camera window's own turn and mirror, on top of the phone's rotation. */
    fun setView(rotation: Int, mirror: Boolean) {
        this.rotation = rotation
        this.mirror = mirror
    }

    fun setOn(on: Boolean) {
        if (backend == null) return
        onSwitch(on)
        synchronized(lock) {
            if (!on) closeSink()
            retryAt = 0
            _state.value = _state.value.copy(on = on, status = if (on) WebcamStatus.Starting else WebcamStatus.Off, live = false, problem = null, hints = if (on) _state.value.hints else emptyList())
        }
        if (on) {
            recheck()
            if (runThread) startThread()
        } else {
            generation++
            thread?.interrupt()
        }
    }

    /** Looks again at what the system has; on window focus and after a hint's button. */
    fun recheck() {
        val backend = backend ?: return
        if (!_state.value.on && !backend.planWhileOff) return
        val fresh = runCatching { backend.plan() }.onFailure { CoreLog.w("DyskLink", "Webcam check", it) }.getOrNull() ?: return
        synchronized(lock) {
            plan = fresh
            // Something changed, such as the module loaded: try at once.
            if (sink == null) retryAt = 0
            val now = _state.value
            _state.value = now.copy(
                settings = fresh.settings,
                // A busy device keeps its own hint until it's tried again.
                hints = when {
                    !now.on -> emptyList()
                    now.status == WebcamStatus.Busy -> now.hints
                    else -> fresh.hints
                },
                status = if (now.on && sink == null && fresh.device == null) WebcamStatus.NeedsSetup else now.status,
            )
        }
    }

    /** A hint's button. False when it didn't work, such as Windows' question answered No. */
    fun action(id: String): Boolean {
        val ok = backend?.action(id) ?: false
        recheck()
        return ok
    }

    fun close() {
        generation++
        thread?.interrupt()
        synchronized(lock) { closeSink() }
    }

    /** Each start makes a new thread; an older one sees the generation change and ends. */
    @Volatile private var generation = 0

    private fun startThread() {
        val gen = ++generation
        thread = Thread({
            while (_state.value.on && generation == gen) {
                try {
                    Thread.sleep(step(System.currentTimeMillis()))
                } catch (_: InterruptedException) {
                }
            }
        }, "DyskLink webcam").apply { isDaemon = true; start() }
    }

    /** One round: open the device if it isn't, then send what's due. Returns how long to wait. */
    fun step(now: Long): Long = synchronized(lock) {
        if (!_state.value.on || backend == null) return 200
        val sink = sink ?: openSink(now) ?: return (retryAt - now).coerceIn(50, 1_000)
        val video = pickSource()
        val jpeg = video?.frame?.takeIf { video.live }
        val turn = Math.floorMod((video?.rotation ?: 0) + rotation, 360) to mirror
        try {
            when {
                jpeg != null && jpeg !== lastJpeg -> {
                    if (!pacer.canSendNew(now)) return pacer.waitMs(now)
                    lastJpeg = jpeg
                    decode(jpeg)?.let { lastPicture = it }
                    lastPicture?.let { draw(it, turn); write(sink, now) }
                }
                jpeg == null && !showingPlaceholder -> {
                    lastJpeg = null
                    lastPicture = null
                    WebcamFrames.placeholder(canvas)
                    showingPlaceholder = true
                    drawn = null
                    write(sink, now)
                }
                // Mirror or Rotate pressed while the phone sends nothing new.
                jpeg != null && drawn != turn && lastPicture != null -> { draw(lastPicture!!, turn); write(sink, now) }
                pacer.keepAliveDue(now) -> write(sink, now)
            }
        } catch (e: Exception) {
            CoreLog.w("DyskLink", "Webcam", e)
            closeSink()
            retryAt = now + RETRY_MS
            failed(e as? WebcamOpenException ?: WebcamOpenException(e.message ?: e.toString()), sink.name)
            return RETRY_MS
        }
        val live = jpeg != null
        if (_state.value.live != live) _state.value = _state.value.copy(live = live)
        pacer.waitMs(now)
    }

    private fun draw(picture: BufferedImage, turn: Pair<Int, Boolean>) {
        WebcamFrames.draw(canvas, picture, turn.first, turn.second)
        drawn = turn
        showingPlaceholder = false
    }

    private fun write(sink: WebcamSink, now: Long) {
        sink.send(canvas.pixels)
        pacer.sent(now)
    }

    /** The camera to send: the one sent so far while it's there, else the first one that's live. */
    private fun pickSource(): PhoneVideo? {
        val all = cameras.value
        val current = source?.let { all[it] }
        if (current != null && (current.live || all.values.none { it.live })) return current
        val next = all.entries.firstOrNull { it.value.live } ?: all.entries.firstOrNull()
        source = next?.key
        return next?.value
    }

    private fun openSink(now: Long): WebcamSink? {
        if (now < retryAt) return null
        retryAt = now + RETRY_MS
        val backend = backend ?: return null
        val plan = plan ?: return null
        val device = plan.device
        if (device == null) {
            _state.value = _state.value.copy(status = WebcamStatus.NeedsSetup, device = null, hints = plan.hints, live = false)
            return null
        }
        val sink = backend.sink(device)
        return try {
            sink.open()
            this.sink = sink
            showingPlaceholder = false
            lastJpeg = null
            drawn = null
            _state.value = _state.value.copy(status = WebcamStatus.Active, device = sink.name, label = plan.label, problem = null, hints = plan.hints)
            sink
        } catch (e: WebcamOpenException) {
            CoreLog.w("DyskLink", "Webcam: ${e.message}")
            failed(e, device)
            null
        } catch (e: Throwable) {
            CoreLog.w("DyskLink", "Webcam", e)
            failed(WebcamOpenException(e.message ?: e.toString()), device)
            null
        }
    }

    private fun failed(e: WebcamOpenException, device: String) {
        val backend = backend ?: return
        _state.value = _state.value.copy(
            status = if (e.busy) WebcamStatus.Busy else WebcamStatus.NeedsSetup,
            device = device, problem = e.message, live = false,
            hints = listOf(backend.busyHint(device, e.message.orEmpty())).takeIf { e.busy } ?: plan?.hints.orEmpty(),
        )
    }

    private fun closeSink() {
        sink?.let { runCatching { it.close() } }
        sink = null
        showingPlaceholder = false
        lastJpeg = null
        lastPicture = null
    }

    companion object {
        const val RETRY_MS = 3_000L

        fun forThisPc(cameras: StateFlow<Map<String, PhoneVideo>>, on: Boolean, onSwitch: (Boolean) -> Unit): VirtualCamera {
            val backend = when {
                Platform.isWindows -> WindowsWebcam()
                Platform.isLinux -> LinuxWebcam { runCatching { File("/etc/os-release").readText() }.getOrDefault("") }
                else -> null
            }
            return VirtualCamera(cameras, backend, on && backend != null, onSwitch)
        }
    }
}
