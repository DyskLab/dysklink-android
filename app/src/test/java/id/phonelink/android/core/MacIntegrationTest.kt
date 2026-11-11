package id.phonelink.android.core

import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assume.assumeTrue

/**
 * Tests the debug Mac app over real TLS. Only runs when DYSKLINK_IT_PORT is set,
 * see scripts/integration-test.sh in dysklink-mac. The Mac runs in dry run mode with separate folders.
 */
class MacIntegrationTest {
    private val port = System.getenv("DYSKLINK_IT_PORT")?.toIntOrNull()
    private val downloads = System.getenv("DYSKLINK_IT_DOWNLOADS")?.let(::File)

    private class Client(port: Int) {
        val identity = LocalIdentity.loadOrCreate(Files.createTempDirectory("dysk-it").toFile())
        val connection = PeerConnection.connect(identity.sslContext(), "127.0.0.1", port)
        val inbox = LinkedBlockingQueue<Envelope>()
        val history = CopyOnWriteArrayList<Envelope>()
        @Volatile var onMessage: ((Envelope) -> Unit)? = null

        init {
            thread(isDaemon = true) {
                runCatching {
                    connection.readLoop {
                        history.add(it)
                        onMessage?.invoke(it)
                        inbox.put(it)
                    }
                }
            }
            connection.send(
                Envelope(MessageType.HELLO, hello = Hello("Tes", "android", "JVM", PhoneLinkProtocol.VERSION, false)),
            )
        }

        fun await(type: String, seconds: Long = 10): Envelope? {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (true) {
                val left = deadline - System.nanoTime()
                if (left <= 0) return null
                val message = inbox.poll(left, TimeUnit.NANOSECONDS) ?: return null
                if (message.type == type) return message
            }
        }

        /** The debug Mac shows 123456 in tests, and the phone types it. */
        fun pair() {
            connection.send(Envelope(MessageType.PAIR_REQUEST))
            Thread.sleep(300)
            connection.send(Envelope(MessageType.PAIR_ACCEPT, key = Pairing.proof("123456", identity.deviceId, connection.peerId)))
            assertNotNull(await(MessageType.PAIR_ACCEPT), "Mac did not accept pairing")
        }

        fun sendFile(id: String, name: String, data: ByteArray, kind: String? = null) {
            val encoded = Base64.getEncoder().encodeToString(data)
            connection.send(Envelope(MessageType.FILE_OFFER, file = FileMessage(id, name = name, size = data.size.toLong(), kind = kind)))
            connection.send(Envelope(MessageType.FILE_CHUNK, file = FileMessage(id, data = encoded)))
            connection.send(Envelope(MessageType.FILE_DONE, file = FileMessage(id, sha256 = sha256Hex(data))))
        }
    }

    @Test
    fun unpairedDeviceCannotUseAnyFeature() {
        assumeTrue(port != null)
        val client = Client(port!!)
        assertNotNull(client.await(MessageType.HELLO))
        client.sendFile("asing", "asing.txt", "tidak boleh masuk".toByteArray())
        client.connection.send(Envelope(MessageType.COMMAND_RUN, commandId = "uji"))
        client.connection.send(Envelope(MessageType.CLIPBOARD, text = "dari-perangkat-asing"))
        client.connection.send(Envelope(MessageType.INPUT, input = InputEvent("text", text = "dari-perangkat-asing")))
        client.connection.send(Envelope(MessageType.MEDIA, media = "play_pause"))
        client.connection.send(Envelope(MessageType.FEATURES, features = PhoneFeature.entries.map { it.id }))
        client.connection.send(Envelope(MessageType.SCREEN_STARTED, screen = ScreenInfo(1234, 1234)))
        client.connection.send(Envelope(MessageType.SMS_NEW, sms = listOf(SmsItem(1, 1, "x", "dari-perangkat-asing", 1, false))))
        client.connection.send(Envelope(MessageType.CALL, call = CallEvent("ringing", "dari-perangkat-asing", 1)))
        client.connection.send(
            Envelope(MessageType.NOTIFICATION_POSTED, notification = NotificationItem("k", "dari-perangkat-asing", time = 1)),
        )

        client.connection.send(Envelope(MessageType.CAMERA_STARTED, screen = ScreenInfo(1281, 721), text = "asing"))
        client.connection.send(Envelope(MessageType.MACSCREEN_START))
        client.connection.send(Envelope(MessageType.MACSCREEN_POINTER, control = ControlEvent("click", 0.5f, 0.5f)))
        assertNull(client.await(MessageType.MACSCREEN_STARTED, seconds = 1), "Screen shown to an unknown device")
        client.connection.send(Envelope(MessageType.NOTCH_ACTION, notchAction = NotchAction("todo.add", text = "asing-tugas")))
        assertNull(client.await(MessageType.NOTCH_STATE, seconds = 1), "Notch sent to an unknown device")
        assertNull(client.await(MessageType.FILE_RESULT, seconds = 2), "File accepted from an unknown device")
        assertNull(client.await(MessageType.COMMAND_RESULT, seconds = 1), "Command run for an unknown device")
        assertTrue(downloads!!.listFiles().orEmpty().none { it.name == "asing.txt" })
        client.connection.close()
    }

