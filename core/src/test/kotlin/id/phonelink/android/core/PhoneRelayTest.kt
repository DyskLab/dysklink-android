package id.phonelink.android.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Notifications and calls from one phone on another, push-to-talk, and sound: the rules, and all of it over TLS. */
class PhoneRelayTest {
    private val scopes = mutableListOf<CoroutineScope>()
    private val managers = mutableListOf<ConnectionManager>()

    @AfterTest
    fun close() {
        managers.forEach { it.shutdown() }
        scopes.forEach { it.cancel() }
    }

    // The rules

    @Test
    fun notificationActionsNeedTheSwitchAndAForwardedKey() {
        val off = PeerAccess()
        val on = PeerAccess(notify = true)
        PhoneRules.TO_SOURCE.forEach { type ->
            val refusedOrDropped = if (type == MessageType.NOTIFICATION_REPLY) PhoneRules.Verdict.Refuse else PhoneRules.Verdict.Drop
            assertEquals(refusedOrDropped, PhoneRules.toSource(type, off, forwarded = true), type)
            assertEquals(refusedOrDropped, PhoneRules.toSource(type, on, forwarded = false), type)
            assertEquals(PhoneRules.Verdict.Deliver, PhoneRules.toSource(type, on, forwarded = true), type)
        }
        // Mac-only messages are no part of it, even with every switch on.
        val all = PeerAccess(screen = true, control = true, camera = true, notify = true, calls = true, talk = true, sound = true)
        listOf(MessageType.SMS_LIST, MessageType.SMS_SEND, MessageType.CONTACTS_LIST, MessageType.FS_LIST, MessageType.INPUT).forEach {
            assertEquals(PhoneRules.Verdict.Drop, PhoneRules.toSource(it, all, true), it)
            assertEquals(PhoneRules.Verdict.Drop, PhoneRules.fromSpeaker(it, all, AudioKind.entries.toSet()), it)
            assertFalse(it in PhoneRules.ALL, it)
        }
        assertEquals("com.whatsapp", PhoneRules.keyPackage("0|com.whatsapp|12|tag|10123"))
        assertNull(PhoneRules.keyPackage("nothing"))
    }

    @Test
    fun voiceAndSoundAskFirstThenNeedTheSwitch() {
        val never = PeerAccess()
        val refused = PeerAccess(talk = false, sound = false)
        val allowed = PeerAccess(talk = true, sound = true)
        val none = emptySet<AudioKind>()
        val both = AudioKind.entries.toSet()
        for ((start, frame, stop) in listOf(
            Triple(MessageType.TALK_START, MessageType.TALK_FRAME, MessageType.TALK_STOP),
            Triple(MessageType.AUDIO_STARTED, MessageType.AUDIO_FRAME, MessageType.AUDIO_STOPPED),
        )) {
            assertEquals(PhoneRules.Verdict.Ask, PhoneRules.fromSpeaker(start, never, none), start)
            assertEquals(PhoneRules.Verdict.Refuse, PhoneRules.fromSpeaker(start, refused, none), start)
            assertEquals(PhoneRules.Verdict.Deliver, PhoneRules.fromSpeaker(start, allowed, none), start)
            assertEquals(PhoneRules.Verdict.Refuse, PhoneRules.fromSpeaker(start, allowed, none, busyWith = "other"), start)
            // Frames only once a start was taken, and only while still allowed.
            assertEquals(PhoneRules.Verdict.Drop, PhoneRules.fromSpeaker(frame, never, both), frame)
            assertEquals(PhoneRules.Verdict.Drop, PhoneRules.fromSpeaker(frame, refused, both), frame)
            assertEquals(PhoneRules.Verdict.Drop, PhoneRules.fromSpeaker(frame, allowed, none), frame)
            assertEquals(PhoneRules.Verdict.Deliver, PhoneRules.fromSpeaker(frame, allowed, both), frame)
            assertEquals(PhoneRules.Verdict.Drop, PhoneRules.fromSpeaker(stop, allowed, none), stop)
            assertEquals(PhoneRules.Verdict.Deliver, PhoneRules.fromSpeaker(stop, refused, both), stop)
        }
        // The talk switch doesn't open sound, and the other way round.
        assertEquals(PhoneRules.Verdict.Ask, PhoneRules.fromSpeaker(MessageType.AUDIO_STARTED, PeerAccess(talk = true), none))
        assertEquals(PhoneRules.Verdict.Drop, PhoneRules.fromSpeaker(MessageType.TALK_FRAME, PeerAccess(sound = true), both))
        assertEquals(MessageType.TALK_STOPPED, PhoneRules.audioRefusal(AudioKind.Talk, "x").type)
        assertEquals(MessageType.AUDIO_STOP, PhoneRules.audioRefusal(AudioKind.Sound, "x").type)
    }

