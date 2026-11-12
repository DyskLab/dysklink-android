package id.phonelink.android.core

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * A short queue of frames between the connection's reading thread and the decoder. When the decoder falls behind and
 * the queue is full, the queued frames are dropped and later ones skipped until the next key frame, so the reader
 * never waits (sound and input on the same connection keep flowing).
 */
class FrameQueue<T>(private val capacity: Int) {
    private val items = LinkedBlockingQueue<Pair<T, Boolean>>()
    private var dropping = false

    /** Queues a frame. Returns true when frames were just dropped, so the caller should ask for a key frame. */
    @Synchronized
    fun offer(frame: T, key: Boolean): Boolean {
        if (key) dropping = false
        if (dropping) return false
        if (items.size >= capacity) {
            items.clear()
            if (!key) {
                dropping = true
                return true
            }
        }
        items.offer(frame to key)
        return false
    }

    /** The next frame and whether it's a key frame, or null after [timeoutMs] with none. */
    fun poll(timeoutMs: Long): Pair<T, Boolean>? = items.poll(timeoutMs, TimeUnit.MILLISECONDS)

    val size: Int get() = items.size

    @Synchronized
    fun clear() {
        items.clear()
    }
}

/**
 * Decodes the Mac's H.264 (Annex B, SPS and PPS before each key frame) onto a surface.
 * Frames before the first key frame, or after a dropped one, are skipped until the next key frame.
 */
class MacScreenDecoder(private val surface: Surface, private val width: Int, private val height: Int, private val onNeedKey: () -> Unit) {
    private var codec: MediaCodec? = null
    private val running = AtomicBoolean(false)
    @Volatile private var waitingForKey = true
    @Volatile var framesShown = 0
        private set

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1024 * 1024)
            // Asks the decoder to show frames as soon as they're ready.
            runCatching { setInteger(MediaFormat.KEY_LOW_LATENCY, 1) }
        }
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, surface, null, 0)
        codec.start()
        this.codec = codec
        running.set(true)
        thread(isDaemon = true, name = "macscreen-out") { drain(codec) }
        thread(isDaemon = true, name = "macscreen-in") { pump() }
    }

    /** Each frame is raw H.264 (a binary frame) or still base64 (a JSON frame from an older Mac). */
    private val queue = FrameQueue<Any>(QUEUE_FRAMES)

    /**
     * Called on the connection's reading thread with a raw frame or one still in base64. Returns at once: decoding any
     * base64 and waiting for the codec happen on the decoder's own thread.
     */
    fun feedEncoded(frame: Any, key: Boolean) {
        if (!running.get()) return
        if (queue.offer(frame, key)) onNeedKey()
    }

    private fun pump() {
        val decoder = java.util.Base64.getDecoder()
        while (running.get()) {
            val (frame, key) = queue.poll(INPUT_POLL_MS) ?: continue
            val data = when (frame) {
                is ByteArray -> frame
                is String -> runCatching { decoder.decode(frame) }.getOrNull()
                else -> null
            } ?: continue
            runCatching { feed(data, key) }
        }
    }

    /** Hands one frame to the codec; runs on the decoder's input thread. */
    private fun feed(data: ByteArray, key: Boolean) {
        val codec = codec ?: return
        if (!running.get()) return
        if (waitingForKey && !key) return
        val index = runCatching { codec.dequeueInputBuffer(INPUT_WAIT_US) }.getOrDefault(-1)
        if (index < 0) {
            // The decoder is behind, so this frame is lost and the next ones can't be decoded until a key frame.
            waitingForKey = true
            onNeedKey()
            return
        }
        val buffer = codec.getInputBuffer(index) ?: return
        if (data.size > buffer.capacity()) {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            waitingForKey = true
            onNeedKey()
            return
        }
        buffer.clear()
        buffer.put(data)
        codec.queueInputBuffer(index, 0, data.size, System.nanoTime() / 1000, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        waitingForKey = false
    }

    private fun drain(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running.get()) {
            val index = runCatching { codec.dequeueOutputBuffer(info, OUTPUT_WAIT_US) }.getOrElse { return }
            if (index >= 0) {
                runCatching { codec.releaseOutputBuffer(index, true) }
                framesShown++
            }
        }
    }

    fun release() {
        if (!running.getAndSet(false)) return
        queue.clear()
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
    }

    private companion object {
        const val INPUT_WAIT_US = 30_000L
        const val INPUT_POLL_MS = 200L
        /** About a tenth of a second at the Mac's frame rate. */
        const val QUEUE_FRAMES = 4
        const val OUTPUT_WAIT_US = 20_000L
    }
}

