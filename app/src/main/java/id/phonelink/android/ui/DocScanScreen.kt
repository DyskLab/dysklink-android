package id.phonelink.android.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import id.phonelink.android.core.DocScan
import id.phonelink.android.core.DocScans
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Scans paper with Google's document scanner (edges, crop, and filters), reads the text in it, and sends
 * the PDF to the Mac. Scans stay in DyskLink's own folder until deleted.
 */
@Composable
fun DocScanScreen(
    macName: String,
    connected: Boolean,
    onBack: () -> Unit,
    sendFile: (File) -> Unit,
    sendText: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { DocScans(File(context.filesDir, "scans")) }
    var scans by remember { mutableStateOf(store.list()) }
    var busy by remember { mutableStateOf<String?>(null) }
    val prefs = remember { context.getSharedPreferences("docscan", Context.MODE_PRIVATE) }
    var autoSend by remember { mutableStateOf(prefs.getBoolean("autoSend", true)) }
    var opened by remember { mutableStateOf<DocScan?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data) ?: return@rememberLauncherForActivityResult
        val pdf = scan.pdf ?: return@rememberLauncherForActivityResult
        val pages = scan.pages.orEmpty().map { it.imageUri }
        scope.launch {
            busy = "Reading the text…"
            val text = readText(context, pages)
            val saved = withContext(Dispatchers.IO) {
                store.save(store.newName(), { target ->
                    context.contentResolver.openInputStream(pdf.uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                }, pdf.pageCount, text)
            }
            busy = null
            scans = store.list()
            if (autoSend && connected) {
                sendFile(saved.pdf)
                Toast.makeText(context, "Sending to $macName", Toast.LENGTH_SHORT).show()
            } else {
                opened = saved
            }
        }
    }

    fun startScan() {
        val activity = context as? Activity ?: return
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(30)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF, GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(activity)
            .addOnSuccessListener { launcher.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener {
                Toast.makeText(context, "The scanner isn't available: ${it.message ?: "Google Play services needs an update"}", Toast.LENGTH_LONG).show()
            }
    }

    FeatureScaffold("Scan documents", onBack) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FlatIcon(Icons.Rounded.DocumentScanner, Palette.Amber)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Point the camera at paper. Edges are found for you, and pages can be cropped, rotated, and cleaned up before saving.",
                            color = Palette.TextSecondary, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton(busy ?: "Scan a document", Modifier.fillMaxWidth()) { if (busy == null) startScan() }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Send to $macName right away", color = Palette.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (connected) "The PDF goes to Downloads/DyskLink on the Mac." else "$macName isn't connected, so scans wait here.",
                                color = Palette.TextFaint, fontSize = 11.5.sp,
                            )
                        }
                        Switch(
                            checked = autoSend,
                            onCheckedChange = {
                                autoSend = it
                                prefs.edit().putBoolean("autoSend", it).apply()
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Mint, checkedThumbColor = Palette.Ink),
                        )
                    }
                }
            }
            if (scans.isNotEmpty()) {
                item {
                    Text("SCANS ON THIS PHONE", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                        modifier = Modifier.padding(top = 6.dp, start = 2.dp))
                }
            }
            items(scans, key = { it.pdf.path }) { scan -> ScanRow(scan) { opened = scan } }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    opened?.let { scan ->
        ScanSheet(
            scan = scan,
            macName = macName,
            connected = connected,
            onDismiss = { opened = null },
            onSend = { sendFile(scan.pdf); Toast.makeText(context, "Sending to $macName", Toast.LENGTH_SHORT).show() },
            onSendText = { sendText(scan.readText()) },
            onShare = { share(context, scan) },
            onRename = { name ->
                store.rename(scan, name)?.let { opened = it } ?: Toast.makeText(context, "That name can't be used.", Toast.LENGTH_SHORT).show()
                scans = store.list()
            },
            onDelete = {
                store.delete(scan)
                opened = null
                scans = store.list()
            },
        )
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
}

