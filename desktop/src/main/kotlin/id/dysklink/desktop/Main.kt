package id.dysklink.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import id.phonelink.android.core.HubPairing
import id.phonelink.android.core.HubPhone
import id.phonelink.android.core.NotificationItem
import id.phonelink.android.core.Transfer
import id.phonelink.android.core.TransferStatus
import java.awt.FileDialog
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private object Palette {
    val Ink = Color(0xFF090D13)
    val Panel = Color(0xFF121822)
    val PanelRaised = Color(0xFF1A2230)
    val Stroke = Color(0x14FFFFFF)
    val TextPrimary = Color(0xFFF2F5F9)
    val TextSecondary = Color(0xFF98A3B3)
    val TextFaint = Color(0xFF667081)
    val Mint = Color(0xFF5EF2C2)
    val Sky = Color(0xFF4CC9F0)
    val Amber = Color(0xFFFFC35C)
    val Coral = Color(0xFFFF6F7D)
}

fun main(args: Array<String>) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val app = DesktopApp(scope)
    app.start()
    // Some Linux desktops, such as GNOME without an extension, have no tray. There a hidden window could never be
    // opened again, so --hidden is ignored and closing the window quits.
    val hasTray = runCatching { java.awt.SystemTray.isSupported() }.getOrDefault(false)
    // Started at login with --hidden, it waits in the tray.
    val startHidden = "--hidden" in args && hasTray
    application {
        val icon = remember { BitmapPainter(DesktopApp::class.java.getResourceAsStream("/icon.png")!!.use(::loadImageBitmap)) }
        var visible by remember { mutableStateOf(!startHidden) }
        val trayState = rememberTrayState()
        val phones by app.hub.phones.collectAsState()
        val pairing by app.hub.pairing.collectAsState()

        LaunchedEffect(Unit) {
            app.popups.collect { trayState.sendNotification(Notification(it.title, it.message, Notification.Type.None)) }
        }
        // The code has to be seen, so the window comes forward when a phone starts pairing.
        LaunchedEffect(pairing?.phoneId) { if (pairing != null) visible = true }

        if (hasTray) Tray(
            icon = icon,
            state = trayState,
            tooltip = phones.firstOrNull { it.isPaired && it.isConnected }?.let { "DyskLink: ${it.name} connected" } ?: "DyskLink",
            onAction = { visible = true },
            menu = {
                Item("Open DyskLink", onClick = { visible = true })
                Item("Send clipboard to phone", enabled = phones.any { it.isPaired && it.isConnected }, onClick = { app.sendClipboardNow() })
                Separator()
                Item("Quit", onClick = { app.stop(); exitApplication() })
            },
        )

        Window(
            onCloseRequest = {
                if (hasTray) {
                    visible = false
                } else {
                    app.stop()
                    exitApplication()
                }
            },
            visible = visible,
            title = "DyskLink",
            icon = icon,
            state = rememberWindowState(size = DpSize(940.dp, 640.dp)),
        ) {
            MaterialTheme(colorScheme = darkColorScheme(primary = Palette.Mint, background = Palette.Ink, surface = Palette.Panel)) {
                Screen(app, pickFiles = { pickFiles(window) })
            }
        }
    }
}

private fun pickFiles(owner: Frame): List<File> {
    val dialog = FileDialog(owner, "Send to phone", FileDialog.LOAD).apply { isMultipleMode = true; isVisible = true }
    return dialog.files.orEmpty().toList()
}

@Composable
private fun Screen(app: DesktopApp, pickFiles: () -> List<File>) {
    val phones by app.hub.phones.collectAsState()
    val pairing by app.hub.pairing.collectAsState()
    val problem by app.problem.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = phones.firstOrNull { it.id == selectedId } ?: phones.firstOrNull { it.isPaired && !it.isComputer } ?: phones.firstOrNull { it.isPaired }

    Row(Modifier.fillMaxSize().background(Palette.Ink)) {
        Sidebar(app, phones, selected?.id) { selectedId = it }
        Box(Modifier.fillMaxHeight().weight(1f).padding(24.dp)) {
            Column {
                problem?.let { Banner(it, Palette.Coral); Spacer(Modifier.height(12.dp)) }
                val current = pairing
                when {
                    current != null && current.outdated -> OutdatedCard(current, onClose = app.hub::rejectPairing)
                    current != null && current.typing -> TypeCodeCard(current, onSubmit = app.hub::submitCode, onCancel = app.hub::rejectPairing)
                    current != null -> PairingCard(current, onCancel = app.hub::rejectPairing)
                    selected != null && selected.isPaired -> PhonePage(app, selected, pickFiles)
                    selected != null && selected.isComputer -> NewComputer(selected) { app.hub.requestPairing(selected.id) }
                    else -> Welcome(app, phones)
                }
            }
        }
    }
}

