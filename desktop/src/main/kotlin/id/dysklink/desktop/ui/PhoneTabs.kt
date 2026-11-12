package id.dysklink.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.phonelink.android.core.ControlEvent
import id.phonelink.android.core.GalleryItem
import id.phonelink.android.core.HubPhone
import id.phonelink.android.core.SmsItem
import id.phonelink.android.core.SmsOutgoing
import java.text.SimpleDateFormat
import java.util.Date

// The phone's screen, camera, gallery, and SMS on this PC.

/** Shown for a phone whose DyskLink is too old to serve this to a PC. */
@Composable
private fun UpdateCard(what: String, device: HubPhone) {
    val colors = Dysk.colors
    GlassCard(padding = 24.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DyskIcon(DyskIcons.Info, colors.amber, 16.dp)
            Spacer(Modifier.width(10.dp))
            Text("$what needs a newer DyskLink on ${device.name}", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(Space.xs))
        Body("Update DyskLink on the phone, then open it once so it connects again.")
    }
}

/** Nothing can be asked of a phone that isn't connected. */
@Composable
private fun OfflineCard(device: HubPhone) = EmptyCard("${device.name} isn't connected. Open DyskLink on the phone, on the same Wi-Fi.", DyskIcons.Link)

/** Returns true when the tab can go on: connected, new enough, and (for [feature]) turned on at the phone. */
@Composable
internal fun ready(device: HubPhone, what: String, serves: Boolean, feature: String?): Boolean {
    when {
        !device.isConnected -> OfflineCard(device)
        !serves -> UpdateCard(what, device)
        feature != null && feature !in device.features -> FeatureOffCard(what)
        else -> return true
    }
    return false
}

/** A strip under a title: what this is, and what to do. */
@Composable
private fun StartCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, button: String, onClick: () -> Unit) {
    val colors = Dysk.colors
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconWell(icon, colors.mint, 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).padding(end = Space.sm)) {
                Text(title, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Body(detail)
            }
            PrimaryButton(button, onClick = onClick)
        }
    }
}

// Screen

@Composable
fun ColumnScope.ScreenTab(context: TabContext) {
    val colors = Dysk.colors
    val device = context.device
    val model = context.model
    val ui = context.ui
    // Leaving the tab ends the sharing, unless it moved to its own window.
    DisposableEffect(device.id) {
        onDispose { if (ui.screenWindow != device.id) model.stopScreen(device.id) }
    }
    if (!ready(device, "Screen sharing", device.servesScreen, "screen")) return
    val screens by model.screens.collectAsState()
    val video = screens[device.id]
    when {
        video == null -> StartCard(
            DyskIcons.Monitor, "Show the phone's screen",
            "${device.name} asks first, and Android asks again each time before anything is shared. Click to tap, drag to swipe, right-click for Back, and type to write on the phone.",
            "Show phone screen",
        ) { model.requestScreen(device.id) }
        video.error != null -> {
            Banner(video.error!!, colors.coral, DyskIcons.Warning, "Try again" to { model.requestScreen(device.id) })
        }
        video.waiting -> GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconWell(DyskIcons.Phone, colors.amber, 40.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f).padding(end = Space.sm)) {
                    Text("Waiting for ${device.name}", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Body("Tap the notification on the phone, then Start now in Android's dialog.")
                }
                SecondaryButton("Cancel") { model.stopScreen(device.id) }
            }
        }
        ui.screenWindow == device.id -> GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Body("The screen shows in its own window.", modifier = Modifier.weight(1f))
                SecondaryButton("Show here") { ui.screenWindow = null }
                Spacer(Modifier.width(Space.xs))
                SecondaryButton("Stop", colors.coral) { ui.screenWindow = null; model.stopScreen(device.id) }
            }
        }
        else -> {
            if ("control" !in device.features) {
                Banner("Taps and typing are off on the phone. Turn on Control under Phone data on ${device.name}, and DyskLink's Accessibility service.", colors.amber)
            }
            val picture = rememberFrame(video)
            val shape = RoundedCornerShape(18.dp)
            val turned = ui.screenRotation % 180 == 90
            val w = (if (turned) video.height else video.width).coerceAtLeast(1)
            val h = (if (turned) video.width else video.height).coerceAtLeast(1)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                VideoSurface(
                    picture, ui.screenRotation,
                    Modifier.weight(1.4f, fill = false).height(560.dp).aspectRatio(w.toFloat() / h, matchHeightConstraintsFirst = true)
                        .clip(shape).border(1.dp, colors.stroke, shape),
                    onControl = { model.control(device.id, it) },
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    StatusPill("Live from ${device.name}", colors.mint)
                    Body("Click to tap, drag to swipe, scroll to scroll, right-click for Back. Click the picture once, then type: letters, Backspace, Enter and the arrow keys go to the phone. Esc is Back.")
                    PhoneKeys(model, ui, device.id)
                    TypeOnPhone { text -> model.control(device.id, ControlEvent("text", text = text)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        SecondaryButton("Pop out", icon = DyskIcons.PopOut) { ui.screenWindow = device.id }
                        SecondaryButton("Stop", colors.coral) { model.stopScreen(device.id) }
                    }
                }
            }
        }
    }
}

