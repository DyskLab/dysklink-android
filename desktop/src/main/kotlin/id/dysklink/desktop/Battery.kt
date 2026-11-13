@file:Suppress("FunctionName")

package id.dysklink.desktop

import id.phonelink.android.core.CoreLog
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.w3c.dom.Element

// Battery health on Windows and Linux laptops, as the Mac's Battery tab: the level, health (what a full charge holds
// against the design), cycles, power in or out, time to full or empty, and the temperature where the system says.

enum class ChargeState { Charging, Discharging, Full, NotCharging, Unknown }

/** A point of Windows' recent usage: when, on the charger or not, and how full. */
data class BatteryUse(val time: LocalDateTime, val onAc: Boolean, val percent: Int?, val kind: String)

data class BatteryReading(
    val percent: Int,
    val state: ChargeState,
    /** On the charger; null when unknown. */
    val pluggedIn: Boolean? = null,
    val designWh: Double? = null,
    val fullWh: Double? = null,
    val nowWh: Double? = null,
    val cycles: Int? = null,
    /** Watts into the battery while charging, out of it on battery, always positive. */
    val watts: Double? = null,
    val voltage: Double? = null,
    val temperatureC: Double? = null,
    /** From the system when it has an estimate, else worked out from [watts]. */
    val minutesToFull: Int? = null,
    val minutesToEmpty: Int? = null,
    /** Where it came from: "sysfs", "UPower", or "Windows". */
    val source: String = "",
    val vendor: String? = null,
    val model: String? = null,
    val chemistry: String? = null,
    val recent: List<BatteryUse> = emptyList(),
) {
    /** Full charge against the design, capped at 100 as on the Mac. */
    val health: Int? get() = BatteryMath.health(fullWh, designWh)

    val timeToFull: Int? get() = minutesToFull ?: if (state == ChargeState.Charging) BatteryMath.minutesToFull(nowWh, fullWh, watts) else null
    val timeToEmpty: Int? get() = minutesToEmpty ?: if (state == ChargeState.Discharging) BatteryMath.minutesToEmpty(nowWh, watts) else null

    val statusText: String get() = when (state) {
        ChargeState.Charging -> "Charging"
        ChargeState.Discharging -> "On battery"
        ChargeState.Full -> "Full, on power"
        ChargeState.NotCharging -> "On power, not charging"
        ChargeState.Unknown -> if (pluggedIn == true) "On power" else "Battery"
    }

    /** Such as "76%, charging, 1 h 20 min to full", for the tray. */
    val trayText: String get() = buildString {
        append("$percent%")
        when (state) {
            ChargeState.Charging -> { append(", charging"); timeToFull?.let { append(", ${BatteryMath.duration(it)} to full") } }
            ChargeState.Discharging -> timeToEmpty?.let { append(", ${BatteryMath.duration(it)} left") }
            ChargeState.Full -> append(", full")
            ChargeState.NotCharging -> append(", not charging")
            ChargeState.Unknown -> Unit
        }
    }
}

object BatteryMath {
    fun health(fullWh: Double?, designWh: Double?): Int? {
        if (fullWh == null || designWh == null || designWh <= 0 || fullWh <= 0) return null
        return minOf(100, (fullWh / designWh * 100).roundToInt())
    }

    /** Null when the battery isn't drawing, or drawing too little to say (below 0.1 W). */
    fun minutesToEmpty(nowWh: Double?, watts: Double?): Int? {
        if (nowWh == null || watts == null || watts < 0.1) return null
        return (nowWh / watts * 60).roundToInt().takeIf { it in 1..(48 * 60) }
    }

    fun minutesToFull(nowWh: Double?, fullWh: Double?, watts: Double?): Int? {
        if (nowWh == null || fullWh == null || watts == null || watts < 0.1) return null
        val left = fullWh - nowWh
        if (left <= 0) return null
        return (left / watts * 60).roundToInt().takeIf { it in 1..(48 * 60) }
    }

