package id.dysklink.desktop

import id.phonelink.android.core.CoreLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/** What the Tools page's Clipboard History and the quick picker show. */
data class ClipHistoryView(
    val entries: List<ClipEntry> = emptyList(),
    val settings: ClipSettings = ClipSettings(),
    val hotkey: HotkeyStatus = HotkeyStatus(HotkeyBackend.Command),
    val combo: String = HotkeyCombo.DEFAULT.label,
    val mode: ClipWatchMode = ClipWatchMode.Off,
    val imports: List<ClipImportSource> = emptyList(),
    /** What the last import did, or why it didn't. */
    val importMessage: String? = null,
    val importing: Boolean = false,
    val hints: List<HostHint> = emptyList(),
    val passwordLike: Int = 0,
)

sealed interface ClipAction {
    data class Copy(val id: String) : ClipAction
    data class Pin(val id: String) : ClipAction
    data class Delete(val id: String) : ClipAction
    data object ClearUnpinned : ClipAction
    data object RemovePasswordLike : ClipAction
    data class SetSettings(val settings: ClipSettings) : ClipAction
    data class Import(val source: ClipImportSource, val file: File? = null) : ClipAction
    data object RetryHotkey : ClipAction
}

/**
 * Clipboard History on this PC: the store, the watcher, and the picker's shortcut, on while the Tools switch is.
 * [pressPaste] presses Ctrl+V in the app in front; [canPaste] says whether it can.
 */
