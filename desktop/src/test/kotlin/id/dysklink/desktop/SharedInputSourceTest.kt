package id.dysklink.desktop

import id.phonelink.android.core.Envelope
import id.phonelink.android.core.MessageType
import id.phonelink.android.core.PhoneLinkProtocol
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** This PC's mouse and keyboard on a Mac, with a fake hook: nothing real is hooked or grabbed here. */
class SharedInputSourceTest {
    /** Records what the source asks of the hooks; [deliver] plays what a hook would see, through the gate as the hook does. */
    private class FakeBackend(override var displays: List<Rectangle> = listOf(Rectangle(0, 0, 1920, 1080))) : CaptureBackend {
        var gate: InputGate? = null
        var sink: ((HookEvent) -> Unit)? = null
        override var installed = false
        var installs = 0
        var uninstalls = 0
        var parked: Point? = null
        val released = mutableListOf<Point?>()
        var captureWorks = true
        var installWorks = true
        override var probeHooks: Set<String> = setOf("mouse", "keyboard")
        /** Hooks that answer probes; one Windows took away doesn't. */
        var answering: Set<String> = setOf("mouse", "keyboard")
        var probes = 0

        override fun install(gate: InputGate, sink: (HookEvent) -> Unit): Boolean {
            installs++
            if (!installWorks) return false
            this.gate = gate
            this.sink = sink
            installed = true
            answering = probeHooks
            return true
        }
        override fun uninstall() { uninstalls++; installed = false; gate?.crossed = false }
        override fun capture(park: Point): Boolean { if (captureWorks) parked = park; return captureWorks }
        override fun release(to: Point?) { parked = null; released += to }
        override fun probe() { probes++; answering.forEach { sink?.invoke(HookEvent.Probe(it)) } }

        /** What a hook does: the gate decides first, then the event is queued. Returns whether it was swallowed. */
        fun move(x: Int, y: Int, dx: Int, dy: Int): Boolean {
            val v = gate!!.motion()
            sink!!(HookEvent.Motion(x, y, dx.toDouble(), dy.toDouble(), v.forward))
            return v.swallow
        }
        fun key(mac: Int, down: Boolean): Verdict {
            val v = gate!!.key(mac, down)
            if (v == Verdict.ESCAPE) sink!!(HookEvent.Escape) else if (v.forward) sink!!(HookEvent.Key(mac, down, true))
            return v
        }
        fun button(n: Int, down: Boolean): Verdict {
            val v = gate!!.button(n, down)
            if (v.forward) sink!!(HookEvent.Button(n, down, true))
            return v
        }
        fun wheel(dx: Double, dy: Double) { val v = gate!!.motion(); if (v.forward) sink!!(HookEvent.Wheel(dx, dy, true)) }
    }

    private class Rig(
        displays: List<Rectangle> = listOf(Rectangle(0, 0, 1920, 1080)),
        var target: SourceTarget? = SourceTarget("mac", ScreenEdge.RIGHT),
        var connected: Boolean = true,
        var ctrlAsCommand: Boolean = true,
    ) {
        var throwOnSend = false
        val backend = FakeBackend(displays)
        var now = 10_000L
        val sent = mutableListOf<Pair<String, Envelope>>()
        val changes = mutableListOf<String?>()
        val source = SharedInputSource(
            backend, send = { id, e -> if (throwOnSend) error("network blew up"); if (connected) { sent += id to e; true } else false }, target = { target },
            ctrlAsCommand = { ctrlAsCommand }, onChange = { changes += it }, clock = { now }, executor = { it.run() },
        )

        init { source.tick() }

        fun types() = sent.map { it.second.type }
        fun inputs() = sent.filter { it.second.type == MessageType.REMOTE_INPUT }.map { it.second.remote!! }
        /** Two pushes against the right edge, as the twice rule wants. */
        fun cross(y: Int = 540) {
            backend.move(1919, y, 5, 0)
            backend.move(1919, y, 5, 0)
        }
        fun advance(ms: Long) { now += ms; source.tick() }
    }

    // Edges

