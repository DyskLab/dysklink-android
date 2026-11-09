package id.phonelink.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.phonelink.android.core.CommandItem
import id.phonelink.android.core.CommandResult
import id.phonelink.android.core.InputEvent
import id.phonelink.android.core.ManualTarget
import id.phonelink.android.core.PairingPrompt
import id.phonelink.android.core.Peer
import id.phonelink.android.core.PhoneFeature
import id.phonelink.android.core.PhoneLinkProtocol
import id.phonelink.android.core.Transfer
import id.phonelink.android.service.Updates

data class UiState(
    val localName: String,
    val peers: List<Peer>,
    val prompt: PairingPrompt?,
    val manualTargets: List<ManualTarget>,
    val lastError: String?,
    val batteryRestricted: Boolean,
    val transfers: List<Transfer>,
    val commands: Map<String, List<CommandItem>>,
    val commandResult: CommandResult?,
    val clipboardEnabled: Boolean,
    val clipboardAuto: Boolean,
    val phoneEnabled: Set<PhoneFeature>,
    val phoneActive: Set<PhoneFeature>,
    val screenPeer: String?,
    val updates: Updates.State = Updates.State(),
    val appVersion: String = "",
)

data class UiActions(
    val requestPairing: (String) -> Unit,
    val acceptPairing: () -> Unit,
    val rejectPairing: () -> Unit,
    val unpair: (String) -> Unit,
    val addManual: (String, Int) -> Unit,
    val removeManual: (ManualTarget) -> Unit,
    val allowBackground: () -> Unit,
    val openAppSettings: () -> Unit,
    val sendFiles: (peerId: String) -> Unit,
    val sendInput: (peerId: String, InputEvent) -> Unit,
    val sendMedia: (peerId: String, String) -> Unit,
    val runCommand: (peerId: String, commandId: String) -> Unit,
    val setClipboardEnabled: (Boolean) -> Unit,
    val setClipboardAuto: (Boolean) -> Unit,
    val setPhoneFeature: (PhoneFeature, Boolean) -> Unit,
    val grantPhoneFeature: (PhoneFeature) -> Unit,
    val startScreen: (peerId: String) -> Unit,
    val stopScreen: () -> Unit,
    val downloadUpdate: () -> Unit = {},
    val skipUpdate: () -> Unit = {},
    val checkUpdates: () -> Unit = {},
    val setAutoUpdates: (Boolean) -> Unit = {},
    val openTransfer: (Transfer) -> Unit = {},
)

