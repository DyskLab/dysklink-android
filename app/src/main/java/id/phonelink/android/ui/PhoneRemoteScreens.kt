package id.phonelink.android.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeDown
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.phonelink.android.core.Envelope
import id.phonelink.android.core.MonitorSettings
import id.phonelink.android.core.PeerAccess
import id.phonelink.android.core.PeerAsk
import id.phonelink.android.core.Peer
import id.phonelink.android.core.QuietMath
import id.phonelink.android.core.QuietRequest
import id.phonelink.android.core.SafeLink
import id.phonelink.android.service.RemoteAsks
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** What links, the music remote, the family monitor, quiet mode, and Locate need from the connection. */
class PhoneRemoteLink(
    val replies: StateFlow<Map<String, Map<PeerAsk, Envelope>>>,
    val monitor: StateFlow<Map<String, MonitorSettings>>,
    val sendLink: (peerId: String, text: String) -> Boolean,
    val media: (peerId: String, action: String) -> Unit,
    val screenTime: (peerId: String) -> Unit,
    val quiet: (peerId: String, QuietRequest) -> Unit,
    val locate: (peerId: String) -> Unit,
    val setMonitor: (peerId: String, MonitorSettings) -> Unit,
)

/** The answers that phone sent to this one's last asks, for its page and tiles. */
@Composable
fun remoteReplies(peer: Peer, link: PhoneRemoteLink?): Map<PeerAsk, Envelope> {
    link ?: return emptyMap()
    val all by link.replies.collectAsStateWithLifecycle()
    return all[peer.id].orEmpty()
}

/**
 * One of the remote cards for another phone (link, music, monitor, quiet, Locate), shown on its own page. Polling and
 * asks stop when the page closes.
 */
