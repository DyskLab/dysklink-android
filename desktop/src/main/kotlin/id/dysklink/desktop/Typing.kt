package id.dysklink.desktop

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * How text from the phone reaches the app in front, whatever keyboard layout this PC has.
 *
 * Typing by key code (java.awt.Robot) only gives the right characters on a US layout, so it's the last resort:
 * - Windows: SendInput with KEYEVENTF_UNICODE sends the characters themselves, for any layout and any script.
 * - X11: xdotool, which maps each character to a spare key code; without it the same is done here through
 *   Xlib and XTest; without either, the text is pasted.
 * - Wayland: plain characters by keysym through the desktop's RemoteDesktop portal, the rest pasted (GNOME only
 *   types keysyms the layout has); with /dev/uinput (no keysyms there) the text is pasted, or typed by US key
 *   positions when the clipboard fails.
 * Enter and Tab are pressed as keys, so they submit forms and move between fields as on a real keyboard.
 */
enum class Desktop { WINDOWS, X11, WAYLAND, OTHER }

enum class TypeMethod {
    /** Windows SendInput with KEYEVENTF_UNICODE. */
    UNICODE_INPUT,
    /** `xdotool type` on X11. */
    XDOTOOL,
    /** A spare X key code mapped to the character for a moment, then pressed through XTest. */
    X11_REMAP,
    /** Keysyms through the Wayland RemoteDesktop portal, which maps them to keys itself. */
    KEYSYM,
    /** Put on the clipboard, Ctrl+V, and the clipboard put back. */
    PASTE,
    /** java.awt.Robot with the US layout: only right on a US layout, so only when nothing else works. */
    US_KEYS,
}

/** What this PC can type with. Each one is switched off when it fails, and the text goes to the next. */
data class TypingTools(
    val desktop: Desktop,
    val unicodeInput: Boolean = false,
    val xdotool: Boolean = false,
    val x11Remap: Boolean = false,
    /** The Wayland portal can type keysyms. */
    val keysyms: Boolean = false,
    val clipboard: Boolean = false,
    val robot: Boolean = false,
) {
    fun without(method: TypeMethod): TypingTools = when (method) {
        TypeMethod.UNICODE_INPUT -> copy(unicodeInput = false)
        TypeMethod.XDOTOOL -> copy(xdotool = false)
        TypeMethod.X11_REMAP -> copy(x11Remap = false)
        TypeMethod.KEYSYM -> copy(keysyms = false)
        TypeMethod.PASTE -> copy(clipboard = false)
        TypeMethod.US_KEYS -> copy(robot = false)
    }
}

sealed interface TypeStep {
    /** A key from [Keys.named], such as "return" or "tab". */
    data class Key(val name: String) : TypeStep
    data class Text(val method: TypeMethod, val text: String) : TypeStep
}

/** How one typing step went. */
sealed interface Typed {
    data object Done : Typed
    /** Nothing went in, so all of it can go another way. */
    data object Nothing : Typed
    /** The first [count] UTF-16 units went in, then it failed: only the rest goes another way. */
    data class Partly(val count: Int) : Typed
    /** It stopped part way and how much went in isn't known, such as xdotool killed after a timeout. None of it is typed again. */
    data object Stopped : Typed
}

/**
 * Types text step by step, and moves to the next way when one fails, without typing anything twice. A way that took
 * nothing is dropped for good, except the clipboard, which can fail for a moment: it's skipped for the rest of
 * that text only. Not thread-safe.
 */
class TypingRun(tools: TypingTools) {
    var tools: TypingTools = tools
        private set

