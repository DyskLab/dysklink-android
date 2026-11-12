package id.phonelink.android.ui

import android.app.Activity
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import id.phonelink.android.core.Peer
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
 * Scans paper with Google's document scanner (edges, crop, and filters), reads the text in it, and keeps the PDF
 * in DyskLink's own folder until deleted. From there it can be saved to Documents, shared, or sent to a computer
 * (with kind "scan", so the Mac adds a text layer) or another phone. Opened from the Tools tab, or from a Mac's
 * page with that Mac picked as [preselected].
 */
@Composable
fun DocScanScreen(
    peers: List<Peer>,
    preselected: String?,
    onBack: () -> Unit,
    sendFile: (peerId: String, File, kind: String?) -> Unit,
    sendText: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { DocScans(File(context.filesDir, "scans")) }
    var scans by remember { mutableStateOf(store.list()) }
    var busy by remember { mutableStateOf<String?>(null) }
    val prefs = remember { context.getSharedPreferences("docscan", Context.MODE_PRIVATE) }
    var autoSend by remember { mutableStateOf(prefs.getBoolean("autoSend", true)) }
    var keepJpeg by remember { mutableStateOf(prefs.getBoolean("jpeg", false)) }
    var opened by remember { mutableStateOf<DocScan?>(null) }
    val unavailable = remember { playServicesProblem(context) }
    var target by remember { mutableStateOf(preselected) }
    val targets = scanTargets(peers, preselected)
    val targetPeer = targets.firstOrNull { it.id == target }
    val computers = peers.filter { it.isPaired && it.isComputer && it.isConnected }
    val phones = peers.filter { it.isPaired && it.isPhone && it.isConnected }

    fun send(peer: Peer, scan: DocScan) {
        // A PC without `pc-scan` gets a plain file, which it keeps in Downloads.
        sendFile(peer.id, scan.pdf, if (peer.isComputer && peer.canTakeScans) "scan" else null)
        Toast.makeText(context, "Sending to ${peer.name}", Toast.LENGTH_SHORT).show()
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val scan = GmsDocumentScanningResult.fromActivityResultIntent(result.data) ?: return@rememberLauncherForActivityResult
        val pdf = scan.pdf ?: return@rememberLauncherForActivityResult
        val pages = scan.pages.orEmpty().map { it.imageUri }
        scope.launch {
            busy = "Reading the text…"
            val text = readText(context, pages)
            val saved = withContext(Dispatchers.IO) {
                val saved = store.save(store.newName(), { target ->
                    context.contentResolver.openInputStream(pdf.uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                }, pdf.pageCount, text)
                if (keepJpeg) pages.forEachIndexed { index, uri ->
                    runCatching {
                        store.saveImage(saved, index + 1) { file ->
                            context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
                        }
                    }
                }
                saved
            }
            busy = null
            scans = store.list()
            val peer = targetPeer
            if (autoSend && peer != null && peer.isConnected) send(peer, saved) else opened = saved
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

    FeatureScaffold("Scan document", onBack) {
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
                    if (unavailable != null) {
                        Text(unavailable, color = Palette.Amber, fontSize = 12.5.sp, lineHeight = 17.sp)
                    } else {
                        PrimaryButton(busy ?: "Scan a document", Modifier.fillMaxWidth()) { if (busy == null) startScan() }
                    }
                    Spacer(Modifier.height(12.dp))
                    ToggleRow(
                        "Also keep pages as JPEG",
                        "Saved next to the PDF, and copied with it when you save to Documents.",
                        keepJpeg,
                    ) {
                        keepJpeg = it
                        prefs.edit().putBoolean("jpeg", it).apply()
                    }
                }
            }
            if (targets.isNotEmpty()) item {
                GlassCard {
                    ScanTargetPicker("SEND NEW SCANS TO", targets, target) { target = it }
                    targetPeer?.let { peer ->
                        Spacer(Modifier.height(12.dp))
                        ToggleRow(
                            "Send to ${peer.name} right away",
                            if (peer.isConnected) "The PDF goes to Downloads/DyskLink there." else "${peer.name} isn't connected, so scans wait here.",
                            autoSend,
                        ) {
                            autoSend = it
                            prefs.edit().putBoolean("autoSend", it).apply()
                        }
                    }
                }
            }
            if (scans.isNotEmpty()) {
                item {
                    Text("RECENT SCANS", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                        modifier = Modifier.padding(top = 6.dp, start = 2.dp))
                }
            }
            items(scans, key = { it.pdf.path }) { scan ->
                ScanRow(
                    scan,
                    onOpen = { opened = scan },
                    onView = { openFile(context, scan.pdf, "application/pdf") },
                    onShare = { share(context, store, scan) },
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }

    opened?.let { scan ->
        ScanSheet(
            scan = scan,
            computers = computers,
            phones = phones,
            onDismiss = { opened = null },
            onSend = { send(it, scan) },
            onSendText = if (computers.isNotEmpty()) ({ sendText(scan.readText()) }) else null,
            onView = { openFile(context, scan.pdf, "application/pdf") },
            onShare = { share(context, store, scan) },
            onSave = { name ->
                scope.launch {
                    val where = withContext(Dispatchers.IO) { runCatching { saveToDocuments(context, scan, store.images(scan), name) }.getOrNull() }
                    Toast.makeText(context, where?.let { "Saved to $it" } ?: "The scan couldn't be saved.", Toast.LENGTH_LONG).show()
                }
            },
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

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(detail, color = Palette.TextFaint, fontSize = 11.5.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Palette.Mint, checkedThumbColor = Palette.Ink),
        )
    }
}

/** The folder scans are saved to, under Documents. */
private const val PUBLIC_FOLDER = "DyskLink/Scans"

/**
 * Copies the PDF (and any JPEG pages) to Documents/DyskLink/Scans through MediaStore on Android 10 and newer,
 * or to the app's own Documents folder on older phones. Returns where it went.
 */
private fun saveToDocuments(context: Context, scan: DocScan, images: List<File>, wanted: String): String {
    val name = DocScans.clean(wanted).ifEmpty { scan.name }
    val files = listOf(scan.pdf to "$name.pdf") + images.mapIndexed { index, file -> file to "$name page ${index + 1}.jpg" }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        files.forEach { (file, display) ->
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, display)
                put(MediaStore.MediaColumns.MIME_TYPE, if (display.endsWith(".pdf")) "application/pdf" else "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/$PUBLIC_FOLDER/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: error("MediaStore refused $display")
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("No stream for $display")
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        }
        return "${Environment.DIRECTORY_DOCUMENTS}/$PUBLIC_FOLDER"
    }
    val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: File(context.filesDir, "Documents"), PUBLIC_FOLDER)
    folder.mkdirs()
    files.forEach { (file, display) ->
        val base = display.substringBeforeLast('.')
        val ext = display.substringAfterLast('.')
        val unique = DocScans.uniqueName(base) { File(folder, "$it.$ext").exists() }
        file.copyTo(File(folder, "$unique.$ext"))
    }
    return "DyskLink's Documents folder (Android/data/${context.packageName}/files/Documents/$PUBLIC_FOLDER)"
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

/** Shares the PDF, and its JPEG pages when they were kept. */
private fun share(context: Context, store: DocScans, scan: DocScan) {
    val images = store.images(scan)
    if (images.isEmpty()) return shareFile(context, scan.pdf, "application/pdf", scan.name)
    val uris = ArrayList((listOf(scan.pdf) + images).map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
    val send = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startSafely(context, Intent.createChooser(send, scan.name))
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
private fun ScanRow(scan: DocScan, onOpen: () -> Unit, onView: () -> Unit, onShare: () -> Unit) {
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
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScanAction("Open", Palette.Mint, onView)
                ScanAction("Share", onClick = onShare)
                ScanAction("More", onClick = onOpen)
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ScanSheet(
    scan: DocScan,
    computers: List<Peer>,
    phones: List<Peer>,
    onDismiss: () -> Unit,
    onSend: (Peer) -> Unit,
    onSendText: (() -> Unit)?,
    onView: () -> Unit,
    onShare: () -> Unit,
    onSave: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val text = remember(scan.pdf.path, scan.created) { scan.readText() }
    var name by remember(scan.pdf.path) { mutableStateOf(scan.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    var saveAs by remember { mutableStateOf<String?>(null) }
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
                PrimaryButton("Save to phone", Modifier.weight(1f)) { saveAs = name.trim().ifEmpty { scan.name } }
                SecondaryButton("Share", Modifier.weight(0.6f), onClick = onShare)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ScanAction("Open", Palette.Mint, onView)
                computers.forEach { peer -> ScanAction("Send to ${peer.name}", Palette.Sky) { onSend(peer) } }
                phones.forEach { peer -> ScanAction("Send to ${peer.name}", Palette.Violet) { onSend(peer) } }
            }
            if (computers.isNotEmpty()) {
                Text("A computer adds a text layer to the PDF, so it can be searched there.", color = Palette.TextFaint, fontSize = 11.5.sp)
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
                    if (onSendText != null) SecondaryButton("Text to computer clipboard", onClick = onSendText)
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
            text = { Text("It's removed from DyskLink. Copies saved to Documents or sent to other devices stay there.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = Palette.Coral) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
    saveAs?.let { value ->
        AlertDialog(
            onDismissRequest = { saveAs = null },
            title = { Text("Save to Documents") },
            text = {
                Column {
                    Text("Saved in Documents/DyskLink/Scans, where file managers and other apps can find it.", fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = value,
                        onValueChange = { saveAs = it.take(80) },
                        label = { Text("File name") },
                        suffix = { Text(".pdf") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = DocScans.clean(value).isNotEmpty(), onClick = { saveAs = null; onSave(value) }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saveAs = null }) { Text("Cancel") } },
        )
    }
}