    fun duration(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

/** What each system can't tell, said in the Battery section so a missing number isn't taken for a fault. */
object BatteryNotes {
    fun forReading(reading: BatteryReading, windows: Boolean): List<String> = buildList {
        if (reading.temperatureC == null) add(
            if (windows) "Windows doesn't share the battery temperature on most laptops."
            else "This battery's driver doesn't report a temperature.",
        )
        if (reading.cycles == null) add(
            if (windows) "This battery doesn't report its cycle count to Windows."
            else "This battery's driver doesn't report a cycle count (cycle_count is missing or 0).",
        )
        if (reading.health == null) add("The design capacity isn't reported, so health can't be worked out.")
        if (reading.watts == null) add("The charge rate isn't reported, so power and time estimates are missing.")
        if (windows) add("Health, cycles and recent usage come from Windows' battery report, refreshed once an hour.")
    }
}

// Linux: /sys/class/power_supply/BAT*

object SysfsBattery {
    /** Every battery's folder, BAT0, BAT1, and those named otherwise but of type Battery (not a mouse's). */
    fun folders(root: File = File("/sys/class/power_supply")): List<File> =
        root.listFiles().orEmpty().filter { dir ->
            val type = File(dir, "type").readOrNull()
            val scope = File(dir, "scope").readOrNull()
            (type == "Battery" || (type == null && dir.name.startsWith("BAT"))) && scope != "Device"
        }.sortedBy { it.name }

    private fun File.readOrNull(): String? = runCatching { readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun read(root: File = File("/sys/class/power_supply")): BatteryReading? {
        val batteries = folders(root).mapNotNull { dir -> parse { File(dir, it).readOrNull() } }
        if (batteries.isEmpty()) return null
        val online = root.listFiles().orEmpty().firstOrNull { File(it, "type").readOrNull() == "Mains" }
            ?.let { File(it, "online").readOrNull() == "1" }
        return combine(batteries).let { r -> if (r.pluggedIn == null && online != null) r.copy(pluggedIn = online) else r }
    }

    /**
     * One battery's files, by name. Energy files are in µWh and power in µW; charge files are in µAh and current
     * in µA, turned into Wh and W with the voltage. Temperature is in tenths of a degree.
     */
    fun parse(file: (String) -> String?): BatteryReading? {
        fun num(name: String) = file(name)?.toDoubleOrNull()
        if (file("present") == "0") return null
        val voltage = (num("voltage_now") ?: num("voltage_avg"))?.div(1e6)?.takeIf { it > 0 }
        val designVoltage = num("voltage_min_design")?.div(1e6)?.takeIf { it > 0 } ?: voltage
        val usesCharge = num("energy_full") == null && num("charge_full") != null
        fun energy(name: String): Double? = if (usesCharge) {
            // Ah times the design voltage (the voltage now when there's none), as UPower does.
            val ah = num("charge_$name")?.div(1e6) ?: return null
            ah * (designVoltage ?: return null)
        } else {
            num("energy_$name")?.div(1e6)
        }
        val full = energy("full")?.takeIf { it > 0 }
        val design = energy("full_design")?.takeIf { it > 0 }
        val now = energy("now")
        val watts = when {
            num("power_now") != null -> abs(num("power_now")!!) / 1e6
            num("current_now") != null && voltage != null -> abs(num("current_now")!!) / 1e6 * voltage
            else -> null
        }?.takeIf { it > 0 }
        val status = when (file("status")?.lowercase()) {
            "charging" -> ChargeState.Charging
            "discharging" -> ChargeState.Discharging
            "full" -> ChargeState.Full
            "not charging" -> ChargeState.NotCharging
            else -> ChargeState.Unknown
        }
        val percent = num("capacity")?.roundToInt()
            ?: if (now != null && full != null) (now / full * 100).roundToInt() else return null
        return BatteryReading(
            percent = percent.coerceIn(0, 100),
            state = status,
            pluggedIn = when (status) {
                ChargeState.Charging, ChargeState.Full, ChargeState.NotCharging -> true
                ChargeState.Discharging -> false
                ChargeState.Unknown -> null
            },
            designWh = design, fullWh = full, nowWh = now,
            cycles = num("cycle_count")?.toInt()?.takeIf { it > 0 },
            watts = if (status == ChargeState.Full || status == ChargeState.NotCharging) null else watts,
            voltage = voltage,
            temperatureC = num("temp")?.div(10)?.takeIf { it > -40 && it < 120 },
            minutesToFull = num("time_to_full_now")?.div(60)?.roundToInt()?.takeIf { it > 0 },
            minutesToEmpty = num("time_to_empty_now")?.div(60)?.roundToInt()?.takeIf { it > 0 },
            source = "sysfs",
            vendor = file("manufacturer"), model = file("model_name"), chemistry = file("technology"),
        )
    }

    /** Laptops with two batteries (ThinkPads) show as one, as the system's own battery icon does. */
    fun combine(list: List<BatteryReading>): BatteryReading {
        if (list.size == 1) return list[0]
        fun sum(pick: (BatteryReading) -> Double?) = list.mapNotNull(pick).takeIf { it.size == list.size }?.sum()
        val now = sum { it.nowWh }
        val full = sum { it.fullWh }
        val state = when {
            list.any { it.state == ChargeState.Charging } -> ChargeState.Charging
            list.any { it.state == ChargeState.Discharging } -> ChargeState.Discharging
            list.all { it.state == ChargeState.Full } -> ChargeState.Full
            list.any { it.state == ChargeState.NotCharging } -> ChargeState.NotCharging
            else -> ChargeState.Unknown
        }
        val watts = list.mapNotNull { it.watts }.takeIf { it.isNotEmpty() }?.sum()
        return list[0].copy(
            percent = if (now != null && full != null && full > 0) (now / full * 100).roundToInt().coerceIn(0, 100) else list.map { it.percent }.average().roundToInt(),
            state = state,
            pluggedIn = list.firstNotNullOfOrNull { it.pluggedIn },
            designWh = sum { it.designWh }, fullWh = full, nowWh = now,
            cycles = list.mapNotNull { it.cycles }.maxOrNull(),
            watts = watts,
            temperatureC = list.mapNotNull { it.temperatureC }.maxOrNull(),
            minutesToFull = null, minutesToEmpty = null,
        )
    }
}

// Linux fallback: UPower over the system bus.

object UPowerBattery {
    /** A UPower Device's properties (GetAll), as plain values. Null for anything but a laptop battery. */
    fun parse(props: Map<String, Any?>): BatteryReading? {
        fun num(name: String) = (props[name] as? Number)?.toDouble()
        if ((props["Type"] as? Number)?.toInt() != 2) return null
        if (props["PowerSupply"] == false || props["IsPresent"] == false) return null
        val state = when ((props["State"] as? Number)?.toInt()) {
            1, 5 -> ChargeState.Charging
            2, 3, 6 -> ChargeState.Discharging
            4 -> ChargeState.Full
            else -> ChargeState.Unknown
        }
        return BatteryReading(
            percent = (num("Percentage") ?: return null).roundToInt().coerceIn(0, 100),
            state = state,
            pluggedIn = when (state) { ChargeState.Charging, ChargeState.Full -> true; ChargeState.Discharging -> false; else -> null },
            designWh = num("EnergyFullDesign")?.takeIf { it > 0 },
            fullWh = num("EnergyFull")?.takeIf { it > 0 },
            nowWh = num("Energy"),
            cycles = num("ChargeCycles")?.toInt()?.takeIf { it > 0 },
            watts = num("EnergyRate")?.let(::abs)?.takeIf { it > 0 && state != ChargeState.Full },
            voltage = num("Voltage")?.takeIf { it > 0 },
            temperatureC = num("Temperature")?.takeIf { it != 0.0 },
            minutesToFull = num("TimeToFull")?.div(60)?.roundToInt()?.takeIf { it > 0 },
            minutesToEmpty = num("TimeToEmpty")?.div(60)?.roundToInt()?.takeIf { it > 0 },
            source = "UPower",
            vendor = (props["Vendor"] as? String)?.ifBlank { null }, model = (props["Model"] as? String)?.ifBlank { null },
        )
    }

    @org.freedesktop.dbus.annotations.DBusInterfaceName("org.freedesktop.UPower")
    interface UPower : org.freedesktop.dbus.interfaces.DBusInterface {
        fun EnumerateDevices(): List<org.freedesktop.dbus.DBusPath>
    }

    fun read(): BatteryReading? = runCatching {
        org.freedesktop.dbus.connections.impl.DBusConnectionBuilder.forSystemBus().withShared(false).build().use { conn ->
            val upower = conn.getRemoteObject("org.freedesktop.UPower", "/org/freedesktop/UPower", UPower::class.java)
            upower.EnumerateDevices().firstNotNullOfOrNull { path ->
                val props = conn.getRemoteObject("org.freedesktop.UPower", path.path, org.freedesktop.dbus.interfaces.Properties::class.java)
                    .GetAll("org.freedesktop.UPower.Device")
                parse(props.mapValues { (_, v) -> unwrap(v.value) })
            }
        }
    }.onFailure { CoreLog.i("DyskLink", "No UPower: ${it.message}") }.getOrNull()

    private fun unwrap(value: Any?): Any? = when (value) {
        is org.freedesktop.dbus.types.UInt32 -> value.toLong()
        is org.freedesktop.dbus.types.UInt64 -> value.toLong()
        is org.freedesktop.dbus.types.UInt16 -> value.toInt()
        is org.freedesktop.dbus.types.Variant<*> -> unwrap(value.value)
        else -> value
    }
}

// Windows: powercfg's battery report for the long-term numbers, WMI through PowerShell for what's happening now.

/** What `powercfg /batteryreport /xml` says. Capacities are in mWh. */
data class PowercfgReport(
    val designMwh: Long?,
    val fullMwh: Long?,
    val cycles: Int?,
    val vendor: String?,
    val model: String?,
    val chemistry: String?,
    val recent: List<BatteryUse>,
    /** Weekly or daily health points from the report's history, as (day, full, design). */
    val history: List<Triple<LocalDate, Long, Long>>,
)

object WindowsBattery {
    fun parseReport(xml: String): PowercfgReport? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // The report is local, but nothing outside it is ever read.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        fun elements(name: String): List<Element> {
            val nodes = doc.getElementsByTagNameNS("*", name)
            return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
        }
        fun Element.child(name: String): String? {
            val nodes = getElementsByTagNameNS("*", name)
            return (0 until nodes.length).firstNotNullOfOrNull { nodes.item(it).textContent?.trim()?.takeIf { t -> t.isNotEmpty() } }
        }
        val batteries = elements("Battery")
        if (batteries.isEmpty()) return null
        val design = batteries.mapNotNull { it.child("DesignCapacity")?.toLongOrNull() }.takeIf { it.isNotEmpty() }?.sum()
        val full = batteries.mapNotNull { it.child("FullChargeCapacity")?.toLongOrNull() }.takeIf { it.isNotEmpty() }?.sum()
        val cycles = batteries.mapNotNull { it.child("CycleCount")?.toIntOrNull() }.filter { it > 0 }.maxOrNull()
        val first = batteries[0]
        val recent = elements("UsageEntry").mapNotNull { e ->
            val time = (e.getAttribute("LocalTimestamp").ifEmpty { e.getAttribute("Timestamp") }).let(::dateTime) ?: return@mapNotNull null
            val charge = e.getAttribute("ChargeCapacity").toLongOrNull()
            val entryFull = e.getAttribute("FullChargeCapacity").toLongOrNull()
            val percent = if (charge != null && entryFull != null && entryFull > 0) (charge * 100 / entryFull).toInt().coerceIn(0, 100) else null
            BatteryUse(time, e.getAttribute("Ac") == "1", percent, e.getAttribute("EntryType"))
        }.sortedBy { it.time }
        val history = elements("HistoryEntry").mapNotNull { e ->
            val day = (e.getAttribute("LocalEndDate").ifEmpty { e.getAttribute("LocalStartDate") }).let(::dateTime)?.toLocalDate() ?: return@mapNotNull null
            val f = e.getAttribute("FullChargeCapacity").toLongOrNull() ?: return@mapNotNull null
            val d = e.getAttribute("DesignCapacity").toLongOrNull() ?: return@mapNotNull null
            if (f <= 0 || d <= 0) null else Triple(day, f, d)
        }
        PowercfgReport(
            design?.takeIf { it > 0 }, full?.takeIf { it > 0 }, cycles,
            first.child("Manufacturer"), first.child("Id"), first.child("Chemistry"), recent, history,
        )
    }.getOrNull()

    private fun dateTime(text: String): LocalDateTime? = runCatching { LocalDateTime.parse(text.trim().take(19)) }.getOrNull()

    /** Prints key=value lines from WMI. Values are in mWh, mW and mV. */
    const val WMI_SCRIPT = "\$ErrorActionPreference='SilentlyContinue';" +
        "\$b=Get-CimInstance -ClassName Win32_Battery|Select-Object -First 1;" +
        "\$s=Get-CimInstance -Namespace root\\wmi -ClassName BatteryStatus|Select-Object -First 1;" +
        "\$f=Get-CimInstance -Namespace root\\wmi -ClassName BatteryFullChargedCapacity|Select-Object -First 1;" +
        "\$d=Get-CimInstance -Namespace root\\wmi -ClassName BatteryStaticData|Select-Object -First 1;" +
        "\$c=Get-CimInstance -Namespace root\\wmi -ClassName BatteryCycleCount|Select-Object -First 1;" +
        "\$t=Get-CimInstance -Namespace root\\wmi -ClassName BatteryTemperature|Select-Object -First 1;" +
        "'present='+[bool]\$b;'percent='+\$b.EstimatedChargeRemaining;'runtime='+\$b.EstimatedRunTime;'status='+\$b.BatteryStatus;" +
        "'remaining='+\$s.RemainingCapacity;'chargeRate='+\$s.ChargeRate;'dischargeRate='+\$s.DischargeRate;'voltage='+\$s.Voltage;" +
        "'charging='+\$s.Charging;'discharging='+\$s.Discharging;'online='+\$s.PowerOnline;" +
        "'full='+\$f.FullChargedCapacity;'design='+\$d.DesignedCapacity;'cycles='+\$c.CycleCount;'temp='+\$t.Temperature"

    /** The WMI lines, with [report] filling what WMI didn't say. Null when there's no battery. */
    fun parseWmi(output: String, report: PowercfgReport?): BatteryReading? {
        val v = output.lines().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        fun num(key: String) = v[key]?.toDoubleOrNull()
        fun bool(key: String) = v[key]?.lowercase()?.let { it == "true" }
        if (v["present"]?.lowercase() != "true") return null
        val remaining = num("remaining")?.takeIf { it > 0 }
        val full = num("full")?.takeIf { it > 0 } ?: report?.fullMwh?.toDouble()
        val design = num("design")?.takeIf { it > 0 } ?: report?.designMwh?.toDouble()
        val charging = bool("charging") == true
        val discharging = bool("discharging") == true
        val online = bool("online")
        val percent = num("percent")?.roundToInt() ?: if (remaining != null && full != null) (remaining / full * 100).roundToInt() else return null
        val state = when {
            charging -> ChargeState.Charging
            discharging -> ChargeState.Discharging
            online == true && percent >= 99 -> ChargeState.Full
            online == true -> ChargeState.NotCharging
            // Win32_Battery's BatteryStatus: 1 discharging, 2 on AC, 3 fully charged, 6-9 charging.
            else -> when (num("status")?.toInt()) {
                1 -> ChargeState.Discharging
                3 -> ChargeState.Full
                6, 7, 8, 9 -> ChargeState.Charging
                2 -> ChargeState.NotCharging
                else -> ChargeState.Unknown
            }
        }
        val rate = when (state) {
            ChargeState.Charging -> num("chargeRate")
            ChargeState.Discharging -> num("dischargeRate")
            else -> null
        }?.let(::abs)?.takeIf { it > 0 }?.div(1000)
        // EstimatedRunTime is 71582788 while charging: no estimate.
        val runtime = num("runtime")?.toInt()?.takeIf { it in 1..(48 * 60) && state == ChargeState.Discharging }
        // BatteryTemperature is in tenths of a kelvin, on the rare laptops that have it.
        val temp = num("temp")?.takeIf { it > 0 }?.let { it / 10 - 273.15 }?.takeIf { it > -40 && it < 120 }
        return BatteryReading(
            percent = percent.coerceIn(0, 100),
            state = state,
            pluggedIn = online ?: (state != ChargeState.Discharging),
            designWh = design?.div(1000), fullWh = full?.div(1000), nowWh = remaining?.div(1000),
            cycles = num("cycles")?.toInt()?.takeIf { it > 0 } ?: report?.cycles,
            watts = rate,
            voltage = num("voltage")?.takeIf { it > 0 }?.div(1000),
            temperatureC = temp,
            minutesToEmpty = runtime,
            source = "Windows",
            vendor = report?.vendor, model = report?.model, chemistry = report?.chemistry,
            recent = report?.recent.orEmpty(),
        )
    }

    private fun run(command: List<String>, timeoutSeconds: Long): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { process.destroy(); return null }
        out
    }.getOrNull()