@Composable
private fun Sidebar(app: DesktopApp, phones: List<HubPhone>, selectedId: String?, onSelect: (String) -> Unit) {
    val clipboardOn by app.clipboardOn.collectAsState()
    val notifyOn by app.notifyOn.collectAsState()
    Column(Modifier.width(270.dp).fillMaxHeight().background(Palette.Panel).padding(18.dp)) {
        Text("DyskLink", color = Palette.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(Platform.deviceName, color = Palette.TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(22.dp))
        val computersOn by app.computersOn.collectAsState()
        Label("PHONES")
        if (phones.none { !it.isComputer }) Text("None yet", color = Palette.TextFaint, fontSize = 13.sp)
        phones.filter { !it.isComputer }.forEach { phone -> DeviceRow(phone, phone.id == selectedId) { onSelect(phone.id) } }
        if (computersOn || phones.any { it.isComputer }) {
            Spacer(Modifier.height(14.dp))
            Label("COMPUTERS")
            if (phones.none { it.isComputer }) Text("Looking on this network…", color = Palette.TextFaint, fontSize = 13.sp)
            phones.filter { it.isComputer }.forEach { computer -> DeviceRow(computer, computer.id == selectedId) { onSelect(computer.id) } }
        }
        Spacer(Modifier.weight(1f))
        Label("THIS PC")
        Toggle("Share the clipboard", clipboardOn, app::setClipboardOn)
        Toggle("Show phone notifications", notifyOn, app::setNotifyOn)
        val controlOn by app.controlOn.collectAsState()
        Toggle("Phones can control this PC", controlOn, app::setControlOn)
        if (controlOn && app.input.isWayland) Hint("Wayland lets the phone only use the media remote. Log in with an X11 session for the touchpad and keyboard.")
        if (controlOn) app.input.typingNote?.let { Hint(it) }
        Toggle("Sync with other computers", computersOn, app::setComputersOn)
        if (Autostart.isAvailable) {
            var atLogin by remember { mutableStateOf(Autostart.isOn()) }
            Toggle("Start at login", atLogin) { on -> if (Autostart.set(on)) atLogin = on }
        }
        TextButton(onClick = { Platform.open(Platform.downloadsDir.apply { mkdirs() }) }) {
            Text("Open received files", color = Palette.Mint, fontSize = 13.sp)
        }
    }
}

@Composable
private fun DeviceRow(phone: HubPhone, active: Boolean, onClick: () -> Unit) {
    run {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    .background(if (active) Palette.PanelRaised else Color.Transparent, RoundedCornerShape(12.dp))
                    .clickable(onClick = onClick).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(9.dp).background(if (phone.isConnected) Palette.Mint else Palette.TextFaint, CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(phone.name, color = Palette.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            !phone.isPaired -> "Not paired"
                            !phone.isConnected -> "Not connected"
                            else -> phone.info?.batteryPercent?.let { "Battery $it%" } ?: "Connected"
                        },
                        color = Palette.TextSecondary, fontSize = 12.sp,
                    )
                }
            }
        }
}

