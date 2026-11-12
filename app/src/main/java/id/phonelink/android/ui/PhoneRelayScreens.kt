package id.phonelink.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.phonelink.android.core.AudioKind
import id.phonelink.android.core.MirrorFeed
import id.phonelink.android.core.NotificationItem
import id.phonelink.android.core.PeerAccess
import id.phonelink.android.core.Peer
import id.phonelink.android.core.PhoneFeature
import id.phonelink.android.service.AudioShare
import id.phonelink.android.service.DyskNotificationListener
import id.phonelink.android.service.PeerAudio
import id.phonelink.android.service.PhoneDataImpl
import id.phonelink.android.service.PhoneSoundService
import id.phonelink.android.service.Talk

/** What the notification list, walkie-talkie, and sound between phones need from the connection. */
class PhoneRelayLink(
    val dismiss: (peerId: String, key: String) -> Unit,
    val reply: (peerId: String, key: String, text: String) -> Unit,
    val read: (peerId: String, key: String) -> Unit,
    val stopHearing: (peerId: String, AudioKind) -> Unit,
)

/**
 * On another phone's page, what this phone does with it: its notifications here, hold to talk, and this phone's
 * sound there. Each shows only when that phone lists it; older ones get a note to update.
 */
@Composable
fun PhoneRelayActions(
    peer: Peer,
    feed: MirrorFeed?,
    link: PhoneRelayLink?,
    onOpenNotifications: () -> Unit,
    onStartSound: () -> Unit,
) {
    val context = LocalContext.current
    val sending by AudioShare.sending.collectAsStateWithLifecycle()
    val soundProblem by AudioShare.problem.collectAsStateWithLifecycle()
    val talking by PeerAudio.talking.collectAsStateWithLifecycle()
    val hearing by PeerAudio.sound.collectAsStateWithLifecycle()

    if (talking == peer.id || hearing == peer.id) {
        Spacer(Modifier.height(10.dp))
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (talking == peer.id) "${peer.name} is talking" else "Playing sound from ${peer.name}",
                    color = Palette.Mint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                )
                SecondaryButton("Stop", tint = Palette.Coral) {
                    link?.stopHearing(peer.id, if (talking == peer.id) AudioKind.Talk else AudioKind.Sound)
                }
            }
        }
    }

    if (peer.canNotify || peer.canPlaySound) {
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (peer.canNotify) {
                val count = feed?.notifications?.size ?: 0
                QuickAction(
                    Icons.Rounded.Notifications, "Notifications", if (count == 0) "From ${peer.name}" else "$count from ${peer.name}",
                    Palette.Violet, Modifier.weight(1f), onClick = onOpenNotifications,
                )
            }
            if (peer.canPlaySound && AudioShare.canSend) {
                val on = sending == peer.id
                QuickAction(
                    Icons.Rounded.Speaker, if (on) "Stop sound" else "Play sound there",
                    if (on) "Playing on ${peer.name}" else "This phone's sound",
                    if (on) Palette.Coral else Palette.Amber, Modifier.weight(1f), active = on,
                ) { if (on) PhoneSoundService.stop(context) else onStartSound() }
            }
        }
        if (soundProblem != null && sending == null) {
            Spacer(Modifier.height(6.dp))
            Text(soundProblem!!, color = Palette.Amber, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }

    if (peer.canTalk) {
        Spacer(Modifier.height(10.dp))
        HoldToTalk(peer)
    }

    if (!(peer.canNotify && peer.canTalk && peer.canPlaySound)) {
        Spacer(Modifier.height(8.dp))
        Text(
            "Update DyskLink on ${peer.name} for its notifications here, the walkie-talkie, and playing this phone's sound there.",
            color = Palette.TextFaint, fontSize = 12.sp, lineHeight = 17.sp,
        )
    }
}