/** For text the keyboard can't send key by key, such as an IME or a paste. */
@Composable
private fun TypeOnPhone(send: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        InputField(text, { text = it }, "Text for the phone", Modifier.weight(1f)) {
            if (text.isNotEmpty()) { send(text); text = "" }
        }
        Spacer(Modifier.width(Space.xs))
        SecondaryButton("Send", icon = DyskIcons.Send, enabled = text.isNotEmpty()) { send(text); text = "" }
    }
}

// Camera

@Composable
fun ColumnScope.CameraTab(context: TabContext) {
    val colors = Dysk.colors
    val device = context.device
    val model = context.model
    val ui = context.ui
    if (!ready(device, "The phone camera", device.servesCamera, null)) return
    val cameras by model.cameras.collectAsState()
    val video = cameras[device.id]
    when {
        video == null -> StartCard(
            DyskIcons.Camera, "Use the phone as a camera",
            "${device.name} shows a notification, and the camera starts when you tap it. The picture opens in a window you can capture in OBS or a video call app.",
            "Use phone camera",
        ) { ui.cameraWindow = device.id; model.requestCamera(device.id) }
        video.error != null -> Banner(video.error!!, colors.coral, DyskIcons.Warning, "Try again" to { ui.cameraWindow = device.id; model.requestCamera(device.id) })
        video.waiting -> GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconWell(DyskIcons.Phone, colors.amber, 40.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f).padding(end = Space.sm)) {
                    Text("Tap the notification on ${device.name}", color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Body("Android only lets the camera start after a tap on the phone.")
                }
                SecondaryButton("Cancel") { ui.cameraWindow = null; model.stopCamera(device.id) }
            }
        }
        else -> {
            val picture = rememberFrame(video)
            val shape = RoundedCornerShape(18.dp)
            VideoSurface(
                picture, ((video.rotation) + ui.camera.rotation) % 360,
                Modifier.fillMaxWidth().height(360.dp).clip(shape).border(1.dp, colors.stroke, shape),
                mirror = ui.camera.mirror,
            )
            CameraControls(model, ui, device.id, video) {
                if (ui.cameraWindow != device.id) SecondaryButton("Open window", icon = DyskIcons.PopOut) { ui.cameraWindow = device.id }
                SecondaryButton("Stop", colors.coral) { ui.cameraWindow = null; model.stopCamera(device.id) }
            }
            Body(
                listOfNotNull(
                    "${video.width} × ${video.height}".takeIf { video.width > 0 },
                    if (video.lens == "front") "front lens" else "back lens",
                    if (ui.cameraWindow == device.id) "showing in its own window" else null,
                ).joinToString(" · "),
                colors.textFaint,
            )
        }
    }
}

