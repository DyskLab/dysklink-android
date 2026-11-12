package id.phonelink.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.phonelink.android.core.MirrorFeed
import id.phonelink.android.core.Peer
import id.phonelink.android.core.PeerAccess
import id.phonelink.android.core.PeerAsk
import id.phonelink.android.core.PhoneFeature
import id.phonelink.android.core.PhoneRules
import id.phonelink.android.core.QuietMath
import id.phonelink.android.service.AudioShare
import id.phonelink.android.service.PeerAudio
import id.phonelink.android.service.PhotoSync
import id.phonelink.android.service.Talk

/** "Allowed", "Ask first" (never asked: it asks here the first time), or "Never". */
internal fun allowState(state: Boolean?): String = when (state) {
    true -> "Allowed"
    false -> "Never"
    null -> "Ask first"
}

/** A short switch row: a title and its state underneath. */
@Composable
internal fun PermissionRow(title: String, state: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            state?.let { Text(it, color = if (checked) Palette.Mint else Palette.TextFaint, fontSize = 12.sp, lineHeight = 17.sp) }
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(Palette.Stroke))
}

/** How many of that phone's switches on this phone are on. */
internal fun allowedCount(peer: Peer, access: PeerAccess): Int {
    fun has(kind: PeerAsk) = when (kind) {
        PeerAsk.Links -> peer.canOpen
        PeerAsk.Media -> peer.canMedia
        PeerAsk.Usage -> peer.canMonitor
        PeerAsk.Quiet -> peer.canQuiet
        PeerAsk.Locate -> peer.canLocate
        PeerAsk.Move -> peer.canMove
        PeerAsk.Sms -> peer.canSms
        PeerAsk.Backup -> peer.canBackup
    }
    val local = listOf(
        access.screen == true,
        access.screen == true && access.control,
        access.camera == true,
        peer.canTalk && access.talk == true,
        peer.canPlaySound && access.sound == true,
        peer.canNotify && access.notify,
        peer.canNotify && access.calls,
    ).count { it }
    return local + PeerAsk.entries.count { has(it) && PhoneRules.allowed(access, it) == true }
}

