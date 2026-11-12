package id.dysklink.desktop

import id.phonelink.android.core.CoreLog
import id.phonelink.android.core.InputEvent
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Robot
import java.awt.event.InputEvent as AwtInput
import java.awt.event.KeyEvent
import java.io.OutputStreamWriter
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * The touchpad, keyboard, and shortcuts from the phone. Pointer, special keys, and shortcuts go through
 * java.awt.Robot by virtual key; text goes the way [Typing] picks, so it's right on any keyboard layout.
 * [pasteText] pastes text and puts the clipboard back, and is false when it couldn't.
 */
class PcInput(private val pasteText: (String) -> Boolean) {
    private val desktop = Typing.desktop()

    /** Wayland doesn't let apps move the pointer or type into other apps, so Robot only reaches X11 windows there. */
    val isWayland = desktop == Desktop.WAYLAND

    private val robot: Robot? by lazy {
        if (GraphicsEnvironment.isHeadless()) null
        else runCatching { Robot().apply { autoDelay = 0 } }.onFailure { CoreLog.w("DyskLink", "No Robot", it) }.getOrNull()
    }

    val isAvailable: Boolean get() = !isWayland && robot != null

    private val windowsTyper by lazy { WindowsUnicodeTyper() }
    private val xdotool by lazy { XdotoolTyper() }
    private val x11Typer by lazy { X11RemapTyper() }

    /** What text is typed with; a way that fails is dropped, and the next one is used from then on. */
    private val tools: TypingTools by lazy {
        TypingTools(
            desktop = desktop,
            unicodeInput = desktop == Desktop.WINDOWS && windowsTyper.isAvailable,
            xdotool = desktop == Desktop.X11 && xdotool.isAvailable,
            // Also with xdotool, as the way to go when xdotool fails.
            x11Remap = desktop == Desktop.X11 && x11Typer.isAvailable,
            clipboard = true,
            robot = robot != null,
        ).also {
            CoreLog.i("DyskLink", "Typing with $it")
            if (desktop == Desktop.WAYLAND) CoreLog.w("DyskLink", "Wayland: text from the phone can't be typed into other apps")
        }
    }
    /** Drops a way that fails, so the next one is used from then on. Guarded by [type]'s lock. */
    private val run by lazy { TypingRun(tools) }

    /** Shown under the switch when text can only be pasted here, which some apps (terminals) don't take with Ctrl+V. */
    val typingNote: String? get() = when {
        desktop != Desktop.X11 || robot == null -> null
        tools.xdotool || tools.x11Remap -> null
        else -> "Text from the phone is pasted here. Install xdotool to have it typed instead."
    }

    private var scrollRest = 0f
    private var moveRestX = 0f
    private var moveRestY = 0f

    /** Presses Ctrl+V by virtual key, so it's Paste on any layout, for text just put on the clipboard. */
    fun pasteNow() {
        val robot = robot ?: return
        tap(robot, KeyEvent.VK_V, listOf(KeyEvent.VK_CONTROL))
    }

    fun handle(event: InputEvent) {
        val robot = robot ?: return
        runCatching {
            when (event.kind) {
                "move" -> {
                    // Slow moves come in fractions of a pixel, so the rest is kept for the next one, as with scrolling.
                    moveRestX += event.dx?.takeIf { it.isFinite() } ?: 0f
                    moveRestY += event.dy?.takeIf { it.isFinite() } ?: 0f
                    val stepX = moveRestX.roundToInt()
                    val stepY = moveRestY.roundToInt()
                    moveRestX -= stepX
                    moveRestY -= stepY
                    if (stepX != 0 || stepY != 0) MouseInfo.getPointerInfo()?.location?.let { robot.mouseMove(it.x + stepX, it.y + stepY) }
                }
                "click" -> {
                    val mask = if (event.button == "right") AwtInput.BUTTON3_DOWN_MASK else AwtInput.BUTTON1_DOWN_MASK
                    robot.mousePress(mask); robot.mouseRelease(mask)
                }
                "scroll" -> {
                    // The phone sends pixels with up as positive, as macOS does; a wheel notch is about 40 of them.
                    scrollRest -= event.dy ?: 0f
                    val notches = (scrollRest / 40f).toInt()
                    if (notches != 0) { robot.mouseWheel(notches); scrollRest -= notches * 40f }
                }
                "text" -> type(robot, event.text.orEmpty())
                "key" -> Keys.named[event.key]?.let { tap(robot, it, emptyList()) }
                "shortcut" -> Keys.parse(event.key.orEmpty())?.let { (key, mods) -> tap(robot, key, mods) }
            }
        }.onFailure { CoreLog.w("DyskLink", "Input failed", it) }
    }

