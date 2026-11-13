package id.phonelink.android.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/** A Tools tab card: icon, title, and one live line. */
@Composable
internal fun ToolHomeCard(icon: ImageVector, tint: Color, title: String, summary: String, onOpen: () -> Unit) {
    GlassCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlatIcon(icon, tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(summary, color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A setting picked from a short list with minus and plus. */
@Composable
internal fun ChoiceStepper(label: String, choices: List<Int>, value: Int, text: (Int) -> String, onPick: (Int) -> Unit) {
    val index = choices.indexOf(value).coerceAtLeast(0)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        StepButton(Icons.Rounded.Remove, "Less", enabled = index > 0) { onPick(choices[index - 1]) }
        Text(
            text(value),
            color = Palette.TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.width(84.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        StepButton(Icons.Rounded.Add, "More", enabled = index < choices.lastIndex) { onPick(choices[index + 1]) }
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Palette.PanelRaised)
            .border(1.dp, Palette.Stroke, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) Palette.TextPrimary else Palette.TextFaint, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun ToolSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Palette.TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun ToolLink(text: String, color: Color = Palette.Mint, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
    )
}

/** Whether notifications may be shown, and a way to ask: the permission prompt on Android 13+, else the settings. */
internal class NotificationAccess(val allowed: Boolean, val ask: () -> Unit)

@Composable
internal fun rememberNotificationAccess(): NotificationAccess {
    val context = LocalContext.current
    fun check() = NotificationManagerCompat.from(context).areNotificationsEnabled()
    var allowed by remember { mutableStateOf(check()) }
    // Once the prompt has been answered, Android may not show it again, so the next ask opens the settings.
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        asked = true
        allowed = check()
    }
    LifecycleResumeEffect(Unit) {
        allowed = check()
        onPauseOrDispose { }
    }
    return NotificationAccess(allowed) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}

/** Shown while notifications are off and the tool needs them. */
@Composable
internal fun NotificationHint(access: NotificationAccess, text: String) {
    if (access.allowed) return
    HintBox(text, "Allow notifications", onClick = access.ask)
}

/** Something outside DyskLink is in the way: what it is and what to do, with a link to where it's fixed. */
@Composable
internal fun HintBox(text: String, action: String?, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Amber.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text, color = Palette.Amber, fontSize = 13.sp, lineHeight = 18.sp)
        if (action != null) {
            Spacer(Modifier.height(2.dp))
            ToolLink(action, Palette.Amber, onClick)
        }
    }
}
