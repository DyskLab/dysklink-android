package id.dysklink.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.dysklink.desktop.ExtendWay
import id.dysklink.desktop.HostHint
import id.dysklink.desktop.SecondDisplayState
import id.dysklink.desktop.SecondDisplayView

/**
 * The phone as a second display, under "Let phones see this screen": whether this PC can add a monitor for it, what
 * to set up when it can't yet, and while a phone is one, its size and Stop.
 */
@Composable
fun SecondDisplaySection(view: SecondDisplayView, hints: List<HostHint>, model: DeskModel) {
    val colors = Dysk.colors
    val state = view.state
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Space.sm)) {
            Text("Second display", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(secondDisplayText(view, model.isWindows), color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp)
        }
        when (state) {
            SecondDisplayState.Active -> {
                StatusPill(if (view.active) "Active ${view.width}×${view.height}" else "Adding…", colors.violet)
                Spacer(Modifier.width(Space.sm))
                SecondaryButton("Stop", onClick = model::stopScreenOut)
            }
            SecondDisplayState.Available -> StatusPill("Available", colors.mint)
            SecondDisplayState.NeedsSetup -> StatusPill("Needs setup", colors.amber)
            SecondDisplayState.Unavailable -> StatusPill("Not available", colors.textSecondary)
            SecondDisplayState.Checking -> StatusPill("Checking…", colors.textSecondary)
        }
    }
    if (state != SecondDisplayState.Active && state != SecondDisplayState.Checking) HintList(hints, model)
    // Not while a phone is the second display: turning it off then would leave the monitor without its switch.
    if (view.experimentShown && state != SecondDisplayState.Active) {
        Spacer(Modifier.height(Space.sm))
        SettingRow(
            "Use the Virtual Display Driver (experimental)",
            "Adds a real monitor to Windows for the phone through an open-source display driver. It hasn't been tried on many PCs yet.",
            view.experimentOn, onChange = model::setExtendExperimentOn,
        )
    }
}

fun secondDisplayText(view: SecondDisplayView, windows: Boolean): String {
    val arrange = if (windows) "Settings, System, Display" else "the display settings"
    return when (view.state) {
        SecondDisplayState.Active -> {
            val name = view.phoneName ?: "A phone"
            if (view.active) "$name is a monitor of this PC now. Drag windows onto it, and arrange it in $arrange. Stop takes the monitor away."
            else "Adding a monitor for $name. The PC may ask you to allow it."
        }
        SecondDisplayState.Checking -> "Looking at what this PC can do."
        SecondDisplayState.Available -> {
            val way = view.way?.way
            val ask = if (way == ExtendWay.PortalVirtual) " The first time, the PC asks you to allow it." else ""
            val off = if (view.screenOn) "" else " Turn on \"Let phones see this screen\" above first."
            "Open Second display on the phone: this PC adds a monitor of the phone's shape, through ${way?.label ?: "a virtual monitor"}, while it's used.$ask$off"
        }
        SecondDisplayState.NeedsSetup -> "This PC can add a monitor for the phone after a one-time setup:"
        SecondDisplayState.Unavailable ->
            if (view.experimentShown && !view.experimentOn) "Windows adds a monitor only through a display driver. Turn on the driver below to try it."
            else "This PC can't add a monitor for the phone. The phone can still view this screen."
    }
}
