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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Two phones on the same machine: they pair, then share files, the clipboard, ringing, and battery. */
class PhoneToPhoneTest {
    private val scopes = mutableListOf<CoroutineScope>()
    private val managers = mutableListOf<ConnectionManager>()

    private inner class Phone(name: String, battery: Int) {
        val dir: File = Files.createTempDirectory("dysk-$name").toFile()
        val clipboard = CopyOnWriteArrayList<String>()
        val rings = CopyOnWriteArrayList<String>()
        val received = File(dir, "received").apply { mkdirs() }
        val smsReads = CopyOnWriteArrayList<Long>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val manager = ConnectionManager(
            dir, scope,
            localHello = { paired -> Hello(name, "android", "Test $name", PhoneLinkProtocol.VERSION, paired) },
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
}