// Gallery

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColumnScope.GalleryTab(context: TabContext) {
    val colors = Dysk.colors
    val device = context.device
    val model = context.model
    if (!ready(device, "The gallery", device.servesGallery, "gallery")) return
    val galleries by model.galleries.collectAsState()
    val gallery = galleries[device.id]
    LaunchedEffect(device.id) { if (gallery == null) model.loadGallery(device.id, true) }
    var selected by remember(device.id) { mutableStateOf(setOf<Long>()) }
    val items = gallery?.items.orEmpty()
    GlassCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = Space.sm)) {
                Text(
                    when {
                        selected.isNotEmpty() -> "${selected.size} selected"
                        items.isEmpty() -> "Photos and videos"
                        else -> "${items.size}${if (gallery?.end == true) "" else "+"} photos and videos"
                    },
                    color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Body("Click to select. Originals go to ${model.downloadsPath}.")
            }
            if (selected.isNotEmpty()) {
                TextAction("Clear", colors.textSecondary) { selected = emptySet() }
                Spacer(Modifier.width(Space.xs))
            } else if (items.isNotEmpty()) {
                TextAction("Select all", colors.mint) { selected = items.map { it.id }.toSet() }
                Spacer(Modifier.width(Space.xs))
            }
            SecondaryButton("Refresh", icon = DyskIcons.Refresh) { selected = emptySet(); model.loadGallery(device.id, true) }
            Spacer(Modifier.width(Space.xs))
            PrimaryButton(if (selected.size > 1) "Download ${selected.size}" else "Download", enabled = selected.isNotEmpty(), icon = DyskIcons.Download) {
                model.downloadGallery(device.id, items.filter { it.id in selected }.map { it.id })
                selected = emptySet()
            }
        }
    }
    gallery?.error?.let { Banner(it, colors.coral, DyskIcons.Warning) }
    if (items.isEmpty()) {
        EmptyCard(if (gallery?.loading != false) "Loading the gallery..." else "No photos or videos on the phone.", DyskIcons.Image)
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / 132.dp).toInt().coerceIn(3, 8)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item ->
                        GalleryTile(item, item.id in selected, Modifier.weight(1f)) {
                            selected = if (item.id in selected) selected - item.id else selected + item.id
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    if (gallery?.end != true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            SecondaryButton(if (gallery?.loading == true) "Loading..." else "Load more", enabled = gallery?.loading != true) { model.loadGallery(device.id, false) }
        }
    }
}

private val thumbs = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap?>(256, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap?>?) = size > 600
})

private fun thumbnail(item: GalleryItem): ImageBitmap? {
    val data = item.thumb ?: return null
    val key = "${item.id}:${data.length}"
    return thumbs.getOrPut(key) {
        runCatching { org.jetbrains.skia.Image.makeFromEncoded(java.util.Base64.getDecoder().decode(data)).toComposeImageBitmap() }.getOrNull()
    }
}

