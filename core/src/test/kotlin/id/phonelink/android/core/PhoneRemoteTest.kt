package id.phonelink.android.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneId
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

/** Links, the music remote, the family monitor, quiet mode, and Locate between phones: the rules, and over TLS. */
class PhoneRemoteTest {
    private val scopes = mutableListOf<CoroutineScope>()
    private val managers = mutableListOf<ConnectionManager>()

    @AfterTest
    fun close() {
        managers.forEach { it.shutdown() }
        scopes.forEach { it.cancel() }
    }

    // The rules

    @Test
    fun eachRequestNeedsItsOwnSwitch() {
        val never = PeerAccess()
        val all = PeerAccess(links = true, media = true, usage = true, quiet = true, locate = true, move = true)
        val refused = PeerAccess(links = false, media = false, usage = false, quiet = false, locate = false, move = false)
        // SMS and photo backup have their own messages; every other kind is a request with one answer.
        assertEquals(PeerAsk.entries.toSet() - PeerAsk.Sms - PeerAsk.Backup, PhoneRules.FROM_CONTROLLER.values.toSet())
        PhoneRules.FROM_CONTROLLER.forEach { (type, kind) ->
            assertTrue(type in PhoneRules.ALL, type)
            assertEquals(PhoneRules.Verdict.Ask, PhoneRules.fromController(type, never), type)
            assertEquals(PhoneRules.Verdict.Refuse, PhoneRules.fromController(type, refused), type)
            assertEquals(PhoneRules.Verdict.Deliver, PhoneRules.fromController(type, all), type)
            // Only its own switch: every other one on is not enough.
            val others = PhoneRules.withAllowed(all, kind, null)
            assertEquals(PhoneRules.Verdict.Ask, PhoneRules.fromController(type, others), type)
            assertEquals(PhoneRules.ANSWER.getValue(kind), PhoneRules.requestRefusal(kind, "B", PhoneRules.Verdict.Refuse).type)
        }
        // Old switches don't open the new ones, and Mac-only messages stay out.
        val old = PeerAccess(screen = true, control = true, camera = true, notify = true, calls = true, talk = true, sound = true)
        PhoneRules.FROM_CONTROLLER.keys.forEach { assertEquals(PhoneRules.Verdict.Ask, PhoneRules.fromController(it, old), it) }
        listOf(MessageType.MEDIA, MessageType.USAGE_LIST, MessageType.SMS_SEND, MessageType.FS_LIST, MessageType.INPUT, MessageType.MAC_ACTION).forEach {
            assertEquals(PhoneRules.Verdict.Drop, PhoneRules.fromController(it, all), it)
            assertFalse(it in PhoneRules.ALL, it)
            assertFalse(it in ConnectionManager.PHONE_MESSAGES, it)
        }
        val asked = PhoneRules.requestRefusal(PeerAsk.Locate, "Kid", PhoneRules.Verdict.Ask, key = "k")
        assertEquals(MessageType.LOCATE_RESULT, asked.type)
        assertEquals("k", asked.key)
        assertTrue(asked.error!!.contains("Kid"))
    }

    @Test
    fun onlyAllowedLinksPass() {
        val accepted = mapOf(
            "https://example.com/a?b=c#d" to SafeLink.Kind.Web,
            "HTTP://Example.com" to SafeLink.Kind.Web,
            "  http://example.com:8080/x  " to SafeLink.Kind.Web,
            "https://maps.app.goo.gl/abc" to SafeLink.Kind.Map,
            "https://www.google.com/maps/place/Monas" to SafeLink.Kind.Map,
            "geo:-6.1754,106.8272" to SafeLink.Kind.Map,
            "geo:0,0?q=coffee+shop" to SafeLink.Kind.Map,
            "GEO:51.5,-0.12;u=35" to SafeLink.Kind.Map,
            "tel:+62 812-3456-7890" to SafeLink.Kind.Phone,
            "+1 (555) 010-9999" to SafeLink.Kind.Phone,
            "112" to SafeLink.Kind.Phone,
        )
        accepted.forEach { (text, kind) -> assertEquals(kind, SafeLink.parse(text)?.kind, text) }
        assertEquals("tel:+6281234567890", SafeLink.parse("tel:+62 812-3456-7890")!!.uri)
        assertEquals("http://Example.com", SafeLink.parse("HTTP://Example.com")!!.uri)

        val rejected = listOf(
            "intent://scan/#Intent;scheme=zxing;package=com.x;end", "intent:#Intent;action=android.settings.SETTINGS;end",
            "file:///sdcard/secret.txt", "content://com.android.contacts/contacts", "javascript:alert(1)",
            "JavaScript:alert(1)", "data:text/html,<script>x</script>", "sms:+123", "mailto:a@b.c", "market://details?id=x",
            "ftp://example.com", "https://bank.com@evil.example/", "https://", "https:///path", "http:example.com",
            "https://exa mple.com", "https://example.com/\u0000", "geo:91,0", "geo:0,181", "geo:abc", "geo:1e5,2",
            "tel:*#06#", "tel:*21*123#", "12", "+1+2345", "", "   ", "hello world", "x".repeat(SafeLink.MAX_CHARS + 1),
            "about:blank", "chrome://settings", "tel:1234;phone-context=x",
        )
        rejected.forEach { assertNull(SafeLink.parse(it), it) }

        // Shared text: the first allowed link in it.
        assertEquals("https://example.com/x", SafeLink.find("Look at this (https://example.com/x).")?.uri)
        assertEquals("geo:1,2", SafeLink.find("Meet at geo:1,2 later")?.uri)
        assertNull(SafeLink.find("Open intent://x#Intent;end or javascript:alert(1)"))
        assertNull(SafeLink.find("Nothing to open here"))
    }

