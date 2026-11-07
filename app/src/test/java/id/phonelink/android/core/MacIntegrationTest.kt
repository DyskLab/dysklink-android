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

        fun pair() {
            connection.send(Envelope(MessageType.PAIR_REQUEST))
            connection.send(Envelope(MessageType.PAIR_ACCEPT))
            assertNotNull(await(MessageType.PAIR_ACCEPT), "Mac did not accept pairing")
        }

        fun sendFile(id: String, name: String, data: ByteArray) {
            val encoded = Base64.getEncoder().encodeToString(data)
            connection.send(Envelope(MessageType.FILE_OFFER, file = FileMessage(id, name = name, size = data.size.toLong())))
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

        assertNull(client.await(MessageType.FILE_RESULT, seconds = 2), "File accepted from an unknown device")
        assertNull(client.await(MessageType.COMMAND_RESULT, seconds = 1), "Command run for an unknown device")
        assertTrue(downloads!!.listFiles().orEmpty().none { it.name == "asing.txt" })
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
        messages.set(listOf(SmsItem(10, 1, "+6281200000002", "sms-baru", 2000, false)) + messages.get())
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
