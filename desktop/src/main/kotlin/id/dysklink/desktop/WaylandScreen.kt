package id.dysklink.desktop

import id.phonelink.android.core.CoreLog
import id.phonelink.android.core.JpegFrames
import id.phonelink.android.core.PcDisplay
import id.phonelink.android.core.PcScreen
import id.phonelink.android.core.PcScreenSource
import id.phonelink.android.core.ScreenGrab
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// This PC's screen on a phone under Wayland: the ScreenCast portal shares one monitor as a PipeWire stream (after the
// user picks it once), and GStreamer's gst-launch-1.0 turns it into JPEG pictures on its stdout, which go to the same
// sender and pacer as Robot's pictures on X11 and Windows.

/** What GStreamer this PC has. */
data class GstCheck(
    val launch: Boolean = false,
    val pipewiresrc: Boolean = false,
    /** Newer pipewiresrc resends the last picture now and then, so a still screen isn't taken for a stalled one. */
    val keepalive: Boolean = false,
) {
    val ready: Boolean get() = launch && pipewiresrc

    companion object {
        /** [run] runs a command and gives its exit code and output, or null when it couldn't run. */
        fun probe(onPath: (String) -> Boolean, run: (List<String>) -> Pair<Int, String>?): GstCheck {
            if (!onPath("gst-launch-1.0")) return GstCheck()
            val inspect = if (onPath("gst-inspect-1.0")) run(listOf("gst-inspect-1.0", "pipewiresrc")) else null
            val ok = inspect != null && inspect.first == 0
            return GstCheck(launch = true, pipewiresrc = ok, keepalive = ok && "keepalive-time" in inspect!!.second)
        }

        fun run(command: List<String>): Pair<Int, String>? = runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroy(); null } else process.exitValue() to out
        }.getOrNull()
    }
}

object GstPipeline {
    const val BOUNDARY = "dysk"
    const val QUALITY = 70
    const val FPS = 15

    /**
     * gst-launch-1.0 for PipeWire [node], scaled to [width] × [height] when known, as JPEGs in a multipart stream on
     * stdout. -q keeps gst-launch's own messages off stdout, where they'd break the stream.
     */
    fun command(node: Long, width: Int?, height: Int?, quality: Int = QUALITY, fps: Int = FPS, keepalive: Boolean = false): List<String> = buildList {
        add("gst-launch-1.0"); add("-q")
        add("pipewiresrc"); add("path=$node"); add("do-timestamp=true")
        if (keepalive) add("keepalive-time=1000")
        add("!"); add("videorate"); add("max-rate=$fps"); add("drop-only=true")
        // Scaled before the color conversion, which then has fewer pixels to do.
        if (width != null && height != null && width > 0 && height > 0) {
            add("!"); add("videoscale"); add("!"); add("video/x-raw,width=$width,height=$height")
        }
        add("!"); add("videoconvert")
        add("!"); add("jpegenc"); add("quality=${quality.coerceIn(1, 100)}")
        add("!"); add("multipartmux"); add("boundary=$BOUNDARY")
        add("!"); add("fdsink"); add("fd=1")
    }
}

/**
 * Reads the parts of a multipart stream as multipartmux writes them: "--boundary", headers, a blank line, then
 * Content-Length bytes. Without Content-Length a part ends where the next boundary starts. Blocks; null at the end.
 */
class MultipartJpegReader(input: InputStream, private val boundary: String = GstPipeline.BOUNDARY, private val maxPart: Int = 8 * 1024 * 1024) {
    private val input = input.buffered(64 * 1024)
    /** The boundary of the next part was already read, at the end of a part without Content-Length. */
    private var atHeaders = false

    fun next(): ByteArray? {
        if (!atHeaders) {
            while (true) {
                val line = readLine() ?: return null
                val trimmed = line.trim()
                if (trimmed == "--$boundary--") return null
                if (trimmed == "--$boundary") break
            }
        }
        atHeaders = false
        var length: Int? = null
        while (true) {
            val line = readLine() ?: return null
            if (line.isEmpty()) break
            val name = line.substringBefore(':').trim()
            if (name.equals("Content-Length", ignoreCase = true)) length = line.substringAfter(':').trim().toIntOrNull()
        }
        if (length != null && length in 0..maxPart) {
            val data = input.readNBytes(length)
            return if (data.size == length) data else null
        }
        return readUntilBoundary()
    }

