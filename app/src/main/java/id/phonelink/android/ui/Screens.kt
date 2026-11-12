package id.phonelink.android.ui

import kotlinx.coroutines.launch

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.DesktopMac
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Monitor
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
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
import id.phonelink.android.core.NotchAction
import id.phonelink.android.core.NotchSnapshot
import id.phonelink.android.core.PairingPrompt
import id.phonelink.android.core.Peer
import id.phonelink.android.core.PhoneFeature
import id.phonelink.android.core.PhoneLinkProtocol
import id.phonelink.android.core.Transfer
import id.phonelink.android.core.TransferStatus
import id.phonelink.android.service.ChargeReminder
import id.phonelink.android.service.ChargeSession
import id.phonelink.android.service.Updates

data class UiState(
    val localName: String,
    val peers: List<Peer>,
    val prompt: PairingPrompt?,
    val manualTargets: List<ManualTarget>,
    val lastError: String?,
    val batteryRestricted: Boolean,
    /** Collected only by the screens that show transfers, so progress doesn't recompose the whole app. */
    val transfers: kotlinx.coroutines.flow.StateFlow<List<Transfer>>,
    val commands: Map<String, List<CommandItem>>,
    val commandResult: CommandResult?,
    val clipboardEnabled: Boolean,
    val clipboardAuto: Boolean,
    val phoneEnabled: Set<PhoneFeature>,
    val phoneActive: Set<PhoneFeature>,
    val screenPeer: String?,
    val updates: Updates.State = Updates.State(),
    val appVersion: String = "",
    val notch: Map<String, NotchSnapshot> = emptyMap(),
    val ringingMacs: Set<String> = emptySet(),
    val focusDnd: Boolean = false,
    val dndAccess: Boolean = false,
    val charge: ChargeReminder.Settings = ChargeReminder.Settings(),
    val chargeSessions: List<ChargeSession> = emptyList(),
    /** Goes up when the prayer widget is tapped, to open that screen. */
    val prayerRequest: Int = 0,
    /** A feature to open on the connected Mac, from a widget. */
    val featureRequest: Pair<String, Long>? = null,
    /** Other phones can find and connect to this one. */
    val phonesEnabled: Boolean = true,
    /** What each paired phone may do with this phone's screen and camera. */
    val phoneAccess: Map<String, id.phonelink.android.core.PeerAccess> = emptyMap(),
    /** Other phones' notifications and calls, while they're connected. */
    val mirror: Map<String, id.phonelink.android.core.MirrorFeed> = emptyMap(),
)

data class UiActions(
    val requestPairing: (String) -> Unit,
    /** Sends the code typed on this phone. */
    val submitCode: (String) -> Unit,
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
    val notchAction: (peerId: String, NotchAction) -> Unit = { _, _ -> },
    val ringMac: (peerId: String, Boolean) -> Unit = { _, _ -> },
    val setFocusDnd: (Boolean) -> Unit = {},
    val openDndSettings: () -> Unit = {},
    val clearTransfers: () -> Unit = {},
    val removeTransfer: (Transfer) -> Unit = {},
    val shareTransfer: (Transfer) -> Unit = {},
    val openReceivedFolder: () -> Unit = {},
    val setCharge: (ChargeReminder.Settings) -> Unit = {},
    val clearCharge: () -> Unit = {},
    val openExactAlarms: () -> Unit = {},
    /** [kind] "scan" lets the Mac add a text layer to a scanned PDF. */
    val sendLocalFile: (peerId: String, java.io.File, kind: String?) -> Unit = { _, _, _ -> },
    val sendClipboardText: (String) -> Unit = {},
    val terminalLink: TerminalLink? = null,
    val macScreenLink: MacScreenLink? = null,
    val startCamera: (peerId: String, front: Boolean) -> Unit = { _, _ -> },
    /** Asks Android to record this phone's sound, then sends it to the Mac. */
    val startPhoneSound: (peerId: String) -> Unit = {},
    val setPhonesEnabled: (Boolean) -> Unit = {},
    /** Sends Wake-on-LAN to a paired Mac. Reports whether it went out. */
    val wakeMac: suspend (String) -> Boolean = { false },
    /** Sends a `mac.action` to one Mac, such as `charge.topup.start`. */
    val macAction: (peerId: String, action: String) -> Unit = { _, _ -> },
    /** Another phone's screen and camera, viewed from this one. */
    val phoneShareLink: PhoneShareLink? = null,
    /** Sets what a paired phone may do with this phone's screen and camera. */
    val setPhoneAccess: (peerId: String, id.phonelink.android.core.PeerAccess) -> Unit = { _, _ -> },
    val openAccessibility: () -> Unit = {},
    /** Another phone's notifications, and voice and sound between phones. */
    val phoneRelayLink: PhoneRelayLink? = null,
    /** Links, music, monitor, quiet mode, and Locate for another phone. */
    val phoneRemoteLink: PhoneRemoteLink? = null,
    /** Rings another phone; [flash] also flashes its torch. */
    val ringPhone: (peerId: String, on: Boolean, flash: Boolean) -> Unit = { _, _, _ -> },
    /** SMS through another phone, moving to it, and photo backup (1.6). */
    val phoneMoreLink: PhoneMoreLink? = null,
)

/** A page opened from another phone's page: its screen, camera, notifications, the remote tools, or Permissions. */
internal enum class PhoneView { Screen, Camera, Notifications, Sms, Move, Talk, Sound, Music, Locate, Link, Quiet, Monitor, Backup, Permissions }

