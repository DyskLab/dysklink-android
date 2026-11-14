package id.dysklink.desktop

import id.phonelink.android.core.Envelope
import id.phonelink.android.core.RemoteEvent
import java.awt.Point
import java.awt.Rectangle
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The desktop fixes from the November audit, each with fakes: nothing here needs Windows or an X server. */
class AuditFixesTest {
    private val dir: File = Files.createTempDirectory("dysk-audit").toFile()

    @AfterTest
    fun clean() { dir.deleteRecursively() }

    // 3: a Mac that goes quiet while a key or button is held

    private class Recorder : InputInjector {
        var at = Point(500, 500)
        val events = CopyOnWriteArrayList<String>()
        override val displays = listOf(Rectangle(0, 0, 1920, 1080))
        override val pointer get() = Point(at)
        override fun moveTo(x: Int, y: Int) { at = Point(x, y) }
        override fun button(button: Int, down: Boolean) { events += "button $button ${if (down) "down" else "up"}" }
        override fun wheel(notches: Int) {}
        override fun key(vk: Int, down: Boolean) { events += "key $vk ${if (down) "down" else "up"}" }
    }

    @Test
    fun heldInputGoesUpWhenTheMacGoesQuiet() {
        var now = 1_000L
        val injector = Recorder()
        val sent = CopyOnWriteArrayList<Envelope>()
        val target = SharedInputTarget(injector, send = { _, e -> sent += e }, clock = { now }, watchdog = false)
        target.enter("mac", ScreenEdge.LEFT, 0.5)
        // An older Mac that never pings isn't timed out: a still, held drag stays held.
        target.handle(RemoteEvent("down", button = 0), "mac")
        now += 10_000
        assertFalse(target.checkStale(), "no ping yet")
        target.handle(RemoteEvent("up", button = 0), "mac")
        target.pinged("mac")
        // Nothing held: quiet is fine, nothing happens.
        now += 5_000
        assertFalse(target.checkStale())
        // A button held, then the Mac's Wi-Fi goes: after 2 s it goes up here.
        target.handle(RemoteEvent("down", button = 0), "mac")
        target.handle(RemoteEvent("key", keyCode = 0, keyDown = true, flags = 0), "mac")
        assertTrue(target.holding)
        now += 1_500
        assertFalse(target.checkStale(), "not yet")
        // A ping or any other message keeps it held.
        target.heard("mac")
        now += 1_500
        assertFalse(target.checkStale(), "heard 1.5 s ago")
        now += 600
        injector.events.clear()
        assertTrue(target.checkStale())
        assertEquals(setOf("button 0 up", "key ${java.awt.event.KeyEvent.VK_A} up"), injector.events.toSet())
        assertFalse(target.holding)
        assertEquals("mac", target.controller, "control stays with the Mac; its next input carries on")
        assertTrue(sent.isEmpty())
        // Another computer's messages don't count for the one in control.
        target.handle(RemoteEvent("down", button = 1), "mac")
        now += 1_000
        target.heard("other")
        now += 1_100
        assertTrue(target.checkStale())
        // Disconnecting lets go at once.
        target.handle(RemoteEvent("down", button = 0), "mac")
        injector.events.clear()
        target.gone("mac")
        assertEquals(listOf("button 0 up"), injector.events.toList())
        assertNull(target.controller)
        assertFalse(target.checkStale())
    }

    // 14: the elevated script

    @Test
    fun theElevatedScriptKeepsNonAsciiPaths() {
        val path = "C:\\Users\\Zoë O\u2019Brien\\AppData\\Local\\Temp\\dysklink-vdd-1.ps1"
        assertEquals("'it''s'", VddInstall.quote("it's"))
        assertEquals("'O\u2019\u2019Brien'", VddInstall.quote("O\u2019Brien"), "a typographic quote ends a PowerShell string too")
        val line = VddInstall.elevated(path)
        assertTrue("'\"C:\\Users\\Zoë O\u2019\u2019Brien\\AppData\\Local\\Temp\\dysklink-vdd-1.ps1\"'" in line, line)
        // -EncodedCommand: UTF-16LE in Base64, back to the same line.
        val decoded = String(java.util.Base64.getDecoder().decode(VddInstall.encoded(line)), Charsets.UTF_16LE)
        assertEquals(line, decoded)
        // The file: UTF-8 with a BOM, which Windows PowerShell 5.1 needs to read it as UTF-8.
        val file = File(dir, "script.ps1")
        val script = VddInstall.installScript("C:\\Users\\Zoë\\DyskLink\\vdd", null)
        VddInstall.writeScript(file, script)
        val bytes = file.readBytes()
        assertEquals(listOf(0xEF, 0xBB, 0xBF), bytes.take(3).map { it.toInt() and 0xFF })
        assertEquals(script, String(bytes, 3, bytes.size - 3, Charsets.UTF_8))
        assertTrue("\$src = 'C:\\Users\\Zoë\\DyskLink\\vdd'" in script)
    }