/** A big button: hold it and the microphone goes to the other phone, which plays it at once. */
@Composable
private fun HoldToTalk(peer: Peer) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val to by Talk.to.collectAsStateWithLifecycle()
    val problem by Talk.problem.collectAsStateWithLifecycle()
    var note by remember { mutableStateOf<String?>(null) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        note = if (granted) "Ready. Hold the button to talk." else "DyskLink needs the microphone to talk to ${peer.name}."
    }
    val active = to == peer.id
    // Leaving the page while holding ends the talk.
    DisposableEffect(peer.id) { onDispose { if (Talk.to.value == peer.id) Talk.stop(context) } }
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(shape)
            .background((if (active) Palette.Coral else Palette.Sky).copy(alpha = if (active) 0.3f else 0.12f))
            .border(1.dp, (if (active) Palette.Coral else Palette.Sky).copy(alpha = 0.6f), shape)
            .pointerInput(peer.id) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        askMic.launch(Manifest.permission.RECORD_AUDIO)
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    note = null
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    Talk.start(context, peer.id)
                    waitForUpOrCancellation()
                    Talk.stop(context)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Mic, null, tint = if (active) Palette.Coral else Palette.Sky)
            Text(if (active) "Talking to ${peer.name}" else "Hold to talk", color = Palette.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(if (active) "Let go to stop" else "${peer.name} hears you at once", color = Palette.TextSecondary, fontSize = 12.sp)
        }
    }
    (note ?: problem)?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, color = Palette.Amber, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

/** The switches for voice and sound from that phone, under "What it can do here". */
@Composable
fun PhoneVoiceSwitches(peer: Peer, access: PeerAccess, onChange: (PeerAccess) -> Unit) {
    if (peer.canTalk) {
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(Palette.Stroke))
        SettingRow(
            "Allow ${peer.name} to talk to this phone",
            "Walkie-talkie: ${peer.name}'s voice plays here at once while they hold Talk.",
            access.talk == true,
        ) { on -> onChange(access.copy(talk = on)) }
    }
    if (peer.canPlaySound) {
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(Palette.Stroke))
        SettingRow(
            "Play sound from ${peer.name}",
            "Music and videos on ${peer.name} can play on this phone's speaker. ${peer.name} starts it, and either phone can stop it.",
            access.sound == true,
        ) { on -> onChange(access.copy(sound = on)) }
    }
}

