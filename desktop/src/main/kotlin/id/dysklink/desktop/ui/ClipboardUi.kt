package id.dysklink.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.dysklink.desktop.ClipAction
import id.dysklink.desktop.ClipEntry
import id.dysklink.desktop.ClipHistoryStore
import id.dysklink.desktop.ClipHistoryView
import id.dysklink.desktop.ClipImportSource
import id.dysklink.desktop.ClipKind
import id.dysklink.desktop.PickerAction
import id.dysklink.desktop.PickerKey
import id.dysklink.desktop.PickerKeys
import id.dysklink.desktop.PickerState
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small copies of the pictures for the lists, a bounded number kept in memory. */
private object Thumbnails {
    private const val PIXELS = 200
    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 80
    }

    fun load(file: File): ImageBitmap? {
        synchronized(cache) { cache[file.path]?.let { return it } }
        val image = runCatching { javax.imageio.ImageIO.read(file) }.getOrNull() ?: return null
        val scale = minOf(1.0, PIXELS.toDouble() / maxOf(image.width, image.height))
        val w = maxOf(1, (image.width * scale).toInt())
        val h = maxOf(1, (image.height * scale).toInt())
        val small = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        small.createGraphics().apply {
            setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(image, 0, 0, w, h, null)
            dispose()
        }
        val bitmap = small.toComposeImageBitmap()
        synchronized(cache) { cache[file.path] = bitmap }
        return bitmap
    }

    /** Already loaded, for snapshots and the first frame. */
    fun cached(file: File): ImageBitmap? = synchronized(cache) { cache[file.path] }
}

@Composable
private fun Thumbnail(file: File?, width: Int, height: Int) {
    val colors = Dysk.colors
    val bitmap by produceState(file?.let(Thumbnails::cached), file) {
        if (value == null && file != null) value = withContext(Dispatchers.IO) { Thumbnails.load(file) }
    }
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.size(width.dp, height.dp).clip(shape).background(colors.panelRaised), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: DyskIcon(DyskIcons.Image, colors.textFaint, 16.dp)
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val dayFormat = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)