    @Test
    fun phoneCameraShowsOnMac() {
        assumeTrue(port != null)
        val client = Client(port!!)
        client.pair()
        client.connection.send(Envelope(MessageType.CAMERA_STARTED, screen = ScreenInfo(1280, 720), text = "front", rotation = 270))
        assertNotNull(client.await(MessageType.CAMERA_SYNC), "Mac did not ask for a key frame")
        // A turn from the phone updates the same window and asks for a fresh picture.
        client.connection.send(Envelope(MessageType.CAMERA_STARTED, screen = ScreenInfo(1280, 720), text = "front", rotation = 0))
        assertNotNull(client.await(MessageType.CAMERA_SYNC), "Mac did not follow the turn")
        client.connection.send(Envelope(MessageType.CAMERA_FRAME, video = Base64.getEncoder().encodeToString(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2))))
        client.connection.send(Envelope(MessageType.CAMERA_STOPPED, error = "uji-berhenti"))
        Thread.sleep(500)
        client.connection.close()
    }

    @Test
    fun syncedPhotosGoToPictures() {
        assumeTrue(port != null)
        val photos = System.getenv("DYSKLINK_IT_PHOTOS")?.let(::File)
        assumeTrue(photos != null)
        val client = Client(port!!)
        client.pair()
        val data = ByteArray(70_000) { (it * 7).toByte() }
        client.sendFile("foto-1", "PXL_20261111.jpg", data, kind = "photo")
        val result = assertNotNull(client.await(MessageType.FILE_RESULT), "Mac did not answer the photo")
        assertEquals(true, result.file?.ok)
        assertContentEquals(data, File(photos, "Tes/PXL_20261111.jpg").readBytes())
        assertTrue(!File(downloads, "PXL_20261111.jpg").exists(), "Photo landed in Downloads")
        client.connection.close()
    }

    @Test
    fun audioGoesBothWays() {
        assumeTrue(port != null)
        val client = Client(port!!)
        client.pair()
        // The Mac's sound to the phone: a test tone in 40 ms stereo chunks.
        client.connection.send(Envelope(MessageType.AUDIO_LISTEN))
        val started = assertNotNull(client.await(MessageType.AUDIO_STARTED, seconds = 10), "Mac did not start its sound")
        assertEquals(48_000, started.audioRate)
        assertEquals(2, started.audioChannels)
        val frame = assertNotNull(client.await(MessageType.AUDIO_FRAME, seconds = 5), "No sound from the Mac")
        val pcm = Base64.getDecoder().decode(frame.audio)
        assertEquals(7_680, pcm.size)
        assertTrue(pcm.any { it != 0.toByte() }, "The Mac sent silence")
        client.connection.send(Envelope(MessageType.AUDIO_STOP))
        assertNotNull(client.await(MessageType.AUDIO_STOPPED, seconds = 5), "Mac did not confirm the stop")

        // The phone's sound to the Mac.
        client.connection.send(Envelope(MessageType.AUDIO_STARTED, audioRate = 48_000, audioChannels = 2))
        val chunk = ByteArray(7_680) { (it % 50).toByte() }
        repeat(4) { client.connection.send(Envelope(MessageType.AUDIO_FRAME, audio = Base64.getEncoder().encodeToString(chunk))) }
        client.connection.send(Envelope(MessageType.AUDIO_STOPPED))
        Thread.sleep(500)
    }

    @Test
    fun macScreenStreamsToPhone() {
        assumeTrue(port != null)
        val client = Client(port!!)
        client.pair()
        client.connection.send(Envelope(MessageType.MACSCREEN_START))
        val started = assertNotNull(client.await(MessageType.MACSCREEN_STARTED, seconds = 15), "Mac did not start sharing its screen")
        val size = assertNotNull(started.screen)
        assertTrue(size.width in 2..1600 && size.height in 2..1600 && size.width % 2 == 0 && size.height % 2 == 0, "Odd size $size")

        // The first frame is a key frame that carries SPS (7) and PPS (8) before the picture (5).
        val frame = assertNotNull(client.await(MessageType.MACSCREEN_FRAME, seconds = 10), "No video from the Mac")
        assertEquals(true, frame.keyFrame)
        val data = Base64.getDecoder().decode(frame.video)
        val types = MacScreenTestNal.types(data)
        assertTrue(types.containsAll(listOf(7, 8, 5)) && types.indexOf(7) < types.indexOf(5), "NAL types $types")

        // Asking again gives the size and another key frame, as when the app comes back.
        client.connection.send(Envelope(MessageType.MACSCREEN_SYNC))
        val deadline = System.currentTimeMillis() + 5_000
        var key = false
        while (!key && System.currentTimeMillis() < deadline) key = client.await(MessageType.MACSCREEN_FRAME, seconds = 5)?.keyFrame == true
        assertTrue(key, "No key frame after sync")

        client.connection.send(Envelope(MessageType.MACSCREEN_POINTER, control = ControlEvent("click", 0.5f, 0.25f)))
        client.connection.send(Envelope(MessageType.MACSCREEN_STOP))
        Thread.sleep(800)

        // As an extra display: the Mac works out the size, and test Macs don't make real displays.
        client.connection.send(Envelope(MessageType.MACSCREEN_START, text = "extend", screen = ScreenInfo(1120, 504)))
        // The earlier stop also answered with a plain stopped message, which is skipped.
        var refused: Envelope? = null
        val until = System.currentTimeMillis() + 5_000
        while (refused?.error == null && System.currentTimeMillis() < until) refused = client.await(MessageType.MACSCREEN_STOPPED, seconds = 5)
        assertTrue(refused?.error?.contains("tests") == true, "Unexpected answer: ${refused?.error}")
        // Upright too.
        client.connection.send(Envelope(MessageType.MACSCREEN_START, text = "extend", screen = ScreenInfo(504, 1120)))
        Thread.sleep(800)
        // Larger text: a smaller 1× desktop.
        client.connection.send(Envelope(MessageType.MACSCREEN_START, text = "extend-large", screen = ScreenInfo(1000, 450)))
        Thread.sleep(800)
        client.connection.close()
    }

    @Test
    fun notchSyncsWithMac() {
        assumeTrue(port != null)
        val client = Client(port!!)
        client.pair()
        fun nextState(seconds: Long = 10) = assertNotNull(client.await(MessageType.NOTCH_STATE, seconds), "Mac did not send the notch").notch!!

        val first = nextState()
        val glass = first.water.glassMl
        client.connection.send(Envelope(MessageType.NOTCH_ACTION, notchAction = NotchAction("todo.add", text = "uji-tugas")))
        var state = nextState()
        val task = assertNotNull(state.todos.firstOrNull { it.title == "uji-tugas" }, "Task was not added")

        client.connection.send(Envelope(MessageType.NOTCH_ACTION, notchAction = NotchAction("note.set", text = "catatan-uji\nbaris dua")))
        state = nextState()
        assertEquals("catatan-uji\nbaris dua", state.note)
        assertNotNull(state.noteTime)

        client.connection.send(Envelope(MessageType.NOTCH_ACTION, notchAction = NotchAction("todo.rename", id = task.id, text = "uji-ganti")))
        state = nextState()
        assertEquals("uji-ganti", state.todos.first { it.id == task.id }.title)

        client.connection.send(Envelope(MessageType.NOTCH_ACTION, notchAction = NotchAction("todo.toggle", id = task.id)))
        state = nextState()
        assertTrue(state.todos.first { it.id == task.id }.done)

        client.connection.send(Envelope(MessageType.NOTCH_ACTION, notchAction = NotchAction("todo.delete", id = task.id)))
        state = nextState()
        assertTrue(state.todos.none { it.id == task.id })

        client.connection.send(Envelope(MessageType.NOTCH_ACTION, notchAction = NotchAction("water.drink")))
        state = nextState()
        assertEquals(first.water.totalMl + glass, state.water.totalMl)
        client.connection.send(Envelope(MessageType.NOTCH_ACTION, notchAction = NotchAction("water.undo")))
        assertEquals(first.water.totalMl, nextState().water.totalMl)

        client.connection.send(Envelope(MessageType.NOTCH_ACTION, notchAction = NotchAction("focus.toggle")))
        assertTrue(nextState().focus.running)
        client.connection.send(Envelope(MessageType.NOTCH_ACTION, notchAction = NotchAction("focus.reset")))
        assertTrue(!nextState().focus.running)

        client.connection.send(Envelope(MessageType.MAC_ACTION, macAction = "lock"))
        val info = assertNotNull(client.await(MessageType.INFO), "Mac did not send its status after an action").info
        assertEquals("unavailable", info?.keepAwake)
        // Wake-on-LAN: the Mac sends a real hardware address and its network's broadcast address.
        val macs = info?.wakeMacs.orEmpty()
        assertTrue(macs.isNotEmpty() && macs.all { WakeOnLan.packet(it) != null && it != "02:00:00:00:00:00" }, "No wake address: $macs")
        assertNotNull(info?.wakeBroadcast)
        client.connection.send(Envelope(MessageType.RING, ring = "start"))
        client.connection.send(Envelope(MessageType.RING, ring = "stop"))
        Thread.sleep(500)
        client.connection.close()
    }

    @Test
    fun pairedDeviceCanUseFeatures() {
        assumeTrue(port != null)
        val client = Client(port!!)
        client.pair()

        val list = assertNotNull(client.await(MessageType.COMMANDS_LIST), "Command list was not sent")
        assertEquals(listOf(CommandItem("uji", "Uji")), list.commands)

        // Script contents are never sent to the phone.
        client.connection.send(Envelope(MessageType.COMMAND_RUN, commandId = "uji"))
        val result = assertNotNull(client.await(MessageType.COMMAND_RESULT)).commandResult
        assertEquals(0, result?.exitCode)
        assertEquals("halo-dari-mac", result?.output?.trim())

        // An unregistered ID must not run anything.
        client.connection.send(Envelope(MessageType.COMMAND_RUN, commandId = "touch /tmp/dysk-jahat"))
        val unknown = assertNotNull(client.await(MessageType.COMMAND_RESULT)).commandResult
        assertEquals(-1, unknown?.exitCode)
        assertTrue(!File("/tmp/dysk-jahat").exists())

        // A file from the phone arrives intact, and a dangerous name doesn't escape the folder.
        val data = Random(3).nextBytes(150_000)
        client.sendFile("f1", "../../keluar.bin", data)
        assertEquals(true, assertNotNull(client.await(MessageType.FILE_RESULT)).file?.ok)
        assertContentEquals(data, File(downloads, "keluar.bin").readBytes())

        // A corrupt file must not be saved.
        client.connection.send(Envelope(MessageType.FILE_OFFER, file = FileMessage("f2", name = "rusak.bin", size = 3)))
        val chunk = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        client.connection.send(Envelope(MessageType.FILE_CHUNK, file = FileMessage("f2", data = chunk)))
        client.connection.send(Envelope(MessageType.FILE_DONE, file = FileMessage("f2", sha256 = "0".repeat(64))))
        assertEquals(false, assertNotNull(client.await(MessageType.FILE_RESULT)).file?.ok)
        assertTrue(!File(downloads, "rusak.bin").exists())
        assertTrue(downloads!!.listFiles().orEmpty().none { it.name.endsWith(".part") })

        // Logged by the Mac and checked by the test script.
        client.connection.send(Envelope(MessageType.CLIPBOARD, text = "dari-hp-uji", key = "c1"))
        val receipt = assertNotNull(client.await(MessageType.CLIPBOARD_ACK), "Mac did not confirm the clipboard")
        assertEquals("c1", receipt.key)
        assertNull(receipt.error)
        client.connection.send(Envelope(MessageType.MEDIA, media = "play_pause"))
        client.connection.send(Envelope(MessageType.MEDIA, media = "bukan-aksi"))
        client.connection.send(Envelope(MessageType.INPUT, input = InputEvent("key", key = "right")))
        client.connection.send(Envelope(MessageType.INPUT, input = InputEvent("shortcut", key = "cmd+shift+4")))

        // A terminal: the Mac runs a real shell, and what it prints comes back.
        client.connection.send(Envelope(MessageType.TERMINAL_OPEN, terminal = TerminalMessage("t1", cols = 90, rows = 30)))
        assertNotNull(client.await(MessageType.TERMINAL_OPENED, 10), "Mac did not open the terminal")
        val typed = "echo dysk-\$((40+2)) \$TERM\r"
        client.connection.send(Envelope(MessageType.TERMINAL_INPUT, terminal = TerminalMessage("t1", data = Base64.getEncoder().encodeToString(typed.toByteArray()))))
        val screen = TerminalEmulator(90, 30)
        val until = System.currentTimeMillis() + 15_000
        while (!screen.screenText().contains("dysk-42 xterm-256color") && System.currentTimeMillis() < until) {
            client.history.toList().filter { it.type == MessageType.TERMINAL_OUTPUT }.let { outputs ->
                val fresh = TerminalEmulator(90, 30)
                outputs.forEach { fresh.feed(Base64.getDecoder().decode(it.terminal!!.data)) }
                if (fresh.screenText().contains("dysk-42 xterm-256color")) screen.feed(fresh.screenText())
            }
            Thread.sleep(150)
        }
        assertTrue(screen.screenText().contains("dysk-42 xterm-256color"), "The shell output didn't come back")
        client.connection.send(Envelope(MessageType.TERMINAL_CLOSE, terminal = TerminalMessage("t1")))
        assertNotNull(client.await(MessageType.TERMINAL_CLOSED, 10), "The terminal did not close")

        // A file from Mac to phone arrives intact and its hash matches.
        val deadline = System.currentTimeMillis() + 15_000
        while (client.history.none { it.type == MessageType.FILE_DONE } && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        val messages = client.history.toList()
        val offer = assertNotNull(messages.firstOrNull { it.type == MessageType.FILE_OFFER }, "Mac did not send the file").file
        assertEquals("dari-mac.txt", offer?.name)
        val received = messages.filter { it.type == MessageType.FILE_CHUNK && it.file?.id == offer?.id }
            .flatMap { Base64.getDecoder().decode(it.file?.data).toList() }.toByteArray()
        assertEquals("isi dari mac\n", received.decodeToString())
        val done = assertNotNull(messages.firstOrNull { it.type == MessageType.FILE_DONE }).file
        assertEquals(sha256Hex(received), done?.sha256)
        client.connection.close()
    }

    @Test
    fun phoneBridgeWorksWithMac() {
        assumeTrue(port != null)
        val client = Client(port!!)
        client.pair()

        val root = Files.createTempDirectory("dysk-it-phone").toFile()
        val storage = File(root, "storage").apply { mkdirs() }
        File(storage, "folder").mkdirs()
        File(storage, "folder/uji.txt").writeText("isi file hp")
        val smsSent = CopyOnWriteArrayList<Pair<String, String>>()
        val dismissed = CopyOnWriteArrayList<String>()
        val messages = java.util.concurrent.atomic.AtomicReference(
            listOf(SmsItem(1, 1, "+6281200000002", "halo-dari-hp", 1000, false)),
        )
        val screenRequests = CopyOnWriteArrayList<String>()
        val controls = CopyOnWriteArrayList<ControlEvent>()
        val data = object : PhoneData {
            override val storageRoot = storage
            override fun isGranted(feature: PhoneFeature) = true
            override fun activeNotifications() = listOf(NotificationItem("k-lama", "AppLama", "Lama", "isi", 1))
            override fun dismissNotification(key: String) { dismissed.add(key) }
            override fun readSms(limit: Int, afterId: Long) = messages.get().filter { it.id > afterId }.take(limit)
            override fun sendSms(address: String, body: String, done: (String?) -> Unit) {
                smsSent.add(address to body)
                done(null)
            }
            override fun readContacts() = listOf(ContactItem("1", "Budi, Santoso", listOf("+6281200000002")))
            override fun requestScreen(peerId: String) { screenRequests.add(peerId) }
            override fun syncScreen(peerId: String) {}
            override fun stopScreen(peerId: String) {}
            override fun control(event: ControlEvent) { controls.add(event) }
            override fun screenTime() = UsageReport(0, 95 * 60_000L, listOf(UsageApp("com.example.chat", "Obrolan", 60 * 60_000L)), 12)
            override fun gallery(offset: Int, limit: Int) = if (offset > 0) emptyList() else listOf(
                GalleryItem(7, "PXL_uji.jpg", 1_000, 11, thumb = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))),
                GalleryItem(8, "VID_uji.mp4", 900, 5, video = true, durationMs = 12_000),
            )
            override fun galleryFile(id: Long) = if (id == 7L) GalleryFile("PXL_uji.jpg", 11) { "isi-foto-hp".byteInputStream() } else null
        }
        val scope = CoroutineScope(Dispatchers.IO)
        val transfers = FileTransfers(scope, { _, _ -> error("unused") }) { _, envelope ->
            client.connection.send(envelope)
        }
        val bridge = PhoneBridge(root, scope, data, transfers, { listOf("mac") }) { _, envelope ->
            client.connection.send(envelope)
        }
        client.onMessage = { if (PhoneBridge.handles(it.type)) bridge.handle("mac", it) }

        // Before being turned on, phone events must not reach the Mac.
        messages.set(listOf(SmsItem(9, 1, "+6281200000002", "sebelum-diaktifkan", 9, false)) + messages.get())
        bridge.smsChanged()
        Thread.sleep(300)
        messages.set(messages.get().filter { it.id != 9L })

        PhoneFeature.entries.forEach { bridge.setEnabled(it, true) }

        // The Mac requests its data once it knows the feature is on, then sends test requests.
        waitUntil("Mac did not request SMS") { client.history.any { it.type == MessageType.SMS_LIST } }
        waitUntil("Mac did not request contacts") { client.history.any { it.type == MessageType.CONTACTS_LIST } }
        waitUntil("Mac did not request screen time") { client.history.any { it.type == MessageType.USAGE_LIST } }
        waitUntil("Mac did not open a gallery photo") { client.history.any { it.type == MessageType.GALLERY_GET && it.galleryId == 7L } }
        waitUntil("SMS from Mac was not sent") { smsSent.isNotEmpty() }
        assertEquals("+620000" to "uji", smsSent.first())
        waitUntil("Notification was not dismissed") { dismissed.contains("uji-notif") }
        val downloaded = File(downloads, "uji.txt")
        waitUntil("Phone file did not reach the Mac") { downloaded.exists() }
        assertEquals("isi file hp", downloaded.readText())

        // Screen and control: requests from the Mac reach the phone with the right content.
        waitUntil("Screen request did not arrive") { screenRequests.isNotEmpty() }
        waitUntil("Control did not arrive") { controls.isNotEmpty() }
        assertEquals("home", controls.first().key)
        client.history.clear()
        client.connection.send(Envelope(MessageType.SCREEN_STARTED, screen = ScreenInfo(720, 1280)))
        waitUntil("Mac did not request a key frame") { client.history.any { it.type == MessageType.SCREEN_SYNC } }
        client.connection.send(Envelope(MessageType.SCREEN_STOPPED))

        // Messages pushed from the phone. The Mac works out the caller name from contacts it received.
        Thread.sleep(500)
        bridge.notificationPosted(NotificationItem("k-baru", "AppBaru", "Baru", "isi", 2))
        bridge.notificationRemoved("k-baru")
        messages.set(listOf(SmsItem(10, 1, "+6281200000002", "sms-baru kode OTP 482915", 2000, false)) + messages.get())
        bridge.smsChanged()
        bridge.callStateChanged("RINGING", "081200000002")
        bridge.callStateChanged("IDLE", null)
        Thread.sleep(500)

        // Turning a feature off clears its data on the Mac. The script checks this in the log.
        bridge.setEnabled(PhoneFeature.Contacts, false)
        Thread.sleep(500)
        client.connection.close()
    }

    private fun waitUntil(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            assertTrue(System.currentTimeMillis() < deadline, message)
            Thread.sleep(50)
        }
    }
}

/** Reads NAL unit types from H.264 Annex B. */
object MacScreenTestNal {
    fun types(data: ByteArray): List<Int> {
        val result = mutableListOf<Int>()
        var i = 0
        while (i + 3 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                result.add(data[i + 3].toInt() and 0x1F)
                i += 3
            } else {
                i++
            }
        }
        return result
    }
}