    @Test
    fun oldPhonesHideTheNewButtons() {
        fun peer(platform: String, caps: List<String>) = Peer("x", "X", platform, "m", isPaired = true, isConnected = true, info = null, caps = caps)
        val now = peer("android", PhoneLinkProtocol.PHONE_CAPS)
        assertTrue(now.canNotify && now.canTalk && now.canPlaySound)
        // 1.3 listed only the screen and the camera.
        val v13 = peer("android", listOf(PhoneLinkProtocol.CAP_BINARY, PhoneLinkProtocol.CAP_P2P_SCREEN, PhoneLinkProtocol.CAP_P2P_CAMERA))
        assertTrue(v13.canViewScreen)
        assertFalse(v13.canNotify || v13.canTalk || v13.canPlaySound)
        assertFalse(peer("android", emptyList()).canTalk)
        assertFalse(peer("macos", PhoneLinkProtocol.PHONE_CAPS).canNotify)
        assertEquals(listOf(PhoneLinkProtocol.CAP_BINARY), PhoneLinkProtocol.CAPS)
    }

    @Test
    fun theNewSwitchesAreKept() {
        val dir = Files.createTempDirectory("dysk-relay-access").toFile()
        PhoneAccess(dir).update("a") { it.copy(notify = true, calls = true, muted = listOf("com.bank"), talk = true, sound = false) }
        assertEquals(PeerAccess(notify = true, calls = true, muted = listOf("com.bank"), talk = true, sound = false), PhoneAccess(dir).of("a"))
    }

    @Test
    fun theMirrorFollowsTheSource() {
        val mirror = NotificationMirror()
        fun item(key: String) = NotificationItem(key, "App", "t", "x", 1)
        mirror.handle("b", Envelope(MessageType.NOTIFICATION_POSTED, notification = item("1")))
        mirror.handle("b", Envelope(MessageType.NOTIFICATION_POSTED, notification = item("2")))
        mirror.handle("b", Envelope(MessageType.NOTIFICATION_POSTED, notification = item("1").copy(text = "new")))
        assertEquals(listOf("1", "2"), mirror.feeds.value["b"]!!.notifications.map { it.key }, "Updated ones come first, once")
        assertEquals("new", mirror.feeds.value["b"]!!.notifications.first().text)
        mirror.handle("b", Envelope(MessageType.NOTIFICATION_REMOVED, key = "2"))
        assertEquals(listOf("1"), mirror.feeds.value["b"]!!.notifications.map { it.key })
        mirror.handle("b", Envelope(MessageType.CALL, call = CallEvent("ringing", "123", 1)))
        mirror.handle("b", Envelope(MessageType.NOTIFICATION_CLEARED))
        assertEquals("ringing", mirror.feeds.value["b"]?.call?.state)
        mirror.clear("b")
        assertTrue(mirror.feeds.value.isEmpty())
    }

    // Over TLS

    private inner class Phone(name: String, caps: List<String>? = PhoneLinkProtocol.PHONE_CAPS, platform: String = "android") {
        val dir: File = Files.createTempDirectory("dysk-$name").toFile()
        val active = CopyOnWriteArrayList<NotificationItem>()
        val dismissed = CopyOnWriteArrayList<String>()
        val read = CopyOnWriteArrayList<String>()
        val replies = CopyOnWriteArrayList<Pair<String, String>>()
        val heard = CopyOnWriteArrayList<Envelope>()
        val sound = CopyOnWriteArrayList<Envelope>()
        val asked = CopyOnWriteArrayList<AudioKind>()
        val results = CopyOnWriteArrayList<Envelope>()
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
            fileSinks = { _, _ ->
                val out = ByteArrayOutputStream()
                object : FileSink {
                    override fun write(bytes: ByteArray) = out.write(bytes)
                    override fun commit(): String = ""
                    override fun abort() {}
                }
            },
            phoneData = object : PhoneData {
                override val storageRoot = dir
                override fun isGranted(feature: PhoneFeature) = true
                override fun activeNotifications() = active.toList()
                override fun dismissNotification(key: String) { dismissed += key }
                override fun replyNotification(key: String, text: String): String? { replies += key to text; return null }
                override fun markNotificationRead(key: String): Boolean { read += key; return true }
                override fun readSms(limit: Int, afterId: Long) = emptyList<SmsItem>()
                override fun sendSms(address: String, body: String, done: (String?) -> Unit) = done("no")
                override fun readContacts() = emptyList<ContactItem>()
                override fun requestScreen(peerId: String) {}
                override fun syncScreen(peerId: String) {}
                override fun stopScreen(peerId: String) {}
                override fun control(event: ControlEvent) {}
            },
        ).also { managers += it }.also { cm ->
            cm.phoneAudioSink = { _, envelope -> heard += envelope }
            cm.audioSink = { _, envelope -> sound += envelope }
            cm.phoneAskSink = { _, kind -> asked += kind }
            scope.launch { cm.mirror.results.collect { results += it.second } }
        }
        val id get() = manager.localDeviceId
        fun peer(other: Phone) = manager.peers.value.firstOrNull { it.id == other.id }
        fun feed(from: Phone) = manager.mirror.feeds.value[from.id]
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

