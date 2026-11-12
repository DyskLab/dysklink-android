package id.dysklink.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.dysklink.desktop.Platform
import id.phonelink.android.core.HubPhone
import java.io.File

/**
 * The whole window: the sidebar with this PC and its devices, and the page for what's selected.
 * [radarProgress] stops the radar on one frame, for snapshots.
 */
@Composable
fun MainScreen(model: DeskModel, ui: UiState, pickFiles: () -> List<File>, radarProgress: Float? = null) {
    val colors = Dysk.colors
    val phones by model.phones.collectAsState()
    val pairing by model.pairing.collectAsState()
    val problem by model.problem.collectAsState()
    val screenTo by model.screenTo.collectAsState()
    val selected = phones.firstOrNull { it.id == ui.selectedId }
        ?: phones.firstOrNull { it.isPaired && !it.isComputer }
        ?: phones.firstOrNull { it.isPaired }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Aurora()
        Row(Modifier.fillMaxSize()) {
            Sidebar(model, ui, phones, if (ui.page == Page.Device) selected?.id else null)
            Box(Modifier.fillMaxHeight().width(1.dp).background(colors.stroke))
            Column(Modifier.fillMaxHeight().weight(1f)) {
                problem?.let {
                    Box(Modifier.padding(start = 28.dp, end = 28.dp, top = Space.lg)) { Banner(it, colors.coral, DyskIcons.Warning) }
                }
                // On every page, so this screen is never watched unnoticed.
                screenTo?.let { id ->
                    val name = phones.firstOrNull { it.id == id }?.name ?: "A phone"
                    Box(Modifier.padding(start = 28.dp, end = 28.dp, top = Space.lg)) {
                        Banner("$name is viewing this screen.", colors.violet, DyskIcons.Monitor, "Stop" to model::stopScreenOut)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        ui.page == Page.Tools -> ToolsPage(model)
                        ui.page == Page.History -> HistoryPage(model)
                        ui.page == Page.Settings -> SettingsPage(model)
                        selected != null && selected.isPaired -> DevicePage(model, ui, selected, pickFiles)
                        selected != null -> NewDeviceState(model, selected)
                        else -> EmptyState(model, phones, radarProgress)
                    }
                }
            }
        }
        pairing?.let { PairingOverlay(model, it) }
    }
}

/** Two soft glows behind everything, as the Mac's AuroraBackground. */
@Composable
private fun Aurora() {
    val colors = Dysk.colors
    val strength = if (colors.isDark) 1f else 0.5f
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(colors.violet.copy(alpha = 0.16f * strength), Color.Transparent), center = androidx.compose.ui.geometry.Offset(1300f, -100f), radius = 900f),
        ),
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(colors.mint.copy(alpha = 0.07f * strength), Color.Transparent), center = androidx.compose.ui.geometry.Offset(150f, 1100f), radius = 800f),
        ),
    )
}