    private fun readUntilBoundary(): ByteArray? {
        val marker = "\r\n--$boundary".toByteArray()
        val out = ByteArrayOutputStream()
        var matched = 0
        while (true) {
            val b = input.read()
            if (b < 0) return out.toByteArray().takeIf { it.isNotEmpty() }
            if (b.toByte() == marker[matched]) {
                matched++
                if (matched == marker.size) {
                    // The rest of the boundary line, then the next part's headers.
                    val rest = readLine() ?: ""
                    atHeaders = !rest.trim().startsWith("--")
                    return out.toByteArray()
                }
            } else {
                if (matched > 0) out.write(marker, 0, matched)
                matched = if (b.toByte() == marker[0]) 1 else 0
                if (matched == 0) out.write(b)
            }
            if (out.size() > maxPart) return null
        }
    }

    private fun readLine(): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1)
            if (b == '\n'.code) return line.toString(Charsets.ISO_8859_1).removeSuffix("\r")
            line.write(b)
            if (line.size() > 4096) return line.toString(Charsets.ISO_8859_1)
        }
    }
}

object JpegSize {
    /** The width and height in a JPEG's SOF marker, or null. */
    fun of(jpeg: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 9 < jpeg.size) {
            if (jpeg[i] != 0xFF.toByte()) { i++; continue }
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue }
            if (marker == 0xD8 || marker in 0xD0..0xD7 || marker == 0x01) { i += 2; continue }
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val height = ((jpeg[i + 5].toInt() and 0xFF) shl 8) or (jpeg[i + 6].toInt() and 0xFF)
                val width = ((jpeg[i + 7].toInt() and 0xFF) shl 8) or (jpeg[i + 8].toInt() and 0xFF)
                return if (width > 0 && height > 0) width to height else null
            }
            if (marker == 0xDA) return null
            i += 2 + length
        }
        return null
    }

    /** A few numbers that change with any byte, for [PcScreen.hash] to tell a new picture from the same one. */
    fun fingerprint(jpeg: ByteArray): IntArray {
        var h = -0x340d631b7bdddcdbL
        for (b in jpeg) h = (h xor (b.toLong() and 0xFF)) * 0x100000001b3L
        return intArrayOf((h and 0xFFFFFF).toInt(), ((h ushr 24) and 0xFFFFFF).toInt(), ((h ushr 48) and 0xFFFF).toInt(), jpeg.size and 0xFFFFFF)
    }
}

/** One gst-launch-1.0 and its pictures. The newest picture is kept; older ones are dropped. */
class GstJpegPipe(private val process: Process) {
    private val latest = AtomicReference<Pair<Long, ByteArray>?>(null)
    @Volatile private var errors = ""
    private val lock = Object()

    init {
        thread(isDaemon = true, name = "dysk-gst-out") {
            val reader = MultipartJpegReader(process.inputStream)
            var seq = 0L
            runCatching {
                while (true) {
                    val part = reader.next() ?: break
                    if (!JpegFrames.looksLikeJpeg(part)) continue
                    latest.set(++seq to part)
                    synchronized(lock) { lock.notifyAll() }
                }
            }
            synchronized(lock) { lock.notifyAll() }
        }
        thread(isDaemon = true, name = "dysk-gst-err") {
            runCatching { process.errorStream.bufferedReader().forEachLine { line -> errors = (errors + "\n" + line).takeLast(600) } }
        }
    }

    val isAlive: Boolean get() = process.isAlive
    val errorText: String get() = errors.trim()

    /** The newest picture, waiting up to [waitMs] for a first one. */
    fun frame(waitMs: Long): Pair<Long, ByteArray>? {
        latest.get()?.let { return it }
        synchronized(lock) { if (latest.get() == null && process.isAlive) lock.wait(waitMs) }
        return latest.get()
    }

    fun stop() {
        runCatching { process.destroy() }
        runCatching { if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly() }
    }
}

