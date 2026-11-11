package id.phonelink.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Language
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.phonelink.android.service.WebFiles
import kotlinx.coroutines.delay

@Composable
fun WebFilesHomeCard(onOpen: () -> Unit) {
    val state by WebFiles.state.collectAsStateWithLifecycle()
    GlassCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlatIcon(Icons.Rounded.Language, if (state.running) Palette.Mint else Palette.Sky)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Phone files in a browser", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.running) "On at ${state.address?.removePrefix("http://")}" else "Get files from any laptop on the same Wi-Fi.",
                    color = if (state.running) Palette.Mint else Palette.TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
fun WebFilesScreen(onBack: () -> Unit, onAllowFiles: () -> Unit) {
    val context = LocalContext.current
    val state by WebFiles.state.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(WebFiles.canReadFiles(context)) }
    // The user may come back from the "All files access" settings page.
    LaunchedEffect(Unit) {
        while (true) {
            allowed = WebFiles.canReadFiles(context)
            delay(1_500)
        }
    }

    FeatureScaffold("Phone files in a browser", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (!allowed) {
                GlassCard {
                    Text("Allow access to files", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "DyskLink needs All files access to show the phone's folders in a browser. It's the same access Phone data for Mac uses for files.",
                        color = Palette.TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton("Allow", Modifier.fillMaxWidth(), onClick = onAllowFiles)
                }
            }

            GlassCard {
                if (state.running) {
                    Text("ON THE LAPTOP, OPEN", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.address.orEmpty(),
                        color = Palette.Mint,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text("AND ENTER THIS PIN", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        state.pin.orEmpty().chunked(3).joinToString(" "),
                        color = Palette.TextPrimary,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 4.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Palette.PanelRaised)
                            .padding(vertical = 14.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        (if (state.downloads > 0) "${state.downloads} files opened, last from ${state.lastDownload}." else "Nothing opened yet.") +
                            (if (state.uploads > 0) " ${state.uploads} uploaded, last ${state.lastUpload}." else ""),
                        color = Palette.TextSecondary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryButton("Copy address", Modifier.weight(1f)) {
                            context.getSystemService(ClipboardManager::class.java)
                                .setPrimaryClip(ClipData.newPlainText("Address", state.address))
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        }
                        SecondaryButton("Stop", Modifier.weight(1f), tint = Palette.Coral) { WebFiles.stop() }
                    }
                } else {
                    Text(
                        "Turn this on, then open the address on a laptop or PC on the same Wi-Fi. No app is needed there, " +
                            "only a browser. Folders can be browsed, and files opened or downloaded.",
                        color = Palette.TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                    state.problem?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = Palette.Amber, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton("Start", Modifier.fillMaxWidth()) { if (allowed) WebFiles.start(context) else onAllowFiles() }
                }
            }

            GlassCard {
                var uploads by remember { mutableStateOf(WebFiles.allowsUploads(context)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Allow uploads", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "The laptop can add files to the folder it has open. Existing files are never replaced or deleted.",
                            color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = uploads,
                        onCheckedChange = { uploads = it; WebFiles.setAllowsUploads(context, it) },
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Folders can be downloaded as a ZIP. Without uploads, files can only be read. Anyone on the same Wi-Fi who knows the PIN can see the phone's " +
                    "files, so a new PIN is made each time and it stops after 30 minutes without use. The connection isn't " +
                    "encrypted, so use it on a Wi-Fi you trust, not in a café.",
                color = Palette.TextFaint,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}
