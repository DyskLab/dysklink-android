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

/** The real phone ConnectionManager against the Windows/Linux computer side, over TLS on this machine. */
class ComputerHubTest {
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

    /** [caps] null acts like 1.1.x, before binary frames; the real desktop app sends [PhoneLinkProtocol.CAPS]. */
    private inner class Pc(dir: File = Files.createTempDirectory("dysk-pc").toFile(), var control: Boolean = true, pingMs: Long = 25_000L,
                           caps: List<String>? = null) {
        val inputs = CopyOnWriteArrayList<String>()
        val media = CopyOnWriteArrayList<String>()
        val sound = CopyOnWriteArrayList<String>()
        val images = CopyOnWriteArrayList<ByteArray>()
        val clipboard = CopyOnWriteArrayList<String>()
        val rings = CopyOnWriteArrayList<Boolean>()
        val popups = CopyOnWriteArrayList<String>()
        val received = File(dir, "received").apply { mkdirs() }
        val hub = ComputerHub(
            dir, scope(),
            localHello = { paired -> Hello("Test PC", "windows", "Windows 11", PhoneLinkProtocol.VERSION, paired, "1.0.0", caps) },
            events = object : HubEvents {
                override fun clipboard(text: String, from: String): Boolean { clipboard += text; return true }
                override fun ring(on: Boolean, from: String) { rings += on }
                override fun notification(item: NotificationItem, from: String) { popups += item.title.orEmpty() }
                override val canControl get() = control
                override fun input(event: InputEvent, from: String) { inputs += "${event.kind}:${event.key ?: event.text ?: event.dx}" }
                override fun media(action: String, from: String) { media += action }
                override fun phoneSound(from: String, rate: Int, channels: Int) { sound += "start $rate/$channels" }
                override fun phoneSoundChunk(from: String, pcm: ByteArray) { sound += "chunk ${pcm.size}" }
                override fun phoneSoundEnded(from: String) { sound += "end" }
                override fun clipboardImage(png: ByteArray, from: String) { images += png }
            },
            fileSinks = sink(received),
            port = 0,
            pingIntervalMs = pingMs,
        ).also { it.start(); closers += it::shutdown }
    }

    private inner class Phone(caps: List<String>? = null) {
        val dir: File = Files.createTempDirectory("dysk-phone").toFile()
        val clipboard = CopyOnWriteArrayList<String>()
        val rings = CopyOnWriteArrayList<String>()
        val received = File(dir, "received").apply { mkdirs() }
        val manager = ConnectionManager(
            dir, scope(),
            localHello = { paired -> Hello("Pixel", "android", "Pixel 9", PhoneLinkProtocol.VERSION, paired, caps = caps) },
            localInfo = { DeviceInfo(batteryPercent = 64) },
            isUiVisible = { true },
            actions = object : DeviceActions {
                override fun setClipboard(text: String) { clipboard += text }
                override fun media(action: String) {}
                override fun ring(from: String) { rings += from }
            },
            fileSinks = sink(received),
            phoneData = object : PhoneData {
                override val storageRoot = dir
                override fun isGranted(feature: PhoneFeature) = true
                override fun activeNotifications() = emptyList<NotificationItem>()
                override fun dismissNotification(key: String) {}
                override fun readSms(limit: Int, afterId: Long) = emptyList<SmsItem>()
                override fun sendSms(address: String, body: String, done: (String?) -> Unit) = done("no")
                override fun readContacts() = emptyList<ContactItem>()
                override fun requestScreen(peerId: String) {}
                override fun syncScreen(peerId: String) {}
                override fun stopScreen(peerId: String) {}
                override fun control(event: ControlEvent) {}
            },
        ).also { closers += it::shutdown }
    }