    @Test
    fun batteryAlertsComeOncePerCycle() {
        val alerts = BatteryAlerts()
        val on = MonitorSettings(lowAlert = true, threshold = 15, fullAlert = true)
        fun at(percent: Int, charging: Boolean, settings: MonitorSettings = on) = alerts.check("b", percent, charging, settings)
        assertNull(at(40, false))
        assertEquals(BatteryAlert.Low, at(15, false))
        assertNull(at(14, false), "Once")
        assertNull(at(16, false), "Wobbling around the threshold doesn't alert again")
        assertNull(at(15, false))
        assertNull(at(15, true), "Plugged in, still at the threshold: not a new cycle yet")
        assertNull(at(14, false))
        assertNull(at(18, true))
        assertEquals(BatteryAlert.Low, at(15, false), "Charged over it, ran down again")
        // Full: once while charging, again only after unplugging.
        assertNull(at(99, true))
        assertEquals(BatteryAlert.Full, at(100, true))
        assertNull(at(100, true))
        assertNull(at(100, false))
        assertEquals(BatteryAlert.Full, at(100, true))
        // Off: nothing, and the threshold stays between 10 and 30.
        assertNull(alerts.check("c", 5, false, MonitorSettings()))
        assertEquals(BatteryAlert.Low, alerts.check("d", 30, false, MonitorSettings(lowAlert = true, threshold = 80)))
        assertNull(alerts.check("e", 11, false, MonitorSettings(lowAlert = true, threshold = 3)))
        assertNull(alerts.check("f", null, false, on))
        val dir = Files.createTempDirectory("dysk-monitor").toFile()
        PhoneMonitor(dir).set("b") { it.copy(lowAlert = true, threshold = 50) }
        assertEquals(MonitorSettings(lowAlert = true, threshold = 30), PhoneMonitor(dir).of("b"))
    }

    @Test
    fun quietEndsOnTime() {
        val zone = ZoneId.of("Asia/Jakarta")
        fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
        val morning = QuietRequest(QuietMath.DND_ON, untilMorning = true)
        assertEquals(at("2026-11-13T07:00"), QuietMath.until(at("2026-11-12T23:30"), morning, zone), "Across midnight")
        assertEquals(at("2026-11-13T07:00"), QuietMath.until(at("2026-11-13T00:10"), morning, zone), "Just after midnight")
        assertEquals(at("2026-11-13T07:00"), QuietMath.until(at("2026-11-13T06:59"), morning, zone))
        assertEquals(at("2026-11-14T07:00"), QuietMath.until(at("2026-11-13T07:00"), morning, zone), "At 07:00 it's tomorrow's")
        assertEquals(at("2026-12-01T07:00"), QuietMath.until(at("2026-11-30T22:00"), morning, zone), "Across the month")
        // Daylight saving: 07:00 local, not 9 hours later.
        val berlin = ZoneId.of("Europe/Berlin")
        val before = LocalDateTime.parse("2026-03-28T22:00").atZone(berlin).toInstant().toEpochMilli()
        assertEquals(LocalDateTime.parse("2026-03-29T07:00").atZone(berlin).toInstant().toEpochMilli(), QuietMath.nextMorning(before, berlin))
        val now = at("2026-11-12T10:00")
        assertEquals(now + 30 * 60_000L, QuietMath.until(now, QuietRequest(QuietMath.SILENT, minutes = 30), zone))
        assertEquals(now + 60 * 60_000L, QuietMath.until(now, QuietRequest(QuietMath.VIBRATE, minutes = 60), zone))
        assertNull(QuietMath.until(now, QuietRequest(QuietMath.SILENT), zone), "No end")
        assertNull(QuietMath.until(now, QuietRequest(QuietMath.NORMAL, minutes = 30), zone), "Normal is where it ends")
        assertNull(QuietMath.until(now, QuietRequest(QuietMath.DND_OFF, untilMorning = true), zone))
        assertFalse(QuietMath.valid(QuietRequest("loud")))
        assertFalse(QuietMath.valid(QuietRequest(QuietMath.SILENT, minutes = 0)))
        assertFalse(QuietMath.valid(QuietRequest(QuietMath.SILENT, minutes = 100_000)))
        assertTrue(QuietMath.valid(QuietRequest(QuietMath.DND_ON, minutes = 60)))
    }

