package id.dysklink.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.phonelink.android.core.ContactItem
import id.phonelink.android.core.ContactSearch
import id.phonelink.android.core.FsEntry
import id.phonelink.android.core.TransferStatus
import java.text.SimpleDateFormat
import java.util.Date

// The phone's contacts and its files, as the Mac's Contacts and Files tabs: read from the phone while its switches
// for them are on, and never changed from here.

/** As many rows as a page draws at once; a search finds the rest. */
private const val MAX_SHOWN = 300

@Composable
fun ColumnScope.ContactsTab(context: TabContext) {
    val colors = Dysk.colors
    val device = context.device
    val model = context.model
    if (!ready(device, "Contacts", device.servesContacts, "contacts", model)) return
    val all by model.contacts.collectAsState()
    val book = all[device.id]
    LaunchedEffect(device.id) { if (book?.loaded != true && book?.loading != true) model.loadContacts(device.id) }
    var query by remember(device.id) { mutableStateOf("") }
    val contacts = book?.contacts.orEmpty()
    val shown = remember(contacts, query) { if (query.isBlank()) contacts else ContactSearch.search(contacts, query) }
    val canSms = device.servesSms && "sms" in device.features
    Row(verticalAlignment = Alignment.CenterVertically) {
        InputField(query, { query = it.take(80) }, "Search a name or a number, like 0812 or +62 812", Modifier.weight(1f))
        Spacer(Modifier.width(Space.xs))
        SecondaryButton("Refresh", icon = DyskIcons.Refresh, enabled = device.isConnected) { model.loadContacts(device.id) }
    }
    Text(
        when {
            book?.loading == true && contacts.isEmpty() -> "Reading contacts from ${device.name}..."
            query.isBlank() -> "${contacts.size} contacts"
            else -> "${shown.size} of ${contacts.size} contacts match"
        },
        Modifier.padding(start = 2.dp), color = colors.textFaint, fontSize = 11.5.sp,
    )
    when {
        contacts.isEmpty() -> EmptyCard(if (book?.loading != false) "Reading contacts..." else "No contacts on the phone.", DyskIcons.Users)
        shown.isEmpty() -> EmptyCard("No contact matches “${query.trim()}”.", DyskIcons.Users)
        query.isBlank() -> {
            // Alphabetical under a letter, when not searching.
            shown.take(MAX_SHOWN).groupBy { ContactSearch.section(it.name) }.toSortedMap(compareBy { if (it == "#") "￿" else it }).forEach { (letter, group) ->
                SectionLabel(letter)
                ContactList(group, canSms, model, context)
            }
        }
        else -> ContactList(shown.take(MAX_SHOWN), canSms, model, context)
    }
    if (shown.size > MAX_SHOWN) Body("Showing $MAX_SHOWN of ${shown.size}. Search to find the others.", colors.textFaint)
}

@Composable
private fun ContactList(contacts: List<ContactItem>, canSms: Boolean, model: DeskModel, context: TabContext) {
    val colors = Dysk.colors
    GlassCard(padding = 4.dp) {
        contacts.forEachIndexed { index, contact ->
            if (index > 0) Box(Modifier.fillMaxWidth().padding(start = 56.dp).height(1.dp).background(colors.stroke))
            ContactRow(contact, canSms, onCopy = model::copyText) { number ->
                context.ui.smsTo = number
                context.ui.tab = "messages"
            }
        }
    }
}

@Composable
private fun ContactRow(contact: ContactItem, canSms: Boolean, onCopy: (String) -> Unit, onSms: (String) -> Unit) {
    val colors = Dysk.colors
    var copied by remember(contact.id) { mutableStateOf<String?>(null) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.Top) {
        val palette = listOf(colors.violet, colors.mint, colors.amber, colors.coral, colors.sky)
        val tint = palette[Math.floorMod(contact.name.hashCode(), palette.size)]
        Box(Modifier.size(34.dp).background(tint.copy(alpha = 0.22f), CircleShape), contentAlignment = Alignment.Center) {
            Text(ContactSearch.initials(contact.name), color = tint, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SelectionContainer { Text(contact.name, color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold) }
            if (contact.phones.isEmpty()) Text("No number", color = colors.textFaint, fontSize = 12.sp)
            contact.phones.forEach { number ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(number, color = colors.textSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }
                    TextAction(if (copied == number) "Copied" else "Copy", colors.mint) { onCopy(number); copied = number }
                    if (canSms) TextAction("SMS", colors.sky) { onSms(number) }
                }
            }
        }
    }
}

