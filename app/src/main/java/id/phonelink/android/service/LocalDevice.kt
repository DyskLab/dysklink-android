package id.phonelink.android.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.telephony.TelephonyManager
import id.phonelink.android.core.DeviceInfo
import id.phonelink.android.core.Hello
import id.phonelink.android.core.PhoneLinkProtocol

/** Information about this phone, sent to the Mac and shown in the UI. */
class LocalDevice(private val context: Context) {
    val name: String
        get() = Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: model

    val model: String
        get() = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    fun hello(paired: Boolean) = Hello(
        name = name,
        platform = "android",
        model = model,
        protocolVersion = PhoneLinkProtocol.VERSION,
        paired = paired,
        appVersion = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull(),
        caps = PhoneLinkProtocol.PHONE_CAPS,
    )

    fun info(): DeviceInfo {
        val battery = context.getSystemService(BatteryManager::class.java)
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val stat = StatFs(Environment.getDataDirectory().path)
        return DeviceInfo(
            batteryPercent = percent.takeIf { it in 0..100 },
            isCharging = battery.isCharging,
            network = networkName(),
            storageFreeBytes = stat.availableBytes,
            storageTotalBytes = stat.totalBytes,
            osVersion = "Android ${Build.VERSION.RELEASE}",
            model = model,
            carrier = telephony?.networkOperatorName?.takeIf { it.isNotBlank() },
            cellType = runCatching { cellType() }.getOrNull(),
            signalLevel = runCatching { signalLevel() }.getOrNull(),
            wifiLevel = wifiLevel(),
        )
    }

    private val telephony: TelephonyManager?
        get() = context.getSystemService(TelephonyManager::class.java)

    /** Needs the basic phone state permission, which isn't considered dangerous since Android 13. */
    private fun cellType(): String? = when (telephony?.dataNetworkType) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        else -> null
    }

    private fun signalLevel(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) telephony?.signalStrength?.level else null

    /** Wi-Fi strength on a 0 to 4 scale. */
    private fun wifiLevel(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val rssi = capabilities.signalStrength
        return when {
            rssi == NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED -> null
            rssi >= -55 -> 4
            rssi >= -66 -> 3
            rssi >= -77 -> 2
            rssi >= -88 -> 1
            else -> 0
        }
    }

    private fun networkName(): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return null
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            else -> null
        }
    }
}
