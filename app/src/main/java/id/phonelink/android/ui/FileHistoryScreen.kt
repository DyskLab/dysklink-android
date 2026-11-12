package id.phonelink.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.phonelink.android.core.Peer
import id.phonelink.android.core.Transfer
import id.phonelink.android.core.TransferStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

enum class HistoryFilter(val label: String, val symbol: String, val tint: Color) {
    All("All", "≡", Palette.TextPrimary),
    Received("Received", "↓", Palette.Mint),
    Sent("Sent", "↑", Palette.Sky),
    Failed("Failed", "!", Palette.Coral),
    ;

    fun includes(transfer: Transfer): Boolean = when (this) {
        All -> true
        Received -> !transfer.outgoing && transfer.status != TransferStatus.Failed
        Sent -> transfer.outgoing && transfer.status != TransferStatus.Failed
        Failed -> transfer.status == TransferStatus.Failed
    }
}

/** Groups by the day each transfer started. Old history without a time goes last, under "Earlier". */
internal fun historyGroups(transfers: List<Transfer>, today: LocalDate, zone: ZoneId): List<Pair<String, List<Transfer>>> {
    val dated = transfers.groupBy { t -> t.time.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } }
    val days = dated.keys.filterNotNull().sortedDescending()
    val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val result = days.map { day ->
        val label = when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> day.format(format)
        }
        label to dated.getValue(day).sortedByDescending { it.time }
    }
    return result + listOfNotNull(dated[null]?.let { "Earlier" to it })
}

/** A list key that stays unique when two devices happen to use the same transfer ID. */
internal fun historyKey(transfer: Transfer): String = "${transfer.peerId}:${transfer.id}:${transfer.time}"

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1e3)
    else -> "$bytes B"
}

