package id.phonelink.android.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import id.phonelink.android.core.PhoneLinkProtocol
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Fallback for networks that block multicast, where mDNS doesn't reach the phone.
 * Checks the PhoneLink port on the same /24 subnet, over Wi-Fi or Ethernet only.
 */
class LanScanner(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    suspend fun findHosts(): List<String> {
        val local = localAddress() ?: return emptyList()
        val prefix = local.substringBeforeLast('.')
        val limit = Semaphore(MAX_PARALLEL)
        return coroutineScope {
            (1..254)
                .map { "$prefix.$it" }
                .filter { it != local }
                .map { host ->
                    async(Dispatchers.IO) {
                        limit.withPermit { host.takeIf { isOpen(it) } }
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
    }

    private fun localAddress(): String? {
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        val isLocal = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!isLocal) return null
        return connectivity.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    private fun isOpen(host: String): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, PhoneLinkProtocol.PORT), CONNECT_TIMEOUT_MS) }
    }.isSuccess

    private companion object {
        const val MAX_PARALLEL = 48
        const val CONNECT_TIMEOUT_MS = 500
    }
}