class ClipHistoryService(
    private val scope: CoroutineScope,
    private val settings: Settings,
    dataDir: File,
    private val portal: () -> PortalClient?,
    passingThrough: () -> String?,
    private val pressPaste: () -> Unit,
    private val canPaste: () -> Boolean,
) {
    val store = ClipHistoryStore(dataDir, loadSettings())
    private val watch = ClipboardWatch(onCopy = { store.add(it) }, passingThrough = passingThrough)
    private val desktop = Typing.desktop()
    private val combo = HotkeyCombo.parse(settings.string("clipHistory.hotkey").orEmpty()) ?: HotkeyCombo.DEFAULT
    private var hotkey: GlobalHotkey? = null
    private val _hotkey = MutableStateFlow(HotkeyStatus(Hotkeys.choose(desktop, false)))
    private val _import = MutableStateFlow<Pair<Boolean, String?>>(false to null)
    private val _view = MutableStateFlow(ClipHistoryView())
    val view: StateFlow<ClipHistoryView> = _view.asStateFlow()
    private val _openPicker = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** The picker should open: from the shortcut, the tray, or `dysklink --clipboard`. */
    val openPicker: SharedFlow<Unit> = _openPicker.asSharedFlow()
    @Volatile private var previousWindow: com.sun.jna.Pointer? = null
    private val distro by lazy { HostChecks.distro(runCatching { File("/etc/os-release").readText() }.getOrDefault("")) }

    private fun loadSettings() = ClipSettings(
        enabled = settings.bool("clipHistory", false),
        paused = settings.bool("clipHistory.paused", false),
        ignorePasswords = settings.bool("clipHistory.ignorePasswords", true),
        maxEntries = settings.int("clipHistory.maxEntries") ?: ClipHistoryStore.DEFAULT_ENTRIES,
        maxImages = settings.int("clipHistory.maxImages") ?: ClipHistoryStore.DEFAULT_IMAGES,
    )

    fun start() {
        combine(store.entries, store.settings, _hotkey, watch.mode, _import) { entries, s, hk, mode, imp ->
            ClipHistoryView(
                entries = entries, settings = s, hotkey = hk, combo = combo.label, mode = mode,
                imports = imports, importing = imp.first, importMessage = imp.second,
                hints = if (!s.enabled) emptyList() else HostChecks.clipboardHints(
                    mode, hk, combo.label, Hotkeys.command(), canPaste(), distro, System.getenv("XDG_CURRENT_DESKTOP"),
                ),
                passwordLike = if (s.ignorePasswords) store.passwordLikeCount else 0,
            )
        }.onEach { _view.value = it }.launchIn(scope)
        apply(store.settings.value)
    }

    private val imports: List<ClipImportSource> by lazy { ClipboardImport.available() }

    fun stop() {
        watch.stop()
        hotkey?.close()
        hotkey = null
        store.flush()
    }

    /** Starts or stops watching and the shortcut, for the settings now. */
    private fun apply(s: ClipSettings) {
        if (s.recording) watch.start() else watch.stop()
        if (s.enabled && hotkey == null) startHotkey()
        if (!s.enabled) { hotkey?.close(); hotkey = null }
    }

    private fun startHotkey() {
        scope.launch(Dispatchers.IO) {
            val portalShortcuts = desktop == Desktop.WAYLAND &&
                portal()?.offer(PortalBits.GLOBAL_SHORTCUTS)?.version != null
            val backend = Hotkeys.choose(desktop, portalShortcuts)
            val open = { requestPicker() }
            val key: GlobalHotkey = when (backend) {
                HotkeyBackend.Windows -> WindowsHotkey(combo, open)
                HotkeyBackend.X11 -> X11Hotkey(combo, open)
                HotkeyBackend.Portal -> PortalHotkey(portal, combo, open)
                HotkeyBackend.Command -> CommandHotkey()
            }
            synchronized(this@ClipHistoryService) {
                if (!store.settings.value.enabled) return@launch
                hotkey?.close()
                hotkey = key
            }
            key.status.onEach { _hotkey.value = it }.launchIn(scope)
            runCatching { key.start() }.onFailure { CoreLog.w("DyskLink", "The clipboard shortcut failed", it) }
        }
    }

    /** Opens the picker, remembering the window in front so the paste goes back there (Windows). */
    fun requestPicker() {
        if (!store.settings.value.enabled) return
        previousWindow = WinClipboard.foreground()
        _openPicker.tryEmit(Unit)
    }

    fun act(action: ClipAction) {
        when (action) {
            is ClipAction.Copy -> store.entries.value.firstOrNull { it.id == action.id }?.let { copy(it) }
            is ClipAction.Pin -> store.togglePin(action.id)
            is ClipAction.Delete -> store.delete(action.id)
            ClipAction.ClearUnpinned -> store.clearUnpinned()
            ClipAction.RemovePasswordLike -> store.removePasswordLike()
            is ClipAction.SetSettings -> {
                val s = action.settings
                settings.set("clipHistory", s.enabled)
                settings.set("clipHistory.paused", s.paused)
                settings.set("clipHistory.ignorePasswords", s.ignorePasswords)
                settings.set("clipHistory.maxEntries", s.maxEntries.toString())
                settings.set("clipHistory.maxImages", s.maxImages.toString())
                store.setSettings(s)
                apply(store.settings.value)
            }
            is ClipAction.Import -> import(action.source, action.file)
            ClipAction.RetryHotkey -> { hotkey?.close(); hotkey = null; startHotkey() }
        }
    }

    private fun copy(entry: ClipEntry): Boolean {
        val ok = watch.put(entry, store.imageFile(entry))
        if (ok) store.touch(entry.id)
        return ok
    }

    /** Enter in the picker: on the clipboard, focus back to the app it was opened over, then Ctrl+V. */
    fun paste(id: String) {
        val entry = store.entries.value.firstOrNull { it.id == id } ?: return
        scope.launch(Dispatchers.IO) {
            if (!copy(entry)) return@launch
            if (!canPaste()) return@launch
            // The picker's window has to be gone and the other app's back in front first.
            delay(120)
            previousWindow?.let(WinClipboard::activate)
            delay(80)
            pressPaste()
        }
    }

    /** Text or a picture from a phone or computer, already put on this PC's clipboard. */
    fun received(capture: ClipCapture, from: String) {
        watch.expect(capture)
        store.add(capture, source = from)
    }

    private fun import(source: ClipImportSource, file: File?) {
        if (_import.value.first) return
        _import.value = true to null
        scope.launch(Dispatchers.IO) {
            val message = try {
                val clips = when (source) {
                    ClipImportSource.Ditto -> ClipboardImport.readDitto(
                        file ?: ClipboardImport.findDitto() ?: throw ClipImportException("Ditto.db wasn't found. Choose it with Choose file."),
                    )
                    ClipImportSource.CopyQ -> ClipboardImport.readCopyQ()
                }
                val (added, trimmed) = store.importClips(clips)
                when {
                    clips.isEmpty() -> "No text or images were found in ${source.label}."
                    added == 0 -> "${clips.size} items were found, and all of them are already in the history."
                    trimmed > 0 -> "Added $added items from ${source.label}. $trimmed of the oldest didn't fit the limit and were removed; choose Unlimited to keep everything."
                    else -> "Added $added items from ${source.label}, sorted by date."
                }
            } catch (e: ClipImportException) {
                e.message
            } catch (e: Exception) {
                CoreLog.w("DyskLink", "Clipboard import failed", e)
                "The import failed: ${e.message}"
            }
            _import.value = false to message
        }
    }
}
