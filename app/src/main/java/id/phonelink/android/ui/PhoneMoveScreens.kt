package id.phonelink.android.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.phonelink.android.core.MoveItem
import id.phonelink.android.core.MoveKind
import id.phonelink.android.core.MovePhase
import id.phonelink.android.core.MovePlan
import id.phonelink.android.core.Peer
import id.phonelink.android.service.MoveChoices
import id.phonelink.android.service.MoveSources
import id.phonelink.android.service.PhotoSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Photos and videos: all, or a range. */
private enum class MediaRange(val label: String) { All("All"), Year("Last 12 months"), Month("Last 30 days"), Custom("Pick dates") }

/**
 * On the old phone: move contacts, photos and videos, and folders to the new phone [peer]. Then progress with pause,
 * resume, and the time left, and a summary. A dropped connection waits and goes on where it stopped.
 */
@Composable
fun PhoneMoveScreen(peer: Peer, link: PhoneMoreLink?, onBack: () -> Unit) {
    link ?: return
    BackHandler(onBack = onBack)
    val plan by link.movePlan.collectAsStateWithLifecycle()
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            "‹ ${peer.name}", color = Palette.TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(vertical = 8.dp, horizontal = 4.dp),
        )
        Text("Move to ${peer.name}", color = Palette.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        val current = plan
        when {
            current == null -> MoveChooser(peer, link)
            current.peerId != peer.id -> GlassCard {
                Text("A move to ${current.peerName} is in progress.", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("One move at a time. Finish or cancel it on the page for ${current.peerName}.", color = Palette.TextSecondary, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                SecondaryButton("Cancel that move", Modifier.fillMaxWidth(), tint = Palette.Coral, onClick = link.cancelMove)
            }
            else -> MoveProgressView(peer, current, link)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveChooser(peer: Peer, link: PhoneMoreLink) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var contacts by remember { mutableStateOf(MoveSources.canReadContacts(context)) }
    var media by remember { mutableStateOf(PhotoSync.canRead(context)) }
    var videos by remember { mutableStateOf(PhotoSync.canReadVideos(context)) }
    var range by remember { mutableStateOf(MediaRange.All) }
    var custom by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var picking by remember { mutableStateOf(false) }
    var folders by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var counted by remember { mutableStateOf<List<MoveItem>?>(null) }
    var counting by remember { mutableStateOf(false) }

    val askContacts = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { contacts = it }
    val askPhotos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        media = PhotoSync.canRead(context)
        videos = videos && PhotoSync.canReadVideos(context)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Kept, so the move can go on after the app was closed.
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if (uri !in folders) folders = folders + uri
            counted = null
        }
    }

    GlassCard {
        SettingRow("Contacts", "All contacts as one file. On ${peer.name}, tap Import contacts in the notification.", contacts) { on ->
            counted = null
            if (on && !MoveSources.canReadContacts(context)) askContacts.launch(Manifest.permission.READ_CONTACTS) else contacts = on
        }
        Spacer(Modifier.height(12.dp))
        SettingRow("Photos and videos", "To Pictures › DyskLink on ${peer.name}. Copies already there are skipped.", media) { on ->
            counted = null
            if (on && !PhotoSync.canRead(context)) askPhotos.launch(arrayOf(PhotoSync.permission, PhotoSync.videoPermission)) else media = on
        }
        if (media) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MediaRange.entries.forEach { option ->
                    val selected = option == range
                    Text(
                        option.label, color = if (selected) Palette.Ink else Palette.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Palette.Mint else Palette.PanelRaised)
                            .clickable {
                                counted = null
                                range = option
                                if (option == MediaRange.Custom) picking = true
                            }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
            custom?.takeIf { range == MediaRange.Custom }?.let { (from, to) ->
                val format = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                Text("${format.format(java.util.Date(from))} to ${format.format(java.util.Date(to))}", color = Palette.TextSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            SettingRow("Include videos", "Videos can be large, so they take longer.", videos) { on ->
                counted = null
                if (on && !PhotoSync.canReadVideos(context)) askPhotos.launch(arrayOf(PhotoSync.videoPermission)) else videos = on
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Files", color = Palette.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text("Pick folders, such as Download or Documents. They go to Download › DyskLink on ${peer.name}, with their subfolders.", color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        folders.forEach { uri ->
            Row(Modifier.padding(top = 6.dp)) {
                Text(MoveSources.treeName(context, uri), color = Palette.TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("Remove", color = Palette.Coral, fontSize = 13.sp, modifier = Modifier.clickable { folders = folders - uri; counted = null })
            }
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Add folder", Modifier.fillMaxWidth()) { pickFolder.launch(null) }
    }
    Spacer(Modifier.height(12.dp))
    val items = counted
    if (items == null) {
        PrimaryButton(if (counting) "Counting…" else "Next", Modifier.fillMaxWidth()) {
            if (counting) return@PrimaryButton
            counting = true
            val now = System.currentTimeMillis()
            val (from, to) = when (range) {
                MediaRange.All -> null to null
                MediaRange.Year -> now - 365L * 86_400_000 to null
                MediaRange.Month -> now - 30L * 86_400_000 to null
                MediaRange.Custom -> custom?.let { it.first to it.second + 86_399_999 } ?: (null to null)
            }
            val choices = MoveChoices(contacts, media, videos && media, from, to, folders)
            scope.launch {
                counted = withContext(Dispatchers.IO) { MoveSources.collect(context, choices) }
                counting = false
            }
        }
    } else {
        GlassCard {
            val bytes = items.sumOf { it.size }
            Text("${items.size} items, ${formatBytes(bytes)}", color = Palette.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text(
                listOf(
                    items.count { it.kind == MoveKind.Contacts }.takeIf { it > 0 }?.let { "contacts" },
                    items.count { it.kind == MoveKind.Media }.takeIf { it > 0 }?.let { "$it photos and videos" },
                    items.count { it.kind == MoveKind.File }.takeIf { it > 0 }?.let { "$it files" },
                ).filterNotNull().joinToString(", ").ifEmpty { "Nothing to move" },
                color = Palette.TextSecondary, fontSize = 12.sp,
            )
            peer.info?.storageFreeBytes?.let { free ->
                Text("${formatBytes(free)} free on ${peer.name}. At least 1 GB stays free there.", color = if (free - bytes < (1L shl 30)) Palette.Amber else Palette.TextFaint, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            PrimaryButton("Start move", Modifier.fillMaxWidth()) {
                if (items.isNotEmpty()) link.startMove(peer.id, peer.name, items)
            }
            Spacer(Modifier.height(6.dp))
            Text("Keep both phones on the same Wi-Fi with DyskLink open. If they lose each other, it goes on where it stopped.", color = Palette.TextFaint, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }

    if (picking) {
        val state = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    val from = state.selectedStartDateMillis
                    if (from != null) custom = from to (state.selectedEndDateMillis ?: from)
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state, Modifier.height(480.dp))
        }
    }
}

@Composable
private fun MoveProgressView(peer: Peer, plan: MovePlan, link: PhoneMoreLink) {
    // The time left counts down between items too.
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(plan.phase) {
        while (plan.phase == MovePhase.Running) {
            delay(1_000)
            tick++
        }
    }
    val progress = remember(plan, tick) { link.moveProgress(plan) } ?: return
    GlassCard {
        val status = when (plan.phase) {
            MovePhase.Asking -> "Asking ${peer.name}…"
            MovePhase.Running -> "Moving"
            MovePhase.Paused -> "Paused"
            MovePhase.Waiting -> "Waiting for ${peer.name} to connect"
            MovePhase.Done -> "Done"
        }
        Text(status, color = if (plan.phase == MovePhase.Done) Palette.Mint else Palette.TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        plan.error?.let { Text(it, color = Palette.Amber, fontSize = 12.sp, lineHeight = 17.sp) }
        Spacer(Modifier.height(10.dp))
        val fraction = if (progress.totalBytes > 0) progress.doneBytes.toFloat() / progress.totalBytes else if (progress.totalItems > 0) progress.doneItems.toFloat() / progress.totalItems else 1f
        LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("${progress.doneItems} of ${progress.totalItems}, ${formatBytes(progress.doneBytes)} of ${formatBytes(progress.totalBytes)}", color = Palette.TextSecondary, fontSize = 13.sp)
        if (plan.phase == MovePhase.Running) {
            val eta = progress.etaMs
            Text(if (eta == null) "Working out the time left…" else "About ${minutes(eta)} left", color = Palette.TextFaint, fontSize = 12.sp)
        }
        if (progress.skipped > 0 || progress.failed > 0) {
            Text(
                listOfNotNull(
                    progress.skipped.takeIf { it > 0 }?.let { "$it already on ${peer.name}" },
                    progress.failed.takeIf { it > 0 }?.let { "$it couldn't be sent" },
                ).joinToString(", "),
                color = if (progress.failed > 0) Palette.Amber else Palette.TextFaint, fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        when (plan.phase) {
            MovePhase.Done -> {
                Text(
                    "${progress.sent} sent. On ${peer.name}: photos in Pictures › DyskLink, files in Download › DyskLink" +
                        (if (plan.items.any { it.kind == MoveKind.Contacts }) ", and Import contacts in its notification." else "."),
                    color = Palette.TextSecondary, fontSize = 12.sp, lineHeight = 17.sp,
                )
                plan.items.filter { it.error != null && it.status == id.phonelink.android.core.MoveItemStatus.Failed }.take(5).forEach {
                    Text("${it.name}: ${it.error}", color = Palette.Amber, fontSize = 11.sp)
                }
                Spacer(Modifier.height(10.dp))
                PrimaryButton("Finish", Modifier.fillMaxWidth(), onClick = link.cancelMove)
            }
            MovePhase.Paused -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Resume", Modifier.weight(1f), onClick = link.resumeMove)
                SecondaryButton("Cancel", Modifier.weight(1f), tint = Palette.Coral, onClick = link.cancelMove)
            }
            else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton("Pause", Modifier.weight(1f), onClick = link.pauseMove)
                SecondaryButton("Cancel", Modifier.weight(1f), tint = Palette.Coral, onClick = link.cancelMove)
            }
        }
    }
}

private fun minutes(ms: Long): String {
    val minutes = (ms + 59_999) / 60_000
    return when {
        minutes < 1 -> "1 min"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}
