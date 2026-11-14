package id.dysklink.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.bin.EmbeddedDBusDaemon
import org.freedesktop.dbus.connections.base.AbstractConnectionBase
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.connections.transports.TransportBuilder
import org.freedesktop.dbus.errors.UnknownProperty
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/** (ua{sv}): a stream as the portal sends it. */
class FakeStream(
    @field:Position(0) @JvmField val node: UInt32,
    @field:Position(1) @JvmField val props: Map<String, Variant<*>>,
) : Struct()

class SizeStruct(@field:Position(0) @JvmField val a: Int, @field:Position(1) @JvmField val b: Int) : Struct()

/**
 * The portal flow against dbus-java's own bus daemon, with a fake xdg-desktop-portal that answers every Request with
 * a Response signal, as the real one does once the user clicks in its dialog.
 */
class WaylandPortalTest {
    private var daemon: EmbeddedDBusDaemon? = null
    private val connections = mutableListOf<DBusConnection>()
    private val dir: File = Files.createTempDirectory("dysk-portal").toFile()

    @AfterTest
    fun stop() {
        connections.forEach { runCatching { it.close() } }
        runCatching { daemon?.close() }
        dir.deleteRecursively()
    }

    private fun startBus(): String? {
        val listen = runCatching { TransportBuilder.createDynamicSession("UNIX", true) }.getOrNull() ?: return null
        val started = runCatching { EmbeddedDBusDaemon(listen).apply { startInBackgroundAndWait(5000) } }.getOrNull() ?: return null
        daemon = started
        return listen.replace(",listen=true", "")
    }

    private fun connect(address: String) = DBusConnectionBuilder.forAddress(address).withShared(false).build().also { connections += it }

    /** Records what DyskLink asked; [answer] is the Response code for Start (0 yes, 1 cancelled). */
    private class FakePortal(val conn: DBusConnection, val remoteVersion: Int?, val castVersion: Int = 5) : RemoteDesktopPortal, ScreenCastPortal, Properties {
        @Volatile var answer = 0
        /** Start never answers, as a dialog nobody clicks. */
        @Volatile var silentStart = false
        val calls = CopyOnWriteArrayList<String>()
        val options = CopyOnWriteArrayList<Pair<String, Map<String, Variant<*>>>>()
        val input = CopyOnWriteArrayList<String>()
        @Volatile var issuedToken = "token-1"
        private var sessions = 0
        private val selected = HashMap<String, Int>()
        private val withScreen = HashSet<String>()

        override fun getObjectPath() = PortalBits.PATH

        private fun iface(): String = AbstractConnectionBase.getCallInfo().`interface`
        private fun sender(): String = AbstractConnectionBase.getCallInfo().source

        private fun respond(token: Variant<*>?, code: Int, results: Map<String, Variant<*>>): DBusPath {
            val path = PortalBits.requestPath(sender(), token?.value as String)
            Thread {
                Thread.sleep(50)
                conn.sendMessage(PortalRequest.Response(path, UInt32(code.toLong()), results))
            }.start()
            return DBusPath(path)
        }

        override fun CreateSession(options: Map<String, Variant<*>>): DBusPath {
            val kind = if (iface() == PortalBits.REMOTE_DESKTOP) "rd" else "sc"
            calls += "$kind.CreateSession"
            this.options += "$kind.CreateSession" to options
            val handle = PortalBits.sessionPath(sender(), options["session_handle_token"]!!.value as String)
            sessions++
            return respond(options["handle_token"], 0, mapOf("session_handle" to Variant(handle)))
        }

        override fun SelectDevices(session: DBusPath, options: Map<String, Variant<*>>): DBusPath {
            calls += "rd.SelectDevices"
            this.options += "rd.SelectDevices" to options
            selected[session.path] = (options["types"]?.value as UInt32).toInt()
            return respond(options["handle_token"], 0, emptyMap())
        }

        override fun SelectSources(session: DBusPath, options: Map<String, Variant<*>>): DBusPath {
            calls += "sc.SelectSources"
            this.options += "sc.SelectSources" to options
            withScreen += session.path
            return respond(options["handle_token"], 0, emptyMap())
        }

