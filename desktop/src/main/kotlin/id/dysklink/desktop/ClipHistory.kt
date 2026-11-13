package id.dysklink.desktop

import id.phonelink.android.core.CoreLog
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

// Clipboard History, as on the Mac: what is copied on this PC, and what phones and computers send, kept in the data
// folder only. Text, pictures (as PNG files named by their hash) and copied files (their paths).

@Serializable
data class ClipEntry(
    val id: String = UUID.randomUUID().toString(),
    val text: String? = null,
    /** A PNG in clipboard-images, named by its SHA-256. */
    val imageFile: String? = null,
    /** Copied files and folders, as paths. */
    val files: List<String>? = null,
    val date: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    /** The phone or computer it came from; null when it was copied on this PC. */
    val source: String? = null,
) {
    val kind: ClipKind get() = when {
        imageFile != null -> ClipKind.Image
        files != null -> ClipKind.Files
        else -> ClipKind.Text
    }

    /** One line for lists. */
    val preview: String get() = when (kind) {
        ClipKind.Text -> text.orEmpty().trim().replace(Regex("\\s+"), " ")
        ClipKind.Image -> "Image"
        ClipKind.Files -> files.orEmpty().let { list ->
            val first = list.firstOrNull()?.let { File(it).name.ifEmpty { it } }.orEmpty()
            if (list.size > 1) "$first and ${list.size - 1} more" else first
        }
    }
}

enum class ClipKind { Text, Image, Files }

/** What a look at the clipboard found. [sensitive] is set when a password manager marked it as secret. */
sealed interface ClipCapture {
    val sensitive: Boolean

    data class Text(val text: String, override val sensitive: Boolean = false) : ClipCapture
    /** PNG bytes. */
    class Image(val png: ByteArray, override val sensitive: Boolean = false) : ClipCapture
    data class Files(val paths: List<String>, override val sensitive: Boolean = false) : ClipCapture
}

data class ClipSettings(
    /** Recording at all, the Tools switch. Off until turned on, as on the Mac. */
    val enabled: Boolean = false,
    /** Kept, but nothing new is recorded. */
    val paused: Boolean = false,
    val ignorePasswords: Boolean = true,
    val maxEntries: Int = ClipHistoryStore.DEFAULT_ENTRIES,
    val maxImages: Int = ClipHistoryStore.DEFAULT_IMAGES,
) {
    val recording: Boolean get() = enabled && !paused
}

/** Decides which copies never enter the history; the Mac's rule, so both keep the same things. */
object PasswordFilter {
    private val email = Regex("^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$")
    private val uuid = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")

    /**
     * One unbroken word of 8 to 128 characters that mixes at least three of lowercase, uppercase, numbers, and
     * symbols. Links, email addresses, file paths, and UUIDs don't count.
     */
    fun looksLikePassword(text: String): Boolean {
        val value = text.trim()
        if (value.length !in 8..128 || value.any { it.isWhitespace() }) return false
        if ("://" in value || value.startsWith("/") || value.startsWith("~/") || value.startsWith("www.")) return false
        if (Regex("^[A-Za-z]:\\\\").containsMatchIn(value)) return false
        if (email.matches(value) || uuid.matches(value)) return false
        val classes = listOf(
            value.any { it.isLowerCase() },
            value.any { it.isUpperCase() },
            value.any { it.isDigit() },
            value.any { !it.isLetterOrDigit() },
        ).count { it }
        return classes >= 3
    }
}

/**
 * The saved history: newest first, pinned entries kept whatever the limits. Thread safe; saves are written a moment
 * after a change, on their own thread, and at once by [flush].
 */
