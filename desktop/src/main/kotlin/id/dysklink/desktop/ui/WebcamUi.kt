package id.dysklink.desktop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.dysklink.desktop.WebcamState
import id.dysklink.desktop.WebcamStatus

// "Use as webcam": the phone camera as DyskLink Camera in Zoom, Meet, Teams and OBS.

/** The status in a word and its color. */
@Composable
internal fun webcamPill(state: WebcamState): Pair<String, Color>? {
    val colors = Dysk.colors
    return when (state.status) {
        WebcamStatus.Off -> null
        WebcamStatus.Starting -> "Starting" to colors.textSecondary
        WebcamStatus.Active -> "Active" to colors.mint
        WebcamStatus.NeedsSetup -> "Needs setup" to colors.amber
        WebcamStatus.Busy -> "Busy" to colors.coral
    }
}

/** What the switch says under its title. */
fun webcamDetail(state: WebcamState): String = when (state.status) {
    WebcamStatus.Off -> "Video apps such as Zoom, Meet, Teams and OBS (Video Capture Device) can pick the phone camera as '${state.label}'."
    WebcamStatus.Starting -> "Looking for the camera device..."
    WebcamStatus.Active -> "Apps list it as '${state.label}'" + (state.device?.takeIf { it.startsWith("/dev/") }?.let { " ($it)" } ?: "") + ". " +
        if (state.live) "The phone camera is going out now." else "It shows 'camera is off' until the phone camera starts."
    WebcamStatus.NeedsSetup -> "This PC needs a camera device first. Do the steps below once; DyskLink starts it by itself."
    WebcamStatus.Busy -> state.problem ?: "Another app is sending to the device."
}

/** The switch, its status, and what to do while it can't start: on the Camera tab. */
@Composable
fun WebcamCard(model: DeskModel) {
    val flow = model.webcam ?: return
    val state by flow.collectAsState()
    val colors = Dysk.colors
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use as webcam", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            webcamPill(state)?.let { (text, color) -> StatusPill(text, color); Spacer(Modifier.width(Space.sm)) }
            DyskSwitch(state.on) { model.setWebcamOn(it) }
        }
        Spacer(Modifier.height(2.dp))
        Text(webcamDetail(state), color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        if (state.on) HintList(state.hints, model)
    }
}

/** The same switch as a button in the camera window, where there's no room for its hints. */
@Composable
fun WebcamButton(model: DeskModel) {
    val flow = model.webcam ?: return
    val state by flow.collectAsState()
    val colors = Dysk.colors
    val (text, tint) = when (state.status) {
        WebcamStatus.Off -> "Use as webcam" to colors.textPrimary
        WebcamStatus.Active -> "Webcam on" to colors.mint
        WebcamStatus.Starting -> "Webcam starting" to colors.textSecondary
        WebcamStatus.NeedsSetup -> "Webcam: needs setup" to colors.amber
        WebcamStatus.Busy -> "Webcam: busy" to colors.coral
    }
    SecondaryButton(text, tint, icon = DyskIcons.Camera) { model.setWebcamOn(!state.on) }
}
