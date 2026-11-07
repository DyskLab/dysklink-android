package id.phonelink.android.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import id.phonelink.android.core.PhoneLinkProtocol
import java.util.concurrent.Executors

/** Looks for Macs advertising the PhoneLink service on the local network. */
class NsdDiscovery(
    context: Context,
    private val onFound: (name: String, host: String, port: Int) -> Unit,
    private val onLost: (name: String) -> Unit,
) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private var started = false

    private val listener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(service: NsdServiceInfo) {
            Log.i(TAG, "Service found: ${service.serviceName}")
            resolve(service)
        }
        override fun onServiceLost(service: NsdServiceInfo) = onLost(service.serviceName)
        override fun onDiscoveryStarted(serviceType: String) {
            Log.i(TAG, "Discovery started: $serviceType")
        }
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Discovery failed to start: $errorCode")
            started = false
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
    }

    fun start() {
        if (started) return
        started = true
        manager.discoverServices(PhoneLinkProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        if (started) {
            started = false
            runCatching { manager.stopServiceDiscovery(listener) }
        }
        executor.shutdown()
    }

    @Suppress("DEPRECATION")
    private fun resolve(service: NsdServiceInfo) {
        // Before Android 14, NsdManager can only resolve one service at a time.
        executor.execute {
            val done = java.util.concurrent.CountDownLatch(1)
            val callback = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "Failed to resolve ${info.serviceName}: $errorCode")
                    done.countDown()
                }
                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        info.hostAddresses.firstOrNull { it is java.net.Inet4Address } ?: info.hostAddresses.firstOrNull()
                    } else {
                        info.host
                    }
                    Log.i(TAG, "Address of ${info.serviceName}: ${host?.hostAddress}:${info.port}")
                    host?.hostAddress?.let { onFound(info.serviceName, it, info.port) }
                    done.countDown()
                }
            }
            runCatching {
                manager.resolveService(service, callback)
                done.await(10, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    private companion object {
        const val TAG = "PhoneLink"
    }
}