@Composable
private fun GalleryTile(item: GalleryItem, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = Dysk.colors
    val shape = RoundedCornerShape(12.dp)
    val picture = remember(item.id, item.thumb) { thumbnail(item) }
    Box(
        modifier.aspectRatio(1f).clip(shape).background(colors.panelRaised)
            .border(if (selected) 3.dp else 1.dp, if (selected) colors.mint else colors.stroke, shape)
            .clickable(onClick = onClick),
    ) {
        if (picture != null) Image(picture, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { DyskIcon(DyskIcons.Image, colors.textFaint, 22.dp) }
        if (item.video) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(horizontal = 7.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DyskIcon(DyskIcons.Play, Color.White, 9.dp)
                item.durationMs?.let { Spacer(Modifier.width(3.dp)); Text(duration(it), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).background(colors.mint, CircleShape),
                contentAlignment = Alignment.Center,
            ) { DyskIcon(DyskIcons.Check, colors.onAccent, 12.dp) }
        }
    }
}

private fun duration(ms: Long): String {
    val seconds = ms / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}

// Messages (SMS)

/** One conversation: the SMS of a thread, newest first, and who it's with. */
class SmsThread(val id: Long, val address: String, val messages: List<SmsItem>) {
    val latest: SmsItem get() = messages.first()
}

/** Threads newest first. */
fun smsThreads(messages: List<SmsItem>): List<SmsThread> =
    messages.groupBy { it.threadId }.map { (id, list) ->
        val sorted = list.sortedByDescending { it.time }
        SmsThread(id, sorted.first().address, sorted)
    }.sortedByDescending { it.latest.time }

/** The same number written two ways, such as +62 812... and 0812...: compared on the last 9 digits. */
fun sameNumber(a: String, b: String): Boolean {
    val x = a.filter(Char::isDigit)
    val y = b.filter(Char::isDigit)
    if (x.isEmpty() || y.isEmpty()) return a.trim().equals(b.trim(), ignoreCase = true)
    return x.takeLast(9) == y.takeLast(9)
}

private const val NEW_THREAD = -1L

@Composable
fun ColumnScope.MessagesTab(context: TabContext) {
    val colors = Dysk.colors
    val device = context.device
    val model = context.model
    if (!ready(device, "Messages", device.servesSms, "sms")) return
    val all by model.sms.collectAsState()
    val sms = all[device.id]
    LaunchedEffect(device.id) { if (sms?.loaded != true && sms?.loading != true) model.loadSms(device.id) }
    val threads = remember(sms?.messages) { smsThreads(sms?.messages.orEmpty()) }
    var chosen by remember(device.id) { mutableStateOf<Long?>(null) }
    var newAddress by remember(device.id) { mutableStateOf("") }
    // Opened from a contact: a new message to that number, or its conversation when there is one.
    LaunchedEffect(context.ui.smsTo) {
        val to = context.ui.smsTo ?: return@LaunchedEffect
        context.ui.smsTo = null
        val existing = threads.firstOrNull { sameNumber(it.address, to) }
        if (existing != null) chosen = existing.id else { chosen = NEW_THREAD; newAddress = to }
    }
    val thread = threads.firstOrNull { it.id == chosen } ?: if (chosen == null) threads.firstOrNull() else null
    val address = if (chosen == NEW_THREAD) newAddress else thread?.address.orEmpty()
    Row(Modifier.fillMaxWidth().height(520.dp), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        // Conversations
        Column(
            Modifier.width(300.dp).fillMaxHeight().background(colors.panel.copy(alpha = if (colors.isDark) 0.86f else 1f), RoundedCornerShape(18.dp))
                .border(1.dp, colors.stroke, RoundedCornerShape(18.dp)).padding(Space.xs),
        ) {
            Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Conversations", Modifier.weight(1f), color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                TextAction("New", colors.mint) { chosen = NEW_THREAD; newAddress = "" }
                TextAction("Refresh", colors.textSecondary) { model.loadSms(device.id) }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (threads.isEmpty()) Text(if (sms?.loading != false) "Loading messages..." else "No messages on the phone.", Modifier.padding(Space.sm), color = colors.textFaint, fontSize = 12.5.sp)
                threads.forEach { item ->
                    ThreadRow(item, item.id == thread?.id && chosen != NEW_THREAD) { chosen = item.id }
                }
            }
        }
        // The conversation
        Column(
            Modifier.weight(1f).fillMaxHeight().background(colors.panel.copy(alpha = if (colors.isDark) 0.86f else 1f), RoundedCornerShape(18.dp))
                .border(1.dp, colors.stroke, RoundedCornerShape(18.dp)).padding(Space.sm),
        ) {
            if (chosen == NEW_THREAD) {
                Text("New message", color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(Space.xs))
                InputField(newAddress, { newAddress = it.take(64) }, "Phone number", Modifier.fillMaxWidth())
            } else if (thread != null) {
                Text(thread.address, color = colors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(Space.xs))
            val scroll = rememberScrollState()
            val messages = if (chosen == NEW_THREAD) emptyList() else thread?.messages.orEmpty().reversed()
            val sent = sms?.sent.orEmpty().filter { address.isNotBlank() && sameNumber(it.address, address) }
                .filter { out -> out.ok != true || messages.none { it.outgoing && it.body == out.body && it.time >= out.time - 120_000 } }
                .reversed()
            LaunchedEffect(thread?.id, messages.size, sent.size) { scroll.scrollTo(scroll.maxValue) }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (messages.isEmpty() && sent.isEmpty()) {
                    Text(if (chosen == NEW_THREAD) "Type a number, then the message." else "Choose a conversation.", color = colors.textFaint, fontSize = 12.5.sp)
                }
                messages.forEach { Bubble(it.body, it.outgoing, SimpleDateFormat("d MMM, HH:mm").format(Date(it.time)), null) }
                sent.forEach { Bubble(it.body, true, sentStatus(it), if (it.ok == false) colors.coral else null) }
            }
            Spacer(Modifier.height(Space.xs))
            var text by remember(device.id, thread?.id, chosen) { mutableStateOf("") }
            val canSend = device.isConnected && address.isNotBlank() && text.isNotBlank()
            val send = {
                if (canSend && model.sendSms(device.id, address, text)) text = ""
            }
            Row(verticalAlignment = Alignment.Bottom) {
                InputField(text, { text = it.take(2_000) }, "Text message", Modifier.weight(1f), multiline = true, onSend = send)
                Spacer(Modifier.width(Space.xs))
                PrimaryButton("Send", enabled = canSend, icon = DyskIcons.Send, onClick = send)
            }
        }
    }
    Body("Sent through ${device.name}'s SIM. Carrier rates apply.", colors.textFaint)
}