class ClipHistoryStore(
    private val directory: File,
    settings: ClipSettings = ClipSettings(),
    private val saveDelayMs: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val serializer = ListSerializer(ClipEntry.serializer())
    private val storeFile get() = File(directory, "clipboard-history.json")
    val imagesFolder: File get() = File(directory, "clipboard-images")

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<ClipEntry>> = _entries.asStateFlow()
    private val _settings = MutableStateFlow(settings.withValidLimits())
    val settings: StateFlow<ClipSettings> = _settings.asStateFlow()

    private val saver = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "dysk-clip-save").apply { isDaemon = true } }
    private var pending: ScheduledFuture<*>? = null
    private val lock = Any()

    fun setSettings(new: ClipSettings) { _settings.value = new.withValidLimits(); synchronized(lock) { trimAndSave() } }

    /** How many saved entries the new limits would remove, so lowering them can be confirmed first. */
    fun removedCount(maxEntries: Int, maxImages: Int): Int = trimmed(_entries.value, maxEntries, maxImages).second.size

    /**
     * Adds what was copied or received; false when it was left out (paused, a password, empty, too big). The same
     * content again moves up to the top and keeps its pin.
     */
    fun add(capture: ClipCapture, source: String? = null): Boolean {
        val settings = _settings.value
        if (!settings.recording) return false
        if (settings.ignorePasswords && capture.sensitive) return false
        return when (capture) {
            is ClipCapture.Text -> {
                val text = capture.text
                if (text.isBlank() || text.toByteArray().size > MAX_TEXT_BYTES) return false
                if (settings.ignorePasswords && PasswordFilter.looksLikePassword(text)) return false
                insert(ClipEntry(text = text, date = clock(), source = source)) { it.text == text }
            }
            is ClipCapture.Files -> {
                if (capture.paths.isEmpty()) return false
                insert(ClipEntry(files = capture.paths, date = clock(), source = source)) { it.files == capture.paths }
            }
            is ClipCapture.Image -> {
                val png = capture.png
                if (png.isEmpty() || png.size > MAX_IMAGE_BYTES) return false
                val name = imageName(png)
                val file = File(imagesFolder, name)
                if (!file.isFile) {
                    val written = runCatching {
                        imagesFolder.mkdirs()
                        privateFolder(directory); privateFolder(imagesFolder)
                        val temp = File(imagesFolder, "$name.part")
                        temp.writeBytes(png)
                        privateFile(temp)
                        if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
                    }.isSuccess
                    if (!written) return false
                }
                insert(ClipEntry(imageFile = name, date = clock(), source = source)) { it.imageFile == name }
            }
        }
    }

    private fun insert(entry: ClipEntry, same: (ClipEntry) -> Boolean): Boolean = synchronized(lock) {
        val list = _entries.value
        val old = list.firstOrNull(same)
        val pinned = old?.pinned ?: false
        _entries.value = listOf(entry.copy(pinned = pinned)) + list.filter { it !== old }
        trimAndSave()
        true
    }

    /** Text matched without case, and file names; pinned first, then newest. */
    fun matches(query: String): List<ClipEntry> = search(_entries.value, query)

    /** Moves an entry to the top, as when it's pasted again from the list. */
    fun touch(id: String) = synchronized(lock) {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return@synchronized
        _entries.value = listOf(entry.copy(date = clock())) + _entries.value.filter { it.id != id }
        scheduleSave()
    }

    fun togglePin(id: String) = synchronized(lock) {
        _entries.value = _entries.value.map { if (it.id == id) it.copy(pinned = !it.pinned) else it }
        scheduleSave()
    }

    fun delete(id: String) = synchronized(lock) {
        val gone = _entries.value.filter { it.id == id }
        _entries.value = _entries.value.filter { it.id != id }
        gone.forEach(::removeImage)
        scheduleSave()
    }

    /** Pinned entries stay. */
    fun clearUnpinned() = synchronized(lock) {
        val gone = _entries.value.filter { !it.pinned }
        _entries.value = _entries.value.filter { it.pinned }
        gone.forEach(::removeImage)
        saveNow()
    }

    val unpinnedCount: Int get() = _entries.value.count { !it.pinned }

    /** Saved items that look like passwords, for removing what was recorded before Ignore passwords was on. */
    val passwordLikeCount: Int get() = _entries.value.count { e -> e.text?.let(PasswordFilter::looksLikePassword) == true }

    fun removePasswordLike() = synchronized(lock) {
        _entries.value = _entries.value.filter { e -> e.text?.let(PasswordFilter::looksLikePassword) != true }
        saveNow()
    }

    fun imageFile(entry: ClipEntry): File? = entry.imageFile?.let { File(imagesFolder, it) }

    /**
     * Adds copies from another clipboard app (Ditto, CopyQ), without repeating what's saved. Copies without a date go
     * below what is saved now, in the order they came. Returns how many were added and how many the limits removed.
     */
    fun importClips(clips: List<ImportedClip>): Pair<Int, Int> = synchronized(lock) {
        val settings = _settings.value
        val texts = _entries.value.mapNotNull { it.text }.toMutableSet()
        val images = _entries.value.mapNotNull { it.imageFile }.toMutableSet()
        var undated = (_entries.value.minOfOrNull { it.date } ?: clock()) - 1_000
        val added = mutableListOf<ClipEntry>()
        for (clip in clips) {
            val date = clip.date ?: undated.also { undated -= 1_000 }
            val text = clip.text
            val png = clip.png
            if (text != null) {
                if (text.isBlank() || text.toByteArray().size > MAX_TEXT_BYTES) continue
                if (settings.ignorePasswords && PasswordFilter.looksLikePassword(text)) continue
                if (!texts.add(text)) continue
                added += ClipEntry(text = text, date = date)
            } else if (png != null && png.size <= MAX_IMAGE_BYTES) {
                val name = imageName(png)
                if (!images.add(name)) continue
                val ok = runCatching {
                    imagesFolder.mkdirs()
                    File(imagesFolder, name).also { it.writeBytes(png); privateFile(it) }
                }.isSuccess
                if (ok) added += ClipEntry(imageFile = name, date = date)
            }
        }
        if (added.isEmpty()) return 0 to 0
        val before = _entries.value.size + added.size
        val (kept, removed) = trimmed((_entries.value + added).sortedByDescending { it.date }, settings.maxEntries, settings.maxImages)
        _entries.value = kept
        removed.forEach(::removeImage)
        saveNow()
        added.size to (before - kept.size)
    }

    private fun trimAndSave() {
        val s = _settings.value
        val (kept, removed) = trimmed(_entries.value, s.maxEntries, s.maxImages)
        if (removed.isNotEmpty()) {
            _entries.value = kept
            removed.forEach(::removeImage)
        }
        scheduleSave()
    }

    private fun removeImage(entry: ClipEntry) {
        val name = entry.imageFile ?: return
        // Another entry can't share it (same picture means same entry), but an import may race; check anyway.
        if (_entries.value.any { it.imageFile == name }) return
        File(imagesFolder, name).delete()
    }

    private fun scheduleSave() {
        pending?.cancel(false)
        pending = saver.schedule({ synchronized(lock) { write(_entries.value) } }, saveDelayMs, TimeUnit.MILLISECONDS)
    }

    /** Writes a save that's still waiting, for quitting. */
    fun flush() = synchronized(lock) {
        if (pending?.isDone == false) saveNow()
    }

    private fun saveNow() {
        pending?.cancel(false)
        pending = null
        write(_entries.value)
    }

    private fun write(list: List<ClipEntry>) {
        runCatching {
            directory.mkdirs()
            privateFolder(directory)
            val temp = File(directory, "clipboard-history.json.part")
            temp.writeText(json.encodeToString(serializer, list))
            privateFile(temp)
            if (!temp.renameTo(storeFile)) { storeFile.delete(); temp.renameTo(storeFile) }
        }.onFailure { CoreLog.w("DyskLink", "Couldn't save the clipboard history", it) }
    }

    private fun load(): List<ClipEntry> =
        runCatching { json.decodeFromString(serializer, storeFile.readText()) }.getOrNull().orEmpty()

    companion object {
        /** 0 means no limit. */
        const val UNLIMITED = 0
        val ENTRY_CHOICES = listOf(50, 100, 200, 500, 1000, 5000, UNLIMITED)
        val IMAGE_CHOICES = listOf(10, 30, 60, 100, 500, UNLIMITED)
        const val DEFAULT_ENTRIES = 200
        const val DEFAULT_IMAGES = 30
        const val MAX_TEXT_BYTES = 1_048_576
        const val MAX_IMAGE_BYTES = 20_000_000

        private fun ClipSettings.withValidLimits() = copy(
            maxEntries = maxEntries.takeIf { it in ENTRY_CHOICES } ?: DEFAULT_ENTRIES,
            maxImages = maxImages.takeIf { it in IMAGE_CHOICES } ?: DEFAULT_IMAGES,
        )

        fun imageName(png: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) } + ".png"

        /** Entries are newest first, so the oldest unpinned ones go. Pinned ones count toward neither limit. */
        fun trimmed(entries: List<ClipEntry>, maxEntries: Int, maxImages: Int): Pair<List<ClipEntry>, List<ClipEntry>> {
            val entryCap = if (maxEntries == UNLIMITED) Int.MAX_VALUE else maxEntries
            val imageCap = if (maxImages == UNLIMITED) Int.MAX_VALUE else maxImages
            var total = 0
            var images = 0
            val kept = mutableListOf<ClipEntry>()
            val removed = mutableListOf<ClipEntry>()
            for (entry in entries) {
                if (entry.pinned) { kept += entry; continue }
                val isImage = entry.kind == ClipKind.Image
                if (total >= entryCap || (isImage && images >= imageCap)) { removed += entry; continue }
                total++
                if (isImage) images++
                kept += entry
            }
            return kept to removed
        }

        fun search(entries: List<ClipEntry>, query: String): List<ClipEntry> {
            val words = query.trim()
            val found = if (words.isEmpty()) entries else entries.filter { e ->
                e.text?.contains(words, ignoreCase = true) == true ||
                    e.files?.any { it.contains(words, ignoreCase = true) } == true ||
                    (e.kind == ClipKind.Image && "image".contains(words, ignoreCase = true))
            }
            return found.filter { it.pinned } + found.filter { !it.pinned }
        }

        private fun privateFolder(dir: File) {
            dir.setReadable(false, false); dir.setReadable(true, true)
            dir.setWritable(false, false); dir.setWritable(true, true)
            dir.setExecutable(false, false); dir.setExecutable(true, true)
        }

        private fun privateFile(file: File) {
            file.setReadable(false, false); file.setReadable(true, true)
            file.setWritable(false, false); file.setWritable(true, true)
        }
    }
}

