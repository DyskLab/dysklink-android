@file:Suppress("FunctionName")

package id.dysklink.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import id.phonelink.android.core.CoreLog
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.SystemFlavorMap
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the history sees copies on this PC, for the hints. */
enum class ClipWatchMode {
    /** Not watching: the history is off or paused. */
    Off,
    /** The AWT clipboard, looked at twice a second (Windows, X11). */
    Poll,
    /** `wl-paste --watch` tells of every copy (Wayland with wl-clipboard). */
    WlPaste,
    /** Wayland without wl-clipboard: only what XWayland sees, looked at twice a second. */
    PollNoWlPaste,
    /** Wayland where wl-paste can't watch (GNOME has no data-control protocol): XWayland, twice a second. */
    PollWlUnsupported,
}

/** Whether a password manager marked a copy as secret, by what each system uses for that. */
object ClipSensitivity {
    /** Windows: password managers add one of these formats; Windows' own history honours the same. */
    const val EXCLUDE = "ExcludeClipboardContentFromMonitorProcessing"
    const val CAN_INCLUDE = "CanIncludeInClipboardHistory"
    /** Ditto's and older managers' marker. */
    const val VIEWER_IGNORE = "Clipboard Viewer Ignore"
    /** KDE's, which KeePassXC and others set on Linux, with the value "secret". */
    const val KDE_HINT = "x-kde-passwordManagerHint"

    /** [canInclude] is the DWORD in CanIncludeInClipboardHistory, when that format is there. */
    fun windows(formats: Set<String>, canInclude: Int?): Boolean =
        EXCLUDE in formats || VIEWER_IGNORE in formats || (CAN_INCLUDE in formats && canInclude == 0)

    /** [state] is wl-paste's CLIPBOARD_STATE, "sensitive" when the copy was marked. */
    fun linux(mimeTypes: Collection<String>, state: String? = null): Boolean =
        mimeTypes.any { it.trim() == KDE_HINT } || state == "sensitive"
}

/** The clipboard formats on Windows that the AWT doesn't show, and the change counter. */
object WinClipboard {
    private interface User32 : Library {
        fun GetClipboardSequenceNumber(): Int
        fun RegisterClipboardFormatW(name: WString): Int
        fun IsClipboardFormatAvailable(format: Int): Boolean
        fun OpenClipboard(owner: Pointer?): Boolean
        fun CloseClipboard(): Boolean
        fun GetClipboardData(format: Int): Pointer?
        fun GetForegroundWindow(): Pointer?
        fun SetForegroundWindow(window: Pointer): Boolean
    }

    private interface Kernel32 : Library {
        fun GlobalLock(memory: Pointer): Pointer?
        fun GlobalUnlock(memory: Pointer): Boolean
    }

    private val user32: User32? by lazy { if (Platform.isWindows) runCatching { Native.load("user32", User32::class.java) }.getOrNull() else null }
    private val kernel32: Kernel32? by lazy { if (Platform.isWindows) runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull() else null }
    private val formats = mutableMapOf<String, Int>()

    private fun format(name: String): Int = synchronized(formats) {
        formats.getOrPut(name) { user32?.RegisterClipboardFormatW(WString(name)) ?: 0 }
    }

    /** Goes up on every change to the clipboard; null off Windows. */
    fun sequence(): Long? = user32?.let { runCatching { it.GetClipboardSequenceNumber().toLong() and 0xffffffffL }.getOrNull() }

    fun sensitive(): Boolean {
        val u = user32 ?: return false
        val present = listOf(ClipSensitivity.EXCLUDE, ClipSensitivity.CAN_INCLUDE, ClipSensitivity.VIEWER_IGNORE)
            .filter { name -> format(name).let { it != 0 && runCatching { u.IsClipboardFormatAvailable(it) }.getOrDefault(false) } }.toSet()
        val canInclude = if (ClipSensitivity.CAN_INCLUDE in present) readDword(format(ClipSensitivity.CAN_INCLUDE)) else null
        return ClipSensitivity.windows(present, canInclude)
    }

    private fun readDword(format: Int): Int? {
        val u = user32 ?: return null
        val k = kernel32 ?: return null
        // The AWT may hold the clipboard for a moment; a few tries.
        repeat(5) {
            if (runCatching { u.OpenClipboard(null) }.getOrDefault(false)) {
                try {
                    val handle = u.GetClipboardData(format) ?: return null
                    val data = k.GlobalLock(handle) ?: return null
                    return try { data.getInt(0) } finally { k.GlobalUnlock(handle) }
                } finally {
                    u.CloseClipboard()
                }
            }
            Thread.sleep(10)
        }
        return null
    }

