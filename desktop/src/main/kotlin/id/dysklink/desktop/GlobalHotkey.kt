@file:Suppress("FunctionName")

package id.dysklink.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import id.phonelink.android.core.CoreLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the clipboard picker's shortcut reaches DyskLink. */
enum class HotkeyBackend {
    /** RegisterHotKey, on a thread with its own message loop. */
    Windows,
    /** XGrabKey on the root window. */
    X11,
    /** org.freedesktop.portal.GlobalShortcuts: the desktop asks once and then sends the key. */
    Portal,
    /** None of those: a desktop shortcut runs `dysklink --clipboard`, which tells the running DyskLink. */
    Command,
}

enum class HotkeyPhase { Off, Waiting, Ready, Taken, Failed }

data class HotkeyStatus(
    val backend: HotkeyBackend,
    val phase: HotkeyPhase = HotkeyPhase.Off,
    val detail: String? = null,
    /** With several shortcuts at once: the ones another app holds, by label, while the others work. */
    val taken: List<String> = emptyList(),
)

/** A shortcut such as Ctrl+Shift+V or Alt+Space: modifiers and one key, a letter, a digit, or one of [NAMED]. */
data class HotkeyCombo(val ctrl: Boolean, val shift: Boolean, val alt: Boolean, val meta: Boolean, val key: String) {
    constructor(ctrl: Boolean, shift: Boolean, alt: Boolean, meta: Boolean, key: Char) : this(ctrl, shift, alt, meta, key.uppercaseChar().toString())

    val label: String get() = listOfNotNull("Ctrl".takeIf { ctrl }, "Alt".takeIf { alt }, "Shift".takeIf { shift }, "Super".takeIf { meta }, key)
        .joinToString("+")

    /** The portal's preferred_trigger, as in the XDG shortcuts spec: "CTRL+SHIFT+v", "ALT+space". */
    val portalTrigger: String get() = listOfNotNull("CTRL".takeIf { ctrl }, "ALT".takeIf { alt }, "SHIFT".takeIf { shift }, "LOGO".takeIf { meta }, keysymName)
        .joinToString("+")

    /** The X keysym's name, which the portal takes. */
    val keysymName: String get() = when (key) {
        "Space" -> "space"
        "Enter" -> "Return"
        "Left", "Right", "Up", "Down" -> key
        else -> key.lowercase()
    }

    /** RegisterHotKey's virtual key. */
    val windowsKey: Int get() = when (key) {
        "Space" -> 0x20
        "Enter" -> 0x0D
        "Left" -> 0x25
        "Up" -> 0x26
        "Right" -> 0x27
        "Down" -> 0x28
        else -> key[0].uppercaseChar().code
    }

    /** XKeysymToKeycode's keysym. */
    val x11Keysym: Long get() = when (key) {
        "Space" -> 0x20
        "Enter" -> 0xFF0D
        "Left" -> 0xFF51
        "Up" -> 0xFF52
        "Right" -> 0xFF53
        "Down" -> 0xFF54
        else -> key[0].lowercaseChar().code.toLong()
    }

    fun with(ctrl: Boolean = this.ctrl, shift: Boolean = this.shift, alt: Boolean = this.alt, meta: Boolean = this.meta, key: String = this.key) =
        HotkeyCombo(ctrl, shift, alt, meta, key)

    companion object {
        val DEFAULT = HotkeyCombo(ctrl = true, shift = true, alt = false, meta = false, key = "V")
        /** Keys other than letters and digits a shortcut may end in, by the name people type. */
        val NAMED = mapOf("space" to "Space", "enter" to "Enter", "return" to "Enter", "left" to "Left", "right" to "Right", "up" to "Up", "down" to "Down")

        fun parse(text: String): HotkeyCombo? {
            val parts = text.split('+').map { it.trim() }.filter { it.isNotEmpty() }
            val last = parts.lastOrNull() ?: return null
            val key = NAMED[last.lowercase()] ?: last.takeIf { it.length == 1 && it[0].isLetterOrDigit() && it[0].code < 128 }?.uppercase() ?: return null
            val mods = parts.dropLast(1).map { it.lowercase() }
            if (mods.any { it !in setOf("ctrl", "control", "shift", "alt", "super", "meta", "win", "logo") }) return null
            val combo = HotkeyCombo(
                ctrl = "ctrl" in mods || "control" in mods, shift = "shift" in mods, alt = "alt" in mods,
                meta = mods.any { it in setOf("super", "meta", "win", "logo") }, key = key,
            )
            return combo.takeIf { it.ctrl || it.alt || it.meta }
        }
    }
}

