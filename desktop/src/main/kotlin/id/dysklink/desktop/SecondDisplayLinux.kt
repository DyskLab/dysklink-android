@file:Suppress("FunctionName")

package id.dysklink.desktop

import id.phonelink.android.core.CoreLog
import id.phonelink.android.core.PcDisplay
import id.phonelink.android.core.PcExtendFailed
import id.phonelink.android.core.PcExtraDisplay
import id.phonelink.android.core.PcSecondDisplay
import id.phonelink.android.core.ScreenGrab
import java.awt.Rectangle
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.MethodNoReply
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

// The phone as a second display on Linux: a virtual monitor of the Wayland compositor (through the portal, else
// GNOME's Mutter API), or the X server's VIRTUAL output set up with xrandr.

/**
 * A virtual monitor's stream as an extra display: pictures from [feed]; taps through [tap] in the monitor's own pixels
 * (the session that made it), or dropped when there's no pointer in that session, since the monitor has no known place
 * in desktop pixels. [close] ends the session, which removes the monitor.
 */
class VirtualStreamDisplay(
    private val name: String,
    private val width: Int,
    private val height: Int,
    private val feed: VirtualMonitorFeed,
    private val position: () -> Pair<Int, Int>?,
    private val streamSize: () -> Pair<Int, Int>?,
    private val tap: ((kind: String, x: Double, y: Double, button: Pair<Int, Boolean>?) -> Unit)?,
    private val close: () -> Unit,
) : PcExtraDisplay {
    @Volatile private var ended: String? = null
    @Volatile private var released = false

    override fun refusal(): String? = null

    override fun displays(): List<PcDisplay> {
        val (w, h) = streamSize() ?: feed.size ?: (width to height)
        val (x, y) = position() ?: (0 to 0)
        return listOf(ExtraDisplayIds.display(name, x, y, w, h))
    }

    override fun grab(display: PcDisplay, maxLong: Int): ScreenGrab? = if (ended != null || released) null else feed.grab()

    override fun failure(): String? = ended ?: feed.failure

    override fun pointer(kind: String, x: Float, y: Float): Boolean {
        val tap = tap ?: return true
        val (w, h) = streamSize() ?: (width to height)
        runCatching {
            VirtualTaps.at(kind, x, y, w, h, move = { px, py -> tap(kind, px, py, null) }, button = { code, down -> tap(kind, 0.0, 0.0, code to down) })
        }.onFailure { CoreLog.w("DyskLink", "Second display: tap failed", it) }
        return true
    }

    /** The desktop ended it (the user pressed Stop in the top bar). */
    fun endedHere(why: String) { ended = why }

    @Synchronized
    override fun release() {
        if (released) return
        released = true
        feed.stop()
        runCatching(close).onFailure { CoreLog.w("DyskLink", "Second display: closing failed", it) }
    }
}

object ExtendWords {
    const val DENIED = "Adding the extra display wasn't allowed on the PC. Allow it there, then try again."
    const val NO_ANSWER = "Nobody allowed the extra display on the PC in time. Allow it there, then try again."
    const val ENDED = "The extra display was stopped on the PC."
    const val NO_PICTURE = "The PC added the extra display but its picture didn't arrive."
}

// ---- The ScreenCast portal's virtual source ----

/**
 * A virtual monitor through org.freedesktop.portal.ScreenCast with SourceType VIRTUAL: GNOME's portal asks Mutter
 * for one, KDE Plasma 6's asks KWin (zkde_screencast's stream_virtual_output). With phones allowed to control this PC
 * it's a RemoteDesktop session with that stream, so taps land on the new monitor exactly.
 */
