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
    private val onFound: (name: String, host: String, port: Int, attributes: Map<String, String>) -> Unit,
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

    private var advertised: NsdManager.RegistrationListener? = null

    /** Lets other phones find this one. Passing null stops it. */
    fun advertise(name: String, deviceId: String, port: Int?) {
        advertised?.let { runCatching { manager.unregisterService(it) } }
        advertised = null
        if (port == null) return
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = PhoneLinkProtocol.SERVICE_TYPE
            setPort(port)
            setAttribute("id", deviceId)
            setAttribute("platform", "android")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Log.i(TAG, "Advertised as ${info.serviceName}").let { }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Log.w(TAG, "Advertising failed: $errorCode").let { }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { advertised = listener }
            .onFailure { Log.w(TAG, "Can't advertise", it) }
    }

    fun stop() {
        advertise("", "", null)
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
                    val attributes = info.attributes.orEmpty().mapValues { (_, value) -> value?.toString(Charsets.UTF_8).orEmpty() }
                    host?.hostAddress?.let { onFound(info.serviceName, it, info.port, attributes) }
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
