package id.phonelink.android.core

/** Pure helpers for the network tools, kept apart so they can be tested without Android. */
object NetTools {
    /** Ports that answer on most devices. A refused connection also proves the device is there. */
    val probePorts = listOf(80, 443, 62078, 445, 22)

    /** Ports checked on devices that answered, to guess what they are. */
    val servicePorts = listOf(
        53, 22, 80, 139, 443, 445, 554, 631, 3689, 5000, 7000, 8008, 8009, 8080, 9100, 62078, PhoneLinkProtocol.PORT,
    )

    const val SPEED_DOWN = "https://speed.cloudflare.com/__down?bytes="
    const val SPEED_UP = "https://speed.cloudflare.com/__up"

    data class Kind(val label: String)

    /** A best guess from open ports. Devices that block everything only show as "Device". */
    fun guess(open: Set<Int>, isRouter: Boolean, isSelf: Boolean): Kind = when {
        isSelf -> Kind("This phone")
        isRouter -> Kind("Router")
        PhoneLinkProtocol.PORT in open -> Kind("Mac with DyskLink")
        62078 in open -> Kind("iPhone or iPad")
        8009 in open || 8008 in open -> Kind("Chromecast or TV")
        9100 in open || 631 in open -> Kind("Printer")
        554 in open -> Kind("Camera")
        7000 in open || 3689 in open -> Kind("AirPlay device")
        445 in open || 139 in open -> Kind("Computer")
        22 in open -> Kind("Server")
        5000 in open -> Kind("NAS or server")
        80 in open || 443 in open || 8080 in open -> Kind("Device with a web page")
        else -> Kind("Device")
    }

    fun portName(port: Int): String = when (port) {
        53 -> "DNS"
        22 -> "SSH"
        80, 8080 -> "Web"
        443 -> "Web (HTTPS)"
        139, 445 -> "File sharing"
        554 -> "Camera stream"
        631, 9100 -> "Printing"
        3689, 7000 -> "AirPlay"
        5000 -> "NAS"
        8008, 8009 -> "Cast"
        62078 -> "Apple sync"
        PhoneLinkProtocol.PORT -> "DyskLink"
        else -> "Port $port"
    }

    /** The /24 around this address, without this address. */
    fun subnet(local: String): List<String> {
        val parts = local.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return emptyList()
        val prefix = parts.take(3).joinToString(".")
        return (1..254).map { "$prefix.$it" }.filter { it != local }
    }

    /** Sorts 192.168.1.2 before 192.168.1.10. */
    fun ipKey(ip: String): Long = ip.split('.').fold(0L) { acc, part -> acc * 256 + (part.toLongOrNull() ?: 0) }

    fun isValidHost(text: String): Boolean {
        val host = text.trim()
        if (host.isEmpty() || host.length > 253) return false
        return host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == ':' } && !host.startsWith("-")
    }

    /** Megabits per second from bytes over milliseconds. */
    fun mbps(bytes: Long, millis: Long): Double = if (millis <= 0) 0.0 else bytes * 8.0 / millis / 1000.0

    fun formatMbps(value: Double): String = when {
        value >= 100 -> "%.0f".format(value)
        value >= 10 -> "%.1f".format(value)
        else -> "%.2f".format(value)
    }

    /** The middle value, which ignores one slow outlier. */
    fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }

    data class PingLine(val seq: Int?, val millis: Double)

    /** One reply line from Android's ping, such as "64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=12.3 ms". */
    fun parsePingLine(line: String): PingLine? {
        val time = Regex("""time[=<]([\d.]+)\s*ms""").find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        val seq = Regex("""icmp_seq=(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
        return PingLine(seq, time)
    }

    data class PingSummary(val sent: Int, val received: Int, val min: Double?, val avg: Double?, val max: Double?) {
        val lossPercent: Int get() = if (sent == 0) 0 else ((sent - received) * 100 + sent / 2) / sent
    }

    fun summarize(sent: Int, times: List<Double>): PingSummary =
        PingSummary(sent, times.size, times.minOrNull(), times.takeIf { it.isNotEmpty() }?.average(), times.maxOrNull())
}
