package id.phonelink.android.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import id.phonelink.android.core.WakeOnLan
import id.phonelink.android.core.WakeRoute
import java.net.Inet4Address

/**
 * The phone's Wi-Fi network for Wake-on-LAN. The packet is tied to it, so it reaches the Mac's network even while a
 * VPN or mobile data is the default. Null when the phone isn't on Wi-Fi (or Ethernet).
 */
object WifiWake {
    fun route(context: Context): WakeRoute? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val network = manager.allNetworks.firstOrNull { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
            (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } ?: return null
        val broadcast = manager.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address }
            ?.let { WakeOnLan.broadcastOf(it.address.address, it.prefixLength) }
        return WakeRoute(broadcast) { socket -> network.bindSocket(socket) }
    }
}
