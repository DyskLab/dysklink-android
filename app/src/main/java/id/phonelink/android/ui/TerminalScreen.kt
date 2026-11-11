package id.phonelink.android.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import id.phonelink.android.core.Envelope
import id.phonelink.android.core.MessageType
import id.phonelink.android.core.TerminalEmulator
import id.phonelink.android.core.TerminalKeys
import id.phonelink.android.core.TerminalMessage
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/** How the terminal screen talks to the Mac. */
class TerminalLink(
    val events: Flow<Pair<String, Envelope>>,
    val send: (peerId: String, type: String, TerminalMessage) -> Unit,
)

private sealed interface TermState {
    data object Locked : TermState
    data object Opening : TermState
    data object Open : TermState
    data class Closed(val message: String) : TermState
}

@Composable
fun TerminalScreen(peerId: String, macName: String, connected: Boolean, link: TerminalLink?, onBack: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<TermState>(TermState.Locked) }
    var sessionId by remember { mutableStateOf<String?>(null) }
    var grid by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var redraw by remember { mutableIntStateOf(0) }
    val emulator = remember { TerminalEmulator(80, 24) }
    var view by remember { mutableStateOf<TerminalView?>(null) }
    val prefs = remember { context.getSharedPreferences("terminal", Context.MODE_PRIVATE) }
    val currentConnected by rememberUpdatedState(connected)

    fun send(type: String, message: TerminalMessage) = link?.send(peerId, type, message)
    fun input(text: String) {
        val id = sessionId ?: return
        if (state != TermState.Open) return
        send(MessageType.TERMINAL_INPUT, TerminalMessage(id, data = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)))
    }

    fun close() {
        sessionId?.let { id -> if (state == TermState.Open || state == TermState.Opening) send(MessageType.TERMINAL_CLOSE, TerminalMessage(id)) }
        sessionId = null
    }

    fun leave() {
        close()
        onBack()
    }

    BackHandler(onBack = ::leave)
    DisposableEffect(Unit) { onDispose { close() } }

    // Unlock: fingerprint, face, or the screen lock, every time the terminal is opened.
    val keyguardLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) state = TermState.Opening else leave()
    }
    var needsLock by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            needsLock = true
            return@LaunchedEffect
        }
        unlock(context as Activity, macName, onDone = { ok -> if (ok) state = TermState.Opening else leave() }) {
            keyguard.createConfirmDeviceCredentialIntent("Open the terminal on $macName", "Confirm it's you")?.let(keyguardLauncher::launch)
                ?: run { state = TermState.Opening }
        }
    }

    // Open a session once unlocked and the screen size is known.
    LaunchedEffect(state, grid, connected) {
        val size = grid ?: return@LaunchedEffect
        if (state == TermState.Opening && sessionId == null) {
            if (!connected) {
                state = TermState.Closed("$macName isn't connected.")
                return@LaunchedEffect
            }
            if (link == null) return@LaunchedEffect
            val id = UUID.randomUUID().toString().take(12)
            sessionId = id
            send(MessageType.TERMINAL_OPEN, TerminalMessage(id, cols = size.first, rows = size.second))
            kotlinx.coroutines.delay(8_000)
            if (state == TermState.Opening && sessionId == id) {
                state = TermState.Closed("$macName didn't answer. Update DyskLink on the Mac, then try again.")
                sessionId = null
            }
        }
    }
    LaunchedEffect(connected) {
        if (!connected && (state == TermState.Open || state == TermState.Opening)) {
            state = TermState.Closed("The connection to $macName was lost.")
            sessionId = null
        }
    }

    LaunchedEffect(link) {
        link?.events?.collect { (from, envelope) ->
            val message = envelope.terminal ?: return@collect
            if (from != peerId || message.id != sessionId) return@collect
            when (envelope.type) {
                MessageType.TERMINAL_OPENED -> state = TermState.Open
                MessageType.TERMINAL_OUTPUT -> {
                    val bytes = message.data?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() } ?: return@collect
                    emulator.feed(bytes)
                    view?.refresh()
                    redraw++
                }
                MessageType.TERMINAL_CLOSED -> {
                    state = TermState.Closed(
                        message.error ?: message.exitCode?.let { if (it == 0) "The shell ended." else "The shell ended with code $it." } ?: "The terminal closed.",
                    )
                    sessionId = null
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF090D13))
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "‹",
                color = Palette.TextSecondary,
                fontSize = 26.sp,
                modifier = Modifier.clip(CircleShape).clickable(onClick = ::leave).padding(horizontal = 10.dp),
            )
            Column(Modifier.weight(1f)) {
                Text("Terminal", color = Palette.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    when (state) {
                        TermState.Open -> emulator.title.ifEmpty { macName }
                        TermState.Opening -> "Opening on $macName…"
                        TermState.Locked -> macName
                        is TermState.Closed -> "Closed"
                    },
                    color = if (state == TermState.Open) Palette.Mint else Palette.TextFaint,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                )
            }
            if (state == TermState.Open) {
                TopButton("A−") { view?.let { it.fontSp = it.fontSp - 1f; prefs.edit().putFloat("font", it.fontSp).apply() } }
                TopButton("A+") { view?.let { it.fontSp = it.fontSp + 1f; prefs.edit().putFloat("font", it.fontSp).apply() } }
                TopButton("Paste") {
                    val text = context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (!text.isNullOrEmpty()) input(TerminalKeys.paste(text, emulator.bracketedPaste))
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
                factory = { ctx ->
                    TerminalView(ctx, emulator).apply {
                        fontSp = prefs.getFloat("font", 12.5f)
                        onInput = ::input
                        onGridSize = { cols, rows ->
                            grid = cols to rows
                            sessionId?.let { id ->
                                if (state == TermState.Open) send(MessageType.TERMINAL_RESIZE, TerminalMessage(id, cols = cols, rows = rows))
                            }
                        }
                        onStickyUsed = {
                            ctrl = false
                            alt = false
                        }
                        onLongPress = {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("Terminal", emulator.screenText()))
                            Toast.makeText(context, "Copied what's on the screen", Toast.LENGTH_SHORT).show()
                        }
                        emulator.onReply = ::input
                        view = this
                        post {
                            grid = emulator.cols to emulator.rows
                        }
                    }
                },
                update = { it.invalidate(); redraw },
            )
            when (val current = state) {
                TermState.Locked -> Overlay {
                    if (needsLock) {
                        FlatIcon(Icons.Rounded.Lock, Palette.Amber)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Set a screen lock first",
                            color = Palette.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "The terminal can do anything on $macName, so DyskLink asks for your fingerprint or screen lock each time. This phone has none.",
                            color = Palette.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(14.dp))
                        PrimaryButton("Open security settings") {
                            runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                        }
                    } else {
                        FlatIcon(Icons.Rounded.Lock, Palette.Mint)
                        Spacer(Modifier.height(12.dp))
                        Text("Confirm it's you", color = Palette.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                }
                is TermState.Closed -> Overlay {
                    FlatIcon(Icons.Rounded.Terminal, Palette.TextSecondary)
                    Spacer(Modifier.height(12.dp))
                    Text(current.message, color = Palette.TextPrimary, fontSize = 15.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    if (currentConnected) {
                        PrimaryButton("New session") {
                            emulator.feed("\u001bc")
                            view?.refresh()
                            state = TermState.Opening
                        }
                    }
                }
                else -> Unit
            }
        }

        if (state == TermState.Open) {
            ExtraKeys(
                ctrl = ctrl,
                alt = alt,
                onCtrl = { ctrl = !ctrl; view?.stickyCtrl = ctrl },
                onAlt = { alt = !alt; view?.stickyAlt = alt },
                onKey = { key -> view?.sendKey(key) },
                onText = { text -> view?.typeText(text) },
                onRaw = ::input,
                onKeyboard = { view?.showKeyboard() },
            )
        }
    }
}