    @Test
    fun answersSayWhyWhenRefusedOrUnavailable() {
        val remote = FakeRemote()
        var usage: UsageReport? = null
        val data = fakeData(Files.createTempDirectory("dysk-answers").toFile()) { usage }
        val answers = RemoteAnswers(data, { remote }, { "Kid" }, clock = { 1_000L }, zone = { ZoneId.of("UTC") })
        remote.fix = null
        val none = answers.answer("a", "Mom", Envelope(MessageType.LOCATE_REQUEST))!!
        assertEquals(MessageType.LOCATE_RESULT, none.type)
        assertNull(none.location)
        assertEquals("Location unavailable while DyskLink is in the background on Kid.", none.error)
        remote.fix = LocationFix(1.5, 2.5, 10f, 99)
        assertEquals(1.5, answers.answer("a", "Mom", Envelope(MessageType.LOCATE_REQUEST))!!.location?.lat)
        assertNull(RemoteAnswers(data, { null }, { "Kid" }).answer("a", "Mom", Envelope(MessageType.LOCATE_REQUEST))!!.location)

        assertTrue(answers.answer("a", "Mom", Envelope(MessageType.MONITOR_REQUEST))!!.error!!.contains("Usage access"))
        usage = UsageReport(0, 60_000, emptyList())
        assertEquals(60_000, answers.answer("a", "Mom", Envelope(MessageType.MONITOR_REQUEST))!!.usage?.totalMs)

        // The receiving phone checks the link again, whatever the sender did.
        val bad = answers.answer("a", "Mom", Envelope(MessageType.LINK_OPEN, text = "intent://x#Intent;end", key = "1"))!!
        assertEquals("1", bad.key)
        assertNotNull(bad.error)
        assertTrue(remote.links.isEmpty())
        assertNull(answers.answer("a", "Mom", Envelope(MessageType.LINK_OPEN, text = "tel:555 1234"))!!.error)
        assertEquals(listOf("tel:5551234"), remote.links.map { it.uri })

        assertNotNull(answers.answer("a", "Mom", Envelope(MessageType.MEDIA_REMOTE, media = "rm -rf"))!!.error)
        assertNotNull(answers.answer("a", "Mom", Envelope(MessageType.QUIET_SET, quiet = QuietRequest("loud")))!!.error)
        val quiet = answers.answer("a", "Mom", Envelope(MessageType.QUIET_SET, quiet = QuietRequest(QuietMath.SILENT, minutes = 30)))!!
        assertEquals(1_000L + 30 * 60_000L, quiet.quiet?.until)
        assertNull(answers.answer("a", "Mom", Envelope(MessageType.MEDIA)), "Not a remote request")
    }

    @Test
    fun answersAreOnlyTakenWhenAsked() {
        var now = 0L
        val awaited = AwaitedReplies { now }
        assertFalse(awaited.take("b", PeerAsk.Locate), "Unasked")
        awaited.expect("b", PeerAsk.Locate)
        assertFalse(awaited.take("b", PeerAsk.Media), "Another kind")
        assertFalse(awaited.take("c", PeerAsk.Locate), "Another phone")
        assertTrue(awaited.take("b", PeerAsk.Locate))
        assertFalse(awaited.take("b", PeerAsk.Locate), "Once")
        awaited.expect("b", PeerAsk.Media)
        now += AwaitedReplies.timeoutMs(PeerAsk.Media) + 1
        assertFalse(awaited.take("b", PeerAsk.Media), "Too late")
    }