/** One copy read from another clipboard app. Pictures are PNG. */
class ImportedClip(val text: String? = null, val png: ByteArray? = null, val date: Long? = null)

/**
 * Tells a new copy from one already seen, on a poll. [sequence] is the system's change counter where there is one
 * (Windows): when it hasn't moved, the clipboard isn't read at all. Otherwise [signature] reads it and the result is
 * compared with the last one. [expect] marks content set here, so it isn't counted as a new copy when seen.
 */
class ClipChangeDetector {
    private var lastSequence: Long? = null
    private var lastSignature: String? = null
    private var primed = false

    @Synchronized
    fun isNew(sequence: Long?, signature: () -> String?): Boolean {
        if (sequence != null && sequence == lastSequence && primed) return false
        lastSequence = sequence
        val now = signature() ?: run { primed = true; return false }
        val changed = primed && now != lastSignature
        lastSignature = now
        primed = true
        return changed
    }

    /** Content set by DyskLink itself (from a phone, or put back from the history). */
    @Synchronized
    fun expect(signature: String) {
        lastSignature = signature
    }

    companion object {
        fun textSignature(text: String) = "t:" + text.hashCode() + ":" + text.length
        fun filesSignature(paths: List<String>) = "f:" + paths.joinToString("\u0000")
        fun imageSignature(quick: String) = "i:$quick"
    }
}