    // 19: JPEG frames in memory

    @Test
    fun jpegFramesAreWrittenInMemoryWithOneWriter() {
        val image = BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        for (i in 0 until 40) { g.color = java.awt.Color((i * 37) and 0xFFFFFF); g.fillRect(i * 8, (i * 13) % 200, 30, 30) }
        g.dispose()
        val tmp = File(System.getProperty("java.io.tmpdir"))
        val before = tmp.listFiles { f -> f.name.startsWith("imageio") }?.size ?: 0
        val high = assertNotNull(ScreenPictures.jpeg(image, 90))
        val low = assertNotNull(ScreenPictures.jpeg(image, 20))
        repeat(20) { assertNotNull(ScreenPictures.jpeg(image, 50 + it)) }
        assertTrue(low.size < high.size, "the quality is set per frame on the reused settings")
        val back = assertNotNull(ImageIO.read(high.inputStream()))
        assertEquals(320 to 200, back.width to back.height)
        val after = tmp.listFiles { f -> f.name.startsWith("imageio") }?.size ?: 0
        assertTrue(after <= before, "no ImageIO cache files")
        // Another thread has its own writer and gets the same bytes.
        var other: ByteArray? = null
        Thread { other = ScreenPictures.jpeg(image, 90) }.apply { start(); join() }
        assertTrue(other!!.contentEquals(high))
    }

    // 20: the clipboard read only when it changed

    @Test
    fun theGateReadsOnlyWhatMayHaveChanged() {
        var mark: ClipMark? = ClipMark(1, exact = true)
        val gate = ClipGate { mark }
        assertEquals(ClipLook.Changed, gate.look(), "the first look reads")
        assertEquals(ClipLook.Unchanged, gate.look())
        mark = ClipMark(2, exact = true)
        assertEquals(ClipLook.Changed, gate.look())
        gate.retry()
        assertEquals(ClipLook.Changed, gate.look(), "a failed read is tried again")
        // The X11 owner alone: the same owner may have copied again, so the text is read, not pictures.
        mark = ClipMark(77, exact = false)
        assertEquals(ClipLook.Changed, gate.look())
        assertEquals(ClipLook.TextOnly, gate.look())
        // No mark at all (macOS, no display): everything, every time, as before.
        mark = null
        assertEquals(ClipLook.Changed, gate.look())
        assertEquals(ClipLook.Changed, gate.look())
        // A mark that throws counts as none.
        assertEquals(ClipLook.Changed, ClipGate { error("no X") }.look())
    }

    /** Counts what's read from it. */
    private class CountingClipboard : java.awt.datatransfer.Clipboard("test") {
        var reads = 0
        var imageReads = 0
        var busy = false
        @Volatile var current: Transferable? = null
        override fun getAvailableDataFlavors(): Array<DataFlavor> {
            if (busy) throw IllegalStateException("cannot open system clipboard")
            return current?.transferDataFlavors ?: emptyArray()
        }
        override fun isDataFlavorAvailable(flavor: DataFlavor) = availableDataFlavors.contains(flavor)
        override fun getData(flavor: DataFlavor): Any {
            reads++
            if (flavor == DataFlavor.imageFlavor) imageReads++
            return current!!.getTransferData(flavor)
        }
    }