/** What this phone sends to that phone: notifications, calls, and the apps left out. All off until turned on. */
@Composable
fun PhoneSendCard(peer: Peer, access: PeerAccess, onChange: (PeerAccess) -> Unit, onGrant: (PhoneFeature) -> Unit) {
    val context = LocalContext.current
    val data = remember { PhoneDataImpl(context) }
    var listening by remember { mutableStateOf(data.isGranted(PhoneFeature.Notifications)) }
    var callsGranted by remember { mutableStateOf(data.isGranted(PhoneFeature.Calls)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        listening = data.isGranted(PhoneFeature.Notifications)
        callsGranted = data.isGranted(PhoneFeature.Calls)
        onPauseOrDispose {}
    }
    var picking by remember { mutableStateOf(false) }
    GlassCard {
        SettingRow(
            "Send my notifications to ${peer.name}",
            "They show on ${peer.name} while it's connected, and can be dismissed or answered from there. Notifications an app marks secret are never sent, as for a Mac.",
            access.notify,
        ) { on ->
            onChange(access.copy(notify = on))
            if (on && !listening) onGrant(PhoneFeature.Notifications)
        }
        if (access.notify && !listening) {
            Spacer(Modifier.height(8.dp))
            Text("Needs notification access for DyskLink.", color = Palette.Amber, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Allow notification access", Modifier.fillMaxWidth()) { onGrant(PhoneFeature.Notifications) }
        }
        if (access.notify) {
            Spacer(Modifier.height(12.dp))
            Text("Not sent", color = Palette.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            if (access.muted.isEmpty()) Text("Every app is sent.", color = Palette.TextFaint, fontSize = 12.sp)
            access.muted.forEach { pkg ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(appLabel(context, pkg), color = Palette.TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Send again", color = Palette.Sky, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(CircleShape).clickable { onChange(access.copy(muted = access.muted - pkg)) }.padding(8.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            SecondaryButton("Leave out an app", Modifier.fillMaxWidth()) { picking = true }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(Palette.Stroke))
        SettingRow(
            "Send calls",
            "Incoming calls show on ${peer.name} with the number, so it can be silenced there. Answer on this phone.",
            access.calls,
        ) { on ->
            onChange(access.copy(calls = on))
            if (on && !callsGranted) onGrant(PhoneFeature.Calls)
        }
        if (access.calls && !callsGranted) {
            Spacer(Modifier.height(8.dp))
            Text("Needs the phone permission for DyskLink.", color = Palette.Amber, fontSize = 12.sp)
        }
    }
    if (picking) {
        // Apps with a notification showing now, as the listener sees them.
        val apps = remember {
            DyskNotificationListener.activeItems().mapNotNull { item -> item.pkg?.let { it to item.app } }
                .distinctBy { it.first }.filter { it.first !in access.muted }.sortedBy { it.second.lowercase() }
        }
        AlertDialog(
            onDismissRequest = { picking = false },
            confirmButton = { TextButton(onClick = { picking = false }) { Text("Close") } },
            title = { Text("Leave out an app") },
            text = {
                if (apps.isEmpty()) {
                    Text("Apps show here while they have a notification on this phone.")
                } else {
                    LazyColumn {
                        items(apps, key = { it.first }) { (pkg, name) ->
                            Text(
                                name, fontSize = 16.sp,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    onChange(access.copy(muted = (access.muted + pkg).distinct()))
                                    picking = false
                                }.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
            },
        )
    }
}

private fun appLabel(context: android.content.Context, pkg: String): String = runCatching {
    val manager = context.packageManager
    manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

/** Another phone's notifications and its call, here while it's connected. */
@Composable
fun PhoneNotificationsScreen(peer: Peer, feed: MirrorFeed?, link: PhoneRelayLink?, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var replying by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf<Set<String>>(emptySet()) }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            "‹ ${peer.name}", color = Palette.TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(vertical = 8.dp, horizontal = 4.dp),
        )
        Text("Notifications from ${peer.name}", color = Palette.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        val call = feed?.call?.takeIf { it.state == "ringing" }
        if (call != null) {
            GlassCard {
                Text("Incoming call on ${peer.name}", color = Palette.Mint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(call.number ?: "Unknown number", color = Palette.TextPrimary, fontSize = 18.sp)
                Text("Answer or decline it on ${peer.name}.", color = Palette.TextSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
        }
        val items = feed?.notifications.orEmpty()
        if (items.isEmpty()) {
            Text(
                if (!peer.isConnected) "Shown only while ${peer.name} is connected."
                else "Nothing yet. On ${peer.name}, open DyskLink, then this phone's page, and turn on \"Send my notifications\".",
                color = Palette.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp,
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(items, key = { it.key }) { item ->
                MirroredCard(
                    item,
                    replying = replying == item.key,
                    sent = item.key in sent,
                    draft = draft,
                    onDraft = { draft = it },
                    onReply = { replying = item.key; draft = "" },
                    onSend = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            link?.reply(peer.id, item.key, text)
                            sent = sent + item.key
                            replying = null
                            draft = ""
                        }
                    },
                    onRead = { link?.read(peer.id, item.key) },
                    onDismiss = { link?.dismiss(peer.id, item.key) },
                )
            }
        }
    }
}

@Composable
private fun MirroredCard(
    item: NotificationItem,
    replying: Boolean,
    sent: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    onReply: () -> Unit,
    onSend: () -> Unit,
    onRead: () -> Unit,
    onDismiss: () -> Unit,
) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.app, color = Palette.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            Text(
                java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(item.time)),
                color = Palette.TextFaint, fontSize = 12.sp,
            )
        }
        item.title?.let { Text(it, color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        item.text?.let { Text(it, color = Palette.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 6, overflow = TextOverflow.Ellipsis) }
        if (sent) Text("Reply sent", color = Palette.Mint, fontSize = 12.sp)
        if (replying) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(draft, onDraft, Modifier.fillMaxWidth(), placeholder = { Text("Reply") }, maxLines = 4)
            Spacer(Modifier.height(6.dp))
            PrimaryButton("Send", Modifier.fillMaxWidth(), onClick = onSend)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (item.reply == true && !replying) CardAction("Reply", Palette.Sky, onReply)
            if (item.markRead == true) CardAction("Mark as read", Palette.Sky, onRead)
            CardAction("Dismiss", Palette.Coral, onDismiss)
        }
    }
}

@Composable
private fun CardAction(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
    )
}