@Composable
private fun PairingCard(pairing: HubPairing, onCancel: () -> Unit) {
    Card {
        Text("Pair with ${pairing.phoneName}", color = Palette.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Type this code on the phone. It only works for this phone and this PC.", color = Palette.TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(22.dp))
        Text(
            pairing.code.chunked(3).joinToString(" "),
            color = Palette.Mint, fontSize = 54.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
        )
        pairing.error?.let { Spacer(Modifier.height(10.dp)); Text(it, color = Palette.Amber, fontSize = 13.sp) }
        Spacer(Modifier.height(22.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel", color = Palette.TextPrimary) }
    }
}

/** The other device has an older DyskLink, which can't pair any more until it's updated. */
@Composable
private fun OutdatedCard(pairing: HubPairing, onClose: () -> Unit) {
    Card {
        Text("Can't pair with ${pairing.phoneName} yet", color = Palette.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(pairing.error ?: "Update DyskLink on ${pairing.phoneName} to pair.", color = Palette.Amber, fontSize = 14.sp)
        Spacer(Modifier.height(22.dp))
        OutlinedButton(onClick = onClose) { Text("Close", color = Palette.TextPrimary) }
    }
}

/** Pairing with another computer: its code is typed here. */
@Composable
private fun TypeCodeCard(pairing: HubPairing, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var code by remember(pairing.phoneId) { mutableStateOf("") }
    // Each code goes once: typing a seventh digit or pressing Pair again mustn't use up another try.
    var sent by remember(pairing.phoneId) { mutableStateOf<String?>(null) }
    // A wrong code clears the field for the next try.
    LaunchedEffect(pairing.phoneId, pairing.attempts) {
        if (pairing.attempts > 0) { code = ""; sent = null }
    }
    val canSend = code.length == 6 && code != sent && !pairing.localAccepted
    val submit = { if (canSend) { sent = code; onSubmit(code) } }
    Card {
        Text("Pair with ${pairing.phoneName}", color = Palette.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Type the 6-digit code that DyskLink shows on ${pairing.phoneName}.", color = Palette.TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            code,
            { typed ->
                val digits = typed.filter(Char::isDigit).take(6)
                if (digits != code) {
                    code = digits
                    if (digits.length == 6 && digits != sent && !pairing.localAccepted) { sent = digits; onSubmit(digits) }
                }
            },
            singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 28.sp, fontFamily = FontFamily.Monospace, color = Palette.TextPrimary),
            modifier = Modifier.width(220.dp),
        )
        pairing.error?.let { Spacer(Modifier.height(10.dp)); Text(it, color = Palette.Amber, fontSize = 13.sp) }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = submit, enabled = canSend) { Text("Pair") }
            OutlinedButton(onClick = onCancel) { Text("Cancel", color = Palette.TextPrimary) }
        }
    }
}

/** A Mac or PC found on the network that isn't paired yet. */
@Composable
private fun NewComputer(computer: HubPhone, onPair: () -> Unit) {
    Card {
        Text(computer.name, color = Palette.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(computer.model, color = Palette.TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "Pair to share copied text and pictures, send files, and ring each other. Neither computer can control the other.",
            color = Palette.TextSecondary, fontSize = 13.sp,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onPair, enabled = computer.isConnected) { Text("Pair with ${computer.name}") }
    }
}

@Composable
private fun Welcome(app: DesktopApp, phones: List<HubPhone>) {
    Card {
        Text("Connect your phone", color = Palette.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Steps(
            "Install DyskLink on your Android phone and keep it on the same Wi-Fi as this PC.",
            "Open DyskLink on the phone. ${Platform.deviceName} appears there and asks to pair.",
            "Type the 6-digit code shown here on the phone.",
        )
        if (phones.any { it.isConnected && !it.isPaired }) {
            Spacer(Modifier.height(12.dp))
            Text("A phone is connected. Open DyskLink on it and tap ${Platform.deviceName} to pair.", color = Palette.Sky, fontSize = 13.sp)
        }
        Spacer(Modifier.height(18.dp))
        Text("Not showing up? Add this PC on the phone by its address:", color = Palette.TextSecondary, fontSize = 13.sp)
        app.addresses.ifEmpty { listOf("No network found") }.forEach {
            Text(it, color = Palette.TextPrimary, fontSize = 15.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "If Windows asks whether DyskLink may use the network, allow private networks.",
            color = Palette.TextFaint, fontSize = 12.sp,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PhonePage(app: DesktopApp, phone: HubPhone, pickFiles: () -> List<File>) {
    val ringing by app.hub.ringing.collectAsState()
    val ringingHere by app.ringingHere.collectAsState()
    val notifications by app.hub.notifications.collectAsState()
    val transfers by app.hub.fileTransfers.transfers.collectAsState()
    var dropping by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val send = { files: List<File> -> files.filter { it.isFile }.forEach { app.hub.sendFile(phone.id, it) } }
    val dropTarget = remember(phone.id) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dropping = true }
            override fun onExited(event: DragAndDropEvent) { dropping = false }
            override fun onEnded(event: DragAndDropEvent) { dropping = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dropping = false
                val transferable = event.awtTransferable
                if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                @Suppress("UNCHECKED_CAST")
                send(transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>)
                return true
            }
        }
    }

    Column(
        Modifier.fillMaxSize()
            .dragAndDropTarget(shouldStartDragAndDrop = { phone.isConnected }, target = dropTarget)
            .border(2.dp, if (dropping) Palette.Mint else Color.Transparent, RoundedCornerShape(18.dp)),
    ) {
        if (ringingHere) {
            Banner("A device is looking for this PC.", Palette.Amber, action = "Stop" to app::stopRinging)
            Spacer(Modifier.height(12.dp))
        }
        val soundFrom by app.soundFrom.collectAsState()
        if (soundFrom == phone.id) {
            Banner("${phone.name}'s sound is playing on this PC.", Palette.Mint, action = "Stop" to app::stopPhoneSound)
            Spacer(Modifier.height(12.dp))
        }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(phone.name, color = Palette.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(phone.info?.model ?: phone.model, color = Palette.TextSecondary, fontSize = 13.sp)
                }
                val battery = phone.info?.batteryPercent
                Text(
                    when {
                        !phone.isConnected -> "Not connected"
                        battery != null -> "$battery%${if (phone.info?.isCharging == true) " ⚡" else ""}"
                        else -> "Connected"
                    },
                    color = if (phone.isConnected) Palette.Mint else Palette.TextFaint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Action("Send files", Palette.Mint, phone.isConnected) { send(pickFiles()) }
                Action("Send clipboard", Palette.Sky, phone.isConnected) {
                    note = if (app.sendClipboardNow(phone.id)) "Sent" else "Copy some text first"
                }
                val isRinging = phone.id in ringing
                Action(if (isRinging) "Stop ringing" else if (phone.isComputer) "Ring it" else "Find phone", if (isRinging) Palette.Coral else Palette.Amber, phone.isConnected) {
                    app.hub.ring(phone.id, !isRinging)
                }
            }
            note?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Palette.TextSecondary, fontSize = 12.sp) }
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    phone.isConnected -> "Drop files here to send them to ${phone.name}."
                    phone.isComputer -> "Turn on DyskLink there, on the same network, and it connects by itself."
                    else -> "Open DyskLink on the phone, on the same Wi-Fi, and it connects by itself."
                },
                color = Palette.TextFaint, fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!phone.isComputer) Column(Modifier.weight(1f).fillMaxHeight()) {
                Label("NOTIFICATIONS")
                val list = notifications[phone.id].orEmpty()
                if ("notifications" !in phone.features) {
                    Hint("Turn on Notifications in DyskLink on the phone, under Phone data for Mac.")
                } else if (list.isEmpty()) {
                    Hint("No notifications on the phone.")
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.key }) { NotificationRow(it) { app.hub.dismissNotification(phone.id, it.key) } }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Label("FILES")
                val list = transfers.filter { it.peerId == phone.id }.take(30)
                if (list.isEmpty()) Hint("Files sent from ${phone.name} go to ${Platform.downloadsDir.path}.")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.id }) { TransferRow(it) }
                }
            }
        }
        Row {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { app.hub.unpair(phone.id) }) { Text("Unpair ${phone.name}", color = Palette.Coral, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun NotificationRow(item: NotificationItem, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Palette.Panel, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                "${item.app} · ${SimpleDateFormat("HH:mm").format(Date(item.time))}",
                color = Palette.TextFaint, fontSize = 11.sp,
            )
            item.title?.let { Text(it, color = Palette.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            item.text?.let { Text(it, color = Palette.TextSecondary, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
        TextButton(onClick = onDismiss) { Text("✕", color = Palette.TextFaint) }
    }
}

@Composable
private fun TransferRow(transfer: Transfer) {
    val file = transfer.uri?.let(::File)
    Row(
        Modifier.fillMaxWidth().background(Palette.Panel, RoundedCornerShape(12.dp))
            .clickable(enabled = file?.exists() == true) { file?.let(Platform::open) }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (transfer.outgoing) "↑" else "↓", color = Palette.Mint, fontSize = 16.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(transfer.name, color = Palette.TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when (transfer.status) {
                    TransferStatus.Active -> "${percent(transfer)}%"
                    TransferStatus.Done -> if (transfer.outgoing) "Sent" else "Received · click to open"
                    TransferStatus.Failed -> transfer.detail ?: "Failed"
                },
                color = if (transfer.status == TransferStatus.Failed) Palette.Coral else Palette.TextSecondary, fontSize = 11.sp,
            )
        }
    }
}

private fun percent(transfer: Transfer) = if (transfer.size > 0) (transfer.transferred * 100 / transfer.size).toInt() else 0

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Palette.Panel, RoundedCornerShape(18.dp))
            .border(1.dp, Palette.Stroke, RoundedCornerShape(18.dp)).padding(20.dp),
        content = content,
    )
}

@Composable
private fun Banner(text: String, color: Color, action: Pair<String, () -> Unit>? = null) {
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.14f), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = color, fontSize = 13.sp, modifier = Modifier.weight(1f))
        action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label, color = color, fontWeight = FontWeight.SemiBold) } }
    }
}

@Composable
private fun Action(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.16f), contentColor = color, disabledContainerColor = Palette.PanelRaised),
        shape = RoundedCornerShape(12.dp),
    ) { Text(label, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Mint, checkedThumbColor = Palette.Ink))
    }
}

@Composable
private fun Label(text: String) {
    Text(text, color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, color = Palette.TextFaint, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun Steps(vararg steps: String) {
    steps.forEachIndexed { index, step ->
        Row(Modifier.padding(vertical = 4.dp)) {
            Text("${index + 1}.", color = Palette.Mint, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
            Text(step, color = Palette.TextPrimary, fontSize = 14.sp)
        }
    }
}
