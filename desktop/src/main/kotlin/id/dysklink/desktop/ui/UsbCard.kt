package id.dysklink.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.dysklink.desktop.AdbDevice
import id.dysklink.desktop.UsbState

/**
 * "USB connection", as on the Mac: the switch, whether adb is here, the phones on the cable, and what to do when
 * one doesn't show up. In Settings, and on a phone's Overview, where [viaUsb] says that phone is on the cable now.
 */
@Composable
fun UsbCard(model: DeskModel, viaUsb: Boolean = false) {
    val colors = Dysk.colors
    val usb by model.usb.collectAsState()
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(DyskIcons.Usb, if (viaUsb) colors.mint else colors.textSecondary)
            Spacer(Modifier.width(Space.sm))
            Text("USB connection", Modifier.weight(1f), color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            if (viaUsb) StatusPill("Connected over USB", colors.mint)
        }
        Spacer(Modifier.height(Space.sm))
        SettingRow("Connect phones over USB", usbStatus(usb, viaUsb), usb.enabled, onChange = model::setUsbOn)
        if (!usb.enabled) return@GlassCard
        Divider()
        val adb = usb.adbPath
        if (adb != null) {
            Text("Android tools", color = colors.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            SelectionContainer {
                Text(adb, color = colors.textSecondary, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = Space.sm)) {
                    Text("Android tools (adb) not found", color = colors.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "About 15 MB from Google, saved in DyskLink's folder. Or install Android platform tools: ${installHint(usb)}",
                        color = colors.textFaint, fontSize = 11.5.sp, lineHeight = 15.sp,
                    )
                }
                val progress = usb.progress
                SecondaryButton(
                    when {
                        usb.downloading && progress != null -> "Downloading ${(progress * 100).toInt()}%"
                        usb.downloading -> "Downloading..."
                        else -> "Download Android tools"
                    },
                    enabled = !usb.downloading, icon = DyskIcons.Download, onClick = model::downloadAdb,
                )
            }
            usb.downloadError?.let {
                Spacer(Modifier.height(Space.xs))
                Text(it, color = colors.coral, fontSize = 12.sp)
            }
        }
        if (usb.devices.isNotEmpty()) {
            Divider()
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) { usb.devices.forEach { UsbDeviceRow(it) } }
        }
        Divider()
        Text(usbHelp(usb), color = colors.textFaint, fontSize = 11.5.sp, lineHeight = 15.sp)
    }
}

@Composable
private fun UsbDeviceRow(device: AdbDevice) {
    val colors = Dysk.colors
    val (label, color) = when (device.state) {
        "device" -> "Ready" to colors.mint
        "unauthorized" -> "Allow on the phone" to colors.amber
        "offline" -> "Offline" to colors.textFaint
        else -> device.state.replaceFirstChar { it.uppercase() } to colors.textFaint
    }
    Row(verticalAlignment = Alignment.Top) {
        IconWell(DyskIcons.Phone, color, 28.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).padding(end = Space.sm)) {
            Text(device.model.ifEmpty { device.serial }, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            deviceHelp(device)?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, color = colors.textSecondary, fontSize = 11.5.sp, lineHeight = 15.sp)
            }
        }
        Text(
            label, Modifier.background(color.copy(alpha = 0.13f), CircleShape).padding(horizontal = 9.dp, vertical = 3.dp),
            color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

/** What the card says under its title, as on the Mac. */
fun usbStatus(usb: UsbState, viaUsb: Boolean): String = when {
    !usb.enabled -> "Off. Phones connect over Wi-Fi only."
    viaUsb -> "Everything goes through the cable now: smoother screen and camera, faster files. Unplug to go back to Wi-Fi."
    usb.adbPath == null -> "Needs Android's adb tool on this PC. Download it once below, or install Android platform tools."
    usb.waiting.isNotEmpty() -> "A phone is plugged in. Tap Allow on the phone's USB debugging prompt."
    usb.ready.isNotEmpty() -> "Phone found on USB. Open DyskLink on the phone; it switches to the cable within a few seconds."
    else -> "Plug the phone in with a cable and turn on USB debugging (Settings, Developer options) for a smoother, faster connection."
}

private fun deviceHelp(device: AdbDevice): String? = when (device.state) {
    "device" -> null
    "unauthorized" -> "Unlock the phone and tap Allow on the USB debugging prompt. Tick Always allow from this computer to skip it next time."
    "offline" -> "adb can't talk to the phone. Unplug the cable and plug it in again, or turn USB debugging off and on."
    else -> "Unplug the cable and plug it in again."
}

private fun installHint(usb: UsbState): String =
    if (usb.windows) "Android Studio, or SDK Platform Tools from developer.android.com."
    else "the adb package (android-tools-adb on Debian and Ubuntu, android-tools on Arch and Fedora)."

/** What to try when a phone doesn't show up. */
fun usbHelp(usb: UsbState): String =
    if (usb.windows) {
        "Phone not showing up? Windows may need the phone maker's USB driver (the Google USB Driver for Pixel phones). " +
            "Also check that the cable carries data, not only charging."
    } else {
        "Phone not showing up? Linux may need udev rules: install android-sdk-platform-tools-common on Debian and Ubuntu, " +
            "or android-udev-rules on Arch and Fedora, and add yourself to the plugdev group, then log in again."
    }