/** One compact tile in the "Use <name>" grid, in the FeatureTile style. */
@Composable
private fun PhoneTile(icon: ImageVector, tint: Color, label: String, blurb: String, live: String?, badge: Int, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .clip(shape)
            .background(Palette.Panel.copy(alpha = 0.86f))
            .border(1.dp, if (live != null) tint.copy(alpha = 0.6f) else Palette.Stroke, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            FlatIcon(icon, tint)
            if (badge > 0) {
                Text(
                    if (badge > 99) "99+" else badge.toString(),
                    color = Palette.Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp)
                        .widthIn(min = 18.dp).clip(CircleShape).background(Palette.Coral).padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            label, color = Palette.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        // A short description, or what's happening now (Talking, Playing, On…) in the tile's colour.
        Text(
            live ?: blurb, color = if (live != null) tint else Palette.TextFaint, fontSize = 11.sp, lineHeight = 14.sp,
            textAlign = TextAlign.Center, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

private class Tile(val icon: ImageVector, val tint: Color, val label: String, val blurb: String, val live: String?, val badge: Int = 0, val open: () -> Unit)

/**
 * The page body for another phone, under its header: quick actions, the "Use <name>" grid, and a single note when
 * some tiles need a newer DyskLink there.
 */
@Composable
internal fun PhonePeerActions(
    peer: Peer,
    isRinging: Boolean,
    mirrorFeed: MirrorFeed?,
    relayLink: PhoneRelayLink?,
    remoteLink: PhoneRemoteLink?,
    onSendFiles: () -> Unit,
    onRing: () -> Unit,
    onRingFlash: (Boolean) -> Unit,
    onSendClipboard: (String) -> Unit,
    onOpen: (PhoneView) -> Unit,
) {
    val context = LocalContext.current
    val name = peer.name
    if (peer.isConnected) {
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            QuickAction(FlatIcons.sendFile, "Send file", "To $name", Palette.Mint, Modifier.weight(1f), onClick = onSendFiles)
            var menu by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                QuickAction(
                    if (isRinging) FlatIcons.ringOff else FlatIcons.ring,
                    if (isRinging) "Stop ringing" else "Find",
                    if (isRinging) "Ringing now" else if (peer.canLocate) "Hold for flash" else "Play a sound",
                    if (isRinging) Palette.Coral else Palette.Amber,
                    Modifier.fillMaxWidth(),
                    active = isRinging,
                    onLongClick = if (peer.canLocate && !isRinging) ({ menu = true }) else null,
                    onClick = onRing,
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Ring") }, onClick = { menu = false; onRing() })
                    DropdownMenuItem(text = { Text("Ring + flash") }, onClick = { menu = false; onRingFlash(true) })
                }
            }
            QuickAction(FlatIcons.commands, "Clipboard", "Send copied text", Palette.Sky, Modifier.weight(1f)) {
                val text = context.getSystemService(android.content.ClipboardManager::class.java)
                    .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                if (text.isNullOrEmpty()) {
                    android.widget.Toast.makeText(context, "Copy some text first", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    onSendClipboard(text)
                }
            }
        }
        IncomingAudioCard(peer, relayLink)
    }

    val talkingTo by Talk.to.collectAsStateWithLifecycle()
    val talkingHere by PeerAudio.talking.collectAsStateWithLifecycle()
    val sending by AudioShare.sending.collectAsStateWithLifecycle()
    val replies = remoteReplies(peer, remoteLink)
    val remote = remoteLink != null
    val backupOn = peer.canBackup && PhotoSync.isOn(context, peer.id)
    val tiles = if (peer.isConnected) {
        listOfNotNull(
            Tile(FlatIcons.shareScreen, Palette.Sky, "Screen", "See and control it", null) { onOpen(PhoneView.Screen) }.takeIf { peer.canViewScreen },
            Tile(Icons.Rounded.PhotoCamera, Palette.Mint, "Camera", "Remote shutter", null) { onOpen(PhoneView.Camera) }.takeIf { peer.canUseCamera },
            Tile(FlatIcons.mic, Palette.Sky, "Talk", "Hold to talk", "Talking".takeIf { talkingTo == peer.id || talkingHere == peer.id }) { onOpen(PhoneView.Talk) }
                .takeIf { peer.canTalk },
            Tile(Icons.Rounded.Speaker, Palette.Amber, "Sound", "Play your sound there", "Playing".takeIf { sending == peer.id }) { onOpen(PhoneView.Sound) }
                .takeIf { peer.canPlaySound && AudioShare.canSend },
            Tile(FlatIcons.media, Palette.Violet, "Music", "Control what plays", "Playing".takeIf { replies[PeerAsk.Media]?.nowPlaying?.playing == true }) { onOpen(PhoneView.Music) }
                .takeIf { peer.canMedia && remote },
            Tile(Icons.Rounded.Notifications, Palette.Violet, "Notifications", "Read and reply", null, mirrorFeed?.notifications?.size ?: 0) { onOpen(PhoneView.Notifications) }
                .takeIf { peer.canNotify },
            Tile(Icons.Rounded.Sms, Palette.Sky, "Messages", "SMS with its SIM", null) { onOpen(PhoneView.Sms) }.takeIf { peer.canSms },
            Tile(Icons.Rounded.LocationOn, Palette.Coral, "Locate", "Where it is now", null) { onOpen(PhoneView.Locate) }.takeIf { peer.canLocate && remote },
            Tile(Icons.Rounded.Link, Palette.Sky, "Send link", "Opens there on tap", null) { onOpen(PhoneView.Link) }.takeIf { peer.canOpen && remote },
            Tile(Icons.Rounded.DoNotDisturbOn, Palette.Amber, "Quiet", "Silent or Do Not Disturb", replies[PeerAsk.Quiet]?.quiet?.mode?.let(QuietMath::label)) { onOpen(PhoneView.Quiet) }
                .takeIf { peer.canQuiet && remote },
            Tile(Icons.Rounded.BatteryChargingFull, Palette.Mint, "Monitor", "Battery and screen time", null) { onOpen(PhoneView.Monitor) }
                .takeIf { peer.canMonitor && remote },
            Tile(Icons.Rounded.SwapHoriz, Palette.Violet, "Move phone", "Copy all to a new phone", null) { onOpen(PhoneView.Move) }.takeIf { peer.canMove },
            Tile(Icons.Rounded.PhotoLibrary, Palette.Mint, "Photo backup", "Send new photos there", "On".takeIf { backupOn }) { onOpen(PhoneView.Backup) }.takeIf { peer.canBackup },
        )
    } else {
        // Photo backup is set up here, so it stays reachable while that phone is away.
        listOfNotNull(Tile(Icons.Rounded.PhotoLibrary, Palette.Mint, "Photo backup", "Send new photos there", "On".takeIf { backupOn }) { onOpen(PhoneView.Backup) }.takeIf { peer.canBackup })
    }
    if (tiles.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        SectionLabel("USE ${name.uppercase()}")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { tile -> PhoneTile(tile.icon, tile.tint, tile.label, tile.blurb, tile.live, tile.badge, Modifier.weight(1f), tile.open) }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    val outdated = !(peer.canViewScreen && peer.canUseCamera && peer.canTalk && peer.canPlaySound && peer.canNotify && peer.canMedia &&
        peer.canOpen && peer.canMonitor && peer.canQuiet && peer.canLocate && peer.canSms && peer.canMove && peer.canBackup)
    if (peer.isConnected && outdated) {
        Spacer(Modifier.height(8.dp))
        Text("Update DyskLink on $name to use more.", color = Palette.TextFaint, fontSize = 12.sp)
    }
}

/** The "Permissions" row on another phone's page, opening [PhonePermissionsScreen]. */
@Composable
internal fun PhonePermissionsRow(peer: Peer, access: PeerAccess, onOpen: () -> Unit) {
    val count = allowedCount(peer, access)
    GlassCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Permissions", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${peer.name} on this phone: " + if (count == 0) "nothing allowed" else "$count allowed",
                    color = Palette.TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text("›", color = Palette.TextFaint, fontSize = 22.sp, modifier = Modifier.padding(start = 10.dp))
        }
    }
}

/** A page opened from another phone's page: "‹ <name>", a title, and its content. Back returns to that page. */
@Composable
internal fun PhonePageScaffold(peer: Peer, title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            "‹ ${peer.name}", color = Palette.TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(vertical = 8.dp, horizontal = 4.dp),
        )
        Text(title, color = Palette.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (!peer.isConnected) {
            Text("${peer.name} isn't connected.", color = Palette.Amber, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
        }
        content()
        Spacer(Modifier.height(12.dp))
    }
}

/** Talk, Sound, Music, Locate, Send link, Quiet, Battery & screen time, and Photo backup for another phone. */
@Composable
internal fun PhoneToolScreen(
    peer: Peer,
    view: PhoneView,
    remoteLink: PhoneRemoteLink?,
    isRinging: Boolean,
    onRingFlash: (Boolean) -> Unit,
    onStartSound: () -> Unit,
    onBack: () -> Unit,
) {
    if (view == PhoneView.Talk) return PhoneTalkScreen(peer, onBack)
    val title = when (view) {
        PhoneView.Sound -> "Sound on ${peer.name}"
        PhoneView.Music -> "Music"
        PhoneView.Locate -> "Locate"
        PhoneView.Link -> "Send link"
        PhoneView.Quiet -> "Quiet"
        PhoneView.Monitor -> "Battery & screen time"
        PhoneView.Backup -> "Photo backup"
        else -> ""
    }
    PhonePageScaffold(peer, title, onBack) {
        val kind = when (view) {
            PhoneView.Music -> PeerAsk.Media
            PhoneView.Locate -> PeerAsk.Locate
            PhoneView.Link -> PeerAsk.Links
            PhoneView.Quiet -> PeerAsk.Quiet
            PhoneView.Monitor -> PeerAsk.Usage
            else -> null
        }
        when {
            view == PhoneView.Sound -> PlaySoundCard(peer, onStartSound)
            view == PhoneView.Backup -> PhotoSyncCard(peer)
            kind != null && remoteLink != null -> PhoneRemoteCard(peer, remoteLink, kind, isRinging, onRingFlash)
        }
    }
}

/** One big Hold to talk button in the middle. Letting go, or leaving, ends the talk. */
@Composable
private fun PhoneTalkScreen(peer: Peer, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            "‹ ${peer.name}", color = Palette.TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(vertical = 8.dp, horizontal = 4.dp),
        )
        Text("Talk", color = Palette.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth()) {
                if (!peer.isConnected) {
                    Text("${peer.name} isn't connected.", color = Palette.Amber, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                }
                HoldToTalk(peer, height = 240.dp)
            }
        }
    }
}