    /** The window in front, to give the focus back to it after the picker. */
    fun foreground(): Pointer? = user32?.let { runCatching { it.GetForegroundWindow() }.getOrNull() }

    fun activate(window: Pointer) { user32?.let { runCatching { it.SetForegroundWindow(window) } } }
}

/**
 * A cheap mark of the clipboard's state, taken before reading it. [exact] when every copy moves it (Windows' sequence
 * number, XFixes' selection events); false when a copy can leave it as it was (the X11 owner window alone, which
 * stays the same when one app copies twice).
 */
data class ClipMark(val value: Long, val exact: Boolean)

/** What a look at the clipboard needs to read, from [ClipGate.look]. */
enum class ClipLook {
    /** Nothing changed: nothing is read. */
    Unchanged,
    /** Something may have changed: everything is read, pictures too. */
    Changed,
    /** Can't be sure: the text (cheap) is read, pictures only when the mark says they changed. */
    TextOnly,
}

/**
 * Whether the clipboard needs reading, from a [mark] that costs a system call instead of the whole clipboard. Each
 * watcher has its own. With no mark (macOS, no X display) everything is read every time, as before. A look whose
 * read failed (the clipboard was held by another app) is [retry]-ed: the next look reads again.
 */
class ClipGate(private val mark: () -> ClipMark? = ClipMarks::current) {
    private var last: ClipMark? = null

    @Synchronized
    fun look(): ClipLook {
        val now = runCatching(mark).getOrNull() ?: return ClipLook.Changed
        val before = last
        last = now
        return when {
            before == null || before.value != now.value -> ClipLook.Changed
            now.exact -> ClipLook.Unchanged
            else -> ClipLook.TextOnly
        }
    }

    /** The clipboard couldn't be read this time: the next look reads everything again. */
    @Synchronized
    fun retry() { last = null }
}

/** The system's clipboard mark: Windows' sequence number, or the X11 CLIPBOARD selection (XWayland included). */
object ClipMarks {
    fun current(): ClipMark? = when {
        Platform.isWindows -> WinClipboard.sequence()?.let { ClipMark(it, exact = true) }
        Platform.isLinux && !System.getenv("DISPLAY").isNullOrBlank() -> X11Selection.instance?.mark()
        else -> null
    }
}

/**
 * The X11 CLIPBOARD selection on a connection of its own. With XFixes every SetSelectionOwner is an event, even when
 * the same app copies again, so a count of them is an exact mark; without XFixes only the owner window is known.
 */
class X11Selection private constructor(private val x: XLib, private val fixes: XFixes?, private val display: Pointer, private val atom: NativeLong) {
    @Suppress("FunctionName")
    interface XLib : Library {
        fun XOpenDisplay(name: String?): Pointer?
        fun XInternAtom(display: Pointer, name: String, onlyIfExists: Boolean): NativeLong
        fun XGetSelectionOwner(display: Pointer, selection: NativeLong): NativeLong
        fun XDefaultRootWindow(display: Pointer): NativeLong
        fun XPending(display: Pointer): Int
        fun XNextEvent(display: Pointer, event: Pointer): Int
        fun XFlush(display: Pointer): Int
    }

    @Suppress("FunctionName")
    interface XFixes : Library {
        fun XFixesQueryExtension(display: Pointer, eventBase: IntByReference, errorBase: IntByReference): Boolean
        fun XFixesSelectSelectionInput(display: Pointer, window: NativeLong, selection: NativeLong, mask: NativeLong)
    }

    private var eventBase = -1
    private var changes = 0L
    /** An XEvent is a union of 24 longs. */
    private val event = Memory(24L * Native.LONG_SIZE)

    private fun listen(): Boolean {
        val f = fixes ?: return false
        val base = IntByReference()
        val error = IntByReference()
        if (!runCatching { f.XFixesQueryExtension(display, base, error) }.getOrDefault(false)) return false
        runCatching {
            f.XFixesSelectSelectionInput(display, x.XDefaultRootWindow(display), atom, NativeLong(SELECTION_EVENTS))
            x.XFlush(display)
        }.onFailure { return false }
        eventBase = base.value
        return true
    }

    @Synchronized
    fun mark(): ClipMark = if (eventBase >= 0) {
        // Only this connection's own events are queued, and only XFixes' are asked for.
        while (x.XPending(display) > 0) {
            x.XNextEvent(display, event)
            if (event.getInt(0) == eventBase + XFIXES_SELECTION_NOTIFY) changes++
        }
        ClipMark(changes, exact = true)
    } else {
        ClipMark(x.XGetSelectionOwner(display, atom).toLong(), exact = false)
    }