    @Test
    fun oldPhonesHideTheRemote() {
        fun peer(platform: String, caps: List<String>) = Peer("x", "X", platform, "m", isPaired = true, isConnected = true, info = null, caps = caps)
        val now = peer("android", PhoneLinkProtocol.PHONE_CAPS)
        assertTrue(now.canOpen && now.canMedia && now.canMonitor && now.canQuiet && now.canLocate)
        val v14 = peer("android", listOf(
            PhoneLinkProtocol.CAP_BINARY, PhoneLinkProtocol.CAP_P2P_SCREEN, PhoneLinkProtocol.CAP_P2P_CAMERA,
            PhoneLinkProtocol.CAP_P2P_NOTIFY, PhoneLinkProtocol.CAP_P2P_TALK, PhoneLinkProtocol.CAP_P2P_SOUND,
        ))
        assertTrue(v14.canNotify)
        assertFalse(v14.canOpen || v14.canMedia || v14.canMonitor || v14.canQuiet || v14.canLocate)
        assertFalse(peer("macos", PhoneLinkProtocol.PHONE_CAPS).canLocate, "Computers never")
        assertEquals(listOf(PhoneLinkProtocol.CAP_BINARY), PhoneLinkProtocol.CAPS)
    }

    // Over TLS

    private class FakeRemote : PhoneRemote {
        val links = CopyOnWriteArrayList<SafeLink.Link>()
        val actions = CopyOnWriteArrayList<String>()
        val quiets = CopyOnWriteArrayList<QuietRequest>()
        @Volatile var fix: LocationFix? = null
        @Volatile var playing = false

        override fun offerLink(peerId: String, peerName: String, link: SafeLink.Link) { links += link }
        override fun media(action: String): NowPlaying {
            actions += action
            if (action == "play_pause") playing = !playing
            return NowPlaying("Song", "Band", "Player", playing = playing, volume = 5, maxVolume = 15)
        }
        override fun quiet(peerId: String, peerName: String, request: QuietRequest): QuietRequest = request.also { quiets += it }
        override fun locate(): LocationFix = fix ?: throw RemoteRefused("Location unavailable while DyskLink is in the background on Kid.")
    }

    private fun fakeData(dir: File, usage: () -> UsageReport? = { null }) = object : PhoneData {
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
        override fun screenTime(): UsageReport? = usage()
    }

    private inner class Phone(name: String, caps: List<String>? = PhoneLinkProtocol.PHONE_CAPS, platform: String = "android") {
        val dir: File = Files.createTempDirectory("dysk-$name").toFile()
        val remote = FakeRemote()
        val asked = CopyOnWriteArrayList<PeerAsk>()
        val rings = CopyOnWriteArrayList<Boolean>()
        val alerts = CopyOnWriteArrayList<BatteryAlert>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        val manager = ConnectionManager(
            dir, scope,
            localHello = { paired -> Hello(name, platform, "Test $name", PhoneLinkProtocol.VERSION, paired, caps = caps) },
            localInfo = { DeviceInfo(batteryPercent = 50) },
            isUiVisible = { false },
            actions = object : DeviceActions {
                override fun setClipboard(text: String) {}
                override fun media(action: String) {}
                override fun ring(from: String, flash: Boolean) { rings += flash }
            },
            fileSinks = { _, _ ->
                val out = ByteArrayOutputStream()
                object : FileSink {
                    override fun write(bytes: ByteArray) = out.write(bytes)
                    override fun commit(): String = ""
                    override fun abort() {}
                }
            },
            phoneData = fakeData(dir) { UsageReport(0, 3_600_000, listOf(UsageApp("com.game", "Game", 3_000_000)), unlocks = 9) },
        ).also { managers += it }.also { cm ->
            cm.phoneRemote = remote
            cm.phoneRequestAskSink = { _, kind -> asked += kind }
            scope.launch { cm.batteryAlerts.collect { alerts += it.second } }
        }
        val id get() = manager.localDeviceId
        fun peer(other: Phone) = manager.peers.value.firstOrNull { it.id == other.id }
        fun reply(from: Phone, kind: PeerAsk) = manager.remoteReplies.value[from.id]?.get(kind)
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
        if (phones) waitUntil("No caps") { a.peer(b)?.canLocate == true && b.peer(a)?.canLocate == true }
    }

