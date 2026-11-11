package id.phonelink.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import id.phonelink.android.core.InputEvent
import id.phonelink.android.core.ScanSuffix
import id.phonelink.android.core.ScanText
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private data class ScanItem(val text: String, val format: String, val time: String)

private fun formatName(format: Int): String = when (format) {
    Barcode.FORMAT_QR_CODE -> "QR"
    Barcode.FORMAT_EAN_13 -> "EAN-13"
    Barcode.FORMAT_EAN_8 -> "EAN-8"
    Barcode.FORMAT_UPC_A -> "UPC-A"
    Barcode.FORMAT_UPC_E -> "UPC-E"
    Barcode.FORMAT_CODE_128 -> "Code 128"
    Barcode.FORMAT_CODE_39 -> "Code 39"
    Barcode.FORMAT_CODE_93 -> "Code 93"
    Barcode.FORMAT_CODABAR -> "Codabar"
    Barcode.FORMAT_ITF -> "ITF"
    Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
    Barcode.FORMAT_PDF417 -> "PDF417"
    Barcode.FORMAT_AZTEC -> "Aztec"
    else -> "Barcode"
}

private fun scanner(context: Context): GmsBarcodeScanner =
    GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder().enableAutoZoom().build())

@Composable
fun ScanScreen(macName: String, connected: Boolean, onBack: () -> Unit, send: (InputEvent) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("scan", Context.MODE_PRIVATE) }
    var suffix by remember { mutableStateOf(runCatching { ScanSuffix.valueOf(prefs.getString("suffix", null) ?: "") }.getOrDefault(ScanSuffix.Enter)) }
    var continuous by remember { mutableStateOf(prefs.getBoolean("continuous", false)) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scans = remember { mutableStateListOf<ScanItem>() }
    val clock = remember { DateTimeFormatter.ofPattern("HH:mm:ss") }

    // The scanner screen comes from Play services and is downloaded the first time. Asking early makes the first scan quicker.
    LaunchedEffect(Unit) {
        runCatching {
            ModuleInstall.getClient(context).installModules(ModuleInstallRequest.newBuilder().addApi(scanner(context)).build())
        }
    }

    fun type(text: String) = ScanText.events(text, suffix).forEach(send)

    lateinit var scan: () -> Unit
    scan = {
        problem = null
        scanner(context).startScan()
            .addOnSuccessListener { barcode ->
                val text = ScanText.clean(barcode.rawValue)
                if (text == null) {
                    problem = "That code has no text in it."
                    return@addOnSuccessListener
                }
                scans.add(0, ScanItem(text, formatName(barcode.format), LocalTime.now().format(clock)))
                if (scans.size > 200) scans.removeAt(scans.lastIndex)
                if (connected) type(text) else problem = "Not connected to $macName. The code is saved below, tap Type to send it later."
                if (continuous && connected) scan()
            }
            .addOnFailureListener { error ->
                problem = if ("download" in (error.message ?: "").lowercase()) {
                    "Google Play services is still downloading the scanner. Try again in a moment."
                } else {
                    "The scanner couldn't start: ${error.message ?: "unknown error"}"
                }
            }
    }

    FeatureScaffold("Scan to Mac", onBack) {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GlassCard {
                Text(
                    "Point the camera at a barcode or QR code. Its text is typed on $macName where the cursor is, " +
                        "like a barcode scanner, for example in a spreadsheet or a stock form.",
                    color = Palette.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(14.dp))
                PrimaryButton(if (continuous) "Start scanning" else "Scan a code", Modifier.fillMaxWidth(), onClick = scan)
                problem?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Palette.Amber, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }

            GlassCard {
                Text("AFTER EACH CODE, PRESS", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScanSuffix.entries.forEach { option ->
                        val on = option == suffix
                        Text(
                            option.label,
                            color = if (on) Palette.Ink else Palette.TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (on) Palette.Mint else Palette.PanelRaised)
                                .border(1.dp, if (on) Palette.Mint else Palette.Stroke, CircleShape)
                                .clickable {
                                    suffix = option
                                    prefs.edit().putString("suffix", option.name).apply()
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                Text(
                    when (suffix) {
                        ScanSuffix.Enter -> "Moves to the next row in most spreadsheets and submits search boxes."
                        ScanSuffix.Tab -> "Moves to the next cell or field."
                        ScanSuffix.None -> "Only the code is typed."
                    },
                    color = Palette.TextFaint,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep scanning", color = Palette.TextPrimary, fontSize = 14.sp)
                        Text("Opens the camera again after each code. Press Back to stop.", color = Palette.TextFaint, fontSize = 12.sp)
                    }
                    Switch(checked = continuous, onCheckedChange = {
                        continuous = it
                        prefs.edit().putBoolean("continuous", it).apply()
                    })
                }
            }

            if (scans.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "SCANNED (${scans.size})",
                        color = Palette.TextFaint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Copy all",
                        color = Palette.Mint,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(CircleShape).clickable {
                            copy(context, scans.reversed().joinToString("\n") { it.text })
                        }.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    Text(
                        "Clear",
                        color = Palette.TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(CircleShape).clickable { scans.clear() }.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                GlassCard {
                    scans.forEachIndexed { index, item ->
                        if (index > 0) Spacer(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Palette.Stroke))
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(ScanText.preview(item.text), color = Palette.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${item.format} · ${item.time}", color = Palette.TextFaint, fontSize = 11.sp)
                            }
                            SmallAction("Copy", Palette.TextSecondary) { copy(context, item.text) }
                            if (connected) SmallAction("Type", Palette.Mint) { type(item.text) }
                        }
                    }
                }
            }
            Text(
                "The camera and scanner come from Google Play services. Codes are read on this phone and only the text is sent " +
                    "to $macName. The list is cleared when you leave DyskLink.",
                color = Palette.TextFaint,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SmallAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Scanned code", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
