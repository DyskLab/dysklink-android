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
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Looper
import android.widget.Toast
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.OrientationEventListener
import android.view.Surface
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import id.phonelink.android.MainActivity
import id.phonelink.android.R
import id.phonelink.android.core.CameraMath
import id.phonelink.android.core.CameraState
import id.phonelink.android.core.Envelope
import id.phonelink.android.core.JpegFrames
import id.phonelink.android.core.JpegStreamer
import id.phonelink.android.core.Yuv
import id.phonelink.android.core.ShotQueue
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
import kotlinx.coroutines.launch

/** What the camera stream is doing, for the phone screen. */
data class CameraStatus(val peerId: String, val front: Boolean, val torch: Boolean)

/**
 * Streams a phone camera to one Mac, or to another phone, as H.264. Android shows the camera indicator while it runs.
 * For a phone it also zooms, takes full-size photos, and records video, saving them here and sending them there.
 */
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

    // Another phone uses this camera: zoom, photos, and video, kept on this phone and sent to it.
    private var forPhone = false
    private var peerName = "Your Mac"
    private var zoom = 1f
    private var maxZoom = 1f
    private var zoomRatio = false
    private var activeArray: Rect? = null
    private var hasFlash = false
    private var stills: ImageReader? = null
    private val shots = ShotQueue()
    private var videoFormat: MediaFormat? = null
    private var recording: Recording? = null

    // A Windows or Linux PC gets JPEG frames instead of H.264 (see PcRules), made from YUV pictures.
    private var forPc = false
    private var yuv: ImageReader? = null
    private var jpegStream: JpegStreamer? = null
    private var drainPosted = false
    private val drainLater = Runnable { drainPosted = false; drain() }

    private class Recording(val video: RemoteCaptures.Video, val muxer: MediaMuxer, val track: Int) {
        var firstUs = -1L
        var waitingForKey = true
        var samples = 0
    }

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
        val manager = PhoneLinkService.connectionManager.value
        val who = manager?.peers?.value?.firstOrNull { it.id == peer }
        forPhone = who?.isPhone == true
        forPc = who?.pcCamera == true
        peerName = who?.name ?: if (forPhone) "the other phone" else "Your Mac"
        // A phone only gets the camera once it's allowed here, whatever started this.
        if (forPhone && manager?.phoneAccess?.of(peer)?.camera != true) {
            failure = "Using this camera is turned off on the phone."
            stopSelf()
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
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
                    if (rotation != sentRotation && (encoder != null || jpegStream != null)) sendStarted()
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
            val sizes = (if (forPc) map.getOutputSizes(ImageFormat.YUV_420_888) else map.getOutputSizes(MediaCodec::class.java))
                .orEmpty().map { it.width to it.height }
            val (width, height) = CameraMath.pickSize(sizes) ?: error("The camera has no video sizes")
            hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val ratios = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
            zoomRatio = ratios != null && ratios.upper > 1f
            maxZoom = (if (zoomRatio) ratios!!.upper else chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
                .coerceIn(1f, MAX_ZOOM)
            zoom = 1f
            if (forPhone) {
                // A second output at the largest JPEG size, for full-size photos while the video keeps going.
                val jpegs = map.getOutputSizes(ImageFormat.JPEG).orEmpty().map { it.width to it.height }
                CameraMath.largest(jpegs)?.let { (w, h) ->
                    stills = ImageReader.newInstance(w, h, ImageFormat.JPEG, 2).apply {
                        setOnImageAvailableListener({ reader -> onStill(reader) }, handler)
                    }
                }
            }
            if (forPc) startJpeg(width, height) else startEncoder(width, height)
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
        device.createCaptureSession(listOfNotNull(target, stills?.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(created: CameraCaptureSession) {
                session = created
                repeat()
                current = this@CameraStreamService
                updateStatus()
                sendStarted()
            }
            override fun onConfigureFailed(created: CameraCaptureSession) {
                // Some cameras can't feed video and full-size photos at once: video alone still works.
                if (stills != null && camera === device) {
                    Log.w(TAG, "Camera can't add full-size photos; streaming only")
                    runCatching { stills?.close() }
                    stills = null
                    startSession(device)
                } else {
                    fail("The camera couldn't start")
                }
            }
        }, handler)
    }

    private fun applyZoom(request: CaptureRequest.Builder) {
        if (zoom <= 1f) return
        if (zoomRatio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            request.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
            return
        }
        val area = activeArray ?: return
        val (left, top, width, height) = CameraMath.cropRegion(area.width(), area.height(), zoom).toList()
        request.set(CaptureRequest.SCALER_CROP_REGION, Rect(area.left + left, area.top + top, area.left + left + width, area.top + top + height))
    }

    private fun setZoom(value: Float?) {
        if (peerId == null || !forPhone) return
        val next = CameraMath.clampZoom(value, maxZoom)
        if (next == zoom) return
        zoom = next
        repeat()
        sendStarted()
    }

    /** A full-size photo for the other phone: saved in this gallery, then sent there. */
    private fun shutter(key: String?) {
        if (peerId == null) return
        shots.ask(key)?.let { send(it); return }
        val reader = stills
        val device = camera
        val running = session
        if (reader == null || device == null || running == null) {
            shots.failed("This camera can't take full-size photos")?.let(::send)
            return
        }
        runCatching {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                surface?.let(::addTarget)
                set(CaptureRequest.JPEG_ORIENTATION, CameraMath.rotation(sensorOrientation, deviceDegrees, front))
                set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.FLASH_MODE, if (torch && !front) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
                applyZoom(this)
            }
            running.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    shots.failed("The camera couldn't take the photo")?.let(::send)
                }
            }, handler)
        }.onFailure {
            Log.e(TAG, "Photo failed", it)
            shots.failed("The camera couldn't take the photo")?.let(::send)
        }
    }

    private fun onStill(reader: ImageReader) {
        val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: return
        val bytes = try {
            val buffer = image.planes[0].buffer
            ByteArray(buffer.remaining()).also(buffer::get)
        } finally {
            image.close()
        }
        val peer = peerId ?: return
        val who = peerName
        saving.launch {
            val name = ShotQueue.fileName(System.currentTimeMillis())
            val saved = runCatching { RemoteCaptures.savePhoto(this@CameraStreamService, name, bytes) }
                .onFailure { Log.e(TAG, "Couldn't save the photo", it) }
                .getOrNull()
            val reply = if (saved == null) shots.failed("The photo couldn't be saved on the phone") else shots.taken(name)
            val manager = PhoneLinkService.connectionManager.value
            reply?.let { manager?.post(peer, it) }
            if (saved != null) {
                manager?.fileTransfers?.sendFile(peer, saved.name, saved.size) { saved.open() }
                toast("Photo taken by $who")
            }
        }
    }

    private fun toast(text: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show() }
    }

    private fun startRecording() {
        val peer = peerId ?: return
        if (!forPhone || recording != null || encoder == null) return
        val format = videoFormat ?: run {
            send(ShotQueue.shot("video", error = "The video isn't ready yet. Try again.", kind = "video"))
            return
        }
        val name = ShotQueue.fileName(System.currentTimeMillis(), "mp4")
        val video = runCatching { RemoteCaptures.startVideo(this, name) }.getOrElse {
            Log.e(TAG, "Couldn't create the video", it)
            send(ShotQueue.shot("video", error = "The video couldn't be saved on the phone", kind = "video"))
            return
        }
        try {
            val muxer = MediaMuxer(video.descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(CameraMath.rotation(sensorOrientation, deviceDegrees, front))
            val track = muxer.addTrack(format)
            muxer.start()
            recording = Recording(video, muxer, track)
        } catch (error: Exception) {
            Log.e(TAG, "Couldn't start recording", error)
            video.discard()
            send(ShotQueue.shot("video", error = "The phone couldn't record video", kind = "video"))
            return
        }
        requestKeyFrame()
        handler.postDelayed(recordingLimit, MAX_RECORDING_MS)
        toast("Recording for $peerName")
        sendStarted()
        Log.i(TAG, "Recording for $peer")
    }

    private val recordingLimit = Runnable { stopRecording() }

    /** Writes one encoded frame into the video being recorded. On the camera thread. */
    private fun record(bytes: ByteArray, info: MediaCodec.BufferInfo, key: Boolean) {
        val active = recording ?: return
        if (active.waitingForKey && !key) return
        active.waitingForKey = false
        if (active.firstUs < 0) active.firstUs = info.presentationTimeUs
        val sample = MediaCodec.BufferInfo().apply {
            set(0, bytes.size, (info.presentationTimeUs - active.firstUs).coerceAtLeast(0), if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        runCatching { active.muxer.writeSampleData(active.track, java.nio.ByteBuffer.wrap(bytes), sample) }
            .onSuccess { active.samples++ }
            .onFailure { Log.w(TAG, "Recording frame lost", it) }
    }

    /** Ends the video: saved in this gallery and sent to the other phone, or dropped when nothing was recorded. */
    private fun stopRecording(peer: String? = peerId) {
        val active = recording ?: return
        recording = null
        handler.removeCallbacks(recordingLimit)
        val wrote = active.samples > 0 && runCatching { active.muxer.stop() }.isSuccess
        runCatching { active.muxer.release() }
        val who = peerName
        saving.launch {
            val manager = PhoneLinkService.connectionManager.value
            val saved = if (wrote) runCatching { active.video.finish() }.getOrNull() else null
            if (saved == null) {
                active.video.discard()
                peer?.let { manager?.post(it, ShotQueue.shot("video", error = "Nothing was recorded", kind = "video")) }
                return@launch
            }
            if (peer != null) {
                manager?.post(peer, ShotQueue.shot("video", name = saved.name, kind = "video"))
                manager?.fileTransfers?.sendFile(peer, saved.name, saved.size) { saved.open() }
            }
            toast("Video recorded by $who")
        }
        if (peerId != null) sendStarted()
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
                applyZoom(this)
            }
            session?.setRepeatingRequest(request.build(), null, handler)
        }.onFailure { Log.e(TAG, "Camera request failed", it) }
    }

    private fun sendStarted() {
        val rotation = CameraMath.rotation(sensorOrientation, deviceDegrees, front)
        sentRotation = rotation
        send(started(rotation))
    }

    /** A phone also gets zoom, torch, and recording; a PC gets JPEG and the torch; a Mac gets what it always did. */
    private fun started(rotation: Int): Envelope = Envelope(
        MessageType.CAMERA_STARTED, screen = size, text = if (front) "front" else "back", rotation = rotation,
        cameraState = when {
            forPhone -> CameraState(
                zoom = zoom, maxZoom = maxZoom, flash = hasFlash && !front, torch = torch && !front,
                recording = recording != null, stills = stills != null,
            )
            forPc -> CameraState(flash = hasFlash && !front, torch = torch && !front)
            else -> null
        },
        format = if (forPc) JpegFrames.FORMAT else null,
    )

    /** Pictures for a PC: YUV from the camera, made JPEG as often as the PC's link keeps up (see JpegPacer). */
    private fun startJpeg(width: Int, height: Int) {
        size = ScreenInfo(width, height)
        val images = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
        val stream = JpegStreamer(scope, ::send, MessageType.CAMERA_FRAME, clock = SystemClock::elapsedRealtime) {
            handler.post { stopSelf() }
        }
        images.setOnImageAvailableListener({ drain() }, handler)
        yuv = images
        surface = images.surface
        jpegStream = stream
    }

    /** On the camera thread. Pictures that come too soon are skipped; the newest one is taken when it's time. */
    private fun drain() {
        val stream = jpegStream ?: return
        val images = yuv ?: return
        if (!stream.due()) {
            // The camera keeps sending at 30 fps: older pictures are dropped so the camera never stalls.
            runCatching { images.acquireLatestImage() }.getOrNull()?.let { latest ->
                latest.close()
            }
            if (!drainPosted) {
                drainPosted = true
                handler.postDelayed(drainLater, stream.waitMs().coerceAtLeast(1))
            }
            return
        }
        val image = runCatching { images.acquireLatestImage() }.getOrNull() ?: return
        try {
            stream.offer(encodeJpeg(image, stream.quality))
        } catch (error: Exception) {
            Log.w(TAG, "Camera picture lost", error)
        } finally {
            image.close()
        }
    }

    private fun encodeJpeg(image: Image, quality: Int): ByteArray {
        val (y, u, v) = image.planes
        val nv21 = Yuv.nv21(image.width, image.height, y.buffer, y.rowStride, u.buffer, v.buffer, v.rowStride, v.pixelStride)
        val out = java.io.ByteArrayOutputStream(96 * 1024)
        YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null).compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
        return out.toByteArray()
    }

    private fun releaseJpeg() {
        handler.removeCallbacks(drainLater)
        drainPosted = false
        jpegStream?.close(notify = false)
        jpegStream = null
        if (yuv != null) surface = null
        runCatching { yuv?.close() }
        yuv = null
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
            send(started(sentRotation.coerceAtLeast(0)))
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
                    else {
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        stream.onFrame(bytes, key)
                        record(bytes, info, key)
                    }
                }
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
            override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) = fail("The phone's video encoder failed")
            // Carries SPS and PPS, which a recorded video needs.
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) { videoFormat = format }
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
        stopRecording()
        shots.clear("The lens changed before the photo").forEach(::send)
        closeCamera()
        releaseEncoder()
        front = !front
        torch = false
        open()
    }

    private fun toggleTorch() {
        if (front || ((forPhone || forPc) && !hasFlash)) return
        torch = !torch
        repeat()
        updateStatus()
        // A PC shows whether the torch is on.
        if (forPc) sendStarted()
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
        runCatching { stills?.close() }
        stills = null
    }

    private fun releaseEncoder() {
        releaseJpeg()
        stopRecording()
        videoFormat = null
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
            .setContentTitle(
                when {
                    forPhone -> "Camera used by $peerName"
                    forPc -> "Camera shared with $peerName"
                    else -> "Camera shared with Mac"
                },
            )
            .setContentText(
                when {
                    forPhone -> "$peerName sees what this camera sees and can take photos. Tap Stop to end it."
                    forPc -> "$peerName sees what this camera sees. Tap Stop to end it."
                    else -> "The Mac sees what this camera sees."
                },
            )
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
        val peer = peerId
        peer?.let {
            val manager = PhoneLinkService.connectionManager.value
            shots.clear("The camera stopped before the photo").forEach { shot -> manager?.post(it, shot) }
            manager?.post(it, Envelope(MessageType.CAMERA_STOPPED, error = failure))
        }
        peerId = null
        handler.post {
            // A video being recorded is kept and still sent.
            stopRecording(peer)
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
        private const val NOTIFICATION_ID = NotificationIds.CAMERA
        const val REQUEST_NOTIFICATION_ID = NotificationIds.CAMERA_REQUEST
        private const val FRAME_RATE = 30
        private const val KEY_FRAME_SECONDS = 3
        private const val BITS_PER_PIXEL = 5
        private const val MAX_BITRATE = 8_000_000
        private const val MAX_ZOOM = 10f
        private const val JPEG_QUALITY: Byte = 95
        /** Recordings end by themselves after this long, so a forgotten one doesn't fill the phone. */
        private const val MAX_RECORDING_MS = 10 * 60_000L

        /** Saving photos and videos outlives the service, which may stop right after the shutter. */
        private val saving = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

        /**
         * Messages from a Mac or a phone. Each can only control its own stream, and can't start one without a tap on
         * this phone. Zoom, photos, and video are only for a phone (the connection never passes them from a Mac).
         */
        fun handle(context: Context, peerId: String, envelope: Envelope) {
            val mine = status.value?.peerId == peerId
            val service = current?.takeIf { mine }
            when (envelope.type) {
                MessageType.CAMERA_REQUEST -> when {
                    service != null -> service.handler.post { service.sendStarted(); service.requestKeyFrame() }
                    // One at a time: the other device is told instead of a notification that can't start anything.
                    status.value != null -> PhoneLinkService.connectionManager.value?.post(
                        peerId, Envelope(MessageType.CAMERA_STOPPED, error = "The camera is in use. Try again when it's free."),
                    )
                    else -> notifyRequest(context, peerId)
                }
                MessageType.CAMERA_SYNC -> service?.requestKeyFrame()
                MessageType.CAMERA_STOP -> if (mine) stop(context)
                MessageType.CAMERA_CONTROL -> if (mine) when (envelope.text) {
                    "switch" -> switchLens(context)
                    "torch" -> toggleTorch(context)
                    "record.start" -> service?.let { it.handler.post { it.startRecording() } }
                    "record.stop" -> service?.let { it.handler.post { it.stopRecording() } }
                }
                MessageType.CAMERA_ZOOM -> service?.let { it.handler.post { it.setZoom(envelope.zoom) } }
                MessageType.CAMERA_SHUTTER -> if (service != null) {
                    service.handler.post { service.shutter(envelope.key) }
                } else {
                    PhoneLinkService.connectionManager.value?.post(peerId, ShotQueue.shot(envelope.key, error = "The camera isn't on"))
                }
            }
        }

        private fun notifyRequest(context: Context, peerId: String) {
            val peer = PhoneLinkService.connectionManager.value?.peers?.value?.firstOrNull { it.id == peerId }
            val name = peer?.name ?: "Your Mac"
            val intent = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_CAMERA_PEER, peerId)
                .putExtra(MainActivity.EXTRA_TOKEN, IntentTokens.shared.issue(IntentTokens.CAMERA, peerId))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val notification = Notification.Builder(context, PhoneLinkService.SCREEN_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_logo_glyph)
                .setContentTitle("$name wants to use this camera")
                .setContentText(
                    if (peer?.isPhone == true) "Tap to choose. The camera starts only after you tap, and it can take photos."
                    else "Tap to start the camera. Nothing is shared until you tap.",
                )
                .setContentIntent(PendingIntent.getActivity(context, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true)
                .build()
            context.getSystemService(NotificationManager::class.java).notify(REQUEST_NOTIFICATION_ID, notification)
        }
    }
}
