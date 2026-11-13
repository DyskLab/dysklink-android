package id.phonelink.android.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import id.phonelink.android.core.Envelope
import id.phonelink.android.core.InputEvent
import id.phonelink.android.core.JpegFrames
import id.phonelink.android.core.JpegScreenDecoder
import androidx.compose.ui.graphics.asImageBitmap
import id.phonelink.android.core.MacScreenDecoder
import id.phonelink.android.core.MacScreenMath
import id.phonelink.android.core.MessageType
import kotlin.math.abs
import kotlin.math.hypot

/** What the Mac screen viewer needs from the connection. */
class MacScreenLink(
    val setSink: (((String, Envelope) -> Unit)?) -> Unit,
    val start: (String, Long?) -> Unit,
    /** Asks the Mac to add this phone as an extra display of this size, in points. */
    val extend: (String, Int, Int, Boolean) -> Unit = { _, _, _, _ -> },
    val stop: (String) -> Unit,
    val sync: (String) -> Unit,
    val pointer: (peerId: String, kind: String, x: Float, y: Float) -> Unit,
    val input: (peerId: String, InputEvent) -> Unit,
)

private sealed interface ViewState {
    data object Starting : ViewState
    data class Showing(
        val width: Int,
        val height: Int,
        val name: String,
        val displays: List<id.phonelink.android.core.DisplayChoice> = emptyList(),
        val displayId: Long? = null,
        /** A Windows or Linux PC: whole JPEG pictures instead of the Mac's H.264. */
        val jpeg: Boolean = false,
    ) : ViewState
    data class Stopped(val message: String) : ViewState
}