/** "14:05" today, "3 Nov, 14:05" before. */
fun clipTime(millis: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochMilli(millis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return if (at.toLocalDate() == today) at.format(timeFormat) else at.format(dayFormat)
}

private fun limitLabel(n: Int) = if (n == ClipHistoryStore.UNLIMITED) "Unlimited" else "$n"

/** Clipboard History on the Tools page: the switch, its settings, hints, and the list to search, pin and delete. */
@Composable
fun ClipboardHistoryCard(model: DeskModel, clock: () -> Long = System::currentTimeMillis) {
    val flow = model.clipHistory ?: return
    val view by flow.collectAsState()
    val colors = Dysk.colors
    val s = view.settings
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var pendingLimits by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    GlassCard {
        CardTitle(DyskIcons.Clipboard, "Clipboard history", colors.violet) {
            if (s.enabled) {
                Text("${view.entries.size} saved", color = colors.textSecondary, fontSize = 12.sp)
                Spacer(Modifier.width(Space.xs))
                TextAction("Open picker", colors.mint, model::openClipboardPicker)
            }
        }
        Spacer(Modifier.height(Space.sm))
        SettingRow(
            "Keep a clipboard history",
            "What you copy on ${model.deviceName}, and what phones and computers send, saved only on this PC. " +
                "Press ${view.combo} to search it and paste.",
            s.enabled,
        ) { model.clipAction(ClipAction.SetSettings(s.copy(enabled = it))) }
        if (!s.enabled) return@GlassCard
        Spacer(Modifier.height(Space.xs))
        SettingRow("Pause", "Nothing new is recorded while paused. What's saved stays.", s.paused) {
            model.clipAction(ClipAction.SetSettings(s.copy(paused = it)))
        }
        Spacer(Modifier.height(Space.xs))
        SettingRow(
            "Ignore passwords",
            "Skips copies a password manager marks as secret, and text that looks like a password.",
            s.ignorePasswords,
        ) { model.clipAction(ClipAction.SetSettings(s.copy(ignorePasswords = it))) }
        Spacer(Modifier.height(Space.sm))
        val setLimits = { entries: Int, images: Int ->
            val removed = ClipHistoryStore.trimmed(view.entries, entries, images).second.size
            if (removed > 0) pendingLimits = entries to images
            else model.clipAction(ClipAction.SetSettings(s.copy(maxEntries = entries, maxImages = images)))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            Box(Modifier.weight(1f)) {
                Stepper("Keep up to", s.maxEntries, ClipHistoryStore.ENTRY_CHOICES, ::limitLabel) { setLimits(it, s.maxImages) }
            }
            Box(Modifier.weight(1f)) {
                Stepper("Of them images", s.maxImages, ClipHistoryStore.IMAGE_CHOICES, ::limitLabel) { setLimits(s.maxEntries, it) }
            }
        }
        pendingLimits?.let { (entries, images) ->
            val removed = ClipHistoryStore.trimmed(view.entries, entries, images).second.size
            Spacer(Modifier.height(Space.xs))
            ConfirmRow("Remove the $removed oldest items? Pinned items stay.", "Remove", {
                model.clipAction(ClipAction.SetSettings(s.copy(maxEntries = entries, maxImages = images)))
                pendingLimits = null
            }) { pendingLimits = null }
        }
        if (view.passwordLike > 0) {
            Spacer(Modifier.height(Space.xs))
            ConfirmRow("${view.passwordLike} saved items look like passwords.", "Remove them", { model.clipAction(ClipAction.RemovePasswordLike) }, null)
        }
        HintList(view.hints, model)
        Spacer(Modifier.height(Space.sm))
        InputField(query, { query = it }, "Search the history", Modifier.fillMaxWidth())
        Spacer(Modifier.height(Space.xs))
        val list = ClipHistoryStore.search(view.entries, query)
        if (list.isEmpty()) {
            Text(
                if (query.isNotBlank()) "No matches" else if (s.paused) "Paused. Nothing copied is recorded." else "Nothing copied yet. Copy something and it shows here.",
                color = colors.textFaint, fontSize = 12.5.sp, modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        val now = clock()
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            list.take(SHOWN).forEach { entry -> HistoryRow(entry, model, now) }
        }
        if (list.size > SHOWN) {
            Spacer(Modifier.height(4.dp))
            Text("${list.size - SHOWN} more. Search to find them, or open the picker.", color = colors.textFaint, fontSize = 12.sp)
        }
        Spacer(Modifier.height(Space.sm))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            view.imports.forEach { source ->
                SecondaryButton(if (view.importing) "Importing..." else "Import from ${source.label}", enabled = !view.importing) {
                    model.clipAction(ClipAction.Import(source))
                }
            }
            if (ClipImportSource.Ditto in view.imports) {
                TextAction("Choose Ditto.db", colors.textSecondary) {
                    model.pickDittoFile()?.let { model.clipAction(ClipAction.Import(ClipImportSource.Ditto, it)) }
                }
            }
            Spacer(Modifier.weight(1f))
            SecondaryButton("Clear history", tint = colors.coral, enabled = view.entries.any { !it.pinned }) { confirmClear = true }
        }
        if (confirmClear) {
            Spacer(Modifier.height(Space.xs))
            ConfirmRow("Clear ${view.entries.count { !it.pinned }} items? Pinned items stay.", "Clear", {
                model.clipAction(ClipAction.ClearUnpinned); confirmClear = false
            }) { confirmClear = false }
        }
        view.importMessage?.let { Spacer(Modifier.height(Space.xs)); Body(it) }
        if (view.imports.isEmpty()) {
            Spacer(Modifier.height(Space.xs))
            Body(
                if (model.isWindows) "Import from Ditto or CopyQ shows here when either is installed."
                else "Import from CopyQ shows here when its copyq command is installed.",
                colors.textFaint,
            )
        }
    }
}

private const val SHOWN = 30

@Composable
private fun ConfirmRow(text: String, action: String, onConfirm: () -> Unit, onCancel: (() -> Unit)?) {
    val colors = Dysk.colors
    Row(
        Modifier.fillMaxWidth().background(colors.coral.copy(alpha = 0.08f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f), color = colors.textPrimary, fontSize = 12.5.sp)
        SecondaryButton(action, tint = colors.coral, onClick = onConfirm)
        onCancel?.let { Spacer(Modifier.width(4.dp)); TextAction("Cancel", colors.textSecondary, it) }
    }
}

@Composable
private fun HistoryRow(entry: ClipEntry, model: DeskModel, now: Long) {
    val colors = Dysk.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(colors.panelRaised).clickable { model.clipAction(ClipAction.Copy(entry.id)) }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryLead(entry, model, 52, 36)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.preview, color = colors.textPrimary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(clipTime(entry.date, now), entry.source?.let { "from $it" }, entry.files?.takeIf { it.size > 1 }?.let { "${it.size} files" })
                    .joinToString("  ·  "),
                color = colors.textFaint, fontSize = 11.sp,
            )
        }
        IconButton(DyskIcons.Pin, if (entry.pinned) colors.mint else colors.textFaint) { model.clipAction(ClipAction.Pin(entry.id)) }
        IconButton(DyskIcons.Copy, colors.textSecondary) { model.clipAction(ClipAction.Copy(entry.id)) }
        IconButton(DyskIcons.Trash, colors.coral) { model.clipAction(ClipAction.Delete(entry.id)) }
    }
}

