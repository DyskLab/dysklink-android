package id.dysklink.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.phonelink.android.core.HubPhone
import id.phonelink.android.core.NotificationItem
import id.phonelink.android.core.Transfer
import id.phonelink.android.core.TransferStatus
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/** What a tab gets to draw with. */
class TabContext(val model: DeskModel, val ui: UiState, val device: HubPhone, val pickFiles: () -> List<File>) {
    fun sendPicked() = model.sendFiles(device.id, pickFiles())
}

/**
 * A tab on a device's page. Adding one, such as Screen, Camera, Gallery, Messages or Commands, is one more entry
 * in [DeviceTabs]: [shows] says for which devices, [badge] puts a count on the pill.
 */
class DeviceTab(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val shows: (HubPhone) -> Boolean = { true },
    val badge: @Composable (TabContext) -> Int? = { null },
    val content: @Composable ColumnScope.(TabContext) -> Unit,
)

val DeviceTabs: List<DeviceTab> = listOf(
    DeviceTab("overview", "Overview", DyskIcons.Grid) { OverviewTab(it) },
    DeviceTab(
        "notifications", "Notifications", DyskIcons.Bell, shows = { !it.isComputer },
        badge = { context -> context.model.notifications.collectAsState().value[context.device.id].orEmpty().size },
    ) { NotificationsTab(it) },
    DeviceTab("screen", "Screen", DyskIcons.Monitor, shows = { !it.isComputer }) { ScreenTab(it) },
    DeviceTab("camera", "Camera", DyskIcons.Camera, shows = { !it.isComputer }) { CameraTab(it) },
    DeviceTab("gallery", "Gallery", DyskIcons.Image, shows = { !it.isComputer }) { GalleryTab(it) },
    DeviceTab("messages", "Messages", DyskIcons.Message, shows = { !it.isComputer }) { MessagesTab(it) },
    DeviceTab("contacts", "Contacts", DyskIcons.Users, shows = { !it.isComputer }) { ContactsTab(it) },
    DeviceTab("files", "Files", DyskIcons.Upload) { FilesTab(it) },
    DeviceTab("phonefiles", "Phone files", DyskIcons.Folder, shows = { !it.isComputer }) { PhoneFilesTab(it) },
    DeviceTab("sound", "Sound", DyskIcons.Speaker, shows = { !it.isComputer }) { SoundTab(it) },
    DeviceTab("control", "Control", DyskIcons.Mouse, shows = { !it.isComputer }) { ControlTab(it) },
    DeviceTab(
        "commands", "Commands", DyskIcons.Terminal, shows = { !it.isComputer },
        badge = { context -> context.model.terminals.collectAsState().value[context.device.id]?.takeIf { it > 0 } },
    ) { CommandsTab(it) },
)