@Composable
private fun Overlay(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xE6090D13)).padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
private fun TopButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Palette.TextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(CircleShape)
            .background(Palette.PanelRaised)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

@Composable
private fun ExtraKeys(
    ctrl: Boolean,
    alt: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onKey: (TerminalKeys.Key) -> Unit,
    onText: (String) -> Unit,
    onRaw: (String) -> Unit,
    onKeyboard: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Panel)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        KeyChip("⌨") { onKeyboard() }
        KeyChip("Esc") { onKey(TerminalKeys.Key.Escape) }
        KeyChip("Ctrl", on = ctrl, onClick = onCtrl)
        KeyChip("Alt", on = alt, onClick = onAlt)
        KeyChip("Tab") { onKey(TerminalKeys.Key.Tab) }
        KeyChip("←") { onKey(TerminalKeys.Key.Left) }
        KeyChip("↓") { onKey(TerminalKeys.Key.Down) }
        KeyChip("↑") { onKey(TerminalKeys.Key.Up) }
        KeyChip("→") { onKey(TerminalKeys.Key.Right) }
        // The ones shells use most, ready without holding Ctrl.
        KeyChip("^C") { onRaw("\u0003") }
        KeyChip("^D") { onRaw("\u0004") }
        KeyChip("^Z") { onRaw("\u001a") }
        KeyChip("^L") { onRaw("\u000c") }
        KeyChip("^R") { onRaw("\u0012") }
        listOf("|", "~", "/", "-", "_", "*", "&", ">", "$").forEach { symbol -> KeyChip(symbol) { onText(symbol) } }
        KeyChip("Home") { onKey(TerminalKeys.Key.Home) }
        KeyChip("End") { onKey(TerminalKeys.Key.End) }
        KeyChip("PgUp") { onKey(TerminalKeys.Key.PageUp) }
        KeyChip("PgDn") { onKey(TerminalKeys.Key.PageDown) }
        KeyChip("Del") { onKey(TerminalKeys.Key.Delete) }
    }
}

@Composable
private fun KeyChip(label: String, on: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (on) Palette.Mint else Palette.PanelRaised)
            .border(1.dp, if (on) Palette.Mint else Palette.Stroke, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) Palette.Ink else Palette.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

/** The system prompt for fingerprint, face, or the screen lock. Android 8 uses the screen lock page instead. */
private fun unlock(activity: Activity, macName: String, onDone: (Boolean) -> Unit, fallback: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return fallback()
    val builder = BiometricPrompt.Builder(activity)
        .setTitle("Open the terminal")
        .setSubtitle("On $macName, as you")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
    } else {
        @Suppress("DEPRECATION")
        builder.setDeviceCredentialAllowed(true)
    }
    // If the system prompt can't run on this phone, the screen lock page does the same job.
    runCatching { builder.build() }.getOrElse { return fallback() }.let { prompt ->
    runCatching { prompt.authenticate(
        CancellationSignal(),
        activity.mainExecutor,
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onDone(true)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onDone(false)
        },
    ) }.onFailure { fallback() }
    }
}