        override fun Start(session: DBusPath, parentWindow: String, options: Map<String, Variant<*>>): DBusPath {
            val kind = if (iface() == PortalBits.REMOTE_DESKTOP) "rd" else "sc"
            calls += "$kind.Start"
            val results = buildMap<String, Variant<*>> {
                put("restore_token", Variant(issuedToken))
                if (kind == "rd") put("devices", Variant(UInt32((selected[session.path] ?: 0).toLong())))
                if (session.path in withScreen) {
                    val stream = FakeStream(
                        UInt32(57),
                        mapOf("position" to Variant(SizeStruct(0, 0), "(ii)"), "size" to Variant(SizeStruct(2560, 1440), "(ii)")),
                    )
                    put("streams", Variant(listOf(stream), "a(ua{sv})"))
                }
            }
            if (silentStart) return DBusPath(PortalBits.requestPath(sender(), options["handle_token"]!!.value as String))
            return respond(options["handle_token"], answer, if (answer == 0) results else emptyMap())
        }

        override fun NotifyPointerMotion(session: DBusPath, options: Map<String, Variant<*>>, dx: Double, dy: Double) { input += "motion $dx,$dy" }
        override fun NotifyPointerMotionAbsolute(session: DBusPath, options: Map<String, Variant<*>>, stream: UInt32, x: Double, y: Double) { input += "abs $stream $x,$y" }
        override fun NotifyPointerButton(session: DBusPath, options: Map<String, Variant<*>>, button: Int, state: UInt32) { input += "button $button $state" }
        override fun NotifyPointerAxis(session: DBusPath, options: Map<String, Variant<*>>, dx: Double, dy: Double) {}
        override fun NotifyPointerAxisDiscrete(session: DBusPath, options: Map<String, Variant<*>>, axis: UInt32, steps: Int) { input += "axis $axis $steps" }
        override fun NotifyKeyboardKeycode(session: DBusPath, options: Map<String, Variant<*>>, keycode: Int, state: UInt32) { input += "key $keycode $state" }
        override fun NotifyKeyboardKeysym(session: DBusPath, options: Map<String, Variant<*>>, keysym: Int, state: UInt32) { input += "sym ${Integer.toHexString(keysym)} $state" }

        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A = when {
            interfaceName == PortalBits.REMOTE_DESKTOP && remoteVersion == null -> throw UnknownProperty("No such interface")
            interfaceName == PortalBits.REMOTE_DESKTOP && propertyName == "version" -> UInt32(remoteVersion!!.toLong())
            interfaceName == PortalBits.REMOTE_DESKTOP && propertyName == "AvailableDeviceTypes" -> UInt32(7)
            interfaceName == PortalBits.SCREEN_CAST && propertyName == "version" -> UInt32(castVersion.toLong())
            interfaceName == PortalBits.SCREEN_CAST && propertyName == "AvailableSourceTypes" -> UInt32(3)
            interfaceName == PortalBits.SCREEN_CAST && propertyName == "AvailableCursorModes" -> UInt32(7)
            else -> throw UnknownProperty("No $propertyName")
        } as A

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) {}
        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = emptyMap()

