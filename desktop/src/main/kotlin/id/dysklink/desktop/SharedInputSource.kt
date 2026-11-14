package id.dysklink.desktop

import id.phonelink.android.core.CoreLog
import id.phonelink.android.core.Envelope
import id.phonelink.android.core.MessageType
import id.phonelink.android.core.RemoteEvent
import id.phonelink.android.core.RemoteLimits
import java.awt.Point
import java.awt.Rectangle
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// This PC's own mouse and keyboard on a Mac: pushing the pointer twice past the edge chosen for the Mac sends
// `remote.enter`, then everything goes to the Mac as `remote.input` (exactly what a Mac sends) until the Mac says
// `remote.leave`, or Ctrl+Alt+Win (or Ctrl+Alt+Shift+Escape) is pressed here. The capture itself is a
// [CaptureBackend]: low-level hooks on Windows, XInput2 and grabs on X11. Everything here is plain logic, tested
// with a fake backend.
//
// Safety: input that's held here is only ever held while the Mac answers. Control ends (and the hooks stop
// swallowing) on no `pong` for 2 s, on the disconnect, the switch going off, the Mac turning its own switch off, an
// exception anywhere, a hook Windows silently removed, and on quit. Keys and buttons pressed on the Mac get their
// release sent to it, and keys pressed before crossing get their release here, so nothing stays down on either side.

/** PC keys to Mac virtual key codes, by US position, from Windows scan codes or Linux (evdev, X11) key codes. */
object PcKeys {
    /** AltGr's fake left Ctrl on Windows: swallowed with AltGr, never sent. */
    const val IGNORE = -2

    /** Scan code set 1 without the E0 prefix. Linux key codes 1 to 88 are the same numbers. */
    private val base: Map<Int, Int> = mapOf(
        0x01 to 53, 0x02 to 18, 0x03 to 19, 0x04 to 20, 0x05 to 21, 0x06 to 23, 0x07 to 22, 0x08 to 26, 0x09 to 28,
        0x0A to 25, 0x0B to 29, 0x0C to 27, 0x0D to 24, 0x0E to 51, 0x0F to 48,
        0x10 to 12, 0x11 to 13, 0x12 to 14, 0x13 to 15, 0x14 to 17, 0x15 to 16, 0x16 to 32, 0x17 to 34, 0x18 to 31,
        0x19 to 35, 0x1A to 33, 0x1B to 30, 0x1C to 36, 0x1D to MacKeys.CONTROL,
        0x1E to 0, 0x1F to 1, 0x20 to 2, 0x21 to 3, 0x22 to 5, 0x23 to 4, 0x24 to 38, 0x25 to 40, 0x26 to 37,
        0x27 to 41, 0x28 to 39, 0x29 to 50, 0x2A to MacKeys.SHIFT, 0x2B to 42,
        0x2C to 6, 0x2D to 7, 0x2E to 8, 0x2F to 9, 0x30 to 11, 0x31 to 45, 0x32 to 46, 0x33 to 43, 0x34 to 47,
        0x35 to 44, 0x36 to MacKeys.RIGHT_SHIFT, 0x37 to 67, 0x38 to MacKeys.OPTION, 0x39 to 49, 0x3A to MacKeys.CAPS_LOCK,
        // F1 to F10.
        0x3B to 122, 0x3C to 120, 0x3D to 99, 0x3E to 118, 0x3F to 96, 0x40 to 97, 0x41 to 98, 0x42 to 100,
        0x43 to 101, 0x44 to 109,
        // Num Lock is the Mac's Clear; Scroll Lock is F14.
        0x45 to 71, 0x46 to 107,
        // The keypad.
        0x47 to 89, 0x48 to 91, 0x49 to 92, 0x4A to 78, 0x4B to 86, 0x4C to 87, 0x4D to 88, 0x4E to 69,
        0x4F to 83, 0x50 to 84, 0x51 to 85, 0x52 to 82, 0x53 to 65,
        // The key left of Z on ISO keyboards, F11, F12.
        0x56 to 10, 0x57 to 103, 0x58 to 111,
    )

