package id.dysklink.desktop

import id.phonelink.android.core.FileOrigin
import java.io.File
import java.nio.file.Files
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

/** The deck's Mac shortcuts on a PC, document scans, and the update check. */
class PcParityDesktopTest {
    // Deck shortcuts

    @Test
    fun macShortcutsBecomeThePcsOwn() {
        fun win(shortcut: String) = MacShortcuts.forPc(shortcut, windows = true)
        fun linux(shortcut: String) = MacShortcuts.forPc(shortcut, windows = false)
        // Command is Ctrl, Option is Alt, fn is dropped.
        assertEquals("ctrl+c", win("cmd+c"))
        assertEquals("ctrl+shift+z", win("shift+cmd+z"))
        assertEquals("ctrl+alt+t", linux("opt+cmd+t"))
        assertEquals("ctrl+c", win("ctrl+cmd+c"), "Command and Control together are one Ctrl")
        assertEquals("f5", win("fn+f5"))
        assertEquals("return", win("return"))
        // Mac-only shortcuts get the PC's match.
        assertEquals("win", win("cmd+space"))
        assertEquals("win+l", win("ctrl+cmd+q"))
        assertEquals("win+l", linux("ctrl+cmd+q"))
        assertEquals("win+shift+s", win("shift+cmd+4"))
        assertEquals("shift+printscreen", linux("shift+cmd+4"))
        assertEquals("alt+f4", win("cmd+q"))
        assertEquals("alt+tab", win("cmd+tab"))
        assertEquals("f11", linux("ctrl+cmd+f"))
        // Names are taken as the phone may write them, in any order.
        assertEquals("win+l", win("Command+Control+Q"))
        // Nothing to do here, or not a shortcut.
        assertNull(win("opt+space"))
        assertNull(win("hyper+a"))
        assertNull(win(""))
        // Each one parses into keys the PC can press, and a lone Windows key too.
        for (shortcut in listOf("cmd+c", "cmd+space", "ctrl+cmd+q", "shift+cmd+3", "shift+cmd+4", "cmd+q", "cmd+tab", "opt+cmd+escape")) {
            for (windows in listOf(true, false)) {
                val pc = assertNotNull(MacShortcuts.forPc(shortcut, windows))
                assertNotNull(Keys.parse(pc), "$shortcut as $pc")
            }
        }
    }

    // Scans

    @Test
    fun scansGoToTheScansFolderAndAreAnnounced() {
        val downloads = Files.createTempDirectory("dysk-dl").toFile()
        val scans = File(Files.createTempDirectory("dysk-docs").toFile(), "DyskLink/Scans")
        val announced = mutableListOf<Pair<File, String>>()
        val sinks = DownloadSinks({ scans }, { file, origin -> announced += file to origin.peerName }) { downloads }

        val scan = sinks.open("Scan 1.pdf", 4, FileOrigin("phone", "Pixel", "scan"))
        scan.write("%PDF".toByteArray())
        assertTrue(announced.isEmpty(), "Not before it's whole")
        val path = File(scan.commit())
        assertEquals(scans, path.parentFile)
        assertEquals(listOf(path to "Pixel"), announced)

        // Anything else, and a scan with no scans folder, go to Downloads.
        sinks.open("photo.jpg", 1, FileOrigin("phone", "Pixel", "backup")).apply { write(byteArrayOf(1)) }.commit().let { assertEquals(downloads, File(it).parentFile) }
        sinks.open("plain.txt", 1).apply { write(byteArrayOf(1)) }.commit().let { assertEquals(downloads, File(it).parentFile) }
        DownloadSinks { downloads }.open("Scan 2.pdf", 1, FileOrigin("phone", "Pixel", "scan")).apply { write(byteArrayOf(1)) }.commit()
            .let { assertEquals(downloads, File(it).parentFile) }
        assertEquals(1, announced.size)
    }

    // Updates

    private fun release(tag: String, vararg assets: String) = """
        {"tag_name":"$tag","html_url":"https://github.com/DyskLab/dysklink-desktop/releases/tag/$tag","draft":false,
         "assets":[${assets.joinToString(",") { """{"name":"$it","browser_download_url":"https://github.com/DyskLab/dysklink-desktop/releases/download/$tag/$it","size":1}""" }}],
         "body":"Notes","author":{"login":"x"}}
    """.trimIndent()

    @Test
    fun tagsAndVersionsAreReadAsTheReleasesWriteThem() {
        assertEquals("1.2.0", DesktopUpdates.versionOf("desktop-v1.2.0"))
        assertEquals("1.2.0", DesktopUpdates.versionOf("v1.2.0"))
        assertEquals("1.2.0", DesktopUpdates.versionOf("1.2.0"))
        assertNull(DesktopUpdates.versionOf("nightly"))
        val parsed = assertNotNull(DesktopUpdates.parse(release("desktop-v1.3.0", "DyskLink-1.3.0.msi", "dysklink_1.3.0_amd64.deb")))
        assertEquals("1.3.0", parsed.version)
        assertEquals(2, parsed.assets.size)
        assertNull(DesktopUpdates.parse("""{"tag_name":"v9.0.0","draft":true}"""), "A draft isn't a release")
        assertNull(DesktopUpdates.parse("not json"))
        assertTrue(DesktopUpdates.isNewer(parsed, "1.2.9"))
        assertFalse(DesktopUpdates.isNewer(parsed, "1.3.0"))
        assertFalse(DesktopUpdates.isNewer(parsed, "1.10.0"), "Numbers, not text")
        assertFalse(DesktopUpdates.isNewer(parsed, "dev"), "A build without a version never asks")
    }

