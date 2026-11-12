package id.phonelink.android.core

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
import kotlinx.coroutines.launch

/** SMS through another phone: the switch, the rate limit, B's log, A taking only what it asked for, and over TLS. */
class PhoneSmsTest {
    private val scopes = mutableListOf<CoroutineScope>()
    private val managers = mutableListOf<ConnectionManager>()

    @AfterTest
    fun close() {
        managers.forEach { it.shutdown() }
        scopes.forEach { it.cancel() }
    }

    private class FakeSms(dir: File, var granted: Boolean = true) : PhoneData {
        override val storageRoot = dir
        val inbox = CopyOnWriteArrayList<SmsItem>()
        val sent = CopyOnWriteArrayList<Pair<String, String>>()
        @Volatile var carrierError: String? = null
        override fun isGranted(feature: PhoneFeature) = granted
        override fun activeNotifications() = emptyList<NotificationItem>()
        override fun dismissNotification(key: String) {}
        override fun readSms(limit: Int, afterId: Long) = inbox.filter { it.id > afterId }.sortedByDescending { it.id }.take(limit)
        override fun sendSms(address: String, body: String, done: (String?) -> Unit) {
            sent += address to body
            done(carrierError)
        }
        override fun readContacts() = emptyList<ContactItem>()
        override fun requestScreen(peerId: String) {}
        override fun syncScreen(peerId: String) {}
        override fun stopScreen(peerId: String) {}
        override fun control(event: ControlEvent) {}
    }

    private fun sms(id: Long, thread: Long, address: String, body: String, outgoing: Boolean = false) =
        SmsItem(id, thread, address, body, time = id * 1000, outgoing = outgoing)

    // The rules

    @Test
    fun onlyTheSmsSwitchLetsThemThrough() {
        val old = PeerAccess(screen = true, control = true, camera = true, notify = true, calls = true, talk = true, sound = true,
            links = true, media = true, usage = true, quiet = true, locate = true, move = true, backup = true)
        PhoneRules.FROM_SMS_READER.forEach { type ->
            assertTrue(type in PhoneRules.ALL, type)
            if (type == MessageType.PSMS_UNWATCH) return@forEach
            assertEquals(PhoneRules.Verdict.Ask, PhoneRules.fromSmsReader(type, old), type)
            assertEquals(PhoneRules.Verdict.Refuse, PhoneRules.fromSmsReader(type, old.copy(sms = false)), type)
            assertEquals(PhoneRules.Verdict.Deliver, PhoneRules.fromSmsReader(type, PeerAccess(sms = true)), type)
            assertNotNull(PhoneRules.smsAnswer(type), type)
        }
        // Stopping only ever stops.
        assertEquals(PhoneRules.Verdict.Deliver, PhoneRules.fromSmsReader(MessageType.PSMS_UNWATCH, PeerAccess(sms = false)))
        PhoneRules.TO_SMS_READER.forEach { assertTrue(it in PhoneRules.ALL, it) }
        // The Mac's SMS messages stay Mac-only.
        listOf(MessageType.SMS_LIST, MessageType.SMS_SEND, MessageType.SMS_NEW, MessageType.SMS_MESSAGES, MessageType.SMS_RESULT).forEach {
            assertFalse(it in PhoneRules.ALL, it)
            assertFalse(it in ConnectionManager.PHONE_MESSAGES, it)
            assertEquals(PhoneRules.Verdict.Drop, PhoneRules.fromSmsReader(it, PeerAccess(sms = true)), it)
        }
    }

    @Test
    fun theRateLimitCountsEachPhoneForAMinute() {
        var now = 0L
        val limit = SendRateLimit(clock = { now })
        repeat(SendRateLimit.MAX_PER_MINUTE) { assertTrue(limit.take("a"), "Send $it") }
        assertFalse(limit.take("a"), "The eleventh in a minute")
        assertTrue(limit.take("b"), "Another phone has its own")
        now = 30_000
        assertFalse(limit.take("a"))
        assertEquals(30_000, limit.waitMs("a"))
        now = 60_001
        assertTrue(limit.take("a"), "A minute later")
        assertEquals(0, limit.waitMs("b"))
    }