    /** Windows: scan codes with the E0 prefix, and the ones past F12. */
    private val windowsExtra: Map<Int, Int> = mapOf(
        0x64 to 105, 0x65 to 107, 0x66 to 113, 0x67 to 106, 0x68 to 64, 0x69 to 79, 0x6A to 80, 0x6B to 90,
        // JIS: Ro, Yen, Henkan (Kana), Muhenkan (Eisu), Katakana.
        0x73 to 94, 0x7D to 93, 0x79 to 104, 0x7B to 102, 0x70 to 104,
    )
    private val windowsExtended: Map<Int, Int> = mapOf(
        0x1C to 76, 0x1D to MacKeys.RIGHT_CONTROL, 0x35 to 75, 0x37 to 105, 0x38 to MacKeys.RIGHT_OPTION,
        0x47 to 115, 0x48 to 126, 0x49 to 116, 0x4B to 123, 0x4D to 124, 0x4F to 119, 0x50 to 125, 0x51 to 121,
        0x52 to 114, 0x53 to 117, 0x5B to MacKeys.COMMAND, 0x5C to MacKeys.RIGHT_COMMAND, 0x5D to 110,
        0x20 to 74, 0x2E to 73, 0x30 to 72,
    )

    /** Linux key codes past 88. */
    private val evdev: Map<Int, Int> = mapOf(
        89 to 94, 92 to 104, 94 to 102, 96 to 76, 97 to MacKeys.RIGHT_CONTROL, 98 to 75, 99 to 105,
        100 to MacKeys.RIGHT_OPTION, 102 to 115, 103 to 126, 104 to 116, 105 to 123, 106 to 124, 107 to 119,
        108 to 125, 109 to 121, 110 to 114, 111 to 117, 113 to 74, 114 to 73, 115 to 72, 117 to 81, 119 to 113,
        124 to 93, 125 to MacKeys.COMMAND, 126 to MacKeys.RIGHT_COMMAND, 127 to 110,
        183 to 105, 184 to 107, 185 to 113, 186 to 106, 187 to 64, 188 to 79, 189 to 80, 190 to 90,
    )

    /** Virtual keys for events without a scan code (some injected ones and odd keyboards). */
    private val windowsVk: Map<Int, Int> = buildMap {
        "ASDFHGZXCV".forEachIndexed { i, c -> put(c.code, i) }
        put('B'.code, 11); put('Q'.code, 12); put('W'.code, 13); put('E'.code, 14); put('R'.code, 15); put('Y'.code, 16)
        put('T'.code, 17); put('O'.code, 31); put('U'.code, 32); put('I'.code, 34); put('P'.code, 35); put('L'.code, 37)
        put('J'.code, 38); put('K'.code, 40); put('N'.code, 45); put('M'.code, 46)
        listOf(29, 18, 19, 20, 21, 23, 22, 26, 28, 25).forEachIndexed { digit, mac -> put('0'.code + digit, mac) }
        put(0x08, 51); put(0x09, 48); put(0x0D, 36); put(0x1B, 53); put(0x20, 49)
        put(0x21, 116); put(0x22, 121); put(0x23, 119); put(0x24, 115); put(0x25, 123); put(0x26, 126); put(0x27, 124); put(0x28, 125)
        put(0x2D, 114); put(0x2E, 117)
        put(0x10, MacKeys.SHIFT); put(0xA0, MacKeys.SHIFT); put(0xA1, MacKeys.RIGHT_SHIFT)
        put(0x11, MacKeys.CONTROL); put(0xA2, MacKeys.CONTROL); put(0xA3, MacKeys.RIGHT_CONTROL)
        put(0x12, MacKeys.OPTION); put(0xA4, MacKeys.OPTION); put(0xA5, MacKeys.RIGHT_OPTION)
        put(0x5B, MacKeys.COMMAND); put(0x5C, MacKeys.RIGHT_COMMAND); put(0x14, MacKeys.CAPS_LOCK)
        put(0xAD, 74); put(0xAE, 73); put(0xAF, 72)
    }

    fun fromScancode(scan: Int, extended: Boolean): Int? =
        if (extended) windowsExtended[scan] else base[scan] ?: windowsExtra[scan]

    /** A Linux key code (as in linux/input-event-codes.h). */
    fun fromEvdev(code: Int): Int? = if (code in 1..88) base[code] else evdev[code]

    /** An X11 key code: the Linux one plus 8 under evdev and libinput. */
    fun fromX11(keycode: Int): Int? = fromEvdev(keycode - 8)

