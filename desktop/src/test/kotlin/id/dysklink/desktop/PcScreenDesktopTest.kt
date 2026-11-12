package id.dysklink.desktop

import id.phonelink.android.core.ComputerHub
import id.phonelink.android.core.ContactItem
import id.phonelink.android.core.ControlEvent
import id.phonelink.android.core.ConnectionManager
import id.phonelink.android.core.DeviceActions
import id.phonelink.android.core.DeviceInfo
import id.phonelink.android.core.Envelope
import id.phonelink.android.core.FileSink
import id.phonelink.android.core.FileSinkFactory
import id.phonelink.android.core.Hello
import id.phonelink.android.core.HubEvents
import id.phonelink.android.core.JpegFrames
import id.phonelink.android.core.MessageType
import id.phonelink.android.core.NotificationItem
import id.phonelink.android.core.PcDisplay
import id.phonelink.android.core.PcScreen
import id.phonelink.android.core.PcScreenSource
import id.phonelink.android.core.PhoneData
import id.phonelink.android.core.PhoneFeature
import id.phonelink.android.core.PhoneLinkProtocol
import id.phonelink.android.core.ScreenGrab
import id.phonelink.android.core.SmsItem
import java.awt.Color
import java.awt.Point
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** This PC's screen for a phone: scaling, the pointer, JPEG, and a fake capture through the real hub to the real phone. */
class PcScreenDesktopTest {
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

    private val display = PcDisplay(7, "Display 1 (3840×2160)", true, 0, 0, 3840, 2160)

