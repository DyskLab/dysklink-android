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
}
