package id.phonelink.android.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.util.Range
import android.view.OrientationEventListener
import android.view.Surface
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import id.phonelink.android.MainActivity
import id.phonelink.android.R
import id.phonelink.android.core.CameraMath
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

/** What the camera stream is doing, for the phone screen. */
data class CameraStatus(val peerId: String, val front: Boolean, val torch: Boolean)

/** Streams a phone camera to one Mac as H.264. Android shows the camera indicator while it runs. */
class CameraStreamService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val thread = HandlerThread("dysk-camera").apply { start() }
    private val handler = Handler(thread.looper)

    private var peerId: String? = null
    private var front = false
    private var torch = false
    private var sensorOrientation = 90
    private var deviceDegrees = 0
    private var sentRotation = -1
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var encoder: MediaCodec? = null
    private var surface: Surface? = null
    private var streamer: ScreenStreamer? = null
    private var size = ScreenInfo(0, 0)
    private var failure: String? = null
    private var orientation: OrientationEventListener? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_SWITCH -> { handler.post { switchLens() }; return START_NOT_STICKY }
            ACTION_TORCH -> { handler.post { toggleTorch() }; return START_NOT_STICKY }
        }
        val peer = intent?.getStringExtra(EXTRA_PEER)
        if (peer == null || peerId != null) {
            if (peerId == null) stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0,
            )
        } catch (error: Exception) {
            // Android only allows the camera from the foreground, such as after a tap.
            Log.e(TAG, "Camera service refused", error)
            peerId = peer
            failure = "Open DyskLink on the phone and start the camera there."
            stopSelf()
            return START_NOT_STICKY
        }
        peerId = peer
        front = intent.getBooleanExtra(EXTRA_FRONT, false)
        startOrientation()
        handler.post { open() }
        return START_NOT_STICKY
    }

    private fun startOrientation() {
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(value: Int) {
                if (value == ORIENTATION_UNKNOWN) return
                handler.post {
                    deviceDegrees = value
                    val rotation = CameraMath.rotation(sensorOrientation, deviceDegrees, front)
                    if (rotation != sentRotation && encoder != null) sendStarted()
                }
            }
        }.also { if (it.canDetectOrientation()) it.enable() }
    }

    private fun cameraId(manager: CameraManager, wantFront: Boolean): String? {
        val wanted = if (wantFront) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        return manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted }
            ?: manager.cameraIdList.firstOrNull()
    }

    @SuppressLint("MissingPermission") // Checked just before.
    private fun open() {
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                error("Allow DyskLink to use the camera on the phone")
            }
            val manager = getSystemService(CameraManager::class.java)
            val id = cameraId(manager, front) ?: error("This phone has no camera")
            val chars = manager.getCameraCharacteristics(id)
            front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: error("The camera has no video sizes")
            val sizes = map.getOutputSizes(MediaCodec::class.java).orEmpty().map { it.width to it.height }
            val (width, height) = CameraMath.pickSize(sizes) ?: error("The camera has no video sizes")
            startEncoder(width, height)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    startSession(device)
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (camera === device) fail("Another app took the camera")
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (camera === device || camera == null) fail("The camera couldn't open (error $error)")
                }
            }, handler)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to open camera", error)
            fail(error.message ?: "The camera couldn't open")
        }
    }

    @Suppress("DEPRECATION") // The list form works on every supported Android version.
    private fun startSession(device: CameraDevice) {
        val target = surface ?: return
        device.createCaptureSession(listOf(target), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(created: CameraCaptureSession) {
                session = created
                repeat()
                current = this@CameraStreamService
                updateStatus()
                sendStarted()
            }
            override fun onConfigureFailed(created: CameraCaptureSession) = fail("The camera couldn't start")
        }, handler)
    }

    private fun repeat() {
        val device = camera ?: return
        val target = surface ?: return
        runCatching {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(target)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(FRAME_RATE, FRAME_RATE))
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                set(CaptureRequest.FLASH_MODE, if (torch && !front) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            }
            session?.setRepeatingRequest(request.build(), null, handler)
        }.onFailure { Log.e(TAG, "Camera request failed", it) }
    }

    private fun sendStarted() {
        val rotation = CameraMath.rotation(sensorOrientation, deviceDegrees, front)
        sentRotation = rotation
        send(Envelope(MessageType.CAMERA_STARTED, screen = size, text = if (front) "front" else "back", rotation = rotation))
    }

    private fun send(envelope: Envelope): Boolean {
        val peer = peerId ?: return false
        return PhoneLinkService.connectionManager.value?.sendDirect(peer, envelope) ?: false
    }

    private fun startEncoder(width: Int, height: Int) {
        size = ScreenInfo(width, height)
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * BITS_PER_PIXEL).coerceAtMost(MAX_BITRATE))
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_SECONDS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val stream = ScreenStreamer(scope, ::send, ::requestKeyFrame, MessageType.CAMERA_FRAME, resync = {
            send(Envelope(MessageType.CAMERA_STARTED, screen = size, text = if (front) "front" else "back", rotation = sentRotation.coerceAtLeast(0)))
        }) { handler.post { stopSelf() } }
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val buffer = runCatching { codec.getOutputBuffer(index) }.getOrNull() ?: return
                if (info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.get(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) stream.onConfig(bytes)
                    else stream.onFrame(bytes, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                }
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
            override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) = fail("The phone's video encoder failed")
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
        }, handler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
        encoder = codec
        streamer = stream
    }

    fun requestKeyFrame() {
        runCatching { encoder?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    private fun switchLens() {
        if (peerId == null) return
        closeCamera()
        releaseEncoder()
        front = !front
        torch = false
        open()
    }

    private fun toggleTorch() {
        if (front) return
        torch = !torch
        repeat()
        updateStatus()
    }

    private fun updateStatus() {
        status.value = peerId?.let { CameraStatus(it, front, torch) }
    }

    private fun fail(message: String) {
        failure = message
        handler.post { stopSelf() }
    }

    private fun closeCamera() {
        runCatching { session?.close() }
        session = null
        runCatching { camera?.close() }
        camera = null
    }

    private fun releaseEncoder() {
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
            this, 0, Intent(this, CameraStreamService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, PhoneLinkService.SHARING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_glyph)
            .setContentTitle("Camera shared with Mac")
            .setContentText("The Mac sees what this camera sees.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (current === this) current = null
        status.value = null
        orientation?.disable()
        peerId?.let { peer ->
            PhoneLinkService.connectionManager.value?.post(peer, Envelope(MessageType.CAMERA_STOPPED, error = failure))
        }
        peerId = null
        handler.post {
            closeCamera()
            releaseEncoder()
            thread.quitSafely()
            scope.cancel()
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PhoneLink"
        private const val ACTION_STOP = "id.phonelink.android.STOP_CAMERA"
        private const val ACTION_SWITCH = "id.phonelink.android.SWITCH_CAMERA"
        private const val ACTION_TORCH = "id.phonelink.android.TORCH_CAMERA"
        private const val EXTRA_PEER = "peer"
        private const val EXTRA_FRONT = "front"
        private const val NOTIFICATION_ID = 6
        const val REQUEST_NOTIFICATION_ID = 7
        private const val FRAME_RATE = 30
        private const val KEY_FRAME_SECONDS = 3
        private const val BITS_PER_PIXEL = 5
        private const val MAX_BITRATE = 8_000_000

        private val status = MutableStateFlow<CameraStatus?>(null)
        val active: StateFlow<CameraStatus?> = status.asStateFlow()
        @Volatile private var current: CameraStreamService? = null

        fun start(context: Context, peerId: String, front: Boolean) {
            val intent = Intent(context, CameraStreamService::class.java).putExtra(EXTRA_PEER, peerId).putExtra(EXTRA_FRONT, front)
            ContextCompat.startForegroundService(context, intent)
        }

        private fun command(context: Context, action: String) {
            if (status.value == null) return
            context.startService(Intent(context, CameraStreamService::class.java).setAction(action))
        }

        fun stop(context: Context) = command(context, ACTION_STOP)
        fun switchLens(context: Context) = command(context, ACTION_SWITCH)
        fun toggleTorch(context: Context) = command(context, ACTION_TORCH)

        /** Messages from a Mac. A Mac can only control its own stream, and can't start one without a tap on the phone. */
        fun handle(context: Context, peerId: String, envelope: Envelope) {
            val mine = status.value?.peerId == peerId
            when (envelope.type) {
                MessageType.CAMERA_REQUEST -> if (mine) current?.let { it.handler.post { it.sendStarted(); it.requestKeyFrame() } } else notifyRequest(context, peerId)
                MessageType.CAMERA_SYNC -> if (mine) current?.requestKeyFrame()
                MessageType.CAMERA_STOP -> if (mine) stop(context)
                MessageType.CAMERA_CONTROL -> if (mine) when (envelope.text) {
                    "switch" -> switchLens(context)
                    "torch" -> toggleTorch(context)
                }
            }
        }

        private fun notifyRequest(context: Context, peerId: String) {
            val name = PhoneLinkService.connectionManager.value?.peers?.value?.firstOrNull { it.id == peerId }?.name ?: "Your Mac"
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_CAMERA_PEER, peerId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val notification = Notification.Builder(context, PhoneLinkService.SCREEN_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_logo_glyph)
                .setContentTitle("$name wants to use this camera")
                .setContentText("Tap to start the camera. Nothing is shared until you tap.")
                .setContentIntent(PendingIntent.getActivity(context, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(REQUEST_NOTIFICATION_ID, notification)
        }
    }
}