/**
 * This PC's screen on Wayland through the ScreenCast portal and GStreamer. Started by the first picture asked for,
 * which waits while the user answers the dialog; with the pointer allowed too ([withControl]) it's one RemoteDesktop
 * session with a stream, so taps on the phone land exactly where they were made.
 */
class PortalScreenSource(
    private val portal: () -> PortalClient?,
    private val gst: () -> GstCheck,
    /** The input side, to share one session and dialog with when phones may control this PC. */
    private val input: () -> WaylandInput?,
    private val withControl: () -> Boolean,
    private val launch: (List<String>) -> Process = { ProcessBuilder(it).start() },
) : PcScreenSource {
    private val _status = MutableStateFlow(PortalStatus())
    val status: StateFlow<PortalStatus> = _status.asStateFlow()

    @Volatile private var offer: PortalOffer? = null
    @Volatile private var session: PortalSession? = null
    @Volatile private var pipe: GstJpegPipe? = null
    @Volatile private var pipeSize: Pair<Int, Int>? = null
    @Volatile private var failure: String? = null
    @Volatile private var lastGrab = 0L
    @Volatile private var starting = false
    @Volatile private var releases = 0
    @Volatile private var releasedAt = 0L
    private var pipeFailures = 0

    /** The ScreenCast portal as offered, asked once. */
    fun screenCast(): PortalOffer = offer ?: (portal()?.offer(PortalBits.SCREEN_CAST) ?: PortalOffer(null)).also { offer = it }

    override fun refusal(): String? {
        if (screenCast().version == null) return PcScreen.WAYLAND_REFUSAL
        val check = gst()
        if (!check.launch) return PcScreen.waylandMissing("GStreamer (gst-launch-1.0)")
        if (!check.pipewiresrc) return PcScreen.waylandMissing("GStreamer's PipeWire plugin (pipewiresrc)")
        return null
    }

    override fun displays(): List<PcDisplay> {
        val stream = session?.takeIf { !it.closed }?.streams?.firstOrNull()
        val size = stream?.size ?: WaylandInjector.awtDisplays().firstOrNull()?.let { it.width to it.height } ?: (1920 to 1080)
        val (x, y) = stream?.position ?: (0 to 0)
        return listOf(PcDisplay(DISPLAY_ID, "Shared screen (${size.first}×${size.second})", main = true, x = x, y = y, width = size.first, height = size.second))
    }

    override fun failure(): String? = failure

    override fun grab(display: PcDisplay, maxLong: Int): ScreenGrab? {
        lastGrab = System.currentTimeMillis()
        val s = session() ?: return null
        val stream = s.streams.firstOrNull() ?: return null
        val wanted = stream.size?.let { JpegFrames.fit(it.first, it.second, maxLong) }
        var current = pipe
        if (current == null || !current.isAlive || pipeSize != wanted) {
            current?.let { old ->
                if (!old.isAlive && old.errorText.isNotEmpty()) CoreLog.w("DyskLink", "gst-launch-1.0 ended: ${old.errorText}")
                old.stop()
            }
            if (current != null && !current.isAlive && ++pipeFailures > 3) {
                failure = "GStreamer couldn't read the screen on the PC." + current.errorText.lines().lastOrNull { it.isNotBlank() }?.let { " $it" }.orEmpty()
                return null
            }
            val check = gst()
            val command = GstPipeline.command(stream.node, wanted?.first, wanted?.second, keepalive = check.keepalive)
            CoreLog.i("DyskLink", "Screen: ${command.joinToString(" ")}")
            current = runCatching { GstJpegPipe(launch(command)) }.onFailure { failure = "gst-launch-1.0 couldn't start on the PC." }.getOrNull() ?: return null
            pipe = current
            pipeSize = wanted
        }
        val (_, jpeg) = current.frame(FIRST_FRAME_MS) ?: return null
        pipeFailures = 0
        val (width, height) = JpegSize.of(jpeg) ?: wanted ?: return null
        return ScreenGrab(width, height, JpegSize.fingerprint(jpeg)) { quality -> reencode(jpeg, quality) }
    }

    /** The picture as GStreamer made it, or smaller when the network wants much lower quality. */
    private fun reencode(jpeg: ByteArray, quality: Int): ByteArray? {
        if (quality >= GstPipeline.QUALITY - 15) return jpeg
        val image = runCatching { ImageIO.read(ByteArrayInputStream(jpeg)) }.getOrNull() ?: return jpeg
        val rgb = java.awt.image.BufferedImage(image.width, image.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        return ScreenPictures.jpeg(rgb, quality) ?: jpeg
    }

    /** The session, started here if there's none; waits while the user is asked. Null when it was refused. */
    private fun session(): PortalSession? {
        session?.takeIf { !it.closed }?.let { return it }
        if (failure != null) return null
        synchronized(this) {
            if (!starting) {
                starting = true
                _status.value = PortalStatus(PortalPhase.Waiting)
                thread(isDaemon = true, name = "dysk-portal-screen") { start() }
            }
        }
        // The sender's thread waits here; an answer or release() ends the wait.
        val gen = releases
        val until = System.currentTimeMillis() + ANSWER_WAIT_MS
        while (System.currentTimeMillis() < until && starting && releases == gen) {
            session?.takeIf { !it.closed }?.let { return it }
            Thread.sleep(150)
        }
        return session?.takeIf { !it.closed }
    }

    private fun start() {
        val startedAt = System.currentTimeMillis()
        try {
            val client = portal() ?: throw PortalRefused(false, "No session bus.")
            val cast = screenCast()
            val wayland = input()
            val combined = withControl() && wayland?.route?.value == InputRoute.PORTAL && wayland.remoteDesktop.version != null
            val onClosed: (PortalSession) -> Unit = { closed ->
                if (session === closed) {
                    failure = "Sharing the screen was stopped on the PC."
                    teardown()
                }
            }
            val started = if (combined) client.startRemoteDesktop(cast, onClosed) else client.startScreenCast(cast, onClosed)
            if (started.streams.isEmpty()) {
                started.close()
                throw PortalRefused(false, "No screen was shared.")
            }
            session = started
            if (started.hasPointer) wayland?.portalBackend?.screenSession = started
            _status.value = PortalStatus(PortalPhase.Allowed)
            // Nobody waits for it any more: the phone gave up while the user was asked.
            if (releasedAt > startedAt && lastGrab < releasedAt) teardown()
        } catch (e: PortalRefused) {
            failure = if (e.cancelled) PcScreen.WAYLAND_DENIED else if (e.message == "No answer from the system dialog.") PcScreen.WAYLAND_NO_ANSWER
                else "The PC couldn't share its screen: ${e.message}"
            _status.value = PortalStatus(if (e.cancelled) PortalPhase.Denied else PortalPhase.Failed, e.message)
        } catch (e: Exception) {
            CoreLog.w("DyskLink", "Screen cast portal failed", e)
            failure = "The PC couldn't share its screen."
            _status.value = PortalStatus(PortalPhase.Failed, e.message)
        } finally {
            starting = false
        }
    }

    /**
     * The phone stopped viewing. The stream is kept a moment, so picking another display or coming back doesn't ask
     * or start GStreamer again; then everything stops and the next viewer starts fresh.
     */
    override fun release() {
        val at = System.currentTimeMillis()
        releasedAt = at
        releases++
        thread(isDaemon = true, name = "dysk-screen-release") {
            Thread.sleep(KEEP_MS)
            if (lastGrab <= at) teardown()
        }
    }

    @Synchronized
    private fun teardown() {
        pipe?.stop()
        pipe = null
        pipeSize = null
        pipeFailures = 0
        val s = session
        session = null
        input()?.portalBackend?.let { if (it.screenSession === s) it.screenSession = null }
        s?.close()
        failure = null
        if (_status.value.phase == PortalPhase.Allowed) _status.value = PortalStatus()
    }

    /** "Ask again" on the hint: the next viewer is asked again. */
    fun retry() {
        failure = null
        _status.value = PortalStatus()
    }

    companion object {
        /** One display: the monitor the user picked in the portal's dialog. */
        const val DISPLAY_ID = 0x5741594CL
        const val FIRST_FRAME_MS = 3_000L
        const val ANSWER_WAIT_MS = 180_000L
        const val KEEP_MS = 4_000L
    }
}