class PortalVirtualBackend(
    private val portal: () -> PortalClient?,
    private val gst: () -> GstCheck,
    private val withControl: () -> Boolean,
    private val launch: (List<String>) -> Process = { ProcessBuilder(it).start() },
) : ExtendBackend {
    override val way = ExtendWay.PortalVirtual

    override fun probe(): ExtendProbe {
        val client = portal() ?: return ExtendProbe(way, ExtendReadiness.Unavailable, ExtendProblem.NoPortal)
        val cast = client.offer(PortalBits.SCREEN_CAST)
        if (cast.version == null) return ExtendProbe(way, ExtendReadiness.Unavailable, ExtendProblem.NoPortal)
        if (cast.types and PortalBits.VIRTUAL == 0) return ExtendProbe(way, ExtendReadiness.Unavailable, ExtendProblem.PortalNoVirtual)
        return gstProbe(way, gst())
    }

    override fun open(width: Int, height: Int, phoneName: String): PcExtraDisplay {
        val client = portal() ?: throw ExtendUnavailable(ExtendProblem.NoPortal, "This PC has no screen sharing portal.")
        val cast = client.offer(PortalBits.SCREEN_CAST)
        var shown: VirtualStreamDisplay? = null
        val onClosed: (PortalSession) -> Unit = { shown?.endedHere(ExtendWords.ENDED) }
        val combined = withControl() && client.offer(PortalBits.REMOTE_DESKTOP).version != null
        val session = try {
            if (combined) client.startRemoteDesktop(cast, onClosed, PortalBits.VIRTUAL) else client.startScreenCast(cast, onClosed, PortalBits.VIRTUAL)
        } catch (e: PortalRefused) {
            throw PcExtendFailed(
                when {
                    e.cancelled -> ExtendWords.DENIED
                    e.message == "No answer from the system dialog." -> ExtendWords.NO_ANSWER
                    else -> "The PC couldn't add the extra display: ${e.message}"
                },
            )
        }
        val stream = session.streams.firstOrNull() ?: run {
            session.close()
            throw PcExtendFailed(PcSecondDisplay.FAILED)
        }
        val feed = VirtualMonitorFeed(stream.node, width, height, gst(), launch)
        if (!feed.start()) {
            feed.stop()
            session.close()
            throw PcExtendFailed(feed.failure ?: ExtendWords.NO_PICTURE)
        }
        val tap: ((String, Double, Double, Pair<Int, Boolean>?) -> Unit)? = if (!session.hasPointer) null else { _, x, y, button ->
            if (button == null) session.motionAbsolute(stream.node, x, y) else session.button(button.first, button.second)
        }
        return VirtualStreamDisplay(phoneName, width, height, feed, { stream.position }, { stream.size ?: feed.size }, tap) { session.close() }
            .also { shown = it }
    }
}

/** GStreamer turns every Wayland stream into pictures: without it a way needs a setup. */
fun gstProbe(way: ExtendWay, check: GstCheck): ExtendProbe = when {
    !check.launch -> ExtendProbe(way, ExtendReadiness.NeedsSetup, ExtendProblem.NoGstreamer)
    !check.pipewiresrc -> ExtendProbe(way, ExtendReadiness.NeedsSetup, ExtendProblem.NoPipewiresrc)
    else -> ExtendProbe(way, ExtendReadiness.Ready)
}

// ---- GNOME's own API: org.gnome.Mutter.ScreenCast RecordVirtual, with org.gnome.Mutter.RemoteDesktop for taps ----

object MutterBits {
    const val SCREEN_CAST = "org.gnome.Mutter.ScreenCast"
    const val SCREEN_CAST_PATH = "/org/gnome/Mutter/ScreenCast"
    const val SCREEN_CAST_SESSION = "org.gnome.Mutter.ScreenCast.Session"
    const val REMOTE_DESKTOP = "org.gnome.Mutter.RemoteDesktop"
    const val REMOTE_DESKTOP_PATH = "/org/gnome/Mutter/RemoteDesktop"
    const val REMOTE_DESKTOP_SESSION = "org.gnome.Mutter.RemoteDesktop.Session"
    /** cursor-mode: 0 hidden, 1 drawn into the picture, 2 as metadata. */
    const val CURSOR_EMBEDDED = 1L