// The quick picker's keys, apart from the window so they can be tested.

data class PickerState(val query: String = "", val selection: Int = 0)

sealed interface PickerKey {
    data object Up : PickerKey
    data object Down : PickerKey
    data object PageUp : PickerKey
    data object PageDown : PickerKey
    data object Enter : PickerKey
    data object Escape : PickerKey
    data object Backspace : PickerKey
    /** Ctrl+Delete or Ctrl+Backspace. */
    data object DeleteEntry : PickerKey
    /** Ctrl+P. */
    data object TogglePin : PickerKey
    /** Ctrl+1 to Ctrl+9. */
    data class Number(val n: Int) : PickerKey
    data class Type(val text: String) : PickerKey
}

sealed interface PickerAction {
    data object None : PickerAction
    data object Close : PickerAction
    data class Paste(val entry: ClipEntry) : PickerAction
    data class Delete(val entry: ClipEntry) : PickerAction
    data class Pin(val entry: ClipEntry) : PickerAction
}

object PickerKeys {
    private const val PAGE = 8

    /** [list] is what shows for [state]'s query. */
    fun reduce(state: PickerState, key: PickerKey, list: List<ClipEntry>): Pair<PickerState, PickerAction> {
        val last = (list.size - 1).coerceAtLeast(0)
        val selected = list.getOrNull(state.selection)
        return when (key) {
            PickerKey.Up -> state.copy(selection = (state.selection - 1).coerceAtLeast(0)) to PickerAction.None
            PickerKey.Down -> state.copy(selection = (state.selection + 1).coerceAtMost(last)) to PickerAction.None
            PickerKey.PageUp -> state.copy(selection = (state.selection - PAGE).coerceAtLeast(0)) to PickerAction.None
            PickerKey.PageDown -> state.copy(selection = (state.selection + PAGE).coerceAtMost(last)) to PickerAction.None
            PickerKey.Enter -> state to (selected?.let { PickerAction.Paste(it) } ?: PickerAction.None)
            PickerKey.Escape -> state to PickerAction.Close
            PickerKey.Backspace ->
                if (state.query.isEmpty()) state to PickerAction.None
                else PickerState(state.query.dropLast(1), 0) to PickerAction.None
            PickerKey.DeleteEntry -> {
                if (selected == null) state to PickerAction.None
                else state.copy(selection = state.selection.coerceAtMost((list.size - 2).coerceAtLeast(0))) to PickerAction.Delete(selected)
            }
            PickerKey.TogglePin -> state to (selected?.let { PickerAction.Pin(it) } ?: PickerAction.None)
            is PickerKey.Number -> state to (list.getOrNull(key.n - 1)?.let { PickerAction.Paste(it) } ?: PickerAction.None)
            is PickerKey.Type -> {
                val typed = key.text.filter { it.code >= 32 && it.code != 127 }
                if (typed.isEmpty()) state to PickerAction.None else PickerState(state.query + typed, 0) to PickerAction.None
            }
        }
    }
}
