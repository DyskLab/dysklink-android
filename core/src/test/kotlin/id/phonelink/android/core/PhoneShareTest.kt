package id.phonelink.android.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
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

/** Another phone's screen and camera: the per-phone switches, the shutter, and both over TLS on this machine. */
class PhoneShareTest {
    private val scopes = mutableListOf<CoroutineScope>()
    private val managers = mutableListOf<ConnectionManager>()

    @AfterTest
    fun close() {
        managers.forEach { it.shutdown() }
        scopes.forEach { it.cancel() }
    }

    // The rules

    private val nothing = PeerAccess()
    private val viewOnly = PeerAccess(screen = true)
    private val everything = PeerAccess(screen = true, control = true, camera = true)

    @Test
    fun aPhoneWithoutTheSwitchGetsNothing() {
        // Only the requests pass, and they only ask.
        PhoneRules.FROM_VIEWER.forEach { type ->
            val verdict = PhoneRules.fromViewer(type, nothing, sharingScreenWithSender = true, controlReady = true)
            val expected = when (type) {
                MessageType.SCREEN_REQUEST, MessageType.CAMERA_REQUEST, MessageType.SCREEN_STOP, MessageType.CAMERA_STOP -> PhoneRules.Verdict.Deliver
                else -> PhoneRules.Verdict.Drop
            }
            assertEquals(expected, verdict, type)
        }
        // "Never" or the switch off: requests are refused with an answer, everything else dropped.
        val refused = PeerAccess(screen = false, camera = false, control = true)
        assertEquals(PhoneRules.Verdict.Refuse, PhoneRules.fromViewer(MessageType.SCREEN_REQUEST, refused, true, true))
        assertEquals(PhoneRules.Verdict.Refuse, PhoneRules.fromViewer(MessageType.CAMERA_REQUEST, refused, true, true))
        assertEquals(PhoneRules.Verdict.Drop, PhoneRules.fromViewer(MessageType.CONTROL, refused, true, true))
        assertEquals(PhoneRules.Verdict.Drop, PhoneRules.fromViewer(MessageType.CAMERA_SHUTTER, refused, true, true))
        assertEquals(PhoneRules.Verdict.Drop, PhoneRules.fromViewer(MessageType.SCREEN_SYNC, refused, true, true))
    }

    @Test
    fun withTheSwitchTheMessagesGoThrough() {
        PhoneRules.FROM_VIEWER.forEach { type ->
            assertEquals(PhoneRules.Verdict.Deliver, PhoneRules.fromViewer(type, everything, sharingScreenWithSender = true, controlReady = true), type)
        }
        // Taps need all of it: viewing allowed, control allowed, the screen shared with that phone now, and Accessibility.
        assertEquals(PhoneRules.Verdict.Drop, PhoneRules.fromViewer(MessageType.CONTROL, viewOnly, true, true))
        assertEquals(PhoneRules.Verdict.Drop, PhoneRules.fromViewer(MessageType.CONTROL, everything, sharingScreenWithSender = false, controlReady = true))
        assertEquals(PhoneRules.Verdict.Drop, PhoneRules.fromViewer(MessageType.CONTROL, everything, sharingScreenWithSender = true, controlReady = false))
        assertEquals(PhoneRules.Verdict.Drop, PhoneRules.fromViewer(MessageType.CONTROL, everything.copy(screen = null), true, true))
        assertTrue(PhoneRules.canControl(everything, controlReady = true))
        assertFalse(PhoneRules.canControl(viewOnly, controlReady = true))
        assertFalse(PhoneRules.canControl(everything, controlReady = false))
        // The camera switch doesn't open the screen, and the other way round.
        assertEquals(PhoneRules.Verdict.Drop, PhoneRules.fromViewer(MessageType.CAMERA_SHUTTER, viewOnly, true, true))
        assertEquals(PhoneRules.Verdict.Drop, PhoneRules.fromViewer(MessageType.SCREEN_SYNC, PeerAccess(camera = true), true, true))
        // Anything else, such as SMS or the Mac's own screen, isn't part of these rules.
        listOf(MessageType.SMS_LIST, MessageType.MACSCREEN_START, MessageType.TERMINAL_OPEN, MessageType.INPUT).forEach {
            assertEquals(PhoneRules.Verdict.Drop, PhoneRules.fromViewer(it, everything, true, true), it)
            assertFalse(it in PhoneRules.ALL)
        }
    }