@Composable
fun PhoneLinkApp(state: UiState, actions: UiActions) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showManual by remember { mutableStateOf(false) }
    var feature by remember { mutableStateOf<Feature?>(null) }
    var phoneView by remember { mutableStateOf<PhoneView?>(null) }
    // Another phone's screen or camera belongs to the page it was opened from.
    LaunchedEffect(selectedId) { phoneView = null }
    var showPrayer by remember { mutableStateOf(false) }
    var showNetwork by remember { mutableStateOf(false) }
    var showDeck by remember { mutableStateOf(false) }
    var showWebFiles by remember { mutableStateOf(false) }
    var showPhoneTools by remember { mutableStateOf(false) }
    var showBarcodes by remember { mutableStateOf(false) }
    var showDocScan by remember { mutableStateOf(false) }
    var showTodo by remember { mutableStateOf(false) }
    var showWater by remember { mutableStateOf(false) }
    var showPomodoro by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var homeTab by rememberSaveable { mutableStateOf(HomeTab.Devices) }
    /** Set when the history is opened from a Mac's page, to show only that Mac's files at first. */
    var historyPeer by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.prayerRequest) { if (state.prayerRequest > 0) showPrayer = true }
    // A Water or Pomodoro notification was tapped.
    val toolRequest by id.phonelink.android.service.PhoneToolsNav.request.collectAsStateWithLifecycle()
    LaunchedEffect(toolRequest) {
        val tool = toolRequest?.first ?: return@LaunchedEffect
        id.phonelink.android.service.PhoneToolsNav.request.value = null
        showTodo = tool == id.phonelink.android.service.PhoneToolsNav.TODO
        showWater = tool == id.phonelink.android.service.PhoneToolsNav.WATER
        showPomodoro = tool == id.phonelink.android.service.PhoneToolsNav.POMODORO
    }
    LaunchedEffect(state.featureRequest) {
        val name = state.featureRequest?.first ?: return@LaunchedEffect
        val mac = state.peers.firstOrNull { it.isMac && it.isPaired && it.isConnected }
            ?: state.peers.firstOrNull { it.isMac && it.isPaired }
            ?: return@LaunchedEffect
        if (name == "Deck") { showDeck = true; return@LaunchedEffect }
        Feature.entries.firstOrNull { it.name == name }?.let {
            showPrayer = false; showNetwork = false; showHistory = false; showPhoneTools = false; showWebFiles = false
            showBarcodes = false; showDocScan = false; showTodo = false; showWater = false; showPomodoro = false
            selectedId = mac.id
            feature = it
        }
    }
    val selected = state.peers.firstOrNull { it.id == selectedId && it.isPaired }

    AuroraBackground {
        if (showHistory) {
            BackHandler { showHistory = false }
            val transfers by state.transfers.collectAsStateWithLifecycle()
            FileHistoryScreen(
                transfers = transfers,
                peers = state.peers,
                initialPeer = historyPeer,
                onBack = { showHistory = false },
                onOpen = actions.openTransfer,
                onShare = actions.shareTransfer,
                onRemove = actions.removeTransfer,
                onClear = actions.clearTransfers,
                onOpenFolder = actions.openReceivedFolder,
            )
        } else if (showBarcodes) {
            BackHandler { showBarcodes = false }
            ScanScreen(state.peers, null, onBack = { showBarcodes = false }, send = actions.sendInput)
        } else if (showDocScan) {
            BackHandler { showDocScan = false }
            DocScanScreen(
                state.peers,
                null,
                onBack = { showDocScan = false },
                sendFile = actions.sendLocalFile,
                sendText = actions.sendClipboardText,
            )
        } else if (showTodo) {
            BackHandler { showTodo = false }
            TodoScreen(onBack = { showTodo = false })
        } else if (showWater) {
            BackHandler { showWater = false }
            WaterScreen(onBack = { showWater = false })
        } else if (showPomodoro) {
            BackHandler { showPomodoro = false }
            PomodoroScreen(onBack = { showPomodoro = false }, openExactAlarms = actions.openExactAlarms)
        } else if (showPhoneTools) {
            BackHandler { showPhoneTools = false }
            PhoneToolsScreen(onBack = { showPhoneTools = false }, onAllowFiles = { actions.grantPhoneFeature(PhoneFeature.Files) })
        } else if (showWebFiles) {
            BackHandler { showWebFiles = false }
            WebFilesScreen(onBack = { showWebFiles = false }, onAllowFiles = { actions.grantPhoneFeature(PhoneFeature.Files) })
        } else if (showDeck) {
            BackHandler { showDeck = false }
            val mac = state.peers.firstOrNull { it.isMac && it.isPaired && it.isConnected }
            DeckScreen(
                mac?.name,
                mac != null,
                mac?.info?.inputTrusted,
                mac?.let { state.commands[it.id] }.orEmpty(),
                onBack = { showDeck = false },
                sendInput = { event -> mac?.let { actions.sendInput(it.id, event) } },
                sendMedia = { action -> mac?.let { actions.sendMedia(it.id, action) } },
                runCommand = { id -> mac?.let { actions.runCommand(it.id, id) } },
            )
        } else if (showNetwork) {
            BackHandler { showNetwork = false }
            NetworkScreen(onBack = { showNetwork = false })
        } else if (showPrayer) {
            BackHandler { showPrayer = false }
            PrayerScreen(onBack = { showPrayer = false }, openExactAlarms = actions.openExactAlarms)
        } else if (selected != null && phoneView != null) {
            val closeView = { phoneView = null }
            when (val view = phoneView!!) {
                PhoneView.Screen -> PhoneScreenScreen(selected.id, selected.name, selected.isConnected, actions.phoneShareLink, closeView)
                PhoneView.Camera -> RemoteCameraScreen(selected.id, selected.name, selected.isConnected, actions.phoneShareLink, closeView)
                PhoneView.Notifications -> PhoneNotificationsScreen(selected, state.mirror[selected.id], actions.phoneRelayLink, closeView)
                PhoneView.Sms -> PhoneSmsScreen(selected, actions.phoneMoreLink, closeView)
                PhoneView.Move -> PhoneMoveScreen(selected, actions.phoneMoreLink, closeView)
                PhoneView.Permissions -> PhonePermissionsScreen(
                    selected,
                    access = state.phoneAccess[selected.id] ?: id.phonelink.android.core.PeerAccess(),
                    onSetAccess = { actions.setPhoneAccess(selected.id, it) },
                    onOpenAccessibility = actions.openAccessibility,
                    onGrant = actions.grantPhoneFeature,
                    clipboardEnabled = state.clipboardEnabled,
                    clipboardAuto = state.clipboardAuto,
                    onToggleClipboard = actions.setClipboardEnabled,
                    onToggleClipboardAuto = actions.setClipboardAuto,
                    onBack = closeView,
                )
                else -> PhoneToolScreen(
                    selected,
                    view,
                    remoteLink = actions.phoneRemoteLink,
                    isRinging = selected.id in state.ringingMacs,
                    onRingFlash = { on -> actions.ringPhone(selected.id, on, true) },
                    onStartSound = { actions.startPhoneSound(selected.id) },
                    onBack = closeView,
                )
            }
        } else if (selected != null) {
            val closeFeature = { feature = null }
            when (feature) {
                null -> {
                    BackHandler { selectedId = null }
                    val allTransfers by state.transfers.collectAsStateWithLifecycle()
                    val transfers = remember(allTransfers, selected.id) { allTransfers.filter { it.peerId == selected.id } }
                    DetailScreen(
                        selected,
                        transfers = transfers,
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
                        onShareTransfer = actions.shareTransfer,
                        onRemoveTransfer = actions.removeTransfer,
                        isRinging = selected.id in state.ringingMacs,
                        onRing = { actions.ringMac(selected.id, selected.id !in state.ringingMacs) },
                        onSendClipboard = actions.sendClipboardText,
                        onWake = { actions.wakeMac(selected.id) },
                        onMacAction = { actions.macAction(selected.id, it) },
                        onSeeAllFiles = {
                            historyPeer = selected.id
                            showHistory = true
                        },
                        access = state.phoneAccess[selected.id] ?: id.phonelink.android.core.PeerAccess(),
                        mirrorFeed = state.mirror[selected.id],
                        relayLink = actions.phoneRelayLink,
                        remoteLink = actions.phoneRemoteLink,
                        onRingFlash = { on -> actions.ringPhone(selected.id, on, true) },
                        onOpenPhoneView = { phoneView = it },
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
                    kind = if (selected.isMac) "Mac" else "PC",
                ) { actions.runCommand(selected.id, it) }
                Feature.Notch -> NotchScreen(
                    selected.name,
                    state.notch[selected.id],
                    state.focusDnd,
                    state.dndAccess,
                    closeFeature,
                    send = { actions.notchAction(selected.id, it) },
                    setFocusDnd = actions.setFocusDnd,
                    openDndSettings = actions.openDndSettings,
                    kind = if (selected.isComputer && !selected.isMac) "PC" else "Mac",
                )
                Feature.Deck -> DeckScreen(
                    selected.name,
                    selected.isConnected,
                    selected.info?.inputTrusted,
                    state.commands[selected.id].orEmpty(),
                    closeFeature,
                    sendInput = { actions.sendInput(selected.id, it) },
                    sendMedia = { actions.sendMedia(selected.id, it) },
                    runCommand = { actions.runCommand(selected.id, it) },
                    kind = if (selected.isComputer && !selected.isMac) "PC" else "Mac",
                )
                Feature.Scan -> ScanScreen(state.peers, selected.id, closeFeature, send = actions.sendInput)
                Feature.Terminal -> TerminalScreen(selected.id, selected.name, selected.isConnected, actions.terminalLink, closeFeature)
                Feature.Sound -> SoundScreen(selected.id, selected.name, selected.isConnected, selected.isMac, closeFeature, canListen = selected.canHear) { actions.startPhoneSound(selected.id) }
                Feature.Camera -> CameraScreen(selected.id, selected.name, selected.isConnected, actions.startCamera, closeFeature)
                Feature.MacScreen -> MacScreenScreen(selected.id, selected.name, selected.isConnected, selected.info?.inputTrusted, actions.macScreenLink, closeFeature, isMac = selected.isMac)
                Feature.SecondDisplay -> MacScreenScreen(selected.id, selected.name, selected.isConnected, selected.info?.inputTrusted, actions.macScreenLink, closeFeature, extend = true, isMac = selected.isMac)
                Feature.DocScan -> DocScanScreen(
                    state.peers,
                    selected.id,
                    closeFeature,
                    sendFile = actions.sendLocalFile,
                    sendText = actions.sendClipboardText,
                )
                Feature.Dictation -> DictationScreen(selected.name, selected.isConnected, closeFeature, kind = if (selected.isComputer && !selected.isMac) "PC" else "Mac") { actions.sendInput(selected.id, it) }
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
                onOpenPrayer = { showPrayer = true },
                onOpenNetwork = { showNetwork = true },
                onOpenDeck = { showDeck = true },
                onOpenWebFiles = { showWebFiles = true },
                onOpenPhoneTools = { showPhoneTools = true },
                onOpenBarcodes = { showBarcodes = true },
                onOpenDocScan = { showDocScan = true },
                onOpenTodo = { showTodo = true },
                onOpenWater = { showWater = true },
                onOpenPomodoro = { showPomodoro = true },
                tab = homeTab,
                onTab = { homeTab = it },
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
            PairingOverlay(prompt, actions.submitCode, actions.rejectPairing)
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
    onOpenPrayer: () -> Unit,
    onOpenNetwork: () -> Unit,
    onOpenDeck: () -> Unit,
    onOpenWebFiles: () -> Unit,
    onOpenPhoneTools: () -> Unit,
    onOpenBarcodes: () -> Unit,
    onOpenDocScan: () -> Unit,
    onOpenTodo: () -> Unit,
    onOpenWater: () -> Unit,
    onOpenPomodoro: () -> Unit,
    tab: HomeTab,
    onTab: (HomeTab) -> Unit,
    actions: UiActions,
) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
    // Each tab keeps its own scroll position.
    val scroll = if (tab == HomeTab.Devices) rememberScrollState() else rememberScrollState()
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(scroll)
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

        if (tab == HomeTab.Tools) {
            SectionLabel("TOOLS ON THIS PHONE")
            PrayerHomeCard(onOpenPrayer)
            Spacer(Modifier.height(12.dp))
            NetworkHomeCard(onOpenNetwork)
            Spacer(Modifier.height(12.dp))
            GlassCard(onClick = onOpenDeck) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlatIcon(FlatIcons.deckGrid, Palette.Coral)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Stream Deck", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Shortcuts and media on your Mac, and scenes, streaming, and recording in OBS.",
                            color = Palette.TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            BarcodeHomeCard(onOpenBarcodes)
            Spacer(Modifier.height(12.dp))
            DocScanHomeCard(onOpenDocScan)
            Spacer(Modifier.height(12.dp))
            TodoHomeCard(onOpenTodo)
            Spacer(Modifier.height(12.dp))
            WaterHomeCard(onOpenWater)
            Spacer(Modifier.height(12.dp))
            PomodoroHomeCard(onOpenPomodoro)
            Spacer(Modifier.height(12.dp))
            PhoneToolsHomeCard(onOpenPhoneTools)
            Spacer(Modifier.height(12.dp))
            WebFilesHomeCard(onOpenWebFiles)
            Spacer(Modifier.height(12.dp))
            ChargeCard(state.charge, state.chargeSessions, actions)
            Spacer(Modifier.height(24.dp))
            SectionLabel("APP")
            UpdatesCard(state.updates, state.appVersion, actions)
            Spacer(Modifier.height(12.dp))
            SupportCard()
            Spacer(Modifier.height(16.dp))
        } else {
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

        GlassCard {
            SettingRow(
                "Connect with other phones",
                "Other phones with DyskLink on this Wi-Fi can pair with this one to send files, share the clipboard, ring, and see the battery. " +
                    "SMS, notifications, and control stay with your Macs. Pairing still needs the same code on both phones.",
                state.phonesEnabled,
                actions.setPhonesEnabled,
            )
        }
        Spacer(Modifier.height(20.dp))

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
        Spacer(Modifier.height(16.dp))
        }
    }
    HomeTabBar(tab, state.peers.count { it.isConnected }, state.updates.available != null, onTab)
    }
}