@Composable
fun FileHistoryScreen(
    transfers: List<Transfer>,
    peers: List<Peer>,
    initialPeer: String? = null,
    onBack: () -> Unit,
    onOpen: (Transfer) -> Unit,
    onShare: (Transfer) -> Unit,
    onRemove: (Transfer) -> Unit,
    onClear: () -> Unit,
    onOpenFolder: () -> Unit,
) {
    var filter by remember { mutableStateOf(HistoryFilter.All) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var peerFilter by remember { mutableStateOf(initialPeer) }
    val names = remember(peers) { peers.associate { it.id to it.name } }
    val all = transfers
    val transfers = remember(all, peerFilter) { peerFilter?.let { id -> all.filter { it.peerId == id } } ?: all }
    val matching = remember(transfers, filter, query) {
        transfers.filter { filter.includes(it) && (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true)) }
    }
    val counts = remember(transfers) { HistoryFilter.entries.associateWith { option -> transfers.count { option.includes(it) } } }
    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now(zone)
    val groups = remember(matching, today) { historyGroups(matching, today, zone) }
    val timeFormat = remember { DateTimeFormatter.ofPattern("HH:mm") }

    FeatureScaffold("File history", onBack) {
        // No spacing between items, so a day's rows join into one card; other items add their own top gap.
        LazyColumn(Modifier.weight(1f)) {
          item(key = "controls") {
          Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Filter tabs, each with its own count.
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .clip(CircleShape)
                    .background(Palette.Panel)
                    .border(1.dp, Palette.Stroke, CircleShape)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HistoryFilter.entries.forEach { option ->
                    val on = option == filter
                    val count = counts[option] ?: 0
                    Row(
                        Modifier
                            .clip(CircleShape)
                            .background(if (on) Palette.PanelRaised else Color.Transparent)
                            .border(1.dp, if (on) option.tint.copy(alpha = 0.45f) else Color.Transparent, CircleShape)
                            .clickable { filter = option }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(option.symbol, color = if (on) option.tint else Palette.TextFaint, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(5.dp))
                        Text(option.label, color = if (on) Palette.TextPrimary else Palette.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "$count",
                            color = if (on) option.tint else Palette.TextFaint,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background((if (on) option.tint else Palette.TextFaint).copy(alpha = 0.14f))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                    }
                }
            }

            peerFilter?.let { id ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Only ${names[id] ?: "this device"}   ✕",
                        color = Palette.Sky,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(Palette.Sky.copy(alpha = 0.12f))
                            .clickable { peerFilter = null }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text("${all.size} on all devices", color = Palette.TextFaint, fontSize = 12.sp)
                }
            }

            // Search
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(CircleShape)
                    .background(Palette.PanelRaised)
                    .border(1.dp, Palette.Stroke, CircleShape)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) Text("Search file names", color = Palette.TextFaint, fontSize = 14.sp)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it.replace("\n", "").take(100) },
                    singleLine = true,
                    textStyle = TextStyle(color = Palette.TextPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(Palette.Mint),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

          }
          }

            if (matching.isEmpty()) item(key = "empty") {
                GlassCard(Modifier.padding(top = 12.dp)) {
                    Text(
                        when {
                            transfers.isEmpty() -> "No files yet. Files you send to your Mac or receive from it show up here."
                            query.isNotBlank() -> "No file names match \"${query.trim()}\"."
                            else -> "Nothing here."
                        },
                        color = Palette.TextSecondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
            }

            groups.forEach { (label, items) ->
                item(key = "day:$label") {
                    Text(label.uppercase(), color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))
                }
                // One lazy row per transfer, drawn as slices of one card, so 500 entries don't all compose at once.
                // IDs come from other devices, so two can share one: the key also names the device and the time.
                val rows = items.distinctBy(::historyKey)
                itemsIndexed(rows, key = { _, transfer -> historyKey(transfer) }) { index, transfer ->
                    Column(Modifier.cardSegment(first = index == 0, last = index == rows.lastIndex)) {
                        if (index > 0) Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(1.dp).background(Palette.Stroke))
                        HistoryRow(
                            transfer = transfer,
                            peerName = names[transfer.peerId] ?: "a device that's no longer paired",
                            time = transfer.time.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone).format(timeFormat) },
                            expanded = selected == transfer.id,
                            onTap = {
                                val canOpen = !transfer.outgoing && transfer.status == TransferStatus.Done && transfer.uri != null
                                if (canOpen && selected != transfer.id) onOpen(transfer)
                                else selected = if (selected == transfer.id) null else transfer.id
                            },
                            onMore = { selected = if (selected == transfer.id) null else transfer.id },
                            onOpen = { onOpen(transfer) },
                            onShare = { onShare(transfer) },
                            onRemove = {
                                selected = null
                                onRemove(transfer)
                            },
                        )
                    }
                }
            }

            item(key = "footer") {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Open folder", Modifier.weight(1f), onClick = onOpenFolder)
                if (peerFilter == null && transfers.any { it.status != TransferStatus.Active }) {
                    SecondaryButton("Clear history", Modifier.weight(1f), tint = Palette.Coral) { confirmClear = true }
                }
            }
            Text(
                "Received files are saved in Download/DyskLink. Removing an entry here only removes it from this list, " +
                    "the file stays on the phone. The newest 500 transfers are kept.",
                color = Palette.TextFaint,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(12.dp))
            }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear file history?") },
            text = { Text("The list is cleared. Files already saved on this phone stay where they are.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text("Clear", color = Palette.Coral) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

/** The files card on a Mac's page: transfers in progress and the newest few, in the same style as the history page. */
@Composable
fun DeviceFilesCard(
    transfers: List<Transfer>,
    peerName: String,
    onSeeAll: () -> Unit,
    onOpen: (Transfer) -> Unit,
    onShare: (Transfer) -> Unit,
    onRemove: (Transfer) -> Unit,
    title: String = "FILES WITH THIS MAC",
    limit: Int = 4,
) {
    var selected by remember { mutableStateOf<String?>(null) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val active = transfers.filter { it.status == TransferStatus.Active }
    val shown = active + transfers.filter { it.status != TransferStatus.Active }.take(limit)
    val received = transfers.count { !it.outgoing && it.status == TransferStatus.Done }
    val sent = transfers.count { it.outgoing && it.status == TransferStatus.Done }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Palette.TextFaint, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                Text(
                    when {
                        active.isNotEmpty() -> "${active.size} in progress"
                        transfers.isEmpty() -> "Nothing sent or received yet"
                        else -> "↓ $received received · ↑ $sent sent"
                    },
                    color = if (active.isNotEmpty()) Palette.Sky else Palette.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (transfers.isNotEmpty()) {
                Text(
                    "See all ›",
                    color = Palette.Mint,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onSeeAll).padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
        if (shown.isNotEmpty()) Spacer(Modifier.height(6.dp))
        shown.forEachIndexed { index, transfer ->
            if (index > 0) Box(Modifier.fillMaxWidth().padding(vertical = 2.dp).height(1.dp).background(Palette.Stroke))
            val date = transfer.time.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone) }
            HistoryRow(
                transfer = transfer,
                peerName = peerName,
                time = date?.let {
                    when (it.toLocalDate()) {
                        today -> it.format(DateTimeFormatter.ofPattern("HH:mm"))
                        today.minusDays(1) -> "Yesterday"
                        else -> it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT))
                    }
                },
                expanded = selected == transfer.id,
                onTap = {
                    val canOpen = !transfer.outgoing && transfer.status == TransferStatus.Done && transfer.uri != null
                    if (canOpen && selected != transfer.id) onOpen(transfer)
                    else selected = if (selected == transfer.id) null else transfer.id
                },
                onMore = { selected = if (selected == transfer.id) null else transfer.id },
                onOpen = { onOpen(transfer) },
                onShare = { onShare(transfer) },
                onRemove = {
                    selected = null
                    onRemove(transfer)
                },
                showPeer = false,
            )
        }
        if (transfers.size > shown.size) {
            Spacer(Modifier.height(4.dp))
            Text(
                "${transfers.size - shown.size} more in File history",
                color = Palette.TextFaint,
                fontSize = 12.sp,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onSeeAll).padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun HistoryRow(
    transfer: Transfer,
    peerName: String,
    time: String?,
    expanded: Boolean,
    onTap: () -> Unit,
    onMore: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRemove: () -> Unit,
    showPeer: Boolean = true,
) {
    val direction = when {
        !showPeer -> if (transfer.outgoing) "Sent" else "Received"
        transfer.outgoing -> "To $peerName"
        else -> "From $peerName"
    }
    val statusColor = when (transfer.status) {
        TransferStatus.Active -> Palette.Sky
        TransferStatus.Done -> if (transfer.outgoing) Palette.Sky else Palette.Mint
        TransferStatus.Failed -> Palette.Coral
    }
    val received = !transfer.outgoing && transfer.status == TransferStatus.Done && transfer.uri != null
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onTap).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(statusColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.Icon(FlatIcons.file(transfer.name), contentDescription = null, tint = statusColor, modifier = Modifier.size(22.dp))
                Box(
                    Modifier.align(Alignment.BottomEnd).size(15.dp).clip(CircleShape).background(statusColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            transfer.status == TransferStatus.Failed -> "!"
                            transfer.outgoing -> "↑"
                            else -> "↓"
                        },
                        color = Palette.Ink,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(transfer.name, color = Palette.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        formatSize(transfer.size),
                        direction,
                        time,
                    ).joinToString(" · "),
                    color = Palette.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (transfer.status != TransferStatus.Active) {
                Text(
                    "⋯",
                    color = Palette.TextSecondary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onMore).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        if (transfer.status == TransferStatus.Active && transfer.size > 0) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Palette.Stroke)) {
                Box(Modifier.fillMaxWidth((transfer.transferred.toFloat() / transfer.size).coerceIn(0f, 1f)).fillMaxHeight().background(Palette.Sky))
            }
        }
        if (transfer.status == TransferStatus.Failed) {
            Text(transfer.detail ?: "Failed", color = Palette.Coral, fontSize = 12.sp, modifier = Modifier.padding(start = 52.dp, top = 2.dp))
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.padding(start = 52.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (received) {
                    RowAction("Open", Palette.Mint, onOpen)
                    RowAction("Share", Palette.Sky, onShare)
                }
                RowAction("Remove from list", Palette.Coral, onRemove)
            }
        }
    }
}

@Composable
private fun RowAction(text: String, color: Color, onClick: () -> Unit) {
    Text(
        text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** One row of a card split across lazy items: the panel, with the outline and rounded corners only at its ends. */
private fun Modifier.cardSegment(first: Boolean, last: Boolean): Modifier {
    val radius = 22.dp
    val shape = RoundedCornerShape(
        topStart = if (first) radius else 0.dp,
        topEnd = if (first) radius else 0.dp,
        bottomStart = if (last) radius else 0.dp,
        bottomEnd = if (last) radius else 0.dp,
    )
    return fillMaxWidth()
        .clip(shape)
        .background(Palette.Panel.copy(alpha = 0.86f))
        .drawWithContent {
            drawContent()
            // A full rounded outline, stretched past the ends that join the next slice, so only the sides show there.
            val stroke = 1.dp.toPx()
            val r = radius.toPx()
            val top = if (first) stroke / 2 else -2 * r
            val bottom = if (last) size.height - stroke / 2 else size.height + 2 * r
            drawRoundRect(
                color = Palette.Stroke,
                topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, top),
                size = androidx.compose.ui.geometry.Size(size.width - stroke, bottom - top),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r - stroke / 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
            )
        }
        .padding(start = 18.dp, end = 18.dp, top = if (first) 18.dp else 0.dp, bottom = if (last) 18.dp else 0.dp)
}