private suspend fun readText(context: Context, pages: List<Uri>): String {
    if (pages.isEmpty()) return ""
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    return try {
        DocScans.joinPages(pages.map { uri -> runCatching { recognizer.process(InputImage.fromFilePath(context, uri)).awaitResult().text }.getOrDefault("") })
    } finally {
        recognizer.close()
    }
}

private fun share(context: Context, scan: DocScan) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", scan.pdf)
    val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, scan.name))
}

/** The first page of the PDF, drawn small. */
@Composable
private fun PdfThumb(file: File, width: Int = 52, height: Int = 68) {
    val bitmap = remember(file.path, file.lastModified()) {
        runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    renderer.openPage(0).use { page ->
                        val scale = (width * 3f) / page.width
                        val out = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        out.eraseColor(android.graphics.Color.WHITE)
                        page.render(out, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        out.asImageBitmap()
                    }
                }
            }
        }.getOrNull()
    }
    Box(
        Modifier.size(width.dp, height.dp).clip(RoundedCornerShape(8.dp)).background(Palette.PanelRaised).border(1.dp, Palette.Stroke, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) Image(bitmap, null, contentScale = ContentScale.Crop, modifier = Modifier.size(width.dp, height.dp))
        else FlatIcon(Icons.Rounded.Description, Palette.TextFaint, size = 28.dp)
    }
}

@Composable
private fun ScanRow(scan: DocScan, onOpen: () -> Unit) {
    val dates = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Palette.Panel.copy(alpha = 0.8f))
            .border(1.dp, Palette.Stroke, RoundedCornerShape(18.dp)).clickable(onClick = onOpen).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PdfThumb(scan.pdf)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(scan.name, color = Palette.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${scan.pages} page${if (scan.pages == 1) "" else "s"} · ${formatSize(scan.size)} · ${dates.format(Date(scan.created))}",
                color = Palette.TextFaint, fontSize = 11.5.sp,
            )
            val preview = remember(scan.pdf.path, scan.created) { scan.readText().replace('\n', ' ').take(90) }
            Text(
                preview.ifEmpty { "No text found" },
                color = if (preview.isEmpty()) Palette.TextFaint else Palette.TextSecondary,
                fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Text("  ›", color = Palette.TextFaint, fontSize = 16.sp)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ScanSheet(
    scan: DocScan,
    macName: String,
    connected: Boolean,
    onDismiss: () -> Unit,
    onSend: () -> Unit,
    onSendText: () -> Unit,
    onShare: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val text = remember(scan.pdf.path, scan.created) { scan.readText() }
    var name by remember(scan.pdf.path) { mutableStateOf(scan.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Palette.Panel,
        contentColor = Palette.TextPrimary,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PdfThumb(scan.pdf, 66, 86)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(80) },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (name.trim() != scan.name && name.isNotBlank()) {
                        Text("Save name", color = Palette.Mint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(CircleShape).clickable { onRename(name) }.padding(vertical = 6.dp))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(if (connected) "Send PDF to Mac" else "Mac not connected", Modifier.weight(1f).then(if (connected) Modifier else Modifier.background(Color.Transparent))) {
                    if (connected) onSend() else Toast.makeText(context, "Open DyskLink on $macName first.", Toast.LENGTH_SHORT).show()
                }
                SecondaryButton("Share", Modifier.weight(0.6f), onClick = onShare)
            }
            Text("TEXT IN THE SCAN", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            if (text.isEmpty()) {
                Text("No text was found. Handwriting and photos usually have none.", color = Palette.TextFaint, fontSize = 12.5.sp)
            } else {
                SelectionContainer {
                    Text(
                        text,
                        color = Palette.TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())
                            .clip(RoundedCornerShape(14.dp)).background(Palette.Ink.copy(alpha = 0.5f)).padding(12.dp),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Copy text") {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(scan.name, text))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                    if (connected) SecondaryButton("Text to Mac clipboard", onClick = onSendText)
                }
            }
            Text("Delete this scan", color = Palette.Coral, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).clickable { confirmDelete = true }.padding(vertical = 6.dp))
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${scan.name}?") },
            text = { Text("It's removed from the phone. A copy already sent to the Mac stays there.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = Palette.Coral) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
