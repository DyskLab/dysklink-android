package id.phonelink.android.core

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The phone's screen, camera, gallery, and SMS on a Windows or Linux PC: only for a PC whose hello lists the `pc-*`
 * caps, JPEG frames instead of H.264, and a Mac served exactly as before. The real phone ConnectionManager against
 * the real ComputerHub, over TLS on this machine.
 */
class PcFeaturesTest {
    private val scopes = mutableListOf<CoroutineScope>()
    private val closers = mutableListOf<() -> Unit>()

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    @AfterTest
    fun close() {
        closers.forEach { it() }
        scopes.forEach { it.cancel() }
    }

    private fun sink(folder: File) = FileSinkFactory { name, _ ->
        val out = ByteArrayOutputStream()
        object : FileSink {
            override fun write(bytes: ByteArray) = out.write(bytes)
            override fun commit(): String = File(folder, name).also { it.writeBytes(out.toByteArray()) }.path
            override fun abort() {}
        }
    }

    private fun waitUntil(message: String, seconds: Int = 10, check: () -> Boolean) {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError(message)
            Thread.sleep(30)
        }
    }

    /** Nothing arrives within [ms]. */
    private fun never(message: String, ms: Long = 1_200, check: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (check()) throw AssertionError(message)
            Thread.sleep(30)
        }
    }

    /** The desktop app's side. [caps] as its hello lists them; [platform] `macos` stands in for a Mac. */
    private inner class Pc(caps: List<String>?, platform: String = "windows") {
        val dir: File = Files.createTempDirectory("dysk-pcf").toFile()
        val received = File(dir, "received").apply { mkdirs() }
        val sms = CopyOnWriteArrayList<SmsItem>()
        val hub = ComputerHub(
            dir, scope(),
            localHello = { paired -> Hello("Test PC", platform, "Test", PhoneLinkProtocol.VERSION, paired, "1.0.0", caps) },
            events = object : HubEvents {
                override fun clipboard(text: String, from: String) = true
                override fun ring(on: Boolean, from: String) {}
                override fun sms(items: List<SmsItem>, from: String) { sms += items }
            },
            fileSinks = sink(received),
            port = 0,
        ).also { it.start(); closers += it::shutdown }
    }

    private inner class Phone {
        val dir: File = Files.createTempDirectory("dysk-pcf-phone").toFile()
        val screenAsks = CopyOnWriteArrayList<String>()
        val screenStops = CopyOnWriteArrayList<String>()
        val controls = CopyOnWriteArrayList<ControlEvent>()
        val camera = CopyOnWriteArrayList<String>()
        val sent = CopyOnWriteArrayList<String>()
        @Volatile var inbox: List<SmsItem> = emptyList()
        val photos = (1..75L).map { GalleryItem(it, "IMG_$it.jpg", 1_000_000 - it, 4, thumb = "AAAA") }
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
                override fun readSms(limit: Int, afterId: Long) = inbox.filter { it.id > afterId }.sortedByDescending { it.id }.take(limit)
                override fun sendSms(address: String, body: String, done: (String?) -> Unit) { sent += "$address:$body"; done(null) }
                override fun readContacts() = emptyList<ContactItem>()
                override fun requestScreen(peerId: String) { screenAsks += peerId }
                override fun syncScreen(peerId: String) {}
                override fun stopScreen(peerId: String) { screenStops += peerId }
                override fun control(event: ControlEvent) { controls += event }
                override fun gallery(offset: Int, limit: Int) = photos.drop(offset).take(limit)
                override fun galleryFile(id: Long) = photos.firstOrNull { it.id == id }?.let { GalleryFile(it.name, 4) { "foto".byteInputStream() } }
            },
        ).also {
            closers += it::shutdown
            it.cameraSink = { _, envelope -> camera += envelope.type }
        }
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
        // Everything the phone can share, as the user would turn it on.
        listOf(PhoneFeature.Screen, PhoneFeature.Control, PhoneFeature.Gallery, PhoneFeature.Sms).forEach { phone.manager.phone.setEnabled(it, true) }
        waitUntil("Features didn't arrive") { pc.hub.phones.value.first().features.containsAll(listOf("screen", "gallery", "sms")) }
        return phone.manager.localDeviceId
    }

    private fun jpeg(width: Int = 64, height: Int = 128): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) for (y in 0 until height) image.setRGB(x, y, (x * 4 shl 16) or (y * 2 shl 8) or 0x40)
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    // The rules on their own

    @Test
    fun onlyMacsAndPcsWithTheCapGetThese() {
        val all = PhoneLinkProtocol.PC_CAPS
        for (type in listOf(MessageType.SCREEN_REQUEST, MessageType.CONTROL, MessageType.CAMERA_REQUEST, MessageType.GALLERY_GET, MessageType.SMS_SEND)) {
            assertTrue(PcRules.allows("macos", PhoneLinkProtocol.CAPS, type), "A Mac keeps $type")
            assertTrue(PcRules.allows("macos", null, type), "A Mac before caps keeps $type")
            assertFalse(PcRules.allows("windows", PhoneLinkProtocol.CAPS, type), "An older PC build doesn't get $type")
            assertFalse(PcRules.allows("linux", null, type), "A PC before caps doesn't get $type")
            assertTrue(PcRules.allows("linux", all, type), "A new PC gets $type")
        }
        // Each cap opens only its own messages.
        assertFalse(PcRules.allows("windows", listOf(PhoneLinkProtocol.CAP_PC_SMS), MessageType.SCREEN_REQUEST))
        assertTrue(PcRules.allows("windows", listOf(PhoneLinkProtocol.CAP_PC_SMS), MessageType.SMS_LIST))
        // What every computer always got stays open.
        assertTrue(PcRules.allows("windows", null, MessageType.NOTIFICATION_DISMISS))
        assertTrue(PcRules.allows("windows", null, MessageType.CLIPBOARD))
        // JPEG only for a PC that asked; a Mac stays on H.264, whatever it lists.
        assertTrue(PcRules.jpeg("windows", all, PhoneLinkProtocol.CAP_PC_SCREEN))
        assertFalse(PcRules.jpeg("macos", all, PhoneLinkProtocol.CAP_PC_SCREEN))
        assertFalse(PcRules.jpeg("windows", PhoneLinkProtocol.CAPS, PhoneLinkProtocol.CAP_PC_SCREEN))
        assertFalse(PcRules.jpeg("android", all, PhoneLinkProtocol.CAP_PC_CAMERA))
        assertEquals(listOf(PhoneLinkProtocol.CAP_BINARY), PhoneLinkProtocol.CAPS, "Macs and older builds keep advertising bin1 only")
    }

    // JPEG frames

    @Test
    fun jpegFramesGoBothWaysUnchanged() {
        val picture = jpeg()
        val frame = Envelope(MessageType.SCREEN_FRAME, format = JpegFrames.FORMAT, raw = picture)
        // Binary to a bin1 peer: the JPEG is the payload, the format in the header.
        val wire = assertNotNull(BinaryFrames.encode(frame))
        val read = assertNotNull(FrameReader(DataInputStream(ByteArrayInputStream(wire))).read())
        assertEquals(JpegFrames.FORMAT, read.format)
        assertContentEquals(picture, read.payload())
        // As JSON (base64 in `video`) to anyone else.
        val json = protocolJson.encodeToString(Envelope.serializer(), BinaryFrames.asJson(frame))
        val back = protocolJson.decodeFromString(Envelope.serializer(), json)
        assertEquals(JpegFrames.FORMAT, back.format)
        assertContentEquals(picture, back.payload())
        // And it's still a picture at the other end.
        val decoded = assertNotNull(ImageIO.read(ByteArrayInputStream(assertNotNull(read.payload()))))
        assertEquals(64, decoded.width)
        assertEquals(128, decoded.height)
        assertTrue(JpegFrames.looksLikeJpeg(picture))
        assertFalse(JpegFrames.looksLikeJpeg(byteArrayOf(0, 0, 0, 1, 0x67)), "H.264 isn't taken for a picture")
    }

    @Test
    fun picturesAreScaledAndCapped() {
        assertEquals(1280 to 576, JpegFrames.fit(2400, 1080))
        assertEquals(576 to 1280, JpegFrames.fit(1080, 2400))
        assertEquals(640 to 480, JpegFrames.fit(640, 480), "Small pictures stay as they are")
        assertEquals(0 to 0, JpegFrames.fit(0, 100))
        // The biggest picture that may go still fits a 1 MiB frame as base64.
        assertTrue(JpegFrames.MAX_BYTES * 4 / 3 + 1024 < PhoneLinkProtocol.MAX_FRAME_BYTES)
        val sent = CopyOnWriteArrayList<Envelope>()
        var now = 0L
        val stream = JpegStreamer(scope(), { sent += it; true }, MessageType.CAMERA_FRAME, clock = { now }) {}
        val before = stream.quality
        assertFalse(stream.offer(ByteArray(JpegFrames.MAX_BYTES + 1)), "Too big: dropped")
        assertTrue(stream.quality < before, "and the quality went down")
        now += 1_000
        assertTrue(stream.offer(jpeg()))
        waitUntil("The frame didn't go") { sent.isNotEmpty() }
        assertEquals(MessageType.CAMERA_FRAME, sent[0].type)
        assertEquals(JpegFrames.FORMAT, sent[0].format)
        stream.close()
        assertFalse(stream.offer(jpeg()), "Nothing after close")
    }

    @Test
    fun theStreamBacksOffWhenTheNetworkFallsBehind() {
        val pacer = JpegPacer(maxFps = 15, minFps = 5, maxQuality = 80, minQuality = 35, startQuality = 70)
        assertTrue(pacer.due(0))
        pacer.taken(0)
        assertFalse(pacer.due(30), "15 fps: not before 66 ms")
        assertTrue(pacer.due(70))
        repeat(4) { pacer.dropped() }
        assertEquals(35, pacer.quality, "Drops lower the quality first")
        assertEquals(15, pacer.fps)
        repeat(10) { pacer.dropped() }
        assertEquals(5, pacer.fps, "then the frame rate, down to the floor")
        repeat(30 * 5) { pacer.sent() }
        assertEquals(15, pacer.fps, "Frames that go out bring the rate back first")
        repeat(30 * 20) { pacer.sent() }
        assertEquals(80, pacer.quality, "then the quality, up to the ceiling")

        // A link that never finishes sending: one frame goes out, one waits, the rest are dropped.
        val gate = java.util.concurrent.CountDownLatch(1)
        var now = 0L
        val stream = JpegStreamer(scope(), { gate.await(); true }, clock = { now }) {}
        val results = (1..5).map { now += 100; stream.offer(jpeg()).also { Thread.sleep(50) } }
        assertEquals(listOf(true, true, false, false, false), results)
        assertTrue(stream.quality < 70)
        gate.countDown()
        stream.close()
    }

    @Test
    fun cameraPicturesBecomeNv21WhateverTheStrides() {
        // 4×2 picture, Y rows padded to 6, chroma interleaved (pixel stride 2) with rows of 4.
        val width = 4
        val height = 2
        val y = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 0, 0, 5, 6, 7, 8, 0, 0))
        val uv = byteArrayOf(10, 20, 11, 21)
        val u = ByteBuffer.wrap(uv)
        val v = ByteBuffer.wrap(uv, 1, 3).slice()
        val nv21 = Yuv.nv21(width, height, y, 6, u, v, 4, 2)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 20, 10, 21, 11), nv21)
        // Planar chroma (pixel stride 1).
        val planar = Yuv.nv21(2, 2, ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), 2, ByteBuffer.wrap(byteArrayOf(9)), ByteBuffer.wrap(byteArrayOf(7)), 1, 1)
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 7, 9), planar)
    }

    // Over the network

    @Test
    fun anOlderPcBuildGetsNoneOfIt() {
        val pc = Pc(PhoneLinkProtocol.CAPS)
        val phone = Phone()
        val phoneId = paired(pc, phone)
        assertFalse(phone.manager.peers.value.first { it.id == pc.hub.deviceId }.pcScreen)
        pc.hub.requestScreen(phoneId)
        pc.hub.requestCamera(phoneId)
        pc.hub.loadGallery(phoneId, fresh = true)
        phone.inbox = listOf(SmsItem(1, 1, "+62811", "halo", 1, false))
        pc.hub.loadSms(phoneId)
        pc.hub.sendSms(phoneId, "+62811", "dari pc lama")
        phone.manager.phone.smsChanged()
        never("The phone answered an older PC") {
            phone.screenAsks.isNotEmpty() || phone.camera.isNotEmpty() || phone.sent.isNotEmpty() ||
                pc.hub.galleries.value[phoneId]?.items?.isNotEmpty() == true ||
                pc.hub.sms.value[phoneId]?.messages?.isNotEmpty() == true || pc.sms.isNotEmpty()
        }
        // What it always had still works.
        phone.manager.phone.setEnabled(PhoneFeature.Notifications, true)
        phone.manager.phone.notificationPosted(NotificationItem("k", "Chat", "Budi", "Halo", 1))
        waitUntil("Notifications stopped working") { pc.hub.notifications.value[phoneId]?.isNotEmpty() == true }
    }

    @Test
    fun aMacIsServedAsBefore() {
        val mac = Pc(PhoneLinkProtocol.CAPS, platform = "macos")
        val phone = Phone()
        val macId = mac.hub.deviceId
        val phoneId = paired(mac, phone)
        val peer = phone.manager.peers.value.first { it.id == macId }
        assertTrue(peer.isMac)
        assertFalse(peer.pcScreen, "A Mac keeps H.264")
        assertFalse(peer.pcCamera)
        mac.hub.requestScreen(phoneId)
        waitUntil("The Mac's screen request didn't reach the phone") { macId in phone.screenAsks }
        mac.hub.requestCamera(phoneId)
        waitUntil("The Mac's camera request didn't reach the phone") { MessageType.CAMERA_REQUEST in phone.camera }
        phone.inbox = listOf(SmsItem(1, 1, "+62811", "halo", 1, false))
        mac.hub.loadSms(phoneId)
        waitUntil("The Mac didn't get SMS") { mac.hub.sms.value[phoneId]?.messages?.size == 1 }
        phone.inbox = phone.inbox + SmsItem(2, 1, "+62811", "baru", 2, false)
        phone.manager.phone.smsChanged()
        waitUntil("The Mac didn't get the new SMS") { mac.sms.any { it.id == 2L } }
    }

    @Test
    fun screenAndControlOnANewPc() {
        val pc = Pc(PhoneLinkProtocol.PC_CAPS)
        val phone = Phone()
        val pcId = pc.hub.deviceId
        val phoneId = paired(pc, phone)
        assertTrue(phone.manager.peers.value.first { it.id == pcId }.pcScreen, "The phone sends this PC JPEG")
        assertTrue(pc.hub.phones.value.first().servesScreen, "The PC knows this phone serves its screen")

        // Frames before asking are dropped.
        phone.manager.sendDirect(pcId, Envelope(MessageType.SCREEN_FRAME, format = JpegFrames.FORMAT, raw = jpeg()))
        Thread.sleep(300)
        assertNull(pc.hub.screens.value[phoneId], "A picture nobody asked for isn't shown")
        pc.hub.requestScreen(phoneId)
        waitUntil("The request didn't reach the phone") { pcId in phone.screenAsks }
        assertTrue(pc.hub.screens.value[phoneId]?.waiting == true)
        // The phone (ScreenCaptureService) after Android's dialog: started, then pictures.
        phone.manager.sendDirect(pcId, Envelope(MessageType.SCREEN_STARTED, screen = ScreenInfo(576, 1280), format = JpegFrames.FORMAT))
        val picture = jpeg()
        phone.manager.sendDirect(pcId, Envelope(MessageType.SCREEN_FRAME, format = JpegFrames.FORMAT, raw = picture))
        waitUntil("The picture didn't show") { pc.hub.screens.value[phoneId]?.live == true }
        val shown = pc.hub.screens.value.getValue(phoneId)
        assertContentEquals(picture, shown.frame)
        assertEquals(1, shown.frames)
        assertEquals(576, shown.width)

        // Taps and typing reach the phone's Accessibility side, sanitized.
        pc.hub.control(phoneId, ControlEvent("down", 0.5f, 0.25f))
        pc.hub.control(phoneId, ControlEvent("up", 2f, 0.25f))
        pc.hub.control(phoneId, ControlEvent("key", key = "back"))
        pc.hub.control(phoneId, ControlEvent("key", key = "power"))
        waitUntil("Control didn't arrive") { phone.controls.size >= 3 }
        assertEquals(listOf("down", "up", "key"), phone.controls.map { it.kind })
        assertEquals(1f, phone.controls[1].x, "Points stay on the screen")

        // Stopping here ends it on the phone; the phone ending it clears it here.
        pc.hub.stopScreen(phoneId)
        waitUntil("Stop didn't reach the phone") { pcId in phone.screenStops }
        assertNull(pc.hub.screens.value[phoneId])

        // H.264 (a phone that doesn't know PCs) is refused with a hint, and stopped.
        pc.hub.requestScreen(phoneId)
        phone.manager.sendDirect(pcId, Envelope(MessageType.SCREEN_STARTED, screen = ScreenInfo(576, 1280)))
        waitUntil("No hint for H.264") { pc.hub.screens.value[phoneId]?.error != null }
        waitUntil("H.264 wasn't stopped") { phone.screenStops.size >= 2 }

        // A refusal on the phone shows here.
        pc.hub.requestScreen(phoneId)
        phone.manager.sendDirect(pcId, Envelope(MessageType.SCREEN_STOPPED, error = "Not now"))
        waitUntil("The refusal didn't show") { pc.hub.screens.value[phoneId]?.error == "Not now" }

        // The camera: only a request, which the phone shows as a notification; then frames with the lens and turn.
        pc.hub.requestCamera(phoneId)
        waitUntil("The camera request didn't reach the phone") { MessageType.CAMERA_REQUEST in phone.camera }
        phone.manager.sendDirect(pcId, Envelope(MessageType.CAMERA_STARTED, screen = ScreenInfo(1280, 720), text = "back", rotation = 90, format = JpegFrames.FORMAT, cameraState = CameraState(flash = true, torch = false)))
        phone.manager.sendDirect(pcId, Envelope(MessageType.CAMERA_FRAME, format = JpegFrames.FORMAT, raw = picture))
        waitUntil("The camera didn't show") { pc.hub.cameras.value[phoneId]?.live == true }
        assertEquals(90, pc.hub.cameras.value.getValue(phoneId).rotation)
        assertTrue(pc.hub.cameras.value.getValue(phoneId).hasFlash)
        pc.hub.cameraControl(phoneId, "torch")
        waitUntil("The torch didn't reach the phone") { MessageType.CAMERA_CONTROL in phone.camera }

        // Leaving ends it all here.
        phone.manager.shutdown()
        waitUntil("The screen and camera didn't end with the connection") { pc.hub.screens.value.isEmpty() && pc.hub.cameras.value.isEmpty() }
    }

    @Test
    fun galleryPagesAndDownloads() {
        val pc = Pc(PhoneLinkProtocol.PC_CAPS)
        val phone = Phone()
        val phoneId = paired(pc, phone)
        pc.hub.loadGallery(phoneId, fresh = true)
        waitUntil("The first page didn't come") { pc.hub.galleries.value[phoneId]?.let { !it.loading && it.items.isNotEmpty() } == true }
        var gallery = pc.hub.galleries.value.getValue(phoneId)
        assertEquals(PhoneBridge.GALLERY_PAGE, gallery.items.size)
        assertFalse(gallery.end)
        pc.hub.loadGallery(phoneId)
        waitUntil("The next page didn't come") { pc.hub.galleries.value[phoneId]?.end == true }
        gallery = pc.hub.galleries.value.getValue(phoneId)
        assertEquals(phone.photos.map { it.id }, gallery.items.map { it.id }, "All 75, in order, once each")
        // Fresh starts over.
        pc.hub.loadGallery(phoneId, fresh = true)
        waitUntil("Refresh didn't come") { pc.hub.galleries.value[phoneId]?.let { !it.loading && it.items.size == PhoneBridge.GALLERY_PAGE } == true }

        // Several at once, each as a file into the downloads folder; unknown IDs aren't asked for.
        pc.hub.downloadGallery(phoneId, listOf(1, 2, 999))
        waitUntil("The photos didn't arrive") { File(pc.received, "IMG_1.jpg").exists() && File(pc.received, "IMG_2.jpg").exists() }
        assertEquals("foto", File(pc.received, "IMG_1.jpg").readText())

        // Turned off on the phone: the PC forgets it.
        phone.manager.phone.setEnabled(PhoneFeature.Gallery, false)
        waitUntil("The gallery wasn't cleared") { pc.hub.galleries.value[phoneId] == null }
        pc.hub.loadGallery(phoneId, fresh = true)
        waitUntil("No reason given") { pc.hub.galleries.value[phoneId]?.error != null }
        assertTrue(pc.hub.galleries.value.getValue(phoneId).items.isEmpty())
    }

    @Test
    fun smsListNewAndSend() {
        val pc = Pc(PhoneLinkProtocol.PC_CAPS)
        val phone = Phone()
        val phoneId = paired(pc, phone)
        phone.inbox = listOf(
            SmsItem(1, 7, "+6281100", "halo", 1_000, false),
            SmsItem(2, 7, "+6281100", "iya", 2_000, true),
            SmsItem(3, 8, "TELKOMSEL", "kuota", 3_000, false),
        )
        pc.hub.loadSms(phoneId)
        waitUntil("The SMS didn't come") { pc.hub.sms.value[phoneId]?.loaded == true }
        assertEquals(listOf(3L, 2L, 1L), pc.hub.sms.value.getValue(phoneId).messages.map { it.id }, "Newest first")

        // A new one arrives by itself and pops up here; the ones already shown don't again.
        phone.inbox = phone.inbox + SmsItem(4, 7, "+6281100", "jam tiga?", 4_000, false)
        phone.manager.phone.smsChanged()
        waitUntil("The new SMS didn't come") { pc.hub.sms.value[phoneId]?.messages?.firstOrNull()?.id == 4L }
        waitUntil("No notification for it") { pc.sms.map { it.id } == listOf(4L) }

        // Sending: the phone gets it and the carrier's answer comes back.
        val id = assertNotNull(pc.hub.sendSms(phoneId, "+6281100", "oke"))
        waitUntil("The SMS wasn't sent") { "+6281100:oke" in phone.sent }
        waitUntil("No answer") { pc.hub.sms.value[phoneId]?.sent?.firstOrNull { it.id == id }?.ok == true }
        assertNull(pc.hub.sendSms(phoneId, "+6281100", "   "), "Empty text isn't sent")

        // Off on the phone: refused there, and the PC drops what it had.
        phone.manager.phone.setEnabled(PhoneFeature.Sms, false)
        waitUntil("SMS weren't cleared") { pc.hub.sms.value[phoneId] == null }
        val refused = assertNotNull(pc.hub.sendSms(phoneId, "+6281100", "lagi"))
        waitUntil("No refusal") { pc.hub.sms.value[phoneId]?.sent?.firstOrNull { it.id == refused }?.ok == false }
        assertFalse("+6281100:lagi" in phone.sent)
    }

    // Game keys: several fingers at once

    @Test
    fun severalFingersNeedTheGameKeysCap() {
        val screenOnly = PhoneLinkProtocol.PC_CAPS - PhoneLinkProtocol.CAP_PC_GAMEKEYS
        assertTrue(PhoneLinkProtocol.CAP_PC_GAMEKEYS in PhoneLinkProtocol.PHONE_CAPS, "The phone says it takes them")
        for (kind in listOf("tdown", "tmove", "tup")) {
            assertTrue(PcRules.allowsControl("macos", PhoneLinkProtocol.CAPS, kind), "A Mac keeps $kind")
            assertTrue(PcRules.allowsControl("macos", null, kind))
            assertFalse(PcRules.allowsControl("windows", screenOnly, kind), "A PC with only pc-screen doesn't get $kind")
            assertTrue(PcRules.allowsControl("linux", PhoneLinkProtocol.PC_CAPS, kind))
        }
        // Plain taps, keys, and text stay as they were.
        for (kind in listOf("down", "drag", "up", "key", "text")) assertTrue(PcRules.allowsControl("windows", screenOnly, kind))
    }

    /** Makes [phoneId]'s screen live on [pc], as the phone does after Android's dialog. */
    private fun liveScreen(pc: Pc, phone: Phone, phoneId: String) {
        val pcId = pc.hub.deviceId
        pc.hub.requestScreen(phoneId)
        waitUntil("The request didn't reach the phone") { pcId in phone.screenAsks }
        phone.manager.sendDirect(pcId, Envelope(MessageType.SCREEN_STARTED, screen = ScreenInfo(576, 1280), format = JpegFrames.FORMAT))
        phone.manager.sendDirect(pcId, Envelope(MessageType.SCREEN_FRAME, format = JpegFrames.FORMAT, raw = jpeg()))
        waitUntil("The picture didn't show") { pc.hub.screens.value[phoneId]?.live == true }
    }

    private fun fingers(pc: Pc, phone: Phone, phoneId: String) {
        phone.controls.clear()
        pc.hub.control(phoneId, ControlEvent("tdown", 0.2f, 0.7f, key = "0"))
        pc.hub.control(phoneId, ControlEvent("tmove", 0.25f, 0.7f, key = "0"))
        pc.hub.control(phoneId, ControlEvent("tdown", 0.8f, 0.6f, key = "9"))
        pc.hub.control(phoneId, ControlEvent("tup", 0.25f, 0.7f, key = "0"))
        pc.hub.control(phoneId, ControlEvent("tup", 0.8f, 0.6f, key = "9"))
        // A plain tap after them, which every PC with pc-screen may send: it marks the end.
        pc.hub.control(phoneId, ControlEvent("down", 0.5f, 0.5f))
        waitUntil("The plain tap didn't arrive") { phone.controls.any { it.kind == "down" } }
    }

    @Test
    fun aPcWithoutTheGameKeysCapCantSendSeveralFingers() {
        val pc = Pc(PhoneLinkProtocol.PC_CAPS - PhoneLinkProtocol.CAP_PC_GAMEKEYS)
        val phone = Phone()
        val phoneId = paired(pc, phone)
        assertTrue(pc.hub.phones.value.first().servesGameKeys, "The phone serves game keys")
        liveScreen(pc, phone, phoneId)
        fingers(pc, phone, phoneId)
        assertEquals(listOf("down"), phone.controls.map { it.kind }, "Only the plain tap got through")
    }

    @Test
    fun aPcWithTheGameKeysCapSendsSeveralFingers() {
        val pc = Pc(PhoneLinkProtocol.PC_CAPS)
        val phone = Phone()
        val phoneId = paired(pc, phone)
        liveScreen(pc, phone, phoneId)
        fingers(pc, phone, phoneId)
        assertEquals(listOf("tdown", "tmove", "tdown", "tup", "tup", "down"), phone.controls.map { it.kind })
        assertEquals(listOf("0", "0", "9", "0", "9"), phone.controls.take(5).map { it.key })
        // A finger number out of range is dropped on both sides.
        phone.controls.clear()
        pc.hub.control(phoneId, ControlEvent("tdown", 0.5f, 0.5f, key = "12"))
        pc.hub.control(phoneId, ControlEvent("up", 0.5f, 0.5f))
        waitUntil("The plain lift didn't arrive") { phone.controls.any { it.kind == "up" } }
        assertEquals(listOf("up"), phone.controls.map { it.kind })
    }

    @Test
    fun aMacSendsSeveralFingersAsBefore() {
        val mac = Pc(PhoneLinkProtocol.CAPS, platform = "macos")
        val phone = Phone()
        val phoneId = paired(mac, phone)
        liveScreen(mac, phone, phoneId)
        fingers(mac, phone, phoneId)
        assertEquals(listOf("tdown", "tmove", "tdown", "tup", "tup", "down"), phone.controls.map { it.kind })
    }
}
