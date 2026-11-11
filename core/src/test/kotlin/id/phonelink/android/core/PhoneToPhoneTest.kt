package id.phonelink.android.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Two phones on the same machine: they pair, then share files, the clipboard, ringing, and battery. */
class PhoneToPhoneTest {
    private val scopes = mutableListOf<CoroutineScope>()
    private val managers = mutableListOf<ConnectionManager>()

    /** [caps] null acts like an app before binary frames (1.1.x): its hello lists nothing. */
    private inner class Phone(name: String, battery: Int, caps: List<String>? = null) {
        val dir: File = Files.createTempDirectory("dysk-$name").toFile()
        val clipboard = CopyOnWriteArrayList<String>()
        val rings = CopyOnWriteArrayList<String>()
        val received = File(dir, "received").apply { mkdirs() }
        val smsReads = CopyOnWriteArrayList<Long>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val manager = ConnectionManager(
            dir, scope,
            localHello = { paired -> Hello(name, "android", "Test $name", PhoneLinkProtocol.VERSION, paired, caps = caps) },
            localInfo = { DeviceInfo(batteryPercent = battery) },
            isUiVisible = { false },
            actions = object : DeviceActions {
                override fun setClipboard(text: String) { clipboard += text }
                override fun media(action: String) {}
                override fun ring(from: String) { rings += from }
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
                override fun isGranted(feature: PhoneFeature) = true
                override fun activeNotifications() = emptyList<NotificationItem>()
                override fun dismissNotification(key: String) {}
                override fun readSms(limit: Int, afterId: Long): List<SmsItem> = emptyList<SmsItem>().also { smsReads += afterId }
                override fun sendSms(address: String, body: String, done: (String?) -> Unit) = done("no")
                override fun readContacts() = emptyList<ContactItem>()
                override fun requestScreen(peerId: String) {}
                override fun syncScreen(peerId: String) {}
                override fun stopScreen(peerId: String) {}
                override fun control(event: ControlEvent) {}
            },
        ).also { managers += it }
        val id get() = manager.localDeviceId
        fun peer(other: Phone) = manager.peers.value.firstOrNull { it.id == other.id }
    }

    @AfterTest
    fun close() {
        managers.forEach { it.shutdown() }
        scopes.forEach { it.cancel() }
    }