private fun sentStatus(out: SmsOutgoing): String = when (out.ok) {
    null -> "Sending..."
    true -> "Sent"
    false -> "Not sent: ${out.error ?: "the phone couldn't send it"}"
}

@Composable
private fun ThreadRow(thread: SmsThread, selected: Boolean, onClick: () -> Unit) {
    val colors = Dysk.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (selected) colors.mint.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).background(colors.sky.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
            val first = thread.address.trim().firstOrNull()
            // A number gets a phone; a sender name such as a carrier gets its first letter.
            if (first == null || first.isDigit() || first == '+') DyskIcon(DyskIcons.Phone, colors.sky, 15.dp)
            else Text(first.uppercase(), color = colors.sky, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(thread.address, Modifier.weight(1f), color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(shortTime(thread.latest.time), color = colors.textFaint, fontSize = 10.5.sp)
            }
            Text(
                (if (thread.latest.outgoing) "You: " else "") + thread.latest.body,
                color = colors.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun shortTime(time: Long): String {
    val day = 86_400_000L
    val fmt = if (System.currentTimeMillis() - time < day) "HH:mm" else "d MMM"
    return SimpleDateFormat(fmt).format(Date(time))
}

@Composable
private fun Bubble(text: String, outgoing: Boolean, detail: String, tint: Color?) {
    val colors = Dysk.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 420.dp)
                .background(if (outgoing) colors.mint.copy(alpha = 0.18f) else colors.panelRaised, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
        ) {
            SelectionContainer { Text(text, color = colors.textPrimary, fontSize = 13.sp, lineHeight = 18.sp) }
            Spacer(Modifier.height(2.dp))
            Text(detail, color = tint ?: colors.textFaint, fontSize = 10.5.sp)
        }
    }
}

/** A plain text box. Enter calls [onSend]; with [multiline], Shift+Enter makes a new line. */
@Composable
fun InputField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, multiline: Boolean = false, onSend: (() -> Unit)? = null) {
    val colors = Dysk.colors
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value, onChange,
        modifier.heightIn(min = 40.dp).background(colors.panelRaised, shape).border(1.dp, colors.stroke, shape)
            .onPreviewKeyEvent { event ->
                val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                if (onSend != null && enter && !event.isShiftPressed && event.type == KeyEventType.KeyDown) { onSend(); true } else false
            },
        singleLine = !multiline,
        maxLines = if (multiline) 5 else 1,
        textStyle = TextStyle(color = colors.textPrimary, fontSize = 13.sp, lineHeight = 18.sp),
        cursorBrush = SolidColor(colors.mint),
        keyboardOptions = KeyboardOptions(imeAction = if (onSend != null) ImeAction.Send else ImeAction.Default),
        keyboardActions = KeyboardActions(onSend = { onSend?.invoke() }),
        decorationBox = { inner ->
            Box(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
                if (value.isEmpty()) Text(hint, color = colors.textFaint, fontSize = 13.sp, maxLines = 1)
                inner()
            }
        },
    )
}
