@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package id.phonelink.android.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import id.phonelink.android.core.Peer
import java.io.File

/*
 * Shared by the barcode and document scanners, which work on their own from the Tools tab
 * and can also send to a computer when one is connected.
 */

/** Why Google's scanners can't run on this phone, or null when Play services is there. */
internal fun playServicesProblem(context: Context): String? {
    val code = runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) }.getOrDefault(ConnectionResult.SERVICE_MISSING)
    return when (code) {
        ConnectionResult.SUCCESS -> null
        ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED, ConnectionResult.SERVICE_UPDATING ->
            "Google Play services needs an update before the scanner can open. Update it in the Play Store, then come back."
        ConnectionResult.SERVICE_DISABLED -> "Google Play services is turned off on this phone, and the scanner comes from it."
        else -> "This phone doesn't have Google Play services, which the scanner comes from, so scanning isn't available here."
    }
}

/**
 * The computers a scan can go to: connected paired ones, and [preselected] even while it's away,
 * so a Mac's own page keeps pointing at that Mac.
 */
internal fun scanTargets(peers: List<Peer>, preselected: String?): List<Peer> =
    peers.filter { it.isPaired && it.isComputer && (it.isConnected || it.id == preselected) }

/** "This phone only" plus each computer. Shown only when there's a computer to pick. */
@Composable
internal fun ScanTargetPicker(title: String, targets: List<Peer>, selected: String?, onSelect: (String?) -> Unit) {
    if (targets.isEmpty()) return
    Text(title, color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
    androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ScanChip("This phone only", selected == null) { onSelect(null) }
        targets.forEach { peer ->
            ScanChip(if (peer.isConnected) peer.name else "${peer.name} (away)", selected == peer.id) { onSelect(peer.id) }
        }
    }
}

@Composable
internal fun ScanChip(text: String, on: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (on) Palette.Ink else Palette.TextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (on) Palette.Mint else Palette.PanelRaised)
            .border(1.dp, if (on) Palette.Mint else Palette.Stroke, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** A small tinted action, as used in the scan lists. */
@Composable
internal fun ScanAction(text: String, color: Color = Palette.TextSecondary, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

internal fun copyText(context: Context, text: String, label: String = "Scanned code") {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

internal fun shareText(context: Context, text: String, title: String = "Share") {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    startSafely(context, Intent.createChooser(send, title))
}

/** Shares a file from the app's folders through the FileProvider. */
internal fun shareFile(context: Context, file: File, mime: String, title: String = file.name) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startSafely(context, Intent.createChooser(send, title))
}

/** Opens a file from the app's folders in another app. */
internal fun openFile(context: Context, file: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    startSafely(context, Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "No app on this phone can open it.")
}

internal fun startSafely(context: Context, intent: Intent, missing: String = "No app on this phone can do that.") {
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, missing, Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(context, missing, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun ScanToolCard(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color, title: String, text: String, onOpen: () -> Unit) {
    GlassCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlatIcon(icon, tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(text, color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
fun BarcodeHomeCard(onOpen: () -> Unit) = ScanToolCard(
    Icons.Rounded.QrCodeScanner, Palette.Mint, "Scan barcodes",
    "QR codes and barcodes: open links, join Wi-Fi, add contacts. Kept in a history on this phone.", onOpen,
)

@Composable
fun DocScanHomeCard(onOpen: () -> Unit) = ScanToolCard(
    Icons.Rounded.DocumentScanner, Palette.Amber, "Scan document",
    "Paper to PDF with cropping and filters. Save, share, or send it to a computer or phone.", onOpen,
)