    private fun waitUntil(message: String, seconds: Int = 10, check: () -> Boolean) {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError(message)
            Thread.sleep(50)
        }
    }

    @Test
    fun onlyTheSmallerIdDials() {
        assertTrue(ConnectionManager.shouldDial("aaa", mapOf("platform" to "macos", "id" to "000"), phonesEnabled = false))
        assertTrue(ConnectionManager.shouldDial("aaa", emptyMap(), phonesEnabled = false))
        assertTrue(ConnectionManager.shouldDial("aaa", mapOf("platform" to "android", "id" to "bbb"), phonesEnabled = true))
        assertFalse(ConnectionManager.shouldDial("bbb", mapOf("platform" to "android", "id" to "aaa"), phonesEnabled = true))
        assertFalse(ConnectionManager.shouldDial("aaa", mapOf("platform" to "android", "id" to "aaa"), phonesEnabled = true))
        assertFalse(ConnectionManager.shouldDial("aaa", mapOf("platform" to "android", "id" to "bbb"), phonesEnabled = false))
        assertFalse(ConnectionManager.shouldDial("aaa", mapOf("platform" to "android"), phonesEnabled = true))
    }

    @Test
    fun twoPhonesPairAndShare() {
        val a = Phone("Alpha", 41)
        val b = Phone("Beta", 87)
        b.manager.startServer()
        waitUntil("Beta didn't listen") { b.manager.serverPort.value != null }
        a.manager.addManual("127.0.0.1", b.manager.serverPort.value!!)

        waitUntil("They didn't connect") { a.peer(b)?.isConnected == true && b.peer(a)?.isConnected == true }
        assertTrue(a.peer(b)!!.isPhone)
        assertTrue(b.peer(a)!!.isPhone)

        // Pairing: Beta was asked, so it shows a random code, and Alpha has to type it.
        a.manager.requestPairing(b.id)
        waitUntil("No code on Beta") { b.manager.prompt.value != null && a.manager.prompt.value != null }
        val shown = b.manager.prompt.value!!
        assertTrue(shown.showsCode && shown.code.length == 6)
        assertFalse(a.manager.prompt.value!!.showsCode)
        assertEquals("", a.manager.prompt.value!!.code)

        // A wrong code is refused, and Alpha can try again.
        val wrong = ((shown.code.toInt() + 1) % 1_000_000).toString().padStart(6, '0')
        a.manager.submitCode(wrong)
        waitUntil("Wrong code wasn't refused") { a.manager.prompt.value?.error != null }
        assertFalse(a.peer(b)!!.isPaired)
        assertEquals(1, b.manager.prompt.value!!.attempts)

        a.manager.submitCode(shown.code)
        waitUntil("Didn't pair") { a.peer(b)?.isPaired == true && b.peer(a)?.isPaired == true }
        assertEquals(null, a.manager.prompt.value)
        assertEquals(null, b.manager.prompt.value)

        // Battery arrives.
        waitUntil("No battery from Beta") { a.peer(b)?.info?.batteryPercent == 87 }
        waitUntil("No battery from Alpha") { b.peer(a)?.info?.batteryPercent == 41 }

        // Clipboard, with a receipt.
        var result: Boolean? = null
        a.manager.shareClipboard("halo dari alpha", force = true) { result = it }
        waitUntil("Clipboard didn't arrive") { b.clipboard.contains("halo dari alpha") }
        waitUntil("No receipt") { result != null }
        assertEquals(true, result)

        // Ringing.
        a.manager.ringMac(b.id, true)
        waitUntil("Beta didn't ring") { b.rings.contains("Alpha") }

        // A file.
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        a.manager.fileTransfers.sendFile(b.id, "foto.jpg", bytes.size.toLong()) { bytes.inputStream() }
        waitUntil("File didn't arrive", seconds = 20) { File(b.received, "foto.jpg").let { it.exists() && it.length() == bytes.size.toLong() } }
        assertTrue(File(b.received, "foto.jpg").readBytes().contentEquals(bytes))

        // SMS and the like are for Macs only, even from a paired phone.
        a.manager.sendDirect(b.id, Envelope(MessageType.SMS_LIST))
        a.manager.sendDirect(b.id, Envelope(MessageType.PING))
        Thread.sleep(500)
        assertTrue(b.smsReads.isEmpty())
        assertTrue(a.peer(b)!!.isConnected)

        // Turning phone connections off closes them.
        b.manager.setPhonesEnabled(false)
        waitUntil("Still connected") { a.peer(b)?.isConnected == false }
        b.manager.setPhonesEnabled(true)
    }

    private fun pair(a: Phone, b: Phone) {
        waitUntil("They didn't connect") { a.peer(b)?.isConnected == true && b.peer(a)?.isConnected == true }
        a.manager.requestPairing(b.id)
        waitUntil("No code on ${b.id}") { b.manager.prompt.value?.showsCode == true && a.manager.prompt.value != null }
        a.manager.submitCode(b.manager.prompt.value!!.code)
        waitUntil("Didn't pair") { a.peer(b)?.isPaired == true && b.peer(a)?.isPaired == true }
    }

    private fun sendFile(from: Phone, to: Phone, name: String, bytes: ByteArray) {
        from.manager.fileTransfers.sendFile(to.id, name, bytes.size.toLong()) { bytes.inputStream() }
        waitUntil("$name didn't arrive", seconds = 20) { File(to.received, name).let { it.exists() && it.length() == bytes.size.toLong() } }
        assertTrue(File(to.received, name).readBytes().contentEquals(bytes), "$name differs")
    }

    @Test
    fun binaryFramesBetweenNewAppsAndJsonWithOldOnes() {
        val new = Phone("Baru", 50, PhoneLinkProtocol.CAPS)
        val newer = Phone("Baru2", 60, PhoneLinkProtocol.CAPS)
        val old = Phone("Lama", 70)
        newer.manager.startServer()
        old.manager.startServer()
        waitUntil("Not listening") { newer.manager.serverPort.value != null && old.manager.serverPort.value != null }
        new.manager.addManual("127.0.0.1", newer.manager.serverPort.value!!)
        new.manager.addManual("127.0.0.1", old.manager.serverPort.value!!)
        pair(new, newer)
        pair(new, old)

        // Both listed bin1: binary both ways. With the old app: JSON both ways.
        waitUntil("No binary between new apps") { new.manager.binaryTo(newer.id) && newer.manager.binaryTo(new.id) }
        assertFalse(new.manager.binaryTo(old.id))
        assertFalse(old.manager.binaryTo(new.id))

        // Files (several chunks, odd size) arrive whole and pass the SHA-256 check either way.
        val bytes = Random(4).nextBytes(300_001)
        sendFile(new, newer, "biner.bin", bytes)
        sendFile(newer, new, "biner-balik.bin", bytes)
        sendFile(new, old, "json.bin", bytes)
        sendFile(old, new, "json-balik.bin", bytes)
        sendFile(new, newer, "kosong.bin", ByteArray(0))

        assertTrue(new.peer(newer)!!.isConnected && new.peer(old)!!.isConnected)
    }
}
