package id.dysklink.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClipHistoryTest {
    private fun dir(): File = Files.createTempDirectory("cliptest").toFile()
    private val on = ClipSettings(enabled = true)
    private var time = 1_000_000L
    private fun store(d: File = dir(), s: ClipSettings = on) = ClipHistoryStore(d, s, saveDelayMs = 10, clock = { time++ })

    private fun png(color: Int): ByteArray {
        val image = java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 8) for (y in 0 until 8) image.setRGB(x, y, color)
        return ClipboardWatch.png(image)!!
    }

    @Test
    fun changeDetectionPrimesThenSeesChanges() {
        val d = ClipChangeDetector()
        // The first look only records what was there before.
        assertFalse(d.isNew(null) { "a" })
        assertFalse(d.isNew(null) { "a" })
        assertTrue(d.isNew(null) { "b" })
        assertFalse(d.isNew(null) { "b" })
        // Set by DyskLink: not counted when seen.
        d.expect("c")
        assertFalse(d.isNew(null) { "c" })
        // An empty clipboard isn't a copy.
        assertFalse(d.isNew(null) { null })
    }

    @Test
    fun windowsSequenceSkipsReading() {
        val d = ClipChangeDetector()
        var reads = 0
        assertFalse(d.isNew(5) { reads++; "a" })
        assertFalse(d.isNew(5) { reads++; "b" })
        assertEquals(1, reads)
        assertTrue(d.isNew(6) { reads++; "b" })
        // The counter moved, but the content is what was there: nothing new.
        assertFalse(d.isNew(7) { reads++; "b" })
    }

    @Test
    fun dedupeMovesToTopAndKeepsPin() {
        val s = store()
        s.add(ClipCapture.Text("one"))
        s.add(ClipCapture.Text("two"))
        val first = s.entries.value.last()
        s.togglePin(first.id)
        s.add(ClipCapture.Text("one"))
        assertEquals(listOf("one", "two"), s.entries.value.map { it.text })
        assertTrue(s.entries.value[0].pinned)
        assertEquals(2, s.entries.value.size)
        s.add(ClipCapture.Files(listOf("/a", "/b")))
        s.add(ClipCapture.Files(listOf("/a", "/b")))
        assertEquals(3, s.entries.value.size)
        s.add(ClipCapture.Image(png(0xff0000)))
        s.add(ClipCapture.Image(png(0xff0000)))
        assertEquals(4, s.entries.value.size)
        assertEquals(ClipKind.Image, s.entries.value[0].kind)
    }

    @Test
    fun capEvictsOldestAndPinnedSurvive() {
        val s = store(s = on.copy(maxEntries = 50))
        s.add(ClipCapture.Text("keep me"))
        s.togglePin(s.entries.value[0].id)
        repeat(60) { s.add(ClipCapture.Text("item $it")) }
        val texts = s.entries.value.map { it.text }
        assertEquals(51, texts.size)
        assertTrue("keep me" in texts)
        assertTrue("item 59" in texts)
        assertFalse("item 9" in texts)
        assertTrue("item 10" in texts)
        // Unlimited keeps everything.
        val trimmed = ClipHistoryStore.trimmed(s.entries.value, ClipHistoryStore.UNLIMITED, ClipHistoryStore.UNLIMITED)
        assertEquals(0, trimmed.second.size)
        assertEquals(40, s.removedCount(10, 10))
    }

    @Test
    fun imageCapRemovesFiles() {
        val s = store(s = on.copy(maxImages = 10))
        repeat(12) { s.add(ClipCapture.Image(png(it * 1000 + 1))) }
        assertEquals(10, s.entries.value.count { it.kind == ClipKind.Image })
        assertEquals(10, s.imagesFolder.listFiles()!!.count { it.name.endsWith(".png") })
        // Too big: left out, as on the Mac.
        assertFalse(s.add(ClipCapture.Image(ByteArray(ClipHistoryStore.MAX_IMAGE_BYTES + 1))))
    }

    @Test
    fun passwordHintsAreSkipped() {
        val s = store()
        assertFalse(s.add(ClipCapture.Text("hunter", sensitive = true)))
        assertFalse(s.add(ClipCapture.Text("Tr0ub4dor&3x")))
        assertTrue(s.add(ClipCapture.Text("plain words here")))
        assertTrue(s.add(ClipCapture.Text("https://example.com/Path?a=1")))
        val off = store(s = on.copy(ignorePasswords = false))
        assertTrue(off.add(ClipCapture.Text("hunter", sensitive = true)))
        assertEquals(0, off.removedCount(200, 30))
        // Windows: a password manager's formats; Linux: KDE's hint or wl-paste's state.
        assertTrue(ClipSensitivity.windows(setOf(ClipSensitivity.EXCLUDE), null))
        assertTrue(ClipSensitivity.windows(setOf(ClipSensitivity.CAN_INCLUDE), 0))
        assertFalse(ClipSensitivity.windows(setOf(ClipSensitivity.CAN_INCLUDE), 1))
        assertFalse(ClipSensitivity.windows(emptySet(), null))
        assertTrue(ClipSensitivity.linux(listOf("text/plain", "x-kde-passwordManagerHint")))
        assertTrue(ClipSensitivity.linux(listOf("text/plain"), "sensitive"))
        assertFalse(ClipSensitivity.linux(listOf("text/plain", "UTF8_STRING"), "data"))
    }

    @Test
    fun pausedAndOffRecordNothing() {
        assertFalse(store(s = on.copy(paused = true)).add(ClipCapture.Text("x")))
        assertFalse(store(s = ClipSettings(enabled = false)).add(ClipCapture.Text("x")))
        assertFalse(store().add(ClipCapture.Text("   ")))
    }

    @Test
    fun persistenceRoundTrip() {
        val d = dir()
        val s = store(d)
        s.add(ClipCapture.Text("hello"))
        s.add(ClipCapture.Files(listOf("/home/me/a.txt")))
        s.add(ClipCapture.Image(png(0x00ff00)), source = "Pixel 8")
        s.togglePin(s.entries.value.last().id)
        s.flush()
        Thread.sleep(50)
        val again = ClipHistoryStore(d, on)
        assertEquals(s.entries.value, again.entries.value)
        assertEquals("Pixel 8", again.entries.value[0].source)
        assertTrue(again.imageFile(again.entries.value[0])!!.isFile)
        again.clearUnpinned()
        assertEquals(listOf("hello"), ClipHistoryStore(d, on).entries.value.map { it.text })
    }

    @Test
    fun searchMatchesTextAndFilesPinnedFirst() {
        val s = store()
        s.add(ClipCapture.Text("Meeting at three"))
        s.add(ClipCapture.Files(listOf("/home/me/Report.pdf")))
        s.add(ClipCapture.Text("another meeting note"))
        s.togglePin(s.entries.value.first { it.text == "Meeting at three" }.id)
        assertEquals(listOf("Meeting at three", "another meeting note"), s.matches("MEETING").map { it.text })
        assertEquals(1, s.matches("report").size)
        assertEquals(3, s.matches("  ").size)
        assertEquals("Meeting at three", s.matches("").first().text)
    }

    @Test
    fun importSkipsDuplicatesAndSortsByDate() {
        val s = store()
        s.add(ClipCapture.Text("already"))
        val (added, trimmed) = s.importClips(listOf(ImportedClip(text = "already"), ImportedClip(text = "new", date = 5L), ImportedClip(png = png(7))))
        assertEquals(2, added)
        assertEquals(0, trimmed)
        assertEquals("already", s.entries.value.first().text)
    }

    @Test
    fun pickerKeyboardModel() {
        val list = listOf(ClipEntry(id = "a", text = "a"), ClipEntry(id = "b", text = "b"), ClipEntry(id = "c", text = "c"))
        var state = PickerState()
        fun press(key: PickerKey): PickerAction = PickerKeys.reduce(state, key, list).also { state = it.first }.second
        assertEquals(PickerAction.None, press(PickerKey.Up))
        assertEquals(0, state.selection)
        press(PickerKey.Down); press(PickerKey.Down); press(PickerKey.Down)
        assertEquals(2, state.selection)
        assertEquals(PickerAction.Paste(list[2]), press(PickerKey.Enter))
        assertEquals(PickerAction.Pin(list[2]), press(PickerKey.TogglePin))
        assertEquals(PickerAction.Delete(list[2]), press(PickerKey.DeleteEntry))
        assertEquals(1, state.selection)
        press(PickerKey.Type("me"))
        assertEquals(PickerState("me", 0), state)
        press(PickerKey.Backspace)
        assertEquals("m", state.query)
        assertEquals(PickerAction.Paste(list[1]), press(PickerKey.Number(2)))
        assertEquals(PickerAction.None, press(PickerKey.Number(9)))
        assertEquals(PickerAction.Close, press(PickerKey.Escape))
        assertEquals(PickerAction.None, PickerKeys.reduce(PickerState(), PickerKey.Enter, emptyList()).second)
        press(PickerKey.PageDown)
        assertEquals(2, state.selection)
    }

    @Test
    fun hotkeyBackendSelection() {
        assertEquals(HotkeyBackend.Windows, Hotkeys.choose(Desktop.WINDOWS, false))
        assertEquals(HotkeyBackend.X11, Hotkeys.choose(Desktop.X11, true))
        assertEquals(HotkeyBackend.Portal, Hotkeys.choose(Desktop.WAYLAND, true))
        assertEquals(HotkeyBackend.Command, Hotkeys.choose(Desktop.WAYLAND, false))
        assertEquals(HotkeyCombo.DEFAULT, HotkeyCombo.parse("Ctrl+Shift+V"))
        assertEquals("CTRL+SHIFT+v", HotkeyCombo.DEFAULT.portalTrigger)
        assertNull(HotkeyCombo.parse("Shift+V"))
        assertNotNull(HotkeyCombo.parse("Super+Alt+C"))
        assertEquals("/opt/dysklink/bin/DyskLink --clipboard", Hotkeys.command("/opt/dysklink/bin/DyskLink"))
        assertEquals("dysklink --clipboard", Hotkeys.command(null))
    }

    @Test
    fun clipboardHintsByDesktop() {
        val ready = HotkeyStatus(HotkeyBackend.X11, HotkeyPhase.Ready)
        assertTrue(HostChecks.clipboardHints(ClipWatchMode.Poll, ready, "Ctrl+Shift+V", "dysklink --clipboard", true).isEmpty())
        val noWl = HostChecks.clipboardHints(ClipWatchMode.PollNoWlPaste, HotkeyStatus(HotkeyBackend.Command, HotkeyPhase.Ready), "Ctrl+Shift+V", "dysklink --clipboard", false, Distro.Debian, "GNOME")
        assertEquals("sudo apt install wl-clipboard", noWl[0].command)
        assertEquals("dysklink --clipboard", noWl[1].command)
        assertTrue("Custom Shortcuts" in noWl[1].text)
        assertTrue(noWl.last().title.startsWith("Enter copies"))
        val taken = HostChecks.clipboardHints(ClipWatchMode.Poll, HotkeyStatus(HotkeyBackend.Windows, HotkeyPhase.Taken, "Taken."), "Ctrl+Shift+V", "x", true)
        assertEquals(HostChecks.ACTION_CLIPBOARD_HOTKEY, taken.single().action?.id)
    }

    @Test
    fun singleInstanceCommands() {
        val d = dir()
        val got = java.util.concurrent.LinkedBlockingQueue<String>()
        SingleInstance(d).use { server ->
            server.listen { got.add(it) }
            assertTrue(SingleInstance.send(d, SingleInstance.CLIPBOARD))
            assertEquals(SingleInstance.CLIPBOARD, got.poll(2, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(SingleInstance.send(d, "rm"))
        }
        assertNull(SingleInstance.parse("wrong clipboard", "token"))
        assertFalse(SingleInstance.send(dir(), SingleInstance.CLIPBOARD))
    }

    @Test
    fun uriListsAndDittoFormats() {
        assertEquals(listOf("/home/me/a b.txt"), ClipboardWatch.uriListPaths("file:///home/me/a%20b.txt\r\n"))
        assertNull(ClipboardWatch.uriListPaths("just text"))
        assertEquals("hi", ClipboardImport.dittoText(mapOf("CF_UNICODETEXT" to "hi\u0000".toByteArray(Charsets.UTF_16LE))))
        assertEquals("file:///C:/Users/Me/Ditto%20x/Ditto.db?immutable=1", ClipboardImport.sqliteUri(File("C:/Users/Me/Ditto x/Ditto.db")).replace(Regex("^file:///.*?C:"), "file:///C:"))
        val clips = ClipboardImport.parseCopyQ("{\"t\":\"one\"}\n{\"p\":\"${java.util.Base64.getEncoder().encodeToString(png(3))}\"}\nnoise")
        assertEquals("one", clips[0].text)
        assertNotNull(clips[1].png)
        // A 2x1 24-bit DIB comes back as a picture.
        val dib = java.nio.ByteBuffer.allocate(40 + 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(40).putInt(2).putInt(1).putShort(1).putShort(24).putInt(0).putInt(8).putInt(0).putInt(0).putInt(0).putInt(0)
            .put(byteArrayOf(0, 0, -1, 0, -1, 0, 0, 0)).array()
        assertNotNull(ClipboardImport.dittoImage(mapOf("CF_DIB" to dib)))
    }
}