    private fun item(key: String, pkg: String = "com.chat", reply: Boolean = true) =
        NotificationItem(key = key, app = "Chat", title = "Ana", text = "Hi", time = 1, pkg = pkg, reply = reply.takeIf { it })

    @Test
    fun notificationsAndCallsGoOnlyWhereTheSwitchIsOn() {
        val shower = Phone("Shower")
        val source = Phone("Source")
        pair(shower, source)
        waitUntil("No caps") { shower.peer(source)?.canNotify == true && source.peer(shower)?.canNotify == true }
        source.active += item("0|com.chat|1|null|1")

        // Off: nothing goes, and a dismiss or reply from the other phone does nothing.
        source.manager.phone.notificationPosted(item("0|com.chat|2|null|1"))
        source.manager.phone.callStateChanged("RINGING", "555")
        shower.manager.dismissMirrored(source.id, "0|com.chat|1|null|1")
        shower.manager.replyMirrored(source.id, "0|com.chat|1|null|1", "hello")
        waitUntil("Reply not refused") { shower.results.any { it.error != null } }
        settle(source, shower)
        assertNull(shower.feed(source))
        assertTrue(source.dismissed.isEmpty() && source.replies.isEmpty())
        source.manager.phone.callStateChanged("IDLE", null)

        // On: what shows now arrives quietly, then new ones as they come.
        source.manager.setPhoneAccess(shower.id) { it.copy(notify = true, calls = true, muted = listOf("com.bank")) }
        waitUntil("Active ones didn't arrive") { shower.feed(source)?.notifications?.size == 1 }
        assertEquals(true, shower.feed(source)!!.notifications.single().silent)
        source.manager.phone.notificationPosted(item("0|com.chat|3|null|1"))
        source.manager.phone.notificationPosted(item("0|com.bank|4|null|1", pkg = "com.bank"))
        waitUntil("New one didn't arrive") { shower.feed(source)?.notifications?.size == 2 }
        settle(source, shower)
        assertFalse(shower.feed(source)!!.notifications.any { it.pkg == "com.bank" }, "A muted app stays here")
        assertEquals(true, shower.feed(source)!!.notifications.first().reply)

        // Removed there, removed here.
        source.manager.phone.notificationRemoved("0|com.chat|3|null|1")
        waitUntil("Not removed") { shower.feed(source)?.notifications?.map { it.key } == listOf("0|com.chat|1|null|1") }

        // Dismiss, reply, and read from here act there, only for notifications it sent.
        shower.results.clear()
        shower.manager.replyMirrored(source.id, "0|com.chat|1|null|1", "On my way")
        waitUntil("No reply result") { shower.results.isNotEmpty() }
        assertNull(shower.results.single().error)
        assertEquals("0|com.chat|1|null|1" to "On my way", source.replies.single())
        shower.manager.markMirroredRead(source.id, "0|com.chat|1|null|1")
        waitUntil("Not read") { source.read.isNotEmpty() }
        shower.manager.dismissMirrored(source.id, "0|com.bank|4|null|1")
        shower.manager.dismissMirrored(source.id, "0|com.chat|1|null|1")
        waitUntil("Not dismissed") { source.dismissed.isNotEmpty() }
        settle(shower, source)
        assertEquals(listOf("0|com.chat|1|null|1"), source.dismissed, "Only what it was sent")
        assertNull(shower.feed(source)?.notifications?.firstOrNull { it.key == "0|com.chat|1|null|1" })

        // Calls.
        source.manager.phone.callStateChanged("RINGING", "555")
        waitUntil("No call") { shower.feed(source)?.call?.state == "ringing" }
        assertEquals("555", shower.feed(source)!!.call!!.number)
        source.manager.phone.callStateChanged("IDLE", null)
        waitUntil("Call didn't end") { shower.feed(source)?.call?.state == "missed" }

        // Off again: everything goes away here.
        source.manager.phone.notificationPosted(item("0|com.chat|5|null|1"))
        waitUntil("Not sent") { shower.feed(source)?.notifications?.isNotEmpty() == true }
        source.manager.setPhoneAccess(shower.id) { it.copy(notify = false) }
        waitUntil("Not cleared") { shower.feed(source)?.notifications.isNullOrEmpty() }

        // A dropped connection takes the rest with it.
        source.manager.phone.callStateChanged("RINGING", "777")
        waitUntil("No second call") { shower.feed(source)?.call?.number == "777" }
        source.manager.shutdown()
        waitUntil("Still shown") { shower.feed(source) == null }
    }