    @Test
    fun crossingNeedsTwoPushesInARow() {
        val rig = Rig()
        assertTrue(rig.backend.installed, "the hooks go in once there's a Mac to go to")
        rig.backend.move(1919, 540, 5, 0)
        assertNull(rig.source.crossedTo, "one push isn't enough")
        rig.backend.move(1500, 540, -3, 0)
        rig.backend.move(1919, 540, 5, 0)
        assertNull(rig.source.crossedTo, "a move away in between starts the count again")
        rig.backend.move(1919, 540, 5, 0)
        assertEquals("mac", rig.source.crossedTo)
        val enter = rig.sent.first { it.second.type == MessageType.REMOTE_ENTER }.second.remote!!
        assertEquals("left", enter.edge, "the pointer appears on the Mac's side facing this PC")
        assertEquals(0.5, enter.at!!, 0.01)
        assertTrue(MessageType.PING in rig.types(), "the heartbeat starts at once")
        assertTrue(rig.backend.gate!!.crossed)
        assertNotNull(rig.backend.parked)
        assertEquals(listOf<String?>("mac"), rig.changes)
    }

    @Test
    fun aPushAgainstTheEdgeThatMovesNothingCounts() {
        // Windows reports the clamped point again when the pointer is pressed against the edge.
        val rig = Rig()
        rig.backend.move(1919, 300, 0, 0)
        rig.backend.move(1919, 300, 0, 0)
        assertEquals("mac", rig.source.crossedTo)
        // Sliding along the edge doesn't.
        val slide = Rig()
        slide.backend.move(1919, 300, 0, 4)
        slide.backend.move(1919, 310, 0, 4)
        assertNull(slide.source.crossedTo)
    }

    @Test
    fun aPointPastTheEdgeIsTheEdge() {
        val rig = Rig()
        rig.backend.move(1925, 540, 6, 0)
        rig.backend.move(1931, 540, 6, 0)
        assertEquals("mac", rig.source.crossedTo)
    }

    @Test
    fun theInnerEdgeBetweenTwoMonitorsNeverCrosses() {
        // The second monitor sits left of the main one, at negative x.
        val displays = listOf(Rectangle(0, 0, 1920, 1080), Rectangle(-1280, 100, 1280, 1024))
        val rig = Rig(displays, target = SourceTarget("mac", ScreenEdge.LEFT))
        rig.backend.move(0, 540, -5, 0)
        rig.backend.move(0, 540, -5, 0)
        assertNull(rig.source.crossedTo, "the left edge of the main monitor leads to the other monitor")
        rig.backend.move(-1280, 600, -5, 0)
        rig.backend.move(-1280, 600, -5, 0)
        assertEquals("mac", rig.source.crossedTo)
        val enter = rig.sent.first { it.second.type == MessageType.REMOTE_ENTER }.second.remote!!
        assertEquals("right", enter.edge)
        assertEquals(500.0 / 1024, enter.at!!, 0.01)
        // Home comes back to the same monitor, inside its left edge.
        rig.source.remote(MessageType.REMOTE_LEAVE, id.phonelink.android.core.RemoteEvent("leave", edge = "right", at = 0.25), "mac")
        val back = rig.backend.released.last()!!
        assertEquals(-1280 + 6, back.x)
        assertTrue(kotlin.math.abs(back.y - (100 + 0.25 * 1023)) <= 1)
    }

    @Test
    fun theMainMonitorsTopIsOuterBesideAMonitorAbove() {
        val displays = listOf(Rectangle(0, 0, 2560, 1440), Rectangle(320, -1080, 1920, 1080))
        val rig = Rig(displays, target = SourceTarget("mac", ScreenEdge.TOP))
        // x=100 has no monitor above it.
        rig.backend.move(100, 0, 0, -4)
        rig.backend.move(100, 0, 0, -4)
        assertEquals("mac", rig.source.crossedTo)
    }

    @Test
    fun theTopEdgeIsOuterOnlyWhereNoMonitorIsAbove() {
        val displays = listOf(Rectangle(0, 0, 2560, 1440), Rectangle(320, -1080, 1920, 1080))
        val beside = Rig(displays, target = SourceTarget("mac", ScreenEdge.TOP))
        // Under the upper monitor: it leads there.
        beside.backend.move(1000, 0, 0, -4)
        beside.backend.move(1000, 0, 0, -4)
        assertNull(beside.source.crossedTo)
        // The upper monitor's top is outer.
        beside.backend.move(1000, -1080, 0, -4)
        beside.backend.move(1000, -1080, 0, -4)
        assertEquals("mac", beside.source.crossedTo)
    }