    @Test
    fun bLogsEverySendAndShowsIt() {
        val dir = Files.createTempDirectory("dysk-sms").toFile()
        val access = PhoneAccess(dir)
        access.update("a") { it.copy(sms = true) }
        val data = FakeSms(dir)
        val sentOut = CopyOnWriteArrayList<Envelope>()
        val shown = CopyOnWriteArrayList<String>()
        var now = 1_000L
        val share = SmsShare(access, data, SmsSendLog(dir), send = { _, e -> sentOut += e }, localName = { "Phone" }, limiter = SendRateLimit(clock = { now }), clock = { now })
        share.onSent = { _, name, number -> shown += "$name sent an SMS to $number" }

        fun send(id: String, address: String, body: String = "Hi") =
            share.handle("a", "Tablet", Envelope(MessageType.PSMS_SEND, smsSend = SmsSend(id, address, body)))

        send("1", "+62 812-3456")
        assertEquals(listOf("+62 812-3456" to "Hi"), data.sent.toList())
        assertEquals(listOf("Tablet sent an SMS to +62 812-3456"), shown.toList())
        assertEquals(true, sentOut.last().smsResult?.ok)
        assertEquals("1", sentOut.last().smsResult?.id)
        val entry = share.log.entries.value.single()
        assertEquals("a", entry.peerId)
        assertTrue(entry.ok)

        // Codes and USSD can't be sent; nothing goes out.
        send("2", "*123#")
        send("3", "12345", body = "")
        assertEquals(1, data.sent.size)
        assertEquals("Invalid recipient number", sentOut[1].smsResult?.error)

        // The carrier refused: logged, not shown as sent.
        data.carrierError = "Carrier rejected the SMS"
        send("4", "12345")
        assertFalse(share.log.entries.value.first().ok)
        assertEquals(1, shown.size)
        data.carrierError = null

        // Ten a minute: the rest are refused and logged, and never reach the carrier.
        repeat(8) { send("r$it", "12345") }
        assertEquals(10, data.sent.size)
        send("over", "12345")
        assertEquals(10, data.sent.size)
        assertTrue(sentOut.last().smsResult!!.error!!.contains("at most 10 a minute"))
        assertTrue(share.log.entries.value.first().error!!.contains("at most 10"))
        now += 60_001
        send("later", "12345")
        assertEquals(11, data.sent.size)

        // The log survives a restart.
        assertEquals(share.log.entries.value, SmsSendLog(dir).entries.value)

        // Without Android's SMS permission, nothing is read or sent.
        data.granted = false
        send("np", "12345")
        assertEquals(11, data.sent.size)
        share.handle("a", "Tablet", Envelope(MessageType.PSMS_LIST, key = "k"))
        assertTrue(sentOut.last().error!!.contains("SMS permission"))
    }

    @Test
    fun newSmsGoOnlyToWatchersStillAllowed() {
        val dir = Files.createTempDirectory("dysk-sms").toFile()
        val access = PhoneAccess(dir)
        access.update("a") { it.copy(sms = true) }
        val data = FakeSms(dir)
        data.inbox += sms(1, 7, "111", "old")
        val out = CopyOnWriteArrayList<Pair<String, Envelope>>()
        val share = SmsShare(access, data, SmsSendLog(null), send = { p, e -> out += p to e }, localName = { "Phone" })
        data.inbox += sms(2, 7, "111", "before watching")
        share.changed()
        assertTrue(out.isEmpty(), "Nobody watches")
        share.handle("a", "Tablet", Envelope(MessageType.PSMS_WATCH, key = "w"))
        assertEquals(MessageType.PSMS_WATCHING, out.single().second.type)
        data.inbox += sms(3, 8, "222", "fresh")
        share.changed()
        assertEquals(listOf("fresh"), out.last().second.sms!!.map { it.body })
        // The switch off: told, and no more.
        val (_, after) = access.update("a") { it.copy(sms = false) }
        share.accessChanged("a", after)
        assertEquals(MessageType.PSMS_WATCHING, out.last().second.type)
        assertNotNull(out.last().second.error)
        val count = out.size
        data.inbox += sms(4, 8, "222", "secret")
        share.changed()
        assertEquals(count, out.size)
    }

