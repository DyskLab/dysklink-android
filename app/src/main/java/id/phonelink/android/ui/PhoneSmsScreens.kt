package id.phonelink.android.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.phonelink.android.core.MoveItem
import id.phonelink.android.core.MovePlan
import id.phonelink.android.core.MoveProgress
import id.phonelink.android.core.Peer
import id.phonelink.android.core.SmsItem
import id.phonelink.android.core.SmsLogEntry
import id.phonelink.android.core.SmsView
import id.phonelink.android.service.SmsLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** What SMS through another phone, moving, and photo backup need from the connection (1.6). */
class PhoneMoreLink(
    val smsViews: StateFlow<Map<String, SmsView>>,
    val smsFollow: StateFlow<Set<String>>,
    val smsLog: StateFlow<List<SmsLogEntry>>,
    val listSms: (peerId: String, thread: Long?) -> Boolean,
    /** The SMS's ID, or null when it couldn't go. */
    val sendSms: (peerId: String, address: String, body: String) -> String?,
    val followSms: (peerId: String, on: Boolean) -> Unit,
    val movePlan: StateFlow<MovePlan?>,
    val moveProgress: (MovePlan) -> MoveProgress?,
    val startMove: (peerId: String, peerName: String, items: List<MoveItem>) -> Unit,
    val pauseMove: () -> Unit,
    val resumeMove: () -> Unit,
    val cancelMove: () -> Unit,
)

private val timeFormat get() = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)

/**
 * Another phone's SMS (B's), read and sent from here. Opens only after this phone's screen lock is confirmed, once per
 * session ([SmsLock]); B still decides with its own switch, and shows each SMS sent.
 */