        fun closeSession(path: String) = conn.sendMessage(PortalSessionObject.Closed(path, emptyMap()))
    }

    private fun startPortal(address: String, remoteVersion: Int? = 2): FakePortal {
        // One thread for calls, as xdg-desktop-portal handles them in order.
        val conn = DBusConnectionBuilder.forAddress(address).withShared(false).receivingThreadConfig().withMethodCallThreadCount(1)
            .connectionConfig().build().also { connections += it }
        conn.requestBusName(PortalBits.DESKTOP)
        return FakePortal(conn, remoteVersion).also { conn.exportObject(PortalBits.PATH, it) }
    }

    private fun client(address: String, timeoutMs: Long = 10_000) =
        PortalClient(connect(address), PortalTokens(File(dir, "portal.properties")), answerTimeoutMs = timeoutMs)

    @Test
    fun remoteDesktopCreatesSelectsStartsAndKeepsTheRestoreToken() {
        val address = startBus() ?: return
        val portal = startPortal(address)
        val client = client(address)
        assertEquals(PortalOffer(2, types = 7), client.offer(PortalBits.REMOTE_DESKTOP))

        val session = client.startRemoteDesktop(null)
        assertEquals(listOf("rd.CreateSession", "rd.SelectDevices", "rd.Start"), portal.calls)
        assertTrue(session.hasPointer && session.hasKeyboard)
        assertTrue(session.streams.isEmpty())
        val select = portal.options.first { it.first == "rd.SelectDevices" }.second
        assertEquals(3, (select.getValue("types").value as UInt32).toInt())
        assertEquals(2, (select.getValue("persist_mode").value as UInt32).toInt())
        assertNull(select["restore_token"])
        // Kept in dataDir, so the next start (even after a restart of DyskLink) asks the portal to restore it.
        assertEquals("token-1", PortalTokens(File(dir, "portal.properties")).get(PortalTokens.INPUT))

        // Input goes out on the bus.
        session.motion(3.0, -2.0)
        session.button(Evdev.BTN_LEFT, true)
        session.keysym(0x01F600, true)
        session.axisDiscrete(false, 2)
        waitFor { portal.input.size >= 4 }
        assertEquals(listOf("motion 3.0,-2.0", "button 272 1", "sym 1f600 1", "axis 0 2"), portal.input.toList())
        session.close()

        portal.issuedToken = "token-2"
        portal.options.clear()
        client.startRemoteDesktop(null)
        assertEquals("token-1", portal.options.first { it.first == "rd.SelectDevices" }.second.getValue("restore_token").value)
        // Tokens are single-use: the new one replaces it.
        assertEquals("token-2", PortalTokens(File(dir, "portal.properties")).get(PortalTokens.INPUT))
    }

    @Test
    fun oldPortalsGetNoPersistOptions() {
        val address = startBus() ?: return
        val portal = startPortal(address, remoteVersion = 1)
        client(address).startRemoteDesktop(null)
        val select = portal.options.first { it.first == "rd.SelectDevices" }.second
        assertNull(select["persist_mode"])
        assertNull(select["restore_token"])
    }

    @Test
    fun deniedIsACancelledRefusalAndKeepsNothing() {
        val address = startBus() ?: return
        val portal = startPortal(address)
        portal.answer = PortalBits.RESPONSE_CANCELLED
        val client = client(address)
        val refused = assertFailsWith<PortalRefused> { client.startRemoteDesktop(null) }
        assertTrue(refused.cancelled)
        assertNull(PortalTokens(File(dir, "portal.properties")).get(PortalTokens.INPUT))
        // The backend shows it as denied, and doesn't ask again by itself.
        val statuses = CopyOnWriteArrayList<PortalStatus>()
        val backend = PortalBackend({ onClosed -> client.startRemoteDesktop(null, onClosed) }) { statuses += it }
        assertTrue(!backend.ready())
        waitFor { statuses.lastOrNull()?.phase == PortalPhase.Denied }
        val before = portal.calls.size
        assertTrue(!backend.ready())
        Thread.sleep(200)
        assertEquals(before, portal.calls.size)
        // Ask again, and this time it's allowed.
        portal.answer = 0
        backend.ask()
        waitFor { statuses.last().phase == PortalPhase.Allowed }
        assertTrue(backend.ready())
        assertEquals(listOf(PortalPhase.Waiting, PortalPhase.Denied, PortalPhase.Waiting, PortalPhase.Allowed), statuses.map { it.phase })
    }

    @Test
    fun anotherErrorDropsTheStoredToken() {
        val address = startBus() ?: return
        val portal = startPortal(address)
        PortalTokens(File(dir, "portal.properties")).set(PortalTokens.INPUT, "stale")
        portal.answer = 2
        val refused = assertFailsWith<PortalRefused> { client(address).startRemoteDesktop(null) }
        assertTrue(!refused.cancelled)
        assertNull(PortalTokens(File(dir, "portal.properties")).get(PortalTokens.INPUT))
    }

    @Test
    fun screenCastAloneAndCombinedWithInput() {
        val address = startBus() ?: return
        val portal = startPortal(address)
        val client = client(address)
        val cast = client.offer(PortalBits.SCREEN_CAST)
        assertEquals(PortalOffer(5, types = 3, cursorModes = 7), cast)

        val screen = client.startScreenCast(cast)
        assertEquals(listOf("sc.CreateSession", "sc.SelectSources", "sc.Start"), portal.calls)
        assertEquals(listOf(PortalStream(57, 0 to 0, 2560 to 1440)), screen.streams)
        val sources = portal.options.first { it.first == "sc.SelectSources" }.second
        assertEquals(1, (sources.getValue("types").value as UInt32).toInt())
        assertEquals(false, sources.getValue("multiple").value)
        assertEquals(PortalBits.CURSOR_EMBEDDED, (sources.getValue("cursor_mode").value as UInt32).toInt())
        assertEquals(2, (sources.getValue("persist_mode").value as UInt32).toInt())
        assertEquals("token-1", PortalTokens(File(dir, "portal.properties")).get(PortalTokens.SCREEN))

        portal.calls.clear(); portal.options.clear()
        val both = client.startRemoteDesktop(cast)
        assertEquals(listOf("rd.CreateSession", "rd.SelectDevices", "sc.SelectSources", "rd.Start"), portal.calls)
        assertTrue(both.hasPointer)
        assertEquals(57, both.streams.single().node)
        // Kept through SelectDevices in a remote desktop session, not SelectSources.
        assertNull(portal.options.first { it.first == "sc.SelectSources" }.second["persist_mode"])
        assertEquals("token-1", PortalTokens(File(dir, "portal.properties")).get(PortalTokens.SCREEN_WITH_INPUT))

        // With the screen's session lent to the backend, the pointer is placed on the stream exactly.
        val backend = PortalBackend({ error("not asked") })
        backend.screenSession = both
        assertTrue(backend.ready() && backend.canAbsolute)
        assertTrue(backend.absolute(1280, 5000))
        waitFor { portal.input.isNotEmpty() }
        assertEquals("abs 57 1280.0,1439.0", portal.input.single())
    }

    @Test
    fun theDesktopEndingASessionIsNoticed() {
        val address = startBus() ?: return
        val portal = startPortal(address)
        val client = client(address)
        val statuses = CopyOnWriteArrayList<PortalStatus>()
        val backend = PortalBackend({ onClosed -> client.startRemoteDesktop(null, onClosed) }) { statuses += it }
        backend.ready()
        waitFor { statuses.lastOrNull()?.phase == PortalPhase.Allowed }
        val handle = portal.options.first { it.first == "rd.CreateSession" }.second.getValue("session_handle_token").value as String
        portal.closeSession(PortalBits.sessionPath(connections[1].uniqueName, handle))
        waitFor { statuses.last().phase == PortalPhase.Idle }
    }

    @Test
    fun noRemoteDesktopInterfaceMeansNoOffer() {
        val address = startBus() ?: return
        startPortal(address, remoteVersion = null)
        val client = client(address)
        assertNull(client.offer(PortalBits.REMOTE_DESKTOP).version)
        assertFailsWith<PortalRefused> { client.startRemoteDesktop(null) }
        // WaylandInput then goes on to /dev/uinput, or to nothing.
        val none = WaylandInput({ client }, uinputWritable = { false })
        assertEquals(InputRoute.NONE, none.probe())
        val fake = FakeBackend(InputRoute.UINPUT)
        val uinput = WaylandInput({ client }, uinputWritable = { true }, makeUinput = { fake })
        assertEquals(InputRoute.UINPUT, uinput.probe())
    }

    @Test
    fun noAnswerInTimeIsAFailureNotADenial() {
        val address = startBus() ?: return
        val portal = startPortal(address)
        portal.silentStart = true
        val refused = assertFailsWith<PortalRefused> { client(address, timeoutMs = 300).startRemoteDesktop(null) }
        assertTrue(!refused.cancelled)
        assertEquals("No answer from the system dialog.", refused.message)
    }

    @Test
    fun screenSourceStartsTheCastAndReadsGstreamer() {
        val address = startBus() ?: return
        val portal = startPortal(address)
        val client = client(address)
        // Missing pieces are named for the phone.
        assertEquals(id.phonelink.android.core.PcScreen.waylandMissing("GStreamer (gst-launch-1.0)"), PortalScreenSource({ client }, { GstCheck() }, { null }, { false }).refusal())
        assertTrue("pipewiresrc" in PortalScreenSource({ client }, { GstCheck(launch = true) }, { null }, { false }).refusal()!!)

        // gst-launch-1.0 is stood in for by cat on what it writes.
        val image = java.awt.image.BufferedImage(160, 90, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val jpeg = ScreenPictures.jpeg(image, 70)!!
        val stream = File(dir, "stream.bin").apply {
            writeBytes("--dysk\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray() + jpeg + "\r\n".toByteArray())
        }
        val commands = CopyOnWriteArrayList<List<String>>()
        val source = PortalScreenSource({ client }, { GstCheck(true, true) }, { null }, { false }, launch = { cmd ->
            commands += cmd
            ProcessBuilder("cat", stream.path).start()
        })
        assertNull(source.refusal())
        val before = source.displays().single()
        assertEquals(PortalScreenSource.DISPLAY_ID, before.id)
        val grab = assertNotNull(source.grab(before, 1600))
        assertEquals(160 to 90, grab.width to grab.height)
        assertTrue(grab.encode(70)!!.contentEquals(jpeg))
        assertEquals(listOf("sc.CreateSession", "sc.SelectSources", "sc.Start"), portal.calls)
        assertTrue("path=57" in commands.first() && "video/x-raw,width=1600,height=900" in commands.first())
        assertEquals(PortalPhase.Allowed, source.status.value.phase)
        val after = source.displays().single()
        assertEquals(listOf(0, 0, 2560, 1440), listOf(after.x, after.y, after.width, after.height))
        // A lower quality than GStreamer's is encoded again here.
        assertTrue(grab.encode(40)!!.size < jpeg.size + 1)
        source.release()
    }

    @Test
    fun stopOnTheDesktopSticksUntilAskedAgain() {
        val address = startBus() ?: return
        val portal = startPortal(address)
        val client = client(address)
        val image = java.awt.image.BufferedImage(160, 90, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val jpeg = ScreenPictures.jpeg(image, 70)!!
        val stream = File(dir, "stream.bin").apply {
            writeBytes("--dysk\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray() + jpeg + "\r\n".toByteArray())
        }
        var stopped = 0
        val source = PortalScreenSource({ client }, { GstCheck(true, true) }, { null }, { false },
            launch = { ProcessBuilder("cat", stream.path).start() }, onStoppedHere = { stopped++ })
        val display = source.displays().single()
        assertNotNull(source.grab(display, 1600))
        val handle = portal.options.first { it.first == "sc.CreateSession" }.second.getValue("session_handle_token").value as String
        // The desktop's Stop in the top bar ends the session.
        portal.closeSession(PortalBits.sessionPath(connections[1].uniqueName, handle))
        waitFor { stopped == 1 }
        assertTrue(source.stoppedHere)
        assertEquals(id.phonelink.android.core.PcScreen.STOPPED_HERE, source.failure(), "the phone is told it was stopped here")
        // The release after the phone is told (and its teardown) keeps it stopped; nothing is started again.
        source.release()
        val calls = portal.calls.size
        repeat(3) { assertNull(source.grab(display, 1600)) }
        Thread.sleep(300)
        assertEquals(calls, portal.calls.size, "no new session, not even through the restore token")
        assertTrue(source.stoppedHere)
        // A phone asking again starts a new session.
        source.phoneAsked()
        assertNull(source.failure())
        assertNotNull(source.grab(display, 1600))
        assertTrue(portal.calls.size > calls)
        source.release()
    }

    @Test
    fun screenSourceDeniedTellsThePhone() {
        val address = startBus() ?: return
        val portal = startPortal(address)
        portal.answer = PortalBits.RESPONSE_CANCELLED
        val source = PortalScreenSource({ client(address) }, { GstCheck(true, true) }, { null }, { false }, launch = { error("not started") })
        assertNull(source.grab(source.displays().single(), 1600))
        assertEquals(id.phonelink.android.core.PcScreen.WAYLAND_DENIED, source.failure())
        assertEquals(PortalPhase.Denied, source.status.value.phase)
        // Asked again only when a phone opens the screen again.
        assertNull(source.grab(source.displays().single(), 1600))
        assertEquals(3, portal.calls.size)
    }

    @Test
    fun requestPathsFollowTheSpec() {
        assertEquals("/org/freedesktop/portal/desktop/request/1_42/t1", PortalBits.requestPath(":1.42", "t1"))
        assertEquals("/org/freedesktop/portal/desktop/session/1_42/s", PortalBits.sessionPath(":1.42", "s"))
        assertEquals(emptyList(), PortalBits.streams(null))
        assertEquals(listOf(PortalStream(9, null, 800 to 600)), PortalBits.streams(listOf(arrayOf<Any?>(UInt32(9), mapOf("size" to Variant(SizeStruct(800, 600), "(ii)"))))))
    }

    private fun waitFor(what: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!what()) {
            check(System.nanoTime() < until) { "Timed out" }
            Thread.sleep(20)
        }
    }
}

/** Records what a Wayland backend was asked. */
class FakeBackend(override val route: InputRoute = InputRoute.PORTAL, var isReady: Boolean = true, override var canAbsolute: Boolean = false, override val keysyms: Boolean = true) : WaylandBackend {
    val log = CopyOnWriteArrayList<String>()
    override fun ready() = isReady
    override fun relative(dx: Double, dy: Double) { log += "rel ${dx.toInt()},${dy.toInt()}" }
    override fun absolute(x: Int, y: Int): Boolean { if (canAbsolute) log += "abs $x,$y"; return canAbsolute }
    override fun button(code: Int, down: Boolean) { log += "button $code ${if (down) "down" else "up"}" }
    override fun scroll(horizontal: Boolean, steps: Int) { log += "scroll ${if (horizontal) "h" else "v"} $steps" }
    override fun key(code: Int, down: Boolean) { log += "key $code ${if (down) "down" else "up"}" }
    override fun keysym(sym: Int, down: Boolean) { log += "sym ${Integer.toHexString(sym)} ${if (down) "down" else "up"}" }
}