    @Test
    fun videoOnlyComesBackWhenAsked() {
        assertFalse(PhoneRules.toViewer(MessageType.SCREEN_FRAME, emptySet()))
        assertFalse(PhoneRules.toViewer(MessageType.SCREEN_FRAME, setOf(P2pKind.Camera)))
        assertTrue(PhoneRules.toViewer(MessageType.SCREEN_FRAME, setOf(P2pKind.Screen)))
        assertFalse(PhoneRules.toViewer(MessageType.CAMERA_SHOT, setOf(P2pKind.Screen)))
        assertTrue(PhoneRules.toViewer(MessageType.CAMERA_SHOT, setOf(P2pKind.Camera)))
        assertFalse(PhoneRules.toViewer(MessageType.SCREEN_REQUEST, setOf(P2pKind.Screen, P2pKind.Camera)))
    }

    @Test
    fun oldPhonesHideTheButtons() {
        fun peer(platform: String, caps: List<String>) = Peer("x", "X", platform, "m", isPaired = true, isConnected = true, info = null, caps = caps)
        assertTrue(peer("android", PhoneLinkProtocol.PHONE_CAPS).canViewScreen)
        assertTrue(peer("android", PhoneLinkProtocol.PHONE_CAPS).canUseCamera)
        // 1.2.0 lists only bin1; 1.1.x lists nothing.
        assertFalse(peer("android", PhoneLinkProtocol.CAPS).canViewScreen)
        assertFalse(peer("android", PhoneLinkProtocol.CAPS).canUseCamera)
        assertFalse(peer("android", emptyList()).canViewScreen)
        // A computer never gets them, whatever it lists.
        assertFalse(peer("macos", PhoneLinkProtocol.PHONE_CAPS).canViewScreen)
        assertFalse(PhoneRules.supports(Hello("m", "macos", "m", 3, true, caps = PhoneLinkProtocol.PHONE_CAPS), PhoneLinkProtocol.CAP_P2P_SCREEN))
        assertTrue(PhoneRules.supports(Hello("p", "android", "m", 3, true, caps = PhoneLinkProtocol.PHONE_CAPS), PhoneLinkProtocol.CAP_P2P_CAMERA))
        assertFalse(PhoneRules.supports(null, PhoneLinkProtocol.CAP_P2P_CAMERA))
        // The desktop apps keep advertising only binary frames.
        assertEquals(listOf(PhoneLinkProtocol.CAP_BINARY), PhoneLinkProtocol.CAPS)
    }

    @Test
    fun accessIsKeptAndForgotten() {
        val dir = Files.createTempDirectory("dysk-access").toFile()
        val access = PhoneAccess(dir)
        assertEquals(PeerAccess(), access.of("a"))
        val (before, after) = access.update("a") { it.copy(screen = true, control = true) }
        assertEquals(PeerAccess(), before)
        assertEquals(PeerAccess(screen = true, control = true), after)
        assertEquals(PeerAccess(screen = true, control = true), PhoneAccess(dir).of("a"))
        access.forget("a")
        assertEquals(PeerAccess(), PhoneAccess(dir).of("a"))
    }