@Composable
fun PhoneSmsScreen(peer: Peer, link: PhoneMoreLink?, onBack: () -> Unit) {
    link ?: return
    val context = LocalContext.current
    var unlocked by remember { mutableStateOf(SmsLock.session.isUnlocked) }
    var noLock by remember { mutableStateOf(false) }
    var thread by remember { mutableStateOf<SmsItem?>(null) }
    var composing by remember { mutableStateOf(false) }
    BackHandler {
        when {
            thread != null -> thread = null
            composing -> composing = false
            else -> onBack()
        }
    }
    val keyguardLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            SmsLock.session.unlock()
            unlocked = true
        }
    }
    fun askUnlock() {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (!keyguard.isDeviceSecure) {
            noLock = true
            return
        }
        confirmScreenLock(context as Activity, "Read SMS on ${peer.name}", onDone = { ok ->
            if (ok) {
                SmsLock.session.unlock()
                unlocked = true
            }
        }) {
            keyguard.createConfirmDeviceCredentialIntent("Read SMS on ${peer.name}", "Confirm it's you")?.let(keyguardLauncher::launch)
        }
    }
    LaunchedEffect(Unit) { if (!unlocked) askUnlock() }
    // The session ends after a while without use, and when the screen goes off or DyskLink leaves the screen.
    LaunchedEffect(unlocked) {
        while (unlocked) {
            delay(2_000)
            if (!SmsLock.session.isUnlocked) unlocked = false
        }
    }
    val views by link.smsViews.collectAsStateWithLifecycle()
    val view = views[peer.id] ?: SmsView()
    val follow by link.smsFollow.collectAsStateWithLifecycle()
    LaunchedEffect(unlocked, peer.isConnected) { if (unlocked && peer.isConnected) link.listSms(peer.id, null) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(
            "‹ ${if (thread != null) "Threads" else peer.name}", color = Palette.TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).clickable { if (thread != null) thread = null else onBack() }.padding(vertical = 8.dp, horizontal = 4.dp),
        )
        Text(thread?.address ?: "SMS on ${peer.name}", color = Palette.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        when {
            noLock -> GlassCard {
                Text("Set a screen lock first", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("SMS from ${peer.name} open only after you confirm your fingerprint, face, or PIN here.", color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Open security settings", Modifier.fillMaxWidth()) { runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) } }
            }
            !unlocked -> GlassCard {
                Text("Locked", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("Confirm it's you to read and send SMS through ${peer.name}.", color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Unlock", Modifier.fillMaxWidth(), onClick = ::askUnlock)
            }
            !peer.isConnected -> Text("Shown only while ${peer.name} is connected.", color = Palette.TextSecondary, fontSize = 13.sp)
            thread != null -> SmsThreadView(peer, link, view, thread!!)
            composing -> NewSmsView(peer, link, view) { composing = false }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Show new SMS here", color = Palette.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("As notifications, while ${peer.name} is connected.", color = Palette.TextSecondary, fontSize = 12.sp)
                    }
                    Switch(peer.id in follow, { on -> SmsLock.session.touch(); link.followSms(peer.id, on) })
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("New SMS", Modifier.weight(1f)) { SmsLock.session.touch(); composing = true }
                    SecondaryButton("Refresh", Modifier.weight(1f)) { SmsLock.session.touch(); link.listSms(peer.id, null) }
                }
                Spacer(Modifier.height(10.dp))
                view.error?.let { Text(it, color = Palette.Amber, fontSize = 12.sp, lineHeight = 17.sp) }
                val threads = view.threads
                when {
                    threads == null -> Text(if (view.loading) "Asking ${peer.name}…" else "", color = Palette.TextSecondary, fontSize = 13.sp)
                    threads.isEmpty() -> Text("No SMS on ${peer.name}.", color = Palette.TextSecondary, fontSize = 13.sp)
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(threads, key = { it.threadId }) { item ->
                            GlassCard(onClick = {
                                SmsLock.session.touch()
                                thread = item
                                link.listSms(peer.id, item.threadId)
                            }) {
                                Row {
                                    Text(item.address, color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
                                    Text(timeFormat.format(java.util.Date(item.time)), color = Palette.TextFaint, fontSize = 11.sp)
                                }
                                Text((if (item.outgoing) "You: " else "") + item.body, color = Palette.TextSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmsThreadView(peer: Peer, link: PhoneMoreLink, view: SmsView, thread: SmsItem) {
    val messages = if (view.threadId == thread.threadId) view.messages else emptyList()
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.scrollToItem(messages.lastIndex) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(messages, key = { it.id }) { message ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (message.outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
                    Column(
                        Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp))
                            .background(if (message.outgoing) Palette.Sky.copy(alpha = 0.18f) else Palette.PanelRaised)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(message.body, color = Palette.TextPrimary, fontSize = 14.sp, lineHeight = 19.sp)
                        Text(timeFormat.format(java.util.Date(message.time)), color = Palette.TextFaint, fontSize = 10.sp)
                    }
                }
            }
        }
        SmsComposer(peer, link, view, thread.address) { link.listSms(peer.id, thread.threadId) }
    }
}

@Composable
private fun NewSmsView(peer: Peer, link: PhoneMoreLink, view: SmsView, onSent: () -> Unit) {
    var number by remember { mutableStateOf("") }
    Column {
        OutlinedTextField(number, { number = it; SmsLock.session.touch() }, Modifier.fillMaxWidth(), placeholder = { Text("Phone number") }, singleLine = true)
        Spacer(Modifier.height(8.dp))
        SmsComposer(peer, link, view, number.trim()) { onSent() }
    }
}

/** The text box and Send, with what happened to the last SMS sent from here. */
@Composable
private fun SmsComposer(peer: Peer, link: PhoneMoreLink, view: SmsView, address: String, onSent: () -> Unit) {
    var text by remember(address) { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val result = pending?.let { view.results[it] }
    LaunchedEffect(result) {
        if (result == null) return@LaunchedEffect
        note = if (result.ok) "Sent through ${peer.name}." else (result.error ?: "Not sent.")
        pending = null
        if (result.ok) {
            delay(1_500)
            onSent()
        }
    }
    Column(Modifier.padding(top = 8.dp)) {
        note?.let { Text(it, color = if (it.startsWith("Sent")) Palette.Mint else Palette.Amber, fontSize = 12.sp, lineHeight = 17.sp) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it; note = null; SmsLock.session.touch() }, Modifier.weight(1f), placeholder = { Text("SMS through ${peer.name}") }, maxLines = 4)
            Spacer(Modifier.padding(4.dp))
            PrimaryButton(if (pending != null) "…" else "Send") {
                if (pending != null) return@PrimaryButton
                SmsLock.session.touch()
                if (!SmsLock.session.isUnlocked) {
                    note = "Locked again. Go back and unlock."
                    return@PrimaryButton
                }
                val id = link.sendSms(peer.id, address, text.trim())
                if (id == null) {
                    note = "Check the number and the text. Codes with * or # can't be sent."
                } else {
                    pending = id
                    note = "Sending…"
                    text = ""
                }
            }
        }
        Text("Uses ${peer.name}'s SIM and number, its default SIM for SMS if it has two. ${peer.name} shows a notification for each SMS, at most 10 a minute.", color = Palette.TextFaint, fontSize = 11.sp, lineHeight = 15.sp)
    }
}

/** On B: every SMS that phone sent through this one, newest first. */
@Composable
fun SmsLogList(peer: Peer, entries: List<SmsLogEntry>) {
    val mine = entries.filter { it.peerId == peer.id }
    Spacer(Modifier.height(8.dp))
    Text("SMS sent by ${peer.name}", color = Palette.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    if (mine.isEmpty()) {
        Text("None yet.", color = Palette.TextFaint, fontSize = 12.sp)
        return
    }
    mine.take(20).forEach { entry ->
        Text(
            "${timeFormat.format(java.util.Date(entry.time))}  ${entry.address}  ${entry.chars} chars" + if (entry.ok) "" else ", not sent: ${entry.error ?: "failed"}",
            color = if (entry.ok) Palette.TextSecondary else Palette.Amber, fontSize = 12.sp, lineHeight = 17.sp,
        )
    }
    if (mine.size > 20) Text("And ${mine.size - 20} more.", color = Palette.TextFaint, fontSize = 12.sp)
}

/** Fingerprint, face, or the screen lock. Android 8 uses the screen lock page ([fallback]) instead. */
internal fun confirmScreenLock(activity: Activity, title: String, onDone: (Boolean) -> Unit, fallback: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return fallback()
    val builder = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle("Confirm it's you")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
    } else {
        @Suppress("DEPRECATION")
        builder.setDeviceCredentialAllowed(true)
    }
    val prompt = runCatching { builder.build() }.getOrElse { return fallback() }
    runCatching {
        prompt.authenticate(
            CancellationSignal(),
            activity.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onDone(true)
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onDone(false)
            },
        )
    }.onFailure { fallback() }
}