    /** The major version in `gnome-shell --version` ("GNOME Shell 46.2"), or null. */
    fun shellVersion(output: String?): Int? = output?.let { Regex("""GNOME Shell (\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /** Virtual monitors came to GNOME with its remote desktop work; GNOME 3.x has none. */
    const val FIRST_VIRTUAL = 40

    /** RecordVirtual's properties: the pointer drawn in, so the phone shows it. */
    fun recordVirtual(): Map<String, Variant<*>> = mapOf("cursor-mode" to Variant(UInt32(CURSOR_EMBEDDED)))

    /** CreateSession's properties, linked to a remote desktop session so its input lands on this stream. */
    fun createSession(remoteDesktopId: String?): Map<String, Variant<*>> =
        if (remoteDesktopId == null) emptyMap() else mapOf("remote-desktop-session-id" to Variant(remoteDesktopId))

    /** Which kind of refusal a D-Bus error is. */
    fun problem(error: Throwable): ExtendProblem? {
        val text = ((error as? DBusExecutionException)?.type.orEmpty() + " " + error.javaClass.simpleName + " " + error.message.orEmpty())
        return when {
            "UnknownMethod" in text || "UnknownInterface" in text -> ExtendProblem.GnomeTooOld
            "AccessDenied" in text || "NotAllowed" in text || "PermissionDenied" in text -> ExtendProblem.MutterRefused
            "ServiceUnknown" in text || "NameHasNoOwner" in text -> ExtendProblem.NoMutter
            else -> null
        }
    }
}

@DBusInterfaceName("org.gnome.Mutter.ScreenCast")
interface MutterScreenCast : DBusInterface {
    fun CreateSession(properties: Map<String, Variant<*>>): DBusPath
}

@DBusInterfaceName("org.gnome.Mutter.ScreenCast.Session")
interface MutterScreenCastSession : DBusInterface {
    fun Start()
    fun Stop()
    fun RecordVirtual(properties: Map<String, Variant<*>>): DBusPath

    class Closed(path: String) : DBusSignal(path)
}

@DBusInterfaceName("org.gnome.Mutter.ScreenCast.Stream")
interface MutterScreenCastStream : DBusInterface {
    class PipeWireStreamAdded(path: String, val nodeId: UInt32) : DBusSignal(path, nodeId)
}

@DBusInterfaceName("org.gnome.Mutter.RemoteDesktop")
interface MutterRemoteDesktop : DBusInterface {
    fun CreateSession(): DBusPath
}

@DBusInterfaceName("org.gnome.Mutter.RemoteDesktop.Session")
interface MutterRemoteDesktopSession : DBusInterface {
    fun Start()
    fun Stop()
    @MethodNoReply fun NotifyPointerMotionAbsolute(stream: String, x: Double, y: Double)
    @MethodNoReply fun NotifyPointerButton(button: Int, state: Boolean)

    class Closed(path: String) : DBusSignal(path)
}

/**
 * A virtual monitor straight from GNOME's Mutter, without a dialog: ScreenCast CreateSession, then RecordVirtual,
 * Start, and the PipeWire node in PipeWireStreamAdded. With control, a RemoteDesktop session is made first and the
 * screen cast linked to it (remote-desktop-session-id), and that one is started; taps go to the stream with
 * NotifyPointerMotionAbsolute. A GNOME that refuses or lacks it falls through to the next way.
 */
class MutterVirtualBackend(
    private val connect: () -> DBusConnection?,
    private val gst: () -> GstCheck,
    private val withControl: () -> Boolean,
    /** GNOME Shell's major version, or null when it isn't GNOME or can't be told. */
    private val gnomeVersion: () -> Int?,
    private val launch: (List<String>) -> Process = { ProcessBuilder(it).start() },
    private val nodeTimeoutMs: Long = 10_000,
) : ExtendBackend {
    override val way = ExtendWay.MutterVirtual

    override fun probe(): ExtendProbe {
        val conn = connect() ?: return ExtendProbe(way, ExtendReadiness.Unavailable, ExtendProblem.NoMutter)
        val there = runCatching {
            conn.getRemoteObject(MutterBits.SCREEN_CAST, MutterBits.SCREEN_CAST_PATH, Properties::class.java).Get<Any?>(MutterBits.SCREEN_CAST, "Version")
        }.isSuccess
        if (!there) return ExtendProbe(way, ExtendReadiness.Unavailable, ExtendProblem.NoMutter)
        gnomeVersion()?.let { if (it < MutterBits.FIRST_VIRTUAL) return ExtendProbe(way, ExtendReadiness.Unavailable, ExtendProblem.GnomeTooOld, "GNOME $it") }
        return gstProbe(way, gst())
    }

    private fun refused(e: Throwable, what: String): Nothing {
        val problem = MutterBits.problem(e)
        CoreLog.w("DyskLink", "Second display: Mutter $what failed: ${e.message}")
        if (problem != null) throw ExtendUnavailable(problem, "GNOME didn't add a virtual monitor here.")
        throw PcExtendFailed("GNOME couldn't add the extra display: ${e.message}")
    }

    override fun open(width: Int, height: Int, phoneName: String): PcExtraDisplay {
        val conn = connect() ?: throw ExtendUnavailable(ExtendProblem.NoMutter, "No session bus.")
        val rdPath: DBusPath? = if (!withControl()) null else runCatching {
            conn.getRemoteObject(MutterBits.REMOTE_DESKTOP, MutterBits.REMOTE_DESKTOP_PATH, MutterRemoteDesktop::class.java).CreateSession()
        }.onFailure { CoreLog.w("DyskLink", "Second display: no Mutter remote desktop, taps won't work: ${it.message}") }.getOrNull()
        val rd = rdPath?.let { conn.getRemoteObject(MutterBits.REMOTE_DESKTOP, it.path, MutterRemoteDesktopSession::class.java) }
        val rdId = rdPath?.let {
            runCatching { PortalBits.string(conn.getRemoteObject(MutterBits.REMOTE_DESKTOP, it.path, Properties::class.java).Get<Any?>(MutterBits.REMOTE_DESKTOP_SESSION, "SessionId")) }.getOrNull()
        }
        fun stopRd() { runCatching { rd?.Stop() } }
        val scPath = try {
            conn.getRemoteObject(MutterBits.SCREEN_CAST, MutterBits.SCREEN_CAST_PATH, MutterScreenCast::class.java).CreateSession(MutterBits.createSession(rdId))
        } catch (e: Exception) {
            stopRd()
            refused(e, "CreateSession")
        }
        val sc = conn.getRemoteObject(MutterBits.SCREEN_CAST, scPath.path, MutterScreenCastSession::class.java)
        fun stopAll() {
            if (rd != null) stopRd() else runCatching { sc.Stop() }
        }
        val streamPath = try {
            sc.RecordVirtual(MutterBits.recordVirtual())
        } catch (e: Exception) {
            stopAll()
            refused(e, "RecordVirtual")
        }
        val node = CompletableFuture<Long>()
        val added = conn.addSigHandler(MutterScreenCastStream.PipeWireStreamAdded::class.java) { if (it.path == streamPath.path) node.complete(it.nodeId.toLong()) }
        var shown: VirtualStreamDisplay? = null
        val closedSc = conn.addSigHandler(MutterScreenCastSession.Closed::class.java) { if (it.path == scPath.path) shown?.endedHere(ExtendWords.ENDED) }
        val closedRd = conn.addSigHandler(MutterRemoteDesktopSession.Closed::class.java) { if (it.path == rdPath?.path) shown?.endedHere(ExtendWords.ENDED) }
        fun dropHandlers() {
            runCatching { added.close() }
            runCatching { closedSc.close() }
            runCatching { closedRd.close() }
        }
        try {
            if (rd != null) rd.Start() else sc.Start()
        } catch (e: Exception) {
            dropHandlers()
            stopAll()
            refused(e, "Start")
        }
        val id = try {
            node.get(nodeTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            dropHandlers()
            stopAll()
            throw PcExtendFailed("GNOME didn't send the extra display's picture.")
        } finally {
            runCatching { added.close() }
        }
        val feed = VirtualMonitorFeed(id, width, height, gst(), launch)
        if (!feed.start()) {
            feed.stop()
            dropHandlers()
            stopAll()
            throw PcExtendFailed(feed.failure ?: ExtendWords.NO_PICTURE)
        }
        val tap: ((String, Double, Double, Pair<Int, Boolean>?) -> Unit)? = if (rd == null) null else { _, x, y, button ->
            if (button == null) rd.NotifyPointerMotionAbsolute(streamPath.path, x, y) else rd.NotifyPointerButton(button.first, button.second)
        }
        return VirtualStreamDisplay(phoneName, width, height, feed, { null }, { width to height }, tap) {
            dropHandlers()
            stopAll()
        }.also { shown = it }
    }
}

// ---- X11: the graphics driver's VIRTUAL output, through xrandr ----

/** One output in `xrandr --query`: its name, whether a monitor is there, and where it is on the desktop when it's on. */
data class XrandrOutput(val name: String, val connected: Boolean, val primary: Boolean, val geometry: Rectangle?)

/** A display mode as cvt prints it, to give to `xrandr --newmode`. */
data class Modeline(val name: String, val clock: String, val timings: List<String>, val flags: List<String>) {
    /** What follows the mode's name in `xrandr --newmode`. */
    val args: List<String> get() = listOf(clock) + timings + flags
    val width: Int? get() = timings.getOrNull(0)?.toIntOrNull()
    val height: Int? get() = timings.getOrNull(4)?.toIntOrNull()
}

object Xrandr {
    private val header = Regex("""^(\S+)\s+(connected|disconnected)(\s+primary)?(?:\s+(\d+)x(\d+)\+(-?\d+)\+(-?\d+))?""")

    fun parse(query: String): List<XrandrOutput> = query.lines().mapNotNull { line ->
        if (line.startsWith(" ") || line.startsWith("\t") || line.startsWith("Screen ")) return@mapNotNull null
        val m = header.find(line) ?: return@mapNotNull null
        val g = m.groupValues
        val geometry = if (g[4].isNotEmpty()) Rectangle(g[6].toInt(), g[7].toInt(), g[4].toInt(), g[5].toInt()) else null
        XrandrOutput(g[1], g[2] == "connected", g[3].isNotBlank(), geometry)
    }

    /**
     * The driver's spare output (intel's VIRTUAL1, also VIRTUAL-1): only these add room to the desktop. `--setmonitor`
     * only names part of the framebuffer, so it's never used.
     */
    fun virtualOutput(outputs: List<XrandrOutput>): XrandrOutput? = outputs.firstOrNull { it.name.uppercase().startsWith("VIRTUAL") }

    /** The output a new one goes beside: the primary one, else the first that's on. */
    fun anchor(outputs: List<XrandrOutput>, except: String): XrandrOutput? =
        outputs.filter { it.name != except && it.geometry != null }.let { on -> on.firstOrNull { it.primary } ?: on.firstOrNull() }

    fun modeName(width: Int, height: Int) = "DyskLink-${width}x$height"

    /** `Modeline "1328x600_60.00"  64.00  1328 1384 1512 1696  600 603 613 631 -hsync +vsync` from cvt. */
    fun cvt(output: String): Modeline? {
        val line = output.lines().firstOrNull { it.trim().startsWith("Modeline") } ?: return null
        val m = Regex("""Modeline\s+"([^"]+)"\s+(.*)""").find(line.trim()) ?: return null
        val parts = m.groupValues[2].trim().split(Regex("""\s+"""))
        if (parts.size < 9 || parts[0].toDoubleOrNull() == null || parts.subList(1, 9).any { it.toIntOrNull() == null }) return null
        return Modeline(m.groupValues[1], parts[0], parts.subList(1, 9), parts.drop(9))
    }

    /** The commands that add [mode] to [output] and turn it on beside [anchor], in order. */
    fun turnOn(output: String, anchor: String?, mode: Modeline, name: String): List<List<String>> = listOf(
        listOf("xrandr", "--newmode", name) + mode.args,
        listOf("xrandr", "--addmode", output, name),
        listOf("xrandr", "--output", output, "--mode", name) + (anchor?.let { listOf("--right-of", it) } ?: emptyList()),
    )

    /** And the ones that take it away again. */
    fun turnOff(output: String, name: String): List<List<String>> = listOf(
        listOf("xrandr", "--output", output, "--off"),
        listOf("xrandr", "--delmode", output, name),
        listOf("xrandr", "--rmmode", name),
    )
}

