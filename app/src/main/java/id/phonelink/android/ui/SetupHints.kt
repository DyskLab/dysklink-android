package id.phonelink.android.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect

/** What keeps the Mac from being found on the network, read from Android's view of the connection. */
internal data class NetworkHintState(val onLan: Boolean, val vpn: Boolean)

internal fun networkHintState(context: Context): NetworkHintState {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkHintState(onLan = true, vpn = false)
    @Suppress("DEPRECATION") // allNetworks still lists every network; a VPN can hide Wi-Fi from the active one.
    val all = runCatching { manager.allNetworks.mapNotNull { manager.getNetworkCapabilities(it) } }.getOrDefault(emptyList())
    val onLan = all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }
    val active = runCatching { manager.getNetworkCapabilities(manager.activeNetwork) }.getOrNull()
    return NetworkHintState(onLan = onLan, vpn = active?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
}

/** Shown while no device is connected: no Wi-Fi, or a VPN that can hide devices on the same Wi-Fi. */
@Composable
internal fun NetworkHint(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var state by remember { mutableStateOf(networkHintState(context)) }
    DisposableEffect(Unit) {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { state = networkHintState(context) }
            override fun onLost(network: Network) { state = networkHintState(context) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { state = networkHintState(context) }
        }
        val registered = runCatching { manager?.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper())) }.isSuccess
        onDispose { if (registered) runCatching { manager?.unregisterNetworkCallback(callback) } }
    }
    LifecycleResumeEffect(Unit) {
        state = networkHintState(context)
        onPauseOrDispose { }
    }
    when {
        !state.onLan -> HintBox(
            "This phone isn't on Wi-Fi. Join the same Wi-Fi as your Mac, or let the Mac join this phone's hotspot.",
            "Open Wi-Fi settings",
            modifier,
        ) { open(context, Intent(Settings.ACTION_WIFI_SETTINGS)) }
        state.vpn -> HintBox(
            "A VPN is on. Many VPNs hide devices on the same Wi-Fi, so your Mac may not show up. Turn the VPN off, or allow local network access in the VPN app.",
            "Open VPN settings",
            modifier,
        ) { open(context, Intent(Settings.ACTION_VPN_SETTINGS)) }
    }
}

/**
 * Steps for phone makers whose own battery managers stop apps in the background even after Android's battery
 * exemption. Null for makers that follow Android's setting.
 */
internal fun makerBackgroundSteps(manufacturer: String): String? = when (val maker = manufacturer.trim().lowercase()) {
    "vivo", "iqoo" ->
        "vivo stops background apps on its own. In App info, open Battery and choose Allow background activity " +
            "(or High background power consumption), and turn on Autostart in i Manager › App manager. " +
            "Then lock DyskLink in the recent apps list: swipe down on it, or tap its lock icon."
    "xiaomi", "redmi", "poco" ->
        "Xiaomi stops background apps on its own. In App info, turn on Autostart, and set Battery saver to No restrictions. " +
            "Then lock DyskLink in the recent apps list."
    "oppo", "realme", "oneplus" ->
        "${makerNames[maker]} stops background apps on its own. In App info, open Battery " +
            "and turn on Allow background activity and Allow auto launch."
    "huawei", "honor" ->
        "${makerNames[maker]} stops background apps on its own. In Settings › Battery › App launch, " +
            "set DyskLink to Manage manually and turn on all three switches."
    "samsung" ->
        "Samsung can put apps to sleep. In App info › Battery, choose Unrestricted, and make sure DyskLink isn't under " +
            "Battery › Background usage limits › Sleeping apps."
    else -> null
}

private val makerNames = mapOf("oppo" to "Oppo", "realme" to "realme", "oneplus" to "OnePlus", "huawei" to "Huawei", "honor" to "Honor")

private const val HINT_PREFS = "setup_hints"
private const val KEY_MAKER_DONE = "maker_background_done"

/** For phones whose maker kills background apps: the steps once, until marked done. */
@Composable
internal fun MakerBackgroundHint(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val steps = remember { makerBackgroundSteps(Build.MANUFACTURER) } ?: return
    val prefs = remember { context.getSharedPreferences(HINT_PREFS, Context.MODE_PRIVATE) }
    var done by remember { mutableStateOf(prefs.getBoolean(KEY_MAKER_DONE, false)) }
    if (done) return
    androidx.compose.foundation.layout.Column(modifier) {
        HintBox("$steps If DyskLink keeps disconnecting with the screen off, this is usually why.", "Open App info") {
            open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
        Spacer(Modifier.height(2.dp))
        ToolLink("I've done this", Palette.TextSecondary) {
            prefs.edit().putBoolean(KEY_MAKER_DONE, true).apply()
            done = true
        }
    }
}

private fun open(context: Context, intent: Intent) {
    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/**
 * After a permission was turned down: once Android stops showing its prompt, opens App info so it can still be
 * given there. True when App info was opened.
 */
internal fun openAppInfoIfBlocked(context: Context, permission: String): Boolean {
    val activity = context as? android.app.Activity ?: return false
    if (activity.shouldShowRequestPermissionRationale(permission)) return false
    android.widget.Toast.makeText(context, "Android won't ask again. Turn it on in Permissions, then come back.", android.widget.Toast.LENGTH_LONG).show()
    open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    return true
}