    @Test
    fun aTakesOnlyWhatItAskedFor() {
        var now = 0L
        val remote = SmsRemote(null) { now }
        val list = remote.list("b")
        // Wrong key, or another phone: dropped.
        assertFalse(remote.handle("b", Envelope(MessageType.PSMS_LISTING, key = "nope", sms = listOf(sms(1, 1, "1", "x")))))
        assertFalse(remote.handle("c", Envelope(MessageType.PSMS_LISTING, key = list.key, sms = listOf(sms(1, 1, "1", "x")))))
        assertTrue(remote.handle("b", Envelope(MessageType.PSMS_LISTING, key = list.key, sms = listOf(sms(1, 1, "1", "x")))))
        assertFalse(remote.handle("b", Envelope(MessageType.PSMS_LISTING, key = list.key)), "Once")
        assertEquals(1, remote.views.value["b"]!!.threads!!.size)

        // A send result needs an SMS sent from here.
        assertNull(remote.send("b", "*100#", "x"), "Not a number")
        val out = remote.send("b", "0812 345", "Hello")!!
        assertFalse(remote.handle("b", Envelope(MessageType.PSMS_RESULT, smsResult = SmsResult("forged", true))))
        assertTrue(remote.handle("b", Envelope(MessageType.PSMS_RESULT, smsResult = SmsResult(out.smsSend!!.id, true))))

        // New SMS only while watching.
        assertFalse(remote.handle("b", Envelope(MessageType.PSMS_NEW, sms = listOf(sms(5, 2, "2", "pushed")))))
        val watch = remote.watch("b")
        assertTrue(remote.handle("b", Envelope(MessageType.PSMS_WATCHING, key = watch.key)))
        assertTrue(remote.isWatching("b"))
        assertTrue(remote.handle("b", Envelope(MessageType.PSMS_NEW, sms = listOf(sms(5, 2, "2", "pushed")))))
        assertEquals(listOf(5L, 1L), remote.views.value["b"]!!.threads!!.map { it.id })
        // B stops it without a key; it only ever stops.
        assertTrue(remote.handle("b", Envelope(MessageType.PSMS_WATCHING, error = "SMS from B is turned off there.")))
        assertFalse(remote.isWatching("b"))
        assertFalse(remote.handle("b", Envelope(MessageType.PSMS_WATCHING)), "A keyless yes doesn't start it")
        assertFalse(remote.isWatching("b"))

        // Too late.
        val late = remote.list("b")
        now += SmsRemote.ANSWER_MS + 1
        assertFalse(remote.handle("b", Envelope(MessageType.PSMS_LISTING, key = late.key)))
        // Disconnected: nothing is kept.
        remote.disconnected("b")
        assertNull(remote.views.value["b"])
    }

    @Test
    fun theSessionNeedsTheScreenLockAgain() {
        var now = 0L
        val session = SmsSession(idleMs = 1_000) { now }
        assertFalse(session.isUnlocked)
        session.unlock()
        assertTrue(session.isUnlocked)
        now = 900
        session.touch()
        now = 1_800
        assertTrue(session.isUnlocked, "Used, so it lasts")
        now = 3_000
        assertFalse(session.isUnlocked, "Idle too long")
        session.touch()
        assertFalse(session.isUnlocked, "Touching doesn't reopen it")
        session.unlock()
        session.lock()
        assertFalse(session.isUnlocked)
    }

    @Test
    fun threadsAreTheNewestOfEach() {
        val all = listOf(sms(1, 1, "a", "1"), sms(2, 2, "b", "2"), sms(3, 1, "a", "3"))
        assertEquals(listOf(3L, 2L), SmsThreads.latest(all).map { it.id })
        val big = (1..5_000L).map { sms(it, it, "1", "x".repeat(400)) }
        val fit = SmsThreads.fit(big)
        assertTrue(fit.size in 100 until 5_000)
        assertTrue(fit.sumOf { protocolJson.encodeToString(SmsItem.serializer(), it).length } <= PhoneBridge.PAGE_BYTES)
    }