/** The Mac's screen, live. Tap to click, hold to right-click, drag to drag, two fingers to scroll, pinch to zoom. */
@Composable
fun MacScreenScreen(
    peerId: String,
    macName: String,
    connected: Boolean,
    inputTrusted: Boolean?,
    link: MacScreenLink?,
    onBack: () -> Unit,
    extend: Boolean = false,
    /** False for a Windows or Linux PC, whose hints and shortcuts differ. */
    isMac: Boolean = true,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    var state by remember { mutableStateOf<ViewState>(ViewState.Starting) }
    var surface by remember { mutableStateOf<android.view.Surface?>(null) }
    // An extra display is wide, so the phone turns sideways for it.
    var landscape by remember { mutableStateOf(extend) }
    // The Mac display being shown; null is the main one.
    var chosenDisplay by remember { mutableStateOf<Long?>(null) }
    val prefs = remember { context.getSharedPreferences("second_display", android.content.Context.MODE_PRIVATE) }
    var large by remember { mutableStateOf(prefs.getBoolean("large", false)) }
    val metrics = context.resources.displayMetrics
    fun begin() {
        if (!extend) return link?.start(peerId, chosenDisplay) ?: Unit
        val long = maxOf(metrics.widthPixels, metrics.heightPixels).toFloat()
        val short = minOf(metrics.widthPixels, metrics.heightPixels).toFloat().coerceAtLeast(1f)
        val (width, height) = MacScreenMath.extraDisplaySize(long, short, portrait = !landscape, large = large)
        link?.extend(peerId, width, height, large)
    }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var viewW by remember { mutableFloatStateOf(0f) }
    var viewH by remember { mutableFloatStateOf(0f) }
    var typing by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val decoderHolder = remember { arrayOfNulls<MacScreenDecoder>(1) }
    val jpegHolder = remember { arrayOfNulls<JpegScreenDecoder>(1) }
    var picture by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    /** Stopped because the app went to the background; it starts again when the app returns. */
    var paused by remember { mutableStateOf(false) }

    fun close() {
        link?.stop(peerId)
        onBack()
    }
    BackHandler(onBack = ::close)

    // The decoder follows the surface and the picture size; a new size means a new decoder.
    val showing = state as? ViewState.Showing
    DisposableEffect(surface, showing?.width, showing?.height) {
        val target = surface
        if (target != null && showing != null && !showing.jpeg) {
            decoderHolder[0] = MacScreenDecoder(target, showing.width, showing.height) { link?.sync(peerId) }.also {
                runCatching { it.start() }.onFailure { error -> state = ViewState.Stopped("This phone can't play the video: ${error.message}") }
            }
            link?.sync(peerId)
        }
        onDispose {
            decoderHolder[0]?.release()
            decoderHolder[0] = null
        }
    }

    // A PC's JPEG pictures need no surface; the decoder lives while the picture shows.
    DisposableEffect(showing?.jpeg) {
        if (showing?.jpeg == true) {
            jpegHolder[0] = JpegScreenDecoder { bitmap -> main.post { picture = bitmap.asImageBitmap() } }
            link?.sync(peerId)
        }
        onDispose {
            jpegHolder[0]?.release()
            jpegHolder[0] = null
        }
    }

    DisposableEffect(link, peerId, connected) {
        if (link == null || !connected) {
            state = ViewState.Stopped("$macName isn't connected.")
            return@DisposableEffect onDispose {}
        }
        link.setSink { id, envelope ->
            if (id != peerId) return@setSink
            when (envelope.type) {
                MessageType.MACSCREEN_FRAME -> {
                    // A PC's whole pictures go to their own decoder, which keeps only the newest.
                    if (envelope.format == JpegFrames.FORMAT) return@setSink jpegHolder[0]?.feed(envelope) ?: Unit
                    // Queued for the decoder's thread; this is the connection's reader and must not wait.
                    // A binary frame is raw already; base64 from an older Mac is decoded on the decoder's thread.
                    val frame: Any = envelope.raw ?: envelope.video ?: return@setSink
                    decoderHolder[0]?.feedEncoded(frame, envelope.keyFrame == true)
                }
                MessageType.MACSCREEN_STARTED -> {
                    val info = envelope.screen ?: return@setSink
                    main.post {
                        state = ViewState.Showing(
                            info.width, info.height, envelope.text ?: macName, envelope.displays.orEmpty(), envelope.displayId,
                            jpeg = envelope.format == JpegFrames.FORMAT,
                        )
                    }
                }
                // The answer to stopping in the background isn't an ending: it starts again on return.
                MessageType.MACSCREEN_STOPPED -> main.post { if (!paused) state = ViewState.Stopped(envelope.error ?: "Viewing ended.") }
            }
        }
        state = ViewState.Starting
        begin()
        onDispose {
            link.setSink(null)
            link.stop(peerId)
            // Sound turned on here ends with the screen; sound from the Sound sharing page keeps going.
            if (id.phonelink.android.service.AudioShare.fromScreen && id.phonelink.android.service.AudioShare.listening.value == peerId) {
                id.phonelink.android.service.AudioShare.stopListening(peerId)
            }
        }
    }

    // In the background (another app, screen off) the Mac stops sending, so video doesn't stream to nobody;
    // coming back asks for it again.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, link, peerId, connected) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> if (link != null && connected && state !is ViewState.Stopped) {
                    paused = true
                    // Also marks the viewer as not showing, so Wi-Fi leaves high-performance mode.
                    link.stop(peerId)
                    id.phonelink.android.service.PhoneLinkService.setMacScreenViewing(false)
                }
                androidx.lifecycle.Lifecycle.Event.ON_START -> if (paused) {
                    paused = false
                    if (link != null && connected) {
                        state = ViewState.Starting
                        begin()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(landscape) {
        activity?.requestedOrientation = when {
            landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            extend -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    fun mac(offset: Offset): Pair<Float, Float>? {
        val s = state as? ViewState.Showing ?: return null
        return MacScreenMath.toMac(offset.x, offset.y, viewW, viewH, s.width.toFloat(), s.height.toFloat(), zoom, panX, panY)
    }
    fun send(kind: String, offset: Offset) {
        val point = mac(offset) ?: return
        link?.pointer(peerId, kind, point.first, point.second)
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("‹", color = Color.White, fontSize = 28.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = ::close).padding(horizontal = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (extend) "$macName · extra display" else macName, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(
                    when (val s = state) {
                        is ViewState.Showing -> if (zoom > 1.01f) "${s.name} · ${"%.1f".format(zoom)}×" else s.name
                        ViewState.Starting -> "Connecting…"
                        is ViewState.Stopped -> "Stopped"
                    },
                    color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 1,
                )
            }
            if (zoom > 1.01f) ToolChip("1×") { zoom = 1f; panX = 0f; panY = 0f }
        }

        Box(
            Modifier.weight(1f).fillMaxWidth()
                .onSizeChanged { viewW = it.width.toFloat(); viewH = it.height.toFloat() }
                .pointerInput(state, link) {
                    var lastTapAt = 0L
                    var lastTapPos = Offset.Zero
                    awaitEachGesture {
                        val first = awaitFirstDown(requireUnconsumed = false)
                        val downAt = System.currentTimeMillis()
                        var mode = 0 // 0 = undecided, 1 = drag, 2 = two-finger scroll, 3 = zoom, 4 = held
                        var lastPos = first.position
                        var lastCentroid = Offset.Zero
                        var lastSpread = 0f
                        var lastDragSent = 0L
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                val a = pressed[0].position
                                val b = pressed[1].position
                                val centroid = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
                                val spread = hypot(a.x - b.x, a.y - b.y)
                                if (mode == 1) send("up", lastPos)
                                if (mode != 2 && mode != 3) {
                                    if (lastSpread == 0f) { lastSpread = spread; lastCentroid = centroid; mode = 0; continue }
                                    if (abs(spread / lastSpread - 1) > 0.12f) mode = 3
                                    else if ((centroid - lastCentroid).getDistance() > 14f) mode = 2
                                    else continue
                                }
                                val delta = centroid - lastCentroid
                                if (mode == 3) {
                                    zoom = (zoom * spread / lastSpread).coerceIn(1f, 5f)
                                    panX = MacScreenMath.clampPan(panX + delta.x, zoom, viewW)
                                    panY = MacScreenMath.clampPan(panY + delta.y, zoom, viewH)
                                } else {
                                    link?.input(peerId, InputEvent("scroll", dx = delta.x, dy = delta.y))
                                }
                                lastCentroid = centroid
                                lastSpread = spread
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            val change = pressed[0]
                            val now = System.currentTimeMillis()
                            if (mode == 0 && (change.position - first.position).getDistance() > viewConfiguration.touchSlop) {
                                mode = 1
                                send("down", first.position)
                            }
                            if (mode == 0 && now - downAt > LONG_PRESS_MS) {
                                mode = 4
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                send("right", first.position)
                            }
                            if (mode == 1 && now - lastDragSent >= DRAG_INTERVAL_MS) {
                                send("drag", change.position)
                                lastDragSent = now
                            }
                            lastPos = change.position
                            if (change.positionChange() != Offset.Zero) change.consume()
                        }
                        when (mode) {
                            1 -> send("up", lastPos)
                            0 -> {
                                val now = System.currentTimeMillis()
                                val double = now - lastTapAt < DOUBLE_TAP_MS && (first.position - lastTapPos).getDistance() < 60f
                                send(if (double) "double" else "click", first.position)
                                lastTapAt = if (double) 0L else now
                                lastTapPos = first.position
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val s = state
            val shownPicture = picture
            if (s is ViewState.Showing && s.jpeg) {
                if (shownPicture != null) {
                    androidx.compose.foundation.Image(
                        shownPicture, contentDescription = "$macName screen",
                        contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
                        modifier = Modifier
                            .aspectRatio(s.width.toFloat() / s.height, matchHeightConstraintsFirst = viewW / viewH.coerceAtLeast(1f) > s.width.toFloat() / s.height)
                            .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = panX; translationY = panY },
                    )
                } else {
                    CircularProgressIndicator(color = Palette.Mint)
                }
                DisposableEffect(Unit) {
                    activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
            } else if (s is ViewState.Showing) {
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
                ViewState.Starting -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Palette.Mint)
                    Spacer(Modifier.height(12.dp))
                    Text("Asking $macName for its screen…", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                }
                is ViewState.Stopped -> Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.message, color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 21.sp)
                    Spacer(Modifier.height(16.dp))
                    if (connected) PrimaryButton("Try again", Modifier.width(180.dp)) {
                        state = ViewState.Starting
                        begin()
                    }
                }
                else -> Unit
            }
        }

        if (state is ViewState.Showing && inputTrusted == false) {
            Text(
                if (isMac) "Taps and typing need Accessibility for DyskLink on the Mac."
                else "Taps and typing need \"Phones can control this PC\" in DyskLink on the PC. On Linux, also allow remote control when the PC asks, or follow the steps DyskLink shows there.",
                color = Palette.Amber, fontSize = 12.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(6.dp),
            )
        }

        if (typing) KeyboardCatcher(onText = { link?.input(peerId, InputEvent("text", text = it)) }, onKey = { link?.input(peerId, InputEvent("key", key = it)) }, onDone = { typing = false })

        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ToolChip(if (typing) "Hide keys" else "⌨ Type") { typing = !typing }
            listOf("esc" to "escape", "tab" to "tab", "←" to "left", "↑" to "up", "↓" to "down", "→" to "right", "⏎" to "return", "⌫" to "delete").forEach { (label, key) ->
                ToolChip(label) { link?.input(peerId, InputEvent("key", key = key)) }
            }
            val hearing by id.phonelink.android.service.AudioShare.listening.collectAsState()
            val soundProblem by id.phonelink.android.service.AudioShare.problem.collectAsState()
            val chipContext = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.runtime.LaunchedEffect(soundProblem) {
                soundProblem?.let { android.widget.Toast.makeText(chipContext, it, android.widget.Toast.LENGTH_LONG).show() }
            }
            ToolChip(if (hearing == peerId) "🔊 Sound on" else "🔇 Sound") {
                if (hearing == peerId) id.phonelink.android.service.AudioShare.stopListening(peerId)
                else id.phonelink.android.service.AudioShare.listen(peerId, fromScreen = true)
            }
            // "cmd" is Ctrl on a PC.
            ToolChip(if (isMac) "⌘C" else "Ctrl+C") { link?.input(peerId, InputEvent("shortcut", key = "cmd+c")) }
            ToolChip(if (isMac) "⌘V" else "Ctrl+V") { link?.input(peerId, InputEvent("shortcut", key = "cmd+v")) }
            val shown = state as? ViewState.Showing
            if (!extend && shown != null && shown.displays.size > 1) {
                // Steps through the Mac's displays.
                ToolChip("🖥 ${shown.name}") {
                    val index = shown.displays.indexOfFirst { it.id == shown.displayId }
                    val next = shown.displays[(index + 1) % shown.displays.size]
                    chosenDisplay = next.id
                    state = ViewState.Starting
                    link?.start(peerId, next.id)
                }
            }
            if (extend) {
                // A PC's monitor is the phone's size at 1x: fewer pixels means larger text, not sharper text.
                ToolChip(if (!large) "Larger text" else if (isMac) "Sharp text" else "Smaller text") {
                    large = !large
                    prefs.edit().putBoolean("large", large).apply()
                    if (connected) {
                        state = ViewState.Starting
                        begin()
                    }
                }
            }
            ToolChip(if (landscape) "Portrait" else "Landscape") {
                landscape = !landscape
                // An extra display turns with the phone: the Mac makes one in the new shape.
                if (extend && connected) {
                    state = ViewState.Starting
                    begin()
                }
            }
        }
    }
}

/** A hidden text box that sends what's typed on the phone keyboard, and backspace and enter as keys. */
@Composable
private fun KeyboardCatcher(onText: (String) -> Unit, onKey: (String) -> Unit, onDone: () -> Unit) {
    // Two spaces stay in the box so backspace has something to remove, which is how it's noticed.
    val sentinel = "  "
    var value by remember { mutableStateOf(TextFieldValue(sentinel, TextRange(sentinel.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = value,
        onValueChange = { new ->
            val text = new.text
            when {
                text.length < sentinel.length -> repeat(sentinel.length - text.length) { onKey("delete") }
                text.startsWith(sentinel) && text.length > sentinel.length -> onText(text.removePrefix(sentinel))
                text != sentinel -> onText(text.trim())
            }
            value = TextFieldValue(sentinel, TextRange(sentinel.length))
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Send, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onSend = { onKey("return") }),
        singleLine = true,
        modifier = Modifier.size(1.dp).focusRequester(focus),
    )
    DisposableEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
        onDispose { keyboard?.hide() }
    }
}

@Composable
private fun ToolChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

private const val LONG_PRESS_MS = 550L
private const val DOUBLE_TAP_MS = 320L
private const val DRAG_INTERVAL_MS = 16L