/** About what a tab pill with its title takes. */
private val TAB_WIDTH = 108.dp

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun DevicePage(model: DeskModel, ui: UiState, device: HubPhone, pickFiles: () -> List<File>) {
    val colors = Dysk.colors
    val context = TabContext(model, ui, device, pickFiles)
    val tabs = DeviceTabs.filter { it.shows(device) }
    val tab = tabs.firstOrNull { it.id == ui.tab } ?: tabs.first()
    var dropping by remember { mutableStateOf(false) }
    val dropTarget = remember(device.id) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dropping = true }
            override fun onExited(event: DragAndDropEvent) { dropping = false }
            override fun onEnded(event: DragAndDropEvent) { dropping = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dropping = false
                val transferable = event.awtTransferable
                if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                @Suppress("UNCHECKED_CAST")
                model.sendFiles(device.id, transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
                return true
            }
        }
    }
    Box(
        Modifier.fillMaxSize()
            .dragAndDropTarget(shouldStartDragAndDrop = { device.isConnected }, target = dropTarget),
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = Space.lg)) {
            Alerts(model, device)
            HeaderCard(context, full = tab.id == "overview")
            Spacer(Modifier.height(14.dp))
            // With many tabs on a narrow window, only the chosen one keeps its title.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val titles = maxWidth >= TAB_WIDTH * tabs.size
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tabs.forEach { item ->
                        TabPill(item.title, item.id == tab.id, item.icon, item.badge(context), showTitle = titles) { ui.tab = item.id }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) { tab.content(this, context) }
        }
        if (dropping) {
            Box(
                Modifier.fillMaxSize().padding(Space.sm).background(colors.mint.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                    .border(2.dp, colors.mint, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("Drop to send to ${device.name}", color = colors.mint, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Something happening now: this PC ringing, or the phone's sound playing here. */
@Composable
private fun Alerts(model: DeskModel, device: HubPhone) {
    val colors = Dysk.colors
    val ringingHere by model.ringingHere.collectAsState()
    val soundFrom by model.soundFrom.collectAsState()
    if (ringingHere) {
        Banner("A device is looking for this PC.", colors.amber, DyskIcons.Speaker, "Stop" to model::stopRinging)
        Spacer(Modifier.height(Space.sm))
    }
    if (soundFrom == device.id) {
        Banner("${device.name}'s sound is playing on this PC.", colors.mint, DyskIcons.Speaker, "Stop" to model::stopPhoneSound)
        Spacer(Modifier.height(Space.sm))
    }
    ServiceAlerts(model, device)
}

@Composable
private fun HeaderCard(context: TabContext, full: Boolean) {
    val colors = Dysk.colors
    val device = context.device
    val model = context.model
    val ringing by model.ringing.collectAsState()
    var note by remember(device.id) { mutableStateOf<String?>(null) }
    val battery = device.info?.batteryPercent
    GlassCard(padding = if (full) 22.dp else 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (battery != null || !device.isComputer) {
                BatteryRing(battery, device.info?.isCharging == true, if (full) 112.dp else 76.dp)
            } else {
                IconWell(deviceIcon(device), colors.mint, if (full) 96.dp else 64.dp, colors.mint.copy(alpha = 0.1f))
            }
            Spacer(Modifier.width(if (full) 22.dp else Space.md))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (device.isConnected) StatusPill("Connected", colors.mint) else StatusPill("Offline", colors.textFaint)
                Title(device.name, if (full) 26.sp else 20.sp)
                val details = listOfNotNull(device.info?.model ?: device.model, device.info?.osVersion?.takeIf { it != device.info?.model }).distinct()
                Text(details.joinToString(" · "), color = colors.textSecondary, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (full) {
            Spacer(Modifier.height(18.dp))
            if (device.isConnected) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickTile(DyskIcons.Upload, "Send files") { context.sendPicked() }
                    val isRinging = device.id in ringing
                    QuickTile(
                        if (isRinging) DyskIcons.SpeakerOff else DyskIcons.Waves,
                        when {
                            isRinging -> "Stop ringing"
                            device.isComputer -> "Ring it"
                            else -> "Find phone"
                        },
                        tint = if (isRinging) colors.amber else colors.mint,
                    ) { model.ring(device.id, !isRinging) }
                    QuickTile(DyskIcons.Clipboard, "Send clipboard", tint = colors.sky) {
                        note = if (model.sendClipboard(device.id)) "Clipboard sent to ${device.name}." else "Copy some text first."
                    }
                }
                note?.let { Spacer(Modifier.height(Space.xs)); Text(it, color = colors.textSecondary, fontSize = 12.sp) }
                Spacer(Modifier.height(10.dp))
                Text("Drop files anywhere on this page to send them to ${device.name}.", color = colors.textFaint, fontSize = 12.sp)
            } else {
                Text(
                    if (device.isComputer) "Turn on DyskLink there, on the same network, and it connects by itself."
                    else "Open DyskLink on the phone, on the same Wi-Fi, and it connects by itself.",
                    color = colors.textFaint, fontSize = 12.5.sp,
                )
            }
        }
    }
}

// Overview

@Composable
private fun ColumnScope.OverviewTab(context: TabContext) {
    val colors = Dysk.colors
    val device = context.device
    val info = device.info
    SectionLabel("At a glance")
    val tiles = if (device.isComputer) {
        listOf(
            Triple(DyskIcons.Monitor, "System", info?.osVersion ?: device.model),
            Triple(DyskIcons.Link, "Connection", if (device.isConnected) "On this network" else "Offline"),
        )
    } else {
        listOf(
            Triple(DyskIcons.Waves, "Network", info?.network?.let { network -> info.wifiLevel?.let { "$network, signal $it/4" } ?: network } ?: "Unknown"),
            Triple(DyskIcons.Phone, "Cellular", listOfNotNull(info?.carrier, info?.cellType, info?.signalLevel?.let { "signal $it/4" }).joinToString(", ").ifEmpty { "Unknown" }),
            Triple(DyskIcons.Folder, "Storage", storageText(info?.storageFreeBytes, info?.storageTotalBytes)),
        )
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.forEach { (icon, title, value) ->
            StatTile(icon, title, value, Modifier.weight(1f).fillMaxHeight(), if (title == "Storage") info?.let { storageUsed(it.storageFreeBytes, it.storageTotalBytes) } else null)
        }
    }
    if (!device.isComputer) {
        val notifications by context.model.notifications.collectAsState()
        val list = notifications[device.id].orEmpty()
        if (list.isNotEmpty()) {
            SectionLabel("Latest notifications")
            list.take(2).forEach { NotificationCard(it, device.isConnected) { context.model.dismissNotification(device.id, it.key) } }
            if (list.size > 2) TextAction("Show all ${list.size}", colors.mint) { context.ui.tab = "notifications" }
        }
    }
    SectionLabel("On this PC")
    GlassCard {
        val clipboardOn by context.model.clipboardOn.collectAsState()
        SettingRow(
            "Automatic clipboard", "Text and pictures copied on this PC go straight to your devices, and theirs come here.",
            clipboardOn, onChange = context.model::setClipboardOn,
        )
        if (!device.isComputer) {
            val notifyOn by context.model.notifyOn.collectAsState()
            Divider()
            SettingRow("Show phone notifications", "Notifications from the phone pop up on this PC.", notifyOn, onChange = context.model::setNotifyOn)
        }
        // A Mac's mouse and keyboard can come over to this PC; a PC's never go to a Mac.
        if (device.platform == "macos") {
            val remoteOn by context.model.remoteOn.collectAsState()
            val computersOn by context.model.computersOn.collectAsState()
            Divider()
            SettingRow(
                "Let paired computers use their mouse and keyboard here",
                "On ${device.name}, choose this PC's side in Advanced, Other computers, Mouse and keyboard. This PC's own mouse doesn't go to the Mac.",
                remoteOn && computersOn, enabled = computersOn, onChange = context.model::setRemoteOn,
            )
        }
    }
    Row(Modifier.padding(top = 4.dp)) {
        TextAction("Unpair ${device.name}", colors.coral) { context.model.unpair(device.id) }
    }
}

private fun storageText(free: Long?, total: Long?): String =
    if (free == null || total == null) "No data yet" else "${bytes(free)} free of ${bytes(total)}"

private fun storageUsed(free: Long?, total: Long?): Float? =
    if (free == null || total == null || total <= 0) null else ((total - free).toFloat() / total).coerceIn(0f, 1f)

fun bytes(value: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var size = value.toDouble()
    var unit = 0
    while (size >= 1000 && unit < units.lastIndex) { size /= 1000; unit++ }
    return if (unit == 0 || size >= 10) "${size.toLong()} ${units[unit]}" else "%.1f %s".format(size, units[unit])
}

@Composable
private fun StatTile(icon: ImageVector, title: String, value: String, modifier: Modifier, bar: Float? = null) {
    val colors = Dysk.colors
    GlassCard(modifier, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DyskIcon(icon, colors.mint, 12.dp)
            Spacer(Modifier.width(6.dp))
            Text(title, color = colors.textSecondary, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(7.dp))
        Text(value, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (bar != null) {
            Spacer(Modifier.height(Space.xs))
            Box(Modifier.fillMaxWidth().height(7.dp).background(colors.iconWell.copy(alpha = 0.12f), CircleShape)) {
                Box(Modifier.fillMaxWidth(bar.coerceAtLeast(0.04f)).height(7.dp).background(colors.accentGradient, CircleShape))
            }
        }
    }
}

// Notifications

@Composable
private fun ColumnScope.NotificationsTab(context: TabContext) {
    val device = context.device
    val notifications by context.model.notifications.collectAsState()
    val list = notifications[device.id].orEmpty()
    when {
        "notifications" !in device.features -> FeatureOffCard("Notifications")
        list.isEmpty() -> EmptyCard("No notifications on the phone.", DyskIcons.Bell)
        else -> list.forEach { item -> NotificationCard(item, device.isConnected) { context.model.dismissNotification(device.id, item.key) } }
    }
}

/** Shown when the feature is off on the phone. This PC can't turn it on by itself. */
@Composable
internal fun FeatureOffCard(title: String) {
    val colors = Dysk.colors
    GlassCard(padding = 24.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DyskIcon(DyskIcons.Info, colors.amber, 16.dp)
            Spacer(Modifier.width(10.dp))
            Text("$title: turned off on the phone", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(Space.xs))
        Body("Open DyskLink on the phone, choose this PC, then turn it on under Phone data for Mac. It can only be turned on from the phone.")
    }
}

@Composable
private fun NotificationCard(item: NotificationItem, canDismiss: Boolean, onDismiss: () -> Unit) {
    val colors = Dysk.colors
    GlassCard {
        Row(verticalAlignment = Alignment.Top) {
            AppAvatar(item.app, item.color)
            Spacer(Modifier.width(Space.sm))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(item.app, color = colors.mint, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text(SimpleDateFormat("HH:mm").format(Date(item.time)), color = colors.textFaint, fontSize = 11.sp)
                }
                item.title?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(it, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                item.text?.let {
                    Spacer(Modifier.height(2.dp))
                    SelectionContainer { Text(it, color = colors.textSecondary, fontSize = 12.5.sp, lineHeight = 17.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                }
            }
            if (canDismiss) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape).background(colors.panelRaised).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { DyskIcon(DyskIcons.Close, colors.textSecondary, 11.dp) }
            }
        }
    }
}

/** The app's first letter on its own color, so the same app keeps the same color. */
@Composable
private fun AppAvatar(name: String, color: Int?) {
    val colors = Dysk.colors
    val palette = listOf(colors.violet, colors.mint, colors.amber, colors.coral, colors.sky)
    val tint = color?.let { Color(0xFF000000 or it.toLong()) } ?: palette[Math.floorMod(name.hashCode(), palette.size)]
    Box(Modifier.size(38.dp).background(tint.copy(alpha = 0.85f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

// Files

@Composable
private fun ColumnScope.FilesTab(context: TabContext) {
    val colors = Dysk.colors
    val device = context.device
    val transfers by context.model.transfers.collectAsState()
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = Space.sm)) {
                Text("Send files", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Body("Choose files, or drag them onto this window. Files from ${device.name} go to ${context.model.downloadsPath}.")
            }
            PrimaryButton("Choose files", enabled = device.isConnected) { context.sendPicked() }
        }
    }
    val list = transfers.filter { it.peerId == device.id }.take(50)
    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("Recent", Modifier.weight(1f))
        TextAction("Open received files", colors.mint, context.model::openDownloads)
    }
    if (list.isEmpty()) EmptyCard("Nothing sent or received with ${device.name} yet.", DyskIcons.Folder)
    list.forEach { TransferRow(it, null, context.model) }
}

/** One file going to or coming from a device. [deviceName] is shown in the history, where devices mix. */
@Composable
fun TransferRow(transfer: Transfer, deviceName: String?, model: DeskModel) {
    val colors = Dysk.colors
    val file = transfer.uri?.let(::File)
    val canOpen = !transfer.outgoing && transfer.status == TransferStatus.Done && file?.exists() == true
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(colors.panel.copy(alpha = if (colors.isDark) 0.86f else 1f))
            .border(1.dp, colors.stroke, shape).clickable(enabled = canOpen) { file?.let(model::open) }
            .padding(horizontal = 14.dp, vertical = Space.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val tint = if (transfer.status == TransferStatus.Failed) colors.coral else colors.mint
            Box(Modifier.size(22.dp).background(tint.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                DyskIcon(if (transfer.outgoing) DyskIcons.ArrowUp else DyskIcons.ArrowDown, tint, 12.dp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(transfer.name, color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val parts = listOfNotNull(
                    deviceName?.let { (if (transfer.outgoing) "To " else "From ") + it },
                    transfer.size.takeIf { it > 0 }?.let(::bytes),
                    transfer.time.takeIf { it > 0 }?.let { SimpleDateFormat("d MMM, HH:mm").format(Date(it)) },
                )
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = colors.textFaint, fontSize = 11.sp, maxLines = 1)
            }
            Spacer(Modifier.width(Space.xs))
            Text(
                when (transfer.status) {
                    TransferStatus.Active -> "${percent(transfer)}%"
                    TransferStatus.Done -> if (transfer.outgoing) "Sent" else if (canOpen) "Open" else "Received"
                    TransferStatus.Failed -> transfer.detail ?: "Failed"
                },
                color = when (transfer.status) {
                    TransferStatus.Failed -> colors.coral
                    TransferStatus.Done -> colors.mint
                    TransferStatus.Active -> colors.textSecondary
                },
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
        }
        if (transfer.status == TransferStatus.Active) {
            Spacer(Modifier.height(Space.xs))
            Box(Modifier.fillMaxWidth().height(5.dp).background(colors.iconWell.copy(alpha = 0.12f), CircleShape)) {
                Box(Modifier.fillMaxWidth((percent(transfer) / 100f).coerceAtLeast(0.02f)).height(5.dp).background(colors.accentGradient, CircleShape))
            }
        }
    }
}

private fun percent(transfer: Transfer) = if (transfer.size > 0) (transfer.transferred * 100 / transfer.size).toInt() else 0

// Sound

@Composable
private fun ColumnScope.SoundTab(context: TabContext) {
    val colors = Dysk.colors
    val device = context.device
    val soundFrom by context.model.soundFrom.collectAsState()
    val playing = soundFrom == device.id
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(DyskIcons.Speaker, if (playing) colors.mint else colors.textSecondary, 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(end = Space.sm)) {
                Text("Phone sound on this PC", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Body(
                    if (playing) "Music, videos and calls from ${device.name} play through this PC's speakers."
                    else "To hear ${device.name} here, open Sound in DyskLink on the phone and tap Play on the PC.",
                )
            }
            if (playing) SecondaryButton("Stop", colors.coral, onClick = context.model::stopPhoneSound)
            else StatusPill("Not playing", colors.textFaint)
        }
    }
    PcSoundCard(context)
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(DyskIcons.Waves, colors.amber, 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(end = Space.sm)) {
                Text("Find ${device.name}", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Body("Plays a sound on the phone so you can find it.")
            }
            val ringing by context.model.ringing.collectAsState()
            val isRinging = device.id in ringing
            SecondaryButton(if (isRinging) "Stop ringing" else "Ring", if (isRinging) colors.amber else colors.textPrimary, enabled = device.isConnected) {
                context.model.ring(device.id, !isRinging)
            }
        }
    }
}

// Control

@Composable
private fun ColumnScope.ControlTab(context: TabContext) {
    val colors = Dysk.colors
    val controlOn by context.model.controlOn.collectAsState()
    GlassCard {
        SettingRow(
            "Phones can control this PC",
            "Paired phones can use this PC's touchpad, keyboard, shortcuts and media keys. Open Touchpad & keyboard in DyskLink on the phone.",
            controlOn, onChange = context.model::setControlOn,
        )
        if (controlOn) context.model.controlNotes().forEach { note ->
            Spacer(Modifier.height(Space.sm))
            Banner(note, colors.amber, DyskIcons.Info)
        }
    }
    SectionLabel("What the phone can do")
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(DyskIcons.Mouse, "Touchpad", "Move, click and scroll", Modifier.weight(1f).fillMaxHeight())
        StatTile(DyskIcons.Grid, "Keyboard", "Type in any language", Modifier.weight(1f).fillMaxHeight())
        StatTile(DyskIcons.Speaker, "Media", "Play, pause, volume", Modifier.weight(1f).fillMaxHeight())
    }
}