/**
 * Shows a Windows or Linux PC's screen, which comes as whole JPEG pictures (`format: "jpeg"`). Only the newest picture
 * waits: one that arrives while another is decoded replaces it, so the picture never falls behind. Pictures are
 * decoded on this class's own thread, never on the connection's reader.
 */
class JpegScreenDecoder(private val onPicture: (android.graphics.Bitmap) -> Unit) {
    private val waiting = java.util.concurrent.ArrayBlockingQueue<id.phonelink.android.core.Envelope>(1)
    private val running = AtomicBoolean(true)
    @Volatile var framesShown = 0
        private set

    init {
        thread(isDaemon = true, name = "pcscreen-jpeg") {
            while (running.get()) {
                val envelope = runCatching { waiting.poll(200, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
                val jpeg = JpegFrames.pictureOf(envelope) ?: continue
                val bitmap = runCatching { android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }.getOrNull() ?: continue
                if (!running.get()) break
                framesShown++
                onPicture(bitmap)
            }
        }
    }

    /** Called on the connection's reading thread. Returns at once. */
    fun feed(envelope: id.phonelink.android.core.Envelope) {
        if (!running.get()) return
        while (!waiting.offer(envelope)) waiting.poll()
    }

    fun release() {
        running.set(false)
        waiting.clear()
    }
}

/** Maps a touch on the shown picture to 0…1 on the Mac's screen, with zoom and pan applied. */
object MacScreenMath {
    /** The picture's box when it fits the view, keeping its shape. Returns left, top, width, height. */
    fun fit(viewW: Float, viewH: Float, picW: Float, picH: Float): FloatArray {
        if (viewW <= 0 || viewH <= 0 || picW <= 0 || picH <= 0) return floatArrayOf(0f, 0f, viewW, viewH)
        val scale = minOf(viewW / picW, viewH / picH)
        val w = picW * scale
        val h = picH * scale
        return floatArrayOf((viewW - w) / 2, (viewH - h) / 2, w, h)
    }

    /**
     * The point on the Mac for a touch at (tx, ty) in the view. The picture is scaled by [zoom] around the view's
     * center and moved by (panX, panY). Null when the touch is outside the picture.
     */
    fun toMac(tx: Float, ty: Float, viewW: Float, viewH: Float, picW: Float, picH: Float, zoom: Float, panX: Float, panY: Float): Pair<Float, Float>? {
        val box = fit(viewW, viewH, picW, picH)
        // Undo the pan and zoom, which happen around the view's center.
        val cx = viewW / 2
        val cy = viewH / 2
        val x = (tx - panX - cx) / zoom + cx
        val y = (ty - panY - cy) / zoom + cy
        val nx = (x - box[0]) / box[2]
        val ny = (y - box[1]) / box[3]
        if (nx < 0f || nx > 1f || ny < 0f || ny > 1f) return null
        return nx to ny
    }

    /**
     * The size, in points, to ask the Mac for when this phone becomes an extra display: the phone's shape, 600 points
     * tall, which is the smallest sharp (2×) desktop macOS makes.
     */
    fun extraDisplaySize(longSide: Float, shortSide: Float, portrait: Boolean = false, large: Boolean = false): Pair<Int, Int> {
        val aspect = (longSide / shortSide.coerceAtLeast(1f)).coerceIn(1f, 4f)
        // Larger text: a smaller 1× desktop, so everything on it is bigger but less sharp.
        val short = if (large) LARGE_SHORT_SIDE else 600
        val long = (short * aspect).toInt() and 1.inv()
        return if (portrait) short to long else long to short
    }

    const val LARGE_SHORT_SIDE = 450

    /** Keeps the zoomed picture from being dragged off screen. */
    fun clampPan(pan: Float, zoom: Float, viewSize: Float): Float {
        val limit = viewSize * (zoom - 1) / 2
        return pan.coerceIn(-limit, limit)
    }
}
