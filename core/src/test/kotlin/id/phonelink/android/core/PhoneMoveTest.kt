package id.phonelink.android.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
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

/** Moving to a new phone and photo backup to a phone: the wizard's state, the contacts file, the rules, and over TLS. */
class PhoneMoveTest {
    private val scopes = mutableListOf<CoroutineScope>()
    private val managers = mutableListOf<ConnectionManager>()

    @AfterTest
    fun close() {
        managers.forEach { it.shutdown() }
        scopes.forEach { it.cancel() }
    }

    private fun item(id: String, size: Long = 100, kind: MoveKind = MoveKind.Media) = MoveItem(id, kind, "$id.jpg", size, "src:$id")

    // The wizard

    @Test
    fun theMoveGoesOnWhereItStopped() {
        val dir = Files.createTempDirectory("dysk-move").toFile()
        var now = 0L
        val job = MoveJob(File(dir, "move.json")) { now }
        job.start("new", "New phone", listOf(item("a"), item("b"), item("c"), item("a")))
        assertEquals(MovePhase.Asking, job.plan.value!!.phase)
        assertEquals(3, job.plan.value!!.items.size, "Each item once")
        assertNull(job.next(), "Nothing goes before the new phone says yes")

        assertTrue(job.accepted())
        assertEquals("a", job.next()!!.id)
        now = 1_000
        job.finished("a", ok = true)
        assertEquals("b", job.next()!!.id)

        // The connection drops in the middle of b: not counted as a try, and nothing is sent while waiting.
        assertTrue(job.disconnected())
        job.finished("b", ok = false, error = "Connection lost", lost = true)
        assertEquals(MovePhase.Waiting, job.plan.value!!.phase)
        assertEquals(0, job.plan.value!!.items.first { it.id == "b" }.tries)
        assertNull(job.next())
        assertFalse(job.accepted(), "Only after asking again")
        assertTrue(job.connected())
        assertEquals(MovePhase.Asking, job.plan.value!!.phase)
        assertTrue(job.accepted())
        assertEquals("b", job.next()!!.id, "It goes on where it stopped")

        // Paused: the transfer it cancels doesn't count either.
        assertTrue(job.pause())
        job.finished("b", ok = false, error = "Paused")
        assertEquals(0, job.plan.value!!.items.first { it.id == "b" }.tries)
        assertFalse(job.connected(), "A connection doesn't resume a pause")
        assertEquals(MovePhase.Paused, job.plan.value!!.phase)

        // The app restarts: the plan is still there, and an interrupted run waits for the connection.
        val again = MoveJob(File(dir, "move.json")) { now }
        assertEquals(MovePhase.Paused, again.plan.value!!.phase)
        assertTrue(again.resume())
        assertTrue(again.accepted())
        val restarted = MoveJob(File(dir, "move.json")) { now }
        assertEquals(MovePhase.Waiting, restarted.plan.value!!.phase)
        assertEquals(MoveItemStatus.Sent, restarted.plan.value!!.items.first { it.id == "a" }.status)

        // Refused by the new phone: paused with why, and resumed by hand.
        restarted.connected()
        assertTrue(restarted.refused("Moving to this phone is turned off on New phone."))
        assertEquals(MovePhase.Paused, restarted.plan.value!!.phase)
        assertNotNull(restarted.plan.value!!.error)
        restarted.resume()
        restarted.accepted()
        assertNull(restarted.plan.value!!.error)

        // A file that keeps failing is left out after three tries; the rest goes on.
        repeat(MoveJob.MAX_TRIES) { restarted.finished("b", ok = false, error = "Can't read b.jpg") }
        assertEquals(MoveItemStatus.Failed, restarted.plan.value!!.items.first { it.id == "b" }.status)
        assertEquals("c", restarted.next()!!.id)
        restarted.finished("c", ok = true, skipped = true)
        assertEquals(MovePhase.Done, restarted.plan.value!!.phase)

        val summary = restarted.summary(restarted.plan.value!!, done = true)
        assertEquals(1, summary.sent)
        assertEquals(1, summary.skipped)
        assertEquals(1, summary.failed)
        restarted.clear()
        assertNull(MoveJob(File(dir, "move.json")).plan.value)
    }

    @Test
    fun progressAndEta() {
        var now = 0L
        val job = MoveJob(null) { now }
        job.start("n", "N", listOf(item("a", 1_000), item("b", 3_000)))
        assertNull(job.progress()!!.etaMs, "Nothing measured yet")
        job.accepted()
        now = 2_000
        job.finished("a", ok = true)
        val progress = job.progress()!!
        assertEquals(4_000, progress.totalBytes)
        assertEquals(1_000, progress.doneBytes)
        // 1000 bytes took 2 s, so 3000 take 6 s.
        assertEquals(6_000, progress.etaMs)
        job.pause()
        now = 100_000
        assertEquals(6_000, job.progress()!!.etaMs, "Paused time doesn't count")
    }