    @Test
    fun parkingIsInsideTheEdge() {
        val p = SharedInputSource.parkPoint(ScreenEdge.RIGHT, Rectangle(0, 0, 1920, 1080), 0.5)
        assertEquals(1920 - 1 - 200, p.x)
        val small = SharedInputSource.parkPoint(ScreenEdge.TOP, Rectangle(-800, -600, 800, 300), 0.0)
        assertEquals(-800, small.x)
        assertTrue(small.y in -600 until -300)
    }

    // Forwarding

    @Test
    fun movesButtonsWheelAndKeysGoToTheMacAndAreSwallowed() {
        val rig = Rig()
        rig.cross()
        assertTrue(rig.backend.move(1719, 540, 12, -3), "moves are swallowed while crossed")
        assertEquals(Verdict.FORWARD, rig.backend.button(0, true))
        assertEquals(Verdict.FORWARD, rig.backend.button(0, false))
        rig.backend.wheel(0.0, 1.0)
        rig.backend.wheel(1.0, 0.0)
        assertEquals(Verdict.FORWARD, rig.backend.key(0, true))
        rig.backend.key(0, false)
        val inputs = rig.inputs()
        assertEquals("move", inputs[0].kind)
        assertEquals(12.0, inputs[0].dx)
        assertEquals(-3.0, inputs[0].dy)
        assertEquals(listOf("down", "up"), inputs.subList(1, 3).map { it.kind })
        assertEquals(0, inputs[1].button)
        assertEquals("scroll", inputs[3].kind)
        assertEquals(SharedInputSource.PIXELS_PER_NOTCH, inputs[3].dy!!, 0.0, "a notch away from you scrolls up")
        assertEquals(-SharedInputSource.PIXELS_PER_NOTCH, inputs[4].dx!!, 0.0, "a tilt right is negative dx on a Mac")
        assertEquals("key", inputs[5].kind)
        assertEquals(0, inputs[5].keyCode)
        assertEquals(true, inputs[5].keyDown)
        assertEquals(false, inputs[6].keyDown)
    }

    @Test
    fun ctrlIsCommandByDefaultAndModifiersComeAsFlags() {
        val rig = Rig()
        rig.cross()
        rig.backend.key(MacKeys.CONTROL, true)
        rig.backend.key(8, true) // C
        rig.backend.key(8, false)
        rig.backend.key(MacKeys.CONTROL, false)
        val inputs = rig.inputs()
        assertEquals("flags", inputs[0].kind)
        assertEquals(MacKeys.COMMAND, inputs[0].keyCode)
        assertTrue(inputs[0].flags!! and MacFlags.COMMAND != 0L)
        assertEquals("key", inputs[1].kind)
        assertTrue(inputs[1].flags!! and MacFlags.COMMAND != 0L, "Ctrl-C is Command-C on the Mac")
        assertEquals(0L, inputs[3].flags!! and MacFlags.COMMAND)

        val plain = Rig(ctrlAsCommand = false)
        plain.cross()
        plain.backend.key(MacKeys.CONTROL, true)
        plain.backend.key(MacKeys.COMMAND, true) // the Windows key
        val flags = plain.inputs().map { it.keyCode }
        assertEquals(listOf(MacKeys.CONTROL, MacKeys.COMMAND), flags)
        assertTrue(plain.inputs()[1].flags!! and MacFlags.CONTROL != 0L)
    }

    @Test
    fun swappedKeysAreReleasedAsTheyWerePressedEvenIfTheSettingChanges() {
        val rig = Rig()
        rig.cross()
        rig.backend.key(MacKeys.CONTROL, true)
        rig.ctrlAsCommand = false
        rig.backend.key(MacKeys.CONTROL, false)
        val inputs = rig.inputs()
        assertEquals(MacKeys.COMMAND, inputs[1].keyCode)
        assertEquals(0L, inputs[1].flags)
    }

    @Test
    fun capsLockTogglesOnEachPress() {
        val rig = Rig()
        rig.cross()
        rig.backend.key(MacKeys.CAPS_LOCK, true)
        rig.backend.key(MacKeys.CAPS_LOCK, false)
        rig.backend.key(MacKeys.CAPS_LOCK, true)
        val flags = rig.inputs().map { it.flags!! and MacFlags.CAPS_LOCK != 0L }
        assertEquals(listOf(true, false), flags)
    }