    fun report(): PowercfgReport? {
        val file = File.createTempFile("dysklink-battery-", ".xml")
        return try {
            run(listOf("powercfg", "/batteryreport", "/xml", "/output", file.path), 60)
            file.takeIf { it.length() > 0 }?.readText()?.let(::parseReport)
        } finally {
            file.delete()
        }
    }

    fun wmi(): String? = run(listOf("powershell", "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", WMI_SCRIPT), 20)
}

// The daily history, as the Mac's: one point a day, kept two years.

@Serializable
data class BatteryDay(val day: String, val health: Int, val fullWh: Double? = null, val cycles: Int? = null)

object BatteryHistory {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(BatteryDay.serializer())
    const val KEEP_DAYS = 730

    fun file(dir: File) = File(dir, "battery-history.json")

    fun load(dir: File): List<BatteryDay> = runCatching { json.decodeFromString(serializer, file(dir).readText()) }.getOrNull().orEmpty()

    fun save(days: List<BatteryDay>, dir: File) {
        runCatching { dir.mkdirs(); file(dir).writeText(json.encodeToString(serializer, days)) }
    }

    /** Today's point added, or replaced. */
    fun adding(reading: BatteryReading, days: List<BatteryDay>, today: LocalDate = LocalDate.now(ZoneId.systemDefault())): List<BatteryDay> {
        val health = reading.health ?: return days
        val point = BatteryDay(today.toString(), health, reading.fullWh?.let { (it * 100).roundToInt() / 100.0 }, reading.cycles)
        return (days.filter { it.day != point.day } + point).sortedBy { it.day }.takeLast(KEEP_DAYS)
    }

