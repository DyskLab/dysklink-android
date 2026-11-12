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

/**
 * The last Mac features on Windows and Linux: the phone's contacts and files on a PC (`pc-contacts`, `pc-files`),
 * the notch tools kept on a PC (`pc-notch`), document scans (`pc-scan`), and which PCs the phone shows the deck and
 * typing for (`pc-deck`, `pc-type`). The rules alone, then the real phone ConnectionManager against the real
 * ComputerHub over TLS.
 */
class PcParityTest {
    private val scopes = mutableListOf<CoroutineScope>()
    private val closers = mutableListOf<() -> Unit>()
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    @AfterTest
    fun close() {
        closers.forEach { runCatching { it() } }
        scopes.forEach { it.cancel() }
    }

    private fun waitUntil(message: String, seconds: Int = 10, check: () -> Boolean) {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError(message)
            Thread.sleep(20)
        }
    }

    private fun never(message: String, ms: Long = 1_200, check: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (check()) throw AssertionError(message)
            Thread.sleep(20)
        }
    }

    private fun peer(platform: String, caps: List<String>?) = Peer("id", "Computer", platform, "", true, true, null, caps = caps.orEmpty())

    // The rules on their own

    @Test
    fun newCapsOpenOnlyTheirOwnFeatures() {
        val all = PhoneLinkProtocol.PC_CAPS
        val old = PhoneLinkProtocol.CAPS + listOf(PhoneLinkProtocol.CAP_PC_SCREEN, PhoneLinkProtocol.CAP_PC_CAMERA, PhoneLinkProtocol.CAP_PC_GALLERY, PhoneLinkProtocol.CAP_PC_SMS)
        // Contacts and files are asked for by the computer, so the phone checks them as for the screen.
        for (type in listOf(MessageType.CONTACTS_LIST, MessageType.FS_LIST, MessageType.FS_GET)) {
            assertTrue(PcRules.allows("macos", null, type), "A Mac keeps $type")
            assertFalse(PcRules.allows("windows", old, type), "An older PC build doesn't get $type")
            assertTrue(PcRules.allows("linux", all, type), "A new PC gets $type")
        }
        assertTrue(PcRules.allows("windows", listOf(PhoneLinkProtocol.CAP_PC_CONTACTS), MessageType.CONTACTS_LIST))
        assertFalse(PcRules.allows("windows", listOf(PhoneLinkProtocol.CAP_PC_CONTACTS), MessageType.FS_LIST))
        // notch.state is taken only from a PC that keeps the tools.
        assertFalse(PcRules.allows("windows", old, MessageType.NOTCH_STATE))
        assertTrue(PcRules.allows("windows", listOf(PhoneLinkProtocol.CAP_PC_NOTCH), MessageType.NOTCH_STATE))
        assertTrue(PcRules.allows("macos", null, MessageType.NOTCH_STATE))

        for (platform in listOf("windows", "linux")) {
            val newPc = peer(platform, all)
            assertTrue(newPc.canDeck && newPc.canNotch && newPc.canType && newPc.canTakeScans, "A new $platform PC shows all four")
            val oldPc = peer(platform, old + PhoneLinkProtocol.PC_SERVICE_CAPS.take(5))
            assertFalse(oldPc.canDeck || oldPc.canNotch || oldPc.canType || oldPc.canTakeScans, "An older $platform PC shows none")
            assertFalse(peer(platform, null).canDeck)
        }
        val mac = peer("macos", PhoneLinkProtocol.CAPS)
        assertTrue(mac.canDeck && mac.canNotch && mac.canType && mac.canTakeScans, "A Mac keeps everything")
        assertFalse(peer("android", all).canDeck, "Never for a phone")

        // The phone serves contacts and files; it doesn't serve the PC's own things.
        assertTrue(PhoneLinkProtocol.CAP_PC_CONTACTS in PhoneLinkProtocol.PHONE_CAPS)
        assertTrue(PhoneLinkProtocol.CAP_PC_FILES in PhoneLinkProtocol.PHONE_CAPS)
        for (cap in listOf(PhoneLinkProtocol.CAP_PC_DECK, PhoneLinkProtocol.CAP_PC_NOTCH, PhoneLinkProtocol.CAP_PC_TYPE, PhoneLinkProtocol.CAP_PC_SCAN)) {
            assertTrue(cap in all, "The PC lists $cap")
            assertFalse(cap in PhoneLinkProtocol.PHONE_CAPS, "The phone doesn't list $cap")
        }
        assertEquals(listOf(PhoneLinkProtocol.CAP_BINARY), PhoneLinkProtocol.CAPS, "Macs keep advertising bin1 only")
    }

    // The tools on the PC

    private class Clock(var now: Long)

    private val zone = ZoneId.of("Asia/Jakarta")
    private fun at(hour: Int, minute: Int = 0) = LocalDateTime.of(2026, 11, 12, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun todosFollowTheMacsLimitsAndAreKept() {
        val dir = Files.createTempDirectory("dysk-notch").toFile()
        val clock = Clock(at(9))
        val notch = PcNotch(dir, { clock.now }, { zone })
        assertFalse(notch.addTodo("   "), "An empty title isn't added")
        assertTrue(notch.addTodo("Line one\nline two"))
        assertEquals("Line one", notch.tools.value.todos.items.single().title, "One line, as on the Mac")
        assertTrue(notch.deleteTodo(notch.tools.value.todos.items.single().id))
        assertTrue(notch.addTodo("y".repeat(200)))
        val first = notch.tools.value.todos.items.single()
        assertEquals(PcNotch.MAX_TITLE, first.title.length, "Cut to 80 characters")
        repeat(PcNotch.MAX_TODOS - 1) { assertTrue(notch.addTodo("Task $it")) }
        assertFalse(notch.addTodo("One too many"), "At most 50")
        assertTrue(notch.todosFull)

        assertTrue(notch.toggleTodo(first.id))
        assertTrue(notch.renameTodo(first.id, "Renamed"))
        assertTrue(notch.deleteTodo(notch.tools.value.todos.items.last().id))
        val state = notch.notchState()
        assertEquals(PcNotch.MAX_TODOS - 1, state.todos.size)
        assertEquals(NotchTask(first.id, "Renamed", true), state.todos.first { it.id == first.id })

        // Saved: a new PcNotch on the same folder has the same list.
        val again = PcNotch(dir, { clock.now }, { zone })
        assertEquals(notch.tools.value.todos, again.tools.value.todos)

        // A task done yesterday is gone the next morning; one not done stays.
        clock.now = at(9) + 24 * 3600_000L
        notch.tick()
        assertNull(notch.tools.value.todos.items.firstOrNull { it.id == first.id })
        assertEquals(PcNotch.MAX_TODOS - 2, notch.tools.value.todos.items.size)
    }

    @Test
    fun phonesChangeTheToolsWithTheMacsActions() {
        val clock = Clock(at(10))
        val notch = PcNotch(Files.createTempDirectory("dysk-notch").toFile(), { clock.now }, { zone })
        assertTrue(notch.apply(NotchAction("todo.add", text = "Buy milk")))
        val id = notch.notchState().todos.single().id
        assertTrue(notch.apply(NotchAction("todo.toggle", id = id)))
        assertTrue(notch.notchState().todos.single().done)
        assertTrue(notch.apply(NotchAction("todo.rename", id = id, text = "Buy oat milk")))
        assertEquals("Buy oat milk", notch.notchState().todos.single().title)
        assertTrue(notch.apply(NotchAction("todo.delete", id = id)))
        assertTrue(notch.notchState().todos.isEmpty())
        assertFalse(notch.apply(NotchAction("todo.toggle", id = "")), "An empty id does nothing")
        assertFalse(notch.apply(NotchAction("system.shutdown")), "An unknown kind does nothing")

        assertTrue(notch.apply(NotchAction("water.drink")))
        assertTrue(notch.apply(NotchAction("water.drink")))
        assertEquals(500, notch.notchState().water.totalMl)
        assertTrue(notch.apply(NotchAction("water.undo")))
        assertEquals(NotchWater(250, 2000, 250), notch.notchState().water)

        assertTrue(notch.apply(NotchAction("note.set", text = "Call the bank")))
        assertEquals("Call the bank", notch.notchState().note)

        assertTrue(notch.apply(NotchAction("focus.toggle")))
        val focus = notch.notchState().focus
        assertTrue(focus.running)
        assertEquals("focus", focus.phase)
        assertEquals(25 * 60_000L, focus.lengthMs)
        clock.now += 60_000
        assertEquals(24 * 60_000L, notch.notchState().focus.remainingMs)
        assertTrue(notch.apply(NotchAction("focus.reset")))
        assertFalse(notch.notchState().focus.running)
    }

    @Test
    fun aRoundEndingAndWaterRemindersComeFromTheTick() {
        val clock = Clock(at(10))
        val notch = PcNotch(Files.createTempDirectory("dysk-notch").toFile(), { clock.now }, { zone })
        notch.togglePomodoro()
        assertNull(notch.tick().finished)
        clock.now += 25 * 60_000L + 1_000
        val tick = notch.tick()
        assertEquals(PomodoroPhase.Focus, tick.finished)
        assertNotNull(tick.next)
        assertEquals(PomodoroPhase.ShortBreak, tick.next!!.phase)
        val (title, _) = PcNotch.finishedText(tick.finished!!, tick.next, notch.tools.value.pomodoro.settings)
        assertTrue(title.isNotBlank())
        assertNull(notch.tick().finished, "Said once")

        // Reminders: off by default; on, once per interval within the hours, and not after the goal.
        assertFalse(notch.tick().remindWater)
        notch.setWater(WaterSettings(reminds = true, remindEveryMinutes = 60, fromHour = 8, toHour = 21))
        clock.now = at(11)
        assertTrue(notch.tick().remindWater)
        assertFalse(notch.tick().remindWater, "Not again right away")
        clock.now = at(12, 1)
        assertTrue(notch.tick().remindWater)
        clock.now = at(22)
        assertFalse(notch.tick().remindWater, "Not at night")
        repeat(8) { notch.drink() }
        clock.now = at(13) + 24 * 3600_000L
        assertTrue(notch.tick().remindWater, "A new day starts at zero")
    }

    @Test
    fun contactSearchMatchesAsTheMacDoes() {
        val book = listOf(
            ContactItem("1", "Budi Santoso", listOf("+62 812-3456-7890")),
            ContactItem("2", "Ánh Nguyễn", listOf("0898 111 222")),
            ContactItem("3", "Siti", listOf("021 555 0101")),
        )
        assertEquals(listOf("1"), ContactSearch.search(book, "budi").map { it.id })
        assertEquals(listOf("1"), ContactSearch.search(book, "0812 3456").map { it.id }, "With the national 0")
        assertEquals(listOf("1"), ContactSearch.search(book, "+62812").map { it.id }, "With the country code")
        assertEquals(listOf("2"), ContactSearch.search(book, "anh").map { it.id }, "Accents fold")
        assertTrue(ContactSearch.search(book, "zzz").isEmpty())
        assertEquals("B", ContactSearch.section("budi"))
        assertEquals("#", ContactSearch.section("123"))
        assertEquals("BS", ContactSearch.initials("Budi Santoso"))
    }

    // Over the network

    private fun sink(folder: File, saved: MutableList<Pair<String, FileOrigin?>>) = object : FileSinkFactory {
        override fun open(name: String, size: Long): FileSink = sinkFor(name, null)
        override fun open(name: String, size: Long, origin: FileOrigin): FileSink = sinkFor(name, origin)
        private fun sinkFor(name: String, origin: FileOrigin?): FileSink {
            val out = ByteArrayOutputStream()
            return object : FileSink {
                override fun write(bytes: ByteArray) = out.write(bytes)
                override fun commit(): String = File(folder, name).also { it.writeBytes(out.toByteArray()); saved += name to origin }.path
                override fun abort() {}
            }
        }
    }

    private inner class Pc(caps: List<String>?) {
        val dir: File = Files.createTempDirectory("dysk-parity").toFile()
        val notch = PcNotch(dir)
        val saved = CopyOnWriteArrayList<Pair<String, FileOrigin?>>()
        val hub: ComputerHub = ComputerHub(
            dir, scope(),
            localHello = { paired -> Hello("Test PC", "windows", "Test", PhoneLinkProtocol.VERSION, paired, "1.0.0", caps) },
            events = object : HubEvents {
                override fun clipboard(text: String, from: String) = true
                override fun ring(on: Boolean, from: String) {}
                override fun notchState(): NotchState = notch.notchState()
                override fun notchAction(action: NotchAction, from: String) { if (notch.apply(action)) hub.notchChanged() }
            },
            fileSinks = sink(dir, saved),
            port = 0,
        ).also { it.start(); closers += it::shutdown }
    }

    private inner class Phone {
        val dir: File = Files.createTempDirectory("dysk-parity-phone").toFile()
        val contacts = (1..30).map { ContactItem("$it", if (it % 2 == 0) "zara $it" else "Adi $it", listOf("0812000$it")) }
        val manager = ConnectionManager(
            dir, scope(),
            localHello = { paired -> Hello("Pixel", "android", "Pixel 9", PhoneLinkProtocol.VERSION, paired, caps = PhoneLinkProtocol.PHONE_CAPS) },
            localInfo = { DeviceInfo(batteryPercent = 64) },
            isUiVisible = { true },
            actions = object : DeviceActions {
                override fun setClipboard(text: String) {}
                override fun media(action: String) {}
            },
            fileSinks = sink(dir, CopyOnWriteArrayList()),
            phoneData = object : PhoneData {
                override val storageRoot = File(dir, "storage").apply { mkdirs() }
                override fun isGranted(feature: PhoneFeature) = true
                override fun activeNotifications() = emptyList<NotificationItem>()
                override fun dismissNotification(key: String) {}
                override fun readSms(limit: Int, afterId: Long) = emptyList<SmsItem>()
                override fun sendSms(address: String, body: String, done: (String?) -> Unit) = done(null)
                override fun readContacts() = contacts
                override fun requestScreen(peerId: String) {}
                override fun syncScreen(peerId: String) {}
                override fun stopScreen(peerId: String) {}
                override fun control(event: ControlEvent) {}
            },
        ).also { closers += it::shutdown }
    }

    private fun paired(pc: Pc, phone: Phone): String {
        phone.manager.addManual("127.0.0.1", pc.hub.boundPort)
        waitUntil("The phone didn't connect") { pc.hub.phones.value.any { it.isConnected } }
        phone.manager.tryAutoPair()
        waitUntil("No code") { pc.hub.pairing.value != null }
        phone.manager.submitCode(pc.hub.pairing.value!!.code)
        waitUntil("Pairing didn't finish") {
            pc.hub.phones.value.any { it.isPaired && it.isConnected } && phone.manager.peers.value.any { it.id == pc.hub.deviceId && it.isPaired && it.isConnected }
        }
        return phone.manager.localDeviceId
    }

    @Test
    fun aPcReadsThePhonesContactsAndFiles() {
        val pc = Pc(PhoneLinkProtocol.PC_CAPS)
        val phone = Phone()
        File(phone.dir, "storage/Documents").mkdirs()
        File(phone.dir, "storage/Documents/report.txt").writeText("hello from the phone")
        File(phone.dir, "storage/notes.md").writeText("# notes")
        File(phone.dir, "storage/.secret").writeText("hidden")
        val phoneId = paired(pc, phone)
        val device = pc.hub.phones.value.first { it.id == phoneId }
        assertTrue(device.servesContacts && device.servesFiles)

        // Off on the phone: nothing comes.
        pc.hub.loadContacts(phoneId)
        never("Contacts while the switch is off") { pc.hub.contacts.value[phoneId]?.contacts.orEmpty().isNotEmpty() }
        phone.manager.phone.setEnabled(PhoneFeature.Contacts, true)
        phone.manager.phone.setEnabled(PhoneFeature.Files, true)
        waitUntil("Features didn't arrive") { pc.hub.phones.value.first().features.containsAll(listOf("contacts", "files")) }

        pc.hub.loadContacts(phoneId)
        waitUntil("No contacts") { pc.hub.contacts.value[phoneId]?.loaded == true }
        val book = pc.hub.contacts.value[phoneId]!!
        assertEquals(30, book.contacts.size)
        assertFalse(book.loading)
        assertEquals(book.contacts.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }), book.contacts, "Sorted by name, case aside")

        pc.hub.openPhoneFolder(phoneId, "")
        waitUntil("No listing") { pc.hub.folders.value[phoneId]?.let { !it.loading && it.entries.isNotEmpty() } == true }
        assertEquals(setOf("Documents", "notes.md"), pc.hub.folders.value[phoneId]!!.entries.map { it.name }.toSet(), "Hidden files stay hidden")
        pc.hub.openPhoneFolder(phoneId, "", hidden = true)
        waitUntil("Hidden files") { pc.hub.folders.value[phoneId]!!.entries.any { it.name == ".secret" } }
        pc.hub.openPhoneFolder(phoneId, "Documents")
        waitUntil("Documents") { pc.hub.folders.value[phoneId]!!.let { it.folder == "Documents" && !it.loading && it.entries.isNotEmpty() } }
        assertEquals(listOf("report.txt"), pc.hub.folders.value[phoneId]!!.entries.map { it.name })

        pc.hub.downloadPhoneFiles(phoneId, listOf("Documents/report.txt", "notes.md"))
        waitUntil("Downloads") { pc.saved.map { it.first }.containsAll(listOf("report.txt", "notes.md")) }
        assertEquals("hello from the phone", File(pc.dir, "report.txt").readText())
        assertTrue(pc.saved.all { it.second == null }, "Into Downloads, as any file")

        // Outside storage is refused by the phone: an error, and nothing saved.
        pc.hub.openPhoneFolder(phoneId, "../../")
        waitUntil("An error for outside") { pc.hub.folders.value[phoneId]!!.error != null }
    }

    @Test
    fun anOlderPcGetsNoContactsFilesOrTools() {
        val oldCaps = PhoneLinkProtocol.CAPS + listOf(PhoneLinkProtocol.CAP_PC_SCREEN, PhoneLinkProtocol.CAP_PC_CAMERA, PhoneLinkProtocol.CAP_PC_GALLERY, PhoneLinkProtocol.CAP_PC_SMS)
        val pc = Pc(oldCaps)
        val phone = Phone()
        File(phone.dir, "storage/notes.md").writeText("# notes")
        val phoneId = paired(pc, phone)
        phone.manager.phone.setEnabled(PhoneFeature.Contacts, true)
        phone.manager.phone.setEnabled(PhoneFeature.Files, true)
        waitUntil("Features didn't arrive") { pc.hub.phones.value.first().features.containsAll(listOf("contacts", "files")) }
        assertTrue(pc.hub.phones.value.first().servesContacts, "The phone lists the caps")
        // The PC asks anyway, as an older build that knew these would: the phone drops it.
        pc.hub.loadContacts(phoneId)
        pc.hub.openPhoneFolder(phoneId, "")
        // The PC keeps tools, but didn't list pc-notch: the phone drops its notch.state.
        pc.notch.addTodo("Hidden")
        pc.hub.notchChanged()
        never("Contacts reached an older PC") { pc.hub.contacts.value[phoneId]?.contacts.orEmpty().isNotEmpty() }
        assertTrue(pc.hub.folders.value[phoneId]?.entries.orEmpty().isEmpty(), "No listing for an older PC")
        assertNull(phone.manager.notch.value[pc.hub.deviceId], "No tools from a PC without pc-notch")
        val peer = phone.manager.peers.value.first { it.id == pc.hub.deviceId }
        assertFalse(peer.canNotch || peer.canDeck || peer.canType || peer.canTakeScans)
    }

    @Test
    fun theToolsSyncBothWaysWithAPhone() {
        val pc = Pc(PhoneLinkProtocol.PC_CAPS)
        pc.notch.addTodo("Write the report")
        val phone = Phone()
        paired(pc, phone)
        val pcId = pc.hub.deviceId
        assertTrue(phone.manager.peers.value.first { it.id == pcId }.canNotch)
        waitUntil("No state after pairing") { phone.manager.notch.value[pcId]?.state?.todos?.map { it.title } == listOf("Write the report") }

        // From the phone: the PC changes and sends the state back.
        phone.manager.sendNotchAction(pcId, NotchAction("todo.add", text = "From the phone"))
        waitUntil("The phone's task") { pc.notch.tools.value.todos.items.any { it.title == "From the phone" } }
        waitUntil("Back on the phone") { phone.manager.notch.value[pcId]!!.state.todos.size == 2 }
        phone.manager.sendNotchAction(pcId, NotchAction("water.drink"))
        waitUntil("Water") { phone.manager.notch.value[pcId]!!.state.water.totalMl == 250 }
        phone.manager.sendNotchAction(pcId, NotchAction("focus.toggle"))
        waitUntil("Focus") { phone.manager.notch.value[pcId]!!.state.focus.running }

        // From the PC: phones get it.
        pc.notch.clearDone()
        pc.notch.toggleTodo(pc.notch.tools.value.todos.items.first().id)
        pc.hub.notchChanged()
        waitUntil("A change on the PC") { phone.manager.notch.value[pcId]!!.state.todos.count { it.done } == 1 }

    }

    @Test
    fun scansFromAPhoneGoToTheScansFolder() {
        val pc = Pc(PhoneLinkProtocol.PC_CAPS)
        val phone = Phone()
        paired(pc, phone)
        val pcId = pc.hub.deviceId
        val pdf = "%PDF-1.4 scan".toByteArray()
        phone.manager.fileTransfers.sendFile(pcId, "Scan 1.pdf", pdf.size.toLong(), kind = "scan") { pdf.inputStream() }
        phone.manager.fileTransfers.sendFile(pcId, "photo.jpg", 3, kind = null) { "jpg".byteInputStream() }
        waitUntil("Both files") { pc.saved.size == 2 }
        val scan = pc.saved.first { it.first == "Scan 1.pdf" }.second
        assertEquals("scan", scan?.kind, "A scan is a scan")
        assertEquals("Pixel", scan?.peerName)
        assertNull(pc.saved.first { it.first == "photo.jpg" }.second, "Anything else goes to Downloads")
    }
}