    private fun desktop(width: Int, height: Int, color: Color = Color(30, 60, 120)): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            val g = createGraphics(); g.color = color; g.fillRect(0, 0, width, height); g.dispose()
        }

    private fun rgb(image: BufferedImage, x: Int, y: Int) = Color(image.getRGB(x, y))

    @Test
    fun picturesAreScaledToTheLongSide() {
        val big = ScreenPictures.scaled(desktop(3840, 2160), PcScreen.MAX_LONG_SIDE)
        assertEquals(1600 to 900, big.width to big.height)
        val tall = ScreenPictures.scaled(desktop(1080, 1920), PcScreen.MAX_LONG_SIDE)
        assertEquals(900 to 1600, tall.width to tall.height)
        val small = ScreenPictures.scaled(desktop(1280, 720), PcScreen.MAX_LONG_SIDE)
        assertEquals(1280 to 720, small.width to small.height, "Never scaled up")
        assertEquals(BufferedImage.TYPE_INT_RGB, small.type)
    }

    @Test
    fun thePointerIsDrawnWhereItIs() {
        val grab = ScreenPictures.grab(desktop(3840, 2160), display, Point(1920, 1080))
        assertEquals(1600, grab.width)
        // Just inside the tip, at a quarter of the original size.
        val image = BufferedImage(grab.width, grab.height, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, width, height, grab.pixels, 0, width) }
        val inside = rgb(image, 802, 460)
        assertTrue(inside.red > 200 && inside.green > 200 && inside.blue > 200, "White arrow at the pointer: $inside")
        assertEquals(Color(30, 60, 120), rgb(image, 100, 100), "The rest is the screen")
        // Elsewhere, or on another display: a different picture, or none drawn.
        val moved = ScreenPictures.grab(desktop(3840, 2160), display, Point(100, 100))
        assertNotEquals(PcScreen.hash(grab.pixels), PcScreen.hash(moved.pixels), "Moving the pointer is a change")
        val away = ScreenPictures.grab(desktop(3840, 2160), display, Point(-500, 100))
        val none = ScreenPictures.grab(desktop(3840, 2160), display, null)
        assertEquals(PcScreen.hash(none.pixels), PcScreen.hash(away.pixels), "A pointer on another display isn't drawn")
    }

    @Test
    fun jpegQualityFollowsThePacer() {
        val image = ScreenPictures.scaled(noise(1600, 900), PcScreen.MAX_LONG_SIDE)
        val high = ScreenPictures.jpeg(image, 80)!!
        val low = ScreenPictures.jpeg(image, 35)!!
        assertTrue(JpegFrames.looksLikeJpeg(high))
        assertTrue(low.size < high.size, "${low.size} < ${high.size}")
        val back = ImageIO.read(ByteArrayInputStream(high))
        assertEquals(1600 to 900, back.width to back.height)
    }

    private fun noise(width: Int, height: Int): BufferedImage {
        val random = java.util.Random(1)
        return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
            for (y in 0 until height step 4) for (x in 0 until width step 4) {
                val c = random.nextInt(0xFFFFFF)
                for (dy in 0 until 4) for (dx in 0 until 4) setRGB(x + dx, y + dy, c)
            }
        }
    }

    @Test
    fun displayIdsStayTheSame() {
        assertEquals(ScreenPictures.displayId(":0.0"), ScreenPictures.displayId(":0.0"))
        assertNotEquals(ScreenPictures.displayId("\\Display1"), ScreenPictures.displayId("\\Display2"))
        assertTrue(ScreenPictures.displayId("x") >= 0)
    }

    @Test
    fun pointerKindsBecomeButtonPresses() {
        assertEquals(listOf(PointerStep.MOVE, PointerStep.LEFT_DOWN, PointerStep.LEFT_UP), PointerSteps.of("click"))
        assertEquals(listOf(PointerStep.MOVE, PointerStep.LEFT_DOWN), PointerSteps.of("down"))
        assertEquals(listOf(PointerStep.MOVE), PointerSteps.of("drag"))
        assertEquals(listOf(PointerStep.MOVE, PointerStep.LEFT_UP), PointerSteps.of("up"))
        assertEquals(listOf(PointerStep.MOVE, PointerStep.RIGHT_DOWN, PointerStep.RIGHT_UP), PointerSteps.of("right"))
        assertEquals(PointerSteps.of("click"), PointerSteps.of("double"))
        assertEquals(emptyList(), PointerSteps.of("teleport"))
        // Every kind the phone may send does something.
        PcScreen.POINTER_KINDS.forEach { assertTrue(PointerSteps.of(it).isNotEmpty(), it) }
    }

    @Test
    fun waylandIsRefusedWithAClearMessage() {
        assertEquals(PcScreen.WAYLAND_REFUSAL, RobotScreenSource(wayland = true).refusal())
        assertTrue("Wayland" in PcScreen.WAYLAND_REFUSAL && "X11" in PcScreen.WAYLAND_REFUSAL)
    }

    /** Stands in for Robot with a made-up desktop; everything after the capture is the real thing. */
    private inner class FakeDesktop : PcScreenSource {
        @Volatile var color = Color(30, 60, 120)
        @Volatile var pointer = Point(1920, 1080)
        override fun refusal(): String? = null
        override fun displays() = listOf(display)
        override fun grab(display: PcDisplay, maxLong: Int): ScreenGrab = ScreenPictures.grab(desktop(display.width, display.height, color), display, pointer, maxLong)
    }

    private fun sink(folder: File) = FileSinkFactory { name, _ ->
        val out = ByteArrayOutputStream()
        object : FileSink {
            override fun write(bytes: ByteArray) = out.write(bytes)
            override fun commit(): String = File(folder, name).also { it.writeBytes(out.toByteArray()) }.path
            override fun abort() {}
        }
    }

    @Test
    fun aFakeDesktopReachesThePhoneAsJpeg() {
        val fake = FakeDesktop()
        val taps = CopyOnWriteArrayList<String>()
        val pcDir = Files.createTempDirectory("dysk-screen-pc").toFile()
        val hub = ComputerHub(
            pcDir, scope(),
            localHello = { paired -> Hello("Test PC", "windows", "Test", PhoneLinkProtocol.VERSION, paired, "1.0.0", PhoneLinkProtocol.PC_CAPS) },
            events = object : HubEvents {
                override fun clipboard(text: String, from: String) = true
                override fun ring(on: Boolean, from: String) {}
                override val canControl get() = true
                override fun screenRefusal(from: String): String? = null
                override fun screenSource(): PcScreenSource = fake
                override fun screenPointer(kind: String, x: Int, y: Int, from: String) { taps += "$kind $x,$y" }
            },
            fileSinks = sink(pcDir), port = 0,
        ).also { it.start(); closers += it::shutdown }
        val phoneDir = Files.createTempDirectory("dysk-screen-phone").toFile()
        val seen = CopyOnWriteArrayList<Envelope>()
        val phone = ConnectionManager(
            phoneDir, scope(),
            localHello = { paired -> Hello("Pixel", "android", "Pixel 9", PhoneLinkProtocol.VERSION, paired, caps = PhoneLinkProtocol.PHONE_CAPS) },
            localInfo = { DeviceInfo() },
            isUiVisible = { true },
            actions = object : DeviceActions {
                override fun setClipboard(text: String) {}
                override fun media(action: String) {}
            },
            fileSinks = sink(phoneDir),
            phoneData = object : PhoneData {
                override val storageRoot = phoneDir
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
        ).also { closers += it::shutdown; it.macScreenSink = { _, envelope -> seen += envelope } }
        phone.addManual("127.0.0.1", hub.boundPort)
        waitUntil("Connected") { hub.phones.value.any { it.isConnected } }
        phone.tryAutoPair()
        waitUntil("A code") { hub.pairing.value != null }
        phone.submitCode(hub.pairing.value!!.code)
        waitUntil("Paired") { phone.peers.value.any { it.id == hub.deviceId && it.isPaired && it.isConnected } }

        phone.startMacScreen(hub.deviceId)
        waitUntil("A picture") { seen.any { it.type == MessageType.MACSCREEN_FRAME } }
        val started = seen.first { it.type == MessageType.MACSCREEN_STARTED }
        assertEquals(1600, started.screen?.width)
        assertEquals(900, started.screen?.height)
        val jpeg = assertNotNull(JpegFrames.pictureOf(seen.first { it.type == MessageType.MACSCREEN_FRAME }))
        val picture = ImageIO.read(ByteArrayInputStream(jpeg))
        assertEquals(1600 to 900, picture.width to picture.height)
        val pointer = rgb(picture, 802, 460)
        assertTrue(pointer.red > 180 && pointer.blue > 180, "The pointer is in the picture: $pointer")
        val screen = rgb(picture, 200, 200)
        assertTrue(screen.blue in 100..140 && screen.red < 60, "The screen's colour survives JPEG: $screen")
        // The screen changes: a new picture shows it.
        fake.color = Color(200, 40, 40)
        waitUntil("The new colour") {
            seen.filter { it.type == MessageType.MACSCREEN_FRAME }.lastOrNull()
                ?.let { JpegFrames.pictureOf(it) }?.let { rgb(ImageIO.read(ByteArrayInputStream(it)), 200, 200).red > 150 } == true
        }
        // A tap at the middle of the picture is the middle of the 4K display.
        phone.sendMacPointer(hub.deviceId, "click", 0.5f, 0.5f)
        waitUntil("Tapped") { taps.isNotEmpty() }
        assertEquals("click 1920,1080", taps.single())
        hub.stopScreenOut()
        waitUntil("Stopped") { seen.any { it.type == MessageType.MACSCREEN_STOPPED } }
    }
}
