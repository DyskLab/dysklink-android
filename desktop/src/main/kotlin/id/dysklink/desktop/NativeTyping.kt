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

    /** [Typed.Nothing] when Windows took none of it, such as when the app in front runs as administrator. */
    fun type(text: String): Typed {
        val lib = user32 ?: return Typed.Nothing
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
                // Only what didn't go in goes another way, so nothing is typed twice. A key down counts as typed.
                var typed = start + (sent.coerceAtLeast(0) + 1) / 2
                if (typed in 1 until text.length && Character.isHighSurrogate(text[typed - 1])) typed++
                return if (typed == 0) Typed.Nothing else Typed.Partly(typed)
            }
            start = end
        }
        return Typed.Done
    }
}

/** Types characters on X11 by `xdotool type`, which handles any layout and any character. */
class XdotoolTyper {
    val isAvailable: Boolean = Typing.onPath("xdotool")

    /**
     * Killed after a timeout it has typed an unknown part, so that's [Typed.Stopped]: typing the whole text again
     * another way would type that part twice.
     */
    fun type(text: String): Typed = runCatching {
        val process = ProcessBuilder("xdotool", "type", "--delay", "4", "--", text)
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        // Waited for, so what's typed next comes after it.
        if (!process.waitFor(timeoutSeconds(text), TimeUnit.SECONDS)) {
            process.destroy()
            CoreLog.w("DyskLink", "xdotool timed out typing ${text.length} characters")
            Typed.Stopped
        } else if (process.exitValue() == 0) Typed.Done else Typed.Nothing
    }.onFailure { CoreLog.w("DyskLink", "xdotool failed", it) }.getOrDefault(Typed.Nothing)

    companion object {
        fun timeoutSeconds(text: String): Long = 10 + text.length / 50L
    }
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
        fun XPending(display: Pointer): Int
        fun XNextEvent(display: Pointer, event: Pointer): Int
        fun XCloseDisplay(display: Pointer): Int
    }

    @Suppress("FunctionName")
    private interface XTest : Library {
        fun XTestFakeKeyEvent(display: Pointer, keycode: Int, press: Int, delay: NativeLong): Int
    }

    private class Libraries(val x: X11, val xtest: XTest)

    private class Session(val x: X11, val xtest: XTest, val display: Pointer) {
        /** An XEvent: a union padded to 24 longs. */
        private val event = Memory(24L * NativeLong.SIZE)

        /**
         * Reads and drops queued events. Every mapping change sends this client a MappingNotify, and nothing else
         * reads them, so they'd pile up on the connection.
         */
        fun sync() {
            x.XSync(display, 0)
            while (x.XPending(display) > 0) x.XNextEvent(display, event)
        }
    }

    /** The libraries, loaded once, and a display that opens; each typing run opens its own connection. */
    private val libraries: Libraries? by lazy {
        runCatching {
            val x = load<X11>("X11", "libX11.so.6")
            val xtest = load<XTest>("Xtst", "libXtst.so.6")
            val display = x.XOpenDisplay(null) ?: error("No X display")
            x.XCloseDisplay(display)
            Libraries(x, xtest)
        }.onFailure { CoreLog.w("DyskLink", "No X11 typing, typing falls back", it) }.getOrNull()
    }

    private inline fun <reified T : Library> load(vararg names: String): T {
        var last: Throwable? = null
        for (name in names) runCatching { return Native.load(name, T::class.java) }.onFailure { last = it }
        throw last ?: IllegalStateException("No library")
    }

    val isAvailable: Boolean get() = libraries != null

    /** A connection of its own for each run, closed after, so nothing builds up on it between runs. */
    @Synchronized
    fun type(text: String): Typed {
        val libs = libraries ?: return Typed.Nothing
        val display = runCatching { libs.x.XOpenDisplay(null) }.getOrNull() ?: return Typed.Nothing
        val s = Session(libs.x, libs.xtest, display)
        // UTF-16 units typed so far, so a failure part way only sends the rest another way.
        var typed = 0
        // Spare keys holding a character now, emptied again whatever happens.
        val assigned = LinkedHashSet<Int>()
        var perKey = 0
        try {
            val min = IntByReference(); val max = IntByReference()
            s.x.XDisplayKeycodes(s.display, min, max)
            val count = max.value - min.value + 1
            val perKeyRef = IntByReference()
            val raw = s.x.XGetKeyboardMapping(s.display, min.value, count, perKeyRef) ?: return Typed.Nothing
            perKey = perKeyRef.value
            val mapping = LongArray(count * perKey) { raw.getNativeLong(it.toLong() * NativeLong.SIZE).toLong() }
            s.x.XFree(raw)
            val spare = X11Keys.spareKeycodes(mapping, perKey, min.value)
            if (spare.isEmpty()) return Typed.Nothing

            val codePoints = text.codePoints().toArray()
            // As many characters at a time as there are spare keys; each one gets the character in every column,
            // so it comes out the same whatever layout group or Shift state is active.
            for (chunk in codePoints.toList().chunked(spare.size)) {
                val keysyms = chunk.distinct()
                val keyFor = keysyms.withIndex().associate { (i, cp) -> cp to spare[i] }
                keyFor.forEach { (cp, keycode) ->
                    assigned += keycode
                    setKey(s, keycode, perKey, X11Keys.keysymFor(cp))
                }
                s.sync()
                // Apps read the new mapping when told it changed; give them a moment before the keys arrive.
                Thread.sleep(40)
                for (cp in chunk) {
                    val keycode = keyFor.getValue(cp)
                    s.xtest.XTestFakeKeyEvent(s.display, keycode, 1, NativeLong(0))
                    s.xtest.XTestFakeKeyEvent(s.display, keycode, 0, NativeLong(0))
                    s.sync()
                    typed += Character.charCount(cp)
                    Thread.sleep(3)
                }
                // And a moment for the keys to be read before the spare keys are emptied again.
                Thread.sleep(60)
                keyFor.values.forEach { setKey(s, it, perKey, X11Keys.NO_SYMBOL) }
                assigned.clear()
                s.sync()
            }
            return Typed.Done
        } catch (e: Exception) {
            CoreLog.w("DyskLink", "X11 typing failed after $typed of ${text.length}", e)
            return if (typed == 0) Typed.Nothing else Typed.Partly(typed)
        } finally {
            runCatching {
                assigned.forEach { setKey(s, it, perKey, X11Keys.NO_SYMBOL) }
                s.sync()
            }.onFailure { CoreLog.w("DyskLink", "Couldn't empty the spare keys", it) }
            runCatching { s.x.XCloseDisplay(s.display) }
        }
    }

    private fun setKey(s: Session, keycode: Int, perKey: Int, keysym: Long) {
        val memory = Memory(perKey.toLong() * NativeLong.SIZE)
        for (i in 0 until perKey) memory.setNativeLong(i.toLong() * NativeLong.SIZE, NativeLong(keysym))
        s.x.XChangeKeyboardMapping(s.display, keycode, perKey, memory, 1)
    }
}