/** One shortcut and what it does, for a [GlobalHotkey] that holds several. */
data class HotkeyBinding(val combo: HotkeyCombo, val onPress: () -> Unit)

object Hotkeys {
    /** Windows and X11 grab the key themselves; on Wayland the portal does, when the desktop has it. */
    fun choose(desktop: Desktop, portalHasShortcuts: Boolean): HotkeyBackend = when (desktop) {
        Desktop.WINDOWS -> HotkeyBackend.Windows
        Desktop.X11 -> HotkeyBackend.X11
        Desktop.WAYLAND -> if (portalHasShortcuts) HotkeyBackend.Portal else HotkeyBackend.Command
        Desktop.OTHER -> HotkeyBackend.Command
    }

    /** What to put in a desktop shortcut: the installed launcher when DyskLink runs from one. */
    fun command(appPath: String? = System.getProperty("jpackage.app-path"), flag: String = "--clipboard"): String =
        (appPath?.takeIf { it.isNotBlank() }?.let { if (' ' in it) "\"$it\"" else it } ?: "dysklink") + " " + flag

    /** What several shortcuts registered together came to: all, some (with the others in [HotkeyStatus.taken]), or none. */
    fun outcome(backend: HotkeyBackend, results: List<Pair<String, Boolean>>, why: String? = null): HotkeyStatus {
        val taken = results.filterNot { it.second }.map { it.first }
        return when {
            results.isEmpty() -> HotkeyStatus(backend, HotkeyPhase.Failed, "No shortcuts to set up.")
            taken.isEmpty() -> HotkeyStatus(backend, HotkeyPhase.Ready)
            taken.size == results.size && results.size == 1 -> HotkeyStatus(backend, HotkeyPhase.Taken, "Another app already uses ${taken[0]}${why?.let { " ($it)" } ?: ""}.", taken)
            taken.size == results.size -> HotkeyStatus(backend, HotkeyPhase.Taken, "Other apps already use these shortcuts.", taken)
            else -> HotkeyStatus(backend, HotkeyPhase.Ready, "Another app already uses ${taken.joinToString(", ")}.", taken)
        }
    }
}

/** A global shortcut that calls [onPress]; stopped by [close]. */
interface GlobalHotkey : AutoCloseable {
    val status: StateFlow<HotkeyStatus>
    fun start()
    override fun close()
}

/** RegisterHotKey. WM_HOTKEY goes to the thread that registered it, so that thread runs a message loop. */
class WindowsHotkey(private val bindings: List<HotkeyBinding>) : GlobalHotkey {
    constructor(combo: HotkeyCombo, onPress: () -> Unit) : this(listOf(HotkeyBinding(combo, onPress)))

    private interface User32 : Library {
        fun RegisterHotKey(window: Pointer?, id: Int, modifiers: Int, key: Int): Boolean
        fun UnregisterHotKey(window: Pointer?, id: Int): Boolean
        fun GetMessageW(msg: Pointer, window: Pointer?, min: Int, max: Int): Int
        fun PostThreadMessageW(thread: Int, msg: Int, wParam: Pointer?, lParam: Pointer?): Boolean
    }

    private interface Kernel32 : Library {
        fun GetCurrentThreadId(): Int
        fun GetLastError(): Int
    }

    private val _status = MutableStateFlow(HotkeyStatus(HotkeyBackend.Windows))
    override val status: StateFlow<HotkeyStatus> = _status.asStateFlow()
    @Volatile private var threadId = 0