    // The contacts file

    @Test
    fun contactsSurviveTheVcf() {
        val contacts = listOf(
            VCardContact("Budi Santoso", phones = listOf("+62 812-3456-7890", "021 555"), emails = listOf("budi@example.com")),
            VCardContact("O'Neil; Smith, Jr.\\", phones = listOf("1"), organization = "Acme, Inc.", note = "Line one\nLine two"),
            VCardContact("Ñandú 日本語 " + "Long name ".repeat(12), phones = listOf("5")),
            VCardContact("", phones = listOf("555 0100")),
        )
        val text = VCard.write(contacts)
        assertTrue(text.startsWith("BEGIN:VCARD\r\nVERSION:3.0\r\n"))
        // No line is longer than 75 bytes before its line break.
        text.split("\r\n").forEach { assertTrue(it.toByteArray().size <= 75, it) }
        val back = VCard.parse(text)
        assertEquals(contacts.size, back.size)
        assertEquals(contacts.take(3), back.take(3))
        assertEquals("555 0100", back[3].name, "A contact without a name is named by its number")
        assertTrue(VCard.write(listOf(VCardContact(""))).isEmpty(), "Nothing to keep")
    }

    // The rules

    @Test
    fun filesFromAPhoneNeedTheirSwitch() {
        val none = PeerAccess(screen = true, sms = true, links = true)
        assertEquals(PhoneRules.Verdict.Deliver, PhoneRules.fileFromPhone(null, none), "Plain files as before")
        assertEquals(PhoneRules.Verdict.Deliver, PhoneRules.fileFromPhone("camera", none))
        assertEquals(PhoneRules.Verdict.Ask, PhoneRules.fileFromPhone(PhoneKinds.PHOTO, none))
        PhoneKinds.MOVE.forEach { kind ->
            assertEquals(PhoneRules.Verdict.Ask, PhoneRules.fileFromPhone(kind, none.copy(backup = true)), kind)
            assertEquals(PhoneRules.Verdict.Refuse, PhoneRules.fileFromPhone(kind, PeerAccess(move = false)), kind)
            assertEquals(PhoneRules.Verdict.Deliver, PhoneRules.fileFromPhone(kind, PeerAccess(move = true)), kind)
        }
        assertEquals(PhoneRules.Verdict.Ask, PhoneRules.fileFromPhone(PhoneKinds.PHOTO, PeerAccess(move = true)), "Moving isn't backup")
        assertEquals(PhoneRules.Verdict.Deliver, PhoneRules.fileFromPhone(PhoneKinds.PHOTO, PeerAccess(backup = true)))
        assertEquals(PhoneRules.Verdict.Ask, PhoneRules.fromController(MessageType.MOVE_REQUEST, PeerAccess(backup = true)))
        assertTrue(MessageType.MOVE_DONE in PhoneRules.ALL)

        assertTrue(StorageGuard.enough(2L shl 30, 100))
        assertFalse(StorageGuard.enough(StorageGuard.MIN_FREE_BYTES + 99, 100))
        assertEquals("DCIM/2026", FileNames.folder("../DCIM//2026/"))
        assertEquals("a/b", FileNames.folder("..\\a\\.\\b"))
        assertNull(FileNames.folder("../.."))
        assertEquals(6, FileNames.folder((1..10).joinToString("/"))!!.split('/').size)
    }

    @Test
    fun theReceivedIndexRemembersCopies() {
        val dir = Files.createTempDirectory("dysk-index").toFile()
        val index = ReceivedIndex(dir)
        assertFalse(index.has("a", null, "x.jpg", 5, "AB"))
        index.add("a", null, "x.jpg", 5, "ab")
        assertTrue(index.has("a", null, "x.jpg", 5, "AB"))
        assertFalse(index.has("a", null, "x.jpg", 6, "ab"), "Another size")
        assertFalse(index.has("a", null, "x.jpg", 5, "cd"), "Other bytes")
        assertFalse(index.has("a", "Docs", "x.jpg", 5, "ab"), "Another folder")
        assertFalse(index.has("b", null, "x.jpg", 5, "ab"), "Another phone")
        assertFalse(index.has("a", null, "x.jpg", 5, null), "No hash, no skip")
        assertTrue(ReceivedIndex(dir).has("a", null, "x.jpg", 5, "ab"), "Kept")
        index.forget("a")
        assertFalse(ReceivedIndex(dir).has("a", null, "x.jpg", 5, "ab"))
    }

    // Over TLS

    private class Saved(val name: String, val origin: FileOrigin?, val bytes: ByteArray)

