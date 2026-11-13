package id.dysklink.desktop

import id.dysklink.desktop.AdbLocator.Os
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

class UsbBridgeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @AfterTest
    fun close() = scope.cancel()

    @Test
    fun readsAdbDevicesWithNoiseAndWindowsLineEnds() {
        val unix = "* daemon not running; starting now at tcp:5037\n* daemon started successfully\nList of devices attached\n" +
            "R58N91ABCDE        device usb:1-1 product:demo model:Demo_Phone device:demo transport_id:3\n" +
            "ZY22 unauthorized usb:1-2 transport_id:4\n\n"
        val devices = UsbBridge.parseDevices(unix)
        assertEquals(listOf(AdbDevice("R58N91ABCDE", "device", "Demo Phone"), AdbDevice("ZY22", "unauthorized", "")), devices)
        val windows = "List of devices attached\r\n" +
            "emulator-5554\tdevice product:sdk model:Pixel_8 device:husky transport_id:1\r\n" +
            "0A1B2C\toffline transport_id:2\r\n\r\n"
        val parsed = UsbBridge.parseDevices(windows)
        assertEquals(listOf("device", "offline"), parsed.map { it.state }, "No \\r may stick to the state")
        assertEquals("Pixel 8", parsed[0].model)
        assertTrue(UsbBridge.parseDevices("List of devices attached\r\n\r\n").isEmpty())
        assertTrue(UsbBridge.parseDevices("adb server version (41) doesn't match this client (39); killing...\n").isEmpty())
    }

    @Test
    fun findsTheReversePortOnlyForThisPcsPort() {
        assertTrue(UsbBridge.hasReverse("UsbFfs tcp:47823 tcp:47821\n", 47821))
        assertTrue(UsbBridge.hasReverse("host-19 tcp:47823 tcp:47821\r\n", 47821))
        assertFalse(UsbBridge.hasReverse("UsbFfs tcp:47823 tcp:50000\r\n", 47821))
        // A longer port that merely starts with this one is someone else's.
        assertFalse(UsbBridge.hasReverse("UsbFfs tcp:47823 tcp:478210\n", 47821))
        assertFalse(UsbBridge.hasReverse("", 47821))
    }

    @Test
    fun pollsQuicklyOnlyShortlyAfterAPhone() {
        assertEquals(4_000L, UsbBridge.interval(10_000))
        assertEquals(4_000L, UsbBridge.interval(119_000))
        assertEquals(30_000L, UsbBridge.interval(120_000))
        assertEquals(30_000L, UsbBridge.interval(3_600_000))
    }

    @Test
    fun looksForAdbWhereWindowsKeepsIt() {
        val env = mapOf(
            "ANDROID_HOME" to "D:\\Sdk",
            "LOCALAPPDATA" to "C:\\Users\\ayu\\AppData\\Local",
            "PATH" to "C:\\Windows\\system32;\"C:\\Tools\\platform-tools\";",
        )
        val data = File("C:\\Users\\ayu\\AppData\\Roaming\\DyskLink")
        val list = AdbLocator.candidates(Os.Windows, env::get, "C:\\Users\\ayu", data)
        assertEquals(
            listOf(
                "D:\\Sdk\\platform-tools\\adb.exe",
                "C:\\Users\\ayu\\AppData\\Local\\Android\\Sdk\\platform-tools\\adb.exe",
                "C:\\Windows\\system32\\adb.exe",
                "C:\\Tools\\platform-tools\\adb.exe",
                "${data.path}\\platform-tools\\adb.exe",
            ),
            list,
        )
        fun find(vararg present: String) = AdbLocator.find(Os.Windows, env::get, "C:\\Users\\ayu", data) { it in present }
        assertEquals("D:\\Sdk\\platform-tools\\adb.exe", find("D:\\Sdk\\platform-tools\\adb.exe", "C:\\Tools\\platform-tools\\adb.exe"))
        assertEquals("C:\\Tools\\platform-tools\\adb.exe", find("C:\\Tools\\platform-tools\\adb.exe"))
        assertEquals("${data.path}\\platform-tools\\adb.exe", find("${data.path}\\platform-tools\\adb.exe"))
        assertNull(find())
    }

    @Test
    fun looksForAdbWhereLinuxKeepsIt() {
        val env = mapOf("ANDROID_SDK_ROOT" to "/opt/android-sdk/", "PATH" to "/home/ayu/bin:/usr/local/bin")
        val data = File("/home/ayu/.config/dysklink")
        val list = AdbLocator.candidates(Os.Linux, env::get, "/home/ayu", data)
        assertEquals(
            listOf(
                "/opt/android-sdk/platform-tools/adb",
                "/home/ayu/Android/Sdk/platform-tools/adb",
                "/usr/bin/adb",
                "/usr/lib/android-sdk/platform-tools/adb",
                "/home/ayu/bin/adb",
                "/usr/local/bin/adb",
                "/home/ayu/.config/dysklink/platform-tools/adb",
            ),
            list,
        )
        assertEquals("/usr/bin/adb", AdbLocator.find(Os.Linux, env::get, "/home/ayu", data) { it == "/usr/bin/adb" || it.endsWith("dysklink/platform-tools/adb") })
        assertEquals("/home/ayu/.config/dysklink/platform-tools/adb", AdbLocator.find(Os.Linux, { null }, "/home/ayu", data) { it.startsWith("/home/ayu/.config") })
        // A check that throws (no permission) counts as not there.
        assertNull(AdbLocator.find(Os.Linux, { null }, "/home/ayu", data) { throw SecurityException("no") })
    }

    private fun zip(vararg entries: Pair<String, String?>): File {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                text?.let { zip.write(it.toByteArray()) }
                zip.closeEntry()
            }
        }
        return File.createTempFile("tools", ".zip").apply { writeBytes(out.toByteArray()); deleteOnExit() }
    }

    @Test
    fun unpacksPlatformToolsAndMakesAdbRunnable() {
        val data = Files.createTempDirectory("dysk-usb").toFile()
        // An older download is replaced whole.
        File(data, "platform-tools/old-file").apply { parentFile.mkdirs(); writeText("old") }
        val file = zip("platform-tools/" to null, "platform-tools/adb" to "#!/bin/sh\necho adb", "platform-tools/lib64/libc++.so" to "lib", "platform-tools/NOTICE.txt" to "n")
        val adb = AdbDownload.install(file, data, windows = false)
        assertEquals(File(data, "platform-tools/adb"), adb)
        assertTrue(adb.canExecute(), "adb must be executable on Linux")
        assertEquals("lib", File(data, "platform-tools/lib64/libc++.so").readText())
        assertFalse(File(data, "platform-tools/old-file").exists())
        assertFalse(File(data, "platform-tools-unpacking").exists())

        val windows = zip("platform-tools/adb.exe" to "MZ", "platform-tools/AdbWinApi.dll" to "dll")
        assertEquals(File(data, "platform-tools/adb.exe"), AdbDownload.install(windows, data, windows = true))

        // Without adb inside, nothing is installed.
        assertFailsWith<IOException> { AdbDownload.install(zip("platform-tools/fastboot" to "x"), data, windows = false) }
    }

    @Test
    fun refusesZipEntriesOutsideTheFolder() {
        val parent = Files.createTempDirectory("dysk-slip").toFile()
        val target = File(parent, "unpack")
        for (evil in listOf("../evil.txt", "platform-tools/../../evil.txt", "a/../../evil.txt")) {
            val error = assertFailsWith<IOException> { zip("platform-tools/adb" to "ok", evil to "pwned").inputStream().use { AdbDownload.unzip(it, target) } }
            assertTrue("outside" in error.message.orEmpty())
            assertFalse(File(parent, "evil.txt").exists(), "$evil was written outside")
        }
        // A sibling folder that starts with the same name is outside too.
        assertFailsWith<IOException> { zip("../unpack-other/x" to "pwned").inputStream().use { AdbDownload.unzip(it, target) } }
        assertFalse(File(parent, "unpack-other/x").exists())
        // An inner ".." that stays inside is fine.
        zip("platform-tools/sub/../adb" to "ok").inputStream().use { AdbDownload.unzip(it, target) }
        assertEquals("ok", File(target, "platform-tools/adb").readText())
    }

    /** adb as the bridge sees it, with what each command prints. */
    private class FakeAdb(var devices: String) : AdbRunner {
        val calls = CopyOnWriteArrayList<String>()
        val reverses = HashMap<String, MutableSet<String>>()

        override fun run(adb: String, args: List<String>, timeoutMs: Long): String? {
            calls += args.joinToString(" ")
            return when {
                args == listOf("start-server") -> ""
                args == listOf("devices", "-l") -> devices
                args.size >= 4 && args[0] == "-s" && args[2] == "reverse" -> {
                    val set = reverses.getOrPut(args[1]) { mutableSetOf() }
                    when (args[3]) {
                        "--list" -> set.joinToString("\r\n", postfix = "\r\n") { "UsbFfs $it" }
                        "--remove" -> { set.removeIf { it.startsWith(args[4] + " ") }; "" }
                        else -> { set += "${args[3]} ${args[4]}"; "" }
                    }
                }
                else -> null
            }
        }
    }

    @Test
    fun reversesReadyPhonesToThisPcsPortAndOffTakesItAway(): Unit = runBlocking {
        var now = 1_000_000L
        val adb = FakeAdb("List of devices attached\r\nAAA\tdevice model:Pixel_8\r\nBBB\tunauthorized\r\n")
        var turnedOff = 0
        val saved = mutableListOf<Boolean>()
        val bridge = UsbBridge(
            scope, port = 52000, dataDir = Files.createTempDirectory("dysk-usb").toFile(), enabled = true,
            onEnabledChange = { saved += it }, onTurnedOff = { turnedOff++ },
            findAdb = { "/fake/adb" }, runner = adb, clock = { now }, windows = false,
        )
        bridge.poll()
        assertEquals("/fake/adb", bridge.state.value.adbPath)
        assertEquals(listOf("AAA", "BBB"), bridge.state.value.devices.map { it.serial })
        assertEquals(setOf("tcp:47823 tcp:52000"), adb.reverses["AAA"]?.toSet(), "Reversed to the port this PC listens on")
        assertNull(adb.reverses["BBB"], "A phone that hasn't allowed this PC gets nothing")
        assertEquals(setOf("AAA"), bridge.forwardedSerials)

        // Shortly after, a known phone isn't asked again.
        adb.calls.clear()
        now += 4_000
        bridge.poll()
        assertEquals(listOf("devices -l"), adb.calls.toList())

        // The adb server restarted and lost it: the check now and then puts it back.
        adb.reverses.clear()
        now += 21_000
        bridge.poll()
        assertTrue("-s AAA reverse --list" in adb.calls)
        assertEquals(setOf("tcp:47823 tcp:52000"), adb.reverses["AAA"]?.toSet())

        // Already there: listed, not added twice.
        adb.calls.clear()
        now += 21_000
        bridge.poll()
        assertFalse(adb.calls.any { it.endsWith("tcp:47823 tcp:52000") && !it.contains("--list") })

        // Off: the port is taken away and the phones are hung up on.
        bridge.setEnabled(false)
        val until = System.currentTimeMillis() + 5_000
        while (turnedOff == 0 && System.currentTimeMillis() < until) Thread.sleep(20)
        assertEquals(1, turnedOff)
        assertEquals(listOf(false), saved)
        assertTrue("-s AAA reverse --remove tcp:47823" in adb.calls)
        assertTrue(adb.reverses["AAA"].orEmpty().isEmpty())
        assertTrue(bridge.state.value.devices.isEmpty())
        assertFalse(bridge.state.value.enabled)
        // While off, nothing runs adb.
        adb.calls.clear()
        bridge.poll()
        assertTrue(adb.calls.isEmpty())
    }

    @Test
    fun startsTheAdbServerOnceAndCopesWithoutAdb(): Unit = runBlocking {
        val adb = FakeAdb("List of devices attached\n\n")
        var path: String? = null
        val bridge = UsbBridge(scope, 47821, Files.createTempDirectory("dysk-usb").toFile(), true, findAdb = { path }, runner = adb, windows = true)
        bridge.poll()
        assertNull(bridge.state.value.adbPath)
        assertTrue(adb.calls.isEmpty(), "No adb, nothing run")
        path = "C:\\adb.exe"
        bridge.poll()
        bridge.poll()
        assertEquals(1, adb.calls.count { it == "start-server" })
        assertEquals(2, adb.calls.count { it == "devices -l" })
        assertTrue(bridge.state.value.devices.isEmpty())
    }

    @Test
    fun aRealProcessIsKilledAtItsTimeout() {
        if (Platform.isWindows) return
        val started = System.currentTimeMillis()
        assertNull(ProcessAdb.run("/bin/sleep", listOf("5"), 300))
        assertTrue(System.currentTimeMillis() - started < 3_000, "Waited past the timeout")
        assertEquals("hi\n", ProcessAdb.run("/bin/echo", listOf("hi"), 5_000))
        assertNull(ProcessAdb.run("/bin/sh", listOf("-c", "exit 3"), 5_000), "A failed command gives null")
        assertNull(ProcessAdb.run("/no/such/adb", listOf("devices"), 5_000))
    }
}
