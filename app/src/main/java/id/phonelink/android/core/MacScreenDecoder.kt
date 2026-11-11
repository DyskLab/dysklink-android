package id.phonelink.android.core

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

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
    }

    /** Called on the reading thread for each frame. */
    fun feed(data: ByteArray, key: Boolean) {
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
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
    }

    private companion object {
        const val INPUT_WAIT_US = 30_000L
        const val OUTPUT_WAIT_US = 20_000L
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