@Composable
internal fun PhoneRemoteCard(peer: Peer, link: PhoneRemoteLink, kind: PeerAsk, isRinging: Boolean, onRing: (flash: Boolean) -> Unit) {
    val replies = remoteReplies(peer, link)
    when (kind) {
        PeerAsk.Links -> SendLinkCard(peer, link, replies[PeerAsk.Links])
        PeerAsk.Media -> MusicRemoteCard(peer, link, replies[PeerAsk.Media])
        PeerAsk.Usage -> MonitorCard(peer, link, replies[PeerAsk.Usage])
        PeerAsk.Quiet -> QuietCard(peer, link, replies[PeerAsk.Quiet])
        PeerAsk.Locate -> LocateCard(peer, link, replies[PeerAsk.Locate], isRinging, onRing)
        else -> Unit
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Note(text: String, color: Color = Palette.TextSecondary) {
    Text(text, color = color, fontSize = 12.sp, lineHeight = 17.sp)
}

@Composable
private fun SendLinkCard(peer: Peer, link: PhoneRemoteLink, reply: Envelope?) {
    var text by remember(peer.id) { mutableStateOf("") }
    var note by remember(peer.id) { mutableStateOf<String?>(null) }
    var sentAt by remember(peer.id) { mutableStateOf<Envelope?>(null) }
    GlassCard {
        CardTitle("Open on ${peer.name}")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(text, { text = it; note = null }, Modifier.fillMaxWidth(), placeholder = { Text("Send link…") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Send", Modifier.fillMaxWidth()) {
            val found = SafeLink.find(text)
            note = when {
                found == null -> "Only web links (http, https), map links, and phone numbers can be sent."
                !link.sendLink(peer.id, text) -> "${peer.name} isn't connected."
                else -> {
                    sentAt = reply
                    text = ""
                    "Sent. ${peer.name} shows it as a notification; it opens only when tapped there."
                }
            }
        }
        // The answer to the last send, once it's a new one.
        val answer = reply?.takeIf { it !== sentAt }
        (answer?.error ?: note)?.let {
            Spacer(Modifier.height(6.dp))
            Note(it, if (answer?.error != null) Palette.Amber else Palette.TextSecondary)
        }
    }
}

@Composable
private fun MusicRemoteCard(peer: Peer, link: PhoneRemoteLink, reply: Envelope?) {
    val playing = reply?.nowPlaying
    val error = reply?.error
    // Polls what plays while the card shows, once the other phone answered; nothing is asked there just by opening this page.
    val live = reply != null && error == null
    LaunchedEffect(peer.id, live) {
        while (live) {
            delay(POLL_MS)
            link.media(peer.id, "state")
        }
    }
    GlassCard {
        CardTitle("Music on ${peer.name}")
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val art = remember(playing?.art) {
                playing?.art?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }
                    ?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
            }
            if (art != null) {
                Image(art, null, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(playing?.title ?: "Nothing playing", color = Palette.TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                listOfNotNull(playing?.artist, playing?.app).joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                    Text(it, color = Palette.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val volume = playing?.volume
                val max = playing?.maxVolume
                if (volume != null && max != null && max > 0) Text("Volume ${volume * 100 / max} %", color = Palette.TextFaint, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RemoteButton(Icons.Rounded.SkipPrevious, "Previous") { link.media(peer.id, "previous") }
            RemoteButton(if (playing?.playing == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play or pause", Palette.Mint) {
                link.media(peer.id, "play_pause")
            }
            RemoteButton(Icons.Rounded.SkipNext, "Next") { link.media(peer.id, "next") }
            RemoteButton(Icons.Rounded.VolumeDown, "Volume down") { link.media(peer.id, "volume_down") }
            RemoteButton(Icons.Rounded.VolumeUp, "Volume up") { link.media(peer.id, "volume_up") }
        }
        (error ?: playing?.note)?.let {
            Spacer(Modifier.height(8.dp))
            Note(it, if (error != null) Palette.Amber else Palette.TextSecondary)
        }
        if (!live) {
            Spacer(Modifier.height(8.dp))
            SecondaryButton(if (error != null) "Try again" else "Show what plays", Modifier.fillMaxWidth()) { link.media(peer.id, "state") }
        }
    }
}

@Composable
private fun RemoteButton(icon: ImageVector, label: String, tint: Color = Palette.Sky, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)).border(1.dp, tint.copy(alpha = 0.4f), CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = tint) }
}

@Composable
private fun MonitorCard(peer: Peer, link: PhoneRemoteLink, reply: Envelope?) {
    val all by link.monitor.collectAsStateWithLifecycle()
    val settings = all[peer.id] ?: MonitorSettings()
    // Asked again on opening only once it was answered before, so opening the page doesn't ask the user there.
    LaunchedEffect(peer.id) { if (reply?.usage != null) link.screenTime(peer.id) }
    GlassCard {
        CardTitle("Family monitor")
        Spacer(Modifier.height(4.dp))
        val info = peer.info
        Note(
            info?.batteryPercent?.let { "Battery $it %" + if (info.isCharging == true) ", charging" else "" } ?: "Battery not known yet",
            Palette.TextPrimary,
        )
        Spacer(Modifier.height(10.dp))
        SettingRow(
            "Alert when the battery is low",
            "Once each time ${peer.name} runs down to ${settings.threshold} %.",
            settings.lowAlert,
        ) { on -> link.setMonitor(peer.id, settings.copy(lowAlert = on)) }
        if (settings.lowAlert) {
            Slider(
                value = settings.threshold.toFloat(),
                onValueChange = { link.setMonitor(peer.id, settings.copy(threshold = (it / 5f).toInt() * 5)) },
                valueRange = MonitorSettings.MIN_THRESHOLD.toFloat()..MonitorSettings.MAX_THRESHOLD.toFloat(),
                steps = 3,
            )
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(Palette.Stroke))
        SettingRow(
            "Alert when fully charged",
            "Once each time ${peer.name} reaches 100 % while charging.",
            settings.fullAlert,
        ) { on -> link.setMonitor(peer.id, settings.copy(fullAlert = on)) }
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(Palette.Stroke))
        Text("Screen time today", color = Palette.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        val usage = reply?.usage
        when {
            reply?.error != null -> Note(reply.error!!, Palette.Amber)
            usage == null -> Note("Tap Refresh to ask ${peer.name}.")
            else -> {
                Text(duration(usage.totalMs) + (usage.unlocks?.let { ", unlocked $it times" }.orEmpty()), color = Palette.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                usage.apps.take(TOP_APPS).forEach { app ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(app.name, color = Palette.TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(duration(app.ms), color = Palette.TextFaint, fontSize = 13.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Refresh", Modifier.fillMaxWidth()) { link.screenTime(peer.id) }
    }
}

private fun duration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

private enum class QuietLength(val label: String, val minutes: Int?, val morning: Boolean) {
    None("Until changed", null, false),
    Half("30 min", 30, false),
    Hour("1 hour", 60, false),
    Morning("Until 07:00", null, true),
}

@Composable
private fun QuietCard(peer: Peer, link: PhoneRemoteLink, reply: Envelope?) {
    var length by remember(peer.id) { mutableStateOf(QuietLength.None) }
    GlassCard {
        CardTitle("Quiet mode")
        Spacer(Modifier.height(8.dp))
        fun send(mode: String) = link.quiet(peer.id, QuietRequest(mode, minutes = length.minutes, untilMorning = length.morning.takeIf { it }))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Silent", Palette.Coral) { send(QuietMath.SILENT) }
            Chip("Vibrate", Palette.Amber) { send(QuietMath.VIBRATE) }
            Chip("Normal", Palette.Mint) { send(QuietMath.NORMAL) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Do Not Disturb on", Palette.Violet) { send(QuietMath.DND_ON) }
            Chip("Off", Palette.Sky) { send(QuietMath.DND_OFF) }
        }
        Spacer(Modifier.height(10.dp))
        Note("For how long (Silent, Vibrate, and Do Not Disturb)")
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QuietLength.entries.forEach { option ->
                Chip(option.label, if (option == length) Palette.Mint else Palette.TextFaint, selected = option == length) { length = option }
            }
        }
        reply?.let { answer ->
            Spacer(Modifier.height(8.dp))
            val set = answer.quiet
            when {
                answer.error != null -> Note(answer.error!!, Palette.Amber)
                set != null -> Note(
                    "${peer.name}: ${QuietMath.label(set.mode)}" +
                        (set.until?.let { " until " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)) }.orEmpty()) +
                        ". It shows a notification there with Undo.",
                    Palette.Mint,
                )
            }
        }
    }
}

@Composable
private fun Chip(label: String, color: Color, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        label, color = if (selected) Palette.Ink else color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(if (selected) color else color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.4f), CircleShape).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun LocateCard(peer: Peer, link: PhoneRemoteLink, reply: Envelope?, isRinging: Boolean, onRing: (Boolean) -> Unit) {
    val context = LocalContext.current
    var asking by remember(peer.id) { mutableStateOf<Envelope?>(null) }
    var asked by remember(peer.id) { mutableStateOf(false) }
    GlassCard {
        CardTitle("Locate ${peer.name}")
        Spacer(Modifier.height(8.dp))
        val fix = reply?.location
        val waiting = asked && reply === asking
        when {
            waiting -> Note("Asking ${peer.name} where it is…")
            reply?.error != null -> Note(reply.error!!, Palette.Amber)
            fix != null -> {
                Text("%.5f, %.5f".format(java.util.Locale.US, fix.lat, fix.lon), color = Palette.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(fix.time))
                Note(listOfNotNull(fix.accuracy?.let { "Within ${it.toInt()} m" }, "at $time").joinToString(", "))
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Open in Maps", Modifier.fillMaxWidth()) {
                    val label = Uri.encode(peer.name)
                    val uri = Uri.parse("geo:${fix.lat},${fix.lon}?q=${fix.lat},${fix.lon}($label)")
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        .onFailure { android.widget.Toast.makeText(context, "No maps app found", android.widget.Toast.LENGTH_SHORT).show() }
                }
            }
            else -> Note("${peer.name} answers only if it allowed sharing its location with this phone.")
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("Locate", Modifier.weight(1f)) {
                asking = reply
                asked = true
                link.locate(peer.id)
            }
            SecondaryButton(if (isRinging) "Stop ringing" else "Ring + flash", Modifier.weight(1f), tint = if (isRinging) Palette.Coral else Palette.Amber) {
                onRing(!isRinging)
            }
        }
    }
}

/** On this phone (B): what the other phone (A) may do with it as a remote. Each asks the first time it's used (Ask first). */
@Composable
fun PhoneRemoteSwitches(peer: Peer, access: PeerAccess, onChange: (PeerAccess) -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose {}
    }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    fun missing(kind: PeerAsk) = refresh >= 0 && RemoteAsks.missing(context, kind)
    var askSmsPermission: (() -> Unit)? = null
    fun grant(kind: PeerAsk) {
        if (kind == PeerAsk.Sms) {
            askSmsPermission?.invoke()
        } else if (kind == PeerAsk.Locate) {
            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        } else {
            RemoteAsks.settingsIntent(context, kind)?.let { runCatching { context.startActivity(it) } }
        }
    }
    val name = peer.name
    val rows = listOfNotNull(
        (PeerAsk.Media to "Music").takeIf { peer.canMedia },
        (PeerAsk.Links to "Links").takeIf { peer.canOpen },
        (PeerAsk.Usage to "Screen time").takeIf { peer.canMonitor },
        (PeerAsk.Quiet to "Quiet").takeIf { peer.canQuiet },
        (PeerAsk.Locate to "Location").takeIf { peer.canLocate },
        (PeerAsk.Sms to "Messages").takeIf { peer.canSms },
        (PeerAsk.Move to "Move").takeIf { peer.canMove },
        (PeerAsk.Backup to "Photo backup").takeIf { peer.canBackup },
    )
    val askSms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    askSmsPermission = { askSms.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)) }
    var confirmSms by remember { mutableStateOf(false) }
    if (confirmSms) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmSms = false },
            title = { Text("Let $name read and send SMS?") },
            text = {
                Text(
                    "$name could read every SMS on this phone, including bank and login codes, and send SMS from this phone's number. " +
                        "Only allow a device you own. You get a notification each time it sends one.",
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmSms = false
                    onChange(id.phonelink.android.core.PhoneRules.withAllowed(access, PeerAsk.Sms, true))
                    if (missing(PeerAsk.Sms)) askSms.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS))
                }) { Text("Allow") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmSms = false }) { Text("Cancel") } },
        )
    }
    rows.forEach { (kind, title) ->
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(Palette.Stroke))
        val state = id.phonelink.android.core.PhoneRules.allowed(access, kind)
        val on = state == true
        PermissionRow(title, allowState(state), on) { checked ->
            // SMS asks once more, with the warning, before it's turned on by hand.
            if (checked && kind == PeerAsk.Sms) return@PermissionRow run { confirmSms = true }
            onChange(id.phonelink.android.core.PhoneRules.withAllowed(access, kind, checked))
            if (checked && missing(kind)) grant(kind)
        }
        if (kind == PeerAsk.Sms && access.sms != null) {
            val manager by id.phonelink.android.service.PhoneLinkService.connectionManager.collectAsStateWithLifecycle()
            val log = manager?.smsLog?.entries?.collectAsStateWithLifecycle()?.value.orEmpty()
            SmsLogList(peer, log)
        }
        if (on && missing(kind)) {
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                when (kind) {
                    PeerAsk.Sms -> "Allow SMS"
                    PeerAsk.Media -> "Allow notification access"
                    PeerAsk.Usage -> "Allow Usage access"
                    PeerAsk.Quiet -> "Allow Do Not Disturb access"
                    else -> "Allow location"
                },
                Modifier.fillMaxWidth(),
            ) { grant(kind) }
        }
    }
}

private const val POLL_MS = 3_000L
private const val TOP_APPS = 5