    // Over TLS

    private inner class Phone(name: String, caps: List<String>? = PhoneLinkProtocol.PHONE_CAPS, platform: String = "android") {
        val dir: File = Files.createTempDirectory("dysk-$name").toFile()
        val data = FakeSms(dir)
        val asked = CopyOnWriteArrayList<PeerAsk>()
        val shown = CopyOnWriteArrayList<String>()
        val incoming = CopyOnWriteArrayList<SmsItem>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val manager = ConnectionManager(
            dir, scope,
            localHello = { paired -> Hello(name, platform, "Test $name", PhoneLinkProtocol.VERSION, paired, caps = caps) },
            localInfo = { DeviceInfo() },
            isUiVisible = { false },
            actions = object : DeviceActions {
                override fun setClipboard(text: String) {}
                override fun media(action: String) {}
            },
            fileSinks = { _, _ -> error("unused") },
            phoneData = data,
        ).also { managers += it }.also { cm ->
            cm.phoneRequestAskSink = { _, kind -> asked += kind }
            cm.smsShare.onSent = { _, peer, number -> shown += "$peer:$number" }
            scope.launch { cm.smsRemote.incoming.collect { incoming += it.second } }
        }
        val id get() = manager.localDeviceId
        fun peer(other: Phone) = manager.peers.value.firstOrNull { it.id == other.id }
        fun view(of: Phone) = manager.smsRemote.views.value[of.id]
    }

