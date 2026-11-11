package id.phonelink.android.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** Sends Wake-on-LAN "magic packets": six 0xFF bytes and the hardware address 16 times. */
object WakeOnLan {
    fun packet(mac: String): ByteArray? {
        val bytes = mac.split(':', '-').mapNotNull { it.toIntOrNull(16)?.toByte() }
        if (bytes.size != 6) return null
        return ByteArray(6) { 0xFF.toByte() } + (1..16).flatMap { bytes }.toByteArray()
    }

    /** Sends to every address, on the network's broadcast and the general one, ports 9 and 7. True when one went out. */
    fun send(macs: List<String>, broadcast: String?): Boolean {
        val targets = listOfNotNull(broadcast, "255.255.255.255").distinct().mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
        var sent = false
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                for (mac in macs) {
                    val data = packet(mac) ?: continue
                    for (target in targets) for (port in listOf(9, 7)) {
                        // A couple of tries, since UDP can drop and a sleeping Wi-Fi card may miss one.
                        repeat(2) { if (runCatching { socket.send(DatagramPacket(data, data.size, target, port)) }.isSuccess) sent = true }
                    }
                }
            }
        }
        return sent
    }
}
