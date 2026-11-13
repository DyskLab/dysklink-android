package id.dysklink.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.dysklink.desktop.GameKey
import id.dysklink.desktop.GameKeyCodes
import id.dysklink.desktop.GameKeysController
import id.dysklink.desktop.GameKeysController.Capture
import id.dysklink.desktop.PhonePoint
import id.dysklink.desktop.PointerRecentre
import id.phonelink.android.core.ControlEvent
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.awt.Point
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// Game keys on the phone's screen: the picture with the controls drawn over it (placed and dragged while editing,
// played with the keyboard and mouse otherwise), and the side panel beside it, as on the Mac.

val GamePanelWidth: Dp = 260.dp

/** Robot and a blank cursor for holding the pointer while looking; made on first use only. */
private object GamePointer {
    val robot: java.awt.Robot? by lazy {
        if (java.awt.GraphicsEnvironment.isHeadless()) null else runCatching { java.awt.Robot().apply { autoDelay = 0 } }.getOrNull()
    }
    val blank: PointerIcon by lazy {
        val image = java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        PointerIcon(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "DyskLink game"))
    }
}

private val Mint = Color(0xFF4FE3B0)
private val SkillColor = Color(0xFFFFC25C)
private val SwipeColor = Color(0xFF8C7AFF)
private val SelectColor = Color(0xFFFFE64D)
private val LookColor = Color(0xFF8CBFFF)
private val FireColor = Color(0xFFFF7366)

/** A press on empty picture while editing, to tell a click from a drag, and the control being dragged. */
private class EditDrag {
    var dragging by mutableStateOf<Pair<String, Boolean>?>(null)
    var pressedAt by mutableStateOf<Offset?>(null)
    var draggedTo by mutableStateOf<Offset?>(null)
}

/**
 * The phone's picture with game keys. While editing, clicks place and drag controls and keys go to the waiting one;
 * while playing, mapped keys and the mouse are fingers, and other keys go to the phone as usual. [pointerLock] says
 * whether looking may hold the pointer (Robot); otherwise the right mouse button is dragged to look.
 */