    /** Types [text]: [key] presses Enter or Tab, [typeWith] types a run of text. Returns false if anything failed. */
    fun type(text: String, key: (String) -> Unit, typeWith: (TypeStep.Text) -> Typed): Boolean {
        var local = tools
        var ok = true
        var steps = Typing.plan(text, local)
        while (steps.isNotEmpty()) {
            val step = steps.first()
            steps = steps.drop(1)
            if (step is TypeStep.Key) { key(step.name); continue }
            step as TypeStep.Text
            val rest = when (val result = typeWith(step)) {
                Typed.Done -> continue
                Typed.Stopped -> { ok = false; continue }
                Typed.Nothing -> step.text
                is Typed.Partly -> step.text.substring(result.count.coerceIn(0, step.text.length))
            }
            ok = false
            local = local.without(step.method)
            if (step.method != TypeMethod.PASTE) tools = tools.without(step.method)
            steps = Typing.plan(rest + steps.joinToString("") {
                when (it) {
                    is TypeStep.Text -> it.text
                    is TypeStep.Key -> if (it.name == "return") "\n" else "\t"
                }
            }, local)
        }
        return ok
    }
}

object Typing {
    /** How one character (a code point, so an emoji is one) is typed, or null when it can't be. */
    fun methodFor(codePoint: Int, tools: TypingTools): TypeMethod? {
        val usKey = tools.robot && codePoint < 0x80 && Keys.forChar(codePoint.toChar()) != null
        // Pasting needs Robot for Ctrl+V.
        val paste = tools.clipboard && tools.robot
        return when (tools.desktop) {
            Desktop.WINDOWS -> when {
                tools.unicodeInput -> TypeMethod.UNICODE_INPUT
                paste -> TypeMethod.PASTE
                usKey -> TypeMethod.US_KEYS
                else -> null
            }
            Desktop.X11 -> when {
                tools.xdotool -> TypeMethod.XDOTOOL
                tools.x11Remap -> TypeMethod.X11_REMAP
                paste -> TypeMethod.PASTE
                usKey -> TypeMethod.US_KEYS
                else -> null
            }
            // [TypingTools.robot] is the Wayland way in here: the portal or /dev/uinput press keys and Ctrl+V. GNOME
            // types a keysym only when the layout has it, so beyond ASCII the text is pasted when the clipboard works.
            Desktop.WAYLAND -> when {
                tools.keysyms && codePoint < 0x80 -> TypeMethod.KEYSYM
                paste -> TypeMethod.PASTE
                tools.keysyms -> TypeMethod.KEYSYM
                usKey -> TypeMethod.US_KEYS
                else -> null
            }
            // A Mac, only while developing: as before, plain characters are typed and the rest pasted.
            Desktop.OTHER -> when {
                usKey -> TypeMethod.US_KEYS
                paste -> TypeMethod.PASTE
                else -> null
            }
        }
    }

    /**
     * The steps for [text]: runs of characters typed the same way, with Enter and Tab as keys. Other control
     * characters, and characters nothing here can type, are left out. Lines that are all pasted are pasted in one
     * go with their line breaks, since one paste after another could race the app reading the clipboard.
     */
    fun plan(text: String, tools: TypingTools): List<TypeStep> {
        val steps = mutableListOf<TypeStep>()
        val run = StringBuilder()
        var runMethod: TypeMethod? = null
        fun flush() {
            runMethod?.let { if (run.isNotEmpty()) steps += TypeStep.Text(it, run.toString()) }
            run.setLength(0)
            runMethod = null
        }
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        var i = 0
        while (i < normalized.length) {
            val cp = normalized.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp == '\n'.code -> { flush(); steps += TypeStep.Key("return") }
                cp == '\t'.code -> { flush(); steps += TypeStep.Key("tab") }
                Character.isISOControl(cp) || Character.getType(cp) == Character.SURROGATE.toInt() -> {}
                else -> {
                    val method = methodFor(cp, tools) ?: continue
                    if (method != runMethod) flush()
                    runMethod = method
                    run.appendCodePoint(cp)
                }
            }
        }
        flush()
        return joinPastes(steps)
    }

    /** Paste, Enter or Tab, paste becomes one paste with the line break or tab in it. */
    private fun joinPastes(steps: List<TypeStep>): List<TypeStep> {
        val out = mutableListOf<TypeStep>()
        var i = 0
        while (i < steps.size) {
            val step = steps[i]
            if (step is TypeStep.Text && step.method == TypeMethod.PASTE) {
                val joined = StringBuilder(step.text)
                var j = i + 1
                while (true) {
                    var k = j
                    val gap = StringBuilder()
                    while (k < steps.size && (steps[k] as? TypeStep.Key)?.name in setOf("return", "tab")) {
                        gap.append(if ((steps[k] as TypeStep.Key).name == "return") '\n' else '\t'); k++
                    }
                    val next = steps.getOrNull(k)
                    if (next is TypeStep.Text && next.method == TypeMethod.PASTE) {
                        joined.append(gap).append(next.text); j = k + 1
                    } else break
                }
                out += TypeStep.Text(TypeMethod.PASTE, joined.toString())
                i = j
            } else {
                out += step; i++
            }
        }
        return out
    }

    fun desktop(isWindows: Boolean = Platform.isWindows, isMac: Boolean = Platform.isMac, env: (String) -> String? = System::getenv): Desktop = when {
        isWindows -> Desktop.WINDOWS
        isMac -> Desktop.OTHER
        env("XDG_SESSION_TYPE") == "wayland" || env("WAYLAND_DISPLAY") != null -> Desktop.WAYLAND
        else -> Desktop.X11
    }

    /** Whether a program is on the PATH, such as xdotool. */
    fun onPath(name: String, path: String? = System.getenv("PATH")): Boolean =
        path.orEmpty().split(File.pathSeparatorChar).filter { it.isNotEmpty() }.any { File(it, name).let { f -> f.isFile && f.canExecute() } }
}

