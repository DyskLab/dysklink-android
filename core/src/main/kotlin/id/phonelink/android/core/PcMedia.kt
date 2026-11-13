package id.phonelink.android.core

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * What a Windows or Linux computer gets from the phone beyond the clipboard, files, notifications and calls: the
 * screen and control, the camera, the gallery, SMS, contacts, and browsing files; and what it serves the phone: its
 * sound, commands, a terminal, its screen, and the notch tools. A Mac gets all of it as it always has. A PC gets each one only
 * when its hello listed the matching `pc-*` cap, so older desktop builds are served exactly what they were before.
 * The phone's own checks (feature switches, Android's recording dialog, the camera tap) still apply on top.
 */
object PcRules {
    val SCREEN = setOf(MessageType.SCREEN_REQUEST, MessageType.SCREEN_SYNC, MessageType.SCREEN_STOP, MessageType.CONTROL)
    val CAMERA = setOf(MessageType.CAMERA_REQUEST, MessageType.CAMERA_SYNC, MessageType.CAMERA_STOP, MessageType.CAMERA_CONTROL)
    val GALLERY = setOf(MessageType.GALLERY_LIST, MessageType.GALLERY_GET)
    val SMS = setOf(MessageType.SMS_LIST, MessageType.SMS_SEND)
    /** What a PC that serves commands and a terminal sends the phone; the phone drops them from any other PC. */
    val COMMANDS = setOf(MessageType.COMMANDS_LIST, MessageType.COMMAND_RESULT)
    val TERMINAL = setOf(MessageType.TERMINAL_OPENED, MessageType.TERMINAL_OUTPUT, MessageType.TERMINAL_CLOSED)
    /** The PC's own screen on the phone; the phone drops it from a PC that didn't list `pc-screen-out`. */
    val SCREEN_OUT = setOf(MessageType.MACSCREEN_STARTED, MessageType.MACSCREEN_FRAME, MessageType.MACSCREEN_STOPPED)
    /** The PC's notch tools on the phone; dropped from a PC that didn't list `pc-notch`. */
    val NOTCH = setOf(MessageType.NOTCH_STATE)
    /** The phone's contacts and files, served only to a PC that listed `pc-contacts` or `pc-files`. */
    val CONTACTS = setOf(MessageType.CONTACTS_LIST)
    val FILES = setOf(MessageType.FS_LIST, MessageType.FS_GET)

    /** The cap a PC needs for a message it sends to the phone, or null when every computer may send it. */
    fun capFor(type: String): String? = when (type) {
        in SCREEN -> PhoneLinkProtocol.CAP_PC_SCREEN
        in CAMERA -> PhoneLinkProtocol.CAP_PC_CAMERA
        in GALLERY -> PhoneLinkProtocol.CAP_PC_GALLERY
        in SMS -> PhoneLinkProtocol.CAP_PC_SMS
        in COMMANDS -> PhoneLinkProtocol.CAP_PC_COMMANDS
        in TERMINAL -> PhoneLinkProtocol.CAP_PC_TERMINAL
        in SCREEN_OUT -> PhoneLinkProtocol.CAP_PC_SCREEN_OUT
        in NOTCH -> PhoneLinkProtocol.CAP_PC_NOTCH
        in CONTACTS -> PhoneLinkProtocol.CAP_PC_CONTACTS
        in FILES -> PhoneLinkProtocol.CAP_PC_FILES
        else -> null
    }

    /** Whether a paired computer ([platform] as stored at pairing, [caps] from its hello) may send [type]. */
    fun allows(platform: String?, caps: List<String>?, type: String): Boolean {
        val cap = capFor(type) ?: return true
        return receives(platform, caps, cap)
    }

    /** `control` kinds for several fingers at once (game keys). */
    val MULTI_TOUCH = setOf("tdown", "tmove", "tup")