    @Test
    fun aMacStillGetsWhatItsFeatureGives() {
        val phone = Phone("Phone")
        val mac = Phone("Mac", caps = PhoneLinkProtocol.CAPS, platform = "macos")
        pair(phone, mac)
        phone.manager.phone.setEnabled(PhoneFeature.Notifications, true)
        // No per-phone switch is involved: the Mac's notifications come from the feature switch alone.
        phone.manager.phone.notificationPosted(item("0|com.chat|9|null|1"))
        waitUntil("Mac didn't get it") { mac.feed(phone)?.notifications?.size == 1 }
        mac.manager.sendDirect(phone.id, Envelope(MessageType.NOTIFICATION_DISMISS, key = "0|com.chat|9|null|1"))
        waitUntil("Mac dismiss lost") { phone.dismissed.contains("0|com.chat|9|null|1") }
        // Phone-only replies aren't for a Mac.
        mac.manager.sendDirect(phone.id, Envelope(MessageType.NOTIFICATION_REPLY, key = "0|com.chat|9|null|1", text = "x"))
        mac.manager.sendDirect(phone.id, Envelope(MessageType.TALK_START, audioRate = 16_000, audioChannels = 1))
        settle(mac, phone)
        assertTrue(phone.replies.isEmpty())
        assertTrue(phone.heard.isEmpty() && phone.asked.isEmpty())
    }

    @Test
    fun pushToTalk() {
        val talker = Phone("Talker")
        val listener = Phone("Listener")
        val third = Phone("Third")
        pair(talker, listener)
        pair(third, listener)
        waitUntil("No caps") { talker.peer(listener)?.canTalk == true }

        // Never asked: refused with why, the listener asks its user, and the voice is dropped.
        assertTrue(talker.manager.startTalk(listener.id, 16_000, 1))
        talker.manager.sendTalk(listener.id, ByteArray(1280))
        waitUntil("Not refused") { talker.heard.any { it.type == MessageType.TALK_STOPPED && it.error != null } }
        waitUntil("Not asked") { listener.asked.contains(AudioKind.Talk) }
        settle(talker, listener)
        assertTrue(listener.heard.isEmpty())

        // Allowed: the voice plays there, whole and binary, and it ends on talk.stop.
        listener.manager.setPhoneAccess(talker.id) { it.copy(talk = true) }
        waitUntil("Not saved") { listener.manager.phoneAccess.of(talker.id).talk == true }
        talker.heard.clear()
        talker.manager.startTalk(listener.id, 16_000, 1)
        repeat(5) { n -> talker.manager.sendTalk(listener.id, ByteArray(1280) { n.toByte() }) }
        waitUntil("No voice") { listener.heard.count { it.type == MessageType.TALK_FRAME } == 5 }
        assertEquals(16_000, listener.heard.first().audioRate)
        assertTrue(listener.heard.filter { it.type == MessageType.TALK_FRAME }.all { it.raw?.size == 1280 })
        assertEquals(setOf(AudioKind.Talk), listener.manager.hearingFrom(talker.id))

        // One talker at a time.
        listener.manager.setPhoneAccess(third.id) { it.copy(talk = true) }
        waitUntil("Not saved") { listener.manager.phoneAccess.of(third.id).talk == true }
        third.manager.startTalk(listener.id, 16_000, 1)
        waitUntil("Third wasn't refused") { third.heard.any { it.type == MessageType.TALK_STOPPED && it.error?.contains("Talker") == true } }

        talker.manager.stopTalk(listener.id)
        waitUntil("No stop") { listener.heard.any { it.type == MessageType.TALK_STOP } }
        assertTrue(listener.manager.hearingFrom(talker.id).isEmpty())
        val before = listener.heard.size
        talker.manager.sendTalk(listener.id, ByteArray(1280))
        settle(talker, listener)
        assertEquals(before, listener.heard.size, "Voice after the stop is dropped")

        // Stopped from the listening phone.
        talker.heard.clear()
        talker.manager.startTalk(listener.id, 16_000, 1)
        waitUntil("Not hearing") { listener.manager.hearingFrom(talker.id).isNotEmpty() }
        listener.manager.stopHearing(talker.id, AudioKind.Talk)
        waitUntil("Talker not told") { talker.heard.any { it.type == MessageType.TALK_STOPPED } }

        // The switch off ends it too; a lost connection ends it on both sides.
        talker.manager.startTalk(listener.id, 16_000, 1)
        waitUntil("Not hearing") { listener.manager.hearingFrom(talker.id).isNotEmpty() }
        listener.heard.clear()
        talker.manager.shutdown()
        waitUntil("Listener still playing") { listener.heard.any { it.type == MessageType.TALK_STOP } }
        assertTrue(listener.manager.hearingFrom(talker.id).isEmpty())
    }