@Composable
fun GameScreen(
    picture: ImageBitmap?,
    rotation: Int,
    game: GameKeysController,
    pointerLock: Boolean,
    modifier: Modifier = Modifier,
    onControl: (ControlEvent) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val drag = remember { EditDrag() }
    val density = LocalDensity.current.density
    val picW = picture?.width?.toFloat() ?: 0f
    val picH = picture?.height?.toFloat() ?: 0f
    var dragLooking by remember { mutableStateOf(false) }
    var originInWindow by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val windowFocused = LocalWindowInfo.current.isWindowFocused

    LaunchedEffect(picW, picH) { if (picW > 0 && picH > 0) game.setAspect(picW.toDouble() / picH) }
    // Keys held when the window loses focus never get their release, so fingers are lifted now.
    LaunchedEffect(windowFocused) { if (!windowFocused) game.release() }
    LaunchedEffect(game.enabled, game.editing, game.focusRequests) { runCatching { focus.requestFocus() } }
    // Swipes, combos, auto-fire, and the heartbeat.
    LaunchedEffect(game.enabled) {
        while (game.enabled && isActive) {
            delay(8)
            game.tick()
        }
    }
    // Leaving the tab, closing the window, or the phone going: nothing stays pressed.
    DisposableEffect(game) { onDispose { game.release() } }
    // Looking with the pointer held in the picture's middle (Windows, X11).
    val holding = pointerLock && game.looking && !dragLooking
    LaunchedEffect(holding) {
        if (!holding) return@LaunchedEffect
        val robot = GamePointer.robot ?: return@LaunchedEffect
        val pane = (KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow as? javax.swing.RootPaneContainer)?.contentPane
        val origin = runCatching { pane?.locationOnScreen }.getOrNull() ?: return@LaunchedEffect
        val centre = Point(
            origin.x + ((originInWindow.x + boxSize.x / 2) / density).toInt(),
            origin.y + ((originInWindow.y + boxSize.y / 2) / density).toInt(),
        )
        val recentre = PointerRecentre({ x, y -> robot.mouseMove(x, y) })
        recentre.begin(centre)
        try {
            while (isActive) {
                delay(8)
                recentre.sample(runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull())?.let { (dx, dy) ->
                    game.lookPixels(dx.toDouble(), dy.toDouble(), rotation)
                }
            }
        } finally {
            recentre.end()
        }
    }

    var input = modifier
        .onGloballyPositioned { originInWindow = it.positionInWindow(); boxSize = Offset(it.size.width.toFloat(), it.size.height.toFloat()) }
        .focusRequester(focus)
        .onFocusChanged { if (!it.hasFocus) game.release() }
        .focusable()
        .onPreviewKeyEvent { event ->
            val awt = event.nativeKeyEvent as? java.awt.event.KeyEvent
            val code = GameKeyCodes.mac(awt?.keyCode ?: event.key.nativeKeyCode, awt?.keyLocation ?: java.awt.event.KeyEvent.KEY_LOCATION_STANDARD)
            val down = event.type == KeyEventType.KeyDown
            if (game.editing) {
                if (!down) return@onPreviewKeyEvent true
                if (code == GameKeyCodes.ESCAPE) game.cancelEditStep() else if (code != null) game.assign(code)
                return@onPreviewKeyEvent true
            }
            if (game.playing && code != null) {
                if (down) {
                    if (code == GameKeyCodes.ESCAPE && game.looking) { game.toggleLook(); dragLooking = false; return@onPreviewKeyEvent true }
                    if (code == GameKeyCodes.ESCAPE && game.cancelSkill()) return@onPreviewKeyEvent true
                    if (game.keyPressed(code)) return@onPreviewKeyEvent true
                    if (code == GameKeyCodes.LOOK && game.hasLook) {
                        if (pointerLock) game.toggleLook()
                        else game.message = "Wayland doesn't let apps hold the pointer. Hold the right mouse button on the picture and drag to look."
                        return@onPreviewKeyEvent true
                    }
                } else if (game.keyReleased(code)) {
                    return@onPreviewKeyEvent true
                }
            }
            val control = VideoMath.phoneKey(event) ?: return@onPreviewKeyEvent false
            onControl(control)
            true
        }
    if (holding) input = input.pointerHoverIcon(GamePointer.blank)
    if (picture != null) {
        input = input.pointerInput(game, rotation, picW, picH, pointerLock) {
            awaitPointerEventScope {
                var primary = false
                var secondary = false
                var touching = false
                var lastLook: Offset? = null
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: continue
                    val pos = change.position
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    fun phone(at: Offset, clamp: Boolean): PhonePoint? =
                        VideoMath.toPicture(at.x, at.y, w, h, picW, picH, rotation, clamp = clamp)?.let { PhonePoint(it.first.toDouble(), it.second.toDouble()) }
                    val nowPrimary = event.buttons.isPrimaryPressed
                    val nowSecondary = event.buttons.isSecondaryPressed
                    val pressedPrimary = event.type == PointerEventType.Press && nowPrimary && !primary
                    val pressedSecondary = event.type == PointerEventType.Press && nowSecondary && !secondary
                    val releasedPrimary = event.type == PointerEventType.Release && primary && !nowPrimary
                    val releasedSecondary = event.type == PointerEventType.Release && secondary && !nowSecondary
                    primary = nowPrimary
                    secondary = nowSecondary
                    if (event.type == PointerEventType.Press) runCatching { focus.requestFocus() }
                    if (game.editing) {
                        val geometry = Geometry(w, h, picW, picH, rotation)
                        when {
                            pressedPrimary -> {
                                val at = phone(pos, false) ?: continue
                                if (game.capture is Capture.Combo) { drag.pressedAt = pos; drag.draggedTo = null; continue }
                                val placing = game.placing
                                val found = hit(game, geometry, pos, density)
                                when {
                                    placing != null -> game.place(placing, at.x, at.y)
                                    found != null -> { game.selected = found.first.id; drag.dragging = found.first.id to found.second }
                                    event.keyboardModifiers.isAltPressed -> game.placeJoystick(at.x, at.y)
                                    event.keyboardModifiers.isShiftPressed -> game.placeLook(at.x, at.y)
                                    event.keyboardModifiers.isCtrlPressed -> game.placeFire(at.x, at.y)
                                    else -> { game.selected = null; drag.pressedAt = pos; drag.draggedTo = null }
                                }
                            }
                            pressedSecondary -> hit(game, geometry, pos, density)?.let { game.remove(it.first.id) }
                            event.type == PointerEventType.Move -> {
                                val dragging = drag.dragging
                                val start = drag.pressedAt
                                if (dragging != null) {
                                    phone(pos, true)?.let { game.move(dragging.first, it.x, it.y, dragging.second) }
                                } else if (start != null && (drag.draggedTo != null || hypot(pos.x - start.x, pos.y - start.y) > 12 * density)) {
                                    drag.draggedTo = pos
                                }
                            }
                            releasedPrimary -> {
                                val start = drag.pressedAt
                                val end = drag.draggedTo
                                if (drag.dragging != null) {
                                    game.finishMove()
                                } else if (start != null) {
                                    val a = phone(start, true)!!
                                    val b = end?.let { phone(it, true) }
                                    if (game.capture is Capture.Combo) {
                                        game.recordStep(
                                            if (b != null) GameKey.Step(kind = GameKey.Step.Kind.Swipe, x = a.x, y = a.y, toX = b.x, toY = b.y, ms = 120.0)
                                            else GameKey.Step(kind = GameKey.Step.Kind.Tap, x = a.x, y = a.y),
                                        )
                                    } else if (b != null) {
                                        game.pending = null
                                        game.pendingSwipe = a to b
                                    } else {
                                        game.pendTap(a.x, a.y)
                                    }
                                }
                                drag.dragging = null
                                drag.pressedAt = null
                                drag.draggedTo = null
                            }
                            event.type == PointerEventType.Scroll -> {
                                change.consume()
                                val found = hit(game, geometry, pos, density) ?: continue
                                if (found.first.kind in setOf(GameKey.Kind.Joystick, GameKey.Kind.Look, GameKey.Kind.Skill)) {
                                    game.resize(found.first.id, -change.scrollDelta.y * 0.005)
                                }
                            }
                        }
                        continue
                    }
                    if (!game.playing) continue
                    when {
                        pressedPrimary -> if (game.looking) game.fire(true) else phone(pos, false)?.let { touching = true; game.mouseTouch("tdown", it) }
                        pressedSecondary -> when {
                            game.looking -> Unit
                            // Wayland: the right button held is looking.
                            !pointerLock && game.hasLook -> { game.toggleLook(); dragLooking = game.looking; lastLook = pos }
                            else -> onControl(ControlEvent("key", key = "back"))
                        }
                        releasedPrimary -> {
                            if (game.looking) game.fire(false)
                            if (touching) { touching = false; phone(pos, true)?.let { game.mouseTouch("tup", it) } }
                        }
                        releasedSecondary -> if (dragLooking) {
                            dragLooking = false
                            lastLook = null
                            if (game.looking) game.toggleLook()
                        }
                        event.type == PointerEventType.Move -> {
                            val from = lastLook
                            if (dragLooking && from != null) {
                                game.lookPixels(((pos.x - from.x) / density).toDouble(), ((pos.y - from.y) / density).toDouble(), rotation)
                                lastLook = pos
                            } else if (!game.looking) {
                                phone(pos, true)?.let { at ->
                                    game.mouse(at)
                                    if (touching) game.mouseTouch("tmove", at)
                                }
                            }
                        }
                        event.type == PointerEventType.Scroll -> if (!touching && !game.looking) {
                            change.consume()
                            val at = phone(pos, false) ?: continue
                            val x = at.x.toFloat()
                            val y = at.y.toFloat()
                            val dy = (change.scrollDelta.y * 0.12f).coerceIn(-0.4f, 0.4f)
                            val dx = (change.scrollDelta.x * 0.12f).coerceIn(-0.4f, 0.4f)
                            onControl(ControlEvent("down", x, y))
                            onControl(ControlEvent("drag", (x - dx / 2).coerceIn(0f, 1f), (y - dy / 2).coerceIn(0f, 1f)))
                            onControl(ControlEvent("up", (x - dx).coerceIn(0f, 1f), (y - dy).coerceIn(0f, 1f)))
                        }
                    }
                }
            }
        }
    }
    Box(input) {
        VideoSurface(picture, rotation, Modifier.fillMaxSize())
        if (picture != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawGameKeys(game, Geometry(size.width, size.height, picW, picH, rotation), drag, measurer, dragLooking)
            }
        }
    }
}

