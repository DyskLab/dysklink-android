package id.dysklink.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.phonelink.android.core.ControlEvent
import id.phonelink.android.core.PhoneVideo
import kotlin.math.min
import kotlin.math.roundToInt

/** Where a picture sits in a view, turned and mirrored, and where a point in the view lands on the picture. */
object VideoMath {
    /** The box the turned picture fills in a [viewW] × [viewH] view, centered: left, top, width, height. */
    fun fit(viewW: Float, viewH: Float, picW: Float, picH: Float, rotation: Int): FloatArray {
        if (viewW <= 0 || viewH <= 0 || picW <= 0 || picH <= 0) return floatArrayOf(0f, 0f, 0f, 0f)
        val turned = Math.floorMod(rotation, 180) == 90
        val w = if (turned) picH else picW
        val h = if (turned) picW else picH
        val scale = min(viewW / w, viewH / h)
        return floatArrayOf((viewW - w * scale) / 2, (viewH - h * scale) / 2, w * scale, h * scale)
    }

    /**
     * A point in the view as 0–1 on the phone's own picture, before it was turned by [rotation] (clockwise) and then
     * [mirror]ed. Null when it's outside the picture, unless [clamp], which keeps it on the nearest edge.
     */
    fun toPicture(x: Float, y: Float, viewW: Float, viewH: Float, picW: Float, picH: Float, rotation: Int, mirror: Boolean = false, clamp: Boolean = false): Pair<Float, Float>? {
        val (left, top, w, h) = fit(viewW, viewH, picW, picH, rotation).toList()
        if (w <= 0 || h <= 0) return null
        var u = (x - left) / w
        var v = (y - top) / h
        if (u !in 0f..1f || v !in 0f..1f) {
            if (!clamp) return null
            u = u.coerceIn(0f, 1f)
            v = v.coerceIn(0f, 1f)
        }
        if (mirror) u = 1 - u
        return when (Math.floorMod(rotation, 360)) {
            90 -> v to 1 - u
            180 -> 1 - u to 1 - v
            270 -> 1 - v to u
            else -> u to v
        }
    }

    /** Where a point 0–1 on the phone's own picture shows in the view: [toPicture] the other way. */
    fun toView(px: Float, py: Float, viewW: Float, viewH: Float, picW: Float, picH: Float, rotation: Int, mirror: Boolean = false): Offset {
        val (left, top, w, h) = fit(viewW, viewH, picW, picH, rotation).toList()
        var (u, v) = when (Math.floorMod(rotation, 360)) {
            90 -> 1 - py to px
            180 -> 1 - px to 1 - py
            270 -> py to 1 - px
            else -> px to py
        }
        if (mirror) u = 1 - u
        return Offset(left + u * w, top + v * h)
    }

    /** How wide the phone's own picture is in the view, turned or not: sizes that are shares of its width. */
    fun phoneWidth(viewW: Float, viewH: Float, picW: Float, picH: Float, rotation: Int): Float {
        val box = fit(viewW, viewH, picW, picH, rotation)
        return if (Math.floorMod(rotation, 180) == 90) box[3] else box[2]
    }

    /** What a key typed on the picture does on the phone: Back, Enter, arrows, or the text; null for anything else. */
    fun phoneKey(event: KeyEvent): ControlEvent? {
        if (event.type != KeyEventType.KeyDown) return null
        val key = when (event.key) {
            Key.Backspace -> "backspace"
            Key.Enter, Key.NumPadEnter -> "enter"
            Key.DirectionLeft -> "left"
            Key.DirectionRight -> "right"
            Key.Escape -> "back"
            else -> null
        }
        if (key != null) return ControlEvent("key", key = key)
        val code = event.utf16CodePoint
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed || code < 32 || code == 127) return null
        return ControlEvent("text", text = String(Character.toChars(code)))
    }

    fun decode(jpeg: ByteArray?): ImageBitmap? =
        jpeg?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() }
}

/** The newest frame of [video], decoded once per frame. */
@Composable
fun rememberFrame(video: PhoneVideo?): ImageBitmap? {
    val bytes = video?.frame
    return remember(bytes) { VideoMath.decode(bytes) }
}

/**
 * Draws [picture] fitted in the space, turned by [rotation] and mirrored. With [onControl], clicks are taps, drags are
 * swipes, the right button is Back, the wheel scrolls, and typing goes to the phone once the picture was clicked.
 */
