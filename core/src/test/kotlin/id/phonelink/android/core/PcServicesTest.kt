package id.phonelink.android.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * What a Windows or Linux PC serves a phone: its sound, commands, and a terminal. The parts on their own with fakes,
 * then the real phone ConnectionManager against the real ComputerHub over TLS on this machine.
 */
class PcServicesTest {
    private val scopes = mutableListOf<CoroutineScope>()
    private val closers = mutableListOf<() -> Unit>()

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    @AfterTest
    fun close() {
        closers.forEach { runCatching { it() } }
        scopes.forEach { it.cancel() }
    }

    private fun waitUntil(message: String, seconds: Int = 10, check: () -> Boolean) {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError(message)
            Thread.sleep(20)
        }
    }

    private fun never(message: String, ms: Long = 1_000, check: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (check()) throw AssertionError(message)
            Thread.sleep(20)
        }
    }

    // Caps

    @Test
    fun theNewCapsAreOnlyAdvertisedByTheDesktopApp() {
        val pc = PhoneLinkProtocol.PC_CAPS
        for (cap in listOf("pc-sound-out", "pc-commands", "pc-terminal", "pc-shared-input")) {
            assertTrue(cap in pc, "The desktop app lists $cap")
            assertFalse(cap in PhoneLinkProtocol.PHONE_CAPS, "The phone doesn't list $cap: it doesn't serve it")
        }
        assertEquals(listOf(PhoneLinkProtocol.CAP_BINARY), PhoneLinkProtocol.CAPS)
        // What the phone shows for each computer.
        fun peer(platform: String, caps: List<String>) = Peer("x", "X", platform, "", true, true, null, caps = caps)
        assertTrue(peer("macos", emptyList()).canHear, "A Mac always sends its sound")
        assertTrue(peer("macos", emptyList()).canRunCommands)
        assertTrue(peer("macos", emptyList()).canOpenTerminal)
        assertFalse(peer("windows", PhoneLinkProtocol.CAPS).canHear, "An older PC build refuses, so it isn't offered")
        assertFalse(peer("linux", PhoneLinkProtocol.CAPS + PhoneLinkProtocol.PC_FEATURE_CAPS).canRunCommands)
        assertTrue(peer("windows", pc).canHear)
        assertTrue(peer("linux", pc).canRunCommands)
        assertTrue(peer("linux", listOf(PhoneLinkProtocol.CAP_PC_TERMINAL)).canOpenTerminal)
        assertFalse(peer("linux", listOf(PhoneLinkProtocol.CAP_PC_TERMINAL)).canRunCommands, "Each cap opens only its own")
        assertFalse(peer("android", pc).canHear, "Phones are never computers")
        // Messages a PC sends the phone: commands and the terminal only from a PC that listed them.
        assertFalse(PcRules.allows("windows", PhoneLinkProtocol.CAPS, MessageType.COMMANDS_LIST))
        assertFalse(PcRules.allows("linux", null, MessageType.TERMINAL_OUTPUT))
        assertTrue(PcRules.allows("windows", pc, MessageType.COMMAND_RESULT))
        assertTrue(PcRules.allows("linux", pc, MessageType.TERMINAL_OPENED))
        assertTrue(PcRules.allows("macos", null, MessageType.COMMANDS_LIST), "A Mac sends what it always did")
        assertTrue(PcRules.allows("macos", null, MessageType.TERMINAL_CLOSED))
        // Sound both ways stays open: the phone's own sound to an older PC still stops with audio.stop.
        assertTrue(PcRules.allows("windows", null, MessageType.AUDIO_STOP))
        assertTrue(PcRules.allows("windows", null, MessageType.AUDIO_STARTED))
    }

    @Test
    fun remoteMessagesMatchTheMac() {
        val event = RemoteEvent("key", keyCode = 0, keyDown = true, flags = 0x100000)
        val json = protocolJson.encodeToString(Envelope.serializer(), Envelope(MessageType.REMOTE_INPUT, remote = event))
        assertTrue("\"remote\":{" in json && "\"keyCode\":0" in json && "\"flags\":1048576" in json, json)
        val mac = """{"type":"remote.enter","remote":{"kind":"enter","edge":"left","at":0.25}}"""
        val back = protocolJson.decodeFromString(Envelope.serializer(), mac)
        assertEquals("left", back.remote?.edge)
        assertEquals(0.25, back.remote?.at)
        val info = protocolJson.decodeFromString(DeviceInfo.serializer(), """{"remoteControl":true}""")
        assertEquals(true, info.remoteControl)
    }

    @Test
    fun remoteNumbersAreClamped() {
        assertEquals(4000.0, RemoteLimits.sanitize(RemoteEvent("move", dx = 1e9, dy = -3.0))?.dx)
        assertEquals(-4000.0, RemoteLimits.sanitize(RemoteEvent("scroll", dy = -1e12))?.dy)
        assertNull(RemoteLimits.sanitize(RemoteEvent("move", dx = Double.NaN)))
        assertNull(RemoteLimits.sanitize(RemoteEvent("scroll", dy = Double.POSITIVE_INFINITY)))
        assertNull(RemoteLimits.sanitize(RemoteEvent("key", keyCode = 128)))
        assertNull(RemoteLimits.sanitize(RemoteEvent("key", keyCode = -1)))
        assertEquals(127, RemoteLimits.sanitize(RemoteEvent("flags", keyCode = 127))?.keyCode)
        assertNull(RemoteLimits.sanitize(RemoteEvent("down", button = 32)))
        assertEquals(0, RemoteLimits.sanitize(RemoteEvent("up"))?.button, "A missing button is the left one")
        assertNull(RemoteLimits.sanitize(RemoteEvent("teleport")))
        assertEquals(0.5, RemoteLimits.fraction(Double.NaN))
        assertEquals(1.0, RemoteLimits.fraction(7.0))
    }

    // Sound

    /** Stands in for WASAPI or parec: [feed] is what the PC plays. */
    private class FakeCapture(val refuse: String? = null) : PcSoundCapture {
        @Volatile var onPcm: ((ByteArray, Int) -> Unit)? = null
        @Volatile var onEnded: ((String) -> Unit)? = null
        @Volatile var started = 0
        @Volatile var stopped = 0

        override fun start(onPcm: (ByteArray, Int) -> Unit, onEnded: (String) -> Unit): String? {
            if (refuse != null) return refuse
            started++
            this.onPcm = onPcm
            this.onEnded = onEnded
            return null
        }

        override fun stop() {
            stopped++
            onPcm = null
        }

        fun feed(bytes: ByteArray) = onPcm?.invoke(bytes, bytes.size)
        fun fail(why: String) = onEnded?.invoke(why)
    }

    private fun loud(size: Int) = ByteArray(size) { if (it % 2 == 0) 0x00 else 0x20 }

    @Test
    fun soundStartsInChunksAndStops() {
        val capture = FakeCapture()
        val out = CopyOnWriteArrayList<Envelope>()
        val ended = CopyOnWriteArrayList<String>()
        val sender = PcSoundSender(capture, send = { out += it; true }, binary = { true }, onEnded = { ended += it })
        assertNull(sender.start())
        assertEquals(1, capture.started)
        // Uneven pieces still go out as whole 40 ms chunks.
        capture.feed(loud(5_000))
        capture.feed(loud(5_000))
        capture.feed(loud(5_360))
        waitUntil("Two chunks") { out.count { it.type == MessageType.AUDIO_FRAME } == 2 }
        assertEquals(MessageType.AUDIO_STARTED, out.first().type)
        assertEquals(48_000, out.first().audioRate)
        assertEquals(2, out.first().audioChannels)
        out.filter { it.type == MessageType.AUDIO_FRAME }.forEach { assertEquals(PcSoundSender.CHUNK_BYTES, it.raw?.size) }
        // Digital silence: five chunks still go (200 ms), the rest is held back toward a bin1 phone.
        repeat(9) { capture.feed(ByteArray(PcSoundSender.CHUNK_BYTES)); Thread.sleep(15) }
        waitUntil("Silence is held") { sender.held == 4L }
        assertEquals(7, out.count { it.type == MessageType.AUDIO_FRAME })
        sender.stop()
        assertEquals(1, capture.stopped)
        assertFalse(sender.isRunning)
        capture.feed(loud(PcSoundSender.CHUNK_BYTES))
        never("Nothing after stop") { out.count { it.type == MessageType.AUDIO_FRAME } != 7 }
        assertTrue(ended.isEmpty(), "A stop asked for isn't an ending by itself")
        sender.stop()
        assertEquals(1, capture.stopped, "Stopping twice stops once")
    }

    @Test
    fun soundToAnOlderPhoneKeepsSilenceAndEndsWhenTheCaptureFails() {
        val capture = FakeCapture()
        val out = CopyOnWriteArrayList<Envelope>()
        val ended = CopyOnWriteArrayList<String>()
        val sender = PcSoundSender(capture, send = { out += it; true }, binary = { false }, onEnded = { ended += it })
        assertNull(sender.start())
        repeat(8) { capture.feed(ByteArray(PcSoundSender.CHUNK_BYTES)); Thread.sleep(15) }
        waitUntil("Every chunk goes to a phone without bin1") { out.count { it.type == MessageType.AUDIO_FRAME } == 8 }
        capture.fail("The sound device went away")
        waitUntil("Ended") { ended.isNotEmpty() }
        assertEquals(listOf("The sound device went away"), ended)
        assertEquals(1, capture.stopped)
        assertEquals("No recording", PcSoundSender(FakeCapture("No recording"), { true }, { true }, {}).start())
    }

    @Test
    fun soundDropsTheOldestWhenThePhoneIsBehind() {
        val capture = FakeCapture()
        val gate = CountDownLatch(1)
        val out = CopyOnWriteArrayList<Envelope>()
        val sender = PcSoundSender(capture, send = { if (it.type == MessageType.AUDIO_FRAME) gate.await(); out += it; true }, binary = { false }, onEnded = {})
        assertNull(sender.start())
        repeat(20) { capture.feed(loud(PcSoundSender.CHUNK_BYTES)) }
        waitUntil("Dropped") { sender.dropped >= 20L - PcSoundSender.MAX_QUEUED - 1 }
        gate.countDown()
        waitUntil("The rest went") { out.count { it.type == MessageType.AUDIO_FRAME } >= PcSoundSender.MAX_QUEUED }
        sender.stop()
        assertTrue(out.count { it.type == MessageType.AUDIO_FRAME } <= PcSoundSender.MAX_QUEUED + 1)
    }

    // Commands

    private val sh = File("/bin/sh").exists()

    @Test
    fun commandsRunWithTheirOutputAndExitCode() {
        if (!sh) return
        val ok = CommandRunner.run("a", listOf("/bin/sh", "-c", "echo hello; echo oops >&2; exit 3"))
        assertEquals("a", ok.id)
        assertEquals(3, ok.exitCode)
        assertTrue("hello" in ok.output && "oops" in ok.output, ok.output)
        // Waiting for input gets the end at once.
        assertEquals(0, CommandRunner.run("b", listOf("/bin/sh", "-c", "cat")).exitCode)
        assertEquals(-1, CommandRunner.run("c", listOf("/no/such/shell", "-c", "true")).exitCode)
    }

    @Test
    fun commandOutputIsCappedToTheEnd() {
        if (!sh) return
        val result = CommandRunner.run("big", listOf("/bin/sh", "-c", "i=0; while [ \$i -lt 2000 ]; do echo line\$i; i=\$((i+1)); done"))
        assertEquals(0, result.exitCode)
        assertEquals(CommandRunner.MAX_OUTPUT_BYTES, result.output.toByteArray().size)
        assertTrue(result.output.trimEnd().endsWith("line1999"), "The end is kept")
        assertFalse("line0\n" in result.output)
    }

    @Test
    fun commandsStopAfterTheTimeout() {
        if (!sh) return
        val started = System.currentTimeMillis()
        // Something left running in the background doesn't keep it waiting either.
        val result = CommandRunner.run("slow", listOf("/bin/sh", "-c", "echo before; sleep 30 & sleep 30"), timeoutMs = 400)
        assertTrue(System.currentTimeMillis() - started < 5_000, "Stopped in time")
        assertEquals(CommandRunner.TIMED_OUT, result.exitCode)
        assertTrue(result.output.startsWith("before"), result.output)
        assertTrue(result.output.endsWith("Stopped after 0 seconds."), result.output)
    }

    @Test
    fun tailBufferKeepsTheLastBytes() {
        val tail = CommandRunner.TailBuffer(4)
        tail.write("abcdef".toByteArray(), 6)
        assertEquals("cdef", tail.text())
        tail.write("gh".toByteArray(), 2)
        assertEquals("efgh", tail.text())
    }

    // Terminal

    /** A shell that prints what it's given, and ends on hang up. */
    private class FakePty(val cols: Int, val rows: Int) : PtySession {
        private val pipeIn = PipedInputStream(64 * 1024)
        private val pipeOut = PipedOutputStream(pipeIn)
        val written = ByteArrayOutputStream()
        val sizes = CopyOnWriteArrayList<Pair<Int, Int>>()
        @Volatile var hungUp = 0
        @Volatile var killed = 0
        @Volatile var ignoresHangUp = false
        private val exited = CountDownLatch(1)
        @Volatile private var code = 0

        override val output: InputStream = pipeIn
        override fun write(bytes: ByteArray) {
            synchronized(written) { written.write(bytes) }
            print(bytes)
        }
        fun print(bytes: ByteArray) = runCatching { pipeOut.write(bytes); pipeOut.flush() }
        override fun resize(cols: Int, rows: Int) { sizes += cols to rows }
        override fun hangUp() { hungUp++; if (!ignoresHangUp) exit(129) }
        override fun kill() { killed++; exit(137) }
        override val isAlive: Boolean get() = exited.count > 0
        override fun waitFor(): Int { exited.await(); return code }
        fun exit(code: Int) {
            if (exited.count == 0L) return
            this.code = code
            runCatching { pipeOut.close() }
            exited.countDown()
        }
        fun typed() = synchronized(written) { written.toString() }
    }

    private class TerminalHarness(maxPerPhone: Int = 4, killAfterMs: Long = 2_000, failing: Boolean = false) {
        val ptys = CopyOnWriteArrayList<FakePty>()
        val out = CopyOnWriteArrayList<Pair<String, Envelope>>()
        val opened = CopyOnWriteArrayList<String>()
        val terminals = PcTerminals(
            factory = { PtyFactory { cols, rows -> if (failing) error("no pty") else FakePty(cols, rows).also { ptys += it } } },
            send = { id, envelope -> out += id to envelope; true },
            post = { id, envelope -> out += id to envelope; true },
            onOpened = { opened += it },
            maxPerPhone = maxPerPhone,
            killAfterMs = killAfterMs,
        )

        fun open(phone: String, id: String, allowed: Boolean = true, cols: Int = 80, rows: Int = 24) =
            terminals.handle(phone, MessageType.TERMINAL_OPEN, TerminalMessage(id, cols = cols, rows = rows), allowed)

        fun of(type: String, id: String) = out.filter { it.second.type == type && it.second.terminal?.id == id }
        fun output(id: String) = of(MessageType.TERMINAL_OUTPUT, id).joinToString("") { String(java.util.Base64.getDecoder().decode(it.second.terminal!!.data)) }
    }

    private fun b64(text: String) = java.util.Base64.getEncoder().encodeToString(text.toByteArray())

    @Test
    fun aTerminalOpensRelaysAndCloses() {
        val h = TerminalHarness()
        h.open("phone", "t1", cols = 3, rows = 1000)
        assertEquals(1, h.of(MessageType.TERMINAL_OPENED, "t1").size)
        assertEquals(listOf("phone"), h.opened, "The PC shows each terminal opened")
        val pty = h.ptys.single()
        assertEquals(10 to 300, pty.cols to pty.rows, "Sizes are kept sane")
        assertEquals(mapOf("phone" to 1), h.terminals.open.value)
        h.terminals.handle("phone", MessageType.TERMINAL_INPUT, TerminalMessage("t1", data = b64("ls\r")), true)
        waitUntil("Typed") { pty.typed() == "ls\r" }
        waitUntil("Printed back") { h.output("t1") == "ls\r" }
        // Another phone can't type into it.
        h.terminals.handle("other", MessageType.TERMINAL_INPUT, TerminalMessage("t1", data = b64("rm")), true)
        h.terminals.handle("phone", MessageType.TERMINAL_RESIZE, TerminalMessage("t1", cols = 100, rows = 40), true)
        assertEquals(listOf(100 to 40), pty.sizes)
        h.terminals.handle("phone", MessageType.TERMINAL_CLOSE, TerminalMessage("t1"), true)
        waitUntil("Closed") { h.of(MessageType.TERMINAL_CLOSED, "t1").isNotEmpty() }
        assertEquals(129, h.of(MessageType.TERMINAL_CLOSED, "t1").single().second.terminal?.exitCode)
        assertEquals(1, pty.hungUp)
        assertEquals("ls\r", pty.typed())
        assertEquals(emptyMap(), h.terminals.open.value)
    }

    @Test
    fun aTerminalNeedsTheSwitchAndHasALimit() {
        val h = TerminalHarness(maxPerPhone = 2)
        h.open("phone", "off", allowed = false)
        assertTrue(h.of(MessageType.TERMINAL_CLOSED, "off").single().second.terminal?.error.orEmpty().contains("turned off"))
        assertTrue(h.ptys.isEmpty(), "Nothing starts while it's off")
        h.open("phone", "a")
        h.open("phone", "a")
        assertEquals(1, h.ptys.size, "The same ID opens once")
        h.open("phone", "b")
        h.open("phone", "c")
        assertTrue(h.of(MessageType.TERMINAL_CLOSED, "c").single().second.terminal?.error.orEmpty().startsWith("Close a terminal first"))
        h.open("other", "d")
        assertEquals(3, h.ptys.size, "The limit is per phone")
        assertEquals(mapOf("phone" to 2, "other" to 1), h.terminals.open.value)
        val failing = TerminalHarness(failing = true)
        failing.open("phone", "x")
        assertEquals("The shell couldn't start.", failing.of(MessageType.TERMINAL_CLOSED, "x").single().second.terminal?.error)
    }

    @Test
    fun endingTellsThePhoneOnceAndKillsAShellThatStays() {
        val h = TerminalHarness(killAfterMs = 100)
        h.open("phone", "a")
        h.open("phone", "b")
        h.open("other", "c")
        h.ptys[0].ignoresHangUp = true
        h.terminals.end("phone")
        assertEquals(PcTerminals.ENDED_HERE, h.of(MessageType.TERMINAL_CLOSED, "a").single().second.terminal?.error)
        assertEquals(PcTerminals.ENDED_HERE, h.of(MessageType.TERMINAL_CLOSED, "b").single().second.terminal?.error)
        waitUntil("Killed after the wait") { h.ptys[0].killed == 1 }
        never("Told only once", 400) { h.of(MessageType.TERMINAL_CLOSED, "a").size > 1 || h.of(MessageType.TERMINAL_CLOSED, "b").size > 1 }
        assertEquals(mapOf("other" to 1), h.terminals.open.value)
        assertTrue(h.ptys[2].isAlive, "Another phone's terminal stays")
        // Gone (disconnected): it ends without a word, since nobody is there to hear it.
        val before = h.out.size
        h.terminals.end("other", null)
        assertFalse(h.ptys[2].isAlive)
        never("Nothing sent to a phone that's gone", 300) { h.out.size != before }
        // A shell that ends by itself reports its code.
        h.open("phone", "e")
        h.ptys.last().exit(0)
        waitUntil("Exit code") { h.of(MessageType.TERMINAL_CLOSED, "e").isNotEmpty() }
        assertEquals(0, h.of(MessageType.TERMINAL_CLOSED, "e").single().second.terminal?.exitCode)
    }

    // Over the network: the real phone and the real hub

    private fun sink(folder: File) = FileSinkFactory { name, _ ->
        val out = ByteArrayOutputStream()
        object : FileSink {
            override fun write(bytes: ByteArray) = out.write(bytes)
            override fun commit(): String = File(folder, name).also { it.writeBytes(out.toByteArray()) }.path
            override fun abort() {}
        }
    }

    private inner class Pc(caps: List<String>?, val capture: FakeCapture? = FakeCapture()) {
        val dir: File = Files.createTempDirectory("dysk-pcs").toFile()
        @Volatile var soundOn = true
        @Volatile var commandsOn = false
        @Volatile var terminalOn = false
        val ran = CopyOnWriteArrayList<String>()
        val opened = CopyOnWriteArrayList<String>()
        val ptys = CopyOnWriteArrayList<FakePty>()
        val list = listOf(SavedCommand("c1", "Say hi", "echo hi"))
        val hub = ComputerHub(
            dir, scope(),
            localHello = { paired -> Hello("Test PC", "windows", "Test", PhoneLinkProtocol.VERSION, paired, "1.0.0", caps) },
            events = object : HubEvents {
                override fun clipboard(text: String, from: String) = true
                override fun ring(on: Boolean, from: String) {}
                override fun soundRefusal(from: String) = if (soundOn) null else "Turn it on"
                override fun soundCapture(): PcSoundCapture? = capture
                override fun commands(from: String) = if (commandsOn) list else null
                override fun runCommand(command: SavedCommand): CommandResult { ran += command.command; return CommandResult("ignored", 0, "hi") }
                override fun terminalAllowed(from: String) = terminalOn
                override val pty: PtyFactory get() = PtyFactory { c, r -> FakePty(c, r).also { ptys += it } }
                override fun terminalOpened(from: String) { opened += from }
            },
            fileSinks = sink(dir),
            port = 0,
        ).also { it.start(); closers += it::shutdown }
    }

    private inner class Phone {
        val dir: File = Files.createTempDirectory("dysk-pcs-phone").toFile()
        val audio = CopyOnWriteArrayList<Envelope>()
        val manager = ConnectionManager(
            dir, scope(),
            localHello = { paired -> Hello("Pixel", "android", "Pixel 9", PhoneLinkProtocol.VERSION, paired, caps = PhoneLinkProtocol.PHONE_CAPS) },
            localInfo = { DeviceInfo(batteryPercent = 64) },
            isUiVisible = { true },
            actions = object : DeviceActions {
                override fun setClipboard(text: String) {}
                override fun media(action: String) {}
            },
            fileSinks = sink(dir),
            phoneData = object : PhoneData {
                override val storageRoot = dir
                override fun isGranted(feature: PhoneFeature) = true
                override fun activeNotifications() = emptyList<NotificationItem>()
                override fun dismissNotification(key: String) {}
                override fun readSms(limit: Int, afterId: Long) = emptyList<SmsItem>()
                override fun sendSms(address: String, body: String, done: (String?) -> Unit) = done(null)
                override fun readContacts() = emptyList<ContactItem>()
                override fun requestScreen(peerId: String) {}
                override fun syncScreen(peerId: String) {}
                override fun stopScreen(peerId: String) {}
                override fun control(event: ControlEvent) {}
            },
        ).also {
            closers += it::shutdown
            it.audioSink = { _, envelope -> audio += envelope }
        }
        val terminal = CopyOnWriteArrayList<Envelope>()
        init {
            scope().launch { manager.terminalEvents.collect { (_, envelope) -> terminal += envelope } }
        }
    }

    private fun paired(pc: Pc, phone: Phone): String {
        phone.manager.addManual("127.0.0.1", pc.hub.boundPort)
        waitUntil("The phone didn't connect") { pc.hub.phones.value.any { it.isConnected } }
        phone.manager.tryAutoPair()
        waitUntil("No code") { pc.hub.pairing.value != null }
        phone.manager.submitCode(pc.hub.pairing.value!!.code)
        waitUntil("Pairing didn't finish") {
            pc.hub.phones.value.any { it.isPaired && it.isConnected } && phone.manager.peers.value.any { it.id == pc.hub.deviceId && it.isPaired && it.isConnected }
        }
        return phone.manager.localDeviceId
    }

    @Test
    fun aPhoneHearsThePcAndStopsIt() {
        val pc = Pc(PhoneLinkProtocol.PC_CAPS)
        val phone = Phone()
        val phoneId = paired(pc, phone)
        val peer = phone.manager.peers.value.first { it.id == pc.hub.deviceId }
        assertTrue(peer.canHear && peer.canRunCommands && peer.canOpenTerminal)
        pc.soundOn = false
        phone.manager.post(pc.hub.deviceId, Envelope(MessageType.AUDIO_LISTEN))
        waitUntil("Refused while it's off") { phone.audio.any { it.type == MessageType.AUDIO_STOPPED && it.error == "Turn it on" } }
        assertEquals(0, pc.capture!!.started)
        pc.soundOn = true
        phone.audio.clear()
        phone.manager.post(pc.hub.deviceId, Envelope(MessageType.AUDIO_LISTEN))
        waitUntil("Started") { phone.audio.any { it.type == MessageType.AUDIO_STARTED } && pc.hub.soundTo.value == phoneId }
        pc.capture.feed(loud(PcSoundSender.CHUNK_BYTES * 2))
        waitUntil("Chunks arrive") { phone.audio.count { it.type == MessageType.AUDIO_FRAME } == 2 }
        assertEquals(PcSoundSender.CHUNK_BYTES, phone.audio.last { it.type == MessageType.AUDIO_FRAME }.payload()?.size)
        // The phone stops listening.
        phone.manager.post(pc.hub.deviceId, Envelope(MessageType.AUDIO_STOP))
        waitUntil("Stopped") { pc.capture.stopped == 1 && pc.hub.soundTo.value == null }
        // Again, and the PC stops it this time; then the phone goes and nothing is left recording.
        phone.audio.clear()
        phone.manager.post(pc.hub.deviceId, Envelope(MessageType.AUDIO_LISTEN))
        waitUntil("Started again") { pc.hub.soundTo.value == phoneId }
        pc.hub.stopSoundOut()
        waitUntil("Told") { phone.audio.any { it.type == MessageType.AUDIO_STOPPED } }
        assertEquals(2, pc.capture.stopped)
        phone.manager.post(pc.hub.deviceId, Envelope(MessageType.AUDIO_LISTEN))
        waitUntil("Started a third time") { pc.hub.soundTo.value == phoneId }
        phone.manager.shutdown()
        waitUntil("Stopped on disconnect") { pc.capture.stopped == 3 && pc.hub.soundTo.value == null }
    }

    @Test
    fun commandsFollowThePhonesSwitch() {
        val pc = Pc(PhoneLinkProtocol.PC_CAPS)
        val phone = Phone()
        paired(pc, phone)
        val pcId = pc.hub.deviceId
        waitUntil("An empty list while it's off") { phone.manager.commands.value.containsKey(pcId) }
        assertEquals(emptyList(), phone.manager.commands.value[pcId])
        phone.manager.runCommand(pcId, "c1")
        waitUntil("Refused") { phone.manager.commandResult.value != null }
        assertEquals(-1, phone.manager.commandResult.value?.exitCode)
        assertTrue(pc.ran.isEmpty())
        pc.commandsOn = true
        pc.hub.commandsChanged()
        waitUntil("The list") { phone.manager.commands.value[pcId] == listOf(CommandItem("c1", "Say hi")) }
        phone.manager.runCommand(pcId, "c1")
        waitUntil("Ran") { phone.manager.commandResult.value?.output == "hi" }
        assertEquals("c1", phone.manager.commandResult.value?.id)
        assertEquals(listOf("echo hi"), pc.ran)
        phone.manager.runCommand(pcId, "rm -rf /")
        waitUntil("Only listed IDs") { phone.manager.commandResult.value?.output == "Command not found" }
        assertEquals(1, pc.ran.size)
    }

    @Test
    fun aTerminalFromThePhoneNeedsItsSwitchAndEndsWithIt() {
        val pc = Pc(PhoneLinkProtocol.PC_CAPS)
        val phone = Phone()
        val phoneId = paired(pc, phone)
        val pcId = pc.hub.deviceId
        phone.manager.sendTerminal(pcId, MessageType.TERMINAL_OPEN, TerminalMessage("s1", cols = 80, rows = 24))
        waitUntil("Refused") { phone.terminal.any { it.type == MessageType.TERMINAL_CLOSED && it.terminal?.error != null } }
        pc.terminalOn = true
        phone.manager.sendTerminal(pcId, MessageType.TERMINAL_OPEN, TerminalMessage("s2", cols = 80, rows = 24))
        waitUntil("Opened") { phone.terminal.any { it.type == MessageType.TERMINAL_OPENED && it.terminal?.id == "s2" } }
        assertEquals(listOf(phoneId), pc.opened)
        phone.manager.sendTerminal(pcId, MessageType.TERMINAL_INPUT, TerminalMessage("s2", data = b64("dir\r")))
        waitUntil("Echoed") { phone.terminal.any { it.type == MessageType.TERMINAL_OUTPUT && it.terminal?.data == b64("dir\r") } }
        // The switch goes off on the PC: the app ends that phone's terminals.
        pc.terminalOn = false
        pc.hub.endTerminals(phoneId)
        waitUntil("Ended") { phone.terminal.any { it.type == MessageType.TERMINAL_CLOSED && it.terminal?.id == "s2" && it.terminal?.error == PcTerminals.ENDED_HERE } }
        assertFalse(pc.ptys.single().isAlive)
        // And on disconnect.
        pc.terminalOn = true
        phone.manager.sendTerminal(pcId, MessageType.TERMINAL_OPEN, TerminalMessage("s3", cols = 80, rows = 24))
        waitUntil("Opened again") { pc.hub.terminals.open.value[phoneId] == 1 }
        phone.manager.shutdown()
        waitUntil("Ended on disconnect") { pc.hub.terminals.open.value.isEmpty() && !pc.ptys.last().isAlive }
    }

    /**
     * Against the debug Mac app with PHONELINK_TEST_MAC_SYNC and PHONELINK_TEST_REMOTE_DEMO: the Mac plays a short
     * crossing (enter, a move, a key, exit) into this PC. Run by hand with DYSKLINK_IT_MAC_REMOTE_PORT and a fixed
     * DYSKLINK_IT_PC_DIR, once to pair and once more for the crossing, since the Mac plays it 3 s after it starts.
     */
    @Test
    fun aMacsPointerComesIntoThePc() {
        val port = System.getenv("DYSKLINK_IT_MAC_REMOTE_PORT")?.toIntOrNull() ?: return
        val dir = File(System.getenv("DYSKLINK_IT_PC_DIR") ?: return).apply { mkdirs() }
        val remote = CopyOnWriteArrayList<String>()
        val hub = ComputerHub(
            dir, scope(),
            localHello = { paired -> Hello("Test PC", "windows", "Test", PhoneLinkProtocol.VERSION, paired, "1.0.0", PhoneLinkProtocol.PC_CAPS) },
            events = object : HubEvents {
                override fun clipboard(text: String, from: String) = true
                override fun ring(on: Boolean, from: String) {}
                override fun remoteAllowed(from: String) = true
                override fun remote(type: String, event: RemoteEvent?, from: String) { remote += "$type ${event?.kind ?: ""} ${event?.edge ?: event?.keyCode ?: event?.dx ?: ""}".trim() }
            },
            fileSinks = sink(dir),
            port = 0,
            localInfo = { DeviceInfo(remoteControl = true) },
        ).also { it.start(); closers += it::shutdown }
        hub.setComputersEnabled(true)
        // Dialed until the Mac is up, so the Mac can be started after this.
        waitUntil("The Mac didn't connect", 60) {
            hub.phones.value.any { it.isConnected && it.platform == "macos" }.also { if (!it) hub.dialComputer("127.0.0.1", port) }
        }
        val mac = hub.phones.value.first { it.platform == "macos" }
        if (!mac.isPaired) {
            hub.requestPairing(mac.id)
            waitUntil("The PC isn't typing a code") { hub.pairing.value?.typing == true }
            hub.submitCode("123456")
            waitUntil("Pairing with the Mac didn't finish", 15) { hub.phones.value.any { it.id == mac.id && it.isPaired } }
            println("REMOTE_IT_PAIRED")
        }
        if (System.getenv("DYSKLINK_IT_EXPECT_REMOTE") == "1") {
            waitUntil("The Mac's crossing didn't arrive: $remote", 15) { remote.any { it.startsWith(MessageType.REMOTE_EXIT) } }
            println("REMOTE_IT $remote")
            assertEquals("${MessageType.REMOTE_ENTER} enter left", remote.first())
            assertTrue(remote.any { it == "${MessageType.REMOTE_INPUT} move 5.0" }, "$remote")
            assertTrue(remote.any { it == "${MessageType.REMOTE_INPUT} key 0" }, "$remote")
        }
    }

    @Test
    fun anOlderPcBuildIsNotOfferedAndItsCommandsAreDropped() {
        // Only bin1, as builds before these caps: the phone shows nothing new and drops commands from it.
        val pc = Pc(PhoneLinkProtocol.CAPS)
        pc.commandsOn = true
        val phone = Phone()
        paired(pc, phone)
        val peer = phone.manager.peers.value.first { it.id == pc.hub.deviceId }
        assertFalse(peer.canHear || peer.canRunCommands || peer.canOpenTerminal)
        pc.hub.commandsChanged()
        never("Dropped by the phone") { phone.manager.commands.value.containsKey(pc.hub.deviceId) }
    }
}
