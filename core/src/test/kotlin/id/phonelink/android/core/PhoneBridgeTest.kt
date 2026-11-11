package id.phonelink.android.core

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class PhoneBridgeTest {
    private val folder: File = Files.createTempDirectory("dysk-bridge").toFile()
    private val storage = File(folder, "storage").apply { mkdirs() }
    private val sent = CopyOnWriteArrayList<Envelope>()

    private inner class FakeData : PhoneData {
        val granted = PhoneFeature.entries.toMutableSet()
        val smsSent = CopyOnWriteArrayList<Pair<String, String>>()
        val dismissed = CopyOnWriteArrayList<String>()
        var sms = listOf(SmsItem(2, 1, "+62811", "halo", 2000, false), SmsItem(1, 1, "+62811", "hai", 1000, true))
        override val storageRoot get() = storage
        override fun isGranted(feature: PhoneFeature) = feature in granted
        override fun activeNotifications() = listOf(NotificationItem("k", "App", "Judul", "Isi", 1))
        override fun dismissNotification(key: String) { dismissed.add(key) }
        override fun readSms(limit: Int, afterId: Long) = sms.filter { it.id > afterId }.take(limit)
        override fun sendSms(address: String, body: String, done: (String?) -> Unit) {
            smsSent.add(address to body)
            done(null)
        }
        override fun readContacts() = listOf(ContactItem("1", "Budi", listOf("+62811")))
        val screenRequests = CopyOnWriteArrayList<String>()
        val screenSyncs = CopyOnWriteArrayList<String>()
        val screenStops = CopyOnWriteArrayList<String>()
        val controls = CopyOnWriteArrayList<ControlEvent>()
        override fun requestScreen(peerId: String) { screenRequests.add(peerId) }
        override fun syncScreen(peerId: String) { screenSyncs.add(peerId) }
        override fun stopScreen(peerId: String) { screenStops.add(peerId) }
        override fun control(event: ControlEvent) { controls.add(event) }
    }

    private val data = FakeData()
    private val scope = CoroutineScope(Dispatchers.Default)
    private val transfers = FileTransfers(scope, { _, _ -> error("unused") }) { _, envelope ->
        sent.add(envelope)
        true
    }

    private fun bridge(paired: List<String> = listOf("mac")) =
        PhoneBridge(folder, scope, data, transfers, { paired }) { _, envelope -> sent.add(envelope) }

    private fun allEnabled() = bridge().also { b -> PhoneFeature.entries.forEach { b.setEnabled(it, true) } }

    private fun waitFor(type: String): Envelope? {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            sent.firstOrNull { it.type == type }?.let { return it }
            Thread.sleep(10)
        }
        return null
    }

    @Test
    fun everythingIsOffByDefault() {
        val bridge = bridge()
        assertEquals(emptySet(), bridge.active.value)
        File(storage, "rahasia.txt").writeText("x")

        bridge.handle("mac", Envelope(MessageType.SMS_LIST))
        bridge.handle("mac", Envelope(MessageType.CONTACTS_LIST))
        bridge.handle("mac", Envelope(MessageType.NOTIFICATION_DISMISS, key = "k"))
        bridge.handle("mac", Envelope(MessageType.SMS_SEND, smsSend = SmsSend("s", "+62811", "halo")))
        bridge.handle("mac", Envelope(MessageType.FS_LIST, path = ""))
        bridge.handle("mac", Envelope(MessageType.FS_GET, path = "rahasia.txt"))
        bridge.notificationPosted(NotificationItem("k", "App", "Judul", "Isi", 1))
        bridge.callStateChanged("RINGING", "+62811")
        bridge.smsChanged()

        assertEquals(false, assertNotNull(waitFor(MessageType.SMS_RESULT)).smsResult?.ok)
        assertNotNull(waitFor(MessageType.FS_LISTING))
        Thread.sleep(200)
        assertTrue(data.smsSent.isEmpty())
        assertTrue(data.dismissed.isEmpty())
        val leaked = setOf(
            MessageType.SMS_MESSAGES, MessageType.SMS_NEW, MessageType.CONTACTS,
            MessageType.NOTIFICATION_POSTED, MessageType.CALL, MessageType.FILE_OFFER,
        )
        assertTrue(sent.none { it.type in leaked }, "Data leaked: ${sent.map { it.type }}")
        assertTrue(sent.filter { it.type == MessageType.FS_LISTING }.all { it.entries.isNullOrEmpty() })
    }

    @Test
    fun revokedPermissionStopsDataEvenIfSwitchIsOn() {
        val bridge = allEnabled()
        data.granted.remove(PhoneFeature.Contacts)
        sent.clear()

        bridge.handle("mac", Envelope(MessageType.CONTACTS_LIST))

        // The phone tells the Mac the feature is off, and contacts are not sent.
        val features = assertNotNull(waitFor(MessageType.FEATURES)).features.orEmpty()
        assertTrue("contacts" !in features)
        assertTrue(sent.none { it.type == MessageType.CONTACTS })
        assertTrue(PhoneFeature.Contacts !in bridge.active.value)
    }

    @Test
    fun settingsSurviveRestart() {
        bridge().setEnabled(PhoneFeature.Sms, true)
        assertEquals(setOf(PhoneFeature.Sms), bridge().enabled.value)
    }

    @Test
    fun enabledFeaturesAnswerRequests() {
        val bridge = allEnabled()
        File(storage, "catatan.txt").writeText("isi")
        sent.clear()

        bridge.handle("mac", Envelope(MessageType.SMS_LIST))
        assertEquals(2, assertNotNull(waitFor(MessageType.SMS_MESSAGES)).sms?.size)

        bridge.handle("mac", Envelope(MessageType.CONTACTS_LIST))
        assertEquals("Budi", assertNotNull(waitFor(MessageType.CONTACTS)).contacts?.single()?.name)

        bridge.handle("mac", Envelope(MessageType.FS_LIST, path = ""))
        assertEquals("catatan.txt", assertNotNull(waitFor(MessageType.FS_LISTING)).entries?.single()?.name)

        bridge.handle("mac", Envelope(MessageType.FS_GET, path = "catatan.txt"))
        assertEquals("catatan.txt", assertNotNull(waitFor(MessageType.FILE_OFFER)).file?.name)

        bridge.handle("mac", Envelope(MessageType.SMS_SEND, smsSend = SmsSend("s1", "+62811", "halo")))
        assertEquals(true, assertNotNull(waitFor(MessageType.SMS_RESULT)).smsResult?.ok)
        assertEquals(listOf("+62811" to "halo"), data.smsSent.toList())
    }

    @Test
    fun invalidSmsIsRejected() {
        val bridge = allEnabled()
        sent.clear()
        bridge.handle("mac", Envelope(MessageType.SMS_SEND, smsSend = SmsSend("s", " ", "halo")))
        assertEquals(false, assertNotNull(waitFor(MessageType.SMS_RESULT)).smsResult?.ok)
        sent.clear()
        bridge.handle("mac", Envelope(MessageType.SMS_SEND, smsSend = SmsSend("s", "+62811", "x".repeat(2_001))))
        assertEquals(false, assertNotNull(waitFor(MessageType.SMS_RESULT)).smsResult?.ok)
        assertTrue(data.smsSent.isEmpty())
    }

    @Test
    fun onlyNewSmsIsPushed() {
        val bridge = allEnabled()
        bridge.handle("mac", Envelope(MessageType.SMS_LIST))
        assertNotNull(waitFor(MessageType.SMS_MESSAGES))
        data.sms = listOf(SmsItem(3, 1, "+62811", "baru", 3000, false)) + data.sms
        bridge.smsChanged()
        assertEquals(listOf(3L), assertNotNull(waitFor(MessageType.SMS_NEW)).sms?.map { it.id })
    }

    @Test
    fun enablingNotificationsSendsCurrentOnesSilently() {
        val bridge = bridge()
        bridge.setEnabled(PhoneFeature.Notifications, true)
        val message = assertNotNull(waitFor(MessageType.NOTIFICATION_POSTED))
        assertEquals(true, message.notification?.silent)
    }

    @Test
    fun longListsAreSplitBelowFrameLimit() {
        val items = List(4_000) { ContactItem("$it", "Name".repeat(100), listOf("+62811")) }
        val pages = ArrayList<Pair<List<ContactItem>, Boolean>>()
        PhoneBridge.sendPages(items, ContactItem.serializer()) { page, more -> pages.add(page to more) }

        assertTrue(pages.size > 1)
        assertEquals(items, pages.flatMap { it.first })
        assertEquals(listOf(false), pages.map { it.second }.takeLast(1))
        assertTrue(pages.dropLast(1).all { it.second })
        pages.forEach { (page, more) ->
            val frame = protocolJson.encodeToString(Envelope.serializer(), Envelope(MessageType.CONTACTS, contacts = page, more = more))
            assertTrue(frame.encodeToByteArray().size < PhoneLinkProtocol.MAX_FRAME_BYTES)
        }
    }

    @Test
    fun emptyListStillAnswers() {
        val pages = ArrayList<List<ContactItem>>()
        PhoneBridge.sendPages(emptyList(), ContactItem.serializer()) { page, _ -> pages.add(page) }
        assertEquals(listOf(emptyList()), pages)
    }

    @Test
    fun browserCannotLeaveRoot() {
        File(folder, "luar.txt").writeText("rahasia")
        File(storage, "Dalam").mkdirs()
        File(storage, "Dalam/a.txt").writeText("a")
        File(storage, ".tersembunyi").writeText("x")
        Files.createSymbolicLink(File(storage, "tautan.txt").toPath(), File(folder, "luar.txt").toPath())
        Files.createSymbolicLink(File(storage, "tautan-folder").toPath(), folder.toPath())
        val browser = FsBrowser(storage)

        assertEquals(listOf("Dalam"), browser.list("").map { it.name })
        assertEquals(listOf("a.txt"), browser.list("Dalam").map { it.name })
        assertEquals(listOf(".tersembunyi", "Dalam"), browser.list("", showHidden = true).map { it.name }.sorted())
        assertEquals("a", browser.file("Dalam/a.txt").readText())
        assertEquals("a", browser.file("/Dalam/a.txt").readText())

        for (path in listOf("../luar.txt", "Dalam/../../luar.txt", "tautan.txt", "tautan-folder/luar.txt")) {
            assertFailsWith<java.io.IOException>(path) { browser.file(path) }
        }
        assertFailsWith<java.io.IOException> { browser.list("..") }
        assertFailsWith<java.io.IOException> { browser.list("tautan-folder") }
        assertFailsWith<java.io.IOException> { browser.file("Dalam") }
    }

    @Test
    fun callTrackerReportsEachCallKind() {
        fun run(vararg steps: Pair<String, String?>): List<CallEvent> {
            val tracker = CallTracker()
            return steps.mapNotNull { (state, number) -> tracker.update(state, number, 1) }
        }
        assertEquals(
            listOf("ringing" to "+62811", "missed" to "+62811"),
            run("RINGING" to "+62811", "IDLE" to null).map { it.state to it.number },
        )
        assertEquals(
            listOf("ringing", "answered", "ended"),
            run("RINGING" to "+62811", "OFFHOOK" to null, "IDLE" to null).map { it.state },
        )
        assertEquals(
            listOf("outgoing", "ended"),
            run("OFFHOOK" to null, "IDLE" to null).map { it.state },
        )
        // Android may send a broadcast without a number first, then one with it.
        assertEquals(
            listOf("ringing" to null, "ringing" to "+62811"),
            run("RINGING" to null, "RINGING" to "+62811").map { it.state to it.number },
        )
        assertEquals(1, run("RINGING" to "+62811", "RINGING" to null).size)
        assertNull(CallTracker().update("IDLE", null, 1))
    }

    @Test
    fun screenAndControlAreOffByDefault() {
        val bridge = bridge()
        bridge.handle("mac", Envelope(MessageType.SCREEN_REQUEST))
        bridge.handle("mac", Envelope(MessageType.CONTROL, control = ControlEvent("key", key = "home")))
        bridge.handle("mac", Envelope(MessageType.CONTROL, control = ControlEvent("text", text = "halo")))
        Thread.sleep(200)
        assertTrue(data.screenRequests.isEmpty())
        assertTrue(data.controls.isEmpty())
    }

    @Test
    fun enabledControlReachesPhoneInOrder() {
        val bridge = allEnabled()
        bridge.handle("mac", Envelope(MessageType.SCREEN_REQUEST))
        repeat(200) { index ->
            bridge.handle("mac", Envelope(MessageType.CONTROL, control = ControlEvent("text", text = "$index")))
        }
        assertEquals(List(200) { "$it" }, data.controls.map { it.text })
        val deadline = System.currentTimeMillis() + 3_000
        while (data.screenRequests.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(listOf("mac"), data.screenRequests.toList())
    }

    @Test
    fun revokingAccessibilityStopsControl() {
        val bridge = allEnabled()
        data.granted.remove(PhoneFeature.Control)
        bridge.handle("mac", Envelope(MessageType.CONTROL, control = ControlEvent("key", key = "home")))
        assertTrue(data.controls.isEmpty())
    }

    @Test
    fun controlRulesRejectBadInput() {
        assertNull(ControlRules.sanitize(ControlEvent("uninstall")))
        assertNull(ControlRules.sanitize(ControlEvent("key", key = "power")))
        assertNull(ControlRules.sanitize(ControlEvent("gesture")))
        assertNull(ControlRules.sanitize(ControlEvent("down")))
        assertNull(ControlRules.sanitize(ControlEvent("down", x = Float.NaN, y = 0f)))
        assertNull(ControlRules.sanitize(ControlEvent("drag", x = 0.5f, y = Float.POSITIVE_INFINITY)))
        assertNull(ControlRules.sanitize(ControlEvent("text", text = "")))

        val down = assertNotNull(ControlRules.sanitize(ControlEvent("down", x = 3f, y = -1f)))
        assertEquals(1f, down.x)
        assertEquals(0f, down.y)
        assertEquals(4_096, ControlRules.sanitize(ControlEvent("text", text = "x".repeat(9_000)))?.text?.length)
    }

    @Test
    fun hiddenFilesAreListedOnlyWhenAsked() {
        val bridge = allEnabled()
        File(storage, ".tersembunyi").writeText("x")
        File(storage, "biasa.txt").writeText("x")

        bridge.handle("mac", Envelope(MessageType.FS_LIST, path = ""))
        assertEquals(listOf("biasa.txt"), assertNotNull(waitFor(MessageType.FS_LISTING)).entries?.map { it.name })

        sent.clear()
        bridge.handle("mac", Envelope(MessageType.FS_LIST, path = "", hidden = true))
        val names = assertNotNull(waitFor(MessageType.FS_LISTING)).entries?.map { it.name }
        assertEquals(listOf(".tersembunyi", "biasa.txt"), names?.sorted())
    }

    @Test
    fun copyDetectorMatchesOnlyShortCopyLabels() {
        for (label in listOf("Copy", "COPY", " salin ", "Salin teks", "Copied to clipboard", "Disalin ke papan klip", "Copy link")) {
            assertTrue(CopyDetector.isCopyLabel(label), label)
        }
        for (label in listOf(null, "", "Paste", "Photocopy", "Salinan", "Bagikan")) {
            assertTrue(!CopyDetector.isCopyLabel(label), label.toString())
        }
        assertTrue(!CopyDetector.isCopyLabel("Please copy this long message to everyone in the team today"))
    }

    @Test
    fun strokeRunsAsOneContinuousTouch() {
        val queue = StrokeQueue()
        queue.down(0.1f, 0.1f, 1_000)
        val first = assertNotNull(queue.next(1_000))
        assertTrue(first.first && !first.last)
        assertEquals(listOf(0.1f to 0.1f), first.points)

        // Points that arrive while a stretch is running join the next one.
        queue.drag(0.2f, 0.2f)
        queue.drag(0.3f, 0.3f)
        assertNull(queue.next(1_005))
        queue.completed()
        val second = assertNotNull(queue.next(1_020))
        assertTrue(!second.first && !second.last)
        assertEquals(listOf(0.1f to 0.1f, 0.2f to 0.2f, 0.3f to 0.3f), second.points)
        assertEquals(20L, second.duration)

        // The finger stays down with nothing new to do, which allows a long press.
        queue.completed()
        assertNull(queue.next(1_500))

        queue.up(0.4f, 0.4f)
        val last = assertNotNull(queue.next(1_510))
        assertTrue(last.last && !last.first)
        assertEquals(listOf(0.3f to 0.3f, 0.4f to 0.4f), last.points)
        assertTrue(last.duration >= 30)
        queue.completed()
        assertNull(queue.next(1_600))
    }

    @Test
    fun cancelledStrokeIsReplayedOnRelease() {
        val queue = StrokeQueue()
        queue.down(0.1f, 0.1f, 1_000)
        assertNotNull(queue.next(1_000))
        queue.cancelled()
        queue.drag(0.2f, 0.2f)
        assertNull(queue.next(1_100))
        queue.up(0.3f, 0.3f)
        val whole = assertNotNull(queue.next(1_400))
        assertTrue(whole.first && whole.last)
        assertEquals(listOf(0.1f to 0.1f, 0.2f to 0.2f, 0.3f to 0.3f), whole.points)
        assertEquals(400L, whole.duration)
        assertNull(queue.next(1_500))
    }

    @Test
    fun releaseLiftsTheFingerWithoutReplaying() {
        val queue = StrokeQueue()
        queue.down(0.1f, 0.1f, 1_000)
        assertNotNull(queue.next(1_000))
        queue.completed()
        queue.release()
        val last = assertNotNull(queue.next(1_100))
        assertTrue(last.last)
        assertEquals(listOf(0.1f to 0.1f), last.points.distinct())

        val broken = StrokeQueue()
        broken.down(0.1f, 0.1f, 1_000)
        assertNotNull(broken.next(1_000))
        broken.cancelled()
        broken.release()
        assertNull(broken.next(1_100))
    }

    @Test
    fun newTouchChangesTheGeneration() {
        val queue = StrokeQueue()
        queue.down(0.1f, 0.1f, 1_000)
        val before = queue.generation
        queue.down(0.2f, 0.2f, 2_000)
        assertTrue(queue.generation != before)
        assertEquals(listOf(0.2f to 0.2f), assertNotNull(queue.next(2_000)).points)
    }
}
