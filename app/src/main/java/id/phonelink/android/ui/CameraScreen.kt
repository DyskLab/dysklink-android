package id.phonelink.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.phonelink.android.service.CameraStreamService

/** Starts and controls the phone camera on a Mac. The picture shows on the Mac, in its own window. */
@Composable
fun CameraScreen(peerId: String, macName: String, connected: Boolean, start: (String, Boolean) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val status by CameraStreamService.active.collectAsState()
    val mine = status?.peerId == peerId
    FeatureScaffold("Camera to Mac", onBack) {
        GlassCard {
            Text(
                if (mine) "Streaming to $macName" else "Use this phone as a camera on $macName",
                color = Palette.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    mine -> "The picture shows in a window on the Mac. Keep this phone on a stand; the screen can turn off. For OBS, Zoom, or Meet, the steps are on the Mac's camera tab."
                    status != null -> "The camera is streaming to another Mac. Stop it there first."
                    !connected -> "$macName isn't connected."
                    else -> "The picture opens in a window on the Mac. To use it in OBS, Zoom, Meet, or Teams, open \"Use it in OBS, Zoom, Meet, or Teams\" on the Mac's camera tab for the steps."
                },
                color = Palette.TextSecondary, fontSize = 13.sp, lineHeight = 18.sp,
            )
            Spacer(Modifier.height(10.dp))
            if (mine) StatusPill(if (status?.front == true) "Front camera" else "Back camera", Palette.Coral)
        }
        Spacer(Modifier.height(16.dp))
        if (mine) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Switch camera", Modifier.weight(1f)) { CameraStreamService.switchLens(context) }
                if (status?.front == false) {
                    SecondaryButton(if (status?.torch == true) "Light off" else "Light on", Modifier.weight(1f)) {
                        CameraStreamService.toggleTorch(context)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Stop", Modifier.fillMaxWidth(), tint = Palette.Coral) { CameraStreamService.stop(context) }
        } else if (status == null && connected) {
            PrimaryButton("Start back camera", Modifier.fillMaxWidth()) { start(peerId, false) }
            Spacer(Modifier.height(10.dp))
            SecondaryButton("Start front camera", Modifier.fillMaxWidth()) { start(peerId, true) }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Android shows the camera indicator while it's shared. Stop it here, from the notification, or on the Mac.",
            color = Palette.TextFaint, fontSize = 12.sp, lineHeight = 17.sp,
        )
    }
}