/** The INPUT structures for SendInput, laid out by hand so the call needs no JNA structure classes. */
object WinInput {
    const val INPUT_KEYBOARD = 1
    const val KEYEVENTF_KEYUP = 0x0002
    const val KEYEVENTF_UNICODE = 0x0004

    /** sizeof(INPUT): the union is pointer-aligned and as big as MOUSEINPUT. */
    fun inputSize(pointerSize: Int) = if (pointerSize == 8) 40 else 28

    /**
     * A key down and a key up for every UTF-16 unit, with the unit as the scan code and no virtual key, so the
     * keyboard layout never comes into it. Windows puts the two halves of an emoji back together.
     */
    fun unicodeEvents(text: String, pointerSize: Int): ByteArray {
        val size = inputSize(pointerSize)
        val union = pointerSize // KEYBDINPUT starts after the DWORD type, padded to pointer alignment.
        val buffer = ByteBuffer.allocate(text.length * 2 * size).order(ByteOrder.LITTLE_ENDIAN)
        var at = 0
        for (unit in text) {
            for (up in listOf(false, true)) {
                buffer.putInt(at, INPUT_KEYBOARD)
                buffer.putShort(at + union, 0) // wVk
                buffer.putShort(at + union + 2, unit.code.toShort()) // wScan
                buffer.putInt(at + union + 4, KEYEVENTF_UNICODE or (if (up) KEYEVENTF_KEYUP else 0))
                at += size // time and dwExtraInfo stay 0.
            }
        }
        return buffer.array()
    }
}

/** The pure parts of typing through a spare X key code. */
object X11Keys {
    const val NO_SYMBOL = 0L

    /** The keysym for a character: Latin-1 keysyms are the code itself, everything else is 0x01000000 + the code. */
    fun keysymFor(codePoint: Int): Long =
        if (codePoint in 0x20..0x7e || codePoint in 0xa0..0xff) codePoint.toLong() else 0x01000000L or codePoint.toLong()

    /**
     * Key codes no key uses: every keysym NoSymbol in [mapping], which holds [perKey] keysyms for each key code from
     * [minKeycode] on. These can be given a character for a moment without changing any real key.
     */
    fun spareKeycodes(mapping: LongArray, perKey: Int, minKeycode: Int): List<Int> {
        if (perKey <= 0) return emptyList()
        return (0 until mapping.size / perKey)
            .filter { code -> (0 until perKey).all { mapping[code * perKey + it] == NO_SYMBOL } }
            .map { it + minKeycode }
    }
}
