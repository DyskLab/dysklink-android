package id.dysklink.desktop

import id.phonelink.android.core.Envelope
import id.phonelink.android.core.MessageType
import id.phonelink.android.core.PcSoundSender
import id.phonelink.android.core.RemoteEvent
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The desktop side of what a PC serves: Mac keys and edges, sound conversion and recording, commands, the pty. */
class PcServicesDesktopTest {
    private fun waitUntil(message: String, seconds: Int = 10, check: () -> Boolean) {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError(message)
            Thread.sleep(20)
        }
    }

    // Mac keys

    @Test
    fun macKeyCodesMapToPcKeys() {
        assertEquals(KeyEvent.VK_A, MacKeys.vk(0))
        assertEquals(KeyEvent.VK_S, MacKeys.vk(1))
        assertEquals(KeyEvent.VK_Z, MacKeys.vk(6))
        assertEquals(KeyEvent.VK_Q, MacKeys.vk(12))
        assertEquals(KeyEvent.VK_M, MacKeys.vk(46))
        assertEquals(KeyEvent.VK_1, MacKeys.vk(18))
        assertEquals(KeyEvent.VK_5, MacKeys.vk(23))
        assertEquals(KeyEvent.VK_6, MacKeys.vk(22))
        assertEquals(KeyEvent.VK_0, MacKeys.vk(29))
        assertEquals(KeyEvent.VK_ENTER, MacKeys.vk(36))
        assertEquals(KeyEvent.VK_BACK_SPACE, MacKeys.vk(51), "Mac Delete is Backspace")
        assertEquals(KeyEvent.VK_DELETE, MacKeys.vk(117), "Forward Delete is Delete")
        assertEquals(KeyEvent.VK_ESCAPE, MacKeys.vk(53))
        assertEquals(KeyEvent.VK_LEFT, MacKeys.vk(123))
        assertEquals(KeyEvent.VK_UP, MacKeys.vk(126))
        assertEquals(KeyEvent.VK_F1, MacKeys.vk(122))
        assertEquals(KeyEvent.VK_F5, MacKeys.vk(96))
        assertEquals(KeyEvent.VK_F12, MacKeys.vk(111))
        assertEquals(KeyEvent.VK_F13, MacKeys.vk(105))
        assertEquals(KeyEvent.VK_NUMPAD7, MacKeys.vk(89))
        assertEquals(KeyEvent.VK_BACK_QUOTE, MacKeys.vk(50))
        for (modifier in MacKeys.MODIFIERS) assertNull(MacKeys.vk(modifier), "Modifiers come from the flags")
        assertNull(MacKeys.vk(72), "Volume keys have no PC key")
        // Every Mac key code maps to a distinct key, except keypad Enter and = sharing Return and =.
        val mapped = (0..127).mapNotNull { MacKeys.vk(it) }
        assertTrue(mapped.size >= 90, "Most keys are there: ${mapped.size}")
    }

    @Test
    fun macFlagsBecomePcModifiers() {
        val cmdShift = MacFlags.COMMAND or MacFlags.SHIFT
        assertEquals(setOf(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT), MacFlags.keys(cmdShift, commandAsCtrl = true))
        assertEquals(setOf(KeyEvent.VK_WINDOWS, KeyEvent.VK_SHIFT), MacFlags.keys(cmdShift, commandAsCtrl = false))
        assertEquals(setOf(KeyEvent.VK_ALT), MacFlags.keys(MacFlags.OPTION, true))
        assertEquals(setOf(KeyEvent.VK_CONTROL), MacFlags.keys(MacFlags.CONTROL or MacFlags.COMMAND, true), "Both make one Ctrl")
        assertEquals(emptySet(), MacFlags.keys(MacFlags.CAPS_LOCK or 0x100L, true), "Caps Lock and device bits aren't held keys")
    }

    // The PC used from a Mac

    private class Recorder(val screens: List<Rectangle> = listOf(Rectangle(0, 0, 1920, 1080))) : InputInjector {
        var at = Point(500, 500)
        val events = CopyOnWriteArrayList<String>()
        override val displays get() = screens
        override val pointer get() = Point(at)
        override fun moveTo(x: Int, y: Int) { at = Point(x, y); events += "move $x,$y" }
        override fun button(button: Int, down: Boolean) { events += "button $button ${if (down) "down" else "up"}" }
        override fun wheel(notches: Int) { events += "wheel $notches" }
        override fun hwheel(notches: Int) { events += "hwheel $notches" }
        override fun key(vk: Int, down: Boolean) { events += "${name(vk)} ${if (down) "down" else "up"}" }

        /** KeyEvent.getKeyText differs by OS once AWT is loaded. */
        private fun name(vk: Int) = when (vk) {
            KeyEvent.VK_CONTROL -> "Ctrl"
            KeyEvent.VK_SHIFT -> "Shift"
            KeyEvent.VK_ALT -> "Alt"
            KeyEvent.VK_WINDOWS -> "Windows"
            KeyEvent.VK_CAPS_LOCK -> "Caps Lock"
            else -> vk.toChar().toString()
        }
    }

    private class Target(screens: List<Rectangle> = listOf(Rectangle(0, 0, 1920, 1080)), commandAsCtrl: Boolean = true) {
        val injector = Recorder(screens)
        val sent = CopyOnWriteArrayList<Pair<String, Envelope>>()
        val changes = CopyOnWriteArrayList<String?>()
        val target = SharedInputTarget(injector, send = { id, e -> sent += id to e }, commandAsCtrl = { commandAsCtrl }, onChange = { changes += it })
        fun input(event: RemoteEvent, from: String = "mac") = target.handle(event, from)
    }

    @Test
    fun theMacsPointerComesInAtTheEdgeAndMoves() {
        val t = Target()
        // The Mac's pointer left its right edge: it appears on this PC's left edge, a quarter of the way down.
        t.target.enter("mac", ScreenEdge.LEFT, 0.25)
        assertEquals(Point(2, 270), t.injector.at)
        assertEquals(listOf<String?>("mac"), t.changes)
        t.input(RemoteEvent("move", dx = 10.4, dy = -0.3))
        assertEquals(Point(12, 270), t.injector.at)
        t.input(RemoteEvent("move", dx = 0.3, dy = 0.4))
        assertEquals(Point(13, 270), t.injector.at, "The fractions add up")
        // Moves are clamped to the screen, and broken ones dropped.
        t.input(RemoteEvent("move", dx = 1e9, dy = 0.0))
        assertEquals(Point(1919, 270), t.injector.at)
        t.input(RemoteEvent("move", dx = Double.NaN, dy = 0.0))
        assertEquals(Point(1919, 270), t.injector.at)
        // Another computer that isn't in control is ignored.
        t.input(RemoteEvent("move", dx = -100.0), from = "other")
        assertEquals(Point(1919, 270), t.injector.at)
        t.input(RemoteEvent("scroll", dy = 50.0))
        assertEquals("wheel -2", t.injector.events.last(), "Up on the Mac scrolls up here")
        t.input(RemoteEvent("scroll", dx = -30.0))
        assertEquals("hwheel 1", t.injector.events.last())
    }

    @Test
    fun keysAndModifiersAreHeldAndLetGo() {
        val t = Target()
        t.target.enter("mac", ScreenEdge.RIGHT, 0.5)
        t.injector.events.clear()
        // Command down, C, Command up: Ctrl+C here.
        t.input(RemoteEvent("flags", keyCode = MacKeys.COMMAND, flags = MacFlags.COMMAND))
        t.input(RemoteEvent("key", keyCode = 8, keyDown = true, flags = MacFlags.COMMAND))
        t.input(RemoteEvent("key", keyCode = 8, keyDown = false, flags = MacFlags.COMMAND))
        t.input(RemoteEvent("flags", keyCode = MacKeys.COMMAND, flags = 0))
        assertEquals(listOf("Ctrl down", "C down", "C up", "Ctrl up"), t.injector.events)
        // Caps Lock taps once per change.
        t.injector.events.clear()
        t.input(RemoteEvent("flags", keyCode = MacKeys.CAPS_LOCK, flags = MacFlags.CAPS_LOCK))
        assertEquals(listOf("Caps Lock down", "Caps Lock up"), t.injector.events)
        // A key code out of range is dropped; a key up that was never down is ignored.
        t.injector.events.clear()
        t.input(RemoteEvent("key", keyCode = 200, keyDown = true))
        t.input(RemoteEvent("key", keyCode = 0, keyDown = false, flags = 0))
        assertTrue(t.injector.events.isEmpty())
        // Held when control ends: everything goes up.
        t.input(RemoteEvent("flags", keyCode = MacKeys.SHIFT, flags = MacFlags.SHIFT))
        t.input(RemoteEvent("key", keyCode = 0, keyDown = true, flags = MacFlags.SHIFT))
        t.input(RemoteEvent("down", button = 0))
        t.input(RemoteEvent("down", button = 40))
        t.injector.events.clear()
        t.target.gone("mac")
        assertEquals(setOf("button 0 up", "A up", "Shift up"), t.injector.events.toSet())
        assertNull(t.target.controller)
        assertEquals(null, t.changes.last())
        assertTrue(t.sent.isEmpty(), "Gone: nobody to tell")
        // Input after that does nothing.
        t.injector.events.clear()
        t.input(RemoteEvent("key", keyCode = 0, keyDown = true))
        assertTrue(t.injector.events.isEmpty())
    }

    @Test
    fun commandCanBeTheWindowsKey() {
        val t = Target(commandAsCtrl = false)
        t.target.enter("mac", ScreenEdge.LEFT, 0.5)
        t.injector.events.clear()
        t.input(RemoteEvent("flags", keyCode = MacKeys.COMMAND, flags = MacFlags.COMMAND))
        assertEquals(listOf("Windows down"), t.injector.events)
    }

    @Test
    fun pushingBackPastTheEdgeGoesHome() {
        val t = Target(listOf(Rectangle(0, 0, 1920, 1080), Rectangle(1920, 0, 1280, 1024)))
        // Entered on the left: the edge facing the Mac.
        t.target.enter("mac", ScreenEdge.LEFT, 0.5)
        t.input(RemoteEvent("flags", keyCode = MacKeys.SHIFT, flags = MacFlags.SHIFT))
        t.input(RemoteEvent("move", dx = -50.0, dy = 0.0))
        assertEquals(0, t.injector.at.x)
        assertTrue(t.sent.isEmpty(), "Reaching the edge isn't enough")
        t.input(RemoteEvent("move", dx = -5.0, dy = 0.0))
        assertTrue(t.sent.isEmpty(), "One push isn't either")
        t.input(RemoteEvent("move", dx = -5.0, dy = 0.0))
        val (to, leave) = t.sent.single()
        assertEquals("mac", to)
        assertEquals(MessageType.REMOTE_LEAVE, leave.type)
        assertEquals("left", leave.remote?.edge)
        assertEquals(0.5, leave.remote!!.at!!, 0.01)
        assertNull(t.target.controller)
        assertTrue("Shift up" in t.injector.events, "Held keys go up")
        // The right edge between two screens isn't an outer edge.
        val two = Target(listOf(Rectangle(0, 0, 1920, 1080), Rectangle(1920, 0, 1280, 1024)))
        two.target.enter("mac", ScreenEdge.RIGHT, 0.1)
        assertEquals(Point(1920 + 1280 - 3, 102), two.injector.at, "Comes in on the outermost screen")
        assertNull(EdgeMath.hit(Point(1919, 500), ScreenEdge.RIGHT, two.injector.displays))
        assertNotNull(EdgeMath.hit(Point(3199, 500), ScreenEdge.RIGHT, two.injector.displays))
    }

    @Test
    fun releaseCanTellTheMac() {
        val t = Target()
        t.target.enter("mac", ScreenEdge.TOP, 0.5)
        t.target.release(notify = true)
        assertEquals(MessageType.REMOTE_EXIT, t.sent.single().second.type)
        // A second Mac taking over tells the first.
        t.sent.clear()
        t.target.enter("mac", ScreenEdge.TOP, 0.5)
        t.target.enter("mac2", ScreenEdge.BOTTOM, 0.5)
        assertEquals("mac" to MessageType.REMOTE_EXIT, t.sent.single().let { it.first to it.second.type })
        assertEquals("mac2", t.target.controller)
    }

    // Sound

    @Test
    fun pcmAt48kStereoPassesThrough() {
        val out = java.io.ByteArrayOutputStream()
        val converter = PcmConverter(48_000, 2) { bytes, length -> out.write(bytes, 0, length) }
        converter.feed(floatArrayOf(0.5f, -0.5f, 1.5f, -2f, 0f, 0.25f), 3)
        val pcm = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(12, out.size())
        assertEquals(listOf<Short>(16384, -16383, 32767, -32768, 0, 8192), (0 until 6).map { pcm.getShort(it * 2) })
    }

    @Test
    fun pcmIsResampledAndMadeStereo() {
        var bytes = 0
        val mono = java.io.ByteArrayOutputStream()
        // 44.1 kHz mono, 5.1 at 96 kHz: always 48 kHz stereo out, about the same length of time.
        val fromMono = PcmConverter(44_100, 1) { b, n -> bytes += n; mono.write(b, 0, n) }
        repeat(10) { fromMono.feed(FloatArray(441) { 0.5f }, 441) }
        val frames = bytes / 4
        assertTrue(frames in 4790..4810, "0.1 s is 4800 frames: $frames")
        val pcm = ByteBuffer.wrap(mono.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(pcm.getShort(400), pcm.getShort(402), "Mono goes to both sides")
        var surround = 0
        val fromSurround = PcmConverter(96_000, 6) { _, n -> surround += n }
        repeat(4) { fromSurround.feed(FloatArray(960 * 6), 960) }
        assertEquals(1920, surround / 4)
        // A ramp stays a ramp across buffers: no jump where one buffer meets the next.
        val ramp = java.io.ByteArrayOutputStream()
        val r = PcmConverter(24_000, 1) { b, n -> ramp.write(b, 0, n) }
        r.feed(FloatArray(100) { it / 200f }, 100)
        r.feed(FloatArray(100) { (100 + it) / 200f }, 100)
        val left = ByteBuffer.wrap(ramp.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).let { b -> (0 until ramp.size() / 4).map { b.getShort(it * 4).toInt() } }
        val steps = left.zipWithNext { a, b -> b - a }
        assertTrue(steps.all { it in 0..100 }, "Smooth: ${steps.filter { it !in 0..100 }}")
    }

    @Test
    fun samplesAreReadInEveryFormat() {
        val b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        b.putFloat(0, 0.5f)
        assertEquals(0.5f, PcmConverter.toFloats(b, 1, 32, true)[0])
        b.putShort(0, (-16384).toShort())
        assertEquals(-0.5f, PcmConverter.toFloats(b, 1, 16, false)[0])
        b.put(0, 0); b.put(1, 0); b.put(2, 0x40)
        assertEquals(0.5f, PcmConverter.toFloats(b, 1, 24, false)[0])
    }

    @Test
    fun wasapiFormatsAndGuidsAreRead() {
        // WAVEFORMATEXTENSIBLE: 48 kHz, 2 channels, 32-bit float, as Windows usually mixes.
        val ext = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN)
        ext.putShort(0, 0xFFFE.toShort()); ext.putShort(2, 2); ext.putInt(4, 48_000); ext.putInt(8, 384_000)
        ext.putShort(12, 8); ext.putShort(14, 32); ext.putShort(16, 22); ext.putShort(18, 32); ext.putInt(20, 3); ext.putInt(24, 3)
        val mix = WasapiLoopback.MixFormat.read(ext.array())
        assertEquals(WasapiLoopback.MixFormat(0xFFFE, 2, 48_000, 8, 32, isFloat = true), mix)
        assertTrue(mix.supported)
        val pcm = WasapiLoopback.MixFormat.read(ext.array().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 1).putShort(14, 16).putShort(12, 4) })
        assertFalse(pcm.isFloat)
        assertTrue(pcm.supported)
        assertFalse(WasapiLoopback.MixFormat(1, 2, 48_000, 2, 8, false).supported, "8-bit isn't taken")
        val guid = WasapiLoopback.guidBytes("BCDE0395-E52F-467C-8E3D-C4579291692E")
        assertEquals(listOf(0x95, 0x03, 0xDE, 0xBC, 0x2F, 0xE5, 0x7C, 0x46, 0x8E, 0x3D, 0xC4, 0x57, 0x92, 0x91, 0x69, 0x2E), guid.map { it.toInt() and 0xff })
    }

    @Test
    fun soundRecordedByAToolStartsAndStops() {
        if (Platform.isWindows) return
        val got = java.util.concurrent.atomic.AtomicLong()
        val ended = CopyOnWriteArrayList<String>()
        // Stands in for parec: endless sound on its output.
        val capture = ProcessCapture(listOf(listOf("/bin/sh", "-c", "exec cat /dev/zero")))
        assertNull(capture.start({ _, n -> got.addAndGet(n.toLong()) }) { ended += it })
        waitUntil("Sound arrives") { got.get() > PcSoundSender.CHUNK_BYTES * 4 }
        capture.stop()
        val after = got.get()
        Thread.sleep(300)
        assertTrue(got.get() - after <= PcSoundSender.CHUNK_BYTES * 2, "Nothing much after stop")
        assertTrue(ended.isEmpty(), "A stop asked for isn't an ending")
        // A tool that's missing, then one that fails at once: the next is tried, and the last reason told.
        val failing = ProcessCapture(listOf(listOf("/no/such/parec"), listOf("/bin/sh", "-c", "echo 'No such entity' >&2; exit 1")))
        val why = failing.start({ _, _ -> }) {}
        assertTrue(why.orEmpty().contains("No such entity"), why)
        // A tool that ends by itself ends the sound, with why.
        val short = ProcessCapture(listOf(listOf("/bin/sh", "-c", "head -c 20000 /dev/zero; sleep 0.5")))
        val shortEnded = CopyOnWriteArrayList<String>()
        assertNull(short.start({ _, _ -> }) { shortEnded += it })
        waitUntil("Ended by itself") { shortEnded.isNotEmpty() }
        assertTrue(shortEnded.single().startsWith("Recording the PC's sound stopped"))
    }

    // Commands and the terminal

    @Test
    fun commandsAreKeptAndRunThroughTheShell() {
        val file = Files.createTempFile("dysk-commands", ".json").toFile().apply { delete() }
        val store = CommandStore(file)
        assertTrue(store.add(" Disk ", " df -h "))
        assertFalse(store.add("", "ls"))
        assertFalse(store.add("Nothing", "  "))
        assertEquals("Disk", store.commands.value.single().name)
        assertEquals("df -h", store.commands.value.single().command)
        assertEquals(store.commands.value, CommandStore(file).commands.value, "Kept across starts")
        store.remove(store.commands.value.single().id)
        assertTrue(CommandStore(file).commands.value.isEmpty())
        assertEquals(listOf("/bin/sh", "-c", "echo hi"), PcShell.commandArgs("echo hi", windows = false))
        assertEquals("/c", PcShell.commandArgs("dir", windows = true)[1])
        assertTrue(PcShell.terminalShell(windows = false).last() == "-l")
        if (!Platform.isWindows) {
            val result = PcShell.run(id.phonelink.android.core.SavedCommand("x", "Hi", "echo hello from \$HOME"))
            assertEquals(0, result.exitCode)
            assertTrue(result.output.startsWith("hello from /"), result.output)
        }
    }

    @Test
    fun theTerminalRunsOnARealPty() {
        // pty4j's own native part for this OS, as the packaged app uses it.
        val session = PcShell.pty.open(80, 24)
        val output = StringBuffer()
        val reader = Thread {
            val buffer = ByteArray(4096)
            runCatching { while (true) { val n = session.output.read(buffer); if (n < 0) break; output.append(String(buffer, 0, n)) } }
        }.apply { isDaemon = true; start() }
        session.resize(100, 30)
        session.write("echo \$((6*7)) cols=\$(tput cols 2>/dev/null || echo ?)\r".toByteArray())
        waitUntil("The shell answered: $output") { "42 cols=" in output.toString() }
        session.write("exit\r".toByteArray())
        assertEquals(0, session.waitFor())
        reader.join(2_000)
        assertFalse(session.isAlive)
        // Hanging up ends a shell that's still running.
        val second = PcShell.pty.open(80, 24)
        Thread.sleep(300)
        second.hangUp()
        val done = Thread { second.waitFor() }.apply { start() }
        done.join(3_000)
        if (second.isAlive) second.kill()
        done.join(2_000)
        assertFalse(second.isAlive)
    }

    @Test
    fun theSideWheelInputIsLaidOutForSendInput() {
        val bytes = WinInput.mouseWheel(-240, horizontal = true, pointerSize = 8)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(40, bytes.size)
        assertEquals(0, b.getInt(0))
        assertEquals(-240, b.getInt(16))
        assertEquals(0x1000, b.getInt(20))
        assertEquals(28, WinInput.mouseWheel(120, horizontal = false, pointerSize = 4).size)
    }
}
