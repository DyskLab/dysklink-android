package id.dysklink.desktop

import com.sun.jna.Memory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnergyTest {
    private val sec = 1_000_000_000L

    private fun p(pid: Long, ppid: Long, image: String, cpu: Long = 0, sandbox: String? = null, kernel: Boolean = false, idle: Boolean = false, start: Long = 1) =
        ProcSample(pid, ppid, start, cpu, image, sandbox, kernel, idle)

    @Test
    fun cpuDeltaPercentMultiCoreAndWrap() {
        assertEquals(50.0, EnergyMath.corePercent(sec / 2, sec))
        // Two cores busy for the whole second: 200 % of one core, 25 % of an 8-core machine.
        assertEquals(200.0, EnergyMath.corePercent(2 * sec, sec))
        assertEquals(25.0, EnergyMath.machinePercent(2 * sec, sec, 8))
        assertEquals(100.0, EnergyMath.machinePercent(20 * sec, sec, 8))
        assertEquals(0.0, EnergyMath.corePercent(5, 0))
        assertEquals(30L, EnergyMath.delta(100, 130))
        // A 32-bit counter that went over the top.
        assertEquals(20L, EnergyMath.delta(0xFFFFFFF6L, 10, wrapAt = 0x1_0000_0000L))
        // A counter that went back without a wrap width: reset, nothing counted.
        assertEquals(0L, EnergyMath.delta(500, 10))
    }

    @Test
    fun trackerDeltasPidReuseAndIdle() {
        val t = EnergyTracker(windows = true, cores = 4)
        assertNull(t.step(listOf(p(0, 0, "Idle", 10 * sec, idle = true), p(10, 1, "app.exe", 1 * sec)), 0))
        val step = t.step(listOf(
            p(0, 0, "Idle", 13 * sec, idle = true),
            p(10, 1, "app.exe", 1 * sec + sec / 2),
            // New since the last sample: all its time counts.
            p(11, 1, "new.exe", sec / 4, start = 9),
        ), 5 * sec)!!
        assertEquals(10.0, step.apps["app.exe"]!!, 1e-9)
        assertEquals(5.0, step.apps["new.exe"]!!, 1e-9)
        // Idle's three seconds aren't load: 0.75 s over 5 s on 4 cores.
        assertEquals(3.75, step.load, 1e-9)
        // pid 10 reused by another process with less CPU: counted as new, never negative.
        val next = t.step(listOf(p(10, 1, "app.exe", sec / 10, start = 50)), 10 * sec)!!
        assertEquals(2.0, next.apps["app.exe"]!!, 1e-9)
        // Never more than the machine can do.
        val t2 = EnergyTracker(windows = false, cores = 2)
        t2.step(listOf(p(5, 1, "x", 0)), 0)
        assertEquals(200.0, t2.step(listOf(p(5, 1, "x", 99 * sec)), sec)!!.apps["x"]!!, 1e-9)
    }

    @Test
    fun windowsGrouping() {
        val procs = listOf(
            p(1, 0, "explorer.exe"),
            p(100, 1, "chrome.exe"), p(101, 100, "chrome.exe"), p(102, 100, "CHROME.EXE"),
            p(103, 100, "crashpad_handler.exe"),
            p(200, 1, "Spotify.exe"), p(201, 200, "msedgewebview2.exe"), p(202, 201, "msedgewebview2.exe"),
            // A game Steam started stays on its own: only helpers go to their parent.
            p(300, 1, "steam.exe"), p(301, 300, "game.exe"),
            // A helper straight under Explorer has nothing to go to.
            p(400, 1, "conhost.exe"),
            p(4, 0, "System", kernel = true), p(0, 0, "Idle", idle = true),
        )
        val keys = EnergyGroups.keys(procs, windows = true)
        listOf(100L, 101L, 102L, 103L).forEach { assertEquals("chrome.exe", keys[it]) }
        assertEquals("spotify.exe", keys[201])
        assertEquals("spotify.exe", keys[202])
        assertEquals("game.exe", keys[301])
        assertEquals("conhost.exe", keys[400])
        assertNull(keys[4])
        assertNull(keys[0])
        assertTrue(EnergyGroups.isWindowsKernel("Memory Compression", 1234))
        assertEquals("Google Chrome", KnownApps.name("chrome.exe"))
    }

    @Test
    fun linuxGroupingCgroupsAndKernelThreads() {
        assertEquals("flatpak:org.mozilla.firefox", EnergyGroups.sandboxFromCgroup(
            "0::/user.slice/user-1000.slice/user@1000.service/app.slice/app-flatpak-org.mozilla.firefox-12345.scope"))
        assertEquals("snap:spotify", EnergyGroups.sandboxFromCgroup(
            "12:pids:/user.slice\n0::/user.slice/user-1000.slice/user@1000.service/app.slice/snap.spotify.spotify-6b1e7d2a-11aa-4b2f-9f0e-1c2d3e4f5a6b.scope"))
        assertEquals("snap:lxd", EnergyGroups.sandboxFromCgroup("0::/system.slice/snap.lxd.daemon.service"))
        assertNull(EnergyGroups.sandboxFromCgroup("0::/user.slice/user-1000.slice/user@1000.service/app.slice/app-gnome-org.gnome.Terminal-4242.scope"))

        val k = LinuxProcSource.parseStat("57 (kworker/3:1-events) I 2 0 0 0 -1 69238880 0 0 0 0 3 9 0 0 20 0 1 0 220 0 0")!!
        assertTrue(k.kernel)
        // A name with spaces and a parenthesis; utime 120 + stime 30 ticks = 1.5 s.
        val s = LinuxProcSource.parseStat("4321 (Web Content (x)) S 4000 4000 4000 0 -1 4194560 100 0 0 0 120 30 0 0 20 0 30 0 98765 1 2")!!
        assertEquals(4321L, s.pid)
        assertEquals(4000L, s.ppid)
        assertEquals("Web Content (x)", s.image)
        assertEquals(1_500_000_000L, s.cpuNanos)
        assertEquals(98765L, s.start)
        assertTrue(!s.kernel)

        val procs = listOf(
            p(1, 0, "systemd"),
            p(2, 0, "kthreadd", kernel = true), p(57, 2, "kworker/3:1", kernel = true),
            p(4000, 1, "firefox", sandbox = "flatpak:org.mozilla.firefox"), p(4321, 4000, "firefox", sandbox = "flatpak:org.mozilla.firefox"),
            p(500, 1, "chrome"), p(501, 500, "chrome"), p(502, 500, "chrome_crashpad_handler"),
            p(600, 1, "spotify", sandbox = "snap:spotify"),
        )
        val keys = EnergyGroups.keys(procs, windows = false)
        assertNull(keys[2])
        assertNull(keys[57])
        assertEquals("flatpak:org.mozilla.firefox", keys[4321])
        assertEquals("chrome", keys[502])
        assertEquals("snap:spotify", keys[600])
    }

    @Test
    fun linuxProcFolder() {
        val root = File(System.getProperty("java.io.tmpdir"), "energy-proc-${System.nanoTime()}").apply { mkdirs() }
        try {
            fun proc(pid: Int, stat: String, cgroup: String, cmdline: String) = File(root, "$pid").apply {
                mkdirs(); File(this, "stat").writeText(stat); File(this, "cgroup").writeText(cgroup); File(this, "cmdline").writeText(cmdline)
            }
            proc(10, "10 (GeckoMain) S 1 1 1 0 -1 0 0 0 0 0 200 100 0 0 20 0 1 0 500 0 0", "0::/app.slice/app-flatpak-org.mozilla.firefox-99.scope", "/app/lib/firefox/firefox\u0000-new")
            proc(2, "2 (kthreadd) S 0 0 0 0 -1 2129984 0 0 0 0 0 0 0 0 20 0 1 0 1 0 0", "0::/", "")
            File(root, "self").mkdirs()
            val apps = DesktopApps(listOf(File(root, "apps").apply {
                mkdirs(); File(this, "org.mozilla.firefox.desktop").writeText("[Desktop Entry]\nName=Firefox Web Browser\nExec=firefox %u\n[Desktop Action new]\nName=New Window\n")
            }))
            val src = LinuxProcSource(root, apps)
            val read = src.read().sortedBy { it.pid }
            assertEquals(listOf(2L, 10L), read.map { it.pid })
            assertTrue(read[0].kernel)
            assertEquals("firefox", read[1].image)
            assertEquals("flatpak:org.mozilla.firefox", read[1].sandbox)
            assertEquals(3_000_000_000L, read[1].cpuNanos)
            assertEquals("Firefox Web Browser", src.name("flatpak:org.mozilla.firefox", read[1]))
            assertEquals("Google Chrome", src.name("chrome", null))
            assertEquals("Gnome terminal server", src.name("gnome-terminal-server", null))
        } finally { root.deleteRecursively() }
        assertEquals(Triple("Visual Studio Code", "code", "Code"),
            DesktopApps.parse("[Desktop Entry]\nName=Visual Studio Code\nExec=env FOO=1 /usr/share/code/code --unity-launch %F\nStartupWMClass=Code\n"))
    }

    @Test
    fun windowsProcessInfoBuffer() {
        // Two SYSTEM_PROCESS_INFORMATION entries as 64-bit Windows lays them out.
        val mem = Memory(0x200)
        mem.clear()
        val name = Memory(64).apply { setWideString(0, "chrome.exe") }
        mem.setInt(0, 0x100)
        mem.setLong(0x28, 30_000_000) // 3 s user
        mem.setLong(0x30, 10_000_000) // 1 s kernel
        mem.setLong(0x50, 0)
        mem.setShort(0x100 + 0x38, ("chrome.exe".length * 2).toShort())
        mem.setPointer(0x100 + 0x40, name)
        mem.setLong(0x100 + 0x20, 777)
        mem.setLong(0x100 + 0x28, 5_000_000)
        mem.setLong(0x100 + 0x50, 1234)
        mem.setLong(0x100 + 0x58, 99)
        val list = WindowsProcSource.parseProcessInfo(mem)
        assertEquals(2, list.size)
        assertTrue(list[0].idle)
        assertEquals(4_000_000_000L, list[0].cpuNanos)
        assertEquals(ProcSample(1234, 99, 777, 500_000_000, "chrome.exe"), list[1])
    }

    @Test
    fun gpuInstances() {
        val byPid = WindowsGpu.gpuByPid(listOf(
            "pid_1234_luid_0x00000000_0x0000D1E3_phys_0_eng_0_engtype_3D" to 20.0,
            "pid_1234_luid_0x00000000_0x0000D1E3_phys_0_eng_1_engtype_3D" to 15.0,
            "pid_1234_luid_0x00000000_0x0000D1E3_phys_0_eng_3_engtype_VideoDecode" to 30.0,
            "pid_88_luid_0x00000000_0x0000D1E3_phys_0_eng_0_engtype_3D" to 0.0,
            "junk" to 50.0,
        ))
        assertEquals(mapOf(1234L to 35.0), byPid)
        assertEquals(mapOf("chrome.exe" to 35.0), gpuByApp(byPid, mapOf(1234L to "chrome.exe")))
    }

    @Test
    fun thresholdTopNAndLevels() {
        val avg = mapOf("a" to 5.0, "b" to 80.0, "c" to 10.0, "d" to 25.0, "e" to 11.0, "f" to 12.0, "g" to 13.0, "h" to 9.99)
        assertEquals(listOf("b", "d", "g", "f", "e"), EnergyMath.top(avg).map { it.first })
        assertEquals(listOf("b", "d"), EnergyMath.top(avg, count = 2).map { it.first })
        assertTrue(EnergyMath.top(mapOf("x" to 3.0)).isEmpty())
        assertEquals(ImpactLevel.High, EnergyMath.level(50.0))
        assertEquals(ImpactLevel.Medium, EnergyMath.level(49.9))
        assertEquals(ImpactLevel.Medium, EnergyMath.level(20.0))
        assertEquals(ImpactLevel.Low, EnergyMath.level(19.9))
    }

    @Test
    fun wattsSplit() {
        assertEquals(20.0, EnergyMath.loadWatts(20.0, 100.0), 1e-9)
        assertEquals(10.0, EnergyMath.loadWatts(20.0, 25.0), 1e-9)
        assertEquals(0.0, EnergyMath.loadWatts(20.0, 0.0), 1e-9)
        // 25 % of 4 cores is 100 % of one core in use; an app with 50 of it gets half the load's watts.
        assertEquals(5.0, EnergyMath.appWatts(10.0, 50.0, 25.0, 4), 1e-9)
        assertEquals(0.0, EnergyMath.appWatts(10.0, 50.0, 0.0, 4), 1e-9)

        val t = EnergyTracker(windows = false, cores = 4)
        val step = EnergyStep(mapOf("chrome" to 60.0, "idle-ish" to 2.0), load = 25.0)
        val v = t.view(step, batteryWatts = 16.0, onBattery = true) { KnownApps.name(it) ?: it }
        assertEquals(8.0, v.loadWatts!!, 1e-9)
        assertEquals(listOf("Google Chrome"), v.apps.map { it.name })
        assertEquals(ImpactLevel.High, v.apps[0].level)
        assertEquals(4.8, v.apps[0].watts!!, 1e-9)
        // On the charger: relative impact only, no watts.
        val plugged = EnergyTracker(windows = false, cores = 4).view(step, batteryWatts = 16.0, onBattery = false) { it }
        assertNull(plugged.batteryWatts)
        assertNull(plugged.apps[0].watts)
        // GPU use adds to the impact but not to the CPU watts.
        val withGpu = EnergyTracker(windows = true, cores = 4).view(EnergyStep(mapOf("game.exe" to 5.0), 5.0), 10.0, true, gpu = mapOf("game.exe" to 40.0)) { it }
        assertEquals(45.0, withGpu.apps[0].impact, 1e-9)
        assertTrue(withGpu.gpu)
    }

    @Test
    fun movingAverage() {
        val m = MovingAverage(3)
        assertEquals(mapOf("a" to 30.0), m.add(mapOf("a" to 30.0)))
        assertEquals(mapOf("a" to 15.0, "b" to 5.0), m.add(mapOf("b" to 10.0)))
        assertEquals(20.0, m.add(mapOf("a" to 30.0))["a"]!!, 1e-9)
        // The first sample has dropped out.
        assertEquals(10.0, m.add(emptyMap())["a"]!!, 1e-9)
        m.clear()
        assertEquals(0, m.size)
    }

    @Test
    fun trayLine() {
        val config = TrayMenuConfig(listOf(TraySection.Battery, TraySection.EnergyApp), setOf(TraySection.Battery, TraySection.EnergyApp))
        val labels = TrayMenu.build(config, TrayMenuState(battery = "62%", topEnergyApp = "Google Chrome (high)"), TrayMenuActions())
            .filterIsInstance<TrayEntry.Item>().map { it.label }
        assertEquals(listOf("Battery: 62%", "Top energy app: Google Chrome (high)", "Quit DyskLink"), labels)
        // Nothing above the threshold: no line.
        val none = TrayMenu.build(config, TrayMenuState(battery = "62%"), TrayMenuActions()).filterIsInstance<TrayEntry.Item>().map { it.label }
        assertEquals(listOf("Battery: 62%", "Quit DyskLink"), none)
    }
}
