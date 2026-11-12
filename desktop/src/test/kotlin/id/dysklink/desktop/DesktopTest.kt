package id.dysklink.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopTest {
    @Test
    fun receivedFilesNeverOverwrite() {
        val dir = Files.createTempDirectory("dysk-dl").toFile()
        assertEquals("photo.jpg", DownloadSinks.freeName(dir, "photo.jpg").name)
        dir.resolve("photo.jpg").writeText("a")
        assertEquals("photo (1).jpg", DownloadSinks.freeName(dir, "photo.jpg").name)
        dir.resolve("photo (1).jpg").writeText("b")
        assertEquals("photo (2).jpg", DownloadSinks.freeName(dir, "photo.jpg").name)
        dir.resolve("README").writeText("c")
        assertEquals("README (1)", DownloadSinks.freeName(dir, "README").name)
    }

    @Test
    fun aFileIsOnlyThereOnceComplete() {
        val dir = Files.createTempDirectory("dysk-dl").toFile()
        val sink = DownloadSinks { dir }.open("report.pdf", 4)
        sink.write("abcd".toByteArray())
        assertTrue(dir.listFiles()!!.none { it.name == "report.pdf" })
        val path = sink.commit()
        assertEquals("abcd", java.io.File(path).readText())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
        val aborted = DownloadSinks { dir }.open("half.zip", 10)
        aborted.write(byteArrayOf(1, 2))
        aborted.abort()
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") || it.name == "half.zip" })
    }

    @Test
    fun linuxAutostartEntryHidesInTheTray() {
        val entry = Autostart.entry("/opt/dysklink/bin/DyskLink")
        assertTrue(entry.contains("Exec=\"/opt/dysklink/bin/DyskLink\" --hidden"))
        assertTrue(entry.startsWith("[Desktop Entry]"))
    }

    @Test
    fun aMacDuringDevelopmentCallsItselfLinux() {
        if (Platform.isMac && System.getenv("DYSKLINK_PLATFORM") == null) assertEquals("linux", Platform.protocolName)
        assertTrue(Platform.protocolName in setOf("windows", "linux"))
    }

    @Test
    fun phoneShortcutsUseCtrlOnAPc() {
        val (key, mods) = Keys.parse("cmd+shift+T")!!
        assertEquals(java.awt.event.KeyEvent.VK_T, key)
        assertEquals(listOf(java.awt.event.KeyEvent.VK_CONTROL, java.awt.event.KeyEvent.VK_SHIFT), mods)
        assertEquals(java.awt.event.KeyEvent.VK_F5 to emptyList<Int>(), Keys.parse("f5"))
        assertEquals(null, Keys.parse("cmd+launchmissiles"))
        assertEquals(null, Keys.parse("hyper+a"))
    }

    @Test
    fun typingKnowsWhichCharactersNeedShift() {
        assertEquals(java.awt.event.KeyEvent.VK_A to true, Keys.forChar('A'))
        assertEquals(java.awt.event.KeyEvent.VK_1 to true, Keys.forChar('!'))
        assertEquals(java.awt.event.KeyEvent.VK_BACK_SLASH to false, Keys.forChar('\\'))
        assertEquals(java.awt.event.KeyEvent.VK_QUOTE to true, Keys.forChar('"'))
        assertEquals(null, Keys.forChar('é'))
    }

    @Test
    fun windowsReservedNamesGetAPrefix() {
        assertEquals("_CON", DownloadSinks.windowsSafe("CON"))
        assertEquals("_nul.txt", DownloadSinks.windowsSafe("nul.txt"))
        assertEquals("_COM1.jpg", DownloadSinks.windowsSafe("COM1.jpg"))
        assertEquals("_lpt9.tar.gz", DownloadSinks.windowsSafe("lpt9.tar.gz"))
        assertEquals("_AUX .txt", DownloadSinks.windowsSafe("AUX .txt"))
        assertEquals("CONSOLE.txt", DownloadSinks.windowsSafe("CONSOLE.txt"))
        assertEquals("COM10.txt", DownloadSinks.windowsSafe("COM10.txt"))
        assertEquals("photo.jpg", DownloadSinks.windowsSafe("photo.jpg"))
        val dir = Files.createTempDirectory("dysk-dl").toFile()
        val sink = DownloadSinks { dir }.open("CON.txt", 1)
        sink.write(byteArrayOf(65))
        assertEquals("_CON.txt", java.io.File(sink.commit()).name)
    }

    @Test
    fun aReceivedFileNeverReplacesOneThatAppearedMeanwhile() {
        val dir = Files.createTempDirectory("dysk-dl").toFile()
        dir.resolve("same.txt").writeText("first")
        val temp = java.io.File.createTempFile(".dysklink-", ".part", dir).apply { writeText("second") }
        val placed = DownloadSinks.place(temp, dir, "same.txt")
        assertEquals("same (1).txt", placed.name)
        assertEquals("first", dir.resolve("same.txt").readText())
        assertEquals("second", placed.readText())
        assertTrue(!temp.exists())
    }

    @Test
    fun windowsAutostartQuotesAPathWithSpaces() {
        val path = "C:\\Program Files\\DyskLink\\DyskLink.exe"
        assertEquals("\"C:\\Program Files\\DyskLink\\DyskLink.exe\" --hidden", Autostart.command(path))
        val reg = Autostart.registryFile(path)
        assertTrue(reg.startsWith("Windows Registry Editor Version 5.00\r\n"))
        assertTrue(reg.contains("[HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run]"))
        // In a .reg file, quotes and backslashes inside the value are escaped.
        assertTrue(reg.contains("\"DyskLink\"=\"\\\"C:\\\\Program Files\\\\DyskLink\\\\DyskLink.exe\\\" --hidden\""), reg)
    }

    @Test
    fun aPictureFromAnotherComputerIsKnownByItsPixelsSoItIsNotSentBack() {
        val original = java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until 40) for (y in 0 until 30) original.setRGB(x, y, (0xFF shl 24) or (x * 6 shl 16) or (y * 8 shl 8) or 77)
        // What arrives: PNG bytes from the other computer.
        val png = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(original, "png", it) }.toByteArray()
        val decoded = javax.imageio.ImageIO.read(png.inputStream())
        // What the watcher reads back: the same pixels, stored another way.
        val copy = java.awt.image.BufferedImage(40, 30, java.awt.image.BufferedImage.TYPE_3BYTE_BGR)
        copy.createGraphics().apply { drawImage(decoded, 0, 0, null); dispose() }
        assertEquals(Clipboard.fingerprint(decoded), Clipboard.fingerprint(original))
        assertEquals(Clipboard.fingerprint(decoded), Clipboard.fingerprint(copy))
        // A changed pixel is a new picture.
        copy.setRGB(3, 3, 0)
        assertTrue(Clipboard.fingerprint(copy) != Clipboard.fingerprint(original))
        assertTrue(Clipboard.fingerprint(original).startsWith("40x30:"))
    }

    @Test
    fun aReadThatOverlapsAChangeIsDropped() {
        val changes = ClipGeneration()
        var last = "old"
        val seen = changes.current()
        // The phone's text lands while the watcher is still reading the old clipboard.
        changes.change { last = "from phone" }
        assertEquals(null, changes.ifUnchanged(seen) { last = "old"; true })
        assertEquals("from phone", last)
        // The next look starts after the change and goes through.
        val next = changes.current()
        assertEquals(true, changes.ifUnchanged(next) { true })
    }

    @Test
    fun quickFingerprintSeesSizeAndSampledPixels() {
        val image = java.awt.image.BufferedImage(400, 300, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val before = Clipboard.quickFingerprint(image)
        assertEquals(before, Clipboard.quickFingerprint(image))
        // The first pixel is always sampled.
        image.setRGB(0, 0, 0xFF336699.toInt())
        kotlin.test.assertNotEquals(before, Clipboard.quickFingerprint(image))
        val bigger = java.awt.image.BufferedImage(401, 300, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        assertTrue(Clipboard.quickFingerprint(bigger).startsWith("401x300:"))
        // A tiny picture has fewer pixels than samples and is still read fully.
        val tiny = java.awt.image.BufferedImage(3, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val tinyBefore = Clipboard.quickFingerprint(tiny)
        tiny.setRGB(2, 1, -1)
        kotlin.test.assertNotEquals(tinyBefore, Clipboard.quickFingerprint(tiny))
    }

    private val windows = TypingTools(Desktop.WINDOWS, unicodeInput = true, clipboard = true, robot = true)
    private val x11Bare = TypingTools(Desktop.X11, clipboard = true, robot = true)

    @Test
    fun windowsTypesEveryCharacterAsUnicode() {
        // The same steps whatever the layout: nothing in the plan depends on key positions.
        for (text in listOf("hello", "@#{}|~", "é ñ ü ß", "日本語", "Grüße 😀")) {
            assertEquals(listOf<TypeStep>(TypeStep.Text(TypeMethod.UNICODE_INPUT, text)), Typing.plan(text, windows))
        }
    }

    @Test
    fun enterAndTabArePressedAsKeys() {
        assertEquals(
            listOf(TypeStep.Text(TypeMethod.UNICODE_INPUT, "a"), TypeStep.Key("return"), TypeStep.Text(TypeMethod.UNICODE_INPUT, "b"),
                TypeStep.Key("tab"), TypeStep.Text(TypeMethod.UNICODE_INPUT, "c"), TypeStep.Key("return")),
            Typing.plan("a\r\nb\tc\n", windows),
        )
        // Other control characters are dropped.
        assertEquals(listOf<TypeStep>(TypeStep.Text(TypeMethod.UNICODE_INPUT, "ab")), Typing.plan("a\u0007b", windows))
    }

    @Test
    fun emojiStaysOneCharacter() {
        val face = "\uD83D\uDE00"
        assertEquals(listOf<TypeStep>(TypeStep.Text(TypeMethod.XDOTOOL, "x$face")), Typing.plan("x$face", x11Bare.copy(xdotool = true)))
        // A lone half of a pair is never typed.
        assertEquals(listOf<TypeStep>(TypeStep.Text(TypeMethod.UNICODE_INPUT, "ab")), Typing.plan("a\uD83Db", windows))
        // SendInput gets both halves, each down and up, as Unicode with no virtual key.
        val bytes = java.nio.ByteBuffer.wrap(WinInput.unicodeEvents(face, 8)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(4 * 40, bytes.capacity())
        val scans = (0 until 4).map { bytes.getShort(it * 40 + 10).toInt() and 0xffff }
        assertEquals(listOf(0xD83D, 0xD83D, 0xDE00, 0xDE00), scans)
        val flags = (0 until 4).map { bytes.getInt(it * 40 + 12) }
        assertEquals(listOf(4, 6, 4, 6), flags)
        assertTrue((0 until 4).all { bytes.getInt(it * 40) == WinInput.INPUT_KEYBOARD && bytes.getShort(it * 40 + 8).toInt() == 0 })
        // 32-bit Windows packs INPUT into 28 bytes, with the key data right after the type.
        val small = java.nio.ByteBuffer.wrap(WinInput.unicodeEvents("é", 4)).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        assertEquals(2 * 28, small.capacity())
        assertEquals(0xE9, small.getShort(6).toInt())
    }

    @Test
    fun linuxPrefersXdotoolThenRemapThenPaste() {
        assertEquals(TypeMethod.XDOTOOL, Typing.methodFor('é'.code, x11Bare.copy(xdotool = true, x11Remap = true)))
        assertEquals(TypeMethod.X11_REMAP, Typing.methodFor('é'.code, x11Bare.copy(x11Remap = true)))
        // Without either, even plain letters are pasted: Robot's US keys would be wrong on another layout.
        assertEquals(listOf<TypeStep>(TypeStep.Text(TypeMethod.PASTE, "ab\ncé")), Typing.plan("ab\ncé", x11Bare))
        assertEquals(listOf<TypeStep>(TypeStep.Key("return"), TypeStep.Text(TypeMethod.PASTE, "a")), Typing.plan("\na", x11Bare))
        // Robot's US keys only as the very last way, and only for what they can type.
        val robotOnly = TypingTools(Desktop.X11, robot = true)
        assertEquals(listOf<TypeStep>(TypeStep.Text(TypeMethod.US_KEYS, "ab")), Typing.plan("aéb", robotOnly))
        assertEquals(emptyList(), Typing.plan("é", TypingTools(Desktop.WAYLAND, clipboard = true, robot = true)))
    }

    @Test
    fun aFailedWayMovesToTheNext() {
        val noUnicode = windows.without(TypeMethod.UNICODE_INPUT)
        assertEquals(listOf<TypeStep>(TypeStep.Text(TypeMethod.PASTE, "é")), Typing.plan("é", noUnicode))
        assertEquals(listOf<TypeStep>(TypeStep.Text(TypeMethod.US_KEYS, "a")), Typing.plan("aé", noUnicode.without(TypeMethod.PASTE)))
    }

    @Test
    fun desktopIsToldApart() {
        assertEquals(Desktop.WINDOWS, Typing.desktop(isWindows = true, isMac = false) { null })
        assertEquals(Desktop.WAYLAND, Typing.desktop(false, false) { if (it == "XDG_SESSION_TYPE") "wayland" else null })
        assertEquals(Desktop.X11, Typing.desktop(false, false) { if (it == "XDG_SESSION_TYPE") "x11" else null })
    }

    @Test
    fun x11KeysymsAndSpareKeys() {
        assertEquals(0x61L, X11Keys.keysymFor('a'.code))
        assertEquals(0xe9L, X11Keys.keysymFor('é'.code))
        assertEquals(0x010020acL, X11Keys.keysymFor('€'.code))
        assertEquals(0x0101f600L, X11Keys.keysymFor(0x1F600))
        // Key codes 8..11, two keysyms each: 9 and 11 are unused.
        val mapping = longArrayOf(0x61, 0x41, 0, 0, 0x62, 0, 0, 0)
        assertEquals(listOf(9, 11), X11Keys.spareKeycodes(mapping, 2, 8))
    }

    private val x11All = TypingTools(Desktop.X11, xdotool = true, x11Remap = true, clipboard = true, robot = true)

    @Test
    fun aTimedOutXdotoolIsntTypedAgainAnotherWay() {
        val run = TypingRun(x11All)
        val typed = mutableListOf<String>()
        val ok = run.type("hello\nworld", key = { typed += "<$it>" }) { step ->
            typed += "${step.method}:${step.text}"
            if (step.text == "hello") Typed.Stopped else Typed.Done
        }
        assertEquals(false, ok)
        // Not retyped by X11 remap, and xdotool stays for next time.
        assertEquals(listOf("XDOTOOL:hello", "<return>", "XDOTOOL:world"), typed)
        assertEquals(x11All, run.tools)
    }

    @Test
    fun aWayThatFailsPartWaySendsOnlyTheRestTheNextWay() {
        val run = TypingRun(x11All)
        val typed = mutableListOf<String>()
        run.type("abcdef\tg", key = { typed += "<$it>" }) { step ->
            typed += "${step.method}:${step.text}"
            when (step.method) {
                TypeMethod.XDOTOOL -> Typed.Nothing
                TypeMethod.X11_REMAP -> if (step.text == "abcdef") Typed.Partly(2) else Typed.Done
                else -> Typed.Done
            }
        }
        // xdotool took nothing: all of it goes by X11 remap, which stops after "ab": only "cdef" is pasted.
        assertEquals(listOf("XDOTOOL:abcdef", "X11_REMAP:abcdef", "PASTE:cdef\tg"), typed)
        assertEquals(x11All.copy(xdotool = false, x11Remap = false), run.tools)
    }

    @Test
    fun aClipboardHiccupDoesntStopPastingForGood() {
        val run = TypingRun(TypingTools(Desktop.X11, clipboard = true, robot = true))
        var fail = true
        val typed = mutableListOf<String>()
        run.type("é", key = {}) { step ->
            typed += "${step.method}:${step.text}"
            if (step.method == TypeMethod.PASTE && fail) Typed.Nothing else Typed.Done
        }
        // Nothing else can type "é" here, so it's lost this time, but the next text is pasted again.
        assertEquals(listOf("PASTE:é"), typed)
        assertTrue(run.tools.clipboard)
        fail = false
        run.type("ü", key = {}) { step -> typed += "${step.method}:${step.text}"; Typed.Done }
        assertEquals(listOf("PASTE:é", "PASTE:ü"), typed)
    }

    /** A clipboard in memory whose next set can be made to fail. */
    private class FlakyClipboard : java.awt.datatransfer.Clipboard("test") {
        @Volatile var failSets = 0
        override fun setContents(contents: java.awt.datatransfer.Transferable?, owner: java.awt.datatransfer.ClipboardOwner?) {
            if (failSets > 0) { failSets--; throw IllegalStateException("Busy") }
            super.setContents(contents, owner)
        }
        fun text(): String? = getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
    }

    @Test
    fun aFailedPasteStillPutsTheClipboardBack() {
        val system = FlakyClipboard()
        system.setContents(java.awt.datatransfer.StringSelection("mine"), null)
        val clipboard = Clipboard(onCopied = {}, system = { system }, restoreAfterMs = 100)
        assertTrue(clipboard.pasteThrough("one") {})
        system.failSets = 1
        assertEquals(false, clipboard.pasteThrough("two") {})
        Thread.sleep(400)
        assertEquals("mine", system.text())
        // Copied later: a paste then puts back this, not the old one.
        system.setContents(java.awt.datatransfer.StringSelection("newer"), null)
        assertTrue(clipboard.pasteThrough("three") {})
        assertEquals("three", system.text())
        Thread.sleep(400)
        assertEquals("newer", system.text())
    }

    @Test
    fun xdotoolGetsLongerForLongerText() {
        assertEquals(10, XdotoolTyper.timeoutSeconds("short"))
        assertEquals(30, XdotoolTyper.timeoutSeconds("x".repeat(1000)))
    }

    @Test
    fun clicksLandWhereThePictureIs() {
        val math = id.dysklink.desktop.ui.VideoMath
        // A 100×200 portrait picture in a 400×400 view: 200 tall... fitted to 200×400, centered at x 100..300.
        assertEquals(listOf(100f, 0f, 200f, 400f), math.fit(400f, 400f, 100f, 200f, 0).toList())
        assertEquals(0.5f to 0.5f, math.toPicture(200f, 200f, 400f, 400f, 100f, 200f, 0))
        assertEquals(0f to 0f, math.toPicture(100f, 0f, 400f, 400f, 100f, 200f, 0))
        assertEquals(null, math.toPicture(50f, 200f, 400f, 400f, 100f, 200f, 0), "Beside the picture")
        assertEquals(0f to 0.5f, math.toPicture(50f, 200f, 400f, 400f, 100f, 200f, 0, clamp = true))
        // Turned 90° clockwise it's 400×200: the picture's top-left corner is now at the top right.
        val (x, y) = math.toPicture(399f, 101f, 400f, 400f, 100f, 200f, 90)!!
        assertTrue(x < 0.02f && y < 0.02f, "($x, $y)")
        val (x2, y2) = math.toPicture(1f, 101f, 400f, 400f, 100f, 200f, 270)!!
        assertTrue(x2 > 0.98f && y2 < 0.02f, "Turned the other way, top-left shows the top-right: ($x2, $y2)")
        assertEquals(1f to 1f, math.toPicture(100f, 0f, 400f, 400f, 100f, 200f, 180))
        // Mirrored, left is right.
        assertEquals(1f to 0f, math.toPicture(100f, 0f, 400f, 400f, 100f, 200f, 0, mirror = true))
    }

    @Test
    fun smsGroupIntoConversations() {
        val items = listOf(
            id.phonelink.android.core.SmsItem(1, 7, "+6281100", "a", 1, false),
            id.phonelink.android.core.SmsItem(2, 8, "BANK", "b", 5, false),
            id.phonelink.android.core.SmsItem(3, 7, "+6281100", "c", 9, true),
        )
        val threads = id.dysklink.desktop.ui.smsThreads(items)
        assertEquals(listOf(7L, 8L), threads.map { it.id })
        assertEquals("c", threads[0].latest.body)
        assertTrue(id.dysklink.desktop.ui.sameNumber("+62 812-3456-7890", "081234567890"))
        assertTrue(!id.dysklink.desktop.ui.sameNumber("+62 812 3456 7890", "+62 812 3456 7891"))
        assertTrue(id.dysklink.desktop.ui.sameNumber("BANK", "bank"))
    }
}