/** Where the picture sits, to map points both ways. */
private class Geometry(val w: Float, val h: Float, val picW: Float, val picH: Float, val rotation: Int) {
    fun view(x: Double, y: Double): Offset = VideoMath.toView(x.toFloat(), y.toFloat(), w, h, picW, picH, rotation)
    val phoneWidth: Float get() = VideoMath.phoneWidth(w, h, picW, picH, rotation)
}

private fun hasReach(key: GameKey) = key.kind == GameKey.Kind.Joystick || key.kind == GameKey.Kind.Look

/** The control under [point] (and whether it's a swipe's end), topmost first. */
private fun hit(game: GameKeysController, geometry: Geometry, point: Offset, density: Float): Pair<GameKey, Boolean>? {
    for (key in game.keys.reversed()) {
        if (key.kind == GameKey.Kind.Swipe) {
            val end = geometry.view(key.toX, key.toY)
            if (hypot(end.x - point.x, end.y - point.y) <= 14 * density) return key to true
        }
        val centre = geometry.view(key.x, key.y)
        val reach = if (hasReach(key)) max(20 * density, (key.radius * geometry.phoneWidth).toFloat()) else 18 * density
        if (hypot(centre.x - point.x, centre.y - point.y) <= reach) return key to false
    }
    return null
}