    @Test
    fun soundToAnotherPhone() {
        val sender = Phone("Sender")
        val player = Phone("Player")
        pair(sender, player)
        waitUntil("No caps") { sender.peer(player)?.canPlaySound == true }
        val started = Envelope(MessageType.AUDIO_STARTED, audioRate = 48_000, audioChannels = 2)

        // Never asked: audio.stop with why goes back to the sending phone's sound code.
        sender.manager.sendDirect(player.id, started)
        sender.manager.sendDirect(player.id, Envelope(MessageType.AUDIO_FRAME, raw = ByteArray(7680)))
        waitUntil("Not refused") { sender.sound.any { it.type == MessageType.AUDIO_STOP && it.error != null } }
        waitUntil("Not asked") { player.asked.contains(AudioKind.Sound) }
        settle(sender, player)
        assertTrue(player.heard.isEmpty())

        // "Never": refused without asking again.
        player.manager.setPhoneAccess(sender.id) { it.copy(sound = false) }
        waitUntil("Not saved") { player.manager.phoneAccess.of(sender.id).sound == false }
        sender.sound.clear()
        sender.manager.sendDirect(player.id, started)
        waitUntil("Not refused") { sender.sound.any { it.type == MessageType.AUDIO_STOP } }
        assertEquals(1, player.asked.size)

        // Allowed: it plays, and turning the switch off stops both sides.
        player.manager.setPhoneAccess(sender.id) { it.copy(sound = true) }
        waitUntil("Not saved") { player.manager.phoneAccess.of(sender.id).sound == true }
        sender.sound.clear()
        sender.manager.sendDirect(player.id, started)
        repeat(3) { sender.manager.sendDirect(player.id, Envelope(MessageType.AUDIO_FRAME, raw = ByteArray(7680))) }
        waitUntil("No sound") { player.heard.count { it.type == MessageType.AUDIO_FRAME } == 3 }
        assertTrue(player.heard.filter { it.type == MessageType.AUDIO_FRAME }.all { it.payload()?.size == 7680 })
        player.manager.setPhoneAccess(sender.id) { it.copy(sound = false) }
        waitUntil("Sender not stopped") { sender.sound.any { it.type == MessageType.AUDIO_STOP } }
        waitUntil("Player still playing") { player.heard.any { it.type == MessageType.AUDIO_STOPPED } }

        // Stopped by the sender, then a lost connection ends it here.
        player.manager.setPhoneAccess(sender.id) { it.copy(sound = true) }
        waitUntil("Not saved") { player.manager.phoneAccess.of(sender.id).sound == true }
        player.heard.clear()
        sender.manager.sendDirect(player.id, started)
        waitUntil("Not playing") { player.manager.hearingFrom(sender.id) == setOf(AudioKind.Sound) }
        sender.manager.sendDirect(player.id, Envelope(MessageType.AUDIO_STOPPED))
        waitUntil("Not ended") { player.manager.hearingFrom(sender.id).isEmpty() }
        sender.manager.sendDirect(player.id, started)
        waitUntil("Not playing") { player.manager.hearingFrom(sender.id).isNotEmpty() }
        player.heard.clear()
        sender.manager.shutdown()
        waitUntil("Still playing") { player.heard.any { it.type == MessageType.AUDIO_STOPPED } }

        // Unpairing forgets every switch.
        player.manager.unpair(sender.id)
        waitUntil("Still allowed") { player.manager.phoneAccess.of(sender.id) == PeerAccess() }
    }
}