    override fun start() {
        val user32 = runCatching { Native.load("user32", User32::class.java) }.getOrNull()
        val kernel32 = runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull()
        if (user32 == null || kernel32 == null) {
            _status.value = HotkeyStatus(HotkeyBackend.Windows, HotkeyPhase.Failed, "user32 couldn't be loaded.")
            return
        }
        val ready = CountDownLatch(1)
        thread(isDaemon = true, name = "dysk-hotkey") {
            threadId = kernel32.GetCurrentThreadId()
            var error = 0
            val results = bindings.mapIndexed { index, binding ->
                val combo = binding.combo
                var mods = MOD_NOREPEAT
                if (combo.ctrl) mods = mods or MOD_CONTROL
                if (combo.shift) mods = mods or MOD_SHIFT
                if (combo.alt) mods = mods or MOD_ALT
                if (combo.meta) mods = mods or MOD_WIN
                val ok = user32.RegisterHotKey(null, ID + index, mods, combo.windowsKey)
                if (!ok) error = kernel32.GetLastError()
                combo.label to ok
            }
            _status.value = Hotkeys.outcome(HotkeyBackend.Windows, results, "error $error".takeIf { error != 0 })
            ready.countDown()
            if (results.none { it.second }) return@thread
            // MSG is 48 bytes on 64-bit Windows, 28 on 32-bit: the window handle, the message, then wParam (the id).
            val msg = Memory(48)
            try {
                while (true) {
                    val got = user32.GetMessageW(msg, null, 0, 0)
                    if (got <= 0) break
                    if (msg.getInt(Native.POINTER_SIZE.toLong()) == WM_HOTKEY) {
                        val index = msg.getInt(Native.POINTER_SIZE * 2L) - ID
                        bindings.getOrNull(index)?.let { runCatching(it.onPress) }
                    }
                }
            } finally {
                results.forEachIndexed { index, (_, ok) -> if (ok) user32.UnregisterHotKey(null, ID + index) }
            }
        }
        ready.await(2, TimeUnit.SECONDS)
    }

    override fun close() {
        val id = threadId
        if (id != 0) runCatching { Native.load("user32", User32::class.java).PostThreadMessageW(id, WM_QUIT, null, null) }
        threadId = 0
        _status.value = HotkeyStatus(HotkeyBackend.Windows)
    }

    private companion object {
        const val ID = 0x4C4B
        const val MOD_ALT = 1
        const val MOD_CONTROL = 2
        const val MOD_SHIFT = 4
        const val MOD_WIN = 8
        const val MOD_NOREPEAT = 0x4000
        const val WM_HOTKEY = 0x0312
        const val WM_QUIT = 0x0012
    }
}

/**
 * XGrabKey on the root window over a display connection of its own, read by a thread. Grabbed also with Caps Lock
 * and Num Lock on, since X counts them as modifiers. An X error for a key another app holds goes to the AWT's error
 * handler, which ignores it, so a key that's taken just never comes.
 */
class X11Hotkey(private val bindings: List<HotkeyBinding>) : GlobalHotkey {
    constructor(combo: HotkeyCombo, onPress: () -> Unit) : this(listOf(HotkeyBinding(combo, onPress)))

    private interface X11 : Library {
        fun XOpenDisplay(name: String?): Pointer?
        fun XDefaultRootWindow(display: Pointer): NativeLong
        fun XKeysymToKeycode(display: Pointer, keysym: NativeLong): Byte
        fun XGrabKey(display: Pointer, keycode: Int, modifiers: Int, window: NativeLong, ownerEvents: Int, pointerMode: Int, keyboardMode: Int): Int
        fun XUngrabKey(display: Pointer, keycode: Int, modifiers: Int, window: NativeLong): Int
        fun XSync(display: Pointer, discard: Int): Int
        fun XPending(display: Pointer): Int
        fun XNextEvent(display: Pointer, event: Pointer): Int
        fun XCloseDisplay(display: Pointer): Int
    }

    private val _status = MutableStateFlow(HotkeyStatus(HotkeyBackend.X11))
    override val status: StateFlow<HotkeyStatus> = _status.asStateFlow()
    @Volatile private var running = false

    private class Grab(val keycode: Int, val mods: Int, val binding: HotkeyBinding) {
        var last = 0L
    }

