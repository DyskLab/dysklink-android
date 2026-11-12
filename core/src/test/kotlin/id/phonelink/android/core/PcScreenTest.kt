package id.phonelink.android.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * A Windows or Linux PC's own screen on the phone (`pc-screen-out`): the parts with a fake capture, then the real
 * phone ConnectionManager against the real ComputerHub over TLS on this machine.
 */
class PcScreenTest {
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

    private val main = PcDisplay(11, "Display 1 (3200×1800)", true, 0, 0, 3200, 1800)
    private val left = PcDisplay(22, "Display 2 (1920×1080)", false, -1920, 0, 1920, 1080)

    /** Stands in for Robot: the picture is one [color], and its "JPEG" says which color and display it was. */
    private class FakeScreen(@Volatile var all: List<PcDisplay>) : PcScreenSource {
        @Volatile var refusal: String? = null
        @Volatile var color = 1
        @Volatile var fail = false
        @Volatile var tooBigOnce = false
        val grabs = AtomicInteger()
        val encodes = AtomicInteger()

        override fun refusal() = refusal
        override fun displays() = all
        override fun grab(display: PcDisplay, maxLong: Int): ScreenGrab? {
            grabs.incrementAndGet()
            if (fail) return null
            val (w, h) = JpegFrames.fit(display.width, display.height, maxLong)
            val pixels = IntArray(w * h) { color }
            val shade = color
            return ScreenGrab(w, h, pixels) { quality ->
                encodes.incrementAndGet()
                if (tooBigOnce) {
                    tooBigOnce = false
                    ByteArray(JpegFrames.MAX_BYTES + 1).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte() }
                } else {
                    byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), shade.toByte(), display.id.toByte(), quality.toByte())
                }
            }
        }
    }

    // Caps

    @Test
    fun theScreenCapIsTheDesktopAppsAndGatesWhatThePhoneTakes() {
        assertTrue(PhoneLinkProtocol.CAP_PC_SCREEN_OUT in PhoneLinkProtocol.PC_CAPS)
        assertFalse(PhoneLinkProtocol.CAP_PC_SCREEN_OUT in PhoneLinkProtocol.PHONE_CAPS)
        assertFalse(PhoneLinkProtocol.CAP_PC_DISPLAY in PhoneLinkProtocol.PC_CAPS, "No PC makes an extra display yet")
        assertFalse(PhoneLinkProtocol.CAP_PC_DISPLAY in PhoneLinkProtocol.PHONE_CAPS)
        fun peer(platform: String, caps: List<String>) = Peer("x", "X", platform, "", true, true, null, caps = caps)
        assertTrue(peer("macos", emptyList()).canSeeComputerScreen, "A Mac always shows its screen")
        assertTrue(peer("macos", emptyList()).canExtend)
        assertTrue(peer("windows", PhoneLinkProtocol.PC_CAPS).canSeeComputerScreen)
        assertTrue(peer("linux", PhoneLinkProtocol.PC_CAPS).canSeeComputerScreen)
        assertFalse(peer("windows", PhoneLinkProtocol.PC_CAPS).canExtend)
        val older = PhoneLinkProtocol.CAPS + PhoneLinkProtocol.PC_FEATURE_CAPS + listOf(PhoneLinkProtocol.CAP_PC_SOUND_OUT, PhoneLinkProtocol.CAP_PC_COMMANDS)
        assertFalse(peer("windows", older).canSeeComputerScreen, "An older PC build ignores macscreen.start")
        assertFalse(peer("android", PhoneLinkProtocol.PC_CAPS).canSeeComputerScreen, "Phones are never computers")
        for (type in listOf(MessageType.MACSCREEN_STARTED, MessageType.MACSCREEN_FRAME, MessageType.MACSCREEN_STOPPED)) {
            assertFalse(PcRules.allows("windows", older, type), type)
            assertFalse(PcRules.allows("linux", null, type), type)
            assertTrue(PcRules.allows("linux", PhoneLinkProtocol.PC_CAPS, type), type)
            assertTrue(PcRules.allows("macos", null, type), "A Mac sends $type as it always did")
        }
    }

    // The parts

    @Test
    fun aChangedPixelChangesTheHash() {
        val a = IntArray(1600 * 900) { it * 31 }
        val b = a.copyOf()
        assertEquals(PcScreen.hash(a), PcScreen.hash(b))
        b[123_456] = b[123_456] xor 1
        assertNotEquals(PcScreen.hash(a), PcScreen.hash(b))
        // Only the colour counts, not the alpha some captures fill in.
        val c = a.copyOf().also { it[0] = it[0] or (0xFF shl 24) }
        assertEquals(PcScreen.hash(a), PcScreen.hash(c))
        assertNotEquals(PcScreen.hash(IntArray(4)), PcScreen.hash(IntArray(5)))
    }

    @Test
    fun phonePointsLandOnTheShownDisplay() {
        assertEquals(0 to 0, PcScreen.toDesktop(main, 0f, 0f))
        assertEquals(3199 to 1799, PcScreen.toDesktop(main, 1f, 1f))
        assertEquals(1600 to 450, PcScreen.toDesktop(main, 0.5f, 0.25f))
        // A display left of the main one has negative coordinates.
        assertEquals(-1920 to 0, PcScreen.toDesktop(left, 0f, 0f))
        assertEquals(-1 to 1079, PcScreen.toDesktop(left, 1f, 1f))
        assertEquals(-960 to 540, PcScreen.toDesktop(left, 0.5f, 0.5f))
        assertEquals(-1920 to 1079, PcScreen.toDesktop(left, -3f, 9f), "Clamped to the display")
        assertNull(PcScreen.toDesktop(main, Float.NaN, 0.5f))
        assertNull(PcScreen.toDesktop(main, 0.5f, Float.POSITIVE_INFINITY))
        assertNull(PcScreen.toDesktop(main, null, 0.5f))
        assertEquals(left, PcScreen.pick(listOf(main, left), 22))
        assertEquals(main, PcScreen.pick(listOf(left, main), 99), "An unknown display is the main one")
        assertEquals(left, PcScreen.pick(listOf(left), null))
        assertNull(PcScreen.pick(emptyList(), null))
        assertEquals(1600 to 900, JpegFrames.fit(3200, 1800, PcScreen.MAX_LONG_SIDE))
    }

    @Test
    fun aStillScreenSendsOnePictureAndAChangeSendsAnother() {
        val screen = FakeScreen(listOf(main, left))
        val out = CopyOnWriteArrayList<Envelope>()
        val sender = PcScreenSender(screen, main, scope(), send = { out += it; true }, onEnded = {})
        sender.start()
        closers += sender::stop
        waitUntil("Started and a picture") { out.any { it.type == MessageType.MACSCREEN_FRAME } }
        val started = out.first()
        assertEquals(MessageType.MACSCREEN_STARTED, started.type, "The size goes before any picture")
        assertEquals(JpegFrames.FORMAT, started.format)
        assertEquals(ScreenInfo(1600, 900), started.screen)
        assertEquals(main.name, started.text)
        assertEquals(listOf(DisplayChoice(11, main.name, true), DisplayChoice(22, left.name, false)), started.displays)
        assertEquals(11L, started.displayId)
        val frame = out.first { it.type == MessageType.MACSCREEN_FRAME }
        assertEquals(JpegFrames.FORMAT, frame.format)
        assertNull(frame.keyFrame, "Every picture stands alone")
        assertTrue(JpegFrames.looksLikeJpeg(frame.raw))
        // Nothing changes: it keeps looking, and sends nothing.
        waitUntil("Looked again") { sender.unchanged >= 5 }
        assertEquals(1, out.count { it.type == MessageType.MACSCREEN_FRAME })
        assertEquals(1, screen.encodes.get(), "An unchanged picture isn't even encoded")
        // Something moves on the screen.
        screen.color = 2
        waitUntil("A new picture") { out.count { it.type == MessageType.MACSCREEN_FRAME } == 2 }
        assertEquals(2.toByte(), out.last { it.type == MessageType.MACSCREEN_FRAME }.raw!![4])
        // The phone asks for a picture (it came back): the same one goes again.
        sender.resync()
        waitUntil("Sent again") { out.count { it.type == MessageType.MACSCREEN_FRAME } == 3 }
        // Another display: a new size first, then its picture.
        sender.show(left)
        waitUntil("The other display") { out.count { it.type == MessageType.MACSCREEN_FRAME } == 4 }
        val again = out.last { it.type == MessageType.MACSCREEN_STARTED }
        assertEquals(ScreenInfo(1600, 900), again.screen)
        assertEquals(22L, again.displayId)
        assertEquals(22.toByte(), out.last().raw!![5])
        sender.stop()
        val count = out.size
        never("Nothing after stop", 400) { out.size != count }
    }

    @Test
    fun aPictureThatWasDroppedIsTriedAgain() {
        val screen = FakeScreen(listOf(main)).apply { tooBigOnce = true }
        val out = CopyOnWriteArrayList<Envelope>()
        val sender = PcScreenSender(screen, main, scope(), send = { out += it; true }, onEnded = {})
        sender.start()
        closers += sender::stop
        // The first is over 760 KiB and dropped; the screen didn't change, but it still goes out, smaller.
        waitUntil("Sent after the drop") { out.any { it.type == MessageType.MACSCREEN_FRAME } }
        assertEquals(2, screen.encodes.get())
        assertTrue(out.single { it.type == MessageType.MACSCREEN_FRAME }.raw!!.size < 100)
        assertTrue(sender.pacer.quality < 70, "Too big lowers the quality")
    }

    @Test
    fun aCaptureThatKeepsFailingEnds() {
        val screen = FakeScreen(listOf(main)).apply { fail = true }
        val ended = CopyOnWriteArrayList<String>()
        val sender = PcScreenSender(screen, main, scope(), send = { true }, onEnded = { ended += it })
        sender.start()
        waitUntil("Ended", 15) { ended.isNotEmpty() }
        assertEquals(listOf("This PC's screen couldn't be captured."), ended)
        assertFalse(sender.isRunning)
        // The phone going away ends it too.
        val gone = CopyOnWriteArrayList<String>()
        val second = PcScreenSender(FakeScreen(listOf(main)), main, scope(), send = { it.type != MessageType.MACSCREEN_FRAME }, onEnded = { gone += it })
        second.start()
        waitUntil("Ended when frames don't go out") { gone.isNotEmpty() }
        assertEquals(listOf("The phone stopped taking the picture."), gone)
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

    private inner class Pc(caps: List<String>?) {
        val dir: File = Files.createTempDirectory("dysk-pcscreen").toFile()
        val screen = FakeScreen(listOf(main, left))
        @Volatile var on = false
        @Volatile var wayland = false
        @Volatile var control = true
        val pointer = CopyOnWriteArrayList<String>()
        val viewed = CopyOnWriteArrayList<String>()
        val hub = ComputerHub(
            dir, scope(),
            localHello = { paired -> Hello("Test PC", "linux", "Test", PhoneLinkProtocol.VERSION, paired, "1.0.0", caps) },
            events = object : HubEvents {
                override fun clipboard(text: String, from: String) = true
                override fun ring(on: Boolean, from: String) {}
                override val canControl: Boolean get() = control
                override fun screenRefusal(from: String) = when {
                    wayland -> PcScreen.WAYLAND_REFUSAL
                    !on -> PcScreen.TURNED_OFF
                    else -> null
                }
                override fun screenSource(): PcScreenSource = screen
                override fun screenPointer(kind: String, x: Int, y: Int, from: String) { pointer += "$kind $x,$y" }
                override fun screenViewed(from: String) { viewed += from }
            },
            fileSinks = sink(dir),
            port = 0,
        ).also { it.start(); closers += it::shutdown }
    }

    private inner class Phone {
        val dir: File = Files.createTempDirectory("dysk-pcscreen-phone").toFile()
        val seen = CopyOnWriteArrayList<Envelope>()
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
            // As MacScreenScreen does: everything for the Mac screen viewer arrives here.
            it.macScreenSink = { _, envelope -> seen += envelope }
        }

        fun of(type: String) = seen.filter { it.type == type }
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
    fun aPhoneSeesThePcsScreenTapsItAndStops() {
        val pc = Pc(PhoneLinkProtocol.PC_CAPS)
        val phone = Phone()
        val phoneId = paired(pc, phone)
        val pcId = pc.hub.deviceId
        assertTrue(phone.manager.peers.value.first { it.id == pcId }.canSeeComputerScreen)

        // Off by default on the PC: refused, nothing captured.
        phone.manager.startMacScreen(pcId)
        waitUntil("Refused while off") { phone.of(MessageType.MACSCREEN_STOPPED).any { it.error == PcScreen.TURNED_OFF } }
        // Wayland is said first, whatever the switch.
        pc.wayland = true
        pc.on = true
        phone.manager.startMacScreen(pcId)
        waitUntil("Wayland") { phone.of(MessageType.MACSCREEN_STOPPED).any { it.error == PcScreen.WAYLAND_REFUSAL } }
        pc.wayland = false
        // No extra display on a PC.
        phone.manager.extendMacScreen(pcId, 1280, 600)
        waitUntil("No extra display") { phone.of(MessageType.MACSCREEN_STOPPED).any { it.error == PcScreen.NO_EXTEND } }
        assertEquals(0, pc.screen.grabs.get())
        assertNull(pc.hub.screenTo.value)

        // On: the size, the displays, then JPEG pictures, through the phone's own code.
        phone.seen.clear()
        phone.manager.startMacScreen(pcId)
        waitUntil("A picture") { phone.of(MessageType.MACSCREEN_FRAME).isNotEmpty() }
        assertEquals(phoneId, pc.hub.screenTo.value)
        assertEquals(listOf(phoneId), pc.viewed)
        val started = phone.of(MessageType.MACSCREEN_STARTED).first()
        assertEquals(JpegFrames.FORMAT, started.format)
        assertEquals(ScreenInfo(1600, 900), started.screen)
        assertEquals(2, started.displays?.size)
        assertEquals(11L, started.displayId)
        val picture = JpegFrames.pictureOf(phone.of(MessageType.MACSCREEN_FRAME).first())
        assertNotNull(picture, "The phone takes the picture")
        assertEquals(11.toByte(), picture[5])
        // A still screen: no more pictures.
        val frames = phone.of(MessageType.MACSCREEN_FRAME).size
        never("Still", 500) { phone.of(MessageType.MACSCREEN_FRAME).size != frames }

        // Taps land on the shown display, in desktop pixels.
        phone.manager.sendMacPointer(pcId, "click", 0.5f, 0.25f)
        waitUntil("Clicked") { pc.pointer.isNotEmpty() }
        assertEquals("click 1600,450", pc.pointer.single())
        phone.manager.post(pcId, Envelope(MessageType.MACSCREEN_POINTER, control = ControlEvent("teleport", 0.5f, 0.5f)))
        phone.manager.post(pcId, Envelope(MessageType.MACSCREEN_POINTER, control = ControlEvent("down", null, 0.5f)))
        // While phones may not control the PC, taps do nothing; the picture goes on.
        pc.control = false
        phone.manager.sendMacPointer(pcId, "down", 0.1f, 0.1f)
        never("No taps while control is off", 400) { pc.pointer.size != 1 }
        pc.control = true

        // The other display, on the left.
        phone.manager.startMacScreen(pcId, 22)
        waitUntil("The other display") { phone.of(MessageType.MACSCREEN_STARTED).any { it.displayId == 22L } }
        waitUntil("Its picture") { phone.of(MessageType.MACSCREEN_FRAME).any { JpegFrames.pictureOf(it)?.get(5) == 22.toByte() } }
        phone.manager.sendMacPointer(pcId, "right", 0f, 1f)
        waitUntil("Right-clicked") { pc.pointer.size == 2 }
        assertEquals("right -1920,1079", pc.pointer.last())
        assertEquals(1, pc.viewed.size, "Switching displays isn't a new viewer")

        // Stop on the PC: the phone is told.
        pc.hub.stopScreenOut()
        waitUntil("Stopped here") { phone.of(MessageType.MACSCREEN_STOPPED).any { it.error == PcScreen.STOPPED_HERE } }
        assertNull(pc.hub.screenTo.value)
        phone.manager.sendMacPointer(pcId, "click", 0.5f, 0.5f)
        never("No taps after stop", 400) { pc.pointer.size != 2 }

        // The phone stops; then it starts again and leaves.
        phone.manager.startMacScreen(pcId)
        waitUntil("Again") { pc.hub.screenTo.value == phoneId }
        phone.manager.stopMacScreen(pcId)
        waitUntil("Stopped by the phone") { pc.hub.screenTo.value == null }
        phone.manager.startMacScreen(pcId)
        waitUntil("A third time") { pc.hub.screenTo.value == phoneId }
        phone.manager.shutdown()
        waitUntil("Ended on disconnect") { pc.hub.screenTo.value == null }
    }

    @Test
    fun aPcWithoutTheCapIsNotOfferedAndItsPicturesAreDropped() {
        // A PC that doesn't list pc-screen-out: even if it answered, the phone takes nothing from it.
        val pc = Pc(PhoneLinkProtocol.CAPS + PhoneLinkProtocol.PC_FEATURE_CAPS)
        pc.on = true
        val phone = Phone()
        paired(pc, phone)
        assertFalse(phone.manager.peers.value.first { it.id == pc.hub.deviceId }.canSeeComputerScreen)
        phone.manager.startMacScreen(pc.hub.deviceId)
        waitUntil("The PC answered") { pc.screen.encodes.get() > 0 }
        never("Dropped by the phone") { phone.seen.isNotEmpty() }
    }
}