    private inner class Phone(name: String, caps: List<String>? = PhoneLinkProtocol.PHONE_CAPS) {
        val dir: File = Files.createTempDirectory("dysk-$name").toFile()
        val asked = CopyOnWriteArrayList<PeerAsk>()
        val saved = CopyOnWriteArrayList<Saved>()
        val lowSpace = CopyOnWriteArrayList<String?>()
        val moved = CopyOnWriteArrayList<MoveSummary>()
        @Volatile var free = 50L shl 30
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        private val data = object : PhoneData {
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
            override fun freeBytes(): Long = free
        }
        private val sinks = object : FileSinkFactory {
            override fun open(name: String, size: Long): FileSink = make(name, null)
            override fun open(name: String, size: Long, origin: FileOrigin): FileSink = make(name, origin)
            fun make(name: String, origin: FileOrigin?): FileSink {
                val out = ByteArrayOutputStream()
                return object : FileSink {
                    override fun write(bytes: ByteArray) = out.write(bytes)
                    override fun commit(): String = "saved".also { saved += Saved(name, origin, out.toByteArray()) }
                    override fun abort() {}
                }
            }
            override fun exists(origin: FileOrigin, name: String, size: Long) = saved.any { it.name == name && it.bytes.size.toLong() == size }
        }
        val manager = ConnectionManager(
            dir, scope,
            localHello = { paired -> Hello(name, "android", "Test $name", PhoneLinkProtocol.VERSION, paired, caps = caps) },
            localInfo = { DeviceInfo() },
            isUiVisible = { false },
            actions = object : DeviceActions {
                override fun setClipboard(text: String) {}
                override fun media(action: String) {}
            },
            fileSinks = sinks,
            phoneData = data,
        ).also { managers += it }.also { cm ->
            cm.phoneRequestAskSink = { _, kind -> asked += kind }
            cm.lowSpaceSink = { _, kind -> lowSpace += kind }
            cm.phoneMoveSink = { _, summary -> moved += summary }
        }
        val id get() = manager.localDeviceId
        fun peer(other: Phone) = manager.peers.value.firstOrNull { it.id == other.id }
    }