/**
 * X11 with a VIRTUAL output: a mode of the phone's size made with cvt, added and turned on right of the main monitor,
 * captured with Robot, and removed on stop. Nothing here needs root.
 */
class X11OutputBackend(
    private val onPath: (String) -> Boolean,
    private val run: (List<String>) -> Pair<Int, String>?,
    private val canCapture: () -> Boolean,
) : ExtendBackend {
    override val way = ExtendWay.X11Output

    override fun probe(): ExtendProbe {
        if (!onPath("xrandr")) return ExtendProbe(way, ExtendReadiness.Unavailable, ExtendProblem.NoXrandr)
        val query = run(listOf("xrandr", "--query"))?.takeIf { it.first == 0 }?.second
            ?: return ExtendProbe(way, ExtendReadiness.Unavailable, ExtendProblem.NoXrandr)
        Xrandr.virtualOutput(Xrandr.parse(query)) ?: return ExtendProbe(way, ExtendReadiness.Unavailable, ExtendProblem.X11NoVirtualOutput)
        if (!onPath("cvt")) return ExtendProbe(way, ExtendReadiness.Unavailable, ExtendProblem.NoCvt)
        if (!canCapture()) return ExtendProbe(way, ExtendReadiness.Unavailable, ExtendProblem.NoCapture)
        return ExtendProbe(way, ExtendReadiness.Ready)
    }

    private fun outputs(): List<XrandrOutput> = run(listOf("xrandr", "--query"))?.takeIf { it.first == 0 }?.second?.let(Xrandr::parse).orEmpty()

    override fun open(width: Int, height: Int, phoneName: String): PcExtraDisplay {
        val all = outputs()
        val output = Xrandr.virtualOutput(all) ?: throw ExtendUnavailable(ExtendProblem.X11NoVirtualOutput, "This X11 session has no virtual output.")
        val mode = run(listOf("cvt", "$width", "$height", "60"))?.takeIf { it.first == 0 }?.second?.let(Xrandr::cvt)
            ?: throw PcExtendFailed("cvt couldn't make a display mode of ${width}×$height on the PC.")
        val name = Xrandr.modeName(mode.width ?: width, mode.height ?: height)
        val off = Xrandr.turnOff(output.name, name)
        // Left over from a stop that never happened (DyskLink was killed): taken away first, quietly.
        off.forEach { run(it) }
        for (command in Xrandr.turnOn(output.name, Xrandr.anchor(all, output.name)?.name, mode, name)) {
            val result = run(command)
            if (result == null || result.first != 0) {
                off.forEach { run(it) }
                throw PcExtendFailed("xrandr couldn't turn on ${output.name}: ${result?.second?.lines()?.firstOrNull { it.isNotBlank() } ?: "it didn't run"}")
            }
        }
        var geometry: Rectangle? = null
        val until = System.currentTimeMillis() + 3_000
        while (geometry == null && System.currentTimeMillis() < until) {
            geometry = outputs().firstOrNull { it.name == output.name }?.geometry
            if (geometry == null) Thread.sleep(150)
        }
        if (geometry == null) {
            off.forEach { run(it) }
            throw PcExtendFailed("${output.name} didn't turn on.")
        }
        // Looked up again now and then, since it can be moved in the display settings while it's shown.
        var bounds: Rectangle = geometry
        var lookedAt = System.currentTimeMillis()
        return RobotExtraDisplay(phoneName, {
            if (System.currentTimeMillis() - lookedAt > 3_000) {
                lookedAt = System.currentTimeMillis()
                outputs().firstOrNull { it.name == output.name }?.geometry?.let { bounds = it }
            }
            bounds
        }) {
            off.forEach { run(it) }
        }
    }
}
