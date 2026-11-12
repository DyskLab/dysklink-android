package id.phonelink.android.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import id.phonelink.android.core.CameraState
import id.phonelink.android.core.ControlEvent
import id.phonelink.android.core.Envelope
import id.phonelink.android.core.MacScreenDecoder
import id.phonelink.android.core.MacScreenMath
import id.phonelink.android.core.MessageType
import kotlin.math.abs
import kotlin.math.hypot

/** What the viewers of another phone's screen and camera need from the connection. */
class PhoneShareLink(
    val setSink: (((String, Envelope) -> Unit)?) -> Unit,
    val viewScreen: (String) -> Unit,
    val stopScreen: (String) -> Unit,
    val syncScreen: (String) -> Unit,
    val control: (String, ControlEvent) -> Unit,
    val useCamera: (String) -> Unit,
    val stopCamera: (String) -> Unit,
    val camera: (String, Envelope) -> Unit,
)

private sealed interface ShareState {
    data object Asking : ShareState
    data class Showing(val width: Int, val height: Int, val canControl: Boolean = false, val rotation: Int = 0, val front: Boolean = false, val camera: CameraState? = null) : ShareState
    data class Stopped(val message: String) : ShareState
}

/**
 * Stops when the app goes to the background, and doesn't start again by itself: the other phone would have to agree
 * again, so the user asks again when ready.
 */
@Composable
private fun StopInBackground(active: () -> Boolean, stop: () -> Unit) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && active()) stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/** The point on the other phone, 0 to 1, for a touch on the picture; kept on the picture's edge when outside. */
private fun toPhone(at: Offset, viewW: Float, viewH: Float, s: ShareState.Showing, zoom: Float, panX: Float, panY: Float): Pair<Float, Float> {
    val box = MacScreenMath.fit(viewW, viewH, s.width.toFloat(), s.height.toFloat())
    val cx = viewW / 2
    val cy = viewH / 2
    val x = (at.x - panX - cx) / zoom + cx
    val y = (at.y - panY - cy) / zoom + cy
    return ((x - box[0]) / box[2]).coerceIn(0f, 1f) to ((y - box[1]) / box[3]).coerceIn(0f, 1f)
}

