package id.dysklink.desktop

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import id.phonelink.android.core.CoreLog
import java.util.concurrent.TimeUnit

/** Types characters on Windows through SendInput, whatever the keyboard layout. */
class WindowsUnicodeTyper {
    @Suppress("FunctionName")
    private interface User32 : Library {
        fun SendInput(count: Int, inputs: Pointer, size: Int): Int
    }

    private val user32: User32? by lazy {
        runCatching { Native.load("user32", User32::class.java) }
            .onFailure { CoreLog.w("DyskLink", "No SendInput, typing falls back", it) }.getOrNull()
    }

    val isAvailable: Boolean get() = user32 != null

    /** False when Windows took none of it, such as when the app in front runs as administrator. */
    fun type(text: String): Boolean {
        val lib = user32 ?: return false
        val size = WinInput.inputSize(Native.POINTER_SIZE)
        // In chunks, so a long text doesn't need one huge buffer; a chunk never splits an emoji.
        var start = 0
        while (start < text.length) {
            var end = minOf(text.length, start + 500)
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            val bytes = WinInput.unicodeEvents(text.substring(start, end), Native.POINTER_SIZE)
            val memory = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
            val count = bytes.size / size
            val sent = lib.SendInput(count, memory, size)
            if (sent != count) {
                CoreLog.w("DyskLink", "SendInput took $sent of $count key events")
                // Only when nothing at all went in does it go another way; otherwise part would be typed twice.
                return sent > 0 || start > 0
            }
            start = end
        }
        return true
    }
}

/** Types characters on X11 by `xdotool type`, which handles any layout and any character. */
class XdotoolTyper {
    val isAvailable: Boolean = Typing.onPath("xdotool")

    fun type(text: String): Boolean = runCatching {
        val process = ProcessBuilder("xdotool", "type", "--delay", "4", "--", text)
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        // Waited for, so what's typed next comes after it.
        if (!process.waitFor(10 + text.length / 50L, TimeUnit.SECONDS)) { process.destroy(); false }
        else process.exitValue() == 0
    }.onFailure { CoreLog.w("DyskLink", "xdotool failed", it) }.getOrDefault(false)
}

/**
 * Types characters on X11 without xdotool, the way xdotool does: spare key codes are given the characters, pressed
 * through XTest, and given back. libXtst is there wherever java.awt.Robot works, as Robot uses it too.
 */
class X11RemapTyper {
    @Suppress("FunctionName")
    private interface X11 : Library {
        fun XOpenDisplay(name: String?): Pointer?
        fun XDisplayKeycodes(display: Pointer, min: IntByReference, max: IntByReference): Int
        fun XGetKeyboardMapping(display: Pointer, first: Int, count: Int, perKey: IntByReference): Pointer?
        fun XChangeKeyboardMapping(display: Pointer, first: Int, perKey: Int, keysyms: Pointer, count: Int): Int
        fun XFree(data: Pointer): Int
        fun XSync(display: Pointer, discard: Int): Int
    }

    @Suppress("FunctionName")
    private interface XTest : Library {
        fun XTestFakeKeyEvent(display: Pointer, keycode: Int, press: Int, delay: NativeLong): Int
    }

    private class Session(val x: X11, val xtest: XTest, val display: Pointer)

    private val session: Session? by lazy {
        runCatching {
            val x = load<X11>("X11", "libX11.so.6")
            val xtest = load<XTest>("Xtst", "libXtst.so.6")
            val display = x.XOpenDisplay(null) ?: error("No X display")
            Session(x, xtest, display)
        }.onFailure { CoreLog.w("DyskLink", "No X11 typing, typing falls back", it) }.getOrNull()
    }

    private inline fun <reified T : Library> load(vararg names: String): T {
        var last: Throwable? = null
        for (name in names) runCatching { return Native.load(name, T::class.java) }.onFailure { last = it }
        throw last ?: IllegalStateException("No library")
    }

    val isAvailable: Boolean get() = session != null

    @Synchronized
    fun type(text: String): Boolean {
        val s = session ?: return false
        return runCatching {
            val min = IntByReference(); val max = IntByReference()
            s.x.XDisplayKeycodes(s.display, min, max)
            val count = max.value - min.value + 1
            val perKeyRef = IntByReference()
            val raw = s.x.XGetKeyboardMapping(s.display, min.value, count, perKeyRef) ?: return false
            val perKey = perKeyRef.value
            val mapping = LongArray(count * perKey) { raw.getNativeLong(it.toLong() * NativeLong.SIZE).toLong() }
            s.x.XFree(raw)
            val spare = X11Keys.spareKeycodes(mapping, perKey, min.value)
            if (spare.isEmpty()) return false

            val codePoints = text.codePoints().toArray()
            // As many characters at a time as there are spare keys; each one gets the character in every column,
            // so it comes out the same whatever layout group or Shift state is active.
            for (chunk in codePoints.toList().chunked(spare.size)) {
                val keysyms = chunk.distinct()
                val keyFor = keysyms.withIndex().associate { (i, cp) -> cp to spare[i] }
                keyFor.forEach { (cp, keycode) -> setKey(s, keycode, perKey, X11Keys.keysymFor(cp)) }
                s.x.XSync(s.display, 0)
                // Apps read the new mapping when told it changed; give them a moment before the keys arrive.
                Thread.sleep(40)
                for (cp in chunk) {
                    val keycode = keyFor.getValue(cp)
                    s.xtest.XTestFakeKeyEvent(s.display, keycode, 1, NativeLong(0))
                    s.xtest.XTestFakeKeyEvent(s.display, keycode, 0, NativeLong(0))
                    s.x.XSync(s.display, 0)
                    Thread.sleep(3)
                }
                // And a moment for the keys to be read before the spare keys are emptied again.
                Thread.sleep(60)
                keyFor.values.forEach { setKey(s, it, perKey, X11Keys.NO_SYMBOL) }
                s.x.XSync(s.display, 0)
            }
            true
        }.onFailure { CoreLog.w("DyskLink", "X11 typing failed", it) }.getOrDefault(false)
    }

    private fun setKey(s: Session, keycode: Int, perKey: Int, keysym: Long) {
        val memory = Memory(perKey.toLong() * NativeLong.SIZE)
        for (i in 0 until perKey) memory.setNativeLong(i.toLong() * NativeLong.SIZE, NativeLong(keysym))
        s.x.XChangeKeyboardMapping(s.display, keycode, perKey, memory, 1)
    }
}