    @Test
    fun theFlagsCarryLeftAndRightBits() {
        val f = SourceKeys.flags(setOf(MacKeys.SHIFT, MacKeys.RIGHT_OPTION), capsLock = false)
        assertEquals(MacFlags.SHIFT or 0x2 or MacFlags.OPTION or 0x40, f)
        assertEquals(MacFlags.CAPS_LOCK, SourceKeys.flags(emptySet(), capsLock = true))
    }

    // Key maps

    @Test
    fun windowsScanCodesMapByPosition() {
        assertEquals(0, PcKeys.fromWindows('A'.code, 0x1E, false))
        assertEquals(12, PcKeys.fromWindows('Q'.code, 0x10, false))
        // AZERTY: the key in Q's place sends VK 'A' but scan code 0x10, and goes to the Mac's Q place.
        assertEquals(12, PcKeys.fromWindows('A'.code, 0x10, false))
        assertEquals(29, PcKeys.fromWindows('0'.code, 0x0B, false))
        assertEquals(36, PcKeys.fromWindows(0x0D, 0x1C, false))
        assertEquals(76, PcKeys.fromWindows(0x0D, 0x1C, true), "keypad Enter")
        assertEquals(123, PcKeys.fromWindows(0x25, 0x4B, true), "left arrow")
        assertEquals(86, PcKeys.fromWindows(0x64, 0x4B, false), "keypad 4")
        assertEquals(117, PcKeys.fromWindows(0x2E, 0x53, true), "Delete")
        assertEquals(MacKeys.CONTROL, PcKeys.fromWindows(0xA2, 0x1D, false))
        assertEquals(MacKeys.RIGHT_CONTROL, PcKeys.fromWindows(0xA3, 0x1D, true))
        assertEquals(MacKeys.COMMAND, PcKeys.fromWindows(0x5B, 0x5B, true))
        assertEquals(MacKeys.OPTION, PcKeys.fromWindows(0xA4, 0x38, false))
        assertEquals(MacKeys.RIGHT_OPTION, PcKeys.fromWindows(0xA5, 0x38, true))
        assertEquals(122, PcKeys.fromWindows(0x70, 0x3B, false), "F1")
        assertEquals(111, PcKeys.fromWindows(0x7B, 0x58, false), "F12")
        assertEquals(10, PcKeys.fromWindows(0xE2, 0x56, false), "the ISO key")
        assertEquals(71, PcKeys.fromWindows(0x90, 0x45, true), "Num Lock comes as E0 45")
        assertEquals(113, PcKeys.fromWindows(0x13, 0x45, false), "Pause comes as 45")
        assertEquals(PcKeys.IGNORE, PcKeys.fromWindows(0xA2, 0x21D, false), "AltGr's fake Ctrl")
        assertEquals(0, PcKeys.fromWindows('A'.code, 0, false), "no scan code: the virtual key")
        assertEquals(74, PcKeys.fromWindows(0xAD, 0x20, true), "mute")
        assertNull(PcKeys.fromWindows(0xFF, 0, false))
    }

    @Test
    fun linuxKeyCodesMapByPosition() {
        assertEquals(0, PcKeys.fromEvdev(30)) // KEY_A
        assertEquals(0, PcKeys.fromX11(38)) // X11 a
        assertEquals(53, PcKeys.fromEvdev(1))
        assertEquals(126, PcKeys.fromEvdev(103))
        assertEquals(MacKeys.COMMAND, PcKeys.fromEvdev(125))
        assertEquals(MacKeys.RIGHT_OPTION, PcKeys.fromEvdev(100))
        assertEquals(MacKeys.RIGHT_CONTROL, PcKeys.fromEvdev(97))
        assertEquals(76, PcKeys.fromEvdev(96))
        assertEquals(105, PcKeys.fromEvdev(183), "F13")
        // Every mapped key is a real Mac code, and the PC target's table takes the letters back where they came from.
        (1..200).mapNotNull { PcKeys.fromEvdev(it) }.forEach { assertTrue(it in 0..127) }
        for (scan in 0x10..0x19) {
            val mac = PcKeys.fromScancode(scan, false)!!
            assertEquals("QWERTYUIOP"[scan - 0x10].code, MacKeys.vk(mac), "scan $scan")
        }
    }

