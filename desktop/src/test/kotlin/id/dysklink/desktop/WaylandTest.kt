package id.dysklink.desktop

import id.phonelink.android.core.PcScreen
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WaylandTest {
    // ---- choosing the way in ----

    @Test
    fun routeOrderIsX11ThenPortalThenUinputThenNothing() {
        assertEquals(InputRoute.ROBOT, InputRoutes.choose(wayland = false, robot = true, portal = { error("not asked") }, uinput = { error("not asked") }))
        assertEquals(InputRoute.NONE, InputRoutes.choose(wayland = false, robot = false, portal = { true }, uinput = { true }))
        var askedUinput = false
        assertEquals(InputRoute.PORTAL, InputRoutes.choose(true, false, portal = { true }, uinput = { askedUinput = true; true }))
        assertTrue(!askedUinput)
        assertEquals(InputRoute.UINPUT, InputRoutes.choose(true, true, portal = { false }, uinput = { true }))
        assertEquals(InputRoute.NONE, InputRoutes.choose(true, true, portal = { false }, uinput = { false }))
    }

    @Test
    fun uinputThatCantBeMadeIsNone() {
        val input = WaylandInput({ null }, uinputWritable = { true }, makeUinput = { null })
        assertEquals(InputRoute.NONE, input.probe())
        assertEquals(InputRoute.NONE, input.route.value)
    }

    // ---- keys ----

    @Test
    fun awtKeysMapToEvdev() {
        assertEquals(30, Evdev.key(KeyEvent.VK_A))
        assertEquals(16, Evdev.key(KeyEvent.VK_Q))
        assertEquals(44, Evdev.key(KeyEvent.VK_Z))
        assertEquals(50, Evdev.key(KeyEvent.VK_M))
        assertEquals(2, Evdev.key(KeyEvent.VK_1))
        assertEquals(11, Evdev.key(KeyEvent.VK_0))
        assertEquals(28, Evdev.key(KeyEvent.VK_ENTER))
        assertEquals(14, Evdev.key(KeyEvent.VK_BACK_SPACE))
        assertEquals(111, Evdev.key(KeyEvent.VK_DELETE))
        assertEquals(59, Evdev.key(KeyEvent.VK_F1))
        assertEquals(68, Evdev.key(KeyEvent.VK_F10))
        assertEquals(87, Evdev.key(KeyEvent.VK_F11))
        assertEquals(183, Evdev.key(KeyEvent.VK_F13))
        assertEquals(105, Evdev.key(KeyEvent.VK_LEFT))
        assertEquals(103, Evdev.key(KeyEvent.VK_UP))
        assertEquals(125, Evdev.key(KeyEvent.VK_WINDOWS))
        assertEquals(29, Evdev.key(KeyEvent.VK_CONTROL))
        assertEquals(99, Evdev.key(KeyEvent.VK_PRINTSCREEN))
        assertEquals(86, Evdev.key(KeyEvent.VK_LESS))
        assertEquals(0x110, Evdev.button(0))
        assertEquals(0x111, Evdev.button(1))
        assertEquals(0x112, Evdev.button(2))
        assertNull(Evdev.button(7))
        // Every key the phone and a Mac send has an evdev code.
        for ((name, vk) in Keys.named) assertTrue(Evdev.key(vk) != null, name)
        for (code in 0..126) MacKeys.vk(code)?.let { assertTrue(Evdev.key(it) != null, "Mac key $code") }
    }

    @Test
    fun keysymsFromUnicode() {
        assertEquals(0x61L, X11Keys.keysymFor('a'.code))
        assertEquals(0xe9L, X11Keys.keysymFor('é'.code))
        assertEquals(0x0100_20ACL, X11Keys.keysymFor('€'.code))
        assertEquals(0x0101_F600L, X11Keys.keysymFor(0x1F600))
    }

    @Test
    fun waylandTypingPlan() {
        val portal = TypingTools(Desktop.WAYLAND, keysyms = true, clipboard = true, robot = true)
        assertEquals(
            listOf(TypeStep.Text(TypeMethod.KEYSYM, "ab"), TypeStep.Text(TypeMethod.PASTE, "é"), TypeStep.Key("return"), TypeStep.Text(TypeMethod.KEYSYM, "c")),
            Typing.plan("abé\nc", portal),
        )
        // The clipboard failing: keysyms for everything.
        assertEquals(listOf<TypeStep>(TypeStep.Text(TypeMethod.KEYSYM, "é")), Typing.plan("é", portal.without(TypeMethod.PASTE)))
        // /dev/uinput: no keysyms, so pasted, or US keys without the clipboard.
        val uinput = TypingTools(Desktop.WAYLAND, clipboard = true, robot = true)
        assertEquals(listOf<TypeStep>(TypeStep.Text(TypeMethod.PASTE, "aé")), Typing.plan("aé", uinput))
        assertEquals(listOf<TypeStep>(TypeStep.Text(TypeMethod.US_KEYS, "a")), Typing.plan("aé", uinput.without(TypeMethod.PASTE)))
        assertEquals(emptyList(), Typing.plan("a", TypingTools(Desktop.WAYLAND)))
    }

    // ---- the injector ----

    private val screens = listOf(Rectangle(0, 0, 1920, 1080), Rectangle(1920, 0, 1280, 1024))

    @Test
    fun injectorFollowsThePointerAndHomesWhereItCantPlaceIt() {
        val backend = FakeBackend()
        val injector = WaylandInjector({ backend }, { screens })
        assertEquals(Point(960, 540), injector.pointer)
        injector.moveBy(10, -5)
        assertEquals(Point(970, 535), injector.pointer)
        // A Mac's pointer coming in: pushed into the corner first, then moved from there.
        injector.sync()
        assertEquals(Point(0, 0), injector.pointer)
        injector.moveTo(3000, 500)
        assertEquals(listOf("rel 10,-5", "rel -100000,-100000", "rel 3000,500"), backend.log.toList())
        // Kept on the displays.
        injector.moveBy(-5000, 0)
        assertEquals(Point(1920, 500), injector.pointer)
    }

    @Test
    fun injectorPlacesThePointerExactlyWhenItCan() {
        val backend = FakeBackend(canAbsolute = true)
        val injector = WaylandInjector({ backend }, { screens })
        injector.sync()
        injector.moveTo(100, 200)
        assertEquals(listOf("abs 100,200"), backend.log.toList())
        assertEquals(Point(100, 200), injector.pointer)
    }

    @Test
    fun injectorButtonsKeysScrollAndKeysyms() {
        val backend = FakeBackend()
        val injector = WaylandInjector({ backend }, { screens })
        injector.button(1, true); injector.button(1, false)
        injector.key(KeyEvent.VK_ENTER, true)
        injector.key(KeyEvent.VK_UNDEFINED, true)
        injector.wheel(2); injector.hwheel(-1); injector.wheel(0)
        assertEquals(Typed.Done, injector.typeKeysyms("aé"))
        assertEquals(
            listOf("button 273 down", "button 273 up", "key 28 down", "scroll v 2", "scroll h -1", "sym 61 down", "sym 61 up", "sym e9 down", "sym e9 up"),
            backend.log.toList(),
        )
        // While the user hasn't allowed it yet, nothing goes out and typing keeps its way for next time.
        backend.isReady = false
        backend.log.clear()
        injector.moveBy(5, 5)
        injector.key(KeyEvent.VK_A, true)
        assertEquals(Typed.Stopped, injector.typeKeysyms("x"))
        assertTrue(backend.log.isEmpty())
        // No backend at all.
        assertEquals(Typed.Stopped, WaylandInjector({ null }, { screens }).typeKeysyms("x"))
    }

    @Test
    fun sharedInputUsesTheWaylandInjector() {
        val backend = FakeBackend()
        val injector = WaylandInjector({ backend }, { screens })
        val sent = mutableListOf<String>()
        val target = SharedInputTarget(injector, send = { _, e -> sent += e.type })
        target.enter("mac", ScreenEdge.LEFT, 0.5)
        // Homed, then to the left edge of the left display, half way down.
        assertEquals(listOf("rel -100000,-100000", "rel 2,540"), backend.log.toList())
        target.handle(id.phonelink.android.core.RemoteEvent("key", keyCode = 0, keyDown = true, flags = 0), "mac")
        assertEquals("key 30 down", backend.log.last())
    }

    // ---- /dev/uinput ----

    @Test
    fun uinputIoctlNumbersMatchLinuxUinputH() {
        // From linux/uinput.h on x86-64 and arm64: _IO('U', 1), _IOW('U', 3, struct uinput_setup) and so on.
        assertEquals(0x5501, Uinput.UI_DEV_CREATE)
        assertEquals(0x5502, Uinput.UI_DEV_DESTROY)
        assertEquals(0x405C5503, Uinput.UI_DEV_SETUP)
        assertEquals(0x401C5504, Uinput.UI_ABS_SETUP)
        assertEquals(0x40045564, Uinput.UI_SET_EVBIT)
        assertEquals(0x40045565, Uinput.UI_SET_KEYBIT)
        assertEquals(0x40045566, Uinput.UI_SET_RELBIT)
        assertEquals(0x40045567, Uinput.UI_SET_ABSBIT)
        assertEquals(92, Uinput.SETUP_SIZE)
        assertEquals(28, Uinput.ABS_SETUP_SIZE)
    }

    @Test
    fun uinputStructLayout() {
        assertEquals(24, Uinput.eventSize(8))
        assertEquals(16, Uinput.eventSize(4))
        val event = ByteBuffer.wrap(Uinput.event(Uinput.EV_KEY, Evdev.BTN_LEFT, 1, longSize = 8)).order(ByteOrder.nativeOrder())
        assertEquals(0L, event.getLong(0))
        assertEquals(0L, event.getLong(8))
        assertEquals(1, event.getShort(16).toInt())
        assertEquals(0x110, event.getShort(18).toInt())
        assertEquals(1, event.getInt(20))
        val frame = Uinput.frame(listOf(Triple(Uinput.EV_REL, Uinput.REL_X, -3)), longSize = 8)
        assertEquals(48, frame.size)
        val f = ByteBuffer.wrap(frame).order(ByteOrder.nativeOrder())
        assertEquals(-3, f.getInt(20))
        assertEquals(0, f.getShort(24 + 16).toInt()) // EV_SYN
        assertEquals(0, f.getShort(24 + 18).toInt()) // SYN_REPORT

        val setup = ByteBuffer.wrap(Uinput.setup("DyskLink keyboard and mouse")).order(ByteOrder.nativeOrder())
        assertEquals(Uinput.BUS_VIRTUAL, setup.getShort(0).toInt())
        assertEquals("DyskLink keyboard and mouse", String(setup.array(), 8, 27))
        assertEquals(0, setup.get(8 + 27).toInt())
        assertEquals(0, setup.getInt(88))
        val long = Uinput.setup("x".repeat(200))
        assertEquals(0, long[8 + 79].toInt()) // always ends in a 0

        val abs = ByteBuffer.wrap(Uinput.absSetup(Uinput.ABS_Y)).order(ByteOrder.nativeOrder())
        assertEquals(1, abs.getShort(0).toInt())
        assertEquals(0, abs.getInt(8)) // minimum
        assertEquals(65535, abs.getInt(12)) // maximum
    }

    @Test
    fun uinputAbsoluteCoversEveryDisplay() {
        assertEquals(0 to 0, Uinput.scale(0, 0, screens))
        assertEquals(65535 to 65535, Uinput.scale(3199, 1079, screens))
        assertEquals(32778 to 32798, Uinput.scale(1600, 540, screens))
        assertNull(Uinput.scale(1, 1, emptyList()))
    }

    // ---- GStreamer ----

    @Test
    fun pipelineCommand() {
        assertEquals(
            "gst-launch-1.0 -q pipewiresrc path=57 do-timestamp=true keepalive-time=1000 ! videorate max-rate=15 drop-only=true ! videoscale ! " +
                "video/x-raw,width=1600,height=900 ! videoconvert ! jpegenc quality=70 ! multipartmux boundary=dysk ! fdsink fd=1",
            GstPipeline.command(57, 1600, 900, keepalive = true).joinToString(" "),
        )
        val bare = GstPipeline.command(9, null, null, quality = 500, fps = 10)
        assertTrue("keepalive-time=1000" !in bare && "videoscale" !in bare)
        assertTrue("quality=100" in bare && "max-rate=10" in bare)
        assertEquals("-q", bare[1])
    }

    @Test
    fun gstreamerIsDetected() {
        assertEquals(GstCheck(), GstCheck.probe({ false }) { error("not run") })
        assertEquals(GstCheck(launch = true), GstCheck.probe({ it == "gst-launch-1.0" }) { error("not run") })
        assertEquals(GstCheck(launch = true), GstCheck.probe({ true }) { 1 to "No such element or plugin 'pipewiresrc'" })
        val old = GstCheck.probe({ true }) { 0 to "Factory Details:\n  path : The source path\n" }
        assertEquals(GstCheck(launch = true, pipewiresrc = true, keepalive = false), old)
        assertTrue(old.ready)
        val new = GstCheck.probe({ true }) { cmd -> assertEquals(listOf("gst-inspect-1.0", "pipewiresrc"), cmd); 0 to "  keepalive-time      : Periodically send last buffer" }
        assertTrue(new.keepalive)
    }

    private fun jpeg(width: Int, height: Int, fill: Int = 0): ByteArray {
        val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) image.setRGB(x, y, fill + x)
        return ScreenPictures.jpeg(image, 70)!!
    }

    private fun multipart(parts: List<ByteArray>, length: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) {
            out.write("--dysk\r\nContent-Type: image/jpeg\r\n".toByteArray())
            if (length) out.write("Content-Length: ${p.size}\r\n".toByteArray())
            out.write("\r\n".toByteArray())
            out.write(p)
            out.write("\r\n".toByteArray())
        }
        return out.toByteArray()
    }

    /** Hands out at most [chunk] bytes per read, as a pipe does. */
    private class Trickle(input: InputStream, val chunk: Int) : FilterInputStream(input) {
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(len, chunk))
    }

    @Test
    fun multipartSplitsFramesEvenFromTinyReads() {
        val frames = listOf(jpeg(64, 48), jpeg(32, 32, 0x2000), jpeg(8, 4, 0x400000))
        for (chunk in listOf(1, 7, 4096)) {
            val reader = MultipartJpegReader(Trickle(ByteArrayInputStream(multipart(frames)), chunk))
            frames.forEach { assertContentEquals(it, reader.next()) }
            assertNull(reader.next())
        }
        // Without Content-Length, a part ends where the next boundary starts.
        val reader = MultipartJpegReader(Trickle(ByteArrayInputStream(multipart(frames, length = false)), 3))
        frames.forEach { assertContentEquals(it, reader.next()) }
        assertNull(reader.next())
    }

    @Test
    fun multipartStopsAtACutOffPart() {
        val frame = jpeg(16, 16)
        val bytes = multipart(listOf(frame, frame))
        val reader = MultipartJpegReader(ByteArrayInputStream(bytes.copyOf(bytes.size - 100)))
        assertContentEquals(frame, reader.next())
        assertNull(reader.next())
    }

    @Test
    fun jpegSizeAndFingerprint() {
        val a = jpeg(64, 48)
        assertEquals(64 to 48, JpegSize.of(a))
        assertNull(JpegSize.of(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0, 0)))
        val b = jpeg(64, 48, 0x10)
        assertEquals(PcScreen.hash(JpegSize.fingerprint(a)), PcScreen.hash(JpegSize.fingerprint(a.copyOf())))
        assertTrue(PcScreen.hash(JpegSize.fingerprint(a)) != PcScreen.hash(JpegSize.fingerprint(b)))
    }

    @Test
    fun screenSourceWithoutAPortalIsRefusedClearly() {
        val noPortal = PortalScreenSource({ null }, { GstCheck(true, true) }, { null }, { false })
        assertEquals(PcScreen.WAYLAND_REFUSAL, noPortal.refusal())
    }
}
