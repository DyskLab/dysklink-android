package id.dysklink.desktop

import id.phonelink.android.core.PcDisplay
import id.phonelink.android.core.PcExtendFailed
import id.phonelink.android.core.PcExtraDisplay
import id.phonelink.android.core.PcScreen
import id.phonelink.android.core.ScreenGrab
import java.awt.Rectangle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.bin.EmbeddedDBusDaemon
import org.freedesktop.dbus.connections.base.AbstractConnectionBase
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.connections.transports.TransportBuilder
import org.freedesktop.dbus.errors.AccessDenied
import org.freedesktop.dbus.errors.UnknownMethod
import org.freedesktop.dbus.errors.UnknownProperty
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/**
 * The phone as a second display on Windows and Linux: picking the way, the portal's virtual source and GNOME's Mutter
 * API on dbus-java's own bus with fake services, xrandr and cvt, the Windows driver's settings and display calls with a
 * fake Windows, and where pictures and taps land on the new monitor.
 */
class SecondDisplayTest {
    private var daemon: EmbeddedDBusDaemon? = null
    private val connections = mutableListOf<DBusConnection>()
    private val dir: File = Files.createTempDirectory("dysk-extend").toFile()

    @AfterTest
    fun stop() {
        connections.forEach { runCatching { it.close() } }
        runCatching { daemon?.close() }
        dir.deleteRecursively()
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!what()) {
            check(System.nanoTime() < until) { "Timed out" }
            Thread.sleep(20)
        }
    }

    // ---- Picking the way ----

    private class FakeBackend(override val way: ExtendWay, var probe: ExtendProbe, val fails: PcExtendFailed? = null) : ExtendBackend {
        val opened = CopyOnWriteArrayList<String>()
        override fun probe() = probe
        override fun open(width: Int, height: Int, phoneName: String): PcExtraDisplay {
            opened += "${width}x$height"
            fails?.let { throw it }
            return object : PcExtraDisplay {
                override fun refusal(): String? = null
                override fun displays() = listOf(ExtraDisplayIds.display(phoneName, 0, 0, width, height))
                override fun grab(display: PcDisplay, maxLong: Int): ScreenGrab? = null
            }
        }
    }

    @Test
    fun theFirstReadyWayIsUsedAndOneThatDoesntWorkHereFallsThrough() {
        val portal = FakeBackend(ExtendWay.PortalVirtual, ExtendProbe(ExtendWay.PortalVirtual, ExtendReadiness.Ready), ExtendUnavailable(ExtendProblem.PortalNoVirtual, "no"))
        val mutter = FakeBackend(ExtendWay.MutterVirtual, ExtendProbe(ExtendWay.MutterVirtual, ExtendReadiness.Ready))
        val service = SecondDisplayService { listOf(portal, mutter) }
        assertTrue(service.checking.value)
        assertNull(service.refusal(), "Checked on the first ask")
        assertTrue(service.listed)
        val shown = service.open(1328, 600, "Pixel")
        assertEquals(listOf("1328x600"), portal.opened)
        assertEquals(listOf("1328x600"), mutter.opened)
        assertEquals(1328, shown.displays().single().width)
        // The way that didn't work isn't tried again until the next check.
        assertEquals(ExtendReadiness.Unavailable, service.probes.value.first().readiness)
        service.open(600, 1328, "Pixel")
        assertEquals(1, portal.opened.size)
        service.recheck()
        assertEquals(ExtendReadiness.Ready, service.probes.value.first().readiness)

        // A refusal the user gave stops there: nothing else is tried.
        val denied = FakeBackend(ExtendWay.PortalVirtual, ExtendProbe(ExtendWay.PortalVirtual, ExtendReadiness.Ready), PcExtendFailed(ExtendWords.DENIED))
        val other = FakeBackend(ExtendWay.MutterVirtual, ExtendProbe(ExtendWay.MutterVirtual, ExtendReadiness.Ready))
        val second = SecondDisplayService { listOf(denied, other) }.apply { recheck() }
        assertEquals(ExtendWords.DENIED, assertFailsWith<PcExtendFailed> { second.open(1328, 600, "Pixel") }.message)
        assertTrue(other.opened.isEmpty())
    }

    @Test
    fun whatThePhoneIsToldAndWhetherTheCapIsListed() {
        fun p(way: ExtendWay, r: ExtendReadiness, problem: ExtendProblem? = null) = ExtendProbe(way, r, problem)
        val gst = listOf(p(ExtendWay.PortalVirtual, ExtendReadiness.NeedsSetup, ExtendProblem.NoGstreamer), p(ExtendWay.MutterVirtual, ExtendReadiness.Unavailable, ExtendProblem.NoMutter))
        assertTrue(ExtendChoice.listed(gst), "Listed: the phone is told what to install")
        assertTrue(ExtendChoice.refusal(gst)!!.contains("GStreamer"))
        val driver = listOf(p(ExtendWay.WindowsDriver, ExtendReadiness.NeedsSetup, ExtendProblem.DriverMissing))
        assertTrue(ExtendChoice.listed(driver), "Installable from the PC")
        assertTrue(ExtendChoice.refusal(driver)!!.contains("Install the virtual display driver"))
        val none = listOf(p(ExtendWay.X11Output, ExtendReadiness.Unavailable, ExtendProblem.X11NoVirtualOutput))
        assertFalse(ExtendChoice.listed(none), "X11 without a virtual output: the phone doesn't offer it")
        assertEquals(PcScreen.NO_EXTEND, ExtendChoice.refusal(none))
        assertFalse(ExtendChoice.listed(emptyList()))
        val off = listOf(p(ExtendWay.WindowsDriver, ExtendReadiness.Unavailable, ExtendProblem.ExperimentOff))
        assertFalse(ExtendChoice.listed(off), "Windows: not offered until the experimental switch is on")
    }

    // ---- GStreamer for a virtual monitor ----

    @Test
    fun theStreamIsAskedForThePhonesSize() {
        val fixed = GstVirtualPipeline.command(91, 1328, 600, fixed = true, maxLong = 1920)
        val line = fixed.joinToString(" ")
        assertTrue(line.startsWith("gst-launch-1.0 -q pipewiresrc path=91 do-timestamp=true ! video/x-raw,width=1328,height=600 ! videorate"), line)
        assertFalse("videoscale" in line, "Already the right size")
        assertTrue(line.endsWith("multipartmux boundary=dysk ! fdsink fd=1"))
        // A compositor that keeps its own size: read as it comes, scaled to the phone's shape.
        val loose = GstVirtualPipeline.command(91, 1328, 600, fixed = false, maxLong = 1920, keepalive = true).joinToString(" ")
        assertFalse("! video/x-raw,width=1328,height=600 ! videorate" in loose)
        assertTrue("keepalive-time=1000" in loose)
        assertTrue("videoscale add-borders=true ! video/x-raw,width=1328,height=600" in loose)
        // A big phone is scaled to the picture's limit, the monitor still its full size.
        val big = GstVirtualPipeline.command(91, 2560, 1152, fixed = true, maxLong = 1920).joinToString(" ")
        assertTrue("video/x-raw,width=2560,height=1152 ! videorate" in big && "video/x-raw,width=1920,height=864" in big, big)
        assertTrue(GstVirtualPipeline.refusedSize("ERROR: from element pipewiresrc0: Internal data stream error. streaming stopped, reason not-negotiated (-4)"))
        assertFalse(GstVirtualPipeline.refusedSize("Could not connect to PipeWire"))
    }

    private fun jpeg(width: Int, height: Int): ByteArray {
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "jpg", out)
        return out.toByteArray()
    }

    private fun multipart(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) {
            out.write("--dysk\r\nContent-Type: image/jpeg\r\nContent-Length: ${p.size}\r\n\r\n".toByteArray())
            out.write(p)
            out.write("\r\n".toByteArray())
        }
        return out.toByteArray()
    }

    /** gst-launch-1.0 as it would run: [stdout] is its pictures, [stderr] its words; alive until stopped unless [dies]. */
    private class FakeGst(stdout: ByteArray, stderr: String = "", private val dies: Boolean = false) : Process() {
        private val input = ByteArrayInputStream(stdout)
        private val errors = ByteArrayInputStream(stderr.toByteArray())
        @Volatile private var alive = !dies
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = errors
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit) = true
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 1
        override fun destroy() { alive = false }
        override fun isAlive() = alive
    }

    private val gstOk = GstCheck(launch = true, pipewiresrc = true)

    @Test
    fun aCompositorThatKeepsItsOwnSizeIsReadAsItComes() {
        val commands = CopyOnWriteArrayList<List<String>>()
        val feed = VirtualMonitorFeed(91, 1328, 600, gstOk, launch = { command ->
            commands += command
            if (commands.size == 1) FakeGst(ByteArray(0), "streaming stopped, reason not-negotiated (-4)", dies = true)
            else FakeGst(multipart(jpeg(1920, 1080)))
        })
        assertTrue(feed.start(5_000))
        assertEquals(2, commands.size)
        assertTrue("video/x-raw,width=1328,height=600 ! videorate" in commands[0].joinToString(" "))
        assertTrue("videoscale" in commands[1].joinToString(" "))
        assertEquals(1920 to 1080, feed.size)
        feed.stop()
    }

    // ---- The portal's virtual source, on dbus-java's own bus ----

    private fun startBus(): String? {
        val listen = runCatching { TransportBuilder.createDynamicSession("UNIX", true) }.getOrNull() ?: return null
        val started = runCatching { EmbeddedDBusDaemon(listen).apply { startInBackgroundAndWait(5000) } }.getOrNull() ?: return null
        daemon = started
        return listen.replace(",listen=true", "")
    }

    private fun connect(address: String) = DBusConnectionBuilder.forAddress(address).withShared(false).build().also { connections += it }

    private fun service(address: String) = DBusConnectionBuilder.forAddress(address).withShared(false).receivingThreadConfig().withMethodCallThreadCount(1)
        .connectionConfig().build().also { connections += it }

    /** xdg-desktop-portal with a virtual source ([sourceTypes] 7) that answers every Request at once. */
    private class VirtualPortal(val conn: DBusConnection, val sourceTypes: Int) : RemoteDesktopPortal, ScreenCastPortal, Properties {
        val calls = CopyOnWriteArrayList<String>()
        val sources = CopyOnWriteArrayList<Map<String, Variant<*>>>()
        val input = CopyOnWriteArrayList<String>()
        val closed = CopyOnWriteArrayList<String>()
        private val selected = HashMap<String, Int>()

        override fun getObjectPath() = PortalBits.PATH
        private fun iface(): String = AbstractConnectionBase.getCallInfo().`interface`
        private fun sender(): String = AbstractConnectionBase.getCallInfo().source

        private fun respond(token: Variant<*>?, results: Map<String, Variant<*>>): DBusPath {
            val path = PortalBits.requestPath(sender(), token?.value as String)
            Thread {
                Thread.sleep(30)
                conn.sendMessage(PortalRequest.Response(path, UInt32(0), results))
            }.start()
            return DBusPath(path)
        }

        override fun CreateSession(options: Map<String, Variant<*>>): DBusPath {
            val kind = if (iface() == PortalBits.REMOTE_DESKTOP) "rd" else "sc"
            calls += "$kind.CreateSession"
            val handle = PortalBits.sessionPath(sender(), options["session_handle_token"]!!.value as String)
            conn.exportObject(handle, object : PortalSessionObject {
                override fun getObjectPath() = handle
                override fun Close() { closed += handle }
            })
            return respond(options["handle_token"], mapOf("session_handle" to Variant(handle)))
        }

        override fun SelectDevices(session: DBusPath, options: Map<String, Variant<*>>): DBusPath {
            calls += "rd.SelectDevices"
            selected[session.path] = (options["types"]?.value as UInt32).toInt()
            return respond(options["handle_token"], emptyMap())
        }

        override fun SelectSources(session: DBusPath, options: Map<String, Variant<*>>): DBusPath {
            calls += "sc.SelectSources"
            sources += options
            return respond(options["handle_token"], emptyMap())
        }

        override fun Start(session: DBusPath, parentWindow: String, options: Map<String, Variant<*>>): DBusPath {
            val kind = if (iface() == PortalBits.REMOTE_DESKTOP) "rd" else "sc"
            calls += "$kind.Start"
            // A virtual monitor's stream has no size yet: the reader picks it.
            val stream = FakeStream(UInt32(91), mapOf("source_type" to Variant(UInt32(PortalBits.VIRTUAL.toLong()))))
            val results = buildMap<String, Variant<*>> {
                put("streams", Variant(listOf(stream), "a(ua{sv})"))
                if (kind == "rd") put("devices", Variant(UInt32((selected[session.path] ?: 0).toLong())))
            }
            return respond(options["handle_token"], results)
        }

        override fun NotifyPointerMotion(session: DBusPath, options: Map<String, Variant<*>>, dx: Double, dy: Double) {}
        override fun NotifyPointerMotionAbsolute(session: DBusPath, options: Map<String, Variant<*>>, stream: UInt32, x: Double, y: Double) { input += "abs $stream $x,$y" }
        override fun NotifyPointerButton(session: DBusPath, options: Map<String, Variant<*>>, button: Int, state: UInt32) { input += "button $button $state" }
        override fun NotifyPointerAxis(session: DBusPath, options: Map<String, Variant<*>>, dx: Double, dy: Double) {}
        override fun NotifyPointerAxisDiscrete(session: DBusPath, options: Map<String, Variant<*>>, axis: UInt32, steps: Int) {}
        override fun NotifyKeyboardKeycode(session: DBusPath, options: Map<String, Variant<*>>, keycode: Int, state: UInt32) {}
        override fun NotifyKeyboardKeysym(session: DBusPath, options: Map<String, Variant<*>>, keysym: Int, state: UInt32) {}

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A = when {
            propertyName == "version" -> UInt32(if (interfaceName == PortalBits.REMOTE_DESKTOP) 2 else 5)
            propertyName == "AvailableDeviceTypes" -> UInt32(3)
            propertyName == "AvailableSourceTypes" -> UInt32(sourceTypes.toLong())
            propertyName == "AvailableCursorModes" -> UInt32(7)
            else -> throw UnknownProperty("No $propertyName")
        } as A

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {}
        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = emptyMap()
    }

    private fun virtualPortal(address: String, sourceTypes: Int = 7): VirtualPortal {
        val conn = service(address)
        conn.requestBusName(PortalBits.DESKTOP)
        return VirtualPortal(conn, sourceTypes).also { conn.exportObject(PortalBits.PATH, it) }
    }

    @Test
    fun thePortalsVirtualSourceMakesTheMonitorAndTapsLandOnIt() {
        val address = startBus() ?: return
        val portal = virtualPortal(address)
        val client = PortalClient(connect(address), PortalTokens(File(dir, "portal.properties")), answerTimeoutMs = 5_000)
        val commands = CopyOnWriteArrayList<String>()
        val backend = PortalVirtualBackend({ client }, { gstOk }, withControl = { true }, launch = { command ->
            commands += command.joinToString(" ")
            FakeGst(multipart(jpeg(1328, 600)))
        })
        assertEquals(ExtendProbe(ExtendWay.PortalVirtual, ExtendReadiness.Ready), backend.probe())
        assertEquals(ExtendReadiness.NeedsSetup, PortalVirtualBackend({ client }, { GstCheck(launch = true) }, { true }).probe().readiness)

        val shown = backend.open(1328, 600, "Pixel 8")
        // One RemoteDesktop session with the virtual source, so taps land on the new monitor.
        assertEquals(listOf("rd.CreateSession", "rd.SelectDevices", "sc.SelectSources", "rd.Start"), portal.calls)
        assertEquals(PortalBits.VIRTUAL, (portal.sources.single().getValue("types").value as UInt32).toInt())
        assertTrue(commands.single().contains("pipewiresrc path=91 do-timestamp=true ! video/x-raw,width=1328,height=600"), commands.single())
        val display = shown.displays().single()
        assertEquals(1328 to 600, display.width to display.height, "Its size, from the picture")
        assertEquals(1328, shown.grab(display, 1920)!!.width)

        assertTrue(shown.pointer("click", 0.5f, 1f))
        waitFor { portal.input.size == 3 }
        assertEquals(listOf("abs 91 663.5,599.0", "button ${Evdev.BTN_LEFT} 1", "button ${Evdev.BTN_LEFT} 0"), portal.input.toList())
        assertTrue(shown.pointer("right", 0f, 0f))
        waitFor { portal.input.size == 6 }
        assertEquals("button ${Evdev.BTN_RIGHT} 1", portal.input[4])

        // Stopping ends the session: the compositor takes the monitor away.
        shown.release()
        waitFor { portal.closed.isNotEmpty() }
        assertTrue(portal.closed.single().contains("/session/"))

        // Without control it's a screen cast alone, and taps are dropped (the monitor has no place in desktop pixels).
        portal.calls.clear()
        val alone = PortalVirtualBackend({ client }, { gstOk }, withControl = { false }, launch = { FakeGst(multipart(jpeg(600, 1328))) }).open(600, 1328, "Pixel 8")
        assertEquals(listOf("sc.CreateSession", "sc.SelectSources", "sc.Start"), portal.calls)
        assertTrue(alone.pointer("click", 0.5f, 0.5f), "Taken, so it never lands on another monitor")
        alone.release()
    }

    @Test
    fun aPortalWithoutAVirtualSourceIsNotUsed() {
        val address = startBus() ?: return
        virtualPortal(address, sourceTypes = 3)
        val client = PortalClient(connect(address), PortalTokens(File(dir, "portal.properties")))
        val probe = PortalVirtualBackend({ client }, { gstOk }, { true }).probe()
        assertEquals(ExtendProbe(ExtendWay.PortalVirtual, ExtendReadiness.Unavailable, ExtendProblem.PortalNoVirtual), probe)
        assertEquals(ExtendProblem.NoPortal, PortalVirtualBackend({ null }, { gstOk }, { true }).probe().problem)
    }

    // ---- GNOME's Mutter API, with a fake Mutter ----

    private class FakeMutter(val conn: DBusConnection) {
        val calls = CopyOnWriteArrayList<String>()
        val input = CopyOnWriteArrayList<String>()
        @Volatile var refuse = false
        @Volatile var noVirtual = false
        private var count = 0
        private val casts = java.util.concurrent.ConcurrentHashMap<String, Cast>()

        inner class Cast(private val path: String, val rdId: String?) : MutterScreenCastSession {
            @Volatile var stream: String? = null
            override fun getObjectPath() = path
            override fun RecordVirtual(properties: Map<String, Variant<*>>): DBusPath {
                if (noVirtual) throw UnknownMethod("No such method RecordVirtual")
                calls += "sc.RecordVirtual cursor=${(properties["cursor-mode"]?.value as UInt32).toInt()}"
                return DBusPath("$path/Stream").also { stream = it.path }
            }
            override fun Start() { calls += "sc.Start"; added() }
            override fun Stop() { calls += "sc.Stop" }
            fun added() {
                val s = stream ?: return
                Thread { Thread.sleep(30); conn.sendMessage(MutterScreenCastStream.PipeWireStreamAdded(s, UInt32(77))) }.start()
            }
        }

        val screenCast: MutterScreenCast = object : MutterScreenCast, Properties {
            override fun getObjectPath() = MutterBits.SCREEN_CAST_PATH
            override fun CreateSession(properties: Map<String, Variant<*>>): DBusPath {
                if (refuse) throw AccessDenied("Not allowed")
                val rd = properties["remote-desktop-session-id"]?.value as String?
                calls += "sc.CreateSession rd=$rd"
                val path = "${MutterBits.SCREEN_CAST_PATH}/Session/u${++count}"
                val cast = Cast(path, rd)
                casts[path] = cast
                conn.exportObject(path, cast)
                return DBusPath(path)
            }
            @Suppress("UNCHECKED_CAST")
            override fun <A : Any?> Get(interfaceName: String, propertyName: String): A = (if (propertyName == "Version") 4 else throw UnknownProperty("No")) as A
            override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {}
            override fun GetAll(interfaceName: String): Map<String, Variant<*>> = emptyMap()
        }

        inner class Remote(private val path: String, val id: String) : MutterRemoteDesktopSession, Properties {
            override fun getObjectPath() = path
            override fun Start() {
                calls += "rd.Start"
                casts.values.filter { it.rdId == id }.forEach { it.added() }
            }
            override fun Stop() { calls += "rd.Stop" }
            override fun NotifyPointerMotionAbsolute(stream: String, x: Double, y: Double) { input += "abs ${stream.substringAfterLast('/')} $x,$y" }
            override fun NotifyPointerButton(button: Int, state: Boolean) { input += "button $button $state" }
            @Suppress("UNCHECKED_CAST")
            override fun <A : Any?> Get(interfaceName: String, propertyName: String): A = (if (propertyName == "SessionId") id else throw UnknownProperty("No")) as A
            override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {}
            override fun GetAll(interfaceName: String): Map<String, Variant<*>> = emptyMap()
        }

        val remoteDesktop: MutterRemoteDesktop = object : MutterRemoteDesktop {
            override fun getObjectPath() = MutterBits.REMOTE_DESKTOP_PATH
            override fun CreateSession(): DBusPath {
                calls += "rd.CreateSession"
                val path = "${MutterBits.REMOTE_DESKTOP_PATH}/Session/r${++count}"
                conn.exportObject(path, Remote(path, "rd-$count"))
                return DBusPath(path)
            }
        }
    }

    private fun fakeMutter(address: String): FakeMutter {
        // One thread for calls, as Mutter takes them in order on its main loop.
        val conn = service(address)
        conn.requestBusName(MutterBits.SCREEN_CAST)
        conn.requestBusName(MutterBits.REMOTE_DESKTOP)
        return FakeMutter(conn).also {
            conn.exportObject(MutterBits.SCREEN_CAST_PATH, it.screenCast)
            conn.exportObject(MutterBits.REMOTE_DESKTOP_PATH, it.remoteDesktop)
        }
    }

    @Test
    fun mutterRecordsAVirtualMonitorLinkedToARemoteDesktopSession() {
        val address = startBus() ?: return
        val mutter = fakeMutter(address)
        val client = connect(address)
        val commands = CopyOnWriteArrayList<String>()
        val backend = MutterVirtualBackend({ client }, { gstOk }, withControl = { true }, gnomeVersion = { 47 }, launch = { command ->
            commands += command.joinToString(" ")
            FakeGst(multipart(jpeg(1328, 600)))
        })
        assertEquals(ExtendReadiness.Ready, backend.probe().readiness)
        assertEquals(ExtendProblem.GnomeTooOld, MutterVirtualBackend({ client }, { gstOk }, { true }, { 3 }).probe().problem)
        assertEquals(ExtendProblem.NoMutter, MutterVirtualBackend({ null }, { gstOk }, { true }, { 47 }).probe().problem)

        val shown = backend.open(1328, 600, "Pixel 8")
        assertEquals(
            listOf("rd.CreateSession", "sc.CreateSession rd=rd-1", "sc.RecordVirtual cursor=1", "rd.Start"),
            mutter.calls.toList(), "The remote desktop session starts both",
        )
        assertTrue(commands.single().contains("pipewiresrc path=77 do-timestamp=true ! video/x-raw,width=1328,height=600"))
        assertEquals(1328, shown.displays().single().width)
        shown.pointer("double", 1f, 0.5f)
        waitFor { mutter.input.size == 3 }
        assertEquals(listOf("abs Stream 1327.0,299.5", "button ${Evdev.BTN_LEFT} true", "button ${Evdev.BTN_LEFT} false"), mutter.input.toList())
        shown.release()
        waitFor { "rd.Stop" in mutter.calls }

        // Without control: the screen cast alone, started itself.
        mutter.calls.clear()
        val alone = MutterVirtualBackend({ client }, { gstOk }, { false }, { 47 }, launch = { FakeGst(multipart(jpeg(600, 1328))) }).open(600, 1328, "Pixel 8")
        assertEquals(listOf("sc.CreateSession rd=null", "sc.RecordVirtual cursor=1", "sc.Start"), mutter.calls.toList())
        alone.release()
        waitFor { "sc.Stop" in mutter.calls }
    }

    @Test
    fun aGnomeThatRefusesOrLacksItFallsThroughToTheNextWay() {
        val address = startBus() ?: return
        val mutter = fakeMutter(address)
        val client = connect(address)
        val backend = MutterVirtualBackend({ client }, { gstOk }, { false }, { 47 }, launch = { FakeGst(multipart(jpeg(1328, 600))) })
        mutter.refuse = true
        assertEquals(ExtendProblem.MutterRefused, assertFailsWith<ExtendUnavailable> { backend.open(1328, 600, "Pixel") }.problem)
        mutter.refuse = false
        mutter.noVirtual = true
        assertEquals(ExtendProblem.GnomeTooOld, assertFailsWith<ExtendUnavailable> { backend.open(1328, 600, "Pixel") }.problem)
        assertTrue("sc.Stop" in mutter.calls, "The half-made session is stopped")
        assertEquals(46, MutterBits.shellVersion("GNOME Shell 46.2\n"))
        assertNull(MutterBits.shellVersion("bash: gnome-shell: command not found"))
    }

    // ---- X11: xrandr and cvt ----

    private val query = """
        Screen 0: minimum 8 x 8, current 1920 x 1080, maximum 32767 x 32767
        eDP1 connected primary 1920x1080+0+0 (normal left inverted right x axis y axis) 344mm x 194mm
           1920x1080     60.02*+  59.93
           1680x1050     59.95    59.88
        DP1 disconnected (normal left inverted right x axis y axis)
        HDMI2 connected 2560x1440+1920+0 left (normal left inverted right x axis y axis) 597mm x 336mm
           2560x1440     59.95*+
        VIRTUAL1 disconnected (normal left inverted right x axis y axis)
    """.trimIndent()

    @Test
    fun xrandrOutputsAndCvtModelinesAreRead() {
        val outputs = Xrandr.parse(query)
        assertEquals(
            listOf(
                XrandrOutput("eDP1", true, true, Rectangle(0, 0, 1920, 1080)),
                XrandrOutput("DP1", false, false, null),
                XrandrOutput("HDMI2", true, false, Rectangle(1920, 0, 2560, 1440)),
                XrandrOutput("VIRTUAL1", false, false, null),
            ),
            outputs,
        )
        assertEquals("VIRTUAL1", Xrandr.virtualOutput(outputs)?.name)
        assertEquals("eDP1", Xrandr.anchor(outputs, "VIRTUAL1")?.name)
        assertNull(Xrandr.virtualOutput(Xrandr.parse(query.replace("VIRTUAL1", "DP2"))), "Only a VIRTUAL output adds room")
        // Turned on, the virtual output says where it is even while "disconnected".
        val on = Xrandr.parse("VIRTUAL1 disconnected 1328x600+1920+0 (normal left inverted right x axis y axis) 0mm x 0mm")
        assertEquals(Rectangle(1920, 0, 1328, 600), on.single().geometry)

        val cvt = "# 1328x600 59.86 Hz (CVT) hsync: 37.26 kHz; pclk: 64.00 MHz\nModeline \"1328x600_60.00\"   64.00  1328 1384 1512 1696  600 603 613 631 -hsync +vsync\n"
        val mode = Xrandr.cvt(cvt)!!
        assertEquals("1328x600_60.00", mode.name)
        assertEquals(listOf("64.00", "1328", "1384", "1512", "1696", "600", "603", "613", "631", "-hsync", "+vsync"), mode.args)
        assertEquals(1328 to 600, mode.width to mode.height)
        assertNull(Xrandr.cvt("cvt: command not found"))
        assertNull(Xrandr.cvt("Modeline \"x\" abc 1 2"))
    }

    @Test
    fun theVirtualOutputIsTurnedOnBesideTheMainOneAndOffAgain() {
        val commands = CopyOnWriteArrayList<String>()
        var turnedOn = false
        val run: (List<String>) -> Pair<Int, String>? = { command ->
            val line = command.joinToString(" ")
            commands += line
            when {
                line == "xrandr --query" -> 0 to (if (turnedOn) query.replace("VIRTUAL1 disconnected", "VIRTUAL1 disconnected 1328x600+1920+0") else query)
                line.startsWith("cvt 1328 600 60") -> 0 to "Modeline \"1328x600_60.00\"   64.00  1328 1384 1512 1696  600 603 613 631 -hsync +vsync"
                line.startsWith("xrandr --output VIRTUAL1 --mode") -> { turnedOn = true; 0 to "" }
                line.startsWith("xrandr --output VIRTUAL1 --off") -> { turnedOn = false; 0 to "" }
                else -> 0 to ""
            }
        }
        val backend = X11OutputBackend({ true }, run) { true }
        assertEquals(ExtendReadiness.Ready, backend.probe().readiness)
        assertEquals(ExtendProblem.X11NoVirtualOutput, X11OutputBackend({ true }, { 0 to query.replace("VIRTUAL1", "DP2") }) { true }.probe().problem)
        assertEquals(ExtendProblem.NoXrandr, X11OutputBackend({ false }, run) { true }.probe().problem)
        assertEquals(ExtendProblem.NoCvt, X11OutputBackend({ it != "cvt" }, run) { true }.probe().problem)

        commands.clear()
        val shown = backend.open(1328, 600, "Pixel 8")
        val on = commands.filter { "--newmode" in it || "--addmode" in it || "--mode" in it }
        assertEquals(
            listOf(
                "xrandr --newmode DyskLink-1328x600 64.00 1328 1384 1512 1696 600 603 613 631 -hsync +vsync",
                "xrandr --addmode VIRTUAL1 DyskLink-1328x600",
                "xrandr --output VIRTUAL1 --mode DyskLink-1328x600 --right-of eDP1",
            ),
            on,
        )
        // Taps land on it in desktop pixels, right of the laptop's screen.
        val display = shown.displays().single()
        assertEquals(Rectangle(1920, 0, 1328, 600), Rectangle(display.x, display.y, display.width, display.height))
        assertFalse(shown.pointer("click", 0.5f, 0.5f), "X11 places taps itself, in desktop pixels")
        assertEquals(1920 + 664 to 300, PcScreen.toDesktop(display, 0.5f, 0.5f))
        commands.clear()
        shown.release()
        assertEquals(listOf("xrandr --output VIRTUAL1 --off", "xrandr --delmode VIRTUAL1 DyskLink-1328x600", "xrandr --rmmode DyskLink-1328x600"), commands.toList())
        shown.release()
        assertEquals(3, commands.size, "Only once")
    }

    // ---- Windows: the driver's settings, the display calls, and finding the monitor ----

    private val shippedSettings = """
        <?xml version='1.0' encoding='utf-8'?>
        <vdd_settings>
            <monitors>
                <count>1</count>
            </monitors>
            <gpu>
                <friendlyname>default</friendlyname>
            </gpu>
        	<global>
        		<!--These are global refreshrates, any you add in here, will be replicated to all resolutions-->
        		<g_refresh_rate>60</g_refresh_rate>
        		<g_refresh_rate>90</g_refresh_rate>
        		<g_refresh_rate>244</g_refresh_rate>
        	</global>
            <resolutions>
                <resolution>
                    <width>800</width>
                    <height>600</height>
                    <refresh_rate>30</refresh_rate>
                </resolution>
                <resolution>
                    <width>1920</width>
                    <height>1080</height>
                    <refresh_rate>30</refresh_rate>
                </resolution>
            </resolutions>
            <options>
        		<CustomEdid>false</CustomEdid>
        		<HardwareCursor>true</HardwareCursor>
        		<logging>false</logging>
        	</options>
        </vdd_settings>
    """.trimIndent()

    @Test
    fun theDriversSettingsGetThePhonesSizeAndKeepTheRest() {
        val written = VddSettings.write(shippedSettings, 1328, 600)
        assertEquals(listOf(1328 to 600), VddSettings.resolutions(written), "The only mode, so the preferred one")
        assertTrue("<count>1</count>" in written)
        assertTrue("<refresh_rate>60</refresh_rate>" in written)
        assertEquals(1, Regex("<g_refresh_rate>").findAll(written).count(), "One rate, so one mode")
        assertTrue("<HardwareCursor>true</HardwareCursor>" in written && "<friendlyname>default</friendlyname>" in written)
        assertEquals(written, VddSettings.write(written, 1328, 600), "Writing it again changes nothing")
        val two = VddSettings.write(shippedSettings.replace("<count>1</count>", "<count>3</count>"), 600, 1328)
        assertTrue("<count>1</count>" in two)
        assertEquals(listOf(600 to 1328), VddSettings.resolutions(two))
        // No file yet, or one that isn't the driver's: its default with the phone's size.
        val fresh = VddSettings.write(null, 1000, 450)
        assertTrue(fresh.startsWith("<?xml") && "<vdd_settings>" in fresh)
        assertEquals(listOf(1000 to 450), VddSettings.resolutions(fresh))
        assertEquals(listOf(1000 to 450), VddSettings.resolutions(VddSettings.write("garbage", 1000, 450)))
    }

    @Test
    fun displayStructsAreLaidOutAsWindowsWantsThem() {
        val mode = WinStructs.devMode(2560, -120, 1328, 600)
        assertEquals(220, mode.size)
        val b = java.nio.ByteBuffer.wrap(mode).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(220, b.getShort(68).toInt(), "dmSize")
        assertEquals(WinStructs.DM_POSITION or WinStructs.DM_PELSWIDTH or WinStructs.DM_PELSHEIGHT, b.getInt(72))
        assertEquals(Rectangle(2560, -120, 1328, 600), WinStructs.bounds(mode))
        assertEquals(Rectangle(0, 0, 0, 0), WinStructs.bounds(WinStructs.devMode(0, 0, 0, 0)), "Off the desktop")

        val device = WinStructs.emptyDisplayDevice()
        val d = java.nio.ByteBuffer.wrap(device).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(840, d.getInt(0))
        fun put(offset: Int, text: String) = text.forEachIndexed { i, c -> d.putChar(offset + i * 2, c) }
        put(4, "\\\\.\\DISPLAY5"); put(68, "Virtual Display Driver"); put(328, "Root\\MttVDD")
        d.putInt(324, DisplaySource.ATTACHED)
        val source = WinStructs.displayDevice(device)
        assertEquals(DisplaySource("\\\\.\\DISPLAY5", "Virtual Display Driver", "Root\\MttVDD", 1), source)
        assertTrue(source.attached && !source.primary && Vdd.isOurs(source))
        assertFalse(Vdd.isOurs(DisplaySource("\\\\.\\DISPLAY1", "NVIDIA GeForce RTX 3060", "PCI\\VEN_10DE", 5)))
        assertTrue(Vdd.isOurs(DisplaySource("\\\\.\\DISPLAY9", "Something", "ROOT\\MTTVDD\\0000", 0)))
    }

    @Test
    fun theMonitorIsFoundInJavasScaledCoordinates() {
        val main = AwtMonitor("\\Display0", Rectangle(0, 0, 1536, 864), 1.25, 1.25)
        val phone = AwtMonitor("\\Display1", Rectangle(1536, 0, 1328, 600), 1.0, 1.0)
        val other = AwtMonitor("\\Display2", Rectangle(-1328, 0, 1328, 600), 1.0, 1.0)
        // The new monitor sits right of the 1920-pixel-wide main one at 125 %: 1920 device pixels in.
        assertEquals(phone, MonitorMatch.pick(listOf(main, other, phone), Rectangle(1920, 0, 1328, 600)))
        assertEquals(main, MonitorMatch.pick(listOf(main, phone), Rectangle(0, 0, 1920, 1080)), "Scaled screens are compared in device pixels")
        assertNull(MonitorMatch.pick(listOf(main), Rectangle(1920, 0, 1328, 600)), "Not there yet")
        assertEquals(phone, MonitorMatch.pick(listOf(phone), Rectangle(1921, 1, 1329, 600)), "A pixel off is the same")
        assertEquals(3840 to 0, MonitorMatch.rightOf(listOf(Rectangle(0, 0, 1920, 1080), Rectangle(1920, -200, 1920, 1080))))
        assertEquals(0 to 0, MonitorMatch.rightOf(emptyList()))
    }

    @Test
    fun tapsOnAVirtualMonitorAreInItsOwnPixels() {
        val steps = mutableListOf<String>()
        VirtualTaps.at("click", 0.25f, 0.5f, 1328, 600, { x, y -> steps += "move $x,$y" }) { b, down -> steps += "b $b $down" }
        assertEquals(listOf("move 331.75,299.5", "b ${Evdev.BTN_LEFT} true", "b ${Evdev.BTN_LEFT} false"), steps)
        steps.clear()
        VirtualTaps.at("drag", 2f, -1f, 1328, 600, { x, y -> steps += "move $x,$y" }) { b, down -> steps += "b $b $down" }
        assertEquals(listOf("move 1327.0,0.0"), steps, "Kept on the monitor")
        steps.clear()
        VirtualTaps.at("teleport", 0.5f, 0.5f, 1328, 600, { _, _ -> steps += "move" }) { _, _ -> steps += "b" }
        assertTrue(steps.isEmpty())
    }

    /** Windows as the backend sees it: one GPU, the driver's source, and Java's screens. */
    private class FakeWindows : VddSystem {
        var installed = true
        var settings: String? = null
        var modes = mutableSetOf<Pair<Int, Int>>()
        var placed: Rectangle? = null
        val calls = CopyOnWriteArrayList<String>()
        var awtScreens = { listOf(AwtMonitor("\\Display0", Rectangle(0, 0, 1536, 864), 1.25, 1.25)) + listOfNotNull(placed?.let { AwtMonitor("\\Display1", Rectangle(1536, 0, it.width, it.height)) }) }

        override fun sources(): List<DisplaySource> = listOfNotNull(
            DisplaySource("\\\\.\\DISPLAY1", "Intel(R) UHD Graphics", "PCI\\VEN_8086", DisplaySource.ATTACHED or DisplaySource.PRIMARY),
            DisplaySource("\\\\.\\DISPLAY5", "Virtual Display Driver", "Root\\MttVDD", if (placed != null) DisplaySource.ATTACHED else 0).takeIf { installed },
        )
        override fun current(source: String): Rectangle? = when (source) {
            "\\\\.\\DISPLAY1" -> Rectangle(0, 0, 1920, 1080)
            else -> placed
        }
        override fun hasMode(source: String, width: Int, height: Int) = (width to height) in modes
        override fun place(source: String, x: Int, y: Int, width: Int, height: Int): Boolean {
            calls += "place $source $x,$y ${width}x$height"
            placed = if (width == 0) null else Rectangle(x, y, width, height)
            return true
        }
        override fun readSettings() = settings
        override fun writeSettings(text: String): Boolean { calls += "write"; settings = text; return true }
        override fun reload(): Boolean {
            calls += "reload"
            settings?.let { modes = VddSettings.resolutions(it).toMutableSet() }
            return true
        }
        override fun awt() = awtScreens()
        override fun sleep(ms: Long) {}
    }

    @Test
    fun windowsGetsTheModeTheMonitorAndLosesItAgain() {
        val windows = FakeWindows().apply { settings = shippedSettings; modes = mutableSetOf(800 to 600, 1920 to 1080) }
        var experiment = false
        val marker = File(dir, "vdd-attached")
        val bundle = File(dir, "vdd")
        val backend = WindowsVddBackend({ experiment }, { bundle }, windows, marker, waitMs = 500)
        assertEquals(ExtendProblem.ExperimentOff, backend.probe().problem)
        experiment = true
        assertEquals(ExtendReadiness.Ready, backend.probe().readiness)
        windows.installed = false
        assertEquals(ExtendProbe(ExtendWay.WindowsDriver, ExtendReadiness.NeedsSetup, ExtendProblem.DriverMissing), backend.probe())
        assertEquals(ExtendProblem.DriverNotInCopy, WindowsVddBackend({ true }, { null }, windows).probe().problem)
        assertTrue(assertFailsWith<PcExtendFailed> { backend.open(1328, 600, "Pixel") }.message!!.contains("Install the virtual display driver"))
        windows.installed = true

        val shown = backend.open(1328, 600, "Pixel 8")
        assertEquals(listOf("write", "reload", "place \\\\.\\DISPLAY5 1920,0 1328x600"), windows.calls.toList(), "Right of the main screen, in device pixels")
        assertEquals(listOf(1328 to 600), VddSettings.resolutions(windows.settings!!))
        assertEquals("\\\\.\\DISPLAY5", marker.readText())
        // Captured and tapped where Java has it: right of the 125 % main screen.
        val display = shown.displays().single()
        assertEquals(Rectangle(1536, 0, 1328, 600), Rectangle(display.x, display.y, display.width, display.height))
        assertFalse(shown.pointer("click", 0.5f, 0.5f), "Windows places taps in desktop pixels")
        assertEquals(1536 + 664 to 300, PcScreen.toDesktop(display, 0.5f, 0.5f))

        windows.calls.clear()
        shown.release()
        assertEquals(listOf("place \\\\.\\DISPLAY5 0,0 0x0"), windows.calls.toList(), "Off the desktop between uses")
        assertFalse(marker.exists())

        // The same size again: no new settings, no reload.
        windows.calls.clear()
        backend.open(1328, 600, "Pixel 8").release()
        assertEquals(listOf("place \\\\.\\DISPLAY5 1920,0 1328x600", "place \\\\.\\DISPLAY5 0,0 0x0"), windows.calls.toList())

        // A monitor left on by a DyskLink that was closed while extending comes off at the next start.
        windows.place("\\\\.\\DISPLAY5", 1920, 0, 1328, 600)
        marker.writeText("\\\\.\\DISPLAY5")
        backend.tidyUp()
        assertNull(windows.placed)
        assertFalse(marker.exists())
        // One the user put there without DyskLink stays.
        windows.place("\\\\.\\DISPLAY5", 1920, 0, 1328, 600)
        backend.tidyUp()
        assertNotNull(windows.placed)
    }

    @Test
    fun windowsThatNeverShowsTheMonitorToJavaIsUndone() {
        val windows = FakeWindows().apply { settings = shippedSettings; awtScreens = { listOf(AwtMonitor("\\Display0", Rectangle(0, 0, 1920, 1080))) } }
        val backend = WindowsVddBackend({ true }, { null }, windows, waitMs = 50)
        assertTrue(assertFailsWith<PcExtendFailed> { backend.open(1328, 600, "Pixel") }.message!!.contains("couldn't find it"))
        assertNull(windows.placed, "Taken off again")
    }

    @Test
    fun theElevatedScriptsFollowTheDriversOwnInstall() {
        val install = VddInstall.installScript("C:\\Program Files\\DyskLink\\app\\resources\\vdd", "S-1-5-21-1-2-3-1001")
        assertTrue("'C:\\Program Files\\DyskLink\\app\\resources\\vdd'" in install)
        assertTrue("X509Store('TrustedPublisher', 'LocalMachine')" in install && "\$store.Add(\$c)" in install)
        assertTrue("install (Join-Path \$driver 'MttVDD.inf') 'Root\\MttVDD'" in install)
        assertTrue("icacls \$conf /grant '*S-1-5-21-1-2-3-1001:(OI)(CI)M'" in install)
        assertFalse("icacls" in VddInstall.installScript("C:\\x", "S-1-5-21'; Remove-Item C:\\"), "Only a real SID goes into the script")
        val remove = VddInstall.removeScript("C:\\it's here")
        assertTrue("'C:\\it''s here'" in remove)
        assertTrue("--remove-device-node --hardware-id 'Root\\MttVDD' --class-guid '4D36E968-E325-11CE-BFC1-08002BE10318'" in remove)
        assertTrue("\$store.Remove(\$c)" in remove)
        assertTrue(VddInstall.elevated("C:\\Temp\\a.ps1").contains("-Verb RunAs -Wait -PassThru"))
    }

    // ---- The hints ----

    @Test
    fun eachProblemHasItsHint() {
        fun hints(vararg probes: ExtendProbe, distro: Distro = Distro.Debian, desktop: String? = "ubuntu:GNOME", vdd: Boolean = false) =
            HostChecks.secondDisplayHints(probes.toList(), distro, desktop, vdd)
        fun p(way: ExtendWay, r: ExtendReadiness, problem: ExtendProblem? = null) = ExtendProbe(way, r, problem)
        assertTrue(HostChecks.secondDisplayHints(null, Distro.Debian, null).isEmpty(), "Nothing while unknown")
        assertTrue(hints(p(ExtendWay.PortalVirtual, ExtendReadiness.Ready)).isEmpty(), "Nothing while it works")
        val gst = hints(p(ExtendWay.PortalVirtual, ExtendReadiness.NeedsSetup, ExtendProblem.NoGstreamer)).single()
        assertEquals("sudo apt install gstreamer1.0-tools gstreamer1.0-pipewire gstreamer1.0-plugins-good", gst.command)
        val old = hints(p(ExtendWay.PortalVirtual, ExtendReadiness.Unavailable, ExtendProblem.PortalNoVirtual), p(ExtendWay.MutterVirtual, ExtendReadiness.Unavailable, ExtendProblem.GnomeTooOld)).single()
        assertEquals("This GNOME can't add a virtual monitor", old.title)
        val kde = hints(p(ExtendWay.PortalVirtual, ExtendReadiness.Unavailable, ExtendProblem.PortalNoVirtual), p(ExtendWay.MutterVirtual, ExtendReadiness.Unavailable, ExtendProblem.NoMutter), desktop = "KDE").single()
        assertTrue("KDE Plasma 6" in kde.text)
        val x11 = hints(p(ExtendWay.X11Output, ExtendReadiness.Unavailable, ExtendProblem.X11NoVirtualOutput)).single()
        assertTrue("VIRTUAL1" in x11.text && "Wayland" in x11.text && x11.command == null, "No root actions by DyskLink")
        assertEquals("sudo dnf install xrandr", hints(p(ExtendWay.X11Output, ExtendReadiness.Unavailable, ExtendProblem.NoXrandr), distro = Distro.Fedora).single().command)
        val driver = hints(p(ExtendWay.WindowsDriver, ExtendReadiness.NeedsSetup, ExtendProblem.DriverMissing), distro = Distro.Other, desktop = null).single()
        assertEquals(HintAction("Install driver", HostChecks.ACTION_VDD_INSTALL), driver.action)
        assertTrue(Vdd.RELEASE in driver.text)
        val installed = hints(p(ExtendWay.WindowsDriver, ExtendReadiness.Ready), distro = Distro.Other, desktop = null, vdd = true).single()
        assertEquals(HostChecks.ACTION_VDD_REMOVE, installed.action?.id)
        assertTrue(hints(p(ExtendWay.WindowsDriver, ExtendReadiness.Unavailable, ExtendProblem.ExperimentOff)).isEmpty(), "The card's switch says it")
        // Through the full checks: the Second display card gets them on Windows and Linux.
        val win = HostChecks.hints(HostEnv(windows = true, linux = false, extend = listOf(p(ExtendWay.WindowsDriver, ExtendReadiness.NeedsSetup, ExtendProblem.DriverMissing))))
        assertEquals(HostChecks.ACTION_VDD_INSTALL, win.secondDisplay.single().action?.id)
        val linux = HostChecks.hints(HostEnv(windows = false, linux = true, env = mapOf("XDG_SESSION_TYPE" to "x11")::get, extend = listOf(p(ExtendWay.X11Output, ExtendReadiness.Unavailable, ExtendProblem.X11NoVirtualOutput))))
        assertEquals("This X11 session has no spare output", linux.secondDisplay.single().title)
    }

    @Test
    fun theCardSaysWhereThingsStand() {
        val ready = listOf(ExtendProbe(ExtendWay.PortalVirtual, ExtendReadiness.Ready))
        val view = SecondDisplayView(ready, screenOn = true, checking = false)
        assertEquals(SecondDisplayState.Available, view.state)
        assertTrue("asks you to allow it" in id.dysklink.desktop.ui.secondDisplayText(view, windows = false))
        assertTrue("Let phones see this screen" in id.dysklink.desktop.ui.secondDisplayText(view.copy(screenOn = false), windows = false))
        val active = SecondDisplayView.of(ready, false, id.phonelink.android.core.HubExtend("p1", 1328, 600, true), { "Pixel 8" }, true, false, false)
        assertEquals(SecondDisplayState.Active, active.state)
        assertTrue(id.dysklink.desktop.ui.secondDisplayText(active, windows = true).startsWith("Pixel 8 is a monitor of this PC now"))
        assertEquals(SecondDisplayState.Checking, SecondDisplayView().state)
        assertEquals(SecondDisplayState.NeedsSetup, SecondDisplayView(listOf(ExtendProbe(ExtendWay.WindowsDriver, ExtendReadiness.NeedsSetup, ExtendProblem.DriverMissing)), checking = false).state)
        val off = SecondDisplayView(listOf(ExtendProbe(ExtendWay.WindowsDriver, ExtendReadiness.Unavailable, ExtendProblem.ExperimentOff)), checking = false, experimentShown = true)
        assertTrue("Turn on the driver below" in id.dysklink.desktop.ui.secondDisplayText(off, windows = true))
    }
}