    private fun tap(robot: Robot, key: Int, modifiers: List<Int>) {
        modifiers.forEach(robot::keyPress)
        robot.keyPress(key); robot.keyRelease(key)
        modifiers.asReversed().forEach(robot::keyRelease)
    }

    @Synchronized
    private fun type(robot: Robot, text: String) {
        val before = run.tools
        val ok = run.type(text, key = { name -> Keys.named[name]?.let { tap(robot, it, emptyList()) } }) { typeWith(robot, it) }
        if (!ok) CoreLog.w("DyskLink", "Typing partly failed; now ${run.tools}" + if (run.tools != before) " (was $before)" else "")
    }

    private fun typeWith(robot: Robot, step: TypeStep.Text): Typed = when (step.method) {
        TypeMethod.UNICODE_INPUT -> windowsTyper.type(step.text)
        TypeMethod.XDOTOOL -> xdotool.type(step.text)
        TypeMethod.X11_REMAP -> x11Typer.type(step.text)
        TypeMethod.PASTE -> if (pasteText(step.text)) Typed.Done else Typed.Nothing
        TypeMethod.US_KEYS -> {
            step.text.forEach { c ->
                Keys.forChar(c)?.let { (key, shift) -> tap(robot, key, if (shift) listOf(KeyEvent.VK_SHIFT) else emptyList()) }
            }
            Typed.Done
        }
    }
}

/** Key names and shortcuts the phone sends. "cmd" means Ctrl here, so the phone's Copy and Paste work. */
object Keys {
    val named: Map<String, Int> = buildMap {
        put("return", KeyEvent.VK_ENTER); put("enter", KeyEvent.VK_ENTER)
        put("delete", KeyEvent.VK_BACK_SPACE); put("forwarddelete", KeyEvent.VK_DELETE)
        put("tab", KeyEvent.VK_TAB); put("escape", KeyEvent.VK_ESCAPE); put("esc", KeyEvent.VK_ESCAPE)
        put("space", KeyEvent.VK_SPACE)
        put("left", KeyEvent.VK_LEFT); put("right", KeyEvent.VK_RIGHT); put("up", KeyEvent.VK_UP); put("down", KeyEvent.VK_DOWN)
        put("home", KeyEvent.VK_HOME); put("end", KeyEvent.VK_END); put("pageup", KeyEvent.VK_PAGE_UP); put("pagedown", KeyEvent.VK_PAGE_DOWN)
        for (n in 1..12) put("f$n", KeyEvent.VK_F1 + n - 1)
        for (c in 'a'..'z') put("$c", KeyEvent.VK_A + (c - 'a'))
        for (c in '0'..'9') put("$c", KeyEvent.VK_0 + (c - '0'))
        put("-", KeyEvent.VK_MINUS); put("=", KeyEvent.VK_EQUALS); put("[", KeyEvent.VK_OPEN_BRACKET); put("]", KeyEvent.VK_CLOSE_BRACKET)
        put("\\", KeyEvent.VK_BACK_SLASH); put(";", KeyEvent.VK_SEMICOLON); put("'", KeyEvent.VK_QUOTE); put(",", KeyEvent.VK_COMMA)
        put(".", KeyEvent.VK_PERIOD); put("/", KeyEvent.VK_SLASH); put("`", KeyEvent.VK_BACK_QUOTE)
    }

    private val modifiers: Map<String, Int> = mapOf(
        "cmd" to KeyEvent.VK_CONTROL, "command" to KeyEvent.VK_CONTROL, "ctrl" to KeyEvent.VK_CONTROL, "control" to KeyEvent.VK_CONTROL,
        "shift" to KeyEvent.VK_SHIFT, "alt" to KeyEvent.VK_ALT, "opt" to KeyEvent.VK_ALT, "option" to KeyEvent.VK_ALT,
        "win" to KeyEvent.VK_WINDOWS, "super" to KeyEvent.VK_WINDOWS, "meta" to KeyEvent.VK_WINDOWS,
    )

    /** "cmd+shift+t" → T with Ctrl and Shift held, or null for anything not on the lists. */
    fun parse(text: String): Pair<Int, List<Int>>? {
        val parts = text.lowercase().trim().split('+').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val key = named[parts.last()] ?: return null
        val mods = parts.dropLast(1).map { modifiers[it] ?: return null }.distinct()
        return key to mods
    }