    /** From a KBDLLHOOKSTRUCT: Windows reports Num Lock as E0 45 and Pause as 45, the other way round from the scan codes. */
    fun fromWindows(vk: Int, scan: Int, extended: Boolean): Int? = when {
        vk == 0x90 -> 71
        vk == 0x13 -> 113
        scan == 0x21D -> IGNORE
        scan != 0 -> fromScancode(scan and 0xFF, extended) ?: windowsVk[vk]
        else -> windowsVk[vk]
    }
}

/** Mac modifier keys and CGEventFlags, as this PC sends them. */
object SourceKeys {
    /** With [ctrlAsCommand], Ctrl is Command and the Windows (Super) key is Control, the PC target's Command-as-Ctrl turned round. */
    fun swap(mac: Int, ctrlAsCommand: Boolean): Int = if (!ctrlAsCommand) mac else when (mac) {
        MacKeys.CONTROL -> MacKeys.COMMAND
        MacKeys.RIGHT_CONTROL -> MacKeys.RIGHT_COMMAND
        MacKeys.COMMAND -> MacKeys.CONTROL
        MacKeys.RIGHT_COMMAND -> MacKeys.RIGHT_CONTROL
        else -> mac
    }

    fun isModifier(mac: Int) = mac in MacKeys.MODIFIERS

    /** The flags for the modifier keys held on the Mac's side, with the left and right bits a Mac's own keyboard sets. */
    fun flags(held: Set<Int>, capsLock: Boolean): Long {
        var flags = if (capsLock) MacFlags.CAPS_LOCK else 0L
        for (key in held) {
            flags = flags or when (key) {
                MacKeys.SHIFT -> MacFlags.SHIFT or 0x2
                MacKeys.RIGHT_SHIFT -> MacFlags.SHIFT or 0x4
                MacKeys.CONTROL -> MacFlags.CONTROL or 0x1
                MacKeys.RIGHT_CONTROL -> MacFlags.CONTROL or 0x2000
                MacKeys.OPTION -> MacFlags.OPTION or 0x20
                MacKeys.RIGHT_OPTION -> MacFlags.OPTION or 0x40
                MacKeys.COMMAND -> MacFlags.COMMAND or 0x8
                MacKeys.RIGHT_COMMAND -> MacFlags.COMMAND or 0x10
                else -> 0L
            }
        }
        return flags
    }
}

/** Ctrl+Alt+Win, or Ctrl+Alt+Shift+Escape, brings the pointer home at once, by the PC keys' own names. */
object EscapeCombo {
    private val ctrl = setOf(MacKeys.CONTROL, MacKeys.RIGHT_CONTROL)
    private val alt = setOf(MacKeys.OPTION, MacKeys.RIGHT_OPTION)
    private val win = setOf(MacKeys.COMMAND, MacKeys.RIGHT_COMMAND)
    private val shift = setOf(MacKeys.SHIFT, MacKeys.RIGHT_SHIFT)
    const val ESCAPE = 53

    /** [held]: the PC keys down now, as Mac codes before any swap, with [pressed] (just going down) among them. */
    fun matches(held: Set<Int>, pressed: Int): Boolean {
        val c = held.any { it in ctrl }
        val a = held.any { it in alt }
        if (!c || !a) return false
        if (pressed in ctrl || pressed in alt || pressed in win) return held.any { it in win }
        return pressed == ESCAPE && held.any { it in shift }
    }
}

/**
 * What the hook does with one event, decided on the hook's own thread before anything is swallowed. [swallow]: this
 * PC doesn't see it. [forward]: it goes to the Mac.
 */
enum class Verdict(val swallow: Boolean, val forward: Boolean) {
    PASS(false, false), FORWARD(true, true), SWALLOW(true, false),
    /** The escape combo: control ends at once; the key itself stays swallowed, and so does its release. */
    ESCAPE(true, false),
}

/**
 * The fast part, on the hook thread: whether input is swallowed now, and where each key and button went down, so its
 * release goes to the same side. A key held here before crossing is let go here, and one pressed on the Mac is
 * swallowed here when it's let go after coming home, so neither side is left with a key down.
 */
class InputGate {
    @Volatile var crossed = false
    private val held = HashSet<Int>()
    private val remoteKeys = HashSet<Int>()
    private val remoteButtons = HashSet<Int>()

