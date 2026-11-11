package id.phonelink.android.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.phonelink.android.service.AudioShare
import id.phonelink.android.service.PhoneSoundService

/** Sound both ways: the Mac's sound on this phone, or this phone's sound on the Mac. */
@Composable
fun SoundScreen(peerId: String, macName: String, connected: Boolean, isMac: Boolean, onBack: () -> Unit, startSending: () -> Unit) {
    val context = LocalContext.current
    val listening by AudioShare.listening.collectAsState()
    val sending by AudioShare.sending.collectAsState()
    val problem by AudioShare.problem.collectAsState()
    val kind = if (isMac) "Mac" else "PC"
    FeatureScaffold("Sound sharing", onBack) {
        // Windows and Linux can't send their sound yet.
        if (isMac) GlassCard {
            Text("Hear $macName on this phone", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Music and videos on the Mac play here, on the speaker or earphones. Turn on \"Let phones hear this Mac\" on the Mac first. " +
                    "The sound is about a quarter of a second behind, so it suits music more than videos.",
                color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            if (listening == peerId) {
                SecondaryButton("Stop listening", Modifier.fillMaxWidth(), tint = Palette.Coral) { AudioShare.stopListening(peerId) }
            } else {
                PrimaryButton("Listen", Modifier.fillMaxWidth().alpha(if (connected) 1f else 0.4f)) { if (connected) AudioShare.listen(peerId) }
            }
        }
        if (isMac) Spacer(Modifier.height(12.dp))
        GlassCard {
            Text("Play this phone on $macName", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (AudioShare.canSend) {
                    "What this phone plays is heard from the $kind's speakers. Android asks each time. Apps that block recording, such as some streaming apps, stay silent, and the phone keeps playing too, so turn its volume down if you like."
                } else {
                    "Needs Android 10 or newer."
                },
                color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            if (sending == peerId) {
                SecondaryButton("Stop", Modifier.fillMaxWidth(), tint = Palette.Coral) { PhoneSoundService.stop(context) }
            } else {
                val canStart = connected && AudioShare.canSend && sending == null
                PrimaryButton("Play on the $kind", Modifier.fillMaxWidth().alpha(if (canStart) 1f else 0.4f)) { if (canStart) startSending() }
            }
        }
        if (isMac && AudioShare.canSend) {
            Spacer(Modifier.height(12.dp))
            GlassCard {
                var withScreen by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(AudioShare.wantsSoundWithScreen(context)) }
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        Text("Sound with this phone's screen", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "When the Mac shows this phone's screen, its sound plays on the Mac too, with the same Android prompt. The Mac can mute it from the screen window.",
                            color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                        )
                    }
                    androidx.compose.material3.Switch(withScreen, { withScreen = it; AudioShare.setSoundWithScreen(context, it) })
                }
            }
        }
        problem?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = Palette.Amber, fontSize = 12.sp, lineHeight = 17.sp)
        }
        if (!connected) {
            Spacer(Modifier.height(10.dp))
            Text("$macName isn't connected.", color = Palette.TextFaint, fontSize = 12.sp)
        }
    }
}