    @Test
    fun x11ButtonsAndValuators() {
        assertEquals(0, X11Input.button(1))
        assertEquals(1, X11Input.button(3))
        assertEquals(2, X11Input.button(2))
        assertEquals(3, X11Input.button(8))
        assertNull(X11Input.button(4), "the wheel isn't a button")
        assertEquals(listOf(0, 1, 3), X11Input.setValuators(byteArrayOf(0b1011)))
        assertEquals(listOf(1, 9), X11Input.setValuators(byteArrayOf(0b10, 0b10)))
    }

    @Test
    fun windowsMouseMessagesAndInputs() {
        assertEquals(0 to true, WinCapture.button(0x0201, 0))
        assertEquals(1 to false, WinCapture.button(0x0205, 0))
        assertEquals(3 to true, WinCapture.button(0x020B, 1 shl 16))
        assertEquals(4 to false, WinCapture.button(0x020C, 2 shl 16))
        assertNull(WinCapture.button(0x0200, 0))
        val probe = WinCapture.mouseProbe(0x44594B50L, 8)
        assertEquals(40, probe.size)
        val buffer = java.nio.ByteBuffer.wrap(probe).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, buffer.getInt(0))
        assertEquals(1, buffer.getInt(8 + 12))
        assertEquals(0x44594B50L, buffer.getLong(8 + 24))
        val key = java.nio.ByteBuffer.wrap(WinCapture.keyInput(0xE8, up = true, extra = 7, pointerSize = 4)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(28, key.capacity())
        assertEquals(1, key.getInt(0))
        assertEquals(0xE8, key.getShort(4).toInt())
        assertEquals(2, key.getInt(8))
        assertEquals(7, key.getInt(4 + 12))
    }

    // The escape combo

    @Test
    fun ctrlAltWinEndsControlInsideTheHookBeforeSwallowing() {
        val rig = Rig()
        rig.cross()
        rig.backend.key(MacKeys.CONTROL, true)
        rig.backend.key(MacKeys.OPTION, true)
        val v = rig.backend.key(MacKeys.COMMAND, true)
        assertEquals(Verdict.ESCAPE, v)
        assertFalse(rig.backend.gate!!.crossed, "the gate stops swallowing in the hook itself")
        assertNull(rig.source.crossedTo)
        assertEquals(EndReason.ESCAPE, rig.source.lastEnd)
        assertEquals(MessageType.REMOTE_EXIT, rig.types().last())
        // The keys' releases are swallowed here (their presses went to the Mac), so Windows sees no stray Win key.
        assertEquals(Verdict.SWALLOW, rig.backend.key(MacKeys.COMMAND, false))
        assertEquals(Verdict.SWALLOW, rig.backend.key(MacKeys.CONTROL, false))
        assertEquals(Verdict.SWALLOW, rig.backend.key(MacKeys.OPTION, false))
        // And the next keys are this PC's again.
        assertEquals(Verdict.PASS, rig.backend.key(0, true))
    }

    @Test
    fun ctrlAltShiftEscapeIsTheBackup() {
        val rig = Rig()
        rig.cross()
        listOf(MacKeys.RIGHT_CONTROL, MacKeys.OPTION, MacKeys.SHIFT).forEach { rig.backend.key(it, true) }
        assertEquals(Verdict.ESCAPE, rig.backend.key(EscapeCombo.ESCAPE, true))
        assertNull(rig.source.crossedTo)
    }

    @Test
    fun theComboIsOnlyForCrossedAndNeedsAllThree() {
        assertFalse(EscapeCombo.matches(setOf(MacKeys.CONTROL, MacKeys.OPTION), MacKeys.OPTION))
        assertFalse(EscapeCombo.matches(setOf(MacKeys.CONTROL, MacKeys.COMMAND, EscapeCombo.ESCAPE), EscapeCombo.ESCAPE))
        assertTrue(EscapeCombo.matches(setOf(MacKeys.COMMAND, MacKeys.RIGHT_OPTION, MacKeys.CONTROL), MacKeys.CONTROL))
        val gate = InputGate()
        listOf(MacKeys.CONTROL, MacKeys.OPTION).forEach { assertEquals(Verdict.PASS, gate.key(it, true)) }
        assertEquals(Verdict.PASS, gate.key(MacKeys.COMMAND, true), "at home Ctrl+Alt+Win is the PC's own")
    }

