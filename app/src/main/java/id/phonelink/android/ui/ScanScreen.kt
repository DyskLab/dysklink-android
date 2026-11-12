@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package id.phonelink.android.ui

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import id.phonelink.android.core.BarcodeContent
import id.phonelink.android.core.InputEvent
import id.phonelink.android.core.Peer
import id.phonelink.android.core.ScanHistory
import id.phonelink.android.core.ScanRecord
import id.phonelink.android.core.ScanSuffix
import id.phonelink.android.core.ScanText
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

/**
 * Scans QR codes and barcodes on the phone, with actions that fit what's in them, and a history kept here.
 * When a computer is picked, each code is also typed on it, like a barcode scanner. Opened from the Tools tab,
 * or from a Mac's page with that Mac picked as [preselected].
 */
@Composable
fun ScanScreen(peers: List<Peer>, preselected: String?, onBack: () -> Unit, send: (peerId: String, InputEvent) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("scan", Context.MODE_PRIVATE) }
    var suffix by remember { mutableStateOf(runCatching { ScanSuffix.valueOf(prefs.getString("suffix", null) ?: "") }.getOrDefault(ScanSuffix.Enter)) }
    var continuous by remember { mutableStateOf(prefs.getBoolean("continuous", false)) }
    var problem by remember { mutableStateOf<String?>(null) }
    val unavailable = remember { playServicesProblem(context) }
    var history by remember { mutableStateOf(ScanHistory.decode(prefs.getString("history", null))) }
    var shown by remember { mutableStateOf<ScanRecord?>(null) }
    var target by remember { mutableStateOf(preselected) }
    var confirmClear by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    val targets = scanTargets(peers, preselected)
    val targetPeer = targets.firstOrNull { it.id == target }
    val canType = targetPeer?.isConnected == true

    fun save(list: List<ScanRecord>) {
        history = list
        prefs.edit().putString("history", ScanHistory.encode(list)).apply()
    }

    // The scanner screen comes from Play services and is downloaded the first time. Asking early makes the first scan quicker.
    LaunchedEffect(Unit) {
        if (unavailable == null) runCatching {
            ModuleInstall.getClient(context).installModules(ModuleInstallRequest.newBuilder().addApi(scanner(context)).build())
        }
    }

    fun type(text: String) {
        val peer = targetPeer?.takeIf { it.isConnected } ?: return
        ScanText.events(text, suffix).forEach { send(peer.id, it) }
    }

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
                val record = ScanRecord(text, formatName(barcode.format), System.currentTimeMillis())
                save(ScanHistory.add(history, record))
                shown = record
                when {
                    targetPeer == null -> Unit
                    targetPeer.isConnected -> type(text)
                    else -> problem = "Not connected to ${targetPeer.name}. The code is saved below, tap Type to send it later."
                }
                if (continuous) scan()
            }
            .addOnFailureListener { error ->
                problem = if ("download" in (error.message ?: "").lowercase()) {
                    "Google Play services is still downloading the scanner. Try again in a moment."
                } else {
                    "The scanner couldn't start: ${error.message ?: "unknown error"}"
                }
            }
    }

    FeatureScaffold("Scan barcodes", onBack) {
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                GlassCard {
                    Text(
                        if (targetPeer == null) {
                            "Point the camera at a QR code or barcode. You'll see what's in it before anything is opened."
                        } else {
                            "Point the camera at a code. Its text is typed on ${targetPeer.name} where the cursor is, " +
                                "like a barcode scanner, for example in a spreadsheet or a stock form."
                        },
                        color = Palette.TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    if (unavailable != null) {
                        Text(unavailable, color = Palette.Amber, fontSize = 12.5.sp, lineHeight = 17.sp)
                    } else {
                        PrimaryButton(if (continuous) "Start scanning" else "Scan a code", Modifier.fillMaxWidth(), onClick = scan)
                    }
                    problem?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = Palette.Amber, fontSize = 12.sp, lineHeight = 17.sp)
                    }
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
            }

            if (targets.isNotEmpty()) item {
                GlassCard {
                    ScanTargetPicker("TYPE EACH CODE ON", targets, target) { target = it }
                    if (targetPeer != null) {
                        Spacer(Modifier.height(14.dp))
                        Text("AFTER EACH CODE, PRESS", color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ScanSuffix.entries.forEach { option ->
                                ScanChip(option.label, option == suffix) {
                                    suffix = option
                                    prefs.edit().putString("suffix", option.name).apply()
                                }
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
                    }
                }
            }

            shown?.takeIf { it in history }?.let { record ->
                item(key = "shown") {
                    ResultCard(
                        record,
                        typeOn = targetPeer?.takeIf { canType }?.name,
                        onType = { type(record.text) },
                        onDelete = {
                            save(history - record)
                            shown = null
                        },
                    )
                }
            }

            if (history.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "HISTORY (${history.size})",
                            color = Palette.TextFaint,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp,
                            modifier = Modifier.weight(1f),
                        )
                        HeaderAction("Copy all") { copyText(context, ScanHistory.toText(history), "Scanned codes") }
                        HeaderAction("Share") { exportMenu = true }
                        HeaderAction("Clear", Palette.TextSecondary) { confirmClear = true }
                    }
                }
                items(history, key = { "${it.time}-${it.text.hashCode()}-${it.format}" }) { item ->
                    HistoryRow(
                        item,
                        selected = item == shown,
                        onOpen = { shown = item },
                        onType = if (canType) ({ type(item.text) }) else null,
                    )
                }
            }
            item {
                Text(
                    "The camera and scanner come from Google Play services. Codes are read on this phone, and the last " +
                        "${ScanHistory.MAX} stay in the history here until you delete them." +
                        (targetPeer?.let { " Only the text is sent to ${it.name}." } ?: ""),
                    color = Palette.TextFaint,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the history?") },
            text = { Text("All ${history.size} scanned codes are removed from this phone.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; save(emptyList()); shown = null }) { Text("Clear", color = Palette.Coral) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    if (exportMenu) {
        AlertDialog(
            onDismissRequest = { exportMenu = false },
            title = { Text("Share the history") },
            text = { Text("A CSV file opens in spreadsheets with the time, type, and value of each code. Text is one code per line.") },
            confirmButton = {
                TextButton(onClick = {
                    exportMenu = false
                    runCatching { shareFile(context, writeCsv(context, history), "text/csv") }
                        .onFailure { Toast.makeText(context, "The file couldn't be made.", Toast.LENGTH_SHORT).show() }
                }) { Text("CSV file") }
            },
            dismissButton = {
                TextButton(onClick = { exportMenu = false; shareText(context, ScanHistory.toText(history), "Scanned codes") }) { Text("Text") }
            },
        )
    }
}

private fun writeCsv(context: Context, history: List<ScanRecord>): File {
    val folder = File(context.cacheDir, "exports").apply { mkdirs() }
    folder.listFiles()?.forEach { it.delete() }
    val name = "Scanned codes " + SimpleDateFormat("yyyy-MM-dd HHmm", Locale.ENGLISH).format(Date()) + ".csv"
    return File(folder, name).apply { writeText(ScanHistory.toCsv(history)) }
}

@Composable
private fun HeaderAction(text: String, color: androidx.compose.ui.graphics.Color = Palette.Mint, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private val rowTime = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

@Composable
private fun HistoryRow(item: ScanRecord, selected: Boolean, onOpen: () -> Unit, onType: (() -> Unit)?) {
    val content = remember(item) { BarcodeContent.classify(item.text, item.format) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Palette.PanelRaised else Palette.Panel.copy(alpha = 0.8f))
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(ScanText.preview(item.text), color = Palette.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${item.format} · ${content.label} · ${rowTime.format(Date(item.time))}", color = Palette.TextFaint, fontSize = 11.sp)
        }
        val context = LocalContext.current
        Spacer(Modifier.padding(start = 6.dp))
        ScanAction("Copy") { copyText(context, item.text) }
        if (onType != null) {
            Spacer(Modifier.padding(start = 6.dp))
            ScanAction("Type", Palette.Mint, onType)
        }
    }
}

/** The code that was just scanned or tapped in the history, with the actions that fit it. */
@Composable
private fun ResultCard(record: ScanRecord, typeOn: String?, onType: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val content = remember(record) { BarcodeContent.classify(record.text, record.format) }
    var confirmUrl by remember { mutableStateOf<String?>(null) }
    GlassCard {
        Text(
            "${record.format} · ${content.label}".uppercase(),
            color = Palette.Mint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(8.dp))
        when (content) {
            is BarcodeContent.Wifi -> {
                Field("Network", content.ssid)
                Field("Password", content.password ?: "None (open network)", mono = content.password != null)
                Field("Security", if (content.security == "nopass") "Open" else content.security + if (content.hidden) ", hidden" else "")
            }
            is BarcodeContent.Contact -> {
                content.name?.let { Field("Name", it) }
                content.phones.forEach { Field("Phone", it) }
                content.emails.forEach { Field("Email", it) }
                content.organization?.let { Field("Company", it) }
                content.address?.let { Field("Address", it) }
                content.url?.let { Field("Website", it) }
            }
            else -> SelectionContainer {
                Text(
                    record.text,
                    color = Palette.TextPrimary,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    fontFamily = if (content is BarcodeContent.Url || content is BarcodeContent.Product) FontFamily.Monospace else FontFamily.Default,
                )
            }
        }
        if (content is BarcodeContent.Url) {
            val host = remember(content.url) { runCatching { Uri.parse(content.url).host }.getOrNull() }
            host?.let {
                Text("Opens a page on $it", color = Palette.TextFaint, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (content) {
                is BarcodeContent.Url -> {
                    ScanAction("Open", Palette.Mint) { confirmUrl = content.url }
                    ScanAction("Share") { shareText(context, content.url) }
                }
                is BarcodeContent.Wifi -> {
                    content.password?.let { password -> ScanAction("Copy password", Palette.Mint) { copyText(context, password, "Wi-Fi password") } }
                    if (canSuggestWifi(content)) ScanAction("Connect") { connectWifi(context, content) }
                }
                is BarcodeContent.Contact -> ScanAction("Add to contacts", Palette.Mint) { addContact(context, content) }
                is BarcodeContent.Phone -> {
                    ScanAction("Call", Palette.Mint) { startSafely(context, Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", content.number, null))) }
                    ScanAction("Message") { message(context, content.number, null) }
                }
                is BarcodeContent.Sms -> ScanAction("Message", Palette.Mint) { message(context, content.number, content.body) }
                is BarcodeContent.Email -> ScanAction("Email", Palette.Mint) { email(context, content) }
                is BarcodeContent.Geo -> ScanAction("Open in Maps", Palette.Mint) { maps(context, content) }
                is BarcodeContent.Product -> ScanAction("Search the web", Palette.Mint) { search(context, content.code) }
                is BarcodeContent.Text -> ScanAction("Share") { shareText(context, record.text) }
            }
            ScanAction("Copy") { copyText(context, record.text) }
            if (content !is BarcodeContent.Url && content !is BarcodeContent.Text) ScanAction("Share") { shareText(context, record.text) }
            if (typeOn != null) ScanAction("Type on $typeOn", Palette.Sky, onType)
            ScanAction("Delete", Palette.Coral, onDelete)
        }
    }
    confirmUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { confirmUrl = null },
            title = { Text("Open this link?") },
            text = {
                SelectionContainer { Text(url, fontFamily = FontFamily.Monospace, fontSize = 13.sp) }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUrl = null
                    startSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE), "No browser on this phone can open it.")
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { confirmUrl = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, color = Palette.TextFaint, fontSize = 12.5.sp, modifier = Modifier.width(88.dp).padding(end = 10.dp))
        SelectionContainer(Modifier.weight(1f)) {
            Text(value, color = Palette.TextPrimary, fontSize = 14.sp, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
        }
    }
}

/** Android 11 and newer can offer to save a network through a system dialog, with no permission. WEP isn't supported there. */
private fun canSuggestWifi(wifi: BarcodeContent.Wifi): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    if (wifi.isOpen) return true
    val security = wifi.security.uppercase()
    return security != "WEP" && (wifi.password?.length ?: 0) in 8..63
}

private fun connectWifi(context: Context, wifi: BarcodeContent.Wifi) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val suggestion = runCatching {
        WifiNetworkSuggestion.Builder().setSsid(wifi.ssid).apply {
            if (wifi.hidden) setIsHiddenSsid(true)
            val password = wifi.password
            if (!wifi.isOpen && password != null) {
                if ("SAE" in wifi.security.uppercase() || "WPA3" in wifi.security.uppercase()) setWpa3Passphrase(password) else setWpa2Passphrase(password)
            }
        }.build()
    }.getOrElse {
        Toast.makeText(context, "Android can't save this network. Copy the password and join it in Settings.", Toast.LENGTH_LONG).show()
        return
    }
    val intent = Intent(Settings.ACTION_WIFI_ADD_NETWORKS)
        .putParcelableArrayListExtra(Settings.EXTRA_WIFI_NETWORK_LIST, arrayListOf(suggestion))
    startSafely(context, intent, "This phone can't add networks from apps. Copy the password and join it in Settings.")
}

private fun addContact(context: Context, contact: BarcodeContent.Contact) {
    val intent = Intent(ContactsContract.Intents.Insert.ACTION).setType(ContactsContract.RawContacts.CONTENT_TYPE).apply {
        contact.name?.let { putExtra(ContactsContract.Intents.Insert.NAME, it) }
        listOf(ContactsContract.Intents.Insert.PHONE, ContactsContract.Intents.Insert.SECONDARY_PHONE, ContactsContract.Intents.Insert.TERTIARY_PHONE)
            .zip(contact.phones).forEach { (key, value) -> putExtra(key, value) }
        listOf(ContactsContract.Intents.Insert.EMAIL, ContactsContract.Intents.Insert.SECONDARY_EMAIL, ContactsContract.Intents.Insert.TERTIARY_EMAIL)
            .zip(contact.emails).forEach { (key, value) -> putExtra(key, value) }
        contact.organization?.let { putExtra(ContactsContract.Intents.Insert.COMPANY, it) }
        contact.address?.let { putExtra(ContactsContract.Intents.Insert.POSTAL, it) }
        listOfNotNull(contact.url, contact.note).joinToString("\n").takeIf { it.isNotEmpty() }
            ?.let { putExtra(ContactsContract.Intents.Insert.NOTES, it) }
    }
    startSafely(context, intent, "No contacts app on this phone.")
}

private fun message(context: Context, number: String, body: String?) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
    body?.let { intent.putExtra("sms_body", it) }
    startSafely(context, intent, "No messaging app on this phone.")
}

private fun email(context: Context, mail: BarcodeContent.Email) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(mail.address))
    mail.subject?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
    mail.body?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
    startSafely(context, intent, "No email app on this phone.")
}

private fun maps(context: Context, geo: BarcodeContent.Geo) {
    val point = "${geo.latitude},${geo.longitude}"
    val label = geo.query?.let { "(" + Uri.encode(it) + ")" } ?: ""
    startSafely(context, Intent(Intent.ACTION_VIEW, Uri.parse("geo:$point?q=$point$label")), "No maps app on this phone.")
}

private fun search(context: Context, query: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query))
    } catch (_: ActivityNotFoundException) {
        startSafely(
            context,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))).addCategory(Intent.CATEGORY_BROWSABLE),
            "No browser on this phone.",
        )
    }
}
