package id.dysklink.desktop

import id.phonelink.android.core.CoreLog
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlin.concurrent.thread

/**
 * Announces `_phonelink._tcp` on each local network, as the Mac does with Bonjour, so phones find this PC.
 * Phones that can't see multicast still find it by scanning port 47821 on the subnet.
 */
class Advertiser(
    private val name: String,
    private val port: Int,
    private val deviceId: String,
    private val platform: String,
    /** Another computer announced itself: its device ID, address, and port. */
    private val onComputer: (id: String, host: String, port: Int) -> Unit = { _, _, _ -> },
    private val onComputerGone: (id: String) -> Unit = {},
) {
    private val listener = object : ServiceListener {
        private val ids = java.util.concurrent.ConcurrentHashMap<String, String>()
        override fun serviceAdded(event: ServiceEvent) { event.dns.requestServiceInfo(event.type, event.name, 3_000) }
        override fun serviceRemoved(event: ServiceEvent) { ids.remove(event.name)?.let(onComputerGone) }
        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info
            val id = info.getPropertyString("id") ?: return
            if (id == deviceId || info.getPropertyString("platform") !in setOf("macos", "windows", "linux")) return
            val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
            ids[event.name] = id
            onComputer(id, host, info.port)
        }
    }

    /** One jmDNS per local address it announces on. */
    private val running = HashMap<InetAddress, JmDNS>()
    @Volatile private var watcher: Thread? = null

    /**
     * Announces on every local address now, then checks every 30 seconds, because Wi-Fi changes and addresses come
     * and go, and DyskLink may start at login before the network is up. Each new address gets its own jmDNS, and
     * one that's gone has its jmDNS closed.
     */
    fun start() {
        if (watcher != null) return
        refresh()
        watcher = thread(isDaemon = true, name = "dysk-mdns") {
            while (true) {
                try {
                    Thread.sleep(CHECK_MS)
                } catch (_: InterruptedException) {
                    break
                }
                runCatching { refresh() }.onFailure { CoreLog.w("DyskLink", "Can't check the network", it) }
            }
        }
    }

    @Synchronized
    private fun refresh() {
        val now = localAddresses().toSet()
        for (gone in running.keys - now) {
            CoreLog.i("DyskLink", "No longer on ${gone.hostAddress}")
            running.remove(gone)?.let { runCatching { it.unregisterAllServices(); it.close() } }
        }
        // An address that failed last time is tried again here.
        for (address in now - running.keys) {
            runCatching {
                // Windows, macOS, and Avahi already answer for the computer's own name, so this gets a name of its own.
                val dns = JmDNS.create(address, "dysklink-${deviceId.take(8)}")
                val info = ServiceInfo.create("_phonelink._tcp.local.", name, port, 0, 0, mapOf("id" to deviceId, "platform" to platform))
                dns.registerService(info)
                dns.addServiceListener("_phonelink._tcp.local.", listener)
                running[address] = dns
                CoreLog.i("DyskLink", "Announced on ${address.hostAddress}:$port")
            }.onFailure { CoreLog.w("DyskLink", "Can't announce on ${address.hostAddress}", it) }
        }
    }

    fun stop() {
        watcher?.interrupt()
        watcher = null
        synchronized(this) {
            running.values.forEach { runCatching { it.unregisterAllServices(); it.close() } }
            running.clear()
        }
    }

    companion object {
        private const val CHECK_MS = 30_000L

        /** Home and office networks: private IPv4 on interfaces that are up, skipping loopback and VPN tunnels. */
        fun localAddresses(): List<InetAddress> =
            runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
                .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual && !it.isPointToPoint }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .filter { it is Inet4Address && it.isSiteLocalAddress }
    }
}