    @Test
    fun theInstallerSuitsTheSystem() {
        val all = assertNotNull(DesktopUpdates.parse(release("v2.0.0", "DyskLink-2.0.0.exe", "DyskLink-2.0.0.msi", "dysklink-2.0.0.x86_64.rpm", "dysklink_2.0.0_amd64.deb", "DyskLink-2.0.0-x86_64.AppImage")))
        assertEquals("DyskLink-2.0.0.msi", DesktopUpdates.asset(all, true, LinuxFamily.Other)?.name)
        assertEquals("dysklink_2.0.0_amd64.deb", DesktopUpdates.asset(all, false, LinuxFamily.Debian)?.name)
        assertEquals("dysklink-2.0.0.x86_64.rpm", DesktopUpdates.asset(all, false, LinuxFamily.RedHat)?.name)
        assertEquals("DyskLink-2.0.0-x86_64.AppImage", DesktopUpdates.asset(all, false, LinuxFamily.Other)?.name)
        val exeOnly = assertNotNull(DesktopUpdates.parse(release("v2.0.0", "DyskLink-2.0.0.exe", "DyskLink-2.0.0-x86_64.AppImage")))
        assertEquals("DyskLink-2.0.0.exe", DesktopUpdates.asset(exeOnly, true, LinuxFamily.Other)?.name)
        assertEquals("DyskLink-2.0.0-x86_64.AppImage", DesktopUpdates.asset(exeOnly, false, LinuxFamily.Debian)?.name, "No .deb: the AppImage")
        assertNull(DesktopUpdates.asset(assertNotNull(DesktopUpdates.parse(release("v2.0.0", "notes.txt"))), true, LinuxFamily.Other), "None: the release page opens")

        assertEquals(LinuxFamily.Debian, DesktopUpdates.family("NAME=\"Ubuntu\"\nID=ubuntu\nID_LIKE=debian\n"))
        assertEquals(LinuxFamily.Debian, DesktopUpdates.family("ID=linuxmint\nID_LIKE=\"ubuntu debian\""))
        assertEquals(LinuxFamily.RedHat, DesktopUpdates.family("ID=fedora\n"))
        assertEquals(LinuxFamily.RedHat, DesktopUpdates.family("ID=\"rocky\"\nID_LIKE=\"rhel centos fedora\""))
        assertEquals(LinuxFamily.RedHat, DesktopUpdates.family("ID=\"opensuse-tumbleweed\"\nID_LIKE=\"opensuse suse\""))
        assertEquals(LinuxFamily.Other, DesktopUpdates.family("ID=arch\n"))
        assertEquals(LinuxFamily.Other, DesktopUpdates.family(""))
    }

    @Test
    fun checksAreQuietAboutNoReleaseAndRememberASkip() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val settings = Settings(File(Files.createTempDirectory("dysk-set").toFile(), "settings.properties"))
            var answer = 404 to """{"message":"Not Found"}"""
            val asked = mutableListOf<String>()
            var now = 1_000_000L
            val updater = Updater(settings, scope, current = "1.0.0", fetch = { url -> asked += url; answer }, clock = { now })

            // No repository or release yet: nothing shown, no error, counted as a check.
            updater.check(manual = false)
            assertEquals(listOf(DesktopUpdates.LATEST_URL), asked)
            assertNull(updater.state.value.available)
            assertNull(updater.state.value.message, "Quiet on the daily check")
            assertEquals(now, settings.string("update.checked")?.toLong())
            updater.check(manual = true)
            assertEquals("No DyskLink for Windows and Linux has been released on GitHub yet.", updater.state.value.message)

            // A newer one: shown, then skipped, then not shown again by the daily check.
            answer = 200 to release("desktop-v1.1.0", "DyskLink-1.1.0.msi")
            updater.check(manual = false)
            assertEquals("1.1.0", updater.state.value.available?.version)
            updater.skip()
            assertNull(updater.state.value.available)
            assertEquals("1.1.0", settings.string("update.skip"))
            updater.check(manual = false)
            assertNull(updater.state.value.available, "Skipped")
            // A later one shows again; so does the skipped one when asked for by hand.
            updater.check(manual = true)
            assertEquals("1.1.0", updater.state.value.available?.version)
            answer = 200 to release("v1.2.0")
            updater.check(manual = false)
            assertEquals("1.2.0", updater.state.value.available?.version)

            // The same version, a failure: nothing shown; a failure isn't counted as a check.
            answer = 200 to release("v1.0.0")
            updater.check(manual = true)
            assertNull(updater.state.value.available)
            assertEquals("DyskLink 1.0.0 is the newest version.", updater.state.value.message)
            val checked = settings.string("update.checked")
            now += 5_000
            answer = 403 to "{}"
            updater.check(manual = true)
            assertEquals(checked, settings.string("update.checked"))
            assertFalse(updater.state.value.checking)
            assertNotNull(updater.state.value.message)
            val failing = Updater(settings, scope, current = "1.0.0", fetch = { error("offline") }, clock = { now })
            failing.check(manual = false)
            assertNull(failing.state.value.message, "Quiet on the daily check")
        } finally {
            scope.cancel()
        }
    }
}