/** Another phone's screen, live. With Control on, taps, drags, long presses, and two-finger scrolls go to it. */
@Composable
fun PhoneScreenScreen(peerId: String, name: String, connected: Boolean, link: PhoneShareLink?, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val main = remember { Handler(Looper.getMainLooper()) }
    val haptics = LocalHapticFeedback.current
    var state by remember { mutableStateOf<ShareState>(ShareState.Asking) }
    var surface by remember { mutableStateOf<android.view.Surface?>(null) }
    var controlling by remember { mutableStateOf(false) }
    var landscape by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var viewW by remember { mutableFloatStateOf(0f) }
    var viewH by remember { mutableFloatStateOf(0f) }
    val decoder = remember { arrayOfNulls<MacScreenDecoder>(1) }

    fun ask() {
        state = ShareState.Asking
        link?.viewScreen(peerId)
    }
    fun close() {
        link?.stopScreen(peerId)
        onBack()
    }
    BackHandler(onBack = ::close)

    val showing = state as? ShareState.Showing
    DisposableEffect(surface, showing?.width, showing?.height) {
        val target = surface
        if (target != null && showing != null) {
            decoder[0] = MacScreenDecoder(target, showing.width, showing.height) { link?.syncScreen(peerId) }.also {
                runCatching { it.start() }.onFailure { error -> state = ShareState.Stopped("This phone can't play the video: ${error.message}") }
            }
            link?.syncScreen(peerId)
        }
        onDispose {
            decoder[0]?.release()
            decoder[0] = null
        }
    }

    DisposableEffect(link, peerId, connected) {
        if (link == null || !connected) {
            state = ShareState.Stopped("$name isn't connected.")
            return@DisposableEffect onDispose {}
        }
        link.setSink { id, envelope ->
            if (id != peerId) return@setSink
            when (envelope.type) {
                // On the connection's reading thread: queued for the decoder, never waited on.
                MessageType.SCREEN_FRAME -> {
                    val frame: Any = envelope.raw ?: envelope.video ?: return@setSink
                    decoder[0]?.feedEncoded(frame, envelope.keyFrame == true)
                }
                MessageType.SCREEN_STARTED -> {
                    val info = envelope.screen ?: return@setSink
                    main.post {
                        val can = envelope.canControl == true
                        state = ShareState.Showing(info.width, info.height, can)
                        if (!can) controlling = false
                    }
                }
                MessageType.SCREEN_STOPPED -> main.post { state = ShareState.Stopped(envelope.error ?: "$name stopped sharing its screen.") }
            }
        }
        ask()
        onDispose {
            link.setSink(null)
            link.stopScreen(peerId)
        }
    }

    StopInBackground(active = { state !is ShareState.Stopped }) {
        link?.stopScreen(peerId)
        state = ShareState.Stopped("Stopped when DyskLink left the screen. Ask again to keep viewing.")
    }

    DisposableEffect(landscape) {
        activity?.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    fun touch(kind: String, at: Offset) {
        val s = state as? ShareState.Showing ?: return
        val (x, y) = toPhone(at, viewW, viewH, s, zoom, panX, panY)
        link?.control(peerId, ControlEvent(kind, x, y))
    }
    fun key(name: String) = link?.control(peerId, ControlEvent("key", key = name))

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Color.White, fontSize = 28.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = ::close).padding(horizontal = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    when (val s = state) {
                        is ShareState.Showing -> buildString {
                            append(if (controlling) "Controlling" else "Viewing")
                            if (zoom > 1.01f) append(" · ${"%.1f".format(zoom)}x")
                        }
                        ShareState.Asking -> "Waiting for $name…"
                        is ShareState.Stopped -> "Stopped"
                    },
                    color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1,
                )
            }
            if (zoom > 1.01f) ShareChip("1x") { zoom = 1f; panX = 0f; panY = 0f }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onSizeChanged { viewW = it.width.toFloat(); viewH = it.height.toFloat() }
                .pointerInput(state, controlling, link) {
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        val downAt = System.currentTimeMillis()
                        // 0 undecided, 1 one-finger touch on the other phone, 2 two-finger scroll, 3 zoom, 4 pan here
                        var mode = 0
                        var last = first.position
                        var lastCentroid = Offset.Zero
                        var lastSpread = 0f
                        var lastSent = 0L
                        var scrollAt = Offset.Zero
                        while (true) {
                            // A finger held still sends no events, so the long press is noticed by the clock too.
                            val waitMs = LONG_PRESS_MS - (System.currentTimeMillis() - downAt)
                            val event = if (controlling && mode == 0 && waitMs > 0) {
                                withTimeoutOrNull(waitMs) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }
                            if (event == null) {
                                mode = 1
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                touch("down", first.position)
                                continue
                            }
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            val now = System.currentTimeMillis()
                            if (pressed.size >= 2) {
                                val a = pressed[0].position
                                val b = pressed[1].position
                                val centroid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
                                val spread = hypot(a.x - b.x, a.y - b.y)
                                if (mode == 1) { touch("up", last); mode = 0 }
                                if (mode != 2 && mode != 3) {
                                    if (lastSpread == 0f) { lastSpread = spread; lastCentroid = centroid; continue }
                                    if (abs(spread / lastSpread - 1) > 0.12f) mode = 3
                                    else if ((centroid - lastCentroid).getDistance() > 14f) {
                                        mode = 2
                                        // A scroll on Android is one finger dragging: it starts where the two fingers are.
                                        scrollAt = lastCentroid
                                        if (controlling) touch("down", scrollAt)
                                    } else continue
                                }
                                val delta = centroid - lastCentroid
                                if (mode == 3 || !controlling) {
                                    if (mode == 3) zoom = (zoom * spread / lastSpread).coerceIn(1f, 5f)
                                    panX = MacScreenMath.clampPan(panX + delta.x, zoom, viewW)
                                    panY = MacScreenMath.clampPan(panY + delta.y, zoom, viewH)
                                } else {
                                    scrollAt += delta
                                    if (now - lastSent >= DRAG_MS) { touch("drag", scrollAt); lastSent = now }
                                }
                                lastCentroid = centroid
                                lastSpread = spread
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            if (mode == 2 || mode == 3) continue
                            val change = pressed[0]
                            if (!controlling) {
                                // View only: one finger moves a zoomed picture.
                                if (zoom > 1.01f) {
                                    val delta = change.position - last
                                    panX = MacScreenMath.clampPan(panX + delta.x, zoom, viewW)
                                    panY = MacScreenMath.clampPan(panY + delta.y, zoom, viewH)
                                    mode = 4
                                }
                            } else {
                                if (mode == 0 && (change.position - first.position).getDistance() > viewConfiguration.touchSlop) {
                                    mode = 1
                                    touch("down", first.position)
                                }
                                if (mode == 0 && now - downAt > LONG_PRESS_MS) {
                                    // Held: the finger goes down there and stays, so the other phone sees a long press.
                                    mode = 1
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    touch("down", first.position)
                                }
                                if (mode == 1 && now - lastSent >= DRAG_MS && change.position != last) {
                                    touch("drag", change.position)
                                    lastSent = now
                                }
                            }
                            last = change.position
                            if (change.positionChange() != Offset.Zero) change.consume()
                        }
                        if (controlling) when (mode) {
                            0 -> { touch("down", first.position); touch("up", first.position) }
                            1 -> touch("up", last)
                            2 -> touch("up", scrollAt)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val s = state
            if (s is ShareState.Showing) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            keepScreenOn = true
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) { surface = holder.surface }
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                                override fun surfaceDestroyed(holder: SurfaceHolder) { surface = null }
                            })
                        }
                    },
                    modifier = Modifier
                        .aspectRatio(s.width.toFloat() / s.height, matchHeightConstraintsFirst = viewW / viewH.coerceAtLeast(1f) > s.width.toFloat() / s.height)
                        .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = panX; translationY = panY },
                )
            }
            when (s) {
                ShareState.Asking -> Waiting("Asking $name for its screen. On $name, tap the DyskLink notification and allow sharing.")
                is ShareState.Stopped -> StoppedNote(s.message, if (connected) "Ask again" else null, ::ask)
                else -> Unit
            }
        }

        val shown = state as? ShareState.Showing
        if (shown != null && !shown.canControl) {
            Text(
                "View only. To control it, turn on \"Allow control\" for this phone on $name, and Accessibility for DyskLink there.",
                color = Palette.Amber, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 16.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (shown?.canControl == true) {
                ShareChip(if (controlling) "Control: on" else "Control: off", active = controlling) { controlling = !controlling }
                if (controlling) {
                    ShareChip("Back") { key("back") }
                    ShareChip("Home") { key("home") }
                    ShareChip("Recents") { key("recents") }
                }
            }
            ShareChip(if (landscape) "Portrait" else "Landscape") { landscape = !landscape }
            if (shown != null) ShareChip("Stop") { close() }
        }
    }
}