enum class HomeTab(val label: String) { Devices("Devices"), Tools("Tools") }

/** Two tabs at the bottom: the Macs on the left, the phone's own tools on the right. */
@Composable
private fun HomeTabBar(tab: HomeTab, connected: Int, updateReady: Boolean, onTab: (HomeTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Ink.copy(alpha = 0.92f))
            .drawBehind { drawLine(Palette.Stroke, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HomeTab.entries.forEach { option ->
            val on = option == tab
            Row(
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (on) Palette.Mint.copy(alpha = 0.14f) else Color.Transparent)
                    .border(1.dp, if (on) Palette.Mint.copy(alpha = 0.45f) else Color.Transparent, RoundedCornerShape(16.dp))
                    .clickable { onTab(option) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (option == HomeTab.Devices) Icons.Default.Home else Icons.Default.Build,
                    contentDescription = null,
                    tint = if (on) Palette.Mint else Palette.TextFaint,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    option.label,
                    color = if (on) Palette.TextPrimary else Palette.TextSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                val badge = when (option) {
                    HomeTab.Devices -> connected.takeIf { it > 0 }?.toString()
                    HomeTab.Tools -> if (updateReady) "1" else null
                }
                badge?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        it,
                        color = Palette.Ink,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (option == HomeTab.Devices) Palette.Mint else Palette.Amber)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChargeCard(settings: ChargeReminder.Settings, sessions: List<ChargeSession>, actions: UiActions) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Charge reminder", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Tells you once per charge when the battery reaches ${settings.limit}%, so you can unplug. Android doesn't let apps stop charging, so this only reminds.",
                    color = Palette.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            Switch(
                checked = settings.on,
                onCheckedChange = { actions.setCharge(settings.copy(on = it)) },
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        if (settings.on) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChargeReminder.limitChoices.forEach { limit ->
                    val chosen = limit == settings.limit
                    Text(
                        "$limit%",
                        color = if (chosen) Palette.Ink else Palette.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (chosen) Palette.Mint else Palette.PanelRaised)
                            .clickable { actions.setCharge(settings.copy(limit = limit)) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (sessions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("RECENT CHARGES", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            sessions.take(5).forEach { session ->
                Spacer(Modifier.height(6.dp))
                Row {
                    Text(
                        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                            .format(java.util.Date(session.start)),
                        color = Palette.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    val end = session.end
                    Text(
                        if (end == null) "${session.startLevel}% → charging"
                        else "${session.startLevel}% → ${session.endLevel}% in ${((end - session.start) / 60_000).coerceAtLeast(1)} min",
                        color = if (end == null) Palette.Mint else Palette.TextPrimary,
                        fontSize = 12.sp,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Clear",
                color = Palette.TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).clickable(onClick = actions.clearCharge).padding(vertical = 4.dp, horizontal = 2.dp),
            )
        }
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

/** Same as the Support card in the Mac app's Advanced page. */
@Composable
private fun SupportCard() {
    val context = androidx.compose.ui.platform.LocalContext.current
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(androidx.compose.material.icons.Icons.Rounded.Favorite, contentDescription = null, tint = Palette.Coral, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Support DyskLink", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Donations help keep this project going.", color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Spacer(Modifier.width(10.dp))
            SecondaryButton("Buy me a coffee") {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(DONATION_URL))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }
}

private const val DONATION_URL = "https://buymeacoffee.com/fannydevz"

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
internal fun SectionLabel(text: String) {
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
                if (peer.isPhone) {
                    Icon(FlatIcons.phone, contentDescription = null, tint = if (peer.isConnected) Palette.Mint else Palette.TextFaint, modifier = Modifier.size(26.dp))
                } else {
                    LaptopGlyph(if (peer.isConnected) Palette.Mint else Palette.TextFaint, Modifier.size(28.dp))
                }
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

/** The Mac's charge control: what it's doing, with buttons for the limit and Top Up. */
@Composable
private fun ChargeRow(charge: id.phonelink.android.core.MacCharge, onAction: (String) -> Unit) {
    Spacer(Modifier.height(14.dp))
    Text("CHARGING", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
    Spacer(Modifier.height(4.dp))
    Text(charge.status, color = Palette.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ChargeChip(
            if (charge.limiting) "Limit ${charge.limit ?: 80}%: on" else "Limit: off",
            charge.limiting, Modifier.weight(1f),
        ) { onAction(if (charge.limiting) "charge.limit.off" else "charge.limit.on") }
        ChargeChip(if (charge.topUp) "Top Up: on" else "Top Up to 100%", charge.topUp, Modifier.weight(1f)) {
            onAction(if (charge.topUp) "charge.topup.stop" else "charge.topup.start")
        }
    }
}

@Composable
private fun ChargeChip(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(if (on) Palette.Mint else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) Palette.Ink else Palette.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Wakes a sleeping Mac with Wake-on-LAN, then waits for it to connect. */
@Composable
private fun WakeRow(peer: Peer, onWake: suspend () -> Boolean) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var status by remember(peer.id) { mutableStateOf<String?>(null) }
    var busy by remember(peer.id) { mutableStateOf(false) }
    Spacer(Modifier.height(12.dp))
    PrimaryButton(if (busy) "Waking…" else "Wake Mac", Modifier.fillMaxWidth()) {
        if (busy) return@PrimaryButton
        busy = true
        scope.launch {
            val sent = onWake()
            status = if (sent) "Sent. A sleeping Mac usually connects within 10–30 seconds." else "Couldn't send. Check that this phone is on Wi-Fi."
            kotlinx.coroutines.delay(4000)
            busy = false
        }
    }
    val note = status ?: when (peer.wakeEnabled) {
        false -> "\"Wake for network access\" is off on the Mac (System Settings › Battery › Options), so it may not wake."
        else -> "Works when the Mac is asleep on power and on the same network. A Mac that is shut down can't be woken."
    }
    Spacer(Modifier.height(6.dp))
    Text(note, color = Palette.TextFaint, fontSize = 12.sp, lineHeight = 17.sp)
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
    onShareTransfer: (Transfer) -> Unit = {},
    onRemoveTransfer: (Transfer) -> Unit = {},
    isRinging: Boolean = false,
    onRing: () -> Unit = {},
    onSeeAllFiles: () -> Unit = {},
    onSendClipboard: (String) -> Unit = {},
    onWake: suspend () -> Boolean = { false },
    onMacAction: (String) -> Unit = {},
    access: id.phonelink.android.core.PeerAccess = id.phonelink.android.core.PeerAccess(),
    mirrorFeed: id.phonelink.android.core.MirrorFeed? = null,
    relayLink: PhoneRelayLink? = null,
    remoteLink: PhoneRemoteLink? = null,
    onRingFlash: (Boolean) -> Unit = {},
    onOpenPhoneView: (PhoneView) -> Unit = {},
) {
    val info = peer.info
    val context = androidx.compose.ui.platform.LocalContext.current
    val kind = when { peer.isPhone -> "phone"; peer.isMac -> "Mac"; else -> "PC" }
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            "‹ Devices",
            color = Palette.TextSecondary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onBack)
                .padding(vertical = 8.dp, horizontal = 4.dp),
        )
        Spacer(Modifier.height(8.dp))

        // Who this Mac is, at a glance.
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiniBattery(info?.batteryPercent, info?.isCharging == true)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(peer.name, color = Palette.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(info?.model ?: peer.model, color = Palette.TextSecondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    if (peer.isConnected) StatusPill(if (peer.viaUsb) "Connected over USB" else "Connected", Palette.Mint) else StatusPill("Not connected", Palette.TextFaint)
                }
            }
            if (!peer.isConnected) {
                Spacer(Modifier.height(12.dp))
                Text(
                    if (peer.isPhone) "Open DyskLink on ${peer.name} and keep both on the same Wi-Fi. It connects by itself."
                    else "Open DyskLink on the $kind and keep both on the same Wi-Fi. Controls come back as soon as it connects.",
                    color = Palette.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                if (peer.isMac && peer.canWake) WakeRow(peer, onWake)
            }
            val charge = info?.charge
            if (peer.isConnected && peer.isMac && charge != null) ChargeRow(charge, onMacAction)
        }

        // Another phone: quick actions, one grid of tools, files, and Permissions.
        if (peer.isPhone) {
            PhonePeerActions(
                peer,
                isRinging = isRinging,
                mirrorFeed = mirrorFeed,
                relayLink = relayLink,
                remoteLink = remoteLink,
                onSendFiles = onSendFiles,
                onRing = onRing,
                onRingFlash = onRingFlash,
                onSendClipboard = onSendClipboard,
                onOpen = onOpenPhoneView,
            )
            if (peer.isConnected) {
                Spacer(Modifier.height(16.dp))
                DeviceFilesCard(
                    transfers = transfers,
                    peerName = peer.name,
                    onSeeAll = onSeeAllFiles,
                    onOpen = onOpenTransfer,
                    onShare = onShareTransfer,
                    onRemove = onRemoveTransfer,
                    title = "FILES",
                    limit = 2,
                )
            }
            Spacer(Modifier.height(16.dp))
            PhonePermissionsRow(peer, access) { onOpenPhoneView(PhoneView.Permissions) }
        }

        // Windows or Linux PCs share files, the clipboard, and ringing. Macs have more, below.
        if (peer.isConnected && peer.isComputer && !peer.isMac) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(FlatIcons.sendFile, "Send file", "To this $kind", Palette.Mint, Modifier.weight(1f), onClick = onSendFiles)
                QuickAction(FlatIcons.commands, "Clipboard", "Send copied text", Palette.Sky, Modifier.weight(1f)) {
                    val text = context.getSystemService(android.content.ClipboardManager::class.java)
                        .primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (text.isNullOrEmpty()) {
                        android.widget.Toast.makeText(context, "Copy some text first", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        onSendClipboard(text)
                    }
                }
                QuickAction(
                    if (isRinging) FlatIcons.ringOff else FlatIcons.ring,
                    if (isRinging) "Stop ringing" else "Find $kind",
                    if (isRinging) "Ringing now" else "Play a sound",
                    if (isRinging) Palette.Coral else Palette.Amber,
                    Modifier.weight(1f),
                    active = isRinging,
                    onClick = onRing,
                )
            }
            if (peer.isComputer) {
                Spacer(Modifier.height(24.dp))
                SectionLabel("CONTROL THE PC")
                pcFeatures(peer).chunked(2).forEach { row ->
                    Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { item -> FeatureTile(item, Modifier.weight(1f), item.pcTitle, item.pcBlurb(peer)) { onOpenFeature(item) } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (peer.info?.inputTrusted == false) {
                    Text(
                        "The touchpad and keyboard are off on this PC. Turn on \"Phones can control this PC\" in DyskLink there. On Linux they need an X11 session.",
                        color = Palette.Amber, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            DeviceFilesCard(
                transfers = transfers,
                peerName = peer.name,
                onSeeAll = onSeeAllFiles,
                onOpen = onOpenTransfer,
                onShare = onShareTransfer,
                onRemove = onRemoveTransfer,
            )
        }

        if (peer.isConnected && peer.isMac) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(FlatIcons.sendFile, "Send file", "To the Mac", Palette.Mint, Modifier.weight(1f), onClick = onSendFiles)
                QuickAction(
                    if (isSharingScreen) FlatIcons.stopShare else FlatIcons.shareScreen,
                    if (isSharingScreen) "Stop sharing" else "Share screen",
                    if (isSharingScreen) "Sharing now" else "Phone to Mac",
                    if (isSharingScreen) Palette.Coral else Palette.Sky,
                    Modifier.weight(1f),
                    active = isSharingScreen,
                    onClick = onToggleScreen,
                )
                QuickAction(
                    if (isRinging) FlatIcons.ringOff else FlatIcons.ring,
                    if (isRinging) "Stop ringing" else "Find Mac",
                    if (isRinging) "Ringing now" else "Play a sound",
                    if (isRinging) Palette.Coral else Palette.Amber,
                    Modifier.weight(1f),
                    active = isRinging,
                    onClick = onRing,
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel("CONTROL THE MAC")
            Feature.entries.filter { it != Feature.Sharing }.chunked(2).forEach { row ->
                Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { item -> FeatureTile(item, Modifier.weight(1f)) { onOpenFeature(item) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(14.dp))
            DeviceFilesCard(
                transfers = transfers,
                peerName = peer.name,
                onSeeAll = onSeeAllFiles,
                onOpen = onOpenTransfer,
                onShare = onShareTransfer,
                onRemove = onRemoveTransfer,
            )
        }

        if (!peer.isPhone) {
        Spacer(Modifier.height(24.dp))
        SectionLabel("CLIPBOARD")
        GlassCard {
            SettingRow(
                "Automatic clipboard",
                if (peer.isPhone) "Text copied on ${peer.name} comes straight to this phone, when it sends it with Clipboard or Send clipboard."
                else "Text copied on the $kind comes straight to this phone. Send from the phone with Send clipboard in the DyskLink notification.",
                clipboardEnabled,
                onToggleClipboard,
            )
            if (clipboardEnabled) {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(Palette.Stroke))
                SettingRow(
                    "Send phone copies automatically",
                    "Sends text to every paired device as soon as you tap Copy. Uses Accessibility to notice Copy buttons, without storing what's on screen. Passwords can be sent unless the app marks them private.",
                    clipboardAuto,
                    onToggleClipboardAuto,
                )
            }
        }

        }

        if (peer.isMac) {
        Spacer(Modifier.height(24.dp))
        SectionLabel("PHOTOS")
        PhotoSyncCard(peer)
        }

        // Notifications, SMS and calls work with every computer, not only a Mac.
        if (peer.isComputer) {
        Spacer(Modifier.height(24.dp))
        SectionLabel("THIS PHONE")
        GlassCard(onClick = { onOpenFeature(Feature.Sharing) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Feature.Sharing.title, color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Notifications, SMS, calls, contacts, files, screen, and control. Everything stays off until you turn it on.",
                        color = Palette.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                }
                Text("›", color = Palette.TextFaint, fontSize = 22.sp, modifier = Modifier.padding(start = 10.dp))
            }
        }

        }

        if (peer.isPhone) {
            Spacer(Modifier.height(12.dp))
            var aboutOpen by rememberSaveable(peer.id) { mutableStateOf(false) }
            GlassCard(onClick = { aboutOpen = !aboutOpen }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("About this phone", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(if (aboutOpen) "⌃" else "⌄", color = Palette.TextFaint, fontSize = 18.sp)
                }
                if (aboutOpen) {
                    Spacer(Modifier.height(8.dp))
                    AboutRows(info, peer.model)
                }
            }
        } else {
        Spacer(Modifier.height(24.dp))
        SectionLabel("ABOUT THIS ${kind.uppercase()}")
        GlassCard {
            AboutRows(info, null)
        }

        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Unpair this $kind",
            color = Palette.Coral,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clip(CircleShape)
                .clickable(onClick = onUnpair)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun AboutRows(info: id.phonelink.android.core.DeviceInfo?, model: String?) {
    if (model != null) DetailRow("Model", info?.model ?: model)
    DetailRow("System", info?.osVersion ?: "Unknown")
    DetailRow("Network", info?.network ?: "Unknown")
    val free = info?.storageFreeBytes
    val total = info?.storageTotalBytes
    DetailRow("Storage", if (free != null && total != null) "${formatBytes(free)} free of ${formatBytes(total)}" else "No data yet")
    if (free != null && total != null) {
        Spacer(Modifier.height(8.dp))
        StorageBar(free, total)
    }
}

private val Feature.icon: androidx.compose.ui.graphics.vector.ImageVector
    get() = when (this) {
        Feature.Touchpad -> FlatIcons.touchpad
        Feature.Media -> FlatIcons.media
        Feature.Presentation -> FlatIcons.presentation
        Feature.Commands -> FlatIcons.commands
        Feature.Notch -> FlatIcons.notch
        Feature.Deck -> FlatIcons.deckGrid
        Feature.Scan -> FlatIcons.scan
        Feature.DocScan -> androidx.compose.material.icons.Icons.Rounded.DocumentScanner
        Feature.Terminal -> androidx.compose.material.icons.Icons.Rounded.Terminal
        Feature.MacScreen -> androidx.compose.material.icons.Icons.Rounded.DesktopMac
        Feature.Camera -> androidx.compose.material.icons.Icons.Rounded.Videocam
        Feature.Sound -> androidx.compose.material.icons.Icons.Rounded.Headset
        Feature.SecondDisplay -> androidx.compose.material.icons.Icons.Rounded.Monitor
        Feature.Dictation -> FlatIcons.mic
        Feature.Sharing -> FlatIcons.phone
    }

private val Feature.tint: Color
    get() = when (this) {
        Feature.Touchpad, Feature.Commands, Feature.Terminal -> Palette.Sky
        Feature.MacScreen -> Palette.Violet
        Feature.Camera -> Palette.Coral
        Feature.Sound -> Palette.Mint
        Feature.SecondDisplay -> Palette.Violet
        Feature.Media, Feature.Dictation -> Palette.Violet
        Feature.Presentation, Feature.Scan, Feature.DocScan -> Palette.Amber
        Feature.Notch, Feature.Sharing -> Palette.Mint
        Feature.Deck -> Palette.Coral
    }

private val Feature.blurb: String
    get() = when (this) {
        Feature.Touchpad -> "Mouse and typing"
        Feature.Media -> "Play, skip, volume"
        Feature.Presentation -> "Next and back slides"
        Feature.Commands -> "Run your Mac shortcuts"
        Feature.Notch -> "From the Mac notch"
        Feature.Deck -> "Buttons, OBS too"
        Feature.Scan -> "Type codes on the Mac"
        Feature.DocScan -> "Paper to PDF on the Mac"
        Feature.Terminal -> "A shell on the Mac"
        Feature.MacScreen -> "See and tap the Mac's screen"
        Feature.Camera -> "Use this phone as a webcam"
        Feature.Sound -> "Hear the Mac here, or this phone there"
        Feature.SecondDisplay -> "Extend the Mac onto this phone"
        Feature.Dictation -> "Speak, the Mac types"
        Feature.Sharing -> ""
    }

@Composable
private fun FeatureTile(feature: Feature, modifier: Modifier, title: String = feature.title, blurb: String = feature.blurb, onClick: () -> Unit) {
    GlassCard(modifier, onClick = onClick) {
        FlatIcon(feature.icon, feature.tint)
        Spacer(Modifier.height(10.dp))
        Text(title, color = Palette.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, minLines = 2, maxLines = 2, lineHeight = 18.sp, overflow = TextOverflow.Ellipsis)
        Text(blurb, color = Palette.TextFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, detail: String, color: Color, modifier: Modifier,
    active: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .height(96.dp)
            .clip(shape)
            .background(color.copy(alpha = if (active) 0.28f else 0.12f))
            .border(1.dp, color.copy(alpha = if (active) 0.8f else 0.3f), shape)
            .then(Modifier.combinedClickable(onLongClick = onLongClick, onClick = onClick))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, color = Palette.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, color = if (active) color else Palette.TextFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Turns photo sync on for one Mac or phone, asking to read photos first. */
@Composable
internal fun PhotoSyncCard(peer: Peer) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var on by remember(peer.id) { mutableStateOf(id.phonelink.android.service.PhotoSync.isOn(context, peer.id)) }
    var canRead by remember { mutableStateOf(id.phonelink.android.service.PhotoSync.canRead(context)) }
    val sent by id.phonelink.android.service.PhotoSync.sent.collectAsState()
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        canRead = granted || id.phonelink.android.service.PhotoSync.canRead(context)
        if (canRead) {
            on = true
            id.phonelink.android.service.PhotoSync.setOn(context, peer.id, true)
        }
    }
    val stopped by id.phonelink.android.service.PhotoSync.stopped.collectAsState()
    GlassCard {
        SettingRow(
            if (peer.isPhone) "Send new photos to ${peer.name}" else "Send new photos",
            "Photos and screenshots taken from now on go to Pictures › DyskLink on ${peer.name} whenever it's connected. Photos already on this phone aren't sent." +
                if (peer.isPhone) " ${peer.name} must allow photo backup from this phone." else "",
            on && canRead,
        ) { want ->
            if (want && !canRead) {
                ask.launch(id.phonelink.android.service.PhotoSync.permission)
            } else {
                on = want
                id.phonelink.android.service.PhotoSync.setOn(context, peer.id, want)
            }
        }
        if (on && canRead) {
            var videos by remember(peer.id) { mutableStateOf(id.phonelink.android.service.PhotoSync.sendsVideos(context, peer.id)) }
            val askVideos = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted || id.phonelink.android.service.PhotoSync.canReadVideos(context)) {
                    videos = true
                    id.phonelink.android.service.PhotoSync.setSendsVideos(context, peer.id, true)
                }
            }
            Spacer(Modifier.height(12.dp))
            SettingRow("Include videos", "New videos go too. They can be large, so they take longer.", videos) { want ->
                if (want && !id.phonelink.android.service.PhotoSync.canReadVideos(context)) {
                    askVideos.launch(id.phonelink.android.service.PhotoSync.videoPermission)
                } else {
                    videos = want
                    id.phonelink.android.service.PhotoSync.setSendsVideos(context, peer.id, want)
                }
            }
        }
        if (on && canRead) {
            var wifiOnly by remember(peer.id) { mutableStateOf(id.phonelink.android.service.PhotoSync.wifiOnly(context, peer.id)) }
            Spacer(Modifier.height(12.dp))
            SettingRow("Only on Wi-Fi", "Waits for Wi-Fi without a data limit, so mobile data and phone hotspots aren't used.", wifiOnly) { want ->
                wifiOnly = want
                id.phonelink.android.service.PhotoSync.setWifiOnly(context, peer.id, want)
            }
            var charging by remember(peer.id) { mutableStateOf(id.phonelink.android.service.PhotoSync.chargingOnly(context, peer.id)) }
            Spacer(Modifier.height(12.dp))
            SettingRow("Only while charging", "Waits until this phone is plugged in, to save battery.", charging) { want ->
                charging = want
                id.phonelink.android.service.PhotoSync.setChargingOnly(context, peer.id, want)
            }
            Spacer(Modifier.height(12.dp))
            EarlierPhotos(peer)
        }
        stopped[peer.id]?.takeIf { peer.isPhone }?.let { why ->
            Spacer(Modifier.height(8.dp))
            Text(why, color = Palette.Amber, fontSize = 12.sp, lineHeight = 17.sp)
        }
        val count = sent[peer.id] ?: 0
        if (on && canRead && count > 0) {
            Spacer(Modifier.height(8.dp))
            Text("$count photo${if (count == 1) "" else "s"} sent since DyskLink started", color = Palette.TextFaint, fontSize = 12.sp)
        } else if (on && !canRead) {
            Spacer(Modifier.height(8.dp))
            Text("Allow DyskLink to read photos to send them.", color = Palette.Amber, fontSize = 12.sp)
        }
    }
}

/** Sends photos taken before sync was turned on, from a chosen time. */
@Composable
private fun EarlierPhotos(peer: Peer) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val progress by id.phonelink.android.service.PhotoSync.earlier.collectAsState()
    val running = progress.containsKey(peer.id) || id.phonelink.android.service.PhotoSync.isSendingEarlier(context, peer.id)
    Text("Earlier photos", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    if (running) {
        val done = progress[peer.id] ?: 0
        Text(
            "Sending earlier photos, oldest first" + (if (done > 0) ": $done sent" else "") + ". New photos go first.",
            color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Stop", color = Palette.Coral, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { id.phonelink.android.service.PhotoSync.stopEarlier(context, peer.id) }.padding(vertical = 4.dp),
        )
    } else {
        Text("Send photos taken before sync was on. Copies already on ${if (peer.isPhone) peer.name else "the Mac"} are skipped.", color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Last 7 days" to 7, "Last 30 days" to 30, "All" to null).forEach { (label, days) ->
                Text(
                    label, color = Palette.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .background(Palette.PanelRaised, RoundedCornerShape(50))
                        .clickable { id.phonelink.android.service.PhotoSync.sendEarlier(context, peer.id, days) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
internal fun SettingRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = Palette.TextSecondary, fontSize = 13.sp, modifier = Modifier.width(80.dp))
        Text(value, color = Palette.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

/** A small battery ring for the Mac's card. */
@Composable
private fun MiniBattery(percent: Int?, charging: Boolean) {
    val color = percent?.let(Palette::battery) ?: Palette.TextFaint
    Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(76.dp)) {
            val stroke = 7.dp.toPx()
            val arc = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)
            drawArc(Color.White.copy(alpha = 0.07f), 0f, 360f, false, topLeft, arc, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            drawArc(
                color, -90f, 360f * (percent ?: 0) / 100f, false, topLeft, arc,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(percent?.let { "$it%" } ?: "–", color = Palette.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (charging) Text("Charging", color = Palette.TextFaint, fontSize = 9.sp)
        }
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
private fun PairingOverlay(prompt: PairingPrompt, onSubmit: (String) -> Unit, onReject: () -> Unit) {
    Scrim {
        DialogSurface {
            LogoMark(48.dp)
            Spacer(Modifier.height(16.dp))
            when {
                prompt.outdated -> {
                    PairingTitle("Can't pair yet", prompt.error.orEmpty())
                    Spacer(Modifier.height(20.dp))
                    SecondaryButton("Close", Modifier.fillMaxWidth(), onClick = onReject)
                }
                prompt.showsCode -> {
                    PairingTitle("Pair with ${prompt.peerName}", "Type this code on ${prompt.peerName}. It works only for this pairing.")
                    Spacer(Modifier.height(20.dp))
                    CodeDigits(prompt.code)
                    prompt.error?.let { PairingError(it) }
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), Palette.Mint, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Waiting for the code…", color = Palette.TextSecondary, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    SecondaryButton("Cancel", Modifier.fillMaxWidth(), onClick = onReject)
                }
                else -> {
                    PairingTitle("Enter the code", "Type the 6-digit code shown on ${prompt.peerName}.")
                    Spacer(Modifier.height(18.dp))
                    // A wrong code clears the box for the next try.
                    var entry by remember(prompt.peerId, prompt.error) { mutableStateOf("") }
                    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                    LaunchedEffect(prompt.peerId, prompt.error) { runCatching { focus.requestFocus() } }
                    OutlinedTextField(
                        value = entry,
                        onValueChange = { value ->
                            entry = value.filter(Char::isDigit).take(6)
                            if (entry.length == 6 && !prompt.localAccepted) onSubmit(entry)
                        },
                        enabled = !prompt.localAccepted,
                        singleLine = true,
                        placeholder = { Text("000000", fontFamily = FontFamily.Monospace) },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Palette.TextPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace, letterSpacing = 6.sp, textAlign = TextAlign.Center,
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                    prompt.error?.let { PairingError(it) }
                    Spacer(Modifier.height(18.dp))
                    if (prompt.localAccepted) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), Palette.Mint, strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Checking the code…", color = Palette.TextSecondary, fontSize = 14.sp)
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SecondaryButton("Cancel", Modifier.weight(1f), onClick = onReject)
                            PrimaryButton("Pair", Modifier.weight(1.3f)) { if (entry.length == 6) onSubmit(entry) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PairingTitle(title: String, detail: String) {
    Text(title, color = Palette.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Spacer(Modifier.height(6.dp))
    Text(detail, color = Palette.TextSecondary, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
}

@Composable
private fun PairingError(text: String) {
    Spacer(Modifier.height(12.dp))
    Text(text, color = Palette.Coral, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp, textAlign = TextAlign.Center)
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

/** What a Windows or Linux PC can do for now. */
private val PC_FEATURES = listOf(Feature.Touchpad, Feature.Media, Feature.Presentation, Feature.Sound)

/**
 * What a Windows or Linux PC offers: Commands, Terminal, its screen, the second display, the deck, its tools, scanning
 * codes and voice typing, and document scans only from a DyskLink that serves them (`pc-commands`, `pc-terminal`,
 * `pc-screen-out`, `pc-display`, `pc-deck`, `pc-notch`, `pc-type`, `pc-scan`).
 */
private fun pcFeatures(peer: id.phonelink.android.core.Peer): List<Feature> =
    PC_FEATURES + listOfNotNull(
        Feature.MacScreen.takeIf { peer.canSeeComputerScreen },
        Feature.SecondDisplay.takeIf { peer.canExtend },
        Feature.Commands.takeIf { peer.canRunCommands },
        Feature.Terminal.takeIf { peer.canOpenTerminal },
        Feature.Deck.takeIf { peer.canDeck },
        Feature.Notch.takeIf { peer.canNotch },
        Feature.Scan.takeIf { peer.canType },
        Feature.Dictation.takeIf { peer.canType },
        Feature.DocScan.takeIf { peer.canTakeScans },
    )

private val Feature.pcTitle: String
    get() = when (this) {
        Feature.Commands -> "PC commands"
        Feature.MacScreen -> "PC screen"
        Feature.Notch -> "PC tools"
        else -> title
    }

private fun Feature.pcBlurb(peer: id.phonelink.android.core.Peer): String = when (this) {
    Feature.Commands -> "Run commands set on the PC"
    Feature.Terminal -> "A shell on the PC"
    Feature.MacScreen -> "See and tap the PC's screen"
    Feature.SecondDisplay -> "Extend the PC onto this phone"
    Feature.Notch -> "Focus, water, and tasks"
    Feature.Scan -> "Type codes on the PC"
    Feature.DocScan -> "Paper to PDF on the PC"
    Feature.Dictation -> "Speak, the PC types"
    Feature.Sound -> if (peer.canHear) "Hear the PC here, or this phone there" else "This phone on the PC"
    else -> blurb
}