    @Test
    fun theMusicRemoteRoundTrip() {
        val mom = Phone("Mom")
        val kid = Phone("Kid")
        pair(mom, kid)

        // Never asked: refused with why, and the kid's phone asks its user. Nothing pressed.
        assertTrue(mom.manager.phoneMedia(kid.id, "play_pause"))
        waitUntil("Not refused") { mom.reply(kid, PeerAsk.Media)?.error != null }
        waitUntil("Not asked") { PeerAsk.Media in kid.asked }
        assertTrue(kid.remote.actions.isEmpty())

        // Allowed: each button acts there and what plays comes back.
        kid.manager.setPhoneAccess(mom.id) { it.copy(media = true) }
        waitUntil("Not saved") { kid.manager.phoneAccess.of(mom.id).media == true }
        mom.manager.phoneMedia(kid.id, "play_pause")
        waitUntil("No state") { mom.reply(kid, PeerAsk.Media)?.nowPlaying?.playing == true }
        assertEquals("Song", mom.reply(kid, PeerAsk.Media)!!.nowPlaying!!.title)
        listOf("next", "previous", "volume_up", "volume_down", "state").forEach { mom.manager.phoneMedia(kid.id, it) }
        waitUntil("Buttons lost") { kid.remote.actions.size == 6 }
        assertEquals(listOf("play_pause", "next", "previous", "volume_up", "volume_down", "state"), kid.remote.actions.toList())
        assertFalse(mom.manager.phoneMedia(kid.id, "shutdown"), "Unknown buttons aren't sent")

        // The Mac's `media` message does nothing between phones.
        mom.manager.sendMedia(kid.id, "next")
        settle(mom, kid)
        assertEquals(6, kid.remote.actions.size)

        // Turned off: refused again.
        kid.manager.setPhoneAccess(mom.id) { it.copy(media = false) }
        waitUntil("Not saved") { kid.manager.phoneAccess.of(mom.id).media == false }
        mom.manager.phoneMedia(kid.id, "next")
        waitUntil("Not refused") { mom.reply(kid, PeerAsk.Media)?.error?.contains("turned off") == true }
        assertEquals(6, kid.remote.actions.size)
    }