    companion object {
        const val XFIXES_SELECTION_NOTIFY = 0
        /** SetSelectionOwner, SelectionWindowDestroy and SelectionClientClose. */
        const val SELECTION_EVENTS = 7L

        val instance: X11Selection? by lazy {
            runCatching {
                val x = Native.load("X11", XLib::class.java)
                val fixes = runCatching { Native.load("Xfixes", XFixes::class.java) }.getOrNull()
                val display = x.XOpenDisplay(null) ?: return@runCatching null
                X11Selection(x, fixes, display, x.XInternAtom(display, "CLIPBOARD", false)).also {
                    val events = it.listen()
                    CoreLog.i("DyskLink", "Clipboard changes: ${if (events) "XFixes" else "owner window"}")
                }
            }.onFailure { CoreLog.w("DyskLink", "No X11 clipboard mark", it) }.getOrNull()
        }
    }
}

/** KDE's password hint as an AWT flavor on X11 and XWayland, so the clipboard can say whether it's there. */
private object LinuxHintFlavor {
    val flavor: DataFlavor? by lazy {
        runCatching {
            val f = DataFlavor("application/x-dysklink-password-hint;class=java.io.InputStream")
            (SystemFlavorMap.getDefaultFlavorMap() as SystemFlavorMap).apply {
                addUnencodedNativeForFlavor(f, ClipSensitivity.KDE_HINT)
                addFlavorForUnencodedNative(ClipSensitivity.KDE_HINT, f)
            }
            f
        }.getOrNull()
    }
}

/**
 * Watches the clipboard for the history and puts entries back on it. Copies seen are handed to [onCopy]; what
 * DyskLink sets itself goes through [expect] first, so it isn't recorded again as a copy on this PC.
 */