    private class Picture(val image: BufferedImage) : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
        override fun getTransferData(flavor: DataFlavor): Any = image
    }

    @Test
    fun theHistoryWatcherSkipsAnUnchangedClipboard() {
        val board = CountingClipboard()
        var mark = ClipMark(1, exact = true)
        val copies = CopyOnWriteArrayList<ClipCapture>()
        val watch = ClipboardWatch(onCopy = { copies += it }, wayland = false, board = { board }, gate = ClipGate { mark })
        board.current = StringSelection("first")
        watch.pollOnce() // sets where it starts
        assertTrue(copies.isEmpty())
        val reads = board.reads
        repeat(10) { watch.pollOnce() }
        assertEquals(reads, board.reads, "the same sequence number: nothing read")
        board.current = StringSelection("second")
        mark = ClipMark(2, exact = true)
        watch.pollOnce()
        assertEquals("second", (copies.single() as ClipCapture.Text).text)
        // Held open by another app: tried again on the next look, though the mark didn't move again.
        board.current = StringSelection("third")
        mark = ClipMark(3, exact = true)
        board.busy = true
        watch.pollOnce()
        board.busy = false
        watch.pollOnce()
        assertEquals("third", (copies.last() as ClipCapture.Text).text)
        // An owner-only mark that didn't move: text is read, pictures aren't.
        mark = ClipMark(50, exact = false)
        watch.pollOnce()
        board.current = Picture(BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB))
        val imageReads = board.imageReads
        repeat(5) { watch.pollOnce() }
        assertEquals(imageReads, board.imageReads, "pictures only when the mark moved")
        mark = ClipMark(51, exact = false)
        watch.pollOnce()
        assertEquals(imageReads + 1, board.imageReads)
        assertTrue(copies.last() is ClipCapture.Image)
    }

    // 21: the battery from GetSystemPowerStatus, WMI rarely

    @Test
    fun theBatteryComesFromPowerStatusWithRareDetails() {
        val details = BatteryReading(
            percent = 60, state = ChargeState.Discharging, pluggedIn = false, designWh = 50.0, fullWh = 45.0, nowWh = 27.0,
            cycles = 210, watts = 8.5, voltage = 11.4, source = "Windows", vendor = "ACME",
        )
        // On battery, 57 %, 1 h 30 min left.
        val onBattery = WindowsBattery.PowerStatus(acLine = 0, flags = 0, percent = 57, secondsLeft = 5_400)
        val stale = assertNotNull(WindowsBattery.fromPowerStatus(onBattery, details, detailsFresh = false, report = null))
        assertEquals(57, stale.percent)
        assertEquals(ChargeState.Discharging, stale.state)
        assertEquals(false, stale.pluggedIn)
        assertEquals(90, stale.minutesToEmpty)
        assertEquals(210, stale.cycles)
        assertEquals(90, stale.health)
        assertNull(stale.watts, "the rate isn't taken from details minutes old")
        assertEquals(8.5, WindowsBattery.fromPowerStatus(onBattery, details, detailsFresh = true, report = null)!!.watts)
        // Charging (flag 8), on the charger and full, and no battery (flag 128).
        assertEquals(ChargeState.Charging, WindowsBattery.fromPowerStatus(WindowsBattery.PowerStatus(1, 8, 40, 0xFFFFFFFFL), null, false, null)!!.state)
        val full = WindowsBattery.fromPowerStatus(WindowsBattery.PowerStatus(1, 1, 100, 0xFFFFFFFFL), null, false, null)!!
        assertEquals(ChargeState.Full, full.state)
        assertNull(full.minutesToEmpty)
        assertNull(WindowsBattery.fromPowerStatus(WindowsBattery.PowerStatus(1, 128, 255, 0xFFFFFFFFL), details, true, null))
        // An unknown level falls back to the details.
        assertEquals(60, WindowsBattery.fromPowerStatus(WindowsBattery.PowerStatus(0, 0, 255, 0xFFFFFFFFL), details, false, null)!!.percent)
        // WMI: always without GetSystemPowerStatus; otherwise only while the card is on screen, every 10 minutes.
        assertTrue(BatteryDetailsPolicy.wmiDue(hasPowerStatus = false, visible = false, now = 0, lastWmiAt = 0))
        assertFalse(BatteryDetailsPolicy.wmiDue(true, visible = false, now = 10_000_000, lastWmiAt = null))
        assertTrue(BatteryDetailsPolicy.wmiDue(true, visible = true, now = 10_000_000, lastWmiAt = null))
        assertFalse(BatteryDetailsPolicy.wmiDue(true, true, now = 1_000_000, lastWmiAt = 1_000_000 - 599_000))
        assertTrue(BatteryDetailsPolicy.wmiDue(true, true, now = 1_000_000, lastWmiAt = 1_000_000 - 600_000))
    }

    @Test
    fun aHungCommandTimesOut() {
        val started = System.nanoTime()
        // Prints, then hangs with its output still open: reading it here would never end.
        assertNull(WindowsBattery.run(listOf("sh", "-c", "echo hello; sleep 30"), 1))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 6_000, "the timeout works")
        assertEquals("hi\n", WindowsBattery.run(listOf("sh", "-c", "echo hi"), 5))
    }

    // 23: the X thread's idle wait and pointer queries

    @Test
    fun thePointerIsAskedAtMostAt60Hz() {
        var now = 0L
        var asked = 0
        var place = Point(100, 100)
        var queried = place
        val throttle = PointerThrottle { now }
        fun move(dx: Double?, dy: Double?) = throttle.next(dx, dy) { asked++; queried = place; place }
        val first = assertNotNull(move(2.0, 0.0))
        assertEquals(1, asked)
        assertEquals(Point(100, 100), Point(first.x, first.y))
        // A thousand raw events a second: asked only every 16 ms, the deltas always passed on.
        var forwarded = 0.0
        repeat(100) {
            now += 1
            place = Point(place.x + 1, 100)
            move(1.0, 0.0)?.let { forwarded += it.dx }
        }
        assertTrue(asked in 6..8, "asked $asked times in 100 ms")
        assertEquals(100.0, forwarded, "no raw delta lost")
        // In between, the place last asked.
        now += 1
        val between = assertNotNull(move(0.5, 0.5))
        assertFalse(between.forward)
        // No deltas at all (scroll valuators): measured from the places asked, or dropped when it isn't time.
        assertNull(move(null, null))
        now += 20
        place = Point(queried.x + 7, queried.y + 3)
        val measured = assertNotNull(move(null, null))
        assertEquals(7.0 to 3.0, measured.dx to measured.dy)
        // After a warp, asked again at once.
        throttle.forget()
        now += 1
        val before = asked
        move(1.0, 0.0)
        assertEquals(before + 1, asked)
        // A failed query gives nothing.
        assertNull(PointerThrottle { 0 }.next(1.0, 1.0) { null })
    }

    @Test
    fun theIdleWaitSleepsUntilWokenOrData() {
        val wait = IdleWait.open() ?: return // no libc here
        val other = IdleWait.open() ?: return
        try {
            // Nothing to read: it waits out the timeout.
            var started = System.nanoTime()
            assertFalse(wait.await(-1, 150))
            assertTrue((System.nanoTime() - started) / 1_000_000 >= 100)
            // A wake ends the wait at once, however many wakes there were.
            repeat(1_000) { wait.wake() }
            started = System.nanoTime()
            assertFalse(wait.await(-1, 5_000))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
            // Drained: the next wait sleeps again, and a later wake still works.
            started = System.nanoTime()
            wait.await(-1, 100)
            assertTrue((System.nanoTime() - started) / 1_000_000 >= 50, "the wakes were drained")
            Thread { Thread.sleep(100); wait.wake() }.start()
            started = System.nanoTime()
            wait.await(-1, 5_000)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
            // Data on the watched descriptor (standing in for the X connection) is reported.
            other.wake()
            assertTrue(wait.await(otherReadFd(other), 5_000), "the watched descriptor had data")
        } finally {
            wait.close()
            other.close()
        }
    }

    /** The read end of [wait]'s pipe, for a test that needs a descriptor with data on it. */
    private fun otherReadFd(wait: IdleWait): Int = IdleWait::class.java.getDeclaredField("readFd").apply { isAccessible = true }.getInt(wait)

    // 24: settings written whole

    @Test
    fun settingsSurviveABrokenFile() {
        val file = File(dir, "settings.properties")
        val settings = Settings(file)
        settings.set("a", "1")
        settings.set("b", true)
        assertEquals("1", Settings(file).string("a"))
        assertFalse(File(dir, "settings.properties.tmp").exists(), "the temp file was moved into place")
        assertTrue(Settings.backup(file).isFile, "the one before is kept")
        // A power cut left zeros, or nothing: the backup is read.
        file.writeBytes(ByteArray(64))
        val recovered = Settings(file)
        assertEquals("1", recovered.string("a"), "the backup holds the last good settings but one")
        file.writeBytes(ByteArray(0))
        assertEquals("1", Settings(file).string("a"))
        file.delete()
        assertEquals("1", Settings(file).string("a"))
        // Saving again writes a good file, and a broken one never becomes the backup.
        file.writeBytes(ByteArray(64))
        Settings(file).set("c", "3")
        val props = Properties().apply { file.inputStream().use { load(it) } }
        assertEquals(setOf("a", "c"), props.stringPropertyNames(), "on top of the backup it was read from")
        assertTrue(Settings.backup(file).readBytes().none { it == 0.toByte() })
    }
}