    @Test
    fun linksQuietScreenTimeAndLocate() {
        val mom = Phone("Mom")
        val kid = Phone("Kid")
        pair(mom, kid)

        // Nothing happens without the switch.
        assertFalse(mom.manager.sendLink(kid.id, "javascript:alert(1)"), "Not even sent")
        assertTrue(mom.manager.sendLink(kid.id, "See https://example.com/a"))
        mom.manager.setPhoneQuiet(kid.id, QuietRequest(QuietMath.DND_ON, untilMorning = true))
        mom.manager.requestScreenTime(kid.id)
        mom.manager.locatePhone(kid.id)
        waitUntil("Not all refused") { listOf(PeerAsk.Links, PeerAsk.Quiet, PeerAsk.Usage, PeerAsk.Locate).all { mom.reply(kid, it)?.error != null } }
        waitUntil("Not asked") { kid.asked.toSet() == setOf(PeerAsk.Links, PeerAsk.Quiet, PeerAsk.Usage, PeerAsk.Locate) }
        assertTrue(kid.remote.links.isEmpty() && kid.remote.quiets.isEmpty())

        kid.manager.setPhoneAccess(mom.id) { it.copy(links = true, quiet = true, usage = true, locate = false) }
        waitUntil("Not saved") { kid.manager.phoneAccess.of(mom.id).links == true }

        mom.manager.sendLink(kid.id, "See https://example.com/a")
        waitUntil("No link") { kid.remote.links.isNotEmpty() }
        assertEquals("https://example.com/a", kid.remote.links.single().uri)
        waitUntil("No answer") { mom.reply(kid, PeerAsk.Links)?.error == null }

        mom.manager.setPhoneQuiet(kid.id, QuietRequest(QuietMath.DND_ON, untilMorning = true))
        waitUntil("Not quiet") { kid.remote.quiets.isNotEmpty() }
        val until = kid.remote.quiets.single().until!!
        assertEquals(QuietMath.nextMorning(System.currentTimeMillis(), ZoneId.systemDefault()), until)
        waitUntil("No quiet answer") { mom.reply(kid, PeerAsk.Quiet)?.quiet?.until == until }

        mom.manager.requestScreenTime(kid.id)
        waitUntil("No screen time") { mom.reply(kid, PeerAsk.Usage)?.usage?.totalMs == 3_600_000L }
        assertEquals(9, mom.reply(kid, PeerAsk.Usage)!!.usage!!.unlocks)

        // Refused: the location never leaves, and no question pops up again.
        val askedBefore = kid.asked.size
        mom.manager.locatePhone(kid.id)
        waitUntil("Not refused") { mom.reply(kid, PeerAsk.Locate)?.error?.contains("turned off on Kid") == true }
        assertEquals(askedBefore, kid.asked.size)
        // Allowed but unavailable: why, and still no location.
        kid.manager.setPhoneAccess(mom.id) { it.copy(locate = true) }
        waitUntil("Not saved") { kid.manager.phoneAccess.of(mom.id).locate == true }
        mom.manager.locatePhone(kid.id)
        waitUntil("No answer") { mom.reply(kid, PeerAsk.Locate)?.error?.contains("background") == true }
        assertNull(mom.reply(kid, PeerAsk.Locate)!!.location)
        kid.remote.fix = LocationFix(-6.2, 106.8, 12f, 1_000)
        mom.manager.locatePhone(kid.id)
        waitUntil("No location") { mom.reply(kid, PeerAsk.Locate)?.location != null }
        assertEquals(-6.2, mom.reply(kid, PeerAsk.Locate)!!.location!!.lat)

        // An answer nobody asked for is dropped.
        kid.manager.sendDirect(mom.id, Envelope(MessageType.LOCATE_RESULT, location = LocationFix(0.0, 0.0, time = 5)))
        kid.manager.sendDirect(mom.id, Envelope(MessageType.MEDIA_STATE, nowPlaying = NowPlaying("Pushed")))
        settle(kid, mom)
        assertEquals(-6.2, mom.reply(kid, PeerAsk.Locate)!!.location!!.lat)
        assertNull(mom.reply(kid, PeerAsk.Media))

        // Ring + flash: the flag reaches the ringing phone.
        mom.manager.ringMac(kid.id, on = true, flash = true)
        mom.manager.ringMac(kid.id, on = true)
        waitUntil("No ring") { kid.rings.size == 2 }
        assertEquals(listOf(true, false), kid.rings.toList())

        // Battery alerts on Mom from Kid's info, once.
        mom.manager.setMonitor(kid.id) { it.copy(lowAlert = true, threshold = 20) }
        repeat(3) { kid.manager.sendDirect(mom.id, Envelope(MessageType.INFO, info = DeviceInfo(batteryPercent = 18, isCharging = false))) }
        waitUntil("No alert") { mom.alerts.isNotEmpty() }
        settle(kid, mom)
        assertEquals(listOf(BatteryAlert.Low), mom.alerts.toList())

        // Unpairing forgets the switches and the alerts.
        kid.manager.unpair(mom.id)
        waitUntil("Still allowed") { kid.manager.phoneAccess.of(mom.id) == PeerAccess() }
        waitUntil("Alerts kept") { mom.manager.monitor.of(kid.id) == MonitorSettings() }
    }

    @Test
    fun aMacGetsNoneOfIt() {
        val phone = Phone("Phone")
        val mac = Phone("Mac", caps = PhoneLinkProtocol.CAPS, platform = "macos")
        pair(phone, mac, phones = false)
        mac.manager.setPhoneAccess(phone.id) { it.copy(links = true, media = true, usage = true, quiet = true, locate = true) }
        // Requests to a computer aren't sent; the same requests from a computer never reach the phone's remote.
        assertFalse(phone.manager.locatePhone(mac.id))
        assertFalse(phone.manager.sendLink(mac.id, "https://example.com"))
        listOf(
            Envelope(MessageType.LOCATE_REQUEST), Envelope(MessageType.MEDIA_REMOTE, media = "next"),
            Envelope(MessageType.LINK_OPEN, text = "https://example.com"), Envelope(MessageType.QUIET_SET, quiet = QuietRequest(QuietMath.SILENT)),
            Envelope(MessageType.MONITOR_REQUEST),
        ).forEach { mac.manager.sendDirect(phone.id, it) }
        settle(mac, phone)
        assertTrue(phone.asked.isEmpty() && phone.remote.links.isEmpty() && phone.remote.actions.isEmpty() && phone.remote.quiets.isEmpty())
    }
}