    // Held keys

    @Test
    fun keysHeldOnTheMacAreReleasedThereWhenControlEnds() {
        val rig = Rig()
        rig.cross()
        rig.backend.key(MacKeys.SHIFT, true)
        rig.backend.key(0, true)
        rig.backend.button(1, true)
        rig.source.end(EndReason.STOPPED)
        val tail = rig.sent.takeLast(4).map { it.second }
        assertEquals(RemoteSummary("key", 0, false), tail[0].remote!!.let { RemoteSummary(it.kind, it.keyCode, it.keyDown) })
        assertEquals("flags", tail[1].remote!!.kind)
        assertEquals(0L, tail[1].remote!!.flags)
        assertEquals("up", tail[2].remote!!.kind)
        assertEquals(1, tail[2].remote!!.button)
        assertEquals(MessageType.REMOTE_EXIT, tail[3].type)
        // Let go here afterwards: swallowed, since this PC never saw them go down.
        assertEquals(Verdict.SWALLOW, rig.backend.key(0, false))
        assertEquals(Verdict.SWALLOW, rig.backend.button(1, false))
        assertEquals(Verdict.SWALLOW, rig.backend.key(MacKeys.SHIFT, false))
    }

    private data class RemoteSummary(val kind: String, val keyCode: Int?, val keyDown: Boolean?)

    @Test
    fun aKeyHeldHereBeforeCrossingIsLetGoHere() {
        val rig = Rig()
        assertEquals(Verdict.PASS, rig.backend.key(MacKeys.SHIFT, true))
        rig.cross()
        assertEquals(Verdict.PASS, rig.backend.key(MacKeys.SHIFT, false), "the release goes where the press went")
        assertTrue(rig.inputs().none { it.kind == "flags" }, "the Mac never saw it")
        // Pressed again while on the Mac, it goes there.
        assertEquals(Verdict.FORWARD, rig.backend.key(MacKeys.SHIFT, true))
    }

    @Test
    fun autoRepeatOfAKeyHeldWhileComingHomeStaysSwallowed() {
        val rig = Rig()
        rig.cross()
        rig.backend.key(0, true)
        rig.source.remote(MessageType.REMOTE_EXIT, null, "mac")
        assertEquals(Verdict.SWALLOW, rig.backend.key(0, true))
        assertEquals(Verdict.SWALLOW, rig.backend.key(0, false))
        assertEquals(Verdict.PASS, rig.backend.key(0, true))
    }

    // Coming home and the watchdog

    @Test
    fun remoteLeaveBringsThePointerBackWhereItLeft() {
        val rig = Rig()
        rig.cross(y = 270)
        val before = rig.sent.size
        rig.source.remote(MessageType.REMOTE_LEAVE, id.phonelink.android.core.RemoteEvent("leave", edge = "left", at = 0.75), "mac")
        assertNull(rig.source.crossedTo)
        assertEquals(EndReason.LEFT_ON_MAC, rig.source.lastEnd)
        assertEquals(before, rig.sent.size, "the Mac ended it, so nothing more is sent")
        val back = rig.backend.released.last()!!
        assertEquals(1920 - 1 - 6, back.x)
        assertTrue(kotlin.math.abs(back.y - 0.75 * 1079) <= 1)
        assertFalse(rig.backend.gate!!.crossed)
        assertEquals(listOf<String?>("mac", null), rig.changes)
        // Pushing past the edge again crosses again.
        rig.cross()
        assertEquals("mac", rig.source.crossedTo)
    }

    @Test
    fun remoteLeaveOrExitFromAnotherMacIsIgnored() {
        val rig = Rig()
        rig.cross()
        rig.source.remote(MessageType.REMOTE_LEAVE, null, "other")
        rig.source.remote(MessageType.REMOTE_EXIT, null, "other")
        rig.source.gone("other")
        assertEquals("mac", rig.source.crossedTo)
    }