    /** [id]: the Mac key code, or [UNKNOWN_BASE] plus the PC's own code for a key the Mac doesn't have. */
    @Synchronized
    fun key(id: Int, down: Boolean): Verdict {
        if (down) {
            held += id
            if (!crossed) return if (id in remoteKeys) Verdict.SWALLOW else Verdict.PASS
            remoteKeys += id
            if (EscapeCombo.matches(held, id)) {
                crossed = false
                return Verdict.ESCAPE
            }
            return Verdict.FORWARD
        }
        held -= id
        if (!remoteKeys.remove(id)) return Verdict.PASS
        return if (crossed) Verdict.FORWARD else Verdict.SWALLOW
    }

    @Synchronized
    fun button(button: Int, down: Boolean): Verdict {
        if (down) {
            if (!crossed) return Verdict.PASS
            remoteButtons += button
            return Verdict.FORWARD
        }
        if (!remoteButtons.remove(button)) return Verdict.PASS
        return if (crossed) Verdict.FORWARD else Verdict.SWALLOW
    }

    /** Pointer moves and the wheel. */
    fun motion(): Verdict = if (crossed) Verdict.FORWARD else Verdict.PASS

    /** Keys held down on this PC now, by the gate's count. */
    @Synchronized
    fun held(): Set<Int> = held.toSet()

    companion object {
        const val UNKNOWN_BASE = 1000
    }
}

/** What a hook saw, queued from the hook thread to the worker. */
sealed interface HookEvent {
    /**
     * Not crossed: [x], [y] where the pointer goes, and [dx], [dy] how it moved. Crossed: [dx], [dy] from where the
     * pointer is held. Both in this PC's pixels.
     */
    data class Motion(val x: Int, val y: Int, val dx: Double, val dy: Double, val forward: Boolean) : HookEvent
    /** 0 left, 1 right, 2 middle, 3 and 4 back and forward, as a Mac numbers them. */
    data class Button(val button: Int, val down: Boolean, val forward: Boolean) : HookEvent
    /** Wheel notches: [dy] positive turned away from you (scrolls up), [dx] positive to the right. */
    data class Wheel(val dx: Double, val dy: Double, val forward: Boolean) : HookEvent
    /** [mac]: the Mac key code (before Ctrl and Command change places), or null for a key a Mac doesn't have. */
    data class Key(val mac: Int?, val down: Boolean, val forward: Boolean) : HookEvent
    data object Escape : HookEvent
    /** A test input from [CaptureBackend.probe] came through [hook]. */
    data class Probe(val hook: String) : HookEvent
    data class Failed(val error: Throwable) : HookEvent
}

/** What watches and holds this PC's input. Windows hooks and X11 grabs in the app; a fake in tests. */
interface CaptureBackend {
    /** The monitors, in the coordinates the hooks report (multi-monitor, with negative offsets left of or above the main one). */
    val displays: List<Rectangle>
    val installed: Boolean
    /** Starts watching. Nothing is swallowed until [capture]. Events go to [sink] from the hook thread. False when it can't. */
    fun install(gate: InputGate, sink: (HookEvent) -> Unit): Boolean
    /** Stops watching and lets everything go. Never throws. */
    fun uninstall()
    /** Holds the pointer at [park] (hidden where it can be) and swallows input. False when it can't, such as another app's grab. */
    fun capture(park: Point): Boolean
    /** Lets the pointer go, at [to]. From any thread; never throws. */
    fun release(to: Point?)
    /** Called on the watchdog's ticks while crossed: the pointer back at its place, the clip renewed. */
    fun keepParked() {}
    /** The hooks [probe] checks, each answering with [HookEvent.Probe]; empty where hooks can't vanish (X11 grabs). */
    val probeHooks: Set<String> get() = emptySet()
    /** Sends an input only the hooks see; each hook swallows it and reports it. */
    fun probe() {}
    /**
     * When the system last saw input (Windows' GetLastInputInfo tick), or null where it isn't known. A probe is
     * injected input, and may or may not move this tick; it's read after a probe's echo, so the next look can tell
     * real input since then from the probe's own.
     */
    fun lastInputTick(): Long? = null
}

/** The Mac this PC's mouse and keyboard can go to, past [edge] of this PC. Null while it can't (switch off, Mac away). */
data class SourceTarget(val peer: String, val edge: ScreenEdge)