    /**
     * Whether a computer may send a `control` of [kind]. Several fingers ([MULTI_TOUCH]) need `pc-gamekeys` from a PC on
     * top of `pc-screen`; older desktop builds never sent them. A Mac always may, as before.
     */
    fun allowsControl(platform: String?, caps: List<String>?, kind: String?): Boolean =
        kind !in MULTI_TOUCH || receives(platform, caps, PhoneLinkProtocol.CAP_PC_GAMEKEYS)

    /** Whether a computer gets what [cap] covers: always a Mac, and a PC that listed it. */
    fun receives(platform: String?, caps: List<String>?, cap: String): Boolean =
        platform == "macos" || (platform in PhoneLinkProtocol.COMPUTERS && caps?.contains(cap) == true)

    /**
     * Whether a computer offers what [cap] covers to the phone (`pc-sound-out`, `pc-commands`, `pc-terminal`): always
     * a Mac, and a PC that listed it. An older PC build refuses or never answers, so the phone doesn't offer it there.
     */
    fun serves(platform: String?, caps: List<String>?, cap: String): Boolean = receives(platform, caps, cap)

    /** Whether video for [cap] goes as JPEG frames: only to a PC that listed it. A Mac keeps H.264. */
    fun jpeg(platform: String?, caps: List<String>?, cap: String): Boolean =
        platform != "macos" && platform in PhoneLinkProtocol.COMPUTERS && caps?.contains(cap) == true
}

/** JPEG frames for PCs: every frame a whole picture, so nothing native is needed to show it. */
object JpegFrames {
    const val FORMAT = "jpeg"
    /** The picture's long side, as for the Mac's H.264. */
    const val MAX_LONG_SIDE = 1280
    /** As [ScreenStreamer.MAX_RAW_BYTES]: still fits in a 1 MiB frame as base64. Bigger frames are dropped. */
    const val MAX_BYTES = 760 * 1024

    /** [width] × [height] scaled down so the long side is at most [maxLong], kept even. */
    fun fit(width: Int, height: Int, maxLong: Int = MAX_LONG_SIDE): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 0 to 0
        val scale = (maxLong.toFloat() / maxOf(width, height)).coerceAtMost(1f)
        return maxOf(2, (width * scale).toInt() and 1.inv()) to maxOf(2, (height * scale).toInt() and 1.inv())
    }

    /** True when [bytes] start like a JPEG (SOI marker), so anything else, such as H.264 from an older phone, is refused. */
    fun looksLikeJpeg(bytes: ByteArray?): Boolean =
        bytes != null && bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

    /**
     * The picture in a frame message marked `format: "jpeg"` (raw, or base64 in `video`), or null for anything else,
     * such as the Mac's H.264. Decodes base64, so it's called off the connection's reading thread.
     */
    fun pictureOf(envelope: Envelope): ByteArray? {
        if (envelope.format != FORMAT) return null
        return envelope.payload()?.takeIf(::looksLikeJpeg)
    }
}

/**
 * Picks the JPEG quality and frame rate, like the Mac's bitrate adaptation: a frame that can't be queued (the
 * network is behind) or is too big lowers the quality, and below the lowest quality the frame rate; a run of frames
 * that went out raises them again. Not thread safe on its own; [JpegStreamer] calls it from one thread, except
 * [sent], which only touches volatile fields.
 */
