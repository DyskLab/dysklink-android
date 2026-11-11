package id.phonelink.android.ui

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import id.phonelink.android.core.AirMouse
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.phonelink.android.core.CommandItem
import id.phonelink.android.core.CommandResult
import id.phonelink.android.core.InputEvent
import id.phonelink.android.core.Transfer
import id.phonelink.android.core.TransferStatus
import kotlin.math.abs
import kotlin.math.hypot

enum class Feature(val title: String) {
    Touchpad("Touchpad & keyboard"),
    Media("Media remote"),
    Presentation("Presentation remote"),
    Commands("Mac commands"),
    Terminal("Terminal"),
    MacScreen("Mac screen"),
    SecondDisplay("Second display"),
    Sound("Sound sharing"),
    Camera("Camera to Mac"),
    Notch("Todo, water & Pomodoro"),
    Deck("Stream Deck"),
    Scan("Scan barcodes to Mac"),
    DocScan("Scan documents to Mac"),
    Dictation("Voice typing to Mac"),
    Sharing("Phone data for Mac"),
}

@Composable
internal fun FeatureScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Text(
            "‹ Back",
            color = Palette.TextSecondary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .padding(vertical = 8.dp, horizontal = 4.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(title, color = Palette.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        content()
    }
}

@Composable
private fun PadButton(label: String, modifier: Modifier = Modifier, height: Int = 58, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .height(height.dp)
            .clip(shape)
            .background(Palette.PanelRaised)
            .border(1.dp, Palette.Stroke, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Palette.TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

// Touchpad and keyboard

@Composable
fun TouchpadScreen(onBack: () -> Unit, send: (InputEvent) -> Unit) {
    var typed by remember { mutableStateOf("") }
    FeatureScaffold(Feature.Touchpad.title, onBack) {
        Touchpad(Modifier.fillMaxWidth().weight(1f), send)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PadButton("Left click", Modifier.weight(1f)) { send(InputEvent("click", button = "left")) }
            PadButton("Right click", Modifier.weight(1f)) { send(InputEvent("click", button = "right")) }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = typed,
            onValueChange = { value ->
                when {
                    value.length > typed.length && value.startsWith(typed) ->
                        send(InputEvent("text", text = value.substring(typed.length)))
                    value.length < typed.length && typed.startsWith(value) ->
                        repeat(typed.length - value.length) { send(InputEvent("key", key = "delete")) }
                    else -> if (value.isNotEmpty()) send(InputEvent("text", text = value))
                }
                typed = value
            },
            label = { Text("Type to Mac") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                send(InputEvent("key", key = "return"))
                typed = ""
            }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Esc" to "escape", "Tab" to "tab", "←" to "left", "↑" to "up", "↓" to "down", "→" to "right")
                .forEach { (label, key) ->
                    PadButton(label, Modifier.weight(1f), height = 46) { send(InputEvent("key", key = key)) }
                }
        }
    }
}

/**
 * One finger moves the pointer and a tap is a left click.
 * Two fingers scroll and a tap is a right click.
 */
@Composable
private fun Touchpad(modifier: Modifier, send: (InputEvent) -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .clip(shape)
            .background(Palette.Panel.copy(alpha = 0.9f))
            .border(1.dp, Palette.Mint.copy(alpha = 0.25f), shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val first = awaitFirstDown()
                    val started = first.uptimeMillis
                    var maxFingers = 1
                    var travelled = 0f
                    var lastCentroid = first.position
                    var lastCount = 1
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        maxFingers = maxOf(maxFingers, pressed.size)
                        val centroid = Offset(
                            pressed.map { it.position.x }.average().toFloat(),
                            pressed.map { it.position.y }.average().toFloat(),
                        )
                        // When the finger count changes, the center jumps and must not count as movement.
                        if (pressed.size == lastCount) {
                            val delta = centroid - lastCentroid
                            travelled += hypot(delta.x, delta.y)
                            if (abs(delta.x) + abs(delta.y) > 0f) {
                                if (pressed.size >= 2) {
                                    send(InputEvent("scroll", dx = delta.x, dy = delta.y))
                                } else {
                                    send(InputEvent("move", dx = delta.x * SENSITIVITY, dy = delta.y * SENSITIVITY))
                                }
                            }
                        }
                        lastCentroid = centroid
                        lastCount = pressed.size
                        event.changes.forEach { it.consume() }
                    }
                    val duration = currentEvent.changes.first().uptimeMillis - started
                    if (duration < TAP_MS && travelled < TAP_SLOP) {
                        send(InputEvent("click", button = if (maxFingers >= 2) "right" else "left"))
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Slide one finger to move the pointer\nSlide two fingers to scroll",
            color = Palette.TextFaint,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
        )
    }
}

private const val SENSITIVITY = 1.1f
private const val TAP_MS = 220L
private const val TAP_SLOP = 18f

// Media

private enum class MediaGlyph { PlayPause, Next, Previous }

@Composable
private fun RoundMediaButton(glyph: MediaGlyph, size: Int, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .then(
                if (primary) Modifier.background(Palette.Accent)
                else Modifier.background(Palette.PanelRaised).border(1.dp, Palette.Stroke, CircleShape),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (primary) Palette.Ink else Palette.TextPrimary
        Canvas(Modifier.size((size * 0.42f).dp)) {
            val w = this.size.width
            val h = this.size.height
            fun triangle(left: Float, right: Float, pointRight: Boolean) = Path().apply {
                moveTo(if (pointRight) left else right, 0f)
                lineTo(if (pointRight) right else left, h / 2)
                lineTo(if (pointRight) left else right, h)
                close()
            }
            when (glyph) {
                MediaGlyph.PlayPause -> {
                    drawPath(triangle(0f, w * 0.56f, true), tint)
                    drawRect(tint, Offset(w * 0.66f, 0f), Size(w * 0.12f, h))
                    drawRect(tint, Offset(w * 0.88f, 0f), Size(w * 0.12f, h))
                }
                MediaGlyph.Next -> {
                    drawPath(triangle(0f, w * 0.8f, true), tint)
                    drawRect(tint, Offset(w * 0.84f, 0f), Size(w * 0.16f, h))
                }
                MediaGlyph.Previous -> {
                    drawRect(tint, Offset(0f, 0f), Size(w * 0.16f, h))
                    drawPath(triangle(w * 0.2f, w, false), tint)
                }
            }
        }
    }
}

@Composable
fun MediaControls(send: (String) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundMediaButton(MediaGlyph.Previous, 68) { send("previous") }
            RoundMediaButton(MediaGlyph.PlayPause, 96, primary = true) { send("play_pause") }
            RoundMediaButton(MediaGlyph.Next, 68) { send("next") }
        }
        Spacer(Modifier.height(26.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PadButton("Volume −", Modifier.weight(1f)) { send("volume_down") }
            PadButton("Mute", Modifier.weight(1f)) { send("mute") }
            PadButton("Volume +", Modifier.weight(1f)) { send("volume_up") }
        }
    }
}