@Composable
fun PhoneLinkApp(state: UiState, actions: UiActions) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showManual by remember { mutableStateOf(false) }
    var feature by remember { mutableStateOf<Feature?>(null) }
    val selected = state.peers.firstOrNull { it.id == selectedId && it.isPaired }

    AuroraBackground {
        if (selected != null) {
            val closeFeature = { feature = null }
            when (feature) {
                null -> {
                    BackHandler { selectedId = null }
                    DetailScreen(
                        selected,
                        transfers = state.transfers.filter { it.peerId == selected.id },
                        clipboardEnabled = state.clipboardEnabled,
                        clipboardAuto = state.clipboardAuto,
                        isSharingScreen = state.screenPeer == selected.id,
                        onToggleScreen = {
                            if (state.screenPeer == selected.id) actions.stopScreen() else actions.startScreen(selected.id)
                        },
                        onBack = { selectedId = null },
                        onOpenFeature = { feature = it },
                        onSendFiles = { actions.sendFiles(selected.id) },
                        onToggleClipboard = actions.setClipboardEnabled,
                        onToggleClipboardAuto = actions.setClipboardAuto,
                        onOpenTransfer = actions.openTransfer,
                        onUnpair = {
                            actions.unpair(selected.id)
                            selectedId = null
                        },
                    )
                }
                Feature.Touchpad -> TouchpadScreen(closeFeature) { actions.sendInput(selected.id, it) }
                Feature.Media -> MediaScreen(closeFeature) { actions.sendMedia(selected.id, it) }
                Feature.Presentation -> PresentationScreen(closeFeature) { actions.sendInput(selected.id, it) }
                Feature.Commands -> CommandsScreen(
                    state.commands[selected.id].orEmpty(),
                    state.commandResult,
                    closeFeature,
                ) { actions.runCommand(selected.id, it) }
                Feature.Sharing -> SharingScreen(
                    state.phoneEnabled,
                    state.phoneActive,
                    closeFeature,
                    actions.setPhoneFeature,
                    actions.grantPhoneFeature,
                )
            }
            if (feature != null) BackHandler(onBack = closeFeature)
            if (selected.isConnected && selected.info?.inputTrusted == false && feature != Feature.Sharing) {
                Text(
                    "Accessibility permission is off on the Mac, so the touchpad, keyboard, and remotes won't work. " +
                        "Press Allow in the DyskLink app on the Mac.",
                    color = Palette.Ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(12.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Palette.Amber)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        } else {
            HomeScreen(
                state = state,
                onOpen = { peer ->
                    if (peer.isPaired) selectedId = peer.id else actions.requestPairing(peer.id)
                },
                onManual = { showManual = true },
                onRemoveManual = actions.removeManual,
                onAllowBackground = actions.allowBackground,
                onOpenSettings = actions.openAppSettings,
                actions = actions,
            )
        }
        if (showManual) {
            BackHandler { showManual = false }
            ManualSheet(
                onDismiss = { showManual = false },
                onSubmit = { host, port ->
                    actions.addManual(host, port)
                    showManual = false
                },
            )
        }
        state.prompt?.let { prompt ->
            BackHandler { actions.rejectPairing() }
            PairingOverlay(prompt, actions.acceptPairing, actions.rejectPairing)
        }
    }
}

@Composable
private fun HomeScreen(
    state: UiState,
    onOpen: (Peer) -> Unit,
    onManual: () -> Unit,
    onRemoveManual: (ManualTarget) -> Unit,
    onAllowBackground: () -> Unit,
    onOpenSettings: () -> Unit,
    actions: UiActions,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LogoMark()
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text("DyskLink", color = Palette.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    state.localName,
                    color = Palette.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.height(24.dp))

        state.updates.available?.let { release ->
            UpdateBanner(release.version, actions.downloadUpdate, actions.skipUpdate)
            Spacer(Modifier.height(20.dp))
        }

        if (state.batteryRestricted) {
            BackgroundCard(onAllowBackground, onOpenSettings)
            Spacer(Modifier.height(20.dp))
        }

        if (state.peers.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Radar()
                Spacer(Modifier.height(10.dp))
                Text("Looking for your Mac", color = Palette.TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Open DyskLink on your Mac and make sure both are on the same Wi-Fi.",
                    color = Palette.TextSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 21.sp,
                )
            }
        } else {
            SectionLabel("DEVICES")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.peers.forEach { peer -> PeerCard(peer) { onOpen(peer) } }
            }
            Spacer(Modifier.height(24.dp))
        }

        // A manual address attempt can fail even when the device is connected another way.
        state.lastError?.takeIf { state.peers.none(Peer::isConnected) }?.let {
            Text(it, color = Palette.Coral, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
        }

        if (state.manualTargets.isNotEmpty()) {
            SectionLabel("MANUAL ADDRESSES")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.manualTargets.forEach { target ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Palette.Panel.copy(alpha = 0.8f))
                            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            target.label,
                            color = Palette.TextSecondary,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "Remove",
                            color = Palette.Coral,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { onRemoveManual(target) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        SecondaryButton("Connect with an IP address", Modifier.fillMaxWidth(), onClick = onManual)
        Spacer(Modifier.height(24.dp))
        UpdatesCard(state.updates, state.appVersion, actions)
    }
}

@Composable
private fun UpdateBanner(version: String, onDownload: () -> Unit, onSkip: () -> Unit) {
    GlassCard {
        Text("DyskLink $version is available", color = Palette.Mint, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Download opens the new APK in your browser. Android asks before it installs, and your pairings stay.",
            color = Palette.TextSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Skip", Modifier.weight(1f), onClick = onSkip)
            PrimaryButton("Download", Modifier.weight(1f), onClick = onDownload)
        }
    }
}

@Composable
private fun UpdatesCard(updates: Updates.State, version: String, actions: UiActions) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Check for updates", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Once a day, reads the list of releases on GitHub and tells you when a new version is out. Nothing is downloaded until you choose Download.",
                    color = Palette.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            Switch(
                checked = updates.auto,
                onCheckedChange = actions.setAutoUpdates,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            updates.status ?: "This is version $version.",
            color = Palette.TextSecondary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(10.dp))
        SecondaryButton(
            if (updates.isChecking) "Checking…" else "Check now",
            Modifier.fillMaxWidth(),
            onClick = { if (!updates.isChecking) actions.checkUpdates() },
        )
    }
}

