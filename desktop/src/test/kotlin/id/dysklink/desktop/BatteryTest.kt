package id.dysklink.desktop

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatteryTest {
    private val sample = javaClass.getResource("/batteryreport-sample.xml")!!.readText()

    @Test
    fun powercfgReport() {
        val report = assertNotNull(WindowsBattery.parseReport(sample))
        assertEquals(52500, report.designMwh)
        assertEquals(45150, report.fullMwh)
        assertEquals(213, report.cycles)
        assertEquals("SMP", report.vendor)
        assertEquals("LiP", report.chemistry)
        assertEquals(3, report.recent.size)
        assertEquals(false, report.recent[1].onAc)
        assertEquals(74, report.recent[1].percent)
        assertEquals(LocalDate.of(2026, 11, 9), report.history.last().first)
        assertNull(WindowsBattery.parseReport("<BatteryReport><Batteries/></BatteryReport>"))
        assertNull(WindowsBattery.parseReport("not xml"))
    }

    @Test
    fun wmiWithReport() {
        val report = WindowsBattery.parseReport(sample)
        val out = "present=True\npercent=70\nruntime=71582788\nstatus=2\nremaining=31605\nchargeRate=24500\ndischargeRate=0\n" +
            "voltage=12850\ncharging=True\ndischarging=False\nonline=True\nfull=\ndesign=\ncycles=\ntemp="
        val r = assertNotNull(WindowsBattery.parseWmi(out, report))
        assertEquals(ChargeState.Charging, r.state)
        assertEquals(86, r.health)
        assertEquals(213, r.cycles)
        assertEquals(24.5, r.watts)
        assertEquals(12.85, r.voltage)
        assertNull(r.temperatureC)
        // (45.15 - 31.605) Wh at 24.5 W: about 33 minutes.
        assertEquals(33, r.timeToFull)
        assertTrue(BatteryNotes.forReading(r, windows = true).any { "temperature" in it })
        assertNull(WindowsBattery.parseWmi("present=False", report))
        val onBattery = WindowsBattery.parseWmi("present=True\npercent=50\nruntime=145\nremaining=20000\ndischargeRate=8000\ndischarging=True\nonline=False\ntemp=3031", null)!!
        assertEquals(ChargeState.Discharging, onBattery.state)
        assertEquals(145, onBattery.timeToEmpty)
        assertEquals(30.0, onBattery.temperatureC!!, 0.1)
    }

    private fun sysfs(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

    @Test
    fun sysfsEnergyUnits() {
        val r = assertNotNull(SysfsBattery.parse(sysfs(
            "status" to "Discharging", "capacity" to "62", "energy_now" to "31000000", "energy_full" to "50000000",
            "energy_full_design" to "57000000", "power_now" to "9300000", "voltage_now" to "11800000", "cycle_count" to "412",
            "manufacturer" to "SMP", "model_name" to "5B10", "technology" to "Li-poly",
        )))
        assertEquals(62, r.percent)
        assertEquals(88, r.health)
        assertEquals(9.3, r.watts!!, 0.001)
        assertEquals(412, r.cycles)
        assertEquals(200, r.timeToEmpty)
        assertNull(r.temperatureC)
        assertEquals(false, r.pluggedIn)
    }

    @Test
    fun sysfsChargeUnitsAndMissingFiles() {
        val r = assertNotNull(SysfsBattery.parse(sysfs(
            "status" to "Charging", "charge_now" to "2000000", "charge_full" to "4000000", "charge_full_design" to "5000000",
            "current_now" to "-1500000", "voltage_now" to "12000000", "voltage_min_design" to "11400000", "temp" to "312",
            "cycle_count" to "0",
        )))
        // No capacity file: worked out from charge now and full.
        assertEquals(50, r.percent)
        assertEquals(80, r.health)
        assertEquals(45.6, r.fullWh!!, 0.001)
        assertEquals(18.0, r.watts!!, 0.001)
        assertEquals(31.2, r.temperatureC!!, 0.001)
        assertNull(r.cycles)
        assertEquals(76, r.timeToFull)
        // Only a level: still shown, without the rest.
        val bare = assertNotNull(SysfsBattery.parse(sysfs("capacity" to "40", "status" to "Unknown")))
        assertNull(bare.health); assertNull(bare.watts); assertNull(bare.timeToEmpty)
        assertNull(SysfsBattery.parse(sysfs("present" to "0", "capacity" to "40")))
        assertNull(SysfsBattery.parse(sysfs("status" to "Full")))
    }

    @Test
    fun sysfsFoldersAndTwoBatteries() {
        val root = Files.createTempDirectory("ps").toFile()
        fun dev(name: String, vararg files: Pair<String, String>) = File(root, name).apply { mkdirs(); files.forEach { (k, v) -> File(this, k).writeText("$v\n") } }
        dev("AC", "type" to "Mains", "online" to "0")
        dev("BAT0", "type" to "Battery", "status" to "Discharging", "energy_now" to "20000000", "energy_full" to "40000000", "energy_full_design" to "50000000", "power_now" to "5000000")
        dev("BAT1", "type" to "Battery", "status" to "Unknown", "energy_now" to "10000000", "energy_full" to "20000000", "energy_full_design" to "25000000")
        dev("hidpp_battery_0", "type" to "Battery", "scope" to "Device", "capacity" to "90")
        val r = assertNotNull(SysfsBattery.read(root))
        assertEquals(50, r.percent)
        assertEquals(80, r.health)
        assertEquals(ChargeState.Discharging, r.state)
        assertEquals(360, r.timeToEmpty)
        assertNull(SysfsBattery.read(Files.createTempDirectory("none").toFile()))
    }

    @Test
    fun upowerProperties() {
        val r = assertNotNull(UPowerBattery.parse(mapOf(
            "Type" to 2L, "PowerSupply" to true, "Percentage" to 81.0, "State" to 2L, "Energy" to 40.5, "EnergyFull" to 50.0,
            "EnergyFullDesign" to 56.0, "EnergyRate" to 7.2, "Voltage" to 12.1, "TimeToEmpty" to 20_250L, "Temperature" to 0.0,
            "ChargeCycles" to -1, "Vendor" to "LGC",
        )))
        assertEquals(89, r.health)
        assertEquals(338, r.timeToEmpty)
        assertNull(r.cycles)
        assertNull(r.temperatureC)
        assertNull(UPowerBattery.parse(mapOf("Type" to 5L, "Percentage" to 50.0)))
    }

    @Test
    fun healthAndEstimates() {
        assertEquals(100, BatteryMath.health(60.0, 57.0))
        assertNull(BatteryMath.health(50.0, 0.0))
        assertEquals(60, BatteryMath.minutesToEmpty(10.0, 10.0))
        assertNull(BatteryMath.minutesToEmpty(10.0, 0.05))
        assertNull(BatteryMath.minutesToFull(50.0, 50.0, 20.0))
        assertEquals("1 h 20 min", BatteryMath.duration(80))
        assertEquals("2 h", BatteryMath.duration(120))
        val r = BatteryReading(76, ChargeState.Charging, fullWh = 50.0, nowWh = 38.0, watts = 12.0)
        assertEquals("76%, charging, 1 h to full", r.trayText)
    }

    @Test
    fun historyOnePointADay() {
        val r = BatteryReading(80, ChargeState.Discharging, designWh = 50.0, fullWh = 45.0, cycles = 100)
        val day = LocalDate.of(2026, 11, 13)
        var days = BatteryHistory.adding(r, emptyList(), day)
        days = BatteryHistory.adding(r.copy(fullWh = 44.0), days, day)
        assertEquals(1, days.size)
        assertEquals(88, days[0].health)
        days = BatteryHistory.adding(r, days, day.plusDays(1))
        assertEquals(listOf("2026-11-13", "2026-11-14"), days.map { it.day })
        // No design capacity: no point.
        assertEquals(days, BatteryHistory.adding(r.copy(designWh = null), days, day.plusDays(2)))
        val dir = Files.createTempDirectory("bh").toFile()
        BatteryHistory.save(days, dir)
        assertEquals(days, BatteryHistory.load(dir))
        val seeded = BatteryHistory.seeded(days, WindowsBattery.parseReport(sample))
        assertEquals(listOf("2026-11-02", "2026-11-09", "2026-11-13", "2026-11-14"), seeded.map { it.day })
    }
}