@Composable
fun MediaScreen(onBack: () -> Unit, send: (String) -> Unit) {
    FeatureScaffold(Feature.Media.title, onBack) {
        Text(
            "Controls the active player on the Mac, such as Spotify, YouTube, or Music.",
            color = Palette.TextSecondary,
            fontSize = 14.sp,
            lineHeight = 21.sp,
        )
        Spacer(Modifier.height(36.dp))
        MediaControls(send)
    }
}

// Presentation

@Composable
fun PresentationScreen(onBack: () -> Unit, send: (InputEvent) -> Unit) {
    val key = { name: String -> send(InputEvent("key", key = name)) }
    val context = LocalContext.current
    val sensors = remember { context.getSystemService(SensorManager::class.java) }
    val gyroscope = remember { sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }
    FeatureScaffold(Feature.Presentation.title, onBack) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(Palette.Accent)
                .clickable { key("right") },
            contentAlignment = Alignment.Center,
        ) {
            Text("Next slide", color = Palette.Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        if (sensors != null && gyroscope != null) {
            val prefs = remember { context.getSharedPreferences("air_mouse", android.content.Context.MODE_PRIVATE) }
            var speed by remember { mutableStateOf(prefs.getFloat("speed", 1f)) }
            AirPointerPad(sensors, gyroscope, speed, Modifier.fillMaxWidth().weight(0.8f), send)
            Spacer(Modifier.height(8.dp))
            // How far the pointer goes for the same turn of the phone.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Pointer speed", color = Palette.TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                listOf("Slow" to 0.6f, "Normal" to 1f, "Fast" to 1.6f).forEach { (label, value) ->
                    val chosen = speed == value
                    Text(
                        label,
                        color = if (chosen) Palette.Ink else Palette.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (chosen) Palette.Mint else Color.White.copy(alpha = 0.08f))
                            .clickable {
                                speed = value
                                prefs.edit().putFloat("speed", value).apply()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        PadButton("Previous slide", Modifier.fillMaxWidth(), height = 76) { key("left") }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PadButton("Start", Modifier.weight(1f)) { key("f5") }
            PadButton("Black screen", Modifier.weight(1f)) { key("b") }
            PadButton("End", Modifier.weight(1f)) { key("escape") }
        }
    }
}

/** Air mouse: while held, the Mac's pointer follows the phone's turns. A quick tap clicks. */
@Composable
private fun AirPointerPad(sensors: SensorManager, gyroscope: Sensor, speed: Float, modifier: Modifier, send: (InputEvent) -> Unit) {
    val haptics = LocalHapticFeedback.current
    var holding by remember { mutableStateOf(false) }
    val mouse = remember(speed) { AirMouse(AirMouse.GAIN * speed) }
    val moved = remember { floatArrayOf(0f) }
    // The gyroscope draws power at game rate, so the sensors run only while the pad is held.
    DisposableEffect(sensors, gyroscope, mouse, holding) {
        if (!holding) return@DisposableEffect onDispose {}
        var lastNanos = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GRAVITY -> mouse.onGravity(event.values[0], event.values[1], event.values[2])
                    Sensor.TYPE_GYROSCOPE -> {
                        val seconds = if (lastNanos == 0L) 0f else (event.timestamp - lastNanos) / 1e9f
                        lastNanos = event.timestamp
                        if (!holding) return
                        mouse.onRotation(event.values[0], event.values[1], event.values[2], seconds)
                        mouse.take(android.os.SystemClock.uptimeMillis())?.let { (dx, dy) ->
                            moved[0] += kotlin.math.abs(dx) + kotlin.math.abs(dy)
                            send(InputEvent("move", dx = dx, dy = dy))
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        sensors.registerListener(listener, gyroscope, SensorManager.SENSOR_DELAY_GAME)
        sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sensors.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sensors.unregisterListener(listener) }
    }
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(if (holding) Palette.Violet else Palette.Violet.copy(alpha = 0.25f))
            .pointerInput(mouse) {
                awaitEachGesture {
                    awaitFirstDown()
                    val downAt = android.os.SystemClock.uptimeMillis()
                    mouse.reset()
                    moved[0] = 0f
                    holding = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    holding = false
                    mouse.reset()
                    val quick = android.os.SystemClock.uptimeMillis() - downAt < AirMouse.TAP_MS
                    if (quick && moved[0] < AirMouse.TAP_MOVE) send(InputEvent("click"))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (holding) "Pointing" else "Hold to point",
                color = if (holding) Palette.Ink else Palette.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (holding) "Turn the phone to move the pointer" else "Tap to click",
                color = if (holding) Palette.Ink.copy(alpha = 0.7f) else Palette.TextSecondary,
                fontSize = 13.sp,
            )
        }
    }
}

// Commands

@Composable
fun CommandsScreen(
    commands: List<CommandItem>,
    result: CommandResult?,
    onBack: () -> Unit,
    onRun: (String) -> Unit,
) {
    FeatureScaffold(Feature.Commands.title, onBack) {
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (commands.isEmpty()) {
                Text(
                    "No commands yet. Add them from the DyskLink app on the Mac.",
                    color = Palette.TextSecondary,
                    fontSize = 14.sp,
                )
            }
            commands.forEach { command ->
                GlassCard(onClick = { onRun(command.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            command.name,
                            color = Palette.TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text("Run", color = Palette.Mint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    if (result?.id == command.id) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (result.exitCode == 0) "Done" else "Failed, code ${result.exitCode}",
                            color = if (result.exitCode == 0) Palette.Mint else Palette.Coral,
                            fontSize = 13.sp,
                        )
                        if (result.output.isNotBlank()) {
                            Text(result.output, color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                }
            }
        }
    }
}

// File transfer

