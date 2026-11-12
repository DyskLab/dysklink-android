@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package id.phonelink.android.ui

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import id.phonelink.android.core.AppAudit
import id.phonelink.android.core.AppEntry
import id.phonelink.android.core.AppSort
import id.phonelink.android.core.CleanItem
import id.phonelink.android.core.CleanKind
import id.phonelink.android.core.CleanReport
import id.phonelink.android.core.PermissionGroup
import id.phonelink.android.core.PhoneCleaner
import id.phonelink.android.service.PhoneApps
import id.phonelink.android.service.WebFiles
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ToolsTab(val label: String) { Storage("Storage"), Apps("Apps"), Permissions("Permissions") }

@Composable
fun PhoneToolsHomeCard(onOpen: () -> Unit) {
    val (free, total) = remember { PhoneApps.storage() }
    GlassCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlatIcon(Icons.Rounded.CleaningServices, Palette.Violet)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Phone tools", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (total > 0) "${formatSize(free)} free of ${formatSize(total)}. Clean up, apps, and permissions."
                    else "Clean up storage, check apps and their permissions.",
                    color = Palette.TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}

@Composable
fun PhoneToolsScreen(onBack: () -> Unit, onAllowFiles: () -> Unit) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(ToolsTab.Storage) }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var usage by remember { mutableStateOf(PhoneApps.hasUsageAccess(context)) }
    var appsVersion by remember { mutableIntStateOf(0) }
    var allowedFiles by remember { mutableStateOf(WebFiles.canReadFiles(context)) }

    // Coming back from settings may have turned on Usage access or All files access.
    LifecycleResumeEffect(Unit) {
        val nowUsage = PhoneApps.hasUsageAccess(context)
        if (nowUsage != usage) {
            usage = nowUsage
            appsVersion++
        }
        allowedFiles = WebFiles.canReadFiles(context)
        // An app uninstalled from its settings page leaves the list right away; sizes are read again behind it.
        apps?.let { list ->
            val left = list.filter { isInstalled(context, it.packageName) }
            if (left.size != list.size) {
                apps = left
                appsVersion++
            }
        }
        onPauseOrDispose { }
    }
    // Apps removed or added while this screen is open, for example from a notification or the Play Store.
    DisposableEffect(Unit) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: android.content.Intent) {
                val name = intent.data?.schemeSpecificPart
                if (intent.action == android.content.Intent.ACTION_PACKAGE_REMOVED && name != null &&
                    !intent.getBooleanExtra(android.content.Intent.EXTRA_REPLACING, false)
                ) {
                    apps = apps?.filterNot { it.packageName == name }
                }
                appsVersion++
            }
        }
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
            addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
            addDataScheme("package")
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    LaunchedEffect(appsVersion) {
        apps = withContext(Dispatchers.IO) { runCatching { PhoneApps.load(context) }.getOrDefault(emptyList()) }
    }

    FeatureScaffold("Phone tools", onBack) {
        Row(
            Modifier.fillMaxWidth().clip(CircleShape).background(Palette.PanelRaised).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ToolsTab.entries.forEach { option ->
                val on = option == tab
                Text(
                    option.label,
                    color = if (on) Palette.Ink else Palette.TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(if (on) Palette.Mint else Color.Transparent)
                        .clickable { tab = option }
                        .padding(vertical = 9.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        when (tab) {
            ToolsTab.Storage -> StorageTab(allowedFiles, onAllowFiles)
            ToolsTab.Apps -> AppsTab(apps, usage, context)
            ToolsTab.Permissions -> PermissionsTab(apps, context)
        }
    }
}

// Storage

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.StorageTab(allowed: Boolean, onAllowFiles: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<CleanReport?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var open by remember { mutableStateOf(setOf<CleanKind>()) }
    var confirm by remember { mutableStateOf(false) }
    var storage by remember { mutableStateOf(PhoneApps.storage()) }
    val cancel = remember { AtomicBoolean(false) }
    val root = remember { Environment.getExternalStorageDirectory() }
    DisposableEffect(Unit) { onDispose { cancel.set(true) } }

    fun scan() {
        if (scanning) return
        scanning = true
        selected = emptySet()
        cancel.set(false)
        scope.launch {
            report = withContext(Dispatchers.IO) { PhoneCleaner(root).scan { cancel.get() } }
            storage = PhoneApps.storage()
            scanning = false
        }
    }

    val chosen = report?.items.orEmpty().filter { it.path in selected }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            GlassCard {
                val (free, total) = storage
                Text("${formatSize(free)} free", color = Palette.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("of ${formatSize(total)} on this phone", color = Palette.TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                UsageBar(if (total > 0) 1f - free.toFloat() / total else 0f)
                Spacer(Modifier.height(14.dp))
                if (!allowed) {
                    Text(
                        "To look through the phone's files, DyskLink needs All files access. It only removes what you pick.",
                        color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton("Allow", Modifier.fillMaxWidth(), onClick = onAllowFiles)
                } else {
                    PrimaryButton(
                        when {
                            scanning -> "Looking through files…"
                            report == null -> "Find things to clean"
                            else -> "Look again"
                        },
                        Modifier.fillMaxWidth(),
                    ) { scan() }
                    report?.let {
                        Spacer(Modifier.height(8.dp))
                        Text("Looked through ${it.scannedFiles} files. Folders inside Android are skipped, because apps need them.",
                            color = Palette.TextFaint, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }
            }
        }
        report?.let { found ->
            CleanKind.entries.forEach { kind ->
                val list = found.of(kind)
                item(key = kind.name) {
                    CleanGroup(kind, list, selected, kind in open,
                        onToggleOpen = { open = if (kind in open) open - kind else open + kind },
                        onSelectAll = { all -> selected = if (all) selected + list.map { it.path } else selected - list.map { it.path }.toSet() })
                }
                if (kind in open) {
                    items(list, key = { kind.name + it.path }) { entry ->
                        CleanRow(entry, entry.path in selected, onView = { viewFile(context, File(root, entry.path)) }) {
                            selected = if (entry.path in selected) selected - entry.path else selected + entry.path
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(if (chosen.isEmpty()) 12.dp else 80.dp)) }
    }
    if (chosen.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        PrimaryButton("Delete ${chosen.size} · ${formatSize(chosen.sumOf { it.size })}", Modifier.fillMaxWidth()) { confirm = true }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Delete ${chosen.size} items?") },
            text = {
                Text("${formatSize(chosen.sumOf { it.size })} will be removed from the phone for good. They don't go to a trash folder.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    val picked = chosen
                    scope.launch {
                        val (freed, failed) = withContext(Dispatchers.IO) { PhoneCleaner(root).delete(picked) }
                        // Tell the gallery the files are gone.
                        val removed = picked.filterNot { it in failed }.map { File(root, it.path).path }.toTypedArray()
                        if (removed.isNotEmpty()) MediaScannerConnection.scanFile(context, removed, null, null)
                        Toast.makeText(
                            context,
                            "Freed ${formatSize(freed)}" + if (failed.isNotEmpty()) ". ${failed.size} couldn't be removed." else ".",
                            Toast.LENGTH_LONG,
                        ).show()
                        report = report?.let { r -> r.copy(items = r.items.filter { it in failed || it !in picked }) }
                        selected = emptySet()
                        storage = PhoneApps.storage()
                    }
                }) { Text("Delete", color = Palette.Coral) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun UsageBar(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).height(8.dp).clip(CircleShape).background(Palette.Accent))
    }
}

@Composable
private fun CleanGroup(
    kind: CleanKind,
    list: List<CleanItem>,
    selected: Set<String>,
    isOpen: Boolean,
    onToggleOpen: () -> Unit,
    onSelectAll: (Boolean) -> Unit,
) {
    val picked = list.count { it.path in selected }
    GlassCard(onClick = if (list.isEmpty()) null else onToggleOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(kind.title, color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(kind.blurb, color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    if (list.isEmpty()) "None" else if (kind == CleanKind.Empty) "${list.size}" else formatSize(list.sumOf { it.size }),
                    color = if (list.isEmpty()) Palette.TextFaint else Palette.Mint,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
                if (list.isNotEmpty()) Text("${list.size} items ${if (isOpen) "▴" else "▾"}", color = Palette.TextFaint, fontSize = 11.sp)
            }
        }
        if (isOpen && list.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (picked == list.size) "Unselect all" else "Select all",
                color = Palette.Mint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(CircleShape).clickable { onSelectAll(picked != list.size) }.padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun CleanRow(item: CleanItem, on: Boolean, onView: () -> Unit, onToggle: () -> Unit) {
    val dates = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (on) Palette.Mint.copy(alpha = 0.08f) else Palette.Panel.copy(alpha = 0.6f))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(RoundedCornerShape(7.dp))
                .background(if (on) Palette.Mint else Color.Transparent)
                .border(1.5.dp, if (on) Palette.Mint else Palette.TextFaint, RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (on) Icon(Icons.Rounded.Check, null, tint = Palette.Ink, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        if (item.isFolder) FlatIcon(Icons.Rounded.Folder, Palette.Sky, size = 30.dp) else FlatIcon(FlatIcons.file(item.name), Palette.Sky, size = 30.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = Palette.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(item.path.substringBeforeLast('/', "Phone"), color = Palette.TextFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (!item.isFolder) Text(formatSize(item.size), color = Palette.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(dates.format(Date(item.modified)), color = Palette.TextFaint, fontSize = 10.sp)
        }
        if (!item.isFolder) {
            Spacer(Modifier.width(8.dp))
            Text(
                "View",
                color = Palette.Mint,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Palette.Mint.copy(alpha = 0.12f))
                    .clickable(onClick = onView)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }
}

/** Opens a found file in an app that can show it, so it can be checked before deleting. */
private fun viewFile(context: Context, file: File) {
    if (!file.isFile) return Toast.makeText(context, "That file is gone.", Toast.LENGTH_SHORT).show()
    val uri = runCatching { androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file) }.getOrNull()
        ?: return Toast.makeText(context, "That file can't be opened.", Toast.LENGTH_SHORT).show()
    val type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, type)
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    // An APK opened as an APK would start installing it, so it is offered as a plain file instead.
    if (file.extension.equals("apk", ignoreCase = true)) {
        intent.setDataAndType(uri, "application/octet-stream")
    }
    runCatching { context.startActivity(android.content.Intent.createChooser(intent, file.name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Toast.makeText(context, "No app on this phone can open ${file.name}.", Toast.LENGTH_SHORT).show() }
}

// Apps

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.AppsTab(apps: List<AppEntry>?, usage: Boolean, context: Context) {
    var sort by rememberSaveable { mutableStateOf(AppSort.Size) }
    var unusedOnly by rememberSaveable { mutableStateOf(false) }
    val now = remember { System.currentTimeMillis() }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!usage) {
            item {
                GlassCard {
                    Text("Allow Usage access", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Android only shares how big each app is and when it was last opened with apps that have Usage access. " +
                            "Find DyskLink in the list and turn it on. Nothing leaves the phone.",
                        color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton("Open settings", Modifier.fillMaxWidth()) {
                        runCatching { context.startActivity(PhoneApps.usageAccessIntent(context)) }
                    }
                }
            }
        }
        if (apps == null) {
            item { Text("Reading apps…", color = Palette.TextSecondary, fontSize = 13.sp) }
            return@LazyColumn
        }
        val unused = apps.filter { AppAudit.isUnused(it, now) }
        val shown = AppAudit.sorted(if (unusedOnly) unused else apps, sort)
        item {
            val total = apps.mapNotNull { it.sizeBytes }.sum()
            Text(
                "${apps.size} apps" + (if (usage) " · ${formatSize(total)} · ${unused.size} not used in ${AppAudit.UNUSED_DAYS} days" else ""),
                color = Palette.TextSecondary, fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppSort.entries.forEach { option ->
                    if (usage || option == AppSort.Name) ToolChip(option.label, sort == option) { sort = option }
                }
                if (usage) ToolChip("Not used lately (${unused.size})", unusedOnly, Palette.Amber) { unusedOnly = !unusedOnly }
            }
        }
        items(shown, key = { it.packageName }) { app ->
            AppRow(app, now, context) { runCatching { context.startActivity(PhoneApps.appInfoIntent(app.packageName)) } }
        }
        if (shown.isEmpty()) item { Text("No apps here.", color = Palette.TextFaint, fontSize = 13.sp) }
        item {
            Text(
                "Tap an app to open its Android settings, where it can be uninstalled, stopped, or its storage cleared.",
                color = Palette.TextFaint, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AppIcon(packageName: String, context: Context, size: Int = 40) {
    val bitmap = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(size * 3, size * 3).asImageBitmap() }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size.dp))
    } else {
        FlatIcon(Icons.Rounded.Apps, Palette.TextSecondary, size = size.dp)
    }
}

@Composable
private fun AppRow(app: AppEntry, now: Long, context: Context, onClick: () -> Unit) {
    val unused = AppAudit.isUnused(app, now)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Panel.copy(alpha = 0.7f))
            .border(1.dp, Palette.Stroke, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName, context)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.name, color = Palette.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val used = AppAudit.lastUsedText(app, now)
            if (used.isNotEmpty()) Text(used, color = if (unused) Palette.Amber else Palette.TextFaint, fontSize = 11.5.sp)
        }
        app.sizeBytes?.let { Text(formatSize(it), color = Palette.TextSecondary, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold) }
        Text("  ›", color = Palette.TextFaint, fontSize = 16.sp)
    }
}

// Permissions

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PermissionsTab(apps: List<AppEntry>?, context: Context) {
    var open by remember { mutableStateOf(setOf<PermissionGroup>()) }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (apps == null) {
            item { Text("Reading apps…", color = Palette.TextSecondary, fontSize = 13.sp) }
            return@LazyColumn
        }
        item {
            Text(
                "Which apps are allowed to do sensitive things. Tap an app to change it in Android settings.",
                color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
        PermissionGroup.entries.forEach { group ->
            val holders = apps.filter { group in it.groups }.sortedBy { it.name.lowercase() }
            permissionGroup(group, holders, group in open, context) { open = if (group in open) open - group else open + group }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

private fun LazyListScope.permissionGroup(group: PermissionGroup, holders: List<AppEntry>, isOpen: Boolean, context: Context, onToggle: () -> Unit) {
    val strong = group == PermissionGroup.Accessibility || group == PermissionGroup.Notifications || group == PermissionGroup.BackgroundLocation
    item(key = group.name) {
        GlassCard(onClick = if (holders.isEmpty()) null else onToggle) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlatIcon(Icons.Rounded.Shield, if (strong && holders.isNotEmpty()) Palette.Amber else Palette.Sky, size = 34.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.title, color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(group.blurb, color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (holders.isEmpty()) "None" else "${holders.size} ${if (isOpen) "▴" else "▾"}",
                    color = if (holders.isEmpty()) Palette.TextFaint else if (strong) Palette.Amber else Palette.Mint,
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
    if (isOpen) {
        items(holders, key = { group.name + it.packageName }) { app ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.Panel.copy(alpha = 0.6f))
                    .clickable { runCatching { context.startActivity(PhoneApps.appInfoIntent(app.packageName)) } }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(app.packageName, context, size = 30)
                Spacer(Modifier.width(10.dp))
                Text(app.name, color = Palette.TextPrimary, fontSize = 13.5.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (app.isSystem) Text("System", color = Palette.TextFaint, fontSize = 11.sp)
                Text("  ›", color = Palette.TextFaint, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun ToolChip(text: String, on: Boolean, color: Color = Palette.Mint, onClick: () -> Unit) {
    Text(
        text,
        color = if (on) Palette.Ink else Palette.TextSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (on) color else Palette.PanelRaised)
            .border(1.dp, if (on) color else Palette.Stroke, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

private fun isInstalled(context: Context, packageName: String): Boolean =
    runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.isSuccess