class JpegPacer(
    private val maxFps: Int = 15,
    private val minFps: Int = 5,
    private val maxQuality: Int = 80,
    private val minQuality: Int = 35,
    startQuality: Int = 70,
) {
    @Volatile var quality: Int = startQuality.coerceIn(minQuality, maxQuality)
        private set
    @Volatile var fps: Int = maxFps
        private set
    @Volatile private var good = 0
    private var lastAt = Long.MIN_VALUE / 2

    val intervalMs: Long get() = 1000L / fps

    /** Whether the next picture should be encoded at [now] (milliseconds). */
    fun due(now: Long): Boolean = now - lastAt >= intervalMs

    /** How long until [due] says yes. */
    fun waitMs(now: Long): Long = (lastAt + intervalMs - now).coerceAtLeast(0)

    /** A picture was taken for encoding at [now]. */
    fun taken(now: Long) { lastAt = now }

    fun sent() {
        if (++good < RAISE_AFTER) return
        good = 0
        if (fps < maxFps) fps = minOf(maxFps, fps + 2) else quality = minOf(maxQuality, quality + 5)
    }

    /** The queue was full: the network can't keep up. */
    fun dropped() {
        good = 0
        if (quality > minQuality) quality = maxOf(minQuality, quality - 10) else fps = maxOf(minFps, fps - 2)
    }

    /** The picture was over [JpegFrames.MAX_BYTES]. */
    fun tooBig() {
        good = 0
        quality = maxOf(minQuality, quality - 20)
    }

    private companion object {
        const val RAISE_AFTER = 30
    }
}

/**
 * Sends JPEG frames to a PC without building up delay: one frame waits while one goes out, and a newer frame that
 * finds the queue full is dropped (and the [pacer] backs off). Every frame stands alone, so a dropped one costs nothing.
 */
class JpegStreamer(
    scope: CoroutineScope,
    private val send: (Envelope) -> Boolean,
    private val frameType: String = MessageType.SCREEN_FRAME,
    val pacer: JpegPacer = JpegPacer(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val onClosed: () -> Unit,
) {
    private val queue = Channel<ByteArray>(1)
    private val closed = AtomicBoolean(false)

    init {
        scope.launch(Dispatchers.IO) {
            for (jpeg in queue) {
                if (!send(Envelope(frameType, format = JpegFrames.FORMAT, raw = jpeg))) { close(); break }
                pacer.sent()
            }
        }
    }

    val quality: Int get() = pacer.quality
    val isClosed: Boolean get() = closed.get()

    /** Whether a picture should be encoded now. */
    fun due(): Boolean = !closed.get() && pacer.due(clock())

    fun waitMs(): Long = pacer.waitMs(clock())

    /** Queues an encoded picture. False when it was dropped. */
    fun offer(jpeg: ByteArray): Boolean {
        if (closed.get()) return false
        pacer.taken(clock())
        if (jpeg.size > JpegFrames.MAX_BYTES) { pacer.tooBig(); return false }
        if (queue.trySend(jpeg).isFailure) { pacer.dropped(); return false }
        return true
    }

    /** [notify] false closes without stopping the sharing, for when the phone turns and a new size takes over. */
    fun close(notify: Boolean = true) {
        if (closed.compareAndSet(false, true)) {
            queue.close()
            if (notify) onClosed()
        }
    }
}

/** Camera pictures (YUV_420_888 planes) to NV21, which Android's YuvImage turns into JPEG. */
object Yuv {
    /**
     * [y], [u], and [v] are read with absolute gets, so their positions don't matter. Strides are as Android's
     * Image.Plane gives them; the chroma planes are half size both ways.
     */
    fun nv21(width: Int, height: Int, y: ByteBuffer, yRowStride: Int, u: ByteBuffer, v: ByteBuffer, uvRowStride: Int, uvPixelStride: Int): ByteArray {
        val out = ByteArray(width * height * 3 / 2)
        var at = 0
        for (row in 0 until height) {
            val start = row * yRowStride
            if (start + width <= y.limit()) {
                val slice = y.duplicate()
                slice.position(start)
                slice.get(out, at, width)
            }
            at += width
        }
        for (row in 0 until height / 2) {
            val start = row * uvRowStride
            for (col in 0 until width / 2) {
                val index = start + col * uvPixelStride
                out[at++] = if (index < v.limit()) v.get(index) else 0
                out[at++] = if (index < u.limit()) u.get(index) else 0
            }
        }
        return out
    }
}
