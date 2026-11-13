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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The phone as a PC's second display (`pc-display`): the size rules, then the real phone ConnectionManager against
 * the real ComputerHub over TLS, with a fake monitor in place of the compositor or the driver.
 */
class PcSecondDisplayTest {
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

    private fun never(message: String, ms: Long = 600, check: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (check()) throw AssertionError(message)
            Thread.sleep(20)
        }
    }

    @Test
    fun theMonitorKeepsThePhonesShapeInPixels() {
        // What the phone asks: its shape at 600 points on the short side (MacScreenMath.extraDisplaySize).
        assertEquals(1328 to 600, PcSecondDisplay.size(ScreenInfo(1332, 600)), "Width a multiple of 8")
        assertEquals(600 to 1332, PcSecondDisplay.size(ScreenInfo(600, 1333)), "Upright, height even")
        assertEquals(1000 to 450, PcSecondDisplay.size(ScreenInfo(1000, 450), large = true), "Larger text is smaller")
        assertEquals(1000 to 450, PcSecondDisplay.size(ScreenInfo(800, 360)), "Too small grows to 450 points")
        assertEquals(800 to 360, PcSecondDisplay.size(ScreenInfo(800, 360), large = true))
        assertNull(PcSecondDisplay.size(ScreenInfo(500, 2100)), "Odd shapes are refused")
        assertNull(PcSecondDisplay.size(ScreenInfo(0, 600)))
        assertNull(PcSecondDisplay.size(null))
        assertNull(PcSecondDisplay.size(ScreenInfo(5000, 1300)), "Too wide")
    }

    @Test
    fun theCapIsListedOnlyWhenThePcSaysSo() {
        fun peer(caps: List<String>) = Peer("x", "X", "linux", "", true, true, null, caps = caps)
        assertFalse(PhoneLinkProtocol.CAP_PC_DISPLAY in PhoneLinkProtocol.PC_CAPS, "Added per PC, only where it can add a monitor")
        assertFalse(peer(PhoneLinkProtocol.PC_CAPS).canExtend)
        assertTrue(peer(PhoneLinkProtocol.PC_CAPS + PhoneLinkProtocol.CAP_PC_DISPLAY).canExtend)
        assertFalse(Peer("x", "X", "android", "", true, true, null, caps = PhoneLinkProtocol.PC_CAPS + PhoneLinkProtocol.CAP_PC_DISPLAY).canExtend)
    }

    /** A monitor that appears when opened: one colour, and a "JPEG" with its size in it. */
    private class FakeMonitor(val width: Int, val height: Int, val handlesTaps: Boolean) : PcExtraDisplay {
        val released = AtomicInteger()
        val taps = CopyOnWriteArrayList<String>()
        @Volatile var color = 3
        val display = PcDisplay(77, "Pixel (${width}×$height)", false, 1920, 0, width, height)
        override fun refusal(): String? = null
        override fun displays() = listOf(display)
        override fun grab(display: PcDisplay, maxLong: Int): ScreenGrab {
            val (w, h) = JpegFrames.fit(width, height, maxLong)
            val shade = color
            return ScreenGrab(w, h, IntArray(w * h) { shade }) { byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), shade.toByte(), (width / 8).toByte()) }
        }
        override fun pointer(kind: String, x: Float, y: Float): Boolean {
            if (handlesTaps) taps += "$kind $x,$y"
            return handlesTaps
        }
        override fun release() { released.incrementAndGet() }
    }

    private class FakeDisplays(var handlesTaps: Boolean = true) : PcExtraDisplays {
        @Volatile var refusal: String? = null
        @Volatile var fail: String? = null
        @Volatile var slowMs = 0L
        val opened = CopyOnWriteArrayList<FakeMonitor>()
        val asked = CopyOnWriteArrayList<String>()
        override fun refusal() = refusal
        override fun open(width: Int, height: Int, phoneName: String): PcExtraDisplay {
            asked += "${width}x$height $phoneName"
            if (slowMs > 0) Thread.sleep(slowMs)
            fail?.let { throw PcExtendFailed(it) }
            return FakeMonitor(width, height, handlesTaps).also { opened += it }
        }
    }

    private fun sink(folder: File) = FileSinkFactory { name, _ ->
        val out = ByteArrayOutputStream()
        object : FileSink {
            override fun write(bytes: ByteArray) = out.write(bytes)
            override fun commit(): String = File(folder, name).also { it.writeBytes(out.toByteArray()) }.path
            override fun abort() {}
        }
    }

    private inner class Pc(caps: List<String>) {
        val dir: File = Files.createTempDirectory("dysk-extend").toFile()
        val displays = FakeDisplays()
        @Volatile var on = true
        @Volatile var control = true
        val pointer = CopyOnWriteArrayList<String>()
        val started = CopyOnWriteArrayList<String>()
        val hub = ComputerHub(
            dir, scope(),
            localHello = { paired -> Hello("Test PC", "linux", "Test", PhoneLinkProtocol.VERSION, paired, "1.0.0", caps) },
            events = object : HubEvents {
                override fun clipboard(text: String, from: String) = true
                override fun ring(on: Boolean, from: String) {}
                override val canControl: Boolean get() = control
                override fun screenRefusal(from: String) = if (on) null else PcScreen.TURNED_OFF
                override fun extendRefusal(from: String) = if (on) null else PcScreen.EXTEND_TURNED_OFF
                override fun extraDisplays(): PcExtraDisplays = displays
                override fun extendStarted(from: String, width: Int, height: Int) { started += "$from ${width}x$height" }
                override fun screenPointer(kind: String, x: Int, y: Int, from: String) { pointer += "$kind $x,$y" }
            },
            fileSinks = sink(dir),
            port = 0,
        ).also { it.start(); closers += it::shutdown }
    }

    private inner class Phone {
        val dir: File = Files.createTempDirectory("dysk-extend-phone").toFile()
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
    fun aPhoneBecomesTheSecondDisplayTurnsAndStops() {
        val pc = Pc(PhoneLinkProtocol.PC_CAPS + PhoneLinkProtocol.CAP_PC_DISPLAY)
        val phone = Phone()
        val phoneId = paired(pc, phone)
        val pcId = pc.hub.deviceId
        assertTrue(phone.manager.peers.value.first { it.id == pcId }.canExtend, "The phone offers Second display for this PC")

        // Off on the PC: refused before anything is added.
        pc.on = false
        phone.manager.extendMacScreen(pcId, 1332, 600)
        waitUntil("Refused while off") { phone.of(MessageType.MACSCREEN_STOPPED).any { it.error == PcScreen.EXTEND_TURNED_OFF } }
        assertTrue(pc.displays.asked.isEmpty())
        pc.on = true
        // What can't be added here is said as the PC words it.
        pc.displays.refusal = "Install the virtual display driver on the PC first."
        phone.manager.extendMacScreen(pcId, 1332, 600)
        waitUntil("Refused by the PC") { phone.of(MessageType.MACSCREEN_STOPPED).any { it.error == "Install the virtual display driver on the PC first." } }
        pc.displays.refusal = null
        phone.manager.extendMacScreen(pcId, 500, 2100)
        waitUntil("Odd shape") { phone.of(MessageType.MACSCREEN_STOPPED).any { it.error == PcSecondDisplay.ODD_SIZE } }

        // Added at the phone's shape, then pictures through the phone's own code, without a display picker.
        phone.seen.clear()
        phone.manager.extendMacScreen(pcId, 1332, 600)
        waitUntil("A picture") { phone.of(MessageType.MACSCREEN_FRAME).isNotEmpty() }
        assertEquals(listOf("1328x600 Pixel"), pc.displays.asked.takeLast(1))
        val started = phone.of(MessageType.MACSCREEN_STARTED).first()
        assertEquals(JpegFrames.FORMAT, started.format)
        assertEquals(ScreenInfo(1328, 600), started.screen)
        assertNull(started.displays, "An extra display has no other display to pick")
        assertEquals(phoneId, pc.hub.screenTo.value)
        assertEquals(HubExtend(phoneId, 1328, 600, active = true), pc.hub.extend.value)
        assertEquals(listOf("$phoneId 1328x600"), pc.started)
        assertEquals((1328 / 8).toByte(), JpegFrames.pictureOf(phone.of(MessageType.MACSCREEN_FRAME).first())!![5])

        // Taps go to the monitor's own session (Wayland), as 0 to 1 across it.
        phone.manager.sendMacPointer(pcId, "click", 0.5f, 0.25f)
        val first = pc.displays.opened.single()
        waitUntil("Tapped") { first.taps.isNotEmpty() }
        assertEquals("click 0.5,0.25", first.taps.single())
        assertTrue(pc.pointer.isEmpty())
        // Off: no taps, the picture goes on.
        pc.control = false
        phone.manager.sendMacPointer(pcId, "click", 0.1f, 0.1f)
        never("No taps while control is off") { first.taps.size != 1 }
        pc.control = true

        // Asked again at the same size (the app came back): the same monitor, its size again.
        val before = phone.of(MessageType.MACSCREEN_STARTED).size
        phone.manager.extendMacScreen(pcId, 1332, 600)
        waitUntil("Size again") { phone.of(MessageType.MACSCREEN_STARTED).size > before }
        assertEquals(1, pc.displays.opened.size)

        // The phone turns: the monitor is made again upright, and the first one goes.
        phone.manager.extendMacScreen(pcId, 600, 1332)
        waitUntil("Upright") { phone.of(MessageType.MACSCREEN_STARTED).any { it.screen == ScreenInfo(600, 1332) } }
        assertEquals(1, first.released.get(), "The landscape monitor is removed")
        assertEquals(2, pc.displays.opened.size)
        val upright = pc.displays.opened.last()

        // Stop on the PC: the phone is told, the monitor goes.
        pc.hub.stopScreenOut()
        waitUntil("Stopped here") { phone.of(MessageType.MACSCREEN_STOPPED).any { it.error == PcScreen.STOPPED_HERE } }
        assertEquals(1, upright.released.get())
        assertNull(pc.hub.extend.value)
        assertNull(pc.hub.screenTo.value)

        // Taps in desktop pixels where the monitor has a place there (X11, Windows).
        pc.displays.handlesTaps = false
        phone.manager.extendMacScreen(pcId, 1332, 600)
        waitUntil("Again") { pc.hub.extend.value?.active == true }
        phone.manager.sendMacPointer(pcId, "right", 0f, 1f)
        waitUntil("Right-clicked in desktop pixels") { pc.pointer.isNotEmpty() }
        assertEquals("right 1920,599", pc.pointer.single())

        // The phone stops it; then it starts again and the phone leaves.
        val third = pc.displays.opened.last()
        phone.manager.stopMacScreen(pcId)
        waitUntil("Stopped by the phone") { third.released.get() == 1 && pc.hub.extend.value == null }
        phone.manager.extendMacScreen(pcId, 1332, 600)
        waitUntil("Once more") { pc.hub.extend.value?.active == true }
        val last = pc.displays.opened.last()
        phone.manager.shutdown()
        waitUntil("Removed on disconnect") { last.released.get() == 1 && pc.hub.screenTo.value == null }
    }

    @Test
    fun aMonitorThatTookTooLongIsRemovedAndAFailureIsSaid() {
        val pc = Pc(PhoneLinkProtocol.PC_CAPS + PhoneLinkProtocol.CAP_PC_DISPLAY)
        val phone = Phone()
        paired(pc, phone)
        val pcId = pc.hub.deviceId
        pc.displays.fail = "Adding the extra display wasn't allowed on the PC."
        phone.manager.extendMacScreen(pcId, 1332, 600)
        waitUntil("Failed") { phone.of(MessageType.MACSCREEN_STOPPED).any { it.error == "Adding the extra display wasn't allowed on the PC." } }
        assertNull(pc.hub.extend.value)
        // Stopped while the PC still asks the user: the monitor that comes later is removed at once.
        pc.displays.fail = null
        pc.displays.slowMs = 600
        phone.manager.extendMacScreen(pcId, 1332, 600)
        waitUntil("Asking") { pc.hub.extend.value?.active == false }
        pc.hub.stopScreenOut()
        waitUntil("Told") { phone.of(MessageType.MACSCREEN_STOPPED).any { it.error == PcScreen.STOPPED_HERE } }
        waitUntil("Opened late, then removed") { pc.displays.opened.singleOrNull()?.released?.get() == 1 }
        never("No pictures after the stop") { phone.of(MessageType.MACSCREEN_FRAME).isNotEmpty() }
        assertNull(pc.hub.screenTo.value)
    }

    @Test
    fun aPcWithoutTheCapIsNotOffered() {
        val pc = Pc(PhoneLinkProtocol.PC_CAPS)
        val phone = Phone()
        paired(pc, phone)
        assertFalse(phone.manager.peers.value.first { it.id == pc.hub.deviceId }.canExtend)
    }
}