    override fun start() {
        val x = runCatching { Native.load("X11", X11::class.java) }.recoverCatching { Native.load("libX11.so.6", X11::class.java) }.getOrNull()
        val display = x?.let { runCatching { it.XOpenDisplay(null) }.getOrNull() }
        if (x == null || display == null) {
            _status.value = HotkeyStatus(HotkeyBackend.X11, HotkeyPhase.Failed, "No X display to listen on.")
            return
        }
        val root = x.XDefaultRootWindow(display)
        val grabs = mutableListOf<Grab>()
        val results = bindings.map { binding ->
            val combo = binding.combo
            val keycode = x.XKeysymToKeycode(display, NativeLong(combo.x11Keysym)).toInt() and 0xff
            if (keycode == 0) return@map combo.label to false
            var mods = 0
            if (combo.shift) mods = mods or SHIFT
            if (combo.ctrl) mods = mods or CONTROL
            if (combo.alt) mods = mods or MOD1
            if (combo.meta) mods = mods or MOD4
            LOCKS.forEach { x.XGrabKey(display, keycode, it or mods, root, 1, GRAB_ASYNC, GRAB_ASYNC) }
            grabs += Grab(keycode, mods, binding)
            combo.label to true
        }
        x.XSync(display, 0)
        if (grabs.isEmpty()) {
            x.XCloseDisplay(display)
            _status.value = HotkeyStatus(HotkeyBackend.X11, HotkeyPhase.Failed, "The keyboard has no ${bindings.joinToString(", ") { it.combo.key }} key.")
            return
        }
        running = true
        _status.value = Hotkeys.outcome(HotkeyBackend.X11, results)
        // Where XKeyEvent keeps the modifiers and the key code, after the window and time fields.
        val stateAt = if (NativeLong.SIZE == 8) 80L else 48L
        thread(isDaemon = true, name = "dysk-hotkey") {
            val event = Memory(24L * NativeLong.SIZE)
            try {
                while (running) {
                    while (running && x.XPending(display) > 0) {
                        x.XNextEvent(display, event)
                        if (event.getInt(0) != KEY_PRESS) continue
                        val state = event.getInt(stateAt) and (SHIFT or CONTROL or MOD1 or MOD4)
                        val keycode = event.getInt(stateAt + 4)
                        val grab = grabs.firstOrNull { it.keycode == keycode && it.mods == state } ?: continue
                        val now = System.currentTimeMillis()
                        // Held down, X repeats the press; one is enough.
                        if (now - grab.last > 300) {
                            grab.last = now
                            runCatching(grab.binding.onPress)
                        }
                    }
                    Thread.sleep(40)
                }
            } catch (_: InterruptedException) {
            } finally {
                grabs.forEach { g -> LOCKS.forEach { runCatching { x.XUngrabKey(display, g.keycode, it or g.mods, root) } } }
                runCatching { x.XCloseDisplay(display) }
            }
        }
    }

    override fun close() {
        running = false
        _status.value = HotkeyStatus(HotkeyBackend.X11)
    }

    private companion object {
        const val SHIFT = 1
        const val LOCK = 2
        const val CONTROL = 4
        const val MOD1 = 8
        const val MOD2 = 16
        const val MOD4 = 64
        const val GRAB_ASYNC = 1
        const val KEY_PRESS = 2
        /** Caps Lock and Num Lock count as modifiers in X, so each key is grabbed with them on too. */
        val LOCKS = listOf(0, LOCK, MOD2, LOCK or MOD2)
    }
}

/** The GlobalShortcuts portal (GNOME 48 and later, KDE Plasma, Hyprland). The desktop may ask once in a dialog. */
class PortalHotkey(
    private val portal: () -> PortalClient?,
    private val combo: HotkeyCombo,
    private val onPress: () -> Unit,
    private val id: String = SHORTCUT_ID,
    private val description: String = "Show DyskLink's clipboard history",
) : GlobalHotkey {
    private val _status = MutableStateFlow(HotkeyStatus(HotkeyBackend.Portal))
    override val status: StateFlow<HotkeyStatus> = _status.asStateFlow()
    @Volatile private var binding: AutoCloseable? = null

    override fun start() {
        val client = portal() ?: run {
            _status.value = HotkeyStatus(HotkeyBackend.Portal, HotkeyPhase.Failed, "No desktop portal.")
            return
        }
        _status.value = HotkeyStatus(HotkeyBackend.Portal, HotkeyPhase.Waiting)
        thread(isDaemon = true, name = "dysk-hotkey-portal") {
            _status.value = try {
                binding = client.bindShortcut(id, description, combo.portalTrigger, onPress)
                HotkeyStatus(HotkeyBackend.Portal, HotkeyPhase.Ready)
            } catch (e: PortalRefused) {
                HotkeyStatus(HotkeyBackend.Portal, if (e.cancelled) HotkeyPhase.Taken else HotkeyPhase.Failed, e.message)
            } catch (e: Exception) {
                CoreLog.w("DyskLink", "The shortcuts portal failed", e)
                HotkeyStatus(HotkeyBackend.Portal, HotkeyPhase.Failed, e.message)
            }
        }
    }

    override fun close() {
        runCatching { binding?.close() }
        binding = null
        _status.value = HotkeyStatus(HotkeyBackend.Portal)
    }

    companion object {
        const val SHORTCUT_ID = "clipboard-history"
    }
}

/** No grab at all: the picker opens from the tray, the Tools page, or `dysklink --clipboard`. */
class CommandHotkey : GlobalHotkey {
    override val status: StateFlow<HotkeyStatus> = MutableStateFlow(HotkeyStatus(HotkeyBackend.Command, HotkeyPhase.Ready))
    override fun start() {}
    override fun close() {}
}