@Composable
fun VideoSurface(
    picture: ImageBitmap?,
    rotation: Int,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    background: Color = Color.Black,
    onControl: ((ControlEvent) -> Unit)? = null,
) {
    val focus = remember { FocusRequester() }
    val picW = picture?.width?.toFloat() ?: 0f
    val picH = picture?.height?.toFloat() ?: 0f
    var input = modifier.background(background)
    if (onControl != null && picture != null) {
        input = input
            .pointerInput(rotation, picW, picH) {
                awaitPointerEventScope {
                    var touching = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        fun at(clamp: Boolean) = VideoMath.toPicture(change.position.x, change.position.y, w, h, picW, picH, rotation, clamp = clamp)
                        when (event.type) {
                            PointerEventType.Press -> {
                                runCatching { focus.requestFocus() }
                                if (event.buttons.isSecondaryPressed) {
                                    onControl(ControlEvent("key", key = "back"))
                                } else {
                                    at(false)?.let { (x, y) -> touching = true; onControl(ControlEvent("down", x, y)) }
                                }
                            }
                            PointerEventType.Move -> if (touching) at(true)?.let { (x, y) -> onControl(ControlEvent("drag", x, y)) }
                            PointerEventType.Release -> if (touching) {
                                touching = false
                                at(true)?.let { (x, y) -> onControl(ControlEvent("up", x, y)) }
                            }
                            PointerEventType.Scroll -> if (!touching) {
                                // The page around the picture doesn't scroll as well.
                                change.consume()
                                val (x, y) = at(false) ?: continue
                                // A short swipe the other way, as a finger scrolls a list.
                                val dy = (change.scrollDelta.y * SCROLL_STEP).coerceIn(-0.4f, 0.4f)
                                val dx = (change.scrollDelta.x * SCROLL_STEP).coerceIn(-0.4f, 0.4f)
                                onControl(ControlEvent("down", x, y))
                                onControl(ControlEvent("drag", (x - dx / 2).coerceIn(0f, 1f), (y - dy / 2).coerceIn(0f, 1f)))
                                onControl(ControlEvent("up", (x - dx).coerceIn(0f, 1f), (y - dy).coerceIn(0f, 1f)))
                            }
                        }
                    }
                }
            }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                val control = VideoMath.phoneKey(event) ?: return@onPreviewKeyEvent false
                onControl(control)
                true
            }
    }
    Box(input, contentAlignment = Alignment.Center) {
        if (picture == null) return@Box
        Canvas(Modifier.fillMaxSize()) {
            val (left, top, w, h) = VideoMath.fit(size.width, size.height, picW, picH, rotation).toList()
            if (w <= 0) return@Canvas
            val turned = Math.floorMod(rotation, 180) == 90
            // The picture's own size on screen, before it's turned.
            val drawW = if (turned) h else w
            val drawH = if (turned) w else h
            withTransform({
                translate(left + w / 2, top + h / 2)
                scale(if (mirror) -1f else 1f, 1f, pivot = Offset.Zero)
                rotate(rotation.toFloat(), pivot = Offset.Zero)
            }) {
                drawImage(
                    picture,
                    dstOffset = IntOffset((-drawW / 2).roundToInt(), (-drawH / 2).roundToInt()),
                    dstSize = IntSize(drawW.roundToInt(), drawH.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
    }
}

private const val SCROLL_STEP = 0.12f

/** The phone's screen in its own window: the picture with the phone keys under it, and the game keys panel beside it. */
@Composable
fun ScreenWindowContent(model: DeskModel, ui: UiState, deviceId: String) {
    val colors = Dysk.colors
    val screens by model.screens.collectAsState()
    val phones by model.phones.collectAsState()
    val video = screens[deviceId]
    val picture = rememberFrame(video)
    val game = ui.gameKeys(deviceId, model)
    val unsupported = gameKeysUnsupported(phones.firstOrNull { it.id == deviceId })
    Row(Modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            if (game.enabled && unsupported == null) {
                GameScreen(picture, ui.screenRotation, game, model.gamePointerLock, Modifier.weight(1f).fillMaxWidth().background(Color.Black)) { model.control(deviceId, it) }
            } else {
                VideoSurface(
                    picture, ui.screenRotation, Modifier.weight(1f).fillMaxWidth(),
                    onControl = { model.control(deviceId, it) },
                )
            }
            if (picture == null) {
                Text(
                    video?.error ?: if (video == null) "The screen isn't shared now." else "Waiting for the phone...",
                    Modifier.padding(Space.sm), color = colors.textSecondary, fontSize = 12.5.sp,
                )
            }
            PhoneKeys(model, ui, deviceId, Modifier.padding(Space.xs)) { GameKeysButton(game) }
        }
        if (game.enabled) {
            GameKeysPanel(
                game, model.gamePointerLock, Modifier.padding(Space.xs), unsupported = unsupported,
                onImport = { GameKeyFiles.import(game) }, onExport = { GameKeyFiles.export(game) },
            )
        }
    }
}

/** Back, Home, Recents, and turning the picture. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneKeys(model: DeskModel, ui: UiState, deviceId: String, modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        SecondaryButton("Back", icon = DyskIcons.Back) { model.control(deviceId, ControlEvent("key", key = "back")) }
        SecondaryButton("Home", icon = DyskIcons.Home) { model.control(deviceId, ControlEvent("key", key = "home")) }
        SecondaryButton("Recents", icon = DyskIcons.Recents) { model.control(deviceId, ControlEvent("key", key = "recents")) }
        SecondaryButton("Rotate", icon = DyskIcons.Rotate) { ui.screenRotation = (ui.screenRotation + 90) % 360 }
        extra()
    }
}

/**
 * The phone's camera in its own window. Clean view leaves only the picture, for capturing the window in OBS; a
 * double-click brings the buttons back.
 */
@Composable
fun CameraWindowContent(model: DeskModel, ui: UiState, deviceId: String, deviceName: String) {
    val colors = Dysk.colors
    val cameras by model.cameras.collectAsState()
    val video = cameras[deviceId]
    val picture = rememberFrame(video)
    val view = ui.camera
    val rotation = ((video?.rotation ?: 0) + view.rotation) % 360
    Column(Modifier.fillMaxSize().background(if (view.clean) Color.Black else colors.background)) {
        Box(
            Modifier.weight(1f).fillMaxWidth().pointerInput(view.clean) {
                awaitPointerEventScope {
                    var lastPress = 0L
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Press) continue
                        val now = System.currentTimeMillis()
                        if (now - lastPress < 400 && view.clean) view.clean = false
                        lastPress = now
                    }
                }
            },
        ) {
            VideoSurface(picture, rotation, Modifier.fillMaxSize(), mirror = view.mirror)
            if (picture == null) {
                Text(
                    video?.error ?: if (video == null) "The camera is off." else "Tap the notification on $deviceName to start the camera.",
                    Modifier.align(Alignment.Center).padding(Space.lg), color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp,
                )
            }
        }
        if (!view.clean) CameraControls(model, ui, deviceId, video, Modifier.padding(Space.xs), inWindow = true)
    }
}