    private fun waitUntil(message: String, seconds: Int = 10, check: () -> Boolean) {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError(message)
            Thread.sleep(50)
        }
    }

    /** Connects and pairs by typing the code the PC shows. */
    private fun paired(pc: Pc, phone: Phone) {
        phone.manager.addManual("127.0.0.1", pc.hub.boundPort)
        waitUntil("The phone didn't connect") { pc.hub.phones.value.any { it.isConnected } }
        val peer = phone.manager.peers.value.first { it.id == pc.hub.deviceId }
        assertTrue(peer.isComputer, "A Windows PC must count as a computer")
        assertFalse(peer.isMac)
        // The phone asks by itself while its app is open; the PC shows the code.
        phone.manager.tryAutoPair()
        val code = run { waitUntil("The PC didn't show a code") { pc.hub.pairing.value != null }; pc.hub.pairing.value!!.code }
        phone.manager.submitCode(code)
        waitUntil("Pairing didn't finish on both sides") {
            pc.hub.phones.value.any { it.isPaired && it.isConnected } && phone.manager.peers.value.any { it.id == pc.hub.deviceId && it.isPaired }
        }
        assertNull(pc.hub.pairing.value)
    }

    @Test
    fun pairsAndSharesWithAWindowsPc() {
        val pc = Pc()
        val phone = Phone()
        paired(pc, phone)
        val phoneId = phone.manager.localDeviceId
        waitUntil("The phone's battery didn't arrive") { pc.hub.phones.value.first().info?.batteryPercent == 64 }

        // Clipboard both ways.
        phone.manager.shareClipboard("dari-hp")
        waitUntil("Phone clipboard didn't reach the PC") { "dari-hp" in pc.clipboard }
        pc.hub.sendClipboard("dari-pc")
        waitUntil("PC clipboard didn't reach the phone") { "dari-pc" in phone.clipboard }

        // Files both ways.
        val outgoing = File.createTempFile("dari-pc", ".txt").apply { writeText("isi dari pc") }
        var sent: Boolean? = null
        pc.hub.sendFile(phoneId, outgoing) { sent = it }
        waitUntil("File from the PC didn't arrive") { File(phone.received, outgoing.name).exists() }
        assertEquals("isi dari pc", File(phone.received, outgoing.name).readText())
        waitUntil("The PC wasn't told the file arrived") { sent == true }
        phone.manager.fileTransfers.sendFile(pc.hub.deviceId, "dari-hp.txt", 11) { "isi dari hp".byteInputStream() }
        waitUntil("File from the phone didn't arrive") { File(pc.received, "dari-hp.txt").exists() }
        assertEquals("isi dari hp", File(pc.received, "dari-hp.txt").readText())

        // Notifications, only once the feature is on.
        phone.manager.phone.setEnabled(PhoneFeature.Notifications, true)
        waitUntil("Features didn't reach the PC") { "notifications" in pc.hub.phones.value.first().features }
        phone.manager.phone.notificationPosted(NotificationItem("k1", "WhatsApp", "Budi", "Halo", System.currentTimeMillis()))
        waitUntil("The notification didn't show") { pc.hub.notifications.value[phoneId]?.any { it.key == "k1" } == true }
        assertEquals(listOf("Budi"), pc.popups)
        phone.manager.phone.notificationRemoved("k1")
        waitUntil("The notification wasn't removed") { pc.hub.notifications.value[phoneId].orEmpty().isEmpty() }

        // Ringing both ways.
        pc.hub.ring(phoneId, true)
        waitUntil("The phone didn't ring") { phone.rings.isNotEmpty() }
        phone.manager.ringMac(pc.hub.deviceId, true)
        waitUntil("The PC didn't ring") { true in pc.rings }
    }

    @Test
    fun aWrongCodeIsRefusedAndNothingIsSharedBeforePairing() {
        val pc = Pc()
        val phone = Phone()
        phone.manager.addManual("127.0.0.1", pc.hub.boundPort)
        waitUntil("The phone didn't connect") { pc.hub.phones.value.any { it.isConnected } }
        // Before pairing the PC ignores the clipboard.
        phone.manager.shareClipboard("rahasia")
        phone.manager.tryAutoPair()
        waitUntil("The PC didn't show a code") { pc.hub.pairing.value != null }
        val code = pc.hub.pairing.value!!.code
        phone.manager.submitCode(if (code == "000000") "111111" else "000000")
        waitUntil("The wrong code wasn't counted") { pc.hub.pairing.value?.attempts == 1 }
        assertNotNull(pc.hub.pairing.value?.error)
        assertTrue(pc.hub.phones.value.none { it.isPaired })
        assertTrue(pc.clipboard.isEmpty())
    }

    @Test
    fun aDoubleSubmitOnThePhoneStillPairsAndCountsOneTry() {
        val pc = Pc()
        val phone = Phone()
        phone.manager.addManual("127.0.0.1", pc.hub.boundPort)
        waitUntil("The phone didn't connect") { pc.hub.phones.value.any { it.isConnected } }
        phone.manager.tryAutoPair()
        waitUntil("The PC didn't show a code") { pc.hub.pairing.value != null }
        val code = pc.hub.pairing.value!!.code
        val wrong = if (code == "000000") "111111" else "000000"
        // The sixth digit and a Pair tap: the second is ignored, so one wrong try is counted, not two.
        phone.manager.submitCode(wrong)
        phone.manager.submitCode(wrong)
        waitUntil("The wrong code wasn't counted") { pc.hub.pairing.value?.attempts == 1 }
        waitUntil("The phone didn't hear it was wrong") { phone.manager.prompt.value?.localAccepted == false }
        Thread.sleep(300)
        assertEquals(1, pc.hub.pairing.value?.attempts)
        phone.manager.submitCode(code)
        phone.manager.submitCode(code)
        waitUntil("Pairing didn't finish on both sides") {
            pc.hub.phones.value.any { it.isPaired && it.isConnected } && phone.manager.peers.value.any { it.id == pc.hub.deviceId && it.isPaired }
        }
    }

    @Test
    fun newNoncesDontGetANewShareEachTime() {
        val pc = Pc()
        val flooder = Raw(Files.createTempDirectory("dysk-flood").toFile(), pc.hub.boundPort)
        flooder.hello("android", paired = false)
        waitUntil("The flooder didn't connect") { pc.hub.phones.value.any { it.isConnected } }
        val typer = Pake.Typer()
        flooder.connection.send(Envelope(MessageType.PAIR_REQUEST, pake = typer.request))
        val share = flooder.expect(MessageType.PAIR_PAKE).pake
        assertTrue(typer.received(share))
        val code = pc.hub.pairing.value!!.code
        // Fresh nonces right away are ignored; the same nonce again gets the same share.
        repeat(30) { flooder.connection.send(Envelope(MessageType.PAIR_REQUEST, pake = Pake.Typer().request)) }
        flooder.connection.send(Envelope(MessageType.PAIR_REQUEST, pake = typer.request))
        flooder.connection.send(Envelope(MessageType.PING))
        val after = ArrayList<Envelope>()
        while (true) {
            val next = flooder.received.poll(5, java.util.concurrent.TimeUnit.SECONDS) ?: throw AssertionError("No pong")
            if (next.type == MessageType.PONG) break
            after += next
        }
        assertEquals(listOf(MessageType.PAIR_PAKE), after.map { it.type })
        assertEquals(share, after.single().pake)
        // A counted wrong code lets the next new nonce have a share at once.
        val channel = Pake.channel(flooder.identity.deviceId, pc.hub.deviceId)
        flooder.connection.send(Envelope(MessageType.PAIR_ACCEPT, pake = typer.answer(if (code == "000000") "111111" else "000000", channel)))
        assertEquals(Pairing.WRONG_CODE, flooder.expect(MessageType.PAIR_REJECT).error)
        val second = Pake.Typer()
        flooder.connection.send(Envelope(MessageType.PAIR_REQUEST, pake = second.request))
        assertTrue(second.received(flooder.expect(MessageType.PAIR_PAKE).pake))
        // And the new share works with the same code.
        flooder.connection.send(Envelope(MessageType.PAIR_ACCEPT, pake = second.answer(code, channel)))
        assertTrue(second.confirms(flooder.expect(MessageType.PAIR_ACCEPT).pake))
        waitUntil("It didn't pair") { pc.hub.phones.value.any { it.isPaired } }
    }

    @Test
    fun anUpdateNoticeDoesntHoldThePairingSlot() {
        val pc = Pc()
        val old = Raw(Files.createTempDirectory("dysk-old2").toFile(), pc.hub.boundPort)
        old.hello("android", paired = false, version = 2)
        waitUntil("The old phone didn't connect") { pc.hub.phones.value.any { it.isConnected } }
        old.connection.send(Envelope(MessageType.PAIR_REQUEST))
        assertEquals(Pairing.UPDATE_REQUIRED, old.expect(MessageType.PAIR_REJECT).error)
        waitUntil("The PC didn't say to update") { pc.hub.pairing.value?.outdated == true }
        // A current phone can still pair while the notice shows.
        val fresh = Raw(Files.createTempDirectory("dysk-fresh").toFile(), pc.hub.boundPort)
        fresh.hello("android", paired = false)
        waitUntil("The second phone didn't connect") { pc.hub.phones.value.count { it.isConnected } == 2 }
        fresh.connection.send(Envelope(MessageType.PAIR_REQUEST, pake = Pake.Typer().request))
        fresh.expect(MessageType.PAIR_PAKE)
        assertEquals(fresh.identity.deviceId, pc.hub.pairing.value?.phoneId)
        assertFalse(pc.hub.pairing.value!!.outdated)
        // And the old phone asking again doesn't take the code away.
        old.connection.send(Envelope(MessageType.PAIR_REQUEST))
        old.expect(MessageType.PAIR_REJECT)
        assertEquals(fresh.identity.deviceId, pc.hub.pairing.value?.phoneId)
    }

    @Test
    fun pairingSurvivesARestart() {
        val dir = Files.createTempDirectory("dysk-pc").toFile()
        val first = Pc(dir)
        val phone = Phone()
        paired(first, phone)
        first.hub.shutdown()
        val again = Pc(dir)
        assertEquals(first.hub.deviceId, again.hub.deviceId)
        assertTrue(again.hub.phones.value.single().isPaired)
    }

    @Test
    fun thePhoneControlsThePcAndPlaysItsSoundThere() {
        // Both with binary frames, as the current apps are.
        val pc = Pc(caps = PhoneLinkProtocol.CAPS)
        val phone = Phone(PhoneLinkProtocol.CAPS)
        paired(pc, phone)
        val id = pc.hub.deviceId
        val phoneId = phone.manager.localDeviceId

        phone.manager.sendInput(id, InputEvent("move", dx = 12f, dy = 3f))
        phone.manager.sendInput(id, InputEvent("shortcut", key = "cmd+c"))
        phone.manager.sendMedia(id, "play_pause")
        waitUntil("Input didn't reach the PC") { pc.inputs.size == 2 && pc.media == listOf("play_pause") }
        assertEquals(listOf("move:12.0", "shortcut:cmd+c"), pc.inputs.toList())

        // With control turned off on the PC, touchpad input is ignored.
        pc.control = false
        phone.manager.sendInput(id, InputEvent("text", text = "nope"))
        phone.manager.sendMedia(id, "next")
        waitUntil("Media didn't arrive") { "next" in pc.media }
        assertEquals(2, pc.inputs.size)

        // The phone's sound: started, a chunk, and stopped from the PC.
        var stopAsked = false
        phone.manager.audioSink = { _, envelope -> if (envelope.type == MessageType.AUDIO_STOP) stopAsked = true }
        phone.manager.post(id, Envelope(MessageType.AUDIO_STARTED, audioRate = 48_000, audioChannels = 2))
        waitUntil("No binary frames") { phone.manager.binaryTo(id) }
        // One raw chunk (a binary frame) and one in base64 (JSON, as an older phone sends): both play.
        phone.manager.post(id, Envelope(MessageType.AUDIO_FRAME, raw = ByteArray(7680)))
        phone.manager.post(id, Envelope(MessageType.AUDIO_FRAME, audio = java.util.Base64.getEncoder().encodeToString(ByteArray(7680))))
        waitUntil("Sound didn't reach the PC") { pc.sound.toList() == listOf("start 48000/2", "chunk 7680", "chunk 7680") }
        pc.hub.stopPhoneSound()
        waitUntil("The phone wasn't asked to stop") { stopAsked }
        assertEquals("end", pc.sound.last())

        // A PC can't send its own sound yet, and says so.
        var refused: String? = null
        phone.manager.audioSink = { _, envelope -> if (envelope.type == MessageType.AUDIO_STOPPED) refused = envelope.error }
        phone.manager.post(id, Envelope(MessageType.AUDIO_LISTEN))
        waitUntil("The PC didn't answer audio.listen") { refused != null }
        assertTrue(phoneId.isNotEmpty())
    }

    @Test
    fun twoPcsPairBySyncingAndShareOnlyTheClipboardFilesAndRinging() {
        // Windows has binary frames, Linux acts like 1.1.x: everything between them stays JSON.
        val windows = Pc(caps = PhoneLinkProtocol.CAPS)
        val linux = Pc()
        windows.hub.setComputersEnabled(true)
        linux.hub.setComputersEnabled(true)
        windows.hub.dialComputer("127.0.0.1", linux.hub.boundPort)
        waitUntil("The PCs didn't connect") {
            windows.hub.phones.value.any { it.id == linux.hub.deviceId && it.isConnected && it.isComputer } &&
                linux.hub.phones.value.any { it.id == windows.hub.deviceId && it.isConnected }
        }

        // Windows asks; Linux shows a code, which is typed on Windows. A wrong one first.
        windows.hub.requestPairing(linux.hub.deviceId)
        waitUntil("Linux didn't show a code") { linux.hub.pairing.value?.code?.length == 6 }
        assertTrue(windows.hub.pairing.value?.typing == true)
        val code = linux.hub.pairing.value!!.code
        windows.hub.submitCode(if (code == "000000") "111111" else "000000")
        waitUntil("The wrong code wasn't reported") { windows.hub.pairing.value?.error != null }
        windows.hub.submitCode(code)
        waitUntil("Pairing didn't finish on both sides") {
            windows.hub.phones.value.any { it.id == linux.hub.deviceId && it.isPaired } &&
                linux.hub.phones.value.any { it.id == windows.hub.deviceId && it.isPaired }
        }
        assertNull(windows.hub.pairing.value)
        assertNull(linux.hub.pairing.value)

        // Text, a picture, a file, and ringing.
        windows.hub.sendClipboard("dari-windows")
        waitUntil("Text didn't arrive") { "dari-windows" in linux.clipboard }
        val png = ByteArray(300_000) { (it % 251).toByte() }
        linux.hub.sendClipboardImage(png)
        waitUntil("The picture didn't arrive") { windows.images.isNotEmpty() }
        assertTrue(windows.images.first().contentEquals(png), "The picture changed on the way")
        assertTrue(windows.hub.fileTransfers.transfers.value.isEmpty(), "A clipboard picture isn't a file transfer")
        val file = File.createTempFile("laporan", ".txt").apply { writeText("isi") }
        windows.hub.sendFile(linux.hub.deviceId, file)
        waitUntil("The file didn't arrive") { File(linux.received, file.name).exists() }
        windows.hub.sendClipboardImage(png)
        waitUntil("The picture didn't arrive on Linux") { linux.images.isNotEmpty() }
        assertTrue(linux.images.first().contentEquals(png), "The picture changed on the way")
        windows.hub.ring(linux.hub.deviceId, true)
        waitUntil("Linux didn't ring") { true in linux.rings }

        // A computer can't use phone-only messages such as input, even with control allowed.
        val linuxAsSeen = windows.hub.phones.value.first { it.id == linux.hub.deviceId }
        assertTrue(linuxAsSeen.isComputer)
        assertTrue(windows.inputs.isEmpty())

        // Turned off, the connection closes and doesn't come back.
        linux.hub.setComputersEnabled(false)
        waitUntil("Turning it off didn't disconnect") { windows.hub.phones.value.none { it.id == linux.hub.deviceId && it.isConnected } }
        windows.hub.dialComputer("127.0.0.1", linux.hub.boundPort)
        Thread.sleep(1500)
        assertTrue(windows.hub.phones.value.none { it.id == linux.hub.deviceId && it.isConnected }, "A PC with it off let a computer in")
    }

    /** A device speaking the protocol by hand, with the identity kept in [dir], so tests can send what real apps never do. */
    private inner class Raw(dir: File, port: Int) {
        val identity = LocalIdentity.loadOrCreate(dir)
        val connection = PeerConnection.connect(identity.sslContext(), "127.0.0.1", port)
        val received = java.util.concurrent.LinkedBlockingQueue<Envelope>()
        @Volatile var closed = false

        init {
            closers += connection::close
            Thread {
                try { connection.readLoop { received += it } } catch (_: Exception) {}
                closed = true
            }.apply { isDaemon = true }.start()
        }

        fun hello(platform: String, paired: Boolean, version: Int = PhoneLinkProtocol.VERSION) =
            connection.send(Envelope(MessageType.HELLO, hello = Hello("Raw", platform, "Raw", version, paired)))

        fun expect(type: String, seconds: Int = 5): Envelope {
            val until = System.currentTimeMillis() + seconds * 1000L
            while (true) {
                val left = until - System.currentTimeMillis()
                if (left <= 0) throw AssertionError("No $type arrived")
                val next = received.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                if (next.type == type) return next
            }
        }
    }

    @Test
    fun aPairedPhoneThatClaimsToBeAComputerIsHungUpOn() {
        val pc = Pc()
        pc.hub.setComputersEnabled(true)
        val phone = Phone()
        paired(pc, phone)
        val phoneId = phone.manager.localDeviceId
        phone.manager.shutdown()
        waitUntil("The phone didn't leave") { pc.hub.phones.value.none { it.isConnected } }

        // The same certificate, now saying it's a Mac, which would let it past the phone-only checks.
        val fake = Raw(phone.dir, pc.hub.boundPort)
        fake.hello("macos", paired = true)
        waitUntil("A hello that changed the kind of device wasn't hung up on") { fake.closed }
        val seen = pc.hub.phones.value.single { it.id == phoneId }
        assertEquals("android", seen.platform)
        assertFalse(seen.isConnected)
        assertTrue(seen.isPaired)

        // As a phone it connects, and a second hello on that connection changes nothing.
        val honest = Raw(phone.dir, pc.hub.boundPort)
        honest.hello("android", paired = true)
        waitUntil("The phone didn't connect again") { pc.hub.phones.value.any { it.id == phoneId && it.isConnected } }
        honest.hello("macos", paired = true)
        honest.connection.send(Envelope(MessageType.PING))
        honest.expect(MessageType.PONG)
        assertEquals("android", pc.hub.phones.value.single { it.id == phoneId }.platform)
        assertFalse(pc.hub.phones.value.single { it.id == phoneId }.isComputer)
    }

    @Test
    fun askingAgainKeepsTheCodeAndTooManyWrongCodesLockPairing() {
        val pc = Pc()
        val guesser = Raw(Files.createTempDirectory("dysk-guess").toFile(), pc.hub.boundPort)
        guesser.hello("android", paired = false)
        waitUntil("The guesser didn't connect") { pc.hub.phones.value.any { it.isConnected } }
        val typer = Pake.Typer()
        guesser.connection.send(Envelope(MessageType.PAIR_REQUEST, pake = typer.request))
        waitUntil("No code was shown") { pc.hub.pairing.value != null }
        val code = pc.hub.pairing.value!!.code
        assertTrue(typer.received(guesser.expect(MessageType.PAIR_PAKE).pake))
        val channel = Pake.channel(guesser.identity.deviceId, pc.hub.deviceId)
        val wrong = if (code == "000000") "111111" else "000000"
        fun guess() = guesser.connection.send(Envelope(MessageType.PAIR_ACCEPT, pake = typer.answer(wrong, channel)))

        guess()
        assertEquals(Pairing.WRONG_CODE, guesser.expect(MessageType.PAIR_REJECT).error)
        assertEquals(1, pc.hub.pairing.value?.attempts)
        // Asking again doesn't make a new code or forget the wrong try.
        guesser.connection.send(Envelope(MessageType.PAIR_REQUEST, pake = typer.request))
        guesser.connection.send(Envelope(MessageType.PING))
        guesser.expect(MessageType.PONG)
        assertEquals(code, pc.hub.pairing.value?.code)
        assertEquals(1, pc.hub.pairing.value?.attempts)

        repeat(4) { guess() }
        waitUntil("Five wrong codes didn't close the code") { pc.hub.pairing.value == null }
        while (guesser.expect(MessageType.PAIR_REJECT).error == Pairing.WRONG_CODE) continue
        // Locked: asking again is refused, with no code shown.
        guesser.connection.send(Envelope(MessageType.PAIR_REQUEST, pake = Pake.Typer().request))
        val refusal = guesser.expect(MessageType.PAIR_REJECT)
        assertTrue(refusal.error.orEmpty().startsWith("Too many wrong codes"), "Got ${refusal.error}")
        assertNull(pc.hub.pairing.value)

        // The lock holds for every device, since a new certificate costs nothing.
        val another = Raw(Files.createTempDirectory("dysk-guess2").toFile(), pc.hub.boundPort)
        another.hello("android", paired = false)
        waitUntil("The second guesser didn't connect") { pc.hub.phones.value.count { it.isConnected } == 2 }
        another.connection.send(Envelope(MessageType.PAIR_REQUEST, pake = Pake.Typer().request))
        another.expect(MessageType.PAIR_REJECT)
        assertNull(pc.hub.pairing.value)
        // An older app is told to wait too, before any notice is shown. Two strangers per address at most.
        guesser.connection.close()
        waitUntil("The guesser didn't leave") { pc.hub.phones.value.count { it.isConnected } == 1 }
        val old = Raw(Files.createTempDirectory("dysk-guess3").toFile(), pc.hub.boundPort)
        old.hello("android", paired = false, version = 2)
        waitUntil("The old phone didn't connect") { pc.hub.phones.value.any { it.id == old.identity.deviceId && it.isConnected } }
        old.connection.send(Envelope(MessageType.PAIR_REQUEST))
        assertTrue(old.expect(MessageType.PAIR_REJECT).error.orEmpty().startsWith("Too many wrong codes"))
        assertNull(pc.hub.pairing.value)
    }

    @Test
    fun olderAppsMustUpdateBeforePairing() {
        val pc = Pc()
        // A request without CPace gets no code, only "update-required", and the PC says why.
        val old = Raw(Files.createTempDirectory("dysk-old").toFile(), pc.hub.boundPort)
        old.hello("android", paired = false, version = 2)
        waitUntil("The old phone didn't connect") { pc.hub.phones.value.any { it.isConnected } }
        old.connection.send(Envelope(MessageType.PAIR_REQUEST))
        assertEquals(Pairing.UPDATE_REQUIRED, old.expect(MessageType.PAIR_REJECT).error)
        waitUntil("The PC didn't say to update") { pc.hub.pairing.value?.outdated == true }
        assertEquals("", pc.hub.pairing.value!!.code)
        assertEquals("Update DyskLink on Raw to pair.", pc.hub.pairing.value!!.error)
        // The old key is refused outright, even for a right code: there's no code to check it against.
        old.connection.send(Envelope(MessageType.PAIR_ACCEPT, key = "anything"))
        old.connection.send(Envelope(MessageType.PING))
        old.expect(MessageType.PONG)
        assertFalse(pc.hub.phones.value.single().isPaired)
        pc.hub.rejectPairing()
        waitUntil("The notice didn't close") { pc.hub.pairing.value == null }

        // A new app whose request is fine, but which then sends only the old key: refused, and no try counted.
        val mixed = Raw(Files.createTempDirectory("dysk-mixed").toFile(), pc.hub.boundPort)
        mixed.hello("android", paired = false)
        waitUntil("The second phone didn't connect") { pc.hub.phones.value.count { it.isConnected } == 2 }
        mixed.connection.send(Envelope(MessageType.PAIR_REQUEST, pake = Pake.Typer().request))
        mixed.expect(MessageType.PAIR_PAKE)
        mixed.connection.send(Envelope(MessageType.PAIR_ACCEPT, key = "legacy"))
        assertEquals(Pairing.UPDATE_REQUIRED, mixed.expect(MessageType.PAIR_REJECT).error)
        waitUntil("The code stayed open") { pc.hub.pairing.value == null }
        assertTrue(pc.hub.phones.value.none { it.isPaired })
    }

    @Test
    fun theComputerPingsSoQuietLinksStayUp() {
        val pc = Pc(pingMs = 200)
        val peer = Raw(Files.createTempDirectory("dysk-ping").toFile(), pc.hub.boundPort)
        peer.hello("android", paired = false)
        peer.expect(MessageType.PING, seconds = 3)
    }

    /** DyskLink for Mac, started by mac/scripts/pc-sync-test.sh with Other computers on, on DYSKLINK_IT_MAC_PORT. */
    @Test
    fun aPcPairsAndSyncsWithTheMacApp() {
        val port = System.getenv("DYSKLINK_IT_MAC_PORT")?.toIntOrNull() ?: return
        // Binary frames, like the real desktop app; pc-sync-test.sh checks the Mac used them.
        val pc = Pc(caps = PhoneLinkProtocol.CAPS)
        pc.hub.setComputersEnabled(true)
        pc.hub.dialComputer("127.0.0.1", port)
        waitUntil("The Mac didn't connect", 20) { pc.hub.phones.value.any { it.isConnected && it.platform == "macos" } }
        val mac = pc.hub.phones.value.first { it.platform == "macos" }
        pc.hub.requestPairing(mac.id)
        waitUntil("The PC isn't typing a code") { pc.hub.pairing.value?.typing == true }
        // The Mac's test build shows 123456.
        pc.hub.submitCode("123456")
        waitUntil("Pairing with the Mac didn't finish", 15) { pc.hub.phones.value.any { it.id == mac.id && it.isPaired } }
        // The Mac sends its test clipboard and picture once paired, and gets ours.
        waitUntil("The Mac's text didn't arrive", 15) { "dari-mac" in pc.clipboard }
        waitUntil("The Mac's picture didn't arrive", 15) { pc.images.isNotEmpty() }
        pc.hub.sendClipboard("dari-pc")
        pc.hub.sendClipboardImage(ByteArray(5_000) { 7 })
        val file = File.createTempFile("dari-pc", ".txt").apply { writeText("isi dari pc") }
        pc.hub.sendFile(mac.id, file)
        pc.hub.ring(mac.id, true)
        Thread.sleep(3000)
        println("PC_SYNC_DONE ${file.name}")
    }

    @Test
    fun twoPcsWithBinaryFramesShareFilesAndPictures() {
        val windows = Pc(caps = PhoneLinkProtocol.CAPS)
        val linux = Pc(caps = PhoneLinkProtocol.CAPS)
        windows.hub.setComputersEnabled(true)
        linux.hub.setComputersEnabled(true)
        windows.hub.dialComputer("127.0.0.1", linux.hub.boundPort)
        waitUntil("The PCs didn't connect") { windows.hub.phones.value.any { it.id == linux.hub.deviceId && it.isConnected } }
        windows.hub.requestPairing(linux.hub.deviceId)
        waitUntil("Linux didn't show a code") { linux.hub.pairing.value?.code?.length == 6 }
        windows.hub.submitCode(linux.hub.pairing.value!!.code)
        waitUntil("Pairing didn't finish") { windows.hub.phones.value.any { it.id == linux.hub.deviceId && it.isPaired } }
        // Pictures in binary chunks (several, odd size) both ways, and a file.
        val png = ByteArray(200_001) { (it % 241).toByte() }
        linux.hub.sendClipboardImage(png)
        windows.hub.sendClipboardImage(png.reversedArray())
        waitUntil("The pictures didn't arrive") { windows.images.isNotEmpty() && linux.images.isNotEmpty() }
        assertTrue(windows.images.first().contentEquals(png))
        assertTrue(linux.images.first().contentEquals(png.reversedArray()))
        val file = File.createTempFile("biner", ".bin").apply { writeBytes(ByteArray(150_000) { (it * 13).toByte() }) }
        windows.hub.sendFile(linux.hub.deviceId, file)
        waitUntil("The file didn't arrive") { File(linux.received, file.name).let { it.exists() && it.length() == file.length() } }
        assertTrue(File(linux.received, file.name).readBytes().contentEquals(file.readBytes()))
    }
}