    @Test
    fun shutterAnswersInOrder() {
        val shots = ShotQueue(max = 2)
        assertNull(shots.ask("one"))
        assertNull(shots.ask("two"))
        // A third while two wait is refused at once, with its own key.
        val busy = shots.ask("three")
        assertEquals(MessageType.CAMERA_SHOT, busy?.type)
        assertEquals("three", busy?.key)
        assertNotNull(busy?.error)
        assertNotNull(shots.ask("")?.error)

        val first = shots.taken("DyskLink_1.jpg")!!
        assertEquals("one", first.key)
        assertEquals("photo", first.text)
        assertEquals("DyskLink_1.jpg", first.file?.name)
        assertNull(first.error)
        val second = shots.failed("The camera couldn't take the photo")!!
        assertEquals("two", second.key)
        assertNull(second.file)
        assertNull(shots.taken("late.jpg"), "Nobody asked")

        assertNull(shots.ask("four"))
        assertEquals(listOf("four"), shots.clear("The camera stopped").map { it.key })
        assertEquals(0, shots.size)
        assertTrue(ShotQueue.fileName(0L).matches(Regex("DyskLink_\\d{8}_\\d{6}\\.jpg")))
    }

    @Test
    fun zoomStaysInRange() {
        assertEquals(1f, CameraMath.clampZoom(0.2f, 8f))
        assertEquals(8f, CameraMath.clampZoom(50f, 8f))
        assertEquals(1f, CameraMath.clampZoom(Float.NaN, 8f))
        assertEquals(1f, CameraMath.clampZoom(null, 8f))
        assertEquals(1f, CameraMath.clampZoom(3f, 0.5f))
        assertTrue(intArrayOf(0, 0, 4000, 3000).contentEquals(CameraMath.cropRegion(4000, 3000, 1f)))
        assertTrue(intArrayOf(1000, 750, 2000, 1500).contentEquals(CameraMath.cropRegion(4000, 3000, 2f)))
        assertEquals(4000 to 3000, CameraMath.largest(listOf(1920 to 1080, 4000 to 3000, 3264 to 2448)))
    }

    // Over TLS

    private inner class Phone(name: String, caps: List<String>? = PhoneLinkProtocol.PHONE_CAPS, platform: String = "android") {
        val dir: File = Files.createTempDirectory("dysk-$name").toFile()
        val received = File(dir, "received").apply { mkdirs() }
        val screenAsks = CopyOnWriteArrayList<String>()
        val syncs = CopyOnWriteArrayList<String>()
        val stops = CopyOnWriteArrayList<String>()
        val touches = CopyOnWriteArrayList<ControlEvent>()
        val camera = CopyOnWriteArrayList<Envelope>()
        val viewed = CopyOnWriteArrayList<Envelope>()
        @Volatile var accessibility = true
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val manager = ConnectionManager(
            dir, scope,
            localHello = { paired -> Hello(name, platform, "Test $name", PhoneLinkProtocol.VERSION, paired, caps = caps) },
            localInfo = { DeviceInfo(batteryPercent = 50) },
            isUiVisible = { false },
            actions = object : DeviceActions {
                override fun setClipboard(text: String) {}
                override fun media(action: String) {}
            },
            fileSinks = { fileName, _ ->
                val out = ByteArrayOutputStream()
                object : FileSink {
                    override fun write(bytes: ByteArray) = out.write(bytes)
                    override fun commit(): String = File(received, fileName).also { it.writeBytes(out.toByteArray()) }.path
                    override fun abort() {}
                }
            },
            phoneData = object : PhoneData {
                override val storageRoot = dir
                // Every Mac feature is granted, so only the per-phone switches can stop a phone.
                override fun isGranted(feature: PhoneFeature) = feature != PhoneFeature.Control || accessibility
                override fun activeNotifications() = emptyList<NotificationItem>()
                override fun dismissNotification(key: String) {}
                override fun readSms(limit: Int, afterId: Long) = emptyList<SmsItem>()
                override fun sendSms(address: String, body: String, done: (String?) -> Unit) = done("no")
                override fun readContacts() = emptyList<ContactItem>()
                override fun requestScreen(peerId: String) { screenAsks += peerId }
                override fun syncScreen(peerId: String) { syncs += peerId }
                override fun stopScreen(peerId: String) { stops += peerId }
                override fun control(event: ControlEvent) { touches += event }
            },
        ).also { managers += it }.also { cm ->
            cm.cameraSink = { _, envelope -> camera += envelope }
            cm.phoneViewSink = { _, envelope -> viewed += envelope }
            Control.entries.forEach { cm.phone.setEnabled(it.feature, true) }
        }
        val id get() = manager.localDeviceId
        fun peer(other: Phone) = manager.peers.value.firstOrNull { it.id == other.id }
    }