@Composable
private fun BackgroundCard(onAllow: () -> Unit, onOpenSettings: () -> Unit) {
    GlassCard {
        Text("Keep the connection alive", color = Palette.Amber, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Android can drop the connection to save battery. Allow DyskLink to run in the " +
                "background. On vivo, Oppo, and Xiaomi, also turn on autostart in the app settings.",
            color = Palette.TextSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Settings", Modifier.weight(1f), onClick = onOpenSettings)
            PrimaryButton("Allow", Modifier.weight(1f), onClick = onAllow)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = Palette.TextFaint,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
private fun PeerCard(peer: Peer, onClick: () -> Unit) {
    GlassCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                LaptopGlyph(if (peer.isConnected) Palette.Mint else Palette.TextFaint, Modifier.size(28.dp))
            }
            Column(Modifier.padding(horizontal = 13.dp).weight(1f)) {
                Text(
                    peer.name,
                    color = Palette.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        !peer.isPaired -> "Tap to pair"
                        peer.isConnected -> "Connected"
                        else -> "Not connected"
                    },
                    color = if (!peer.isPaired) Palette.Mint else Palette.TextSecondary,
                    fontSize = 13.sp,
                )
            }
            val percent = peer.info?.batteryPercent
            if (peer.isConnected && percent != null) {
                Text("$percent%", color = Palette.battery(percent), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DetailScreen(
    peer: Peer,
    transfers: List<Transfer>,
    clipboardEnabled: Boolean,
    clipboardAuto: Boolean,
    isSharingScreen: Boolean,
    onToggleScreen: () -> Unit,
    onBack: () -> Unit,
    onOpenFeature: (Feature) -> Unit,
    onSendFiles: () -> Unit,
    onToggleClipboard: (Boolean) -> Unit,
    onToggleClipboardAuto: (Boolean) -> Unit,
    onUnpair: () -> Unit,
    onOpenTransfer: (Transfer) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "‹ Back",
            color = Palette.TextSecondary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .padding(vertical = 8.dp, horizontal = 4.dp),
        )
        GlassCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                BatteryRing(peer.info?.batteryPercent, peer.info?.isCharging == true)
                Spacer(Modifier.height(16.dp))
                Text(
                    peer.name,
                    color = Palette.TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(peer.info?.model ?: peer.model, color = Palette.TextSecondary, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                if (peer.isConnected) StatusPill("Connected", Palette.Mint)
                else StatusPill("Not connected", Palette.TextFaint)
            }
        }
        if (peer.isConnected) {
            PrimaryButton("Send a file to ${peer.name}", Modifier.fillMaxWidth(), onClick = onSendFiles)
            SecondaryButton(
                if (isSharingScreen) "Stop sharing the screen" else "Share the screen with ${peer.name}",
                Modifier.fillMaxWidth(),
                tint = if (isSharingScreen) Palette.Coral else Palette.TextPrimary,
                onClick = onToggleScreen,
            )
            transfers.forEach { TransferRow(it, onOpenTransfer) }
            Feature.entries.filter { it != Feature.Sharing }.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { item ->
                        GlassCard(Modifier.weight(1f), onClick = { onOpenFeature(item) }) {
                            Text(
                                item.title,
                                color = Palette.TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                minLines = 2,
                            )
                        }
                    }
                }
            }
        }
        GlassCard(onClick = { onOpenFeature(Feature.Sharing) }) {
            Text(Feature.Sharing.title, color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Notifications, SMS, calls, contacts, files, screen, and control. Everything stays off until you turn it on.",
                color = Palette.TextSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        GlassCard(onClick = { onToggleClipboard(!clipboardEnabled) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Automatic clipboard",
                        color = Palette.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Text copied on the Mac goes straight to the phone. From the phone, tap Send clipboard in the DyskLink notification, open this app, or turn on the option below.",
                        color = Palette.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
                Switch(
                    checked = clipboardEnabled,
                    onCheckedChange = onToggleClipboard,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
        }
        if (clipboardEnabled) {
            GlassCard(onClick = { onToggleClipboardAuto(!clipboardAuto) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Send phone copies automatically",
                            color = Palette.TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "Sends text to every paired Mac as soon as you tap Copy. Needs Accessibility, which checks the labels of tapped buttons but doesn't store them. Copied passwords can be sent unless the app marks them private. Icon-only copy buttons may not be noticed.",
                            color = Palette.TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                    Switch(
                        checked = clipboardAuto,
                        onCheckedChange = onToggleClipboardAuto,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoTile("Network", peer.info?.network ?: "Unknown", Modifier.weight(1f))
            InfoTile("System", peer.info?.osVersion ?: "Unknown", Modifier.weight(1f))
        }
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Storage",
                    color = Palette.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                peer.info?.storageFreeBytes?.let {
                    Text("${formatBytes(it)} free", color = Palette.TextSecondary, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            val free = peer.info?.storageFreeBytes
            val total = peer.info?.storageTotalBytes
            if (free != null && total != null) {
                StorageBar(free, total)
                Spacer(Modifier.height(8.dp))
                Text("${formatBytes(total)} total", color = Palette.TextFaint, fontSize = 12.sp)
            } else {
                Text("No data yet", color = Palette.TextFaint, fontSize = 13.sp)
            }
        }
        SecondaryButton("Unpair", Modifier.fillMaxWidth(), tint = Palette.Coral, onClick = onUnpair)
    }
}

@Composable
private fun InfoTile(title: String, value: String, modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Text(title, color = Palette.TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text(value, color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
    }
}

@Composable
private fun Scrim(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.68f))
            // Stops taps from reaching the screen behind.
            .clickable(remember { MutableInteractionSource() }, null) { }
            .imePadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun DialogSurface(content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.Panel)
            .border(1.dp, Palette.Stroke, shape)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
    }
}

@Composable
private fun PairingOverlay(prompt: PairingPrompt, onAccept: () -> Unit, onReject: () -> Unit) {
    Scrim {
        DialogSurface {
            LogoMark(48.dp)
            Spacer(Modifier.height(16.dp))
            Text("Match the code", color = Palette.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "Make sure the code on ${prompt.peerName} is exactly the same.",
                color = Palette.TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            CodeDigits(prompt.code)
            Spacer(Modifier.height(22.dp))
            if (prompt.localAccepted) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), Palette.Mint, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Waiting for confirmation from the Mac…", color = Palette.TextSecondary, fontSize = 14.sp)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Decline", Modifier.weight(1f), onClick = onReject)
                    PrimaryButton("Codes match", Modifier.weight(1.4f), onClick = onAccept)
                }
            }
        }
    }
}

@Composable
private fun ManualSheet(onDismiss: () -> Unit, onSubmit: (String, Int) -> Unit) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(PhoneLinkProtocol.PORT.toString()) }
    val portNumber = port.toIntOrNull()?.takeIf { it in 1..65535 }

    Scrim {
        DialogSurface {
            Text("Connect manually", color = Palette.TextPrimary, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "The IP address is shown in the DyskLink app on the Mac.",
                color = Palette.TextSecondary,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("IP address") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("Port") },
                singleLine = true,
                isError = portNumber == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cancel", Modifier.weight(1f), onClick = onDismiss)
                PrimaryButton("Connect", Modifier.weight(1.3f)) {
                    if (host.isNotBlank() && portNumber != null) onSubmit(host, portNumber)
                }
            }
        }
    }
}