@Composable
private fun EntryLead(entry: ClipEntry, model: DeskModel, width: Int, height: Int) {
    val colors = Dysk.colors
    when (entry.kind) {
        ClipKind.Image -> Thumbnail(model.clipImageFile(entry), width, height)
        ClipKind.Files -> Box(Modifier.size(width.dp, height.dp).clip(RoundedCornerShape(6.dp)).background(colors.sky.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            DyskIcon(DyskIcons.File, colors.sky, 16.dp)
        }
        ClipKind.Text -> Box(Modifier.size(width.dp, height.dp).clip(RoundedCornerShape(6.dp)).background(colors.violet.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
            Text("Aa", color = colors.violet, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun IconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { DyskIcon(icon, tint, 13.dp) }
}

/** The AWT key behind a Compose key event, as a picker key; null for keys the picker doesn't use. */
fun pickerKey(event: KeyEvent): PickerKey? {
    val awt = event.nativeKeyEvent as? java.awt.event.KeyEvent ?: return null
    val ctrl = awt.isControlDown || awt.isMetaDown
    if (awt.id == java.awt.event.KeyEvent.KEY_TYPED) {
        val c = awt.keyChar
        if (ctrl || awt.isAltDown || c == java.awt.event.KeyEvent.CHAR_UNDEFINED || c.code < 32 || c.code == 127) return null
        return PickerKey.Type(c.toString())
    }
    if (awt.id != java.awt.event.KeyEvent.KEY_PRESSED) return null
    return when (awt.keyCode) {
        java.awt.event.KeyEvent.VK_UP -> PickerKey.Up
        java.awt.event.KeyEvent.VK_DOWN -> PickerKey.Down
        java.awt.event.KeyEvent.VK_PAGE_UP -> PickerKey.PageUp
        java.awt.event.KeyEvent.VK_PAGE_DOWN -> PickerKey.PageDown
        java.awt.event.KeyEvent.VK_ENTER -> PickerKey.Enter
        java.awt.event.KeyEvent.VK_ESCAPE -> PickerKey.Escape
        java.awt.event.KeyEvent.VK_BACK_SPACE -> if (ctrl) PickerKey.DeleteEntry else PickerKey.Backspace
        java.awt.event.KeyEvent.VK_DELETE -> if (ctrl) PickerKey.DeleteEntry else null
        java.awt.event.KeyEvent.VK_P -> if (ctrl) PickerKey.TogglePin else null
        in java.awt.event.KeyEvent.VK_1..java.awt.event.KeyEvent.VK_9 -> if (ctrl) PickerKey.Number(awt.keyCode - java.awt.event.KeyEvent.VK_0) else null
        else -> null
    }
}

/**
 * The quick picker, in its own small window: type to search, arrows to move, Enter to paste, Esc to close.
 * [onClose] hides the window; a paste closes it first so the app below gets the keyboard back.
 */
@Composable
fun ClipPickerContent(model: DeskModel, onClose: () -> Unit, clock: () -> Long = System::currentTimeMillis, initial: PickerState = PickerState()) {
    val flow = model.clipHistory ?: return
    val view by flow.collectAsState()
    var state by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val list = ClipHistoryStore.search(view.entries, state.query)
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(state.selection) { if (state.selection in list.indices) listState.animateScrollToItem(maxOf(0, state.selection - 3)) }
    val handle = { key: PickerKey ->
        val (next, action) = PickerKeys.reduce(state, key, list)
        state = next
        when (action) {
            PickerAction.None -> Unit
            PickerAction.Close -> onClose()
            is PickerAction.Paste -> { onClose(); model.clipPaste(action.entry.id) }
            is PickerAction.Delete -> model.clipAction(ClipAction.Delete(action.entry.id))
            is PickerAction.Pin -> model.clipAction(ClipAction.Pin(action.entry.id))
        }
    }
    val colors = Dysk.colors
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxSize().background(colors.panel, shape).border(1.dp, colors.stroke, shape)
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { event -> pickerKey(event)?.let { handle(it); true } ?: false },
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            DyskIcon(DyskIcons.Search, colors.textFaint, 15.dp)
            Spacer(Modifier.width(9.dp))
            Text(
                state.query.ifEmpty { "Type to search" }, Modifier.weight(1f),
                color = if (state.query.isEmpty()) colors.textFaint else colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            )
            Text("${list.size}", color = colors.textFaint, fontSize = 13.sp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.stroke))
        if (list.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    if (state.query.isNotEmpty()) "No matches" else if (!view.settings.enabled) "Clipboard history is off. Turn it on in Tools." else "Nothing copied yet",
                    color = colors.textSecondary, fontSize = 13.sp,
                )
            }
        } else {
            val now = clock()
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(8.dp), state = listState, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(list, key = { _, e -> e.id }) { index, entry ->
                    val selected = index == state.selection
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(9.dp)).background(if (selected) colors.panelRaised else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { state = state.copy(selection = index); handle(PickerKey.Enter) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        EntryLead(entry, model, 46, 32)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.preview, color = colors.textPrimary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(clipTime(entry.date, now), entry.source?.let { "from $it" }).joinToString("  ·  "), color = colors.textFaint, fontSize = 10.5.sp)
                        }
                        if (entry.pinned) { Spacer(Modifier.width(6.dp)); DyskIcon(DyskIcons.Pin, colors.mint, 12.dp) }
                        if (index < 9) { Spacer(Modifier.width(8.dp)); Text("Ctrl+${index + 1}", color = colors.textFaint, fontSize = 10.5.sp) }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.stroke))
        Text(
            "Enter paste  ·  Ctrl+P pin  ·  Ctrl+Delete delete  ·  Esc close",
            Modifier.padding(10.dp).align(Alignment.CenterHorizontally), color = colors.textFaint, fontSize = 11.sp,
        )
    }
}