    private const val SHIFTED = "~!@#\$%^&*()_+{}|:\"<>?"
    private const val UNSHIFTED = "`1234567890-=[]\\;',./"

    /** The US key for a character and whether it needs Shift, or null when it has none. */
    fun forChar(c: Char): Pair<Int, Boolean>? = when {
        c == ' ' -> KeyEvent.VK_SPACE to false
        c == '\n' -> KeyEvent.VK_ENTER to false
        c in 'a'..'z' || c in '0'..'9' -> named.getValue("$c") to false
        c in 'A'..'Z' -> named.getValue("${c.lowercaseChar()}") to true
        c in SHIFTED -> named[UNSHIFTED[SHIFTED.indexOf(c)].toString()]?.let { it to true }
        else -> named[c.toString()]?.let { it to false }
    }
}

/** Play, skip, and volume from the phone's media remote. */
class MediaKeys {
    private var shell: Process? = null
    private var shellIn: OutputStreamWriter? = null

    fun press(action: String) {
        runCatching { if (Platform.isWindows) windows(action) else linux(action) }
            .onFailure { CoreLog.w("DyskLink", "Media key $action failed", it) }
    }

    /** Windows has no media keys in Java, but WScript.Shell can send their virtual key codes. One PowerShell stays open. */
    private fun windows(action: String) {
        val code = mapOf("play_pause" to 179, "next" to 176, "previous" to 177, "volume_up" to 175, "volume_down" to 174, "mute" to 173)[action] ?: return
        synchronized(this) {
            if (shell?.isAlive != true) {
                shell = ProcessBuilder("powershell", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", "-")
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                shellIn = OutputStreamWriter(shell!!.outputStream)
                shellIn!!.write("\$k = New-Object -ComObject WScript.Shell\n")
            }
            shellIn!!.write("\$k.SendKeys([char]$code)\n")
            shellIn!!.flush()
        }
    }

    /** MPRIS players through playerctl, and PulseAudio or PipeWire volume through pactl. */
    private fun linux(action: String) {
        val command = when (action) {
            "play_pause" -> listOf("playerctl", "play-pause")
            "next" -> listOf("playerctl", "next")
            "previous" -> listOf("playerctl", "previous")
            "volume_up" -> listOf("pactl", "set-sink-volume", "@DEFAULT_SINK@", "+5%")
            "volume_down" -> listOf("pactl", "set-sink-volume", "@DEFAULT_SINK@", "-5%")
            "mute" -> listOf("pactl", "set-sink-mute", "@DEFAULT_SINK@", "toggle")
            else -> return
        }
        val ok = runCatching { ProcessBuilder(command).start().waitFor() == 0 }.getOrDefault(false)
        if (!ok) {
            // Without playerctl or pactl, the desktop's own media keys usually still work through xdotool.
            val key = mapOf("play_pause" to "XF86AudioPlay", "next" to "XF86AudioNext", "previous" to "XF86AudioPrev",
                "volume_up" to "XF86AudioRaiseVolume", "volume_down" to "XF86AudioLowerVolume", "mute" to "XF86AudioMute")[action]
            runCatching { ProcessBuilder("xdotool", "key", key).start() }
        }
    }

    fun stop() { runCatching { shell?.destroy() } }
}

/** The phone's sound on this PC's speakers. Chunks wait in a short queue, and old ones are dropped so it stays close to live. */
class PhoneSpeaker {
    private val queue = ArrayBlockingQueue<ByteArray>(6)
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var player: Thread? = null

    fun start(rate: Int, channels: Int) {
        stop()
        val format = AudioFormat(rate.toFloat(), 16, channels, true, false)
        val opened = runCatching {
            AudioSystem.getSourceDataLine(format).apply { open(format, rate * channels * 2 / 5); start() }
        }.onFailure { CoreLog.w("DyskLink", "Can't play phone sound", it) }.getOrNull() ?: return
        line = opened
        player = thread(isDaemon = true, name = "dysk-speaker") {
            val me = Thread.currentThread()
            while (player === me) {
                val chunk = runCatching { queue.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
                opened.write(chunk, 0, chunk.size)
            }
            runCatching { opened.stop(); opened.close() }
        }
    }

    fun play(pcm: ByteArray) {
        if (line == null) return
        while (!queue.offer(pcm)) queue.poll()
    }

    fun stop() {
        player = null
        queue.clear()
        line?.let { runCatching { it.flush() } }
        line = null
    }
}