    /** Points from Windows' own report for days not yet in [days], so the chart starts with what Windows knew. */
    fun seeded(days: List<BatteryDay>, report: PowercfgReport?): List<BatteryDay> {
        val known = days.map { it.day }.toSet()
        val extra = report?.history.orEmpty().mapNotNull { (day, full, design) ->
            val health = BatteryMath.health(full.toDouble(), design.toDouble()) ?: return@mapNotNull null
            BatteryDay(day.toString(), health, full / 1000.0).takeIf { it.day !in known }
        }.distinctBy { it.day }
        return (days + extra).sortedBy { it.day }.takeLast(KEEP_DAYS)
    }
}

/** What the Battery section shows: the reading, the daily history, and the level over the last hours. */
data class BatteryView(
    val reading: BatteryReading,
    val days: List<BatteryDay> = emptyList(),
    /** The level about once a minute since DyskLink started, oldest first. */
    val levels: List<Int> = emptyList(),
    val notes: List<String> = emptyList(),
)

/**
 * Reads the battery now and then: every 10 seconds on Linux (files), every 30 on Windows (PowerShell is slow), the
 * battery report once an hour. [view] stays null on a PC without a battery, and the section is hidden then.
 */
class BatteryMonitor(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val dataDir: File,
) {
    private val _view = kotlinx.coroutines.flow.MutableStateFlow<BatteryView?>(null)
    val view: kotlinx.coroutines.flow.StateFlow<BatteryView?> = _view
    private var job: kotlinx.coroutines.Job? = null
    private val levels = ArrayDeque<Int>()
    private var lastLevelAt = 0L

    fun start() {
        if (job != null || Platform.isMac) return
        job = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var days = BatteryHistory.load(dataDir)
            var report: PowercfgReport? = null
            var reportAt = 0L
            var misses = 0
            while (true) {
                val now = System.currentTimeMillis()
                if (Platform.isWindows && now - reportAt > 3_600_000) {
                    reportAt = now
                    report = WindowsBattery.report() ?: report
                    val seeded = BatteryHistory.seeded(days, report)
                    if (seeded != days) { days = seeded; BatteryHistory.save(days, dataDir) }
                }
                val reading = when {
                    Platform.isWindows -> WindowsBattery.wmi()?.let { WindowsBattery.parseWmi(it, report) }
                    else -> SysfsBattery.read() ?: UPowerBattery.read()
                }
                if (reading == null) {
                    _view.value = null
                    // A desktop without a battery: looked at again rarely, in case one shows up (a docked tablet).
                    misses++
                    kotlinx.coroutines.delay(if (misses > 3) 600_000 else 30_000)
                    continue
                }
                misses = 0
                val updated = BatteryHistory.adding(reading, days)
                if (updated != days) { days = updated; BatteryHistory.save(days, dataDir) }
                if (now - lastLevelAt >= 60_000) {
                    lastLevelAt = now
                    levels.addLast(reading.percent)
                    while (levels.size > 240) levels.removeFirst()
                }
                _view.value = BatteryView(reading, days, levels.toList(), BatteryNotes.forReading(reading, Platform.isWindows))
                kotlinx.coroutines.delay(if (Platform.isWindows) 30_000 else 10_000)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}