/** Another phone's camera as a viewfinder: lens, torch, zoom, a shutter with a self-timer, and video. */
@Composable
fun RemoteCameraScreen(peerId: String, name: String, connected: Boolean, link: PhoneShareLink?, onBack: () -> Unit) {
    val main = remember { Handler(Looper.getMainLooper()) }
    val density = LocalDensity.current
    var state by remember { mutableStateOf<ShareState>(ShareState.Asking) }
    var surface by remember { mutableStateOf<android.view.Surface?>(null) }
    var viewW by remember { mutableFloatStateOf(0f) }
    var viewH by remember { mutableFloatStateOf(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var timer by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(0) }
    var shutterRequest by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    var lastZoomSent by remember { mutableStateOf(0L) }
    val decoder = remember { arrayOfNulls<MacScreenDecoder>(1) }

    fun ask() {
        state = ShareState.Asking
        note = null
        link?.useCamera(peerId)
    }
    fun close() {
        link?.stopCamera(peerId)
        onBack()
    }
    fun send(envelope: Envelope) = link?.camera(peerId, envelope)
    fun sendZoom(value: Float, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastZoomSent < ZOOM_MS) return
        lastZoomSent = now
        send(Envelope(MessageType.CAMERA_ZOOM, zoom = value))
    }
    BackHandler(onBack = ::close)

    val showing = state as? ShareState.Showing
    DisposableEffect(surface, showing?.width, showing?.height, showing?.front) {
        val target = surface
        if (target != null && showing != null) {
            decoder[0] = MacScreenDecoder(target, showing.width, showing.height) { send(Envelope(MessageType.CAMERA_SYNC)) }.also {
                runCatching { it.start() }.onFailure { error -> state = ShareState.Stopped("This phone can't play the video: ${error.message}") }
            }
            send(Envelope(MessageType.CAMERA_SYNC))
        }
        onDispose {
            decoder[0]?.release()
            decoder[0] = null
        }
    }

    DisposableEffect(link, peerId, connected) {
        if (link == null || !connected) {
            state = ShareState.Stopped("$name isn't connected.")
            return@DisposableEffect onDispose {}
        }
        link.setSink { id, envelope ->
            if (id != peerId) return@setSink
            when (envelope.type) {
                MessageType.CAMERA_FRAME -> {
                    val frame: Any = envelope.raw ?: envelope.video ?: return@setSink
                    decoder[0]?.feedEncoded(frame, envelope.keyFrame == true)
                }
                MessageType.CAMERA_STARTED -> {
                    val info = envelope.screen ?: return@setSink
                    main.post {
                        val camera = envelope.cameraState
                        state = ShareState.Showing(info.width, info.height, rotation = envelope.rotation ?: 0, front = envelope.text == "front", camera = camera)
                        camera?.zoom?.let { zoom = it }
                    }
                }
                MessageType.CAMERA_SHOT -> main.post {
                    val video = envelope.text == "video"
                    note = envelope.error ?: if (video) "Video saved on $name. It's coming to this phone, see File history."
                    else "Photo saved on $name. It's coming to this phone, see File history."
                }
                MessageType.CAMERA_STOPPED -> main.post { state = ShareState.Stopped(envelope.error ?: "$name stopped its camera.") }
            }
        }
        ask()
        onDispose {
            link.setSink(null)
            link.stopCamera(peerId)
        }
    }

    StopInBackground(active = { state !is ShareState.Stopped }) {
        link?.stopCamera(peerId)
        countdown = 0
        state = ShareState.Stopped("Stopped when DyskLink left the screen. Ask again to keep using the camera.")
    }

    // The self-timer counts here, then the photo is taken there.
    LaunchedEffect(shutterRequest) {
        if (shutterRequest == 0) return@LaunchedEffect
        countdown = timer
        while (countdown > 0) {
            kotlinx.coroutines.delay(1000)
            countdown--
        }
        if (state is ShareState.Showing) {
            note = "Taking the photo…"
            send(Envelope(MessageType.CAMERA_SHUTTER, key = java.util.UUID.randomUUID().toString()))
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Color.White, fontSize = 28.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = ::close).padding(horizontal = 12.dp))
            Column(Modifier.weight(1f)) {
                Text("$name camera", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    when (val s = state) {
                        is ShareState.Showing -> buildString {
                            append(if (s.front) "Front" else "Back")
                            if (zoom > 1.01f) append(" · ${"%.1f".format(zoom)}x")
                            if (s.camera?.recording == true) append(" · Recording")
                        }
                        ShareState.Asking -> "Waiting for $name…"
                        is ShareState.Stopped -> "Stopped"
                    },
                    color = if ((state as? ShareState.Showing)?.camera?.recording == true) Palette.Coral else Color.White.copy(alpha = 0.55f),
                    fontSize = 12.sp, maxLines = 1,
                )
            }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onSizeChanged { viewW = it.width.toFloat(); viewH = it.height.toFloat() }
                .pointerInput(state) {
                    val max = (state as? ShareState.Showing)?.camera?.maxZoom ?: return@pointerInput
                    detectTransformGestures { _, _, change, _ ->
                        if (change != 1f) {
                            zoom = (zoom * change).coerceIn(1f, max)
                            sendZoom(zoom)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val s = state
            if (s is ShareState.Showing && viewW > 0 && viewH > 0) {
                // The picture comes as the sensor sees it: turned here by the rotation the other phone sends.
                val turned = s.rotation % 180 != 0
                val shownW = if (turned) s.height.toFloat() else s.width.toFloat()
                val shownH = if (turned) s.width.toFloat() else s.height.toFloat()
                val box = MacScreenMath.fit(viewW, viewH, shownW, shownH)
                val (texW, texH) = if (turned) box[3] to box[2] else box[2] to box[3]
                AndroidView(
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            keepScreenOn = true
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { surface = android.view.Surface(texture) }
                                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
                                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                    surface?.release()
                                    surface = null
                                    return true
                                }
                                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                            }
                        }
                    },
                    modifier = Modifier
                        .requiredSize(with(density) { texW.toDp() }, with(density) { texH.toDp() })
                        .graphicsLayer { rotationZ = s.rotation.toFloat() },
                )
            }
            when (s) {
                ShareState.Asking -> Waiting("Asking $name for its camera. On $name, tap the DyskLink notification to start it.")
                is ShareState.Stopped -> StoppedNote(s.message, if (connected) "Ask again" else null, ::ask)
                else -> Unit
            }
            if (countdown > 0) {
                Text("$countdown", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold)
            }
        }

        val shown = state as? ShareState.Showing
        val camera = shown?.camera
        note?.let {
            Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
        }
        if (camera != null && (camera.maxZoom ?: 1f) > 1.01f) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("1x", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                Slider(
                    value = zoom,
                    onValueChange = { zoom = it; sendZoom(it) },
                    onValueChangeFinished = { sendZoom(zoom, force = true) },
                    valueRange = 1f..(camera.maxZoom ?: 1f),
                    colors = SliderDefaults.colors(thumbColor = Palette.Mint, activeTrackColor = Palette.Mint),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text("${"%.0f".format(camera.maxZoom ?: 1f)}x", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (shown != null) {
                ShareChip("Switch lens") { send(Envelope(MessageType.CAMERA_CONTROL, text = "switch")) }
                if (camera?.flash == true) {
                    ShareChip(if (camera.torch == true) "Torch: on" else "Torch: off", active = camera.torch == true) {
                        send(Envelope(MessageType.CAMERA_CONTROL, text = "torch"))
                    }
                }
                ShareChip(if (timer == 0) "Timer: off" else "Timer: $timer s", active = timer > 0) {
                    timer = when (timer) { 0 -> 3; 3 -> 10; else -> 0 }
                }
                if (camera != null) {
                    val recording = camera.recording == true
                    ShareChip(if (recording) "Stop recording" else "Record video", active = recording) {
                        send(Envelope(MessageType.CAMERA_CONTROL, text = if (recording) "record.stop" else "record.start"))
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
            val ready = shown != null && camera?.stills == true && countdown == 0
            Box(
                Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .border(4.dp, Color.White.copy(alpha = if (ready) 1f else 0.3f), CircleShape)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (ready) 0.95f else 0.2f))
                    .clickable(enabled = ready) { shutterRequest++ },
            )
            if (shown != null && camera?.stills != true) {
                Text(
                    "This camera can't take photos while streaming.",
                    color = Palette.Amber, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun Waiting(text: String) {
    Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Palette.Mint)
        Spacer(Modifier.height(12.dp))
        Text(text, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp)
    }
}

@Composable
private fun StoppedNote(message: String, action: String?, onAction: () -> Unit) {
    Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp)
        Spacer(Modifier.height(16.dp))
        if (action != null) PrimaryButton(action, Modifier.width(180.dp), onClick = onAction)
    }
}

@Composable
private fun ShareChip(label: String, active: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) Palette.Ink else Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Palette.Mint else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

private const val LONG_PRESS_MS = 550L
private const val DRAG_MS = 16L
private const val ZOOM_MS = 80L