/** Lens, torch, mirror, turn, and the window's own switches. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CameraControls(model: DeskModel, ui: UiState, deviceId: String, video: PhoneVideo?, modifier: Modifier = Modifier, inWindow: Boolean = false, extra: @Composable () -> Unit = {}) {
    val colors = Dysk.colors
    val view = ui.camera
    val live = video?.live == true
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        SecondaryButton(if (video?.lens == "front") "Back lens" else "Front lens", icon = DyskIcons.Camera, enabled = live) { model.cameraControl(deviceId, "switch") }
        SecondaryButton(
            if (video?.torch == true) "Torch off" else "Torch", if (video?.torch == true) colors.amber else colors.textPrimary,
            icon = DyskIcons.Bolt, enabled = live && video?.lens != "front",
        ) { model.cameraControl(deviceId, "torch") }
        SecondaryButton("Mirror", if (view.mirror) colors.mint else colors.textPrimary, icon = DyskIcons.Mirror) { view.mirror = !view.mirror }
        SecondaryButton("Rotate", icon = DyskIcons.Rotate) { view.rotation = (view.rotation + 90) % 360 }
        if (inWindow) {
            SecondaryButton("On top", if (view.onTop) colors.mint else colors.textPrimary, icon = DyskIcons.Pin) { view.onTop = !view.onTop }
            SecondaryButton("Clean view", icon = DyskIcons.Eye) { view.clean = true }
            WebcamButton(model)
        }
        extra()
    }
    if (inWindow) {
        Text(
            "In OBS, add this window with ${id.dysklink.desktop.HostChecks.obsSource(model.host.collectAsState().value.obs)}. " +
                "Clean view leaves only the picture; double-click it to bring the buttons back." +
                if (model.webcam != null) " Or use it as a webcam: OBS and call apps then pick '${id.dysklink.desktop.Loopback.LABEL}' as a Video Capture Device." else "",
            Modifier.padding(start = Space.sm, bottom = Space.xs), color = colors.textFaint, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        )
    }
}