    @Test
    fun noHeartbeatForTwoSecondsTakesTheInputBack() {
        val rig = Rig()
        rig.cross()
        for (i in 0 until 15) { rig.advance(100); rig.source.heard("mac") }
        assertEquals("mac", rig.source.crossedTo, "pongs keep it going")
        assertTrue(rig.types().count { it == MessageType.PING } >= 3, "pinged every half second")
        rig.advance(1_900)
        assertEquals("mac", rig.source.crossedTo)
        rig.advance(200)
        assertNull(rig.source.crossedTo)
        assertEquals(EndReason.HEARTBEAT, rig.source.lastEnd)
        assertFalse(rig.backend.gate!!.crossed)
        assertEquals(1, rig.backend.released.size)
    }

    @Test
    fun aPongFromAnotherComputerIsNoHeartbeat() {
        val rig = Rig()
        rig.cross()
        repeat(25) { rig.advance(100); rig.source.heard("someone-else") }
        assertEquals(EndReason.HEARTBEAT, rig.source.lastEnd)
    }

    @Test
    fun theDisconnectEndsItWithoutSending() {
        val rig = Rig()
        rig.cross()
        rig.connected = false
        rig.source.gone("mac")
        assertEquals(EndReason.DISCONNECTED, rig.source.lastEnd)
        assertFalse(rig.backend.gate!!.crossed)
        // A send that fails while crossed ends it too.
        val other = Rig()
        other.cross()
        other.connected = false
        other.backend.move(1700, 500, 3, 3)
        assertEquals(EndReason.DISCONNECTED, other.source.lastEnd)
    }

    @Test
    fun theMacTurningItsSwitchOffOrThisOneGoingOffEndsIt() {
        val rig = Rig()
        rig.cross()
        rig.target = null // the Mac's info.remoteControl went false, or the PC's switch went off
        rig.advance(100)
        assertEquals(EndReason.TURNED_OFF, rig.source.lastEnd)
        assertEquals(MessageType.REMOTE_EXIT, rig.types().last())
        assertFalse(rig.backend.installed, "no hooks while there's nowhere to go")
        rig.target = SourceTarget("mac", ScreenEdge.RIGHT)
        rig.advance(2_100)
        assertTrue(rig.backend.installed, "back when it's on again")
    }

    @Test
    fun anExceptionInTheHookReleasesEverything() {
        val rig = Rig()
        rig.cross()
        // What the hook's catch-all does: stop swallowing, then report.
        rig.backend.gate!!.crossed = false
        rig.backend.sink!!(HookEvent.Failed(IllegalStateException("boom")))
        assertEquals(EndReason.HOOK_FAILED, rig.source.lastEnd)
        assertFalse(rig.backend.installed)
        assertEquals(MessageType.REMOTE_EXIT, rig.types().last())
        rig.advance(2_100)
        assertTrue(rig.backend.installed, "installed again on a later tick")
    }

    @Test
    fun anExceptionInTheWorkerReleasesEverything() {
        val rig = Rig()
        rig.cross()
        rig.throwOnSend = true
        rig.backend.move(1700, 500, 3, 3)
        assertNull(rig.source.crossedTo)
        assertEquals(EndReason.HOOK_FAILED, rig.source.lastEnd)
        assertFalse(rig.backend.gate!!.crossed)
        assertNull(rig.backend.parked)
    }

    @Test
    fun quitReleasesAndRemovesTheHooks() {
        val rig = Rig()
        rig.cross()
        rig.source.shutdown()
        assertEquals(EndReason.QUIT, rig.source.lastEnd)
        assertFalse(rig.backend.installed)
        assertFalse(rig.backend.gate!!.crossed)
        assertEquals(MessageType.REMOTE_EXIT, rig.types().last())
    }

    @Test
    fun aFailedCaptureDoesNotCross() {
        val rig = Rig()
        rig.backend.captureWorks = false
        rig.cross()
        assertNull(rig.source.crossedTo)
        assertFalse(rig.backend.gate!!.crossed)
        assertTrue(rig.types().none { it == MessageType.REMOTE_ENTER })
    }

    @Test
    fun noCrossingWithoutATarget() {
        val rig = Rig(target = null)
        assertFalse(rig.backend.installed)
        rig.target = SourceTarget("mac", ScreenEdge.RIGHT)
        rig.advance(100)
        assertTrue(rig.backend.installed)
        rig.target = null
        rig.cross()
        assertNull(rig.source.crossedTo)
    }