class ClipboardWatch(
    private val onCopy: (ClipCapture) -> Unit,
    /** Text only on the clipboard for a moment, for typing from a phone; never recorded. */
    private val passingThrough: () -> String? = { null },
    private val wayland: Boolean = Typing.desktop() == Desktop.WAYLAND,
    private val hasWlPaste: () -> Boolean = { Typing.onPath("wl-paste") },
    private val hasWlCopy: () -> Boolean = { Typing.onPath("wl-copy") },
    /** The system clipboard; tests pass their own. */
    private val board: () -> java.awt.datatransfer.Clipboard = { Toolkit.getDefaultToolkit().systemClipboard },
    private val gate: ClipGate = ClipGate(),
) {
    private val detector = ClipChangeDetector()
    private val _mode = MutableStateFlow(ClipWatchMode.Off)
    val mode: StateFlow<ClipWatchMode> = _mode.asStateFlow()
    @Volatile private var running = false
    private var poller: Thread? = null
    private var wlProcess: Process? = null
    private val clipboard get() = board()

    @Synchronized
    fun start() {
        if (running) return
        running = true
        if (wayland && hasWlPaste() && startWlPaste()) return
        startPolling(if (!wayland) ClipWatchMode.Poll else if (hasWlPaste()) ClipWatchMode.PollWlUnsupported else ClipWatchMode.PollNoWlPaste)
    }

    @Synchronized
    fun stop() {
        running = false
        poller?.interrupt()
        poller = null
        wlProcess?.destroy()
        wlProcess = null
        _mode.value = ClipWatchMode.Off
    }

    private fun startPolling(mode: ClipWatchMode) {
        _mode.value = mode
        // What was copied while this was off stays unrecorded: the first look only sets where it starts.
        poller = thread(isDaemon = true, name = "dysk-clip-history") {
            while (running && !Thread.currentThread().isInterrupted) {
                runCatching { pollOnce() }.onFailure { if (it is InterruptedException) return@thread }
                try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { return@thread }
            }
        }
    }

    /** One look at the AWT clipboard. */
    internal fun pollOnce() {
        val look = gate.look()
        if (look == ClipLook.Unchanged) return
        var read: AwtRead? = null
        var failed = false
        val isNew = detector.isNew(null) {
            readAwt(images = look == ClipLook.Changed, failed = { failed = true }).also { read = it }?.signature
        }
        if (failed) gate.retry()
        val found = read ?: return
        if (!isNew) return
        if (found.text != null && found.text == passingThrough()) return
        val sensitive = when {
            Platform.isWindows -> WinClipboard.sensitive()
            else -> LinuxHintFlavor.flavor?.let { f -> runCatching { clipboard.isDataFlavorAvailable(f) }.getOrDefault(false) } ?: false
        }
        val capture = when {
            found.files != null -> ClipCapture.Files(found.files, sensitive)
            found.text != null -> ClipCapture.Text(found.text, sensitive)
            found.image != null -> png(found.image)?.let { ClipCapture.Image(it, sensitive) }
            else -> null
        } ?: return
        onCopy(capture)
    }

    private class AwtRead(val signature: String, val text: String? = null, val files: List<String>? = null, val image: BufferedImage? = null)

    /** What's on the clipboard; pictures only with [images]. [failed] when the clipboard couldn't be opened. */
    private fun readAwt(images: Boolean = true, failed: () -> Unit = {}): AwtRead? {
        val board = clipboard
        // Windows: another app may hold the clipboard open for a moment; that look is tried again.
        val flavors = runCatching { board.availableDataFlavors.toSet() }.getOrElse { failed(); return null }
        fun has(flavor: DataFlavor) = flavor in flavors
        if (has(DataFlavor.javaFileListFlavor)) {
            @Suppress("UNCHECKED_CAST")
            val files = runCatching { board.getData(DataFlavor.javaFileListFlavor) as? List<File> }.getOrNull()
            val paths = files?.map { it.path }?.takeIf { it.isNotEmpty() }
            if (paths != null) return AwtRead(ClipChangeDetector.filesSignature(paths), files = paths)
        }
        if (has(DataFlavor.stringFlavor)) {
            val text = runCatching { board.getData(DataFlavor.stringFlavor) as? String }.getOrNull()
            if (text != null) {
                // Linux file managers also offer the paths as text/uri-list text.
                val paths = ClipboardWatch.uriListPaths(text)
                if (paths != null) return AwtRead(ClipChangeDetector.filesSignature(paths), files = paths)
                return AwtRead(ClipChangeDetector.textSignature(text), text = text)
            }
        }
        if (images && has(DataFlavor.imageFlavor)) {
            val image = runCatching { board.getData(DataFlavor.imageFlavor) as? java.awt.Image }.getOrNull()?.let(::buffered) ?: return null
            return AwtRead(ClipChangeDetector.imageSignature(Clipboard.quickFingerprint(image)), image = image)
        }
        return null
    }

    // Wayland, with wl-clipboard: a line from wl-paste for every copy, then a look at what it is.

    private fun startWlPaste(): Boolean {
        val process = runCatching {
            ProcessBuilder("wl-paste", "--watch", "sh", "-c", "cat > /dev/null; echo \"x\$CLIPBOARD_STATE\"")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrNull() ?: return false
        // GNOME's compositor can't watch: wl-paste ends at once and says so.
        if (process.waitFor(1_500, TimeUnit.MILLISECONDS)) {
            CoreLog.i("DyskLink", "wl-paste --watch isn't supported here (exit ${process.exitValue()}), polling instead")
            return false
        }
        wlProcess = process
        _mode.value = ClipWatchMode.WlPaste
        thread(isDaemon = true, name = "dysk-wl-paste") {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> if (running) runCatching { wlChanged(line.removePrefix("x")) } }
                }
            }
            synchronized(this) {
                // wl-paste ended by itself (the compositor went away?): fall back to looking twice a second.
                if (running && wlProcess === process) {
                    wlProcess = null
                    startPolling(ClipWatchMode.PollWlUnsupported)
                }
            }
        }
        return true
    }

    private fun wlChanged(state: String) {
        if (state == "nil" || state == "clear") return
        val types = wl("--list-types")?.toString(Charsets.UTF_8)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return
        val sensitive = ClipSensitivity.linux(types, state)
        var capture: ClipCapture? = null
        val signature: String? = when {
            "text/uri-list" in types -> wl("-n", "-t", "text/uri-list")?.toString(Charsets.UTF_8)?.let(::uriListPaths)
                ?.let { capture = ClipCapture.Files(it, sensitive); ClipChangeDetector.filesSignature(it) }
            types.any { it.startsWith("text/plain") || it == "UTF8_STRING" || it == "STRING" || it == "TEXT" } ->
                (wl("-n", "-t", "text/plain;charset=utf-8") ?: wl("-n", "-t", "text"))?.toString(Charsets.UTF_8)
                    ?.let { capture = ClipCapture.Text(it, sensitive); ClipChangeDetector.textSignature(it) }
            types.any { it.startsWith("image/") } -> {
                val type = if ("image/png" in types) "image/png" else types.first { it.startsWith("image/") }
                val bytes = wl("-t", type)
                val image = bytes?.let { runCatching { ImageIO.read(it.inputStream()) }.getOrNull() }
                image?.let {
                    val png = if (type == "image/png") bytes else png(it)
                    if (png != null) capture = ClipCapture.Image(png, sensitive)
                    ClipChangeDetector.imageSignature(Clipboard.quickFingerprint(it))
                }
            }
            else -> null
        }
        if (signature == null || !detector.isNew(null) { signature }) return
        val found = capture ?: return
        if (found is ClipCapture.Text && found.text == passingThrough()) return
        onCopy(found)
    }

    private fun wl(vararg args: String): ByteArray? = runCatching {
        val process = ProcessBuilder(listOf("wl-paste") + args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val bytes = process.inputStream.use { it.readNBytes(ClipHistoryStore.MAX_IMAGE_BYTES + 1) }
        if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroy(); return null }
        if (process.exitValue() != 0) null else bytes
    }.getOrNull()

    // Putting an entry back.

    /** Puts [entry] on the clipboard; false when it couldn't. [image] is the entry's PNG file. */
    fun put(entry: ClipEntry, image: File?): Boolean = runCatching {
        when (entry.kind) {
            ClipKind.Text -> {
                val text = entry.text ?: return false
                detector.expect(ClipChangeDetector.textSignature(text))
                if (wayland && hasWlCopy()) wlCopy(text.toByteArray(), null) else clipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
            }
            ClipKind.Files -> {
                val paths = entry.files ?: return false
                detector.expect(ClipChangeDetector.filesSignature(paths))
                if (wayland && hasWlCopy()) wlCopy(uriList(paths).toByteArray(), "text/uri-list")
                else clipboard.setContents(FilesSelection(paths.map(::File)), null)
            }
            ClipKind.Image -> {
                val bytes = image?.takeIf { it.isFile }?.readBytes() ?: return false
                val picture = ImageIO.read(bytes.inputStream()) ?: return false
                detector.expect(ClipChangeDetector.imageSignature(Clipboard.quickFingerprint(picture)))
                if (wayland && hasWlCopy()) wlCopy(bytes, "image/png") else clipboard.setContents(ImageSelection(picture), null)
            }
        }
        true
    }.getOrElse {
        CoreLog.w("DyskLink", "Couldn't put the clipboard entry back", it)
        false
    }

    /** Set by DyskLink from a phone or computer, so it's recorded with where it came from and not again. */
    fun expect(capture: ClipCapture) {
        when (capture) {
            is ClipCapture.Text -> detector.expect(ClipChangeDetector.textSignature(capture.text))
            is ClipCapture.Files -> detector.expect(ClipChangeDetector.filesSignature(capture.paths))
            is ClipCapture.Image -> runCatching { ImageIO.read(capture.png.inputStream()) }.getOrNull()
                ?.let { detector.expect(ClipChangeDetector.imageSignature(Clipboard.quickFingerprint(it))) }
        }
    }

    private fun wlCopy(bytes: ByteArray, type: String?) {
        val process = ProcessBuilder(listOfNotNull("wl-copy", type?.let { "-t" }, type)).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.use { it.write(bytes) }
        // wl-copy stays in the background serving the paste; it hands the data over and returns.
        process.waitFor(2, TimeUnit.SECONDS)
    }

    private class FilesSelection(private val files: List<File>) : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor in transferDataFlavors
        override fun getTransferData(flavor: DataFlavor): Any = when (flavor) {
            DataFlavor.javaFileListFlavor -> files
            DataFlavor.stringFlavor -> files.joinToString("\n") { it.path }
            else -> throw UnsupportedFlavorException(flavor)
        }
    }

    private class ImageSelection(private val image: java.awt.Image) : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
        override fun getTransferData(flavor: DataFlavor): Any =
            if (flavor == DataFlavor.imageFlavor) image else throw UnsupportedFlavorException(flavor)
    }

    companion object {
        const val POLL_MS = 500L

        /** Paths from a text/uri-list, when every line is a file:// URI; null for any other text. */
        fun uriListPaths(text: String): List<String>? {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            if (lines.isEmpty() || !lines.all { it.startsWith("file://") }) return null
            return lines.mapNotNull { runCatching { File(URI(it)).path }.getOrNull() }.takeIf { it.size == lines.size }
        }

        fun uriList(paths: List<String>): String = paths.joinToString("\r\n") { File(it).toURI().toString() } + "\r\n"

        fun png(image: BufferedImage): ByteArray? = runCatching {
            ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        }.getOrNull()

        private fun buffered(image: java.awt.Image): BufferedImage? {
            if (image is BufferedImage) return image
            val w = image.getWidth(null)
            val h = image.getHeight(null)
            if (w <= 0 || h <= 0) return null
            return BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { it.createGraphics().apply { drawImage(image, 0, 0, null); dispose() } }
        }
    }
}
