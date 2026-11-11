package id.phonelink.android.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import id.phonelink.android.MainActivity
import id.phonelink.android.R
import id.phonelink.android.core.Envelope
import id.phonelink.android.core.MessageType
import id.phonelink.android.core.ScreenInfo
import id.phonelink.android.core.ScreenStreamer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max

/** Records the screen after the user approves the Android dialog. Android shows a recording indicator. */
class ScreenCaptureService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val thread = HandlerThread("dysk-screen").apply { start() }
    private val handler = Handler(thread.looper)

    private var peerId: String? = null
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var surface: Surface? = null
    private var streamer: ScreenStreamer? = null
    private var size = ScreenInfo(0, 0)
    private var failure: String? = null
    private var sound: PlaybackPump? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        // Called when the user stops recording from the system, such as the status bar.
        override fun onStop() {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        val peer = intent?.getStringExtra(EXTRA_PEER)
        val busyWith = peerId
        if (busyWith != null && peer != null && peer != busyWith) {
            // Already sharing with another computer: tell this one instead of leaving it waiting.
            val other = PhoneLinkService.connectionManager.value?.peers?.value?.firstOrNull { it.id == busyWith }?.name ?: "another computer"
            PhoneLinkService.connectionManager.value?.sendDirect(
                peer, Envelope(MessageType.SCREEN_STOPPED, error = "The phone is already sharing its screen with $other. Stop that first."),
            )
            return START_NOT_STICKY
        }
        if (code != Activity.RESULT_OK || data == null || peer == null || projection != null || busyWith != null) {
            if (projection == null && busyWith == null) stopSelf()
            return START_NOT_STICKY
        }
        // Android requires this service to be in the foreground before the recording permission is used.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
        )
        peerId = peer
        handler.post { begin(code, data, peer) }
        return START_NOT_STICKY
    }

    private fun begin(code: Int, data: Intent, peer: String) {
        try {
            val capture = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
                ?: error("Recording permission is unavailable")
            capture.registerCallback(projectionCallback, handler)
            projection = capture
            startEncoder()
            display = capture.createVirtualDisplay(
                "DyskLink", size.width, size.height, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler,
            )
            current = this
            active.value = peer
            sendStarted()
            if (AudioShare.soundWithScreen(this)) {
                val pump = PlaybackPump(peer)
                val problem = pump.start(capture)
                if (problem == null) sound = pump else Log.w(TAG, "Screen goes without sound: $problem")
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to start recording", error)
            failure = error.message ?: "Failed to start screen recording"
            stopSelf()
        }
    }

    private fun send(envelope: Envelope): Boolean {
        val peer = peerId ?: return false
        return PhoneLinkService.connectionManager.value?.sendDirect(peer, envelope) ?: false
    }

    private fun sendStarted() {
        send(Envelope(MessageType.SCREEN_STARTED, screen = size))
    }

    private fun startEncoder() {
        size = captureSize()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (size.width * size.height * BITS_PER_PIXEL).coerceAtMost(MAX_BITRATE))
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_SECONDS)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            // A still screen keeps producing frames so the Mac window isn't left waiting.
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000L)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val stream = ScreenStreamer(scope, ::send, ::requestKeyFrame, resync = { send(Envelope(MessageType.SCREEN_STARTED, screen = size)) }) {
            handler.post { stopSelf() }
        }
        codec.setCallback(EncoderOutput(stream), handler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
        encoder = codec
        streamer = stream
    }

    private inner class EncoderOutput(private val stream: ScreenStreamer) : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val buffer = codec.getOutputBuffer(index) ?: return codec.releaseOutputBuffer(index, false)
            if (info.size > 0) {
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    stream.onConfig(bytes)
                } else {
                    stream.onFrame(bytes, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                }
            }
            codec.releaseOutputBuffer(index, false)
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
            Log.e(TAG, "Encoder failed", error)
            failure = "The phone's video encoder failed"
            stopSelf()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
    }

    private fun requestKeyFrame() {
        runCatching {
            encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    /** When the phone rotates, the encoder is recreated at the new size. The recording permission stays. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.post {
            val target = display ?: return@post
            if (captureSize() == size) return@post
            try {
                target.surface = null
                releaseEncoder()
                startEncoder()
                target.resize(size.width, size.height, resources.displayMetrics.densityDpi)
                target.surface = surface
                sendStarted()
            } catch (error: Exception) {
                Log.e(TAG, "Failed to adjust rotation", error)
                failure = "Failed to adjust screen rotation"
                stopSelf()
            }
        }
    }

    @Suppress("DEPRECATION") // currentWindowMetrics only exists since Android 11.
    private fun captureSize(): ScreenInfo {
        val manager = getSystemService(WindowManager::class.java)
        val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            DisplayMetrics().also { manager.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
        }
        val scale = (MAX_LONG_SIDE.toFloat() / max(width, height)).coerceAtMost(1f)
        // The H.264 encoder needs even dimensions.
        return ScreenInfo((width * scale).toInt() and 1.inv(), (height * scale).toInt() and 1.inv())
    }

    private fun releaseEncoder() {
        // Quietly: a new encoder replaces this one when the phone turns, and sharing goes on.
        streamer?.close(notify = false)
        streamer = null
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        encoder = null
        runCatching { surface?.release() }
        surface = null
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, PhoneLinkService.SHARING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_glyph)
            .setContentTitle("Screen shared with Mac")
            .setContentText("Everything on your screen is visible on the Mac.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (current === this) current = null
        active.value = null
        // The Mac is told even if recording fails before it starts.
        peerId?.let { peer ->
            val message = Envelope(MessageType.SCREEN_STOPPED, error = failure)
            PhoneLinkService.connectionManager.value?.post(peer, message)
        }
        sound?.stop()
        sound = null
        peerId = null
        val release = {
            runCatching { display?.release() }
            releaseEncoder()
            runCatching {
                projection?.unregisterCallback(projectionCallback)
                projection?.stop()
            }
            thread.quitSafely()
            scope.cancel()
        }
        handler.post { release() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PhoneLink"
        private const val ACTION_STOP = "id.phonelink.android.STOP_SCREEN"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_PEER = "peer"
        private const val NOTIFICATION_ID = 4
        private const val MAX_LONG_SIDE = 1280
        private const val FRAME_RATE = 60
        /** Key frames are large, so frequent ones stutter. A dropped frame requests one when needed. */
        private const val KEY_FRAME_SECONDS = 10
        private const val BITS_PER_PIXEL = 6
        private const val MAX_BITRATE = 8_000_000

        private val active = MutableStateFlow<String?>(null)
        val activePeer: StateFlow<String?> = active.asStateFlow()
        @Volatile private var current: ScreenCaptureService? = null

        /** Called after the user approves the Android dialog. */
        fun start(context: Context, code: Int, data: Intent, peerId: String) {
            val intent = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_CODE, code)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_PEER, peerId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }

        /** A Mac can only stop its own session. */
        fun stop(context: Context, peerId: String) {
            if (active.value == peerId) stop(context)
        }

        fun requestSync(peerId: String) {
            val service = current ?: return
            if (active.value == peerId) service.requestKeyFrame()
        }
    }
}