private fun DrawScope.drawGameKeys(
    game: GameKeysController,
    geometry: Geometry,
    drag: EditDrag,
    measurer: androidx.compose.ui.text.TextMeasurer,
    dragLooking: Boolean,
) {
    if (!game.enabled || geometry.picW <= 0) return
    val alpha = if (game.editing) 0.9f else 0.4f
    val width = geometry.phoneWidth
    fun label(text: String, at: Offset, a: Float, dark: Boolean = false, size: Float = 11f) {
        if (text.isEmpty()) return
        val layout = measurer.measure(text, TextStyle(color = (if (dark) Color.Black else Color.White).copy(alpha = a), fontSize = size.sp, fontWeight = FontWeight.Bold))
        drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
    }
    fun badge(text: String, at: Offset, color: Color) {
        val layout = measurer.measure(text, TextStyle(color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold))
        val bw = max(14.dp.toPx(), layout.size.width + 6.dp.toPx())
        val bh = 14.dp.toPx()
        drawRoundRect(color, Offset(at.x - bw / 2, at.y - bh / 2), androidx.compose.ui.geometry.Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(bh / 2))
        drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
    }
    fun highlight(at: Offset, r: Float) = drawCircle(SelectColor, r, at, style = Stroke(2.dp.toPx()))
    fun arrow(a: Offset, b: Offset, color: Color, dashed: Boolean = false) {
        drawLine(color, a, b, 3.dp.toPx(), StrokeCap.Round, if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 8f)) else null)
        val angle = atan2(b.y - a.y, b.x - a.x)
        val len = 12.dp.toPx()
        val head = Path().apply {
            moveTo(b.x, b.y)
            lineTo(b.x - len * cos(angle - 0.45f), b.y - len * sin(angle - 0.45f))
            lineTo(b.x - len * cos(angle + 0.45f), b.y - len * sin(angle + 0.45f))
            close()
        }
        drawPath(head, color)
    }
    val corner = 14.dp.toPx()
    for (key in game.keys) {
        val centre = geometry.view(key.x, key.y)
        val selected = game.editing && game.selected == key.id
        when (key.kind) {
            GameKey.Kind.Joystick -> {
                val radius = max(14.dp.toPx(), (key.radius * width).toFloat())
                drawCircle(Color.White.copy(alpha = alpha * 0.2f), radius, centre)
                drawCircle(Color.White.copy(alpha = alpha), radius, centre, style = Stroke(2.dp.toPx()))
                drawCircle(Color.White.copy(alpha = alpha), 4.dp.toPx(), centre)
                // Each direction's key sits at its edge of the ring.
                val inset = max(radius - 10.dp.toPx(), 8.dp.toPx())
                val spots = listOf(Offset(0f, -inset), Offset(-inset, 0f), Offset(0f, inset), Offset(inset, 0f))
                key.directionLabels.zip(spots).forEach { (text, off) -> label(text, centre + off, alpha) }
                if (key.walk != GameKey.Walk.Off) {
                    val mod = mapOf(56 to "Shift", 59 to "Ctrl", 58 to "Alt")[key.walkKey] ?: "Shift"
                    badge(mod + if (key.walk == GameKey.Walk.FullWithKey) " full" else " half", Offset(centre.x, centre.y + radius + 10.dp.toPx()), Color.White.copy(alpha = alpha))
                }
                if (selected) highlight(centre, radius + 4.dp.toPx())
            }
            GameKey.Kind.Look -> {
                val radius = max(14.dp.toPx(), (key.radius * width).toFloat())
                drawCircle(LookColor.copy(alpha = alpha), radius, centre, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
                label(if (game.looking) "Aiming" else if (dragLooking) "Aiming" else "Look  `", centre, alpha)
                if (selected) highlight(centre, radius + 4.dp.toPx())
            }
            GameKey.Kind.Fire -> {
                drawCircle(FireColor.copy(alpha = alpha * 0.85f), 18.dp.toPx(), centre)
                label("Click", centre, 1f, dark = true)
                if (selected) highlight(centre, 22.dp.toPx())
            }
            GameKey.Kind.Skill -> {
                // The dashed ring shows how far the finger can be pulled to aim.
                drawCircle(SkillColor.copy(alpha = alpha), max(18.dp.toPx(), (key.radius * width).toFloat()), centre, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
                drawCircle(SkillColor.copy(alpha = alpha * 0.85f), 16.dp.toPx(), centre)
                drawCircle(Color.Black.copy(alpha = 0.7f), 5.dp.toPx(), Offset(centre.x, centre.y - 5.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                label(key.label, Offset(centre.x, centre.y + 6.dp.toPx()), 1f, dark = true, size = 9f)
                if (key.quickCast) badge("Q", Offset(centre.x + corner, centre.y - corner), SkillColor)
                if (selected) highlight(centre, max(22.dp.toPx(), (key.radius * width).toFloat() + 4.dp.toPx()))
            }
            GameKey.Kind.Swipe -> {
                val end = geometry.view(key.toX, key.toY)
                arrow(centre, end, SwipeColor.copy(alpha = alpha))
                drawCircle(SwipeColor.copy(alpha = alpha * 0.9f), 15.dp.toPx(), centre)
                label(key.label, centre, 1f)
                if (selected) { highlight(centre, 19.dp.toPx()); highlight(end, 9.dp.toPx()) }
            }
            GameKey.Kind.Tap -> {
                drawCircle(Mint.copy(alpha = alpha * 0.85f), 16.dp.toPx(), centre)
                label(key.label, centre, 1f, dark = true)
                val at = Offset(centre.x + corner, centre.y - corner)
                when (key.mode) {
                    GameKey.Mode.Tap -> Unit
                    GameKey.Mode.Toggle -> badge("hold", at, Color.White)
                    GameKey.Mode.AutoFire -> badge("auto", at, SelectColor)
                    GameKey.Mode.Combo -> {
                        badge("${max(key.steps.count { it.kind != GameKey.Step.Kind.Wait }, 1)}", at, SwipeColor)
                        if (selected || game.capture == Capture.Combo(key.id)) {
                            var number = 0
                            var last: Offset? = null
                            for (step in key.steps) {
                                if (step.kind == GameKey.Step.Kind.Wait) continue
                                number++
                                val p = geometry.view(step.x, step.y)
                                last?.let { drawLine(Color.White.copy(alpha = 0.6f), it, p, 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))) }
                                if (step.kind == GameKey.Step.Kind.Swipe) arrow(p, geometry.view(step.toX, step.toY), SwipeColor)
                                badge("$number", p, SwipeColor)
                                last = if (step.kind == GameKey.Step.Kind.Swipe) geometry.view(step.toX, step.toY) else p
                            }
                        }
                    }
                }
                if (selected) highlight(centre, 20.dp.toPx())
            }
        }
    }
    game.pending?.let { spot ->
        val centre = geometry.view(spot.x, spot.y)
        drawCircle(Color.White, 16.dp.toPx(), centre, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
        label("Press a key", Offset(centre.x, centre.y + 28.dp.toPx()), 1f)
    }
    game.pendingSwipe?.let { (from, to) ->
        val a = geometry.view(from.x, from.y)
        arrow(a, geometry.view(to.x, to.y), Color.White, dashed = true)
        label("Press a key", Offset(a.x, a.y + 24.dp.toPx()), 1f)
    }
    val start = drag.pressedAt
    val end = drag.draggedTo
    if (start != null && end != null) arrow(start, end, Color.White, dashed = true)
}

// The side panel

private val palette = listOf(
    GameKey.Kind.Tap to "Tap key", GameKey.Kind.Joystick to "Joystick", GameKey.Kind.Skill to "Skill aim",
    GameKey.Kind.Swipe to "Swipe", GameKey.Kind.Look to "Look area", GameKey.Kind.Fire to "Click spot",
)

/** The line that says what to do now, as the Mac's game bar. */
fun gameHelp(game: GameKeysController, pointerLock: Boolean): String {
    game.message?.let { return it }
    if (game.editing) {
        when (val capture = game.capture) {
            is Capture.Key -> return "Press the new key for this button. Esc cancels."
            is Capture.StickKeys -> return "Press the joystick's ${listOf("up", "left", "down", "right")[minOf(capture.codes.size, 3)]} key. Esc cancels."
            is Capture.Combo -> return "Recording a combo: click spots in order, drag for a swipe. Esc or Stop recording ends."
            null -> Unit
        }
        if (game.pendingSwipe != null) return "Press the key for this swipe. Esc cancels."
        if (game.pending != null) return "Press the key for this spot. Esc cancels."
        game.placing?.let { kind -> return "Click the picture to place the ${palette.first { it.first == kind }.second.lowercase()}. Esc cancels." }
        return "Pick a control under Add a control, or: click + key = tap key, drag + key = swipe, Alt-click joystick, Shift-click look area, " +
            "Ctrl-click click spot. Click a control to edit it; drag moves, right-click deletes, scroll resizes."
    }
    if (game.looking) {
        return if (pointerLock) "Aiming with the mouse. Click presses the click spot. ` or Esc stops."
        else "Aiming while the right button is held. Left click presses the click spot."
    }
    if (game.keys.isEmpty()) return "No keys yet. Click Edit keys to place them on the picture."
    val sticks = game.joystickCount
    return "Game keys on: ${game.tapCount} key${if (game.tapCount == 1) "" else "s"}" +
        (if (sticks > 0) " + $sticks joystick${if (sticks == 1) "" else "s"}" else "") +
        (if (game.keys.any { it.kind == GameKey.Kind.Skill }) ". Hold a skill key and point with the mouse; Esc cancels" else "") +
        (if (game.hasLook) (if (pointerLock) ". Press ` to aim with the mouse" else ". Hold the right button and drag to aim") else ". The mouse taps too") + "."
}

/**
 * The game keys panel beside the picture: the profile, then while editing a palette to add controls, the placed ones,
 * and the selected one's settings; while playing, what the keys do and the aim speed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GameKeysPanel(
    game: GameKeysController,
    pointerLock: Boolean,
    modifier: Modifier = Modifier,
    unsupported: String? = null,
    scrolls: Boolean = true,
    onImport: () -> Unit = {},
    onExport: () -> Unit = {},
) {
    val colors = Dysk.colors
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.width(GamePanelWidth).fillMaxHeight().clip(shape).background(colors.panel).border(1.dp, colors.stroke, shape)) {
        Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            DyskIcon(DyskIcons.Gamepad, colors.mint, 16.dp)
            Spacer(Modifier.width(8.dp))
            Text("Game keys", Modifier.weight(1f), color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (unsupported == null) {
                if (game.editing) TextAction("Done", colors.mint) { game.edit(false); game.refocus() }
                else TextAction("Edit keys", colors.mint) { game.edit(true); game.refocus() }
            }
        }
        Divider()
        val body = if (scrolls) Modifier.weight(1f).verticalScroll(rememberScrollState()) else Modifier.weight(1f)
        Column(body.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (unsupported != null) {
                Note(unsupported, colors.amber)
                return@Column
            }
            Note(gameHelp(game, pointerLock), if (game.message != null) colors.coral else if (game.editing && (game.pending != null || game.pendingSwipe != null || game.capture != null || game.placing != null)) colors.amber else colors.textSecondary)
            ProfileSection(game, onImport, onExport)
            if (game.editing) {
                Section("Add a control") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        palette.forEach { (kind, name) ->
                            Chip(name, game.placing == kind, Modifier.width(112.dp)) { game.placing = if (game.placing == kind) null else kind; game.refocus() }
                        }
                    }
                }
                val key = game.selectedKey
                if (key != null) Section("Settings") { Inspector(game, key) }
                val free = game.freeFingers
                Section("Placed (${game.keys.size}) · $free free finger${if (free == 1) "" else "s"}") {
                    if (game.keys.isEmpty()) Note("Nothing placed yet.", colors.textFaint)
                    game.keys.forEach { key ->
                        val on = game.selected == key.id
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (on) colors.panelRaised else Color.Transparent)
                                .clickable { game.selected = key.id; game.refocus() }.padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(title(key), Modifier.weight(1f), color = colors.textPrimary, fontSize = 11.5.sp)
                            Text(keyText(key), color = colors.textSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (key == null && game.keys.isNotEmpty()) Note("Select a control in the list or on the picture to change it.", Dysk.colors.textFaint)
                if (game.keys.isNotEmpty()) TextAction("Clear all", colors.coral) { game.clear(); game.refocus() }
            } else {
                if (game.hasLook) {
                    Section("Aim speed") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Slow" to 0.6, "Normal" to 1.0, "Fast" to 1.7).forEach { (name, value) ->
                                Chip(name, game.lookSpeed == value) { game.chooseLookSpeed(value); game.refocus() }
                            }
                        }
                    }
                    Note(
                        if (pointerLock) "Press ` to aim with the mouse: the pointer is held and hidden until ` or Esc."
                        else "Wayland doesn't let apps hold or move the pointer, so hold the right mouse button on the picture and drag to look.",
                        colors.textFaint,
                    )
                }
                Note("Keys go to the game while the picture has the keyboard; click it once. Focus elsewhere lifts every finger.", colors.textFaint)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileSection(game: GameKeysController, onImport: () -> Unit, onExport: () -> Unit) {
    val colors = Dysk.colors
    var naming by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf(false) }
    Section("Game profile") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            game.profiles.forEachIndexed { index, profile ->
                Chip(profile.name, index == game.current) { game.selectProfile(index); game.refocus() }
            }
        }
        val text = naming
        if (text != null) {
            InputField(text, { naming = it.take(40) }, "Game name", Modifier.fillMaxWidth()) {
                if (renaming) game.renameProfile(text) else game.addProfile(text)
                naming = null
                game.refocus()
            }
            Row {
                TextAction(if (renaming) "Rename" else "Add", colors.mint) {
                    if (renaming) game.renameProfile(text) else game.addProfile(text)
                    naming = null
                    game.refocus()
                }
                TextAction("Cancel", colors.textSecondary) { naming = null; game.refocus() }
            }
        } else {
            FlowRow {
                TextAction("New", colors.mint) { renaming = false; naming = "" }
                TextAction("Rename", colors.textSecondary) { renaming = true; naming = game.profileName }
                TextAction(if (game.profiles.size > 1) "Delete" else "Clear keys", colors.coral) { game.deleteProfile(); game.refocus() }
                TextAction("Import", colors.textSecondary, onImport)
                TextAction("Export", colors.textSecondary, onExport)
            }
        }
    }
}

private fun title(key: GameKey): String = when (key.kind) {
    GameKey.Kind.Joystick -> "Joystick"
    GameKey.Kind.Look -> "Look area"
    GameKey.Kind.Fire -> "Click spot"
    GameKey.Kind.Skill -> "Skill aim"
    GameKey.Kind.Swipe -> "Swipe"
    GameKey.Kind.Tap -> when (key.mode) {
        GameKey.Mode.Tap -> "Tap"
        GameKey.Mode.Toggle -> "Hold toggle"
        GameKey.Mode.AutoFire -> "Auto-fire"
        GameKey.Mode.Combo -> "Combo"
    }
}

private fun keyText(key: GameKey): String = when (key.kind) {
    GameKey.Kind.Joystick -> key.directionLabels.joinToString("")
    GameKey.Kind.Look -> "`"
    GameKey.Kind.Fire -> "Mouse"
    else -> key.label
}

/** The selected control's settings: kind, key, mode, and the rest. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Inspector(game: GameKeysController, key: GameKey) {
    val colors = Dysk.colors
    Column(
        Modifier.fillMaxWidth().background(colors.panelRaised, RoundedCornerShape(10.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title(key), color = colors.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        fun change(block: (GameKey) -> GameKey) { game.update(key.id, block); game.refocus() }
        if (key.isAction) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(GameKey.Kind.Tap to "Tap", GameKey.Kind.Skill to "Skill", GameKey.Kind.Swipe to "Swipe").forEach { (kind, name) ->
                    Chip(name, key.kind == kind) { game.setKind(key.id, kind); game.refocus() }
                }
            }
        }
        @Composable
        fun keyRow() {
            val waiting = game.capture == Capture.Key(key.id)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Key", Modifier.weight(1f), color = colors.textSecondary, fontSize = 11.5.sp)
                Box(
                    Modifier.width(110.dp).height(24.dp).clip(RoundedCornerShape(5.dp)).background(colors.background)
                        .border(1.dp, if (waiting) colors.amber else colors.stroke, RoundedCornerShape(5.dp))
                        .clickable { game.capture = if (waiting) null else Capture.Key(key.id); game.refocus() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (waiting) "Press a key..." else key.label, color = if (waiting) colors.amber else colors.textPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        @Composable
        fun radius(name: String) {
            fun step(delta: Double) = change { it.copy(radius = (Math.round((it.radius + delta) * 100) / 100.0).coerceIn(0.03, 0.25)) }
            Stepper("$name ${Math.round(key.radius * 100)}% of width", { step(-0.01) }, { step(0.01) })
        }
        when (key.kind) {
            GameKey.Kind.Joystick -> {
                Label("Keys")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(GameKey.KeySet.Wasd to "WASD", GameKey.KeySet.Arrows to "Arrows", GameKey.KeySet.Ijkl to "IJKL", GameKey.KeySet.Custom to "Custom").forEach { (set, name) ->
                        Chip(name, key.keySet == set) {
                            change { k -> k.copy(keySet = set).let { it.copy(label = it.directionLabels.joinToString("")) } }
                            if (set == GameKey.KeySet.Custom && key.customKeys.size != 4) game.capture = Capture.StickKeys(key.id, emptyList(), emptyList())
                        }
                    }
                }
                val capture = game.capture
                if (capture is Capture.StickKeys && capture.id == key.id) {
                    Note("Press the key for ${listOf("up", "left", "down", "right")[capture.codes.size]}... Esc stops.", colors.amber)
                } else if (key.keySet == GameKey.KeySet.Custom) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(key.directionLabels.joinToString(" "), Modifier.weight(1f), color = colors.textPrimary, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        TextAction("Pick 4 keys", colors.mint) { game.capture = Capture.StickKeys(key.id, emptyList(), emptyList()); game.refocus() }
                    }
                }
                Label("Walk")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(GameKey.Walk.Off to "Off", GameKey.Walk.FullWithKey to "Hold for full", GameKey.Walk.HalfWithKey to "Hold for half").forEach { (walk, name) ->
                        Chip(name, key.walk == walk) { change { it.copy(walk = walk) } }
                    }
                }
                if (key.walk != GameKey.Walk.Off) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(56 to "Shift", 59 to "Ctrl", 58 to "Alt").forEach { (code, name) -> Chip(name, key.walkKey == code) { change { it.copy(walkKey = code) } } }
                    }
                }
                radius("Reach")
            }
            GameKey.Kind.Tap -> {
                keyRow()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(GameKey.Mode.Tap to "Tap", GameKey.Mode.Toggle to "Hold toggle", GameKey.Mode.AutoFire to "Auto-fire", GameKey.Mode.Combo to "Combo").forEach { (mode, name) ->
                        Chip(name, key.mode == mode) { change { it.copy(mode = mode) } }
                    }
                }
                when (key.mode) {
                    GameKey.Mode.Tap -> Note("Down while the key is held.")
                    GameKey.Mode.Toggle -> Note("Press once to hold, again to let go.")
                    GameKey.Mode.AutoFire -> Stepper("${key.rate.toInt()} taps a second",
                        { change { it.copy(rate = (it.rate - 1).coerceIn(1.0, 20.0)) } }, { change { it.copy(rate = (it.rate + 1).coerceIn(1.0, 20.0)) } })
                    GameKey.Mode.Combo -> {
                        val recording = game.capture == Capture.Combo(key.id)
                        Row {
                            TextAction(if (recording) "Stop recording" else "Record combo", colors.mint) {
                                if (recording) game.capture = null else game.recordCombo(key.id)
                                game.refocus()
                            }
                            Spacer(Modifier.weight(1f))
                            if (key.steps.isNotEmpty()) TextAction("+ Wait", colors.textSecondary) { change { it.copy(steps = it.steps + GameKey.Step(kind = GameKey.Step.Kind.Wait, ms = 80.0)) } }
                        }
                        if (recording) Note("Click spots in order; drag for a swipe.", colors.amber)
                        if (key.steps.isEmpty() && !recording) Note("No steps yet: the key taps its own spot.")
                        key.steps.forEachIndexed { index, step ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${index + 1}", Modifier.width(18.dp), color = colors.textFaint, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                fun ms(delta: Double, min: Double, maxMs: Double) = change { k ->
                                    k.copy(steps = k.steps.map { if (it.id == step.id) it.copy(ms = (it.ms + delta).coerceIn(min, maxMs)) else it })
                                }
                                when (step.kind) {
                                    GameKey.Step.Kind.Tap -> Text("Tap  ${(step.x * 100).toInt()}, ${(step.y * 100).toInt()}", Modifier.weight(1f), color = colors.textPrimary, fontSize = 11.sp)
                                    GameKey.Step.Kind.Swipe -> Stepper("Swipe ${step.ms.toInt()} ms", { ms(-20.0, 20.0, 2000.0) }, { ms(20.0, 20.0, 2000.0) }, Modifier.weight(1f))
                                    GameKey.Step.Kind.Wait -> Stepper("Wait ${step.ms.toInt()} ms", { ms(-10.0, 0.0, 5000.0) }, { ms(10.0, 0.0, 5000.0) }, Modifier.weight(1f))
                                }
                                TextAction("x", colors.textSecondary) { change { k -> k.copy(steps = k.steps.filter { it.id != step.id }) } }
                            }
                        }
                    }
                }
            }
            GameKey.Kind.Skill -> {
                keyRow()
                radius("Aim reach")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Quick cast: a short press taps", Modifier.weight(1f), color = colors.textPrimary, fontSize = 11.5.sp)
                    DyskSwitch(key.quickCast) { on -> change { it.copy(quickCast = on) } }
                }
                Note("Hold the key and point with the mouse, measured from the screen's middle. Let go casts; Esc cancels.")
            }
            GameKey.Kind.Swipe -> {
                keyRow()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("From the spot", Modifier.weight(1f), color = colors.textSecondary, fontSize = 11.5.sp)
                    listOf("↑" to GameKeysController.Direction.Up, "↓" to GameKeysController.Direction.Down, "←" to GameKeysController.Direction.Left, "→" to GameKeysController.Direction.Right).forEach { (arrow, direction) ->
                        Chip(arrow, false) { game.swipePreset(key.id, direction); game.refocus() }
                    }
                }
                Stepper("Takes ${key.duration.toInt()} ms", { change { it.copy(duration = (it.duration - 20).coerceIn(20.0, 2000.0)) } }, { change { it.copy(duration = (it.duration + 20).coerceIn(20.0, 2000.0)) } })
                Note("Drag the arrow's head to change where it ends.")
            }
            GameKey.Kind.Look -> radius("Area")
            GameKey.Kind.Fire -> Note("The mouse button presses this spot while aiming with `.")
        }
        if (game.conflicts(key)) Note("Another control uses the same keyboard key. The first one wins.", colors.amber)
        TextAction("Delete this control", colors.coral) { game.remove(key.id); game.refocus() }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(), color = Dysk.colors.textFaint, fontSize = 9.5.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun Label(text: String) = Text(text, color = Dysk.colors.textSecondary, fontSize = 11.sp)

@Composable
private fun Note(text: String, color: Color = Dysk.colors.textSecondary) = Text(text, color = color, fontSize = 11.sp, lineHeight = 15.sp)

/** A small choice button, filled when it's the one chosen. */
@Composable
private fun Chip(text: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = Dysk.colors
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.height(26.dp).clip(shape).background(if (on) colors.mint else colors.panelRaised).border(1.dp, if (on) colors.mint else colors.stroke, shape)
            .clickable(onClick = onClick).padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, color = if (on) colors.ink else colors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun Stepper(text: String, minus: () -> Unit, plus: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Dysk.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = colors.textPrimary, fontSize = 11.5.sp)
        Chip("-", false, onClick = minus)
        Spacer(Modifier.width(4.dp))
        Chip("+", false, onClick = plus)
    }
}

/** Saves the current profile as the Mac's JSON, or adds profiles from such a file. Runs a file dialog. */
object GameKeyFiles {
    fun export(game: GameKeysController) {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Export game profile", java.awt.FileDialog.SAVE)
        dialog.file = game.profileName.replace(Regex("[^A-Za-z0-9 _-]"), "").ifBlank { "Game" } + ".json"
        dialog.isVisible = true
        val name = dialog.file ?: return
        runCatching { java.io.File(dialog.directory, name).writeText(game.exportProfile()) }
            .onFailure { game.message = "Couldn't save the profile: ${it.message}" }
    }

    fun import(game: GameKeysController) {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Import game profile", java.awt.FileDialog.LOAD)
        dialog.isVisible = true
        val name = dialog.file ?: return
        val file = java.io.File(dialog.directory, name)
        if (file.length() > 2_000_000) { game.message = "That file is too big for a game profile."; return }
        runCatching { game.importProfiles(file.readText()) }.onFailure { game.message = "Couldn't read the profile: ${it.message}" }
        game.refocus()
    }
}