    private fun waitUntil(message: String, seconds: Int = 10, check: () -> Boolean) {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError(message)
            Thread.sleep(20)
        }
    }

    private fun settle(from: Phone, to: Phone) {
        from.manager.sendDirect(to.id, Envelope(MessageType.PING))
        Thread.sleep(300)
    }

    private fun pair(a: Phone, b: Phone, phones: Boolean = true) {
        b.manager.startServer()
        waitUntil("Not listening") { b.manager.serverPort.value != null }
        a.manager.addManual("127.0.0.1", b.manager.serverPort.value!!)
        waitUntil("They didn't connect") { a.peer(b)?.isConnected == true && b.peer(a)?.isConnected == true }
        a.manager.requestPairing(b.id)
        waitUntil("No code") { b.manager.prompt.value?.showsCode == true && a.manager.prompt.value != null }
        a.manager.submitCode(b.manager.prompt.value!!.code)
        waitUntil("Didn't pair") { a.peer(b)?.isPaired == true && b.peer(a)?.isPaired == true }
        if (phones) waitUntil("No caps") { a.peer(b)?.canSms == true && b.peer(a)?.canSms == true }
    }

    @Test
    fun smsThroughAnotherPhone() {
        val tablet = Phone("Tablet")
        val phone = Phone("Phone")
        phone.data.inbox += listOf(sms(1, 10, "111", "Hi"), sms(2, 10, "111", "Hello", outgoing = true), sms(3, 20, "222", "Code 1234"))
        pair(tablet, phone)

        // Never asked: refused with why, and the phone asks its user. Nothing is read or sent.
        assertTrue(tablet.manager.listPhoneSms(phone.id))
        waitUntil("Not refused") { tablet.view(phone)?.error?.contains("Waiting for Phone") == true }
        assertNotNull(tablet.manager.sendPhoneSms(phone.id, "12345", "Hi"))
        waitUntil("Send not refused") { tablet.view(phone)?.results?.values?.any { !it.ok } == true }
        waitUntil("Not asked") { PeerAsk.Sms in phone.asked }
        assertTrue(phone.data.sent.isEmpty())

        phone.manager.setPhoneAccess(tablet.id) { it.copy(sms = true) }
        waitUntil("Not saved") { phone.manager.phoneAccess.of(tablet.id).sms == true }

        tablet.manager.listPhoneSms(phone.id)
        waitUntil("No threads") { tablet.view(phone)?.threads?.size == 2 }
        assertEquals(listOf(3L, 2L), tablet.view(phone)!!.threads!!.map { it.id })
        tablet.manager.listPhoneSms(phone.id, thread = 10)
        waitUntil("No thread") { tablet.view(phone)?.threadId == 10L }
        assertEquals(listOf("Hi", "Hello"), tablet.view(phone)!!.messages.map { it.body })

        assertNotNull(tablet.manager.sendPhoneSms(phone.id, "0812 3456", "From the tablet"))
        waitUntil("Not sent") { phone.data.sent.isNotEmpty() }
        assertEquals("0812 3456" to "From the tablet", phone.data.sent.single())
        waitUntil("No result") { tablet.view(phone)?.results?.values?.any { it.ok } == true }
        assertEquals(listOf("Tablet:0812 3456"), phone.shown.toList())
        assertEquals(tablet.id, phone.manager.smsLog.entries.value.first { it.ok }.peerId)

        // Pushed without watching: dropped.
        phone.manager.sendDirect(tablet.id, Envelope(MessageType.PSMS_NEW, sms = listOf(sms(99, 9, "9", "forged"))))
        settle(phone, tablet)
        assertTrue(tablet.incoming.isEmpty())

        // Watching: a new SMS comes live.
        tablet.manager.followPhoneSms(phone.id, true)
        waitUntil("Not watching") { tablet.view(phone)?.watching == true }
        phone.data.inbox += sms(4, 20, "222", "New one")
        phone.manager.smsShare.changed()
        waitUntil("No new SMS") { tablet.incoming.any { it.body == "New one" } }
        assertEquals(setOf(phone.id), tablet.manager.smsRemote.follow.value)

        // The switch off stops it at once.
        phone.manager.setPhoneAccess(tablet.id) { it.copy(sms = false) }
        waitUntil("Still watching") { tablet.view(phone)?.watching == false }
        phone.data.inbox += sms(5, 20, "222", "After")
        phone.manager.smsShare.changed()
        settle(phone, tablet)
        assertFalse(tablet.incoming.any { it.body == "After" })

        // Unpairing forgets the switch, and the follow on the other side.
        phone.manager.unpair(tablet.id)
        waitUntil("Still allowed") { phone.manager.phoneAccess.of(tablet.id) == PeerAccess() }
        waitUntil("Still following") { tablet.manager.smsRemote.follow.value.isEmpty() }
    }

    @Test
    fun aMacNeverReadsSmsThisWay() {
        val phone = Phone("Phone")
        val mac = Phone("Mac", caps = PhoneLinkProtocol.CAPS, platform = "macos")
        phone.data.inbox += sms(1, 1, "111", "Secret")
        pair(phone, mac, phones = false)
        phone.manager.setPhoneAccess(mac.id) { it.copy(sms = true) }
        assertFalse(phone.manager.listPhoneSms(mac.id), "Not sent to a computer")
        listOf(
            Envelope(MessageType.PSMS_LIST, key = "k"), Envelope(MessageType.PSMS_WATCH, key = "w"),
            Envelope(MessageType.PSMS_SEND, smsSend = SmsSend("1", "12345", "x")),
        ).forEach { mac.manager.sendDirect(phone.id, it) }
        settle(mac, phone)
        assertTrue(phone.data.sent.isEmpty() && phone.asked.isEmpty() && phone.manager.smsShare.watchers.value.isEmpty())
    }

    @Test
    fun oldPhonesHideTheNewFeatures() {
        fun peer(platform: String, caps: List<String>) = Peer("x", "X", platform, "m", isPaired = true, isConnected = true, info = null, caps = caps)
        val now = peer("android", PhoneLinkProtocol.PHONE_CAPS)
        assertTrue(now.canSms && now.canMove && now.canBackup)
        val v15 = peer("android", PhoneLinkProtocol.PHONE_CAPS - setOf(PhoneLinkProtocol.CAP_P2P_SMS, PhoneLinkProtocol.CAP_P2P_MOVE, PhoneLinkProtocol.CAP_P2P_BACKUP))
        assertTrue(v15.canLocate)
        assertFalse(v15.canSms || v15.canMove || v15.canBackup)
        assertFalse(peer("macos", PhoneLinkProtocol.PHONE_CAPS).canSms, "Computers never")
        assertFalse(peer("android", emptyList()).canBackup)
    }
}