    /** Every Mac-facing feature on, to show they don't open anything to a phone. */
    private enum class Control(val feature: PhoneFeature) { Screen(PhoneFeature.Screen), Touch(PhoneFeature.Control), Sms(PhoneFeature.Sms) }

    // Generous: the suite also runs while the integration script loads this machine.
    private fun waitUntil(message: String, seconds: Int = 25, check: () -> Boolean) {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError(message)
            Thread.sleep(20)
        }
    }

    /** Lets messages already sent arrive, for checks that something did not happen. */
    private fun settle(from: Phone, to: Phone) {
        from.manager.sendDirect(to.id, Envelope(MessageType.PING))
        Thread.sleep(300)
    }

    private fun pair(a: Phone, b: Phone) {
        b.manager.startServer()
        waitUntil("Not listening") { b.manager.serverPort.value != null }
        a.manager.addManual("127.0.0.1", b.manager.serverPort.value!!)
        waitUntil("They didn't connect") { a.peer(b)?.isConnected == true && b.peer(a)?.isConnected == true }
        a.manager.requestPairing(b.id)
        waitUntil("No code") { b.manager.prompt.value?.showsCode == true && a.manager.prompt.value != null }
        a.manager.submitCode(b.manager.prompt.value!!.code)
        waitUntil("Didn't pair") { a.peer(b)?.isPaired == true && b.peer(a)?.isPaired == true }
    }

    @Test
    fun viewAndControlAnotherPhone() {
        val viewer = Phone("Viewer")
        val shared = Phone("Shared")
        val old = Phone("Old", caps = PhoneLinkProtocol.CAPS)
        pair(viewer, shared)
        pair(viewer, old)
        waitUntil("No caps") { viewer.peer(shared)?.canViewScreen == true }
        assertTrue(viewer.peer(shared)!!.canUseCamera)
        assertFalse(viewer.peer(old)!!.canViewScreen, "1.2.0 can't share its screen with a phone")
        assertFalse(viewer.peer(old)!!.canUseCamera)

        // Video nobody asked for is dropped.
        shared.manager.sendDirect(viewer.id, Envelope(MessageType.SCREEN_STARTED, screen = ScreenInfo(720, 1600)))
        shared.manager.sendDirect(viewer.id, Envelope(MessageType.SCREEN_FRAME, keyFrame = true, raw = byteArrayOf(0, 0, 0, 1, 0x65)))
        settle(shared, viewer)
        assertTrue(viewer.viewed.isEmpty())

        // First request: it only asks on the shared phone.
        viewer.manager.viewPhoneScreen(shared.id)
        waitUntil("Shared phone wasn't asked") { shared.screenAsks.contains(viewer.id) }

        // The user said "Never": the next request is refused, with an answer.
        shared.manager.setPhoneAccess(viewer.id) { it.copy(screen = false) }
        waitUntil("Not saved") { shared.manager.phoneAccess.of(viewer.id).screen == false }
        viewer.manager.viewPhoneScreen(shared.id)
        waitUntil("Not refused") { viewer.viewed.any { it.type == MessageType.SCREEN_STOPPED && it.error != null } }
        assertEquals(1, shared.screenAsks.size)

        // Allowed, and the user agreed in Android's dialog: the screen goes to the viewer.
        shared.manager.setPhoneAccess(viewer.id) { it.copy(screen = true) }
        waitUntil("Not saved") { shared.manager.phoneAccess.of(viewer.id).screen == true }
        viewer.viewed.clear()
        viewer.manager.viewPhoneScreen(shared.id)
        waitUntil("Not asked again") { shared.screenAsks.size == 2 }
        shared.manager.screenSharedWith = viewer.id
        assertEquals(false, shared.manager.phoneControlAllowed(viewer.id))
        shared.manager.sendDirect(viewer.id, Envelope(MessageType.SCREEN_STARTED, screen = ScreenInfo(720, 1600), canControl = false))
        repeat(5) { n -> shared.manager.sendDirect(viewer.id, Envelope(MessageType.SCREEN_FRAME, keyFrame = n == 0, raw = ByteArray(2000) { n.toByte() })) }
        waitUntil("No frames") { viewer.viewed.count { it.type == MessageType.SCREEN_FRAME } == 5 }
        assertEquals(720, viewer.viewed.first { it.type == MessageType.SCREEN_STARTED }.screen?.width)
        assertTrue(viewer.viewed.filter { it.type == MessageType.SCREEN_FRAME }.all { it.raw?.size == 2000 }, "Frames arrive whole, binary")
        viewer.manager.syncPhoneScreen(shared.id)
        waitUntil("No sync") { shared.syncs.contains(viewer.id) }

        // Taps: dropped until control is allowed for this phone.
        val tap = ControlEvent("down", 0.5f, 0.5f)
        viewer.manager.sendPhoneControl(shared.id, tap)
        settle(viewer, shared)
        assertTrue(shared.touches.isEmpty())
        shared.manager.setPhoneAccess(viewer.id) { it.copy(control = true) }
        waitUntil("Not saved") { shared.manager.phoneAccess.of(viewer.id).control }
        assertEquals(true, shared.manager.phoneControlAllowed(viewer.id))
        viewer.manager.sendPhoneControl(shared.id, tap)
        viewer.manager.sendPhoneControl(shared.id, ControlEvent("up", 2f, 0.5f))
        viewer.manager.sendPhoneControl(shared.id, ControlEvent("key", key = "home"))
        waitUntil("Taps didn't arrive") { shared.touches.size == 3 }
        assertEquals(listOf("down", "up", "key"), shared.touches.map { it.kind })
        assertEquals(1f, shared.touches[1].x, "Sanitized like taps from a Mac")

        // Without Accessibility, or once the screen is no longer shared with this phone, taps stop.
        shared.accessibility = false
        assertEquals(false, shared.manager.phoneControlAllowed(viewer.id))
        viewer.manager.sendPhoneControl(shared.id, tap)
        settle(viewer, shared)
        shared.accessibility = true
        shared.manager.screenSharedWith = null
        viewer.manager.sendPhoneControl(shared.id, tap)
        settle(viewer, shared)
        assertEquals(3, shared.touches.size)
        shared.manager.screenSharedWith = viewer.id

        // Mac-only messages stay Mac-only, even with every switch on.
        listOf(MessageType.SMS_LIST, MessageType.MACSCREEN_STARTED, MessageType.INPUT, MessageType.NOTCH_STATE).forEach {
            viewer.manager.sendDirect(shared.id, Envelope(it, input = InputEvent("move", 1f, 1f)))
        }
        settle(viewer, shared)
        assertEquals(3, shared.touches.size)
        assertTrue(shared.manager.notch.value.isEmpty())
        assertTrue(viewer.peer(shared)!!.isConnected)

        // Turning viewing off ends the session there.
        shared.manager.setPhoneAccess(viewer.id) { it.copy(screen = false) }
        waitUntil("Session didn't end") { shared.stops.contains(viewer.id) }

        // Stopping from the viewer, then frames after it are dropped.
        viewer.manager.stopPhoneScreen(shared.id)
        waitUntil("No stop") { shared.stops.count { it == viewer.id } >= 2 }
        val before = viewer.viewed.size
        shared.manager.sendDirect(viewer.id, Envelope(MessageType.SCREEN_FRAME, raw = byteArrayOf(1)))
        settle(shared, viewer)
        assertEquals(before, viewer.viewed.size)
    }

    @Test
    fun remoteCameraAndShutter() {
        val viewer = Phone("Remote")
        val camera = Phone("Camera")
        pair(viewer, camera)

        // Not allowed yet: the request asks, but the shutter and zoom are dropped.
        viewer.manager.usePhoneCamera(camera.id)
        waitUntil("No request") { camera.camera.any { it.type == MessageType.CAMERA_REQUEST } }
        viewer.manager.sendPhoneCamera(camera.id, Envelope(MessageType.CAMERA_SHUTTER, key = "early"))
        viewer.manager.sendPhoneCamera(camera.id, Envelope(MessageType.CAMERA_ZOOM, zoom = 2f))
        settle(viewer, camera)
        assertEquals(listOf(MessageType.CAMERA_REQUEST), camera.camera.map { it.type })
        // Only camera messages go this way.
        viewer.manager.sendPhoneCamera(camera.id, Envelope(MessageType.SCREEN_REQUEST))
        settle(viewer, camera)
        assertEquals(1, camera.screenAsks.size + camera.camera.size)

        // Allowed and started on the camera phone: what the camera service does, played by hand.
        camera.manager.setPhoneAccess(viewer.id) { it.copy(camera = true) }
        waitUntil("Not saved") { camera.manager.phoneAccess.of(viewer.id).camera == true }
        val state = CameraState(zoom = 1f, maxZoom = 8f, flash = true, torch = false, recording = false, stills = true)
        camera.manager.sendDirect(viewer.id, Envelope(MessageType.CAMERA_STARTED, screen = ScreenInfo(1280, 720), text = "back", rotation = 90, cameraState = state))
        repeat(3) { n -> camera.manager.sendDirect(viewer.id, Envelope(MessageType.CAMERA_FRAME, keyFrame = n == 0, raw = ByteArray(500))) }
        waitUntil("No video") { viewer.viewed.count { it.type == MessageType.CAMERA_FRAME } == 3 }
        assertEquals(8f, viewer.viewed.first { it.type == MessageType.CAMERA_STARTED }.cameraState?.maxZoom)

        // The shutter: camera.shutter, then camera.shot, then the photo as a file.
        val shots = ShotQueue()
        camera.manager.cameraSink = { peer, envelope ->
            camera.camera += envelope
            if (envelope.type == MessageType.CAMERA_SHUTTER) {
                shots.ask(envelope.key)?.let { camera.manager.post(peer, it) } ?: run {
                    val photo = ByteArray(250_000) { (it % 199).toByte() }
                    val name = "DyskLink_test.jpg"
                    camera.manager.post(peer, shots.taken(name)!!)
                    camera.manager.fileTransfers.sendFile(peer, name, photo.size.toLong(), "camera") { photo.inputStream() }
                }
            }
        }
        viewer.manager.sendPhoneCamera(camera.id, Envelope(MessageType.CAMERA_ZOOM, zoom = 2.5f))
        viewer.manager.sendPhoneCamera(camera.id, Envelope(MessageType.CAMERA_CONTROL, text = "torch"))
        viewer.manager.sendPhoneCamera(camera.id, Envelope(MessageType.CAMERA_SHUTTER, key = "shot-1"))
        waitUntil("No shot answer") { viewer.viewed.any { it.type == MessageType.CAMERA_SHOT } }
        val shot = viewer.viewed.first { it.type == MessageType.CAMERA_SHOT }
        assertEquals("shot-1", shot.key)
        assertNull(shot.error)
        assertEquals("DyskLink_test.jpg", shot.file?.name)
        waitUntil("Photo didn't arrive", seconds = 20) { File(viewer.received, "DyskLink_test.jpg").length() == 250_000L }
        assertTrue(viewer.manager.fileTransfers.transfers.value.any { !it.outgoing && it.name == "DyskLink_test.jpg" }, "In File history")
        assertEquals(
            listOf(MessageType.CAMERA_ZOOM, MessageType.CAMERA_CONTROL, MessageType.CAMERA_SHUTTER),
            camera.camera.map { it.type }.filter { it != MessageType.CAMERA_REQUEST },
        )
        assertEquals(2.5f, camera.camera.first { it.type == MessageType.CAMERA_ZOOM }.zoom)

        // Stopped by the camera phone: later frames are dropped until the viewer asks again.
        camera.manager.sendDirect(viewer.id, Envelope(MessageType.CAMERA_STOPPED))
        waitUntil("No stop") { viewer.viewed.any { it.type == MessageType.CAMERA_STOPPED } }
        val before = viewer.viewed.size
        camera.manager.sendDirect(viewer.id, Envelope(MessageType.CAMERA_FRAME, raw = byteArrayOf(1)))
        settle(camera, viewer)
        assertEquals(before, viewer.viewed.size)

        // Unpairing forgets the switch.
        camera.manager.unpair(viewer.id)
        waitUntil("Still allowed") { camera.manager.phoneAccess.of(viewer.id) == PeerAccess() }
    }

    @Test
    fun aDroppedConnectionEndsTheSessions() {
        val viewer = Phone("Gone")
        val shared = Phone("Stays")
        pair(viewer, shared)
        shared.manager.setPhoneAccess(viewer.id) { it.copy(screen = true, camera = true) }
        viewer.manager.viewPhoneScreen(shared.id)
        waitUntil("Not asked") { shared.screenAsks.isNotEmpty() }
        viewer.manager.shutdown()
        waitUntil("Screen didn't stop") { shared.stops.contains(viewer.id) }
        waitUntil("Camera didn't stop") { shared.camera.any { it.type == MessageType.CAMERA_STOP } }
    }

    @Test
    fun aMacIsUnaffected() {
        val phone = Phone("Phone")
        // A Mac as the phone sees one: it listens, the phone dials it, and its hello says macos.
        val mac = Phone("Mac", caps = PhoneLinkProtocol.CAPS, platform = "macos")
        pair(phone, mac)
        assertTrue(phone.peer(mac)!!.isMac)
        assertFalse(phone.peer(mac)!!.canViewScreen)
        assertEquals(PeerAccess(), phone.manager.phoneAccess.of(mac.id))
        assertNull(phone.manager.phoneControlAllowed(mac.id), "A computer isn't told about phone control")

        // With no per-phone switch at all, the Mac still gets what its feature switches give it.
        mac.manager.sendDirect(phone.id, Envelope(MessageType.SCREEN_REQUEST))
        mac.manager.sendDirect(phone.id, Envelope(MessageType.CONTROL, control = ControlEvent("down", 0.2f, 0.3f)))
        mac.manager.sendDirect(phone.id, Envelope(MessageType.CAMERA_REQUEST))
        waitUntil("Mac screen request lost") { phone.screenAsks.contains(mac.id) }
        waitUntil("Mac taps lost") { phone.touches.size == 1 }
        waitUntil("Mac camera request lost") { phone.camera.any { it.type == MessageType.CAMERA_REQUEST } }
        // The phone-only camera messages aren't for a Mac.
        mac.manager.sendDirect(phone.id, Envelope(MessageType.CAMERA_SHUTTER, key = "x"))
        settle(mac, phone)
        assertEquals(listOf(MessageType.CAMERA_REQUEST), phone.camera.map { it.type })
    }
}