/** Why control came home. [notify]: the Mac is told (key releases, then `remote.exit`). */
enum class EndReason(val notify: Boolean) {
    LEFT_ON_MAC(false), MAC_EXIT(false), ESCAPE(true), HEARTBEAT(true), DISCONNECTED(false), TURNED_OFF(true),
    HOOK_FAILED(true), HOOK_LOST(true), QUIT(true), STOPPED(true),
}

class SharedInputSource(
    private val backend: CaptureBackend,
    /** Sends to a paired Mac; false when it isn't connected. */
    private val send: (String, Envelope) -> Boolean,
    /** Checked on every crossing and every tick. */
    private val target: () -> SourceTarget?,
    private val ctrlAsCommand: () -> Boolean = { true },
    /** The Mac this PC's input is on now, or null when it came home. */
    private val onChange: (String?) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    /** Runs hook events in order, off the hook thread. Tests run them at once. */
    private val executor: ((Runnable) -> Unit)? = null,
) {
    val gate = InputGate()
    @Volatile var crossedTo: String? = null
        private set
    var lastEnd: EndReason? = null
        private set
    /** How often the hooks were put back after Windows took them away. */
    var reinstalls = 0
        private set

    private val worker = if (executor == null) Executors.newSingleThreadExecutor { r -> Thread(r, "dysk-input-source").apply { isDaemon = true } } else null
    private val run: (Runnable) -> Unit = executor ?: { r -> runCatching { worker!!.execute(r) } }
    @Volatile private var ticking = false

    private var departure: Triple<Rectangle, ScreenEdge, Double>? = null
    private var pushes = 0
    private var lastPoint: Point? = null
    private var lastHeard = 0L
    private var lastPing = 0L
    private var lastInstallTry = Long.MIN_VALUE / 2
    private var probeSent: Long? = null
    private val probeEchoes = HashSet<String>()
    /** The system's input tick right after the last probe came back, and whether the hooks saw real input since. */
    private var probeTick: Long? = null
    private var activeSinceProbe = true
    // What the Mac holds down because of this PC, released when control ends.
    private val keysDown = HashSet<Int>()
    private val modifiers = HashSet<Int>()
    private val buttons = HashSet<Int>()
    /** The Mac code each held PC key went out as, so a release matches its press even if the setting changed. */
    private val sentAs = HashMap<Int, Int>()
    private var capsLock = false

    /** Starts the watchdog. The hooks are installed by the first tick when there's a Mac to go to. */
    fun start() {
        if (ticking) return
        ticking = true
        thread(isDaemon = true, name = "dysk-input-watchdog") {
            while (ticking) {
                runCatching { tick() }.onFailure { CoreLog.w(TAG, "Watchdog", it) }
                runCatching { Thread.sleep(TICK_MS) }
            }
        }
    }

    /** From the hook thread. */
    private fun sink(event: HookEvent) = run(Runnable { process(event) })

    @Synchronized
    fun process(event: HookEvent) {
        try {
            if (event !is HookEvent.Probe) activeSinceProbe = true
            when (event) {
                is HookEvent.Motion -> if (crossedTo == null) watch(event) else if (event.forward) move(event.dx, event.dy)
                is HookEvent.Button -> if (event.forward) button(event.button, event.down)
                is HookEvent.Wheel -> if (event.forward) wheel(event.dx, event.dy)
                is HookEvent.Key -> if (event.forward) key(event.mac, event.down)
                HookEvent.Escape -> end(EndReason.ESCAPE)
                is HookEvent.Probe -> {
                    probeEchoes += event.hook
                    // Read after the probe went through, so its own effect on the tick (if any) is in it.
                    probeTick = runCatching { backend.lastInputTick() }.getOrNull()
                }
                is HookEvent.Failed -> {
                    CoreLog.w(TAG, "Input hook failed", event.error)
                    end(EndReason.HOOK_FAILED)
                    runCatching { backend.uninstall() }
                }
            }
        } catch (error: Throwable) {
            CoreLog.w(TAG, "Input source", error)
            end(EndReason.HOOK_FAILED)
        }
    }

    // Watching the edge

    private fun watch(motion: HookEvent.Motion) {
        val chosen = target()
        val displays = backend.displays
        val raw = Point(motion.x, motion.y)
        // Hooks may report a point past the screen's edge, or the edge itself again: either way it's the edge.
        val point = EdgeMath.keepOnDisplays(raw, lastPoint ?: raw, displays)
        lastPoint = point
        if (chosen == null) { pushes = 0; return }
        val edge = chosen.edge
        // Pressed against the edge, the pointer can't move, so a move of nothing there counts as a push too.
        val pushing = edge.pushes(motion.dx, motion.dy) || (motion.dx == 0.0 && motion.dy == 0.0)
        val hit = if (pushing) EdgeMath.hit(point, edge, displays) else null
        if (hit == null) { pushes = 0; return }
        // Two pushes in a row, as on a Mac, so brushing past the edge doesn't cross.
        if (++pushes < 2) return
        pushes = 0
        enter(chosen, hit.first, hit.second)
    }

    private fun enter(chosen: SourceTarget, display: Rectangle, at: Double) {
        if (crossedTo != null) return
        val park = parkPoint(chosen.edge, display, at)
        if (!runCatching { backend.capture(park) }.getOrDefault(false)) {
            CoreLog.w(TAG, "REMOTE_SOURCE_CAPTURE_FAILED")
            runCatching { backend.release(null) }
            return
        }
        gate.crossed = true
        crossedTo = chosen.peer
        departure = Triple(display, chosen.edge, at)
        keysDown.clear(); modifiers.clear(); buttons.clear(); sentAs.clear(); capsLock = false
        val now = clock()
        lastHeard = now
        lastPing = now
        val ok = send(chosen.peer, Envelope(MessageType.REMOTE_ENTER, remote = RemoteEvent("enter", edge = chosen.edge.opposite.wire, at = at)))
        if (!ok) return end(EndReason.DISCONNECTED)
        send(chosen.peer, Envelope(MessageType.PING))
        CoreLog.i(TAG, "REMOTE_SOURCE_ENTER ${chosen.edge.wire}")
        runCatching { onChange(chosen.peer) }
    }

    // While crossed

    private fun forward(event: RemoteEvent) {
        val peer = crossedTo ?: return
        if (!send(peer, Envelope(MessageType.REMOTE_INPUT, remote = event))) end(EndReason.DISCONNECTED)
    }

    private fun move(dx: Double, dy: Double) {
        if (dx == 0.0 && dy == 0.0) return
        val x = RemoteLimits.delta(dx, RemoteLimits.MAX_MOVE) ?: return
        val y = RemoteLimits.delta(dy, RemoteLimits.MAX_MOVE) ?: return
        forward(RemoteEvent("move", dx = x, dy = y))
    }

    private fun button(number: Int, down: Boolean) {
        val n = RemoteLimits.button(number) ?: return
        if (down) buttons += n else if (!buttons.remove(n)) return
        forward(RemoteEvent(if (down) "down" else "up", button = n))
    }

    private fun wheel(dx: Double, dy: Double) {
        if (dx == 0.0 && dy == 0.0) return
        // A Mac scrolls up for positive dy and left for positive dx, in pixels.
        forward(RemoteEvent("scroll", dx = -dx * PIXELS_PER_NOTCH, dy = dy * PIXELS_PER_NOTCH))
    }

    private fun key(mac: Int?, down: Boolean) {
        if (mac == null || mac < 0) return
        val code = if (down) SourceKeys.swap(mac, ctrlAsCommand()).also { sentAs[mac] = it } else sentAs.remove(mac) ?: SourceKeys.swap(mac, ctrlAsCommand())
        if (code == MacKeys.CAPS_LOCK) {
            // A Mac toggles Caps Lock with each press, as one flags change.
            if (!down) return
            capsLock = !capsLock
            return forward(RemoteEvent("flags", keyCode = code, flags = SourceKeys.flags(modifiers, capsLock)))
        }
        if (SourceKeys.isModifier(code)) {
            if (down) { if (!modifiers.add(code)) return } else if (!modifiers.remove(code)) return
            return forward(RemoteEvent("flags", keyCode = code, flags = SourceKeys.flags(modifiers, capsLock)))
        }
        // Held down, a PC repeats the press; the Mac gets each one, as it posts no repeats of its own for these.
        if (down) keysDown += code else if (!keysDown.remove(code)) return
        forward(RemoteEvent("key", keyCode = code, keyDown = down, flags = SourceKeys.flags(modifiers, capsLock)))
    }

    // Coming home

    /** Ends control now, whatever is going on: the pointer comes back and nothing stays held on either side. */
    @Synchronized
    fun end(reason: EndReason) {
        val peer = crossedTo ?: return
        // First stop swallowing, so this PC has its mouse and keyboard back even if what follows fails.
        gate.crossed = false
        crossedTo = null
        val to = departure?.let { (display, edge, at) -> EdgeMath.point(edge, display, at, inset = 6) }
        departure = null
        runCatching { backend.release(to) }.onFailure { CoreLog.w(TAG, "Release", it) }
        lastPoint = to
        pushes = 0
        if (reason.notify) runCatching {
            // The Mac lets go of everything on remote.exit too; these are for a Mac that missed it.
            keysDown.forEach { send(peer, Envelope(MessageType.REMOTE_INPUT, remote = RemoteEvent("key", keyCode = it, keyDown = false, flags = 0))) }
            modifiers.lastOrNull()?.let { send(peer, Envelope(MessageType.REMOTE_INPUT, remote = RemoteEvent("flags", keyCode = it, flags = 0))) }
            buttons.forEach { send(peer, Envelope(MessageType.REMOTE_INPUT, remote = RemoteEvent("up", button = it))) }
            send(peer, Envelope(MessageType.REMOTE_EXIT))
        }
        keysDown.clear(); modifiers.clear(); buttons.clear(); sentAs.clear()
        lastEnd = reason
        CoreLog.i(TAG, "REMOTE_SOURCE_HOME ${reason.name}")
        runCatching { onChange(null) }
    }

    /** `remote.leave` (the pointer was pushed back past the edge on the Mac) or `remote.exit` from [from]. */
    @Synchronized
    fun remote(type: String, event: RemoteEvent?, from: String) {
        if (from != crossedTo) return
        when (type) {
            MessageType.REMOTE_LEAVE -> {
                departure = departure?.copy(third = RemoteLimits.fraction(event?.at))
                end(EndReason.LEFT_ON_MAC)
            }
            MessageType.REMOTE_EXIT -> end(EndReason.MAC_EXIT)
        }
    }

    /** Anything from [peer], its `pong` included: the heartbeat. */
    fun heard(peer: String) {
        if (peer == crossedTo) synchronized(this) { lastHeard = clock() }
    }

    /** [peer] disconnected or was unpaired. */
    @Synchronized
    fun gone(peer: String) {
        if (peer == crossedTo) end(EndReason.DISCONNECTED)
    }

    /** The watchdog: the heartbeat, the hooks, and whether there's still a Mac to go to. Every [TICK_MS]. */
    @Synchronized
    fun tick() {
        val now = clock()
        val chosen = target()
        crossedTo?.let { peer ->
            when {
                chosen?.peer != peer -> end(EndReason.TURNED_OFF)
                now - lastHeard > HEARTBEAT_MS -> end(EndReason.HEARTBEAT)
                else -> {
                    if (now - lastPing >= PING_MS) {
                        lastPing = now
                        if (!runCatching { send(peer, Envelope(MessageType.PING)) }.getOrDefault(false)) end(EndReason.DISCONNECTED)
                    }
                    runCatching { backend.keepParked() }
                }
            }
        }
        if (chosen == null) {
            if (backend.installed) uninstall()
            return
        }
        if (!backend.installed) {
            // Not more than every 2 s, so a backend that can't start isn't hammered.
            if (now - lastInstallTry < REINSTALL_MS) return
            lastInstallTry = now
            install()
            return
        }
        checkHooks(now)
    }

    /** Windows removes a hook that answered too slowly, without a word: a probe that doesn't come back means it's gone. */
    private fun checkHooks(now: Long) {
        val hooks = backend.probeHooks
        if (hooks.isEmpty()) return
        val sent = probeSent
        if (sent != null && !probeEchoes.containsAll(hooks)) {
            if (now - sent < PROBE_TIMEOUT_MS) return
            CoreLog.w(TAG, "REMOTE_SOURCE_HOOK_LOST ${hooks - probeEchoes}")
            end(EndReason.HOOK_LOST)
            runCatching { backend.uninstall() }
            reinstalls++
            lastInstallTry = now
            install()
            return
        }
        // Every second while the pointer is on the Mac, every 10 s otherwise; and only after real input since the
        // last probe, so a PC left alone sends nothing that could keep its screen from locking.
        val every = if (crossedTo != null) PROBE_EVERY_MS else IDLE_PROBE_EVERY_MS
        if (sent == null || now - sent >= every) {
            if (sent != null && !usedSinceProbe()) return
            probeEchoes.clear()
            probeSent = now
            activeSinceProbe = false
            runCatching { backend.probe() }
        }
    }

    /** Real input since the last probe: seen by the hooks, or a system input tick other than the probe's. */
    private fun usedSinceProbe(): Boolean {
        if (activeSinceProbe) return true
        val before = probeTick ?: return true
        val tick = runCatching { backend.lastInputTick() }.getOrNull() ?: return true
        return tick != before
    }

    private fun install() {
        probeSent = null
        probeEchoes.clear()
        probeTick = null
        activeSinceProbe = true
        val ok = runCatching { backend.install(gate, ::sink) }.onFailure { CoreLog.w(TAG, "Install", it) }.getOrDefault(false)
        CoreLog.i(TAG, if (ok) "REMOTE_SOURCE_WATCHING" else "REMOTE_SOURCE_INSTALL_FAILED")
    }

    private fun uninstall() {
        end(EndReason.TURNED_OFF)
        runCatching { backend.uninstall() }
        probeSent = null
        CoreLog.i(TAG, "REMOTE_SOURCE_STOPPED")
    }

    /** On quit (and from the shutdown hook): everything let go, the hooks removed. */
    @Synchronized
    fun shutdown() {
        ticking = false
        end(EndReason.QUIT)
        gate.crossed = false
        runCatching { backend.uninstall() }
        worker?.shutdown()
        runCatching { worker?.awaitTermination(200, TimeUnit.MILLISECONDS) }
    }

    companion object {
        private const val TAG = "DyskLink"
        const val TICK_MS = 100L
        const val PING_MS = 500L
        /** No answer from the Mac for this long and this PC takes its input back. */
        const val HEARTBEAT_MS = 2_000L
        const val PROBE_EVERY_MS = 1_000L
        /** While the pointer is on this PC: a lost hook only matters once the pointer goes to the edge. */
        const val IDLE_PROBE_EVERY_MS = 10_000L
        const val PROBE_TIMEOUT_MS = 1_000L
        const val REINSTALL_MS = 2_000L
        /** A wheel notch on Windows scrolls 3 lines; about this many pixels on a Mac. */
        const val PIXELS_PER_NOTCH = 40.0

        /**
         * Where the pointer is held while it's on the Mac: inside [edge] of [display], far enough in that a fast move
         * toward the edge isn't cut short by it.
         */
        fun parkPoint(edge: ScreenEdge, display: Rectangle, at: Double): Point {
            val depth = when (edge) {
                ScreenEdge.LEFT, ScreenEdge.RIGHT -> display.width
                ScreenEdge.TOP, ScreenEdge.BOTTOM -> display.height
            }
            return EdgeMath.point(edge, display, at, inset = minOf(200, maxOf(depth / 2 - 1, 0)))
        }
    }
}

/** "Use this PC's mouse and keyboard on a Mac" as the settings show it. */
data class SourceView(
    /** False where this PC can't capture its input (Wayland); the hints say why. */
    val supported: Boolean,
    val on: Boolean = false,
    /** The paired Mac chosen, by device ID. */
    val mac: String? = null,
    /** The side of this PC the Mac sits on. */
    val edge: ScreenEdge = ScreenEdge.RIGHT,
    val ctrlAsCommand: Boolean = true,
    /** The Mac the pointer is on now. */
    val usingMac: String? = null,
)

/** Monitors looked up at most every [maxAgeMs], since the hooks ask on every pointer move. */
class CachedDisplays(private val maxAgeMs: Long = 2_000, private val clock: () -> Long = System::currentTimeMillis, private val load: () -> List<Rectangle>) {
    private var value: List<Rectangle> = emptyList()
    private var at = Long.MIN_VALUE / 2

    @Synchronized
    fun get(): List<Rectangle> {
        val now = clock()
        if (value.isEmpty() || now - at >= maxAgeMs) {
            value = runCatching(load).getOrDefault(value)
            at = now
        }
        return value
    }
}