    private fun waitUntil(message: String, seconds: Int = 10, check: () -> Boolean) {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError(message)
            Thread.sleep(20)
        }
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
        waitUntil("No caps") { a.peer(b)?.canBackup == true && b.peer(a)?.canMove == true }
    }

    /** Sends like photo backup does, and waits for the answer. */
    private fun backup(from: Phone, to: Phone, name: String, bytes: ByteArray): Pair<Boolean, FileTransfers.Outcome?> {
        val result = java.util.concurrent.LinkedBlockingQueue<Boolean>()
        val id = from.manager.fileTransfers.sendFile(to.id, name, bytes.size.toLong(), PhoneKinds.PHOTO, onFinished = { result.put(it) }, sha256 = sha256Hex(bytes)) {
            bytes.inputStream()
        }
        val ok = result.poll(10, java.util.concurrent.TimeUnit.SECONDS) ?: throw AssertionError("No answer for $name")
        return ok to from.manager.fileTransfers.outcome(id)
    }

    @Test
    fun photoBackupToAnotherPhone() {
        val phone = Phone("Phone")
        val tablet = Phone("Tablet")
        pair(phone, tablet)
        val photo = ByteArray(200_000) { (it % 251).toByte() }

        // Never asked: refused, and the tablet asks. Nothing saved.
        val (first, why) = backup(phone, tablet, "IMG_1.jpg", photo)
        assertFalse(first)
        assertEquals(PhoneKinds.ASK, why?.reason)
        waitUntil("Not asked") { PeerAsk.Backup in tablet.asked }
        assertTrue(tablet.saved.isEmpty())
        // A plain file still goes, to Downloads, without a switch.
        val plain = java.util.concurrent.LinkedBlockingQueue<Boolean>()
        phone.manager.fileTransfers.sendFile(tablet.id, "notes.txt", 3, onFinished = { plain.put(it) }) { "abc".byteInputStream() }
        assertEquals(true, plain.poll(10, java.util.concurrent.TimeUnit.SECONDS))
        assertNull(tablet.saved.single().origin)

        tablet.manager.setPhoneAccess(phone.id) { it.copy(backup = true) }
        waitUntil("Not saved") { tablet.manager.phoneAccess.of(phone.id).backup == true }
        assertTrue(backup(phone, tablet, "IMG_1.jpg", photo).first)
        val kept = tablet.saved.last()
        assertEquals(PhoneKinds.PHOTO, kept.origin?.kind)
        assertEquals("Phone", kept.origin?.peerName)
        assertTrue(photo.contentEquals(kept.bytes))

        // The same photo again: skipped, nothing sent twice.
        val count = tablet.saved.size
        val (again, outcome) = backup(phone, tablet, "IMG_1.jpg", photo)
        assertTrue(again)
        assertEquals(true, outcome?.skipped)
        assertEquals(count, tablet.saved.size)

        // Less than 1 GB would be left: refused with the reason, and the tablet says so.
        tablet.free = StorageGuard.MIN_FREE_BYTES + 1_000
        val (full, low) = backup(phone, tablet, "IMG_2.jpg", photo)
        assertFalse(full)
        assertEquals(PhoneKinds.LOW_SPACE, low?.reason)
        assertTrue(low?.error!!.contains("Less than 1 GB"))
        waitUntil("Not told") { tablet.lowSpace.isNotEmpty() }
        assertEquals(count, tablet.saved.size)

        // Refused: no more questions.
        tablet.free = 50L shl 30
        tablet.manager.setPhoneAccess(phone.id) { it.copy(backup = false) }
        waitUntil("Not saved") { tablet.manager.phoneAccess.of(phone.id).backup == false }
        val asked = tablet.asked.size
        assertEquals(PhoneKinds.OFF, backup(phone, tablet, "IMG_3.jpg", photo).second?.reason)
        assertEquals(asked, tablet.asked.size)
    }

    private class Files3(val contents: Map<String, ByteArray>) : MoveSource {
        val opened = ConcurrentHashMap<String, Int>()
        override fun open(item: MoveItem): InputStream {
            opened.merge(item.id, 1, Int::plus)
            return (contents[item.id] ?: error("gone")).inputStream()
        }
        override fun length(item: MoveItem) = contents[item.id]?.size?.toLong() ?: item.size
    }

    @Test
    fun movingToANewPhone() {
        val old = Phone("Old")
        val new = Phone("New")
        pair(old, new)
        val vcf = VCard.write(listOf(VCardContact("Budi", phones = listOf("0812")))).toByteArray()
        val contents = mapOf("contacts" to vcf, "m1" to ByteArray(150_000) { 1 }, "f1" to "doc".toByteArray())
        old.manager.mover.source = Files3(contents)
        val items = listOf(
            MoveItem("contacts", MoveKind.Contacts, "contacts.vcf", vcf.size.toLong(), "x"),
            MoveItem("m1", MoveKind.Media, "IMG_1.jpg", 150_000, "x"),
            MoveItem("f1", MoveKind.File, "a.txt", 3, "x", folder = "Documents/Work"),
        )
        old.manager.mover.start(new.id, "New", items)

        // Never asked: the new phone asks, and the old one pauses with why.
        waitUntil("Not paused") { old.manager.mover.job.plan.value?.phase == MovePhase.Paused }
        assertTrue(old.manager.mover.job.plan.value!!.error!!.contains("Waiting for New"))
        waitUntil("Not asked") { PeerAsk.Move in new.asked }
        assertTrue(new.saved.isEmpty())

        new.manager.setPhoneAccess(old.id) { it.copy(move = true) }
        waitUntil("Not saved") { new.manager.phoneAccess.of(old.id).move == true }
        old.manager.mover.resume()
        waitUntil("Not done") { old.manager.mover.job.plan.value?.phase == MovePhase.Done }
        waitUntil("No summary") { new.moved.isNotEmpty() }
        val summary = new.moved.single()
        assertEquals(3, summary.sent)
        assertEquals("contacts.vcf", summary.contactsFile)
        assertEquals(setOf(PhoneKinds.MOVE_CONTACTS, PhoneKinds.MOVE_MEDIA, PhoneKinds.MOVE_FILE), new.saved.map { it.origin?.kind }.toSet())
        assertEquals("Documents/Work", new.saved.first { it.name == "a.txt" }.origin?.folder)
        assertEquals("Old", new.saved.first().origin?.peerName)
        assertEquals("Budi", VCard.parse(new.saved.first { it.name == "contacts.vcf" }.bytes.decodeToString()).single().name)

        // Moving again skips what's there.
        old.manager.mover.start(new.id, "New", items)
        waitUntil("Not done again") { old.manager.mover.job.plan.value?.phase == MovePhase.Done }
        assertEquals(3, old.manager.mover.job.progress()!!.skipped)
        assertEquals(3, new.saved.size)

        // The new phone is full: paused with why.
        new.free = StorageGuard.MIN_FREE_BYTES
        old.manager.mover.start(new.id, "New", listOf(MoveItem("m2", MoveKind.Media, "IMG_2.jpg", 10, "x")))
        old.manager.mover.source = Files3(contents + ("m2" to ByteArray(10)))
        waitUntil("Not paused for space") { old.manager.mover.job.plan.value?.phase == MovePhase.Paused }
        assertTrue(old.manager.mover.job.plan.value!!.error!!.contains("Less than 1 GB"))
    }
}