/** Everything that phone may do here, and what this phone sends it. */
@Composable
internal fun PhonePermissionsScreen(
    peer: Peer,
    access: PeerAccess,
    onSetAccess: (PeerAccess) -> Unit,
    onOpenAccessibility: () -> Unit,
    onGrant: (PhoneFeature) -> Unit,
    clipboardEnabled: Boolean,
    clipboardAuto: Boolean,
    onToggleClipboard: (Boolean) -> Unit,
    onToggleClipboardAuto: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    PhonePageScaffold(peer, "Permissions", onBack) {
        SectionLabel("${peer.name.uppercase()} CAN")
        PhoneCanCard(peer, access, onSetAccess, onOpenAccessibility)
        if (peer.canNotify) {
            Spacer(Modifier.height(16.dp))
            SectionLabel("SEND TO ${peer.name.uppercase()}")
            PhoneSendCard(peer, access, onSetAccess, onGrant)
        }
        Spacer(Modifier.height(16.dp))
        SectionLabel("CLIPBOARD")
        GlassCard {
            PermissionRow("Receive copied text", if (clipboardEnabled) "From ${peer.name} when it sends it" else "Off", clipboardEnabled, onToggleClipboard)
            if (clipboardEnabled) {
                Divider()
                SettingRow(
                    "Send copies automatically",
                    "To every paired device when you tap Copy. Uses Accessibility. Passwords can be sent unless the app marks them private.",
                    clipboardAuto,
                    onToggleClipboardAuto,
                )
            }
        }
    }
}

