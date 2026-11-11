package id.phonelink.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.phonelink.android.core.PhoneFeature

private val PhoneFeature.title: String
    get() = when (this) {
        PhoneFeature.Notifications -> "Notifications"
        PhoneFeature.Sms -> "SMS"
        PhoneFeature.Calls -> "Calls"
        PhoneFeature.Contacts -> "Contacts"
        PhoneFeature.Files -> "Files"
        PhoneFeature.Screen -> "Screen requests"
        PhoneFeature.Control -> "Control from Mac"
        PhoneFeature.ScreenTime -> "Screen time"
        PhoneFeature.Gallery -> "Gallery"
    }

private val PhoneFeature.description: String
    get() = when (this) {
        PhoneFeature.Notifications ->
            "Notification content appears on the Mac, including messages and verification codes if the app shows them. " +
                "The Mac can dismiss notifications on the phone."
        PhoneFeature.Sms -> "The Mac can read the last 300 SMS and send SMS from this phone's number. Your carrier's SMS charges apply."
        PhoneFeature.Calls -> "The Mac is told about incoming, answered, and missed calls, with their numbers."
        PhoneFeature.Contacts -> "The Mac can read contact names and numbers. It keeps them in memory only, unless you export them."
        PhoneFeature.Files -> "The Mac can browse and download files in the phone's internal storage. It can't change or delete files."
        PhoneFeature.Screen ->
            "The Mac may request the phone screen. You still have to approve the Android dialog every time, and you can stop from the notification."
        PhoneFeature.Control ->
            "The Mac can tap, swipe, and type on the phone. It doesn't work while the phone is locked and never types into password fields. Needs Accessibility permission."
        PhoneFeature.ScreenTime ->
            "The Mac shows how long you used the phone today and which apps you used most, with their names and icons. Needs Usage access."
        PhoneFeature.Gallery ->
            "The Mac can browse your photos and videos, newest first, and open or save them. Photos are only copied when you open or save one."
    }

@Composable
fun SharingScreen(
    enabled: Set<PhoneFeature>,
    active: Set<PhoneFeature>,
    onBack: () -> Unit,
    onToggle: (PhoneFeature, Boolean) -> Unit,
    onGrant: (PhoneFeature) -> Unit,
) {
    FeatureScaffold(Feature.Sharing.title, onBack) {
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "What you turn on applies to every paired Mac. Data only travels over local, encrypted " +
                    "Wi-Fi, and you can turn it off at any time.",
                color = Palette.TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            PhoneFeature.entries.forEach { feature ->
                val isOn = feature in enabled
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(feature.title, color = Palette.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(3.dp))
                            Text(feature.description, color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                        Switch(
                            checked = isOn,
                            onCheckedChange = { onToggle(feature, it) },
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                    if (isOn && feature !in active) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Android permission not granted. Tap to grant it.",
                            color = Palette.Amber,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { onGrant(feature) }.padding(vertical = 4.dp),
                        )
                    }
                }
            }
            Text(
                "If the permission option is grayed out, open DyskLink's App info, tap the three-dot menu, " +
                    "then choose to allow restricted settings.",
                color = Palette.TextFaint,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}