@Composable
fun ColumnScope.PhoneFilesTab(context: TabContext) {
    val colors = Dysk.colors
    val device = context.device
    val model = context.model
    if (!ready(device, "Phone files", device.servesFiles, "files", model)) return
    val all by model.folders.collectAsState()
    val folder = all[device.id]
    LaunchedEffect(device.id) { if (folder == null) model.openPhoneFolder(device.id, "") }
    val path = folder?.folder.orEmpty()
    var selected by remember(device.id, path) { mutableStateOf(setOf<String>()) }
    val entries = folder?.entries.orEmpty()
    val files = entries.filter { !it.isDirectory }
    fun child(entry: FsEntry) = if (path.isEmpty()) entry.name else "$path/${entry.name}"

    // Where you are: Up, the path, and Reload.
    Row(verticalAlignment = Alignment.CenterVertically) {
        SecondaryButton("Up", icon = DyskIcons.ArrowUp, enabled = path.isNotEmpty() && device.isConnected) {
            model.openPhoneFolder(device.id, path.substringBeforeLast('/', ""))
        }
        Spacer(Modifier.width(Space.xs))
        Row(
            Modifier.weight(1f).height(40.dp).background(colors.panelRaised, RoundedCornerShape(12.dp)).border(1.dp, colors.stroke, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DyskIcon(DyskIcons.Folder, colors.textSecondary, 14.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                (listOf("Internal storage") + path.split('/').filter { it.isNotEmpty() }).joinToString(" › "),
                color = colors.textPrimary, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.StartEllipsis,
            )
        }
        Spacer(Modifier.width(Space.xs))
        SecondaryButton("Reload", icon = DyskIcons.Refresh, enabled = device.isConnected) { model.openPhoneFolder(device.id, path) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Show hidden", color = colors.textSecondary, fontSize = 12.sp)
        Spacer(Modifier.width(6.dp))
        DyskSwitch(folder?.showHidden == true, enabled = device.isConnected) { model.openPhoneFolder(device.id, path, it) }
        Spacer(Modifier.weight(1f))
        if (files.isNotEmpty()) {
            TextAction(if (selected.size == files.size) "Clear selection" else "Select all", colors.mint) {
                selected = if (selected.size == files.size) emptySet() else files.map { it.name }.toSet()
            }
            Spacer(Modifier.width(Space.xs))
        }
        PrimaryButton(if (selected.size > 1) "Download ${selected.size}" else "Download", enabled = selected.isNotEmpty() && device.isConnected, icon = DyskIcons.Download) {
            model.downloadPhoneFiles(device.id, files.filter { it.name in selected }.map(::child))
            selected = emptySet()
        }
    }
    folder?.error?.let { PhoneRefusal(it, device) }
    val transfers by model.transfers.collectAsState()
    transfers.filter { it.peerId == device.id && !it.outgoing && it.status == TransferStatus.Active }.take(5).forEach { TransferRow(it, null, model) }
    when {
        entries.isEmpty() && folder?.loading != false -> EmptyCard("Opening folder...", DyskIcons.Folder)
        entries.isEmpty() -> EmptyCard("This folder is empty.", DyskIcons.Folder)
        else -> GlassCard(padding = 6.dp) {
            entries.take(MAX_SHOWN * 2).forEach { entry ->
                FileRow(entry, entry.name in selected, device.isConnected,
                    onOpen = {
                        if (entry.isDirectory) model.openPhoneFolder(device.id, child(entry))
                        else selected = if (entry.name in selected) selected - entry.name else selected + entry.name
                    },
                    onDownload = { model.downloadPhoneFiles(device.id, listOf(child(entry))) },
                )
            }
            if (entries.size > MAX_SHOWN * 2) Body("Showing ${MAX_SHOWN * 2} of ${entries.size}.", colors.textFaint, Modifier.padding(8.dp))
        }
    }
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = Space.sm)) {
                Text("Put files on the phone", color = colors.textPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Body("Files sent from this PC go to the phone's Download folder. Browsing is read-only, as on a Mac: nothing here changes or deletes the phone's files. Downloads go to ${model.downloadsPath}.")
            }
            SecondaryButton("Send files", icon = DyskIcons.Upload, enabled = device.isConnected) { context.sendPicked() }
        }
    }
}

@Composable
private fun FileRow(entry: FsEntry, selected: Boolean, connected: Boolean, onOpen: () -> Unit, onDownload: () -> Unit) {
    val colors = Dysk.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) colors.mint.copy(alpha = 0.1f) else Color.Transparent)
            .clickable(enabled = connected || !entry.isDirectory, onClick = onOpen).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Folders keep the checkbox's room, so their icons line up with the files'.
        Box(
            Modifier.size(16.dp).background(if (selected) colors.mint else Color.Transparent, RoundedCornerShape(4.dp))
                .border(1.5.dp, if (entry.isDirectory) Color.Transparent else if (selected) colors.mint else colors.textFaint, RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) { if (selected) DyskIcon(DyskIcons.Check, colors.onAccent, 10.dp) }
        Spacer(Modifier.width(10.dp))
        DyskIcon(if (entry.isDirectory) DyskIcons.Folder else DyskIcons.File, if (entry.isDirectory) colors.mint else colors.textSecondary, 16.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name, color = if (entry.name.startsWith(".")) colors.textSecondary else colors.textPrimary, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
            )
            val time = SimpleDateFormat("d MMM yyyy, HH:mm").format(Date(entry.modified))
            Text(if (entry.isDirectory) time else "${bytes(entry.size)}, $time", color = colors.textFaint, fontSize = 11.sp)
        }
        if (entry.isDirectory) DyskIcon(DyskIcons.ChevronRight, colors.textFaint, 13.dp)
        else if (connected) TextAction("Download", colors.mint, onDownload)
    }
}