/** View screen, Control, Camera, then voice and the remote switches. All off until turned on. */
@Composable
private fun PhoneCanCard(peer: Peer, access: PeerAccess, onChange: (PeerAccess) -> Unit, onOpenAccessibility: () -> Unit) {
    // Accessibility can be turned on in Settings and back: read again each time this page shows.
    var accessibility by remember { mutableStateOf(id.phonelink.android.service.DyskAccessibilityService.isRunning) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        accessibility = id.phonelink.android.service.DyskAccessibilityService.isRunning
        onPauseOrDispose {}
    }
    GlassCard {
        PermissionRow(
            "View screen",
            if (access.screen == true) "Allowed. Android asks here each time." else allowState(access.screen),
            access.screen == true,
        ) { on -> onChange(access.copy(screen = on, control = access.control && on)) }
        if (access.screen == true) {
            Divider()
            PermissionRow("Control", if (access.control) "Taps, Back, Home, Recents" else "Off", access.control) { on -> onChange(access.copy(control = on)) }
            if (access.control && !accessibility) {
                Spacer(Modifier.height(8.dp))
                Text("Control needs Accessibility. Until then ${peer.name} can only view.", color = Palette.Amber, fontSize = 12.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(8.dp))
                SecondaryButton("Open Accessibility settings", Modifier.fillMaxWidth(), onClick = onOpenAccessibility)
            }
        }
        Divider()
        PermissionRow("Camera", allowState(access.camera), access.camera == true) { on -> onChange(access.copy(camera = on)) }
        PhoneVoiceSwitches(peer, access, onChange)
        PhoneRemoteSwitches(peer, access, onChange)
    }
}