    // The hooks Windows removes

    @Test
    fun aHookThatStopsAnsweringIsInstalledAgain() {
        val rig = Rig()
        rig.advance(100) // first probe
        assertTrue(rig.backend.probes >= 1)
        rig.advance(1_100)
        assertEquals(0, rig.source.reinstalls, "both hooks answered")
        rig.cross()
        rig.source.heard("mac")
        // Windows silently drops the keyboard hook.
        rig.backend.answering = setOf("mouse")
        rig.advance(1_000) // probe goes out
        rig.source.heard("mac")
        rig.advance(600)
        rig.source.heard("mac")
        rig.advance(600)
        assertEquals(1, rig.source.reinstalls)
        assertEquals(EndReason.HOOK_LOST, rig.source.lastEnd, "control ends: the keyboard wasn't held any more")
        assertTrue(rig.backend.installed)
        assertEquals(2, rig.backend.installs)
        assertFalse(rig.backend.gate!!.crossed)
    }

    @Test
    fun backendsWithoutProbesAreNotReinstalled() {
        val rig = Rig()
        rig.backend.probeHooks = emptySet()
        repeat(50) { rig.advance(100) }
        assertEquals(0, rig.backend.probes)
        assertEquals(0, rig.source.reinstalls)
    }

    @Test
    fun aBackendThatCantInstallIsRetriedSlowly() {
        val rig = Rig(target = null)
        rig.backend.installWorks = false
        rig.target = SourceTarget("mac", ScreenEdge.RIGHT)
        repeat(30) { rig.advance(100) }
        assertTrue(rig.backend.installs in 1..2, "every 2 s, not every tick: ${rig.backend.installs}")
    }

    // Cap gating

    @Test
    fun theSourceCapIsSeparateFromThePcCaps() {
        assertEquals("pc-shared-input-source", PhoneLinkProtocol.CAP_PC_SHARED_INPUT_SOURCE)
        assertFalse(PhoneLinkProtocol.CAP_PC_SHARED_INPUT_SOURCE in PhoneLinkProtocol.PC_CAPS, "listed only where input can be captured")
        assertTrue(PhoneLinkProtocol.CAP_PC_SHARED_INPUT in PhoneLinkProtocol.PC_CAPS)
    }

    @Test
    fun theSettingsSayWhatTheMacNeeds() {
        val mac = id.phonelink.android.core.HubPhone("m1", "Air", "MacBook Air", isPaired = true, isConnected = true, platform = "macos")
        assertTrue(id.dysklink.desktop.ui.sourceStatus(null, emptyList())!!.startsWith("Pair a Mac"))
        assertEquals("Choose the Mac.", id.dysklink.desktop.ui.sourceStatus(null, listOf(mac)))
        assertEquals("Air isn't connected.", id.dysklink.desktop.ui.sourceStatus(mac.copy(isConnected = false), listOf(mac)))
        assertTrue(id.dysklink.desktop.ui.sourceStatus(mac, listOf(mac))!!.contains("Let paired computers use their mouse and keyboard here"))
        val ready = mac.copy(info = id.phonelink.android.core.DeviceInfo(remoteControl = true))
        assertNull(id.dysklink.desktop.ui.sourceStatus(ready, listOf(ready)))
    }

    @Test
    fun waylandAndMissingXInput2GetAHint() {
        val wayland = HostChecks.hints(HostEnv(windows = false, linux = true, env = mapOf("XDG_SESSION_TYPE" to "wayland", "WAYLAND_DISPLAY" to "wayland-0")::get))
        assertTrue(wayland.source.single().text.startsWith("On Wayland this needs the InputCapture portal and libei, not supported yet"))
        val noXi = HostChecks.hints(HostEnv(windows = false, linux = true, env = mapOf("XDG_SESSION_TYPE" to "x11")::get, osRelease = "ID=debian", xinput2 = false))
        assertEquals("sudo apt install libxi6", noXi.source.single().command)
        val fine = HostChecks.hints(HostEnv(windows = false, linux = true, env = mapOf("XDG_SESSION_TYPE" to "x11")::get, xinput2 = true))
        assertTrue(fine.source.isEmpty())
        assertTrue(HostChecks.hints(HostEnv(windows = true, linux = false)).source.isEmpty())
    }
}