@Composable
private fun Sidebar(model: DeskModel, ui: UiState, devices: List<HubPhone>, selectedId: String?) {
    val colors = Dysk.colors
    val problem by model.problem.collectAsState()
    val computersOn by model.computersOn.collectAsState()
    val notifications by model.notifications.collectAsState()
    val phones = devices.filter { !it.isComputer }
    val computers = devices.filter { it.isComputer }

    Column(Modifier.width(272.dp).fillMaxHeight().background(colors.sidebar.copy(alpha = if (colors.isDark) 0.55f else 0.7f)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoMark()
            Spacer(Modifier.width(11.dp))
            Column {
                Text("DyskLink", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(model.deviceName, color = colors.textSecondary, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(Space.lg))
        if (problem != null) StatusPill("Can't accept devices", colors.coral) else StatusPill("Ready to connect", colors.mint)
        Spacer(Modifier.height(Space.lg))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SectionLabel("Devices")
            Spacer(Modifier.height(Space.sm))
            if (phones.isEmpty()) SidebarHint("No phones yet")
            phones.forEach { phone ->
                DeviceRow(phone, phone.id == selectedId, if (phone.isConnected) notifications[phone.id].orEmpty().size else 0) { ui.openDevice(phone.id) }
            }
            if (computersOn || computers.isNotEmpty()) {
                Spacer(Modifier.height(Space.md))
                SectionLabel("Computers")
                Spacer(Modifier.height(Space.sm))
                if (computers.isEmpty()) SidebarHint("Looking on this network…")
                computers.forEach { computer -> DeviceRow(computer, computer.id == selectedId, 0) { ui.openDevice(computer.id) } }
            }
        }
        Spacer(Modifier.height(Space.sm))
        val update by model.update.collectAsState()
        update.available?.let { release ->
            // As on the Mac: a capsule that leads to the update card in Settings.
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(CircleShape).background(colors.mint.copy(alpha = 0.14f))
                    .border(1.dp, colors.mint.copy(alpha = 0.35f), CircleShape).clickable { ui.page = Page.Settings }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DyskIcon(DyskIcons.Download, colors.mint, 13.dp)
                Spacer(Modifier.width(8.dp))
                Text("Update to ${release.version}", Modifier.weight(1f), color = colors.mint, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("View", color = colors.textSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        val tools by model.tools.collectAsState()
        val focus = tools.pomodoro.state
        SidebarButton(DyskIcons.Checklist, "Tools", ui.page == Page.Tools, trailing = if (focus.isRunning) {
            { Box(Modifier.size(8.dp).background(colors.coral, CircleShape)) }
        } else null) { ui.page = Page.Tools }
        Spacer(Modifier.height(4.dp))
        SidebarButton(DyskIcons.History, "File history", ui.page == Page.History) { ui.page = Page.History }
        Spacer(Modifier.height(4.dp))
        SidebarButton(DyskIcons.Sliders, "Settings", ui.page == Page.Settings) { ui.page = Page.Settings }
    }
}

@Composable
private fun SidebarHint(text: String) {
    Text(text, Modifier.padding(start = 2.dp, bottom = Space.xs), color = Dysk.colors.textFaint, fontSize = 12.5.sp)
}

/** A row in the sidebar that is selected the way the Mac selects one: raised, with a faint mint edge. */
@Composable
private fun SelectableRow(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val colors = Dysk.colors
    val shape = RoundedCornerShape(13.dp)
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(shape)
            .background(if (selected) colors.panelRaised else Color.Transparent)
            .border(1.dp, if (selected) colors.mint.copy(alpha = 0.35f) else Color.Transparent, shape)
            .clickable(onClick = onClick).padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun DeviceRow(device: HubPhone, selected: Boolean, alerts: Int, onClick: () -> Unit) {
    val colors = Dysk.colors
    SelectableRow(selected, onClick) {
        IconWell(deviceIcon(device), if (device.isConnected) colors.mint else colors.textFaint)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(device.name, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                val battery = device.info?.batteryPercent
                Text(
                    when {
                        !device.isPaired -> if (device.isComputer) "Not paired" else "Not paired yet"
                        device.isConnected -> "Connected"
                        else -> "Offline"
                    },
                    color = colors.textSecondary, fontSize = 11.sp, maxLines = 1,
                )
                if (battery != null && device.isConnected && device.isPaired) {
                    Text(" · ", color = colors.textFaint, fontSize = 11.sp)
                    Text("$battery%", color = colors.battery(battery), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        if (alerts > 0) AlertBadge(alerts)
    }
}

@Composable
private fun SidebarButton(icon: ImageVector, title: String, selected: Boolean, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val colors = Dysk.colors
    SelectableRow(selected, onClick) {
        IconWell(icon, if (selected) colors.mint else colors.textSecondary)
        Spacer(Modifier.width(11.dp))
        Text(title, Modifier.weight(1f), color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        trailing?.invoke()
    }
}

fun deviceIcon(device: HubPhone): ImageVector = when {
    device.platform == "macos" -> DyskIcons.Laptop
    device.isComputer -> DyskIcons.Monitor
    else -> DyskIcons.Phone
}

/** Nothing paired yet: the radar, and how to get the phone to find this PC. */
@Composable
private fun EmptyState(model: DeskModel, phones: List<HubPhone>, radarProgress: Float?) {
    val colors = Dysk.colors
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RadarView(radarProgress)
        Spacer(Modifier.height(Space.sm))
        Text("Looking for your phone", color = colors.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(Space.xs))
        Text(
            "Open DyskLink on your Android phone, on the same Wi-Fi as this PC.\n${model.deviceName} shows up on the phone by itself. Tap it, then type the code shown here.",
            Modifier.widthIn(max = 520.dp),
            color = colors.textSecondary, fontSize = 13.5.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
        )
        if (phones.any { it.isConnected && !it.isPaired && !it.isComputer }) {
            Spacer(Modifier.height(Space.md))
            Text("A phone is connected. Tap ${model.deviceName} in DyskLink on it to pair.", color = colors.sky, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(Space.lg))
        Text("Not showing up? Add this PC on the phone by its address:", color = colors.textFaint, fontSize = 12.5.sp)
        Spacer(Modifier.height(Space.xs))
        model.addresses.ifEmpty { listOf("No network found") }.forEach { address ->
            Row(
                Modifier.padding(top = 6.dp).background(colors.panel, CircleShape).border(1.dp, colors.stroke, CircleShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Manual connection", color = colors.textFaint, fontSize = 12.5.sp)
                Spacer(Modifier.width(7.dp))
                SelectionContainer {
                    Text(address, color = colors.mint, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                }
            }
        }
        if (Platform.isWindows) {
            Spacer(Modifier.height(Space.md))
            Text("If Windows asks whether DyskLink may use the network, allow private networks.", color = colors.textFaint, fontSize = 12.sp)
        }
    }
}

/** A device that's connected or found but not paired yet. */
@Composable
private fun NewDeviceState(model: DeskModel, device: HubPhone) {
    val colors = Dysk.colors
    Column(
        Modifier.fillMaxSize().padding(30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconWell(deviceIcon(device), colors.mint, 72.dp, colors.mint.copy(alpha = 0.1f))
        Spacer(Modifier.height(Space.md))
        Text(device.name, color = colors.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(device.model, color = colors.textSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(Space.sm))
        Text(
            if (device.isComputer) {
                "Pair to share copied text and pictures, send files, and ring each other.\nNeither computer can control the other."
            } else {
                "This phone is connected but not paired yet.\nOpen DyskLink on it and tap ${model.deviceName} to pair."
            },
            color = colors.textSecondary, fontSize = 13.5.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
        )
        if (device.isComputer) {
            Spacer(Modifier.height(Space.lg))
            PrimaryButton("Pair with ${device.name}", enabled = device.isConnected, icon = DyskIcons.Link) { model.requestPairing(device.id) }
            if (!device.isConnected) {
                Spacer(Modifier.height(Space.xs))
                Text("Turn on DyskLink there, on the same network.", color = colors.textFaint, fontSize = 12.sp)
            }
        }
    }
}
