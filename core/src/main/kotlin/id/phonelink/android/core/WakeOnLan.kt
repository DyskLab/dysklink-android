package id.phonelink.android.core

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/** Sends Wake-on-LAN "magic packets": six 0xFF bytes and the hardware address 16 times. */
object WakeOnLan {
    fun packet(mac: String): ByteArray? {
        val bytes = mac.split(':', '-').mapNotNull { it.toIntOrNull(16)?.toByte() }
        if (bytes.size != 6) return null
        return ByteArray(6) { 0xFF.toByte() } + (1..16).flatMap { bytes }.toByteArray()
    }

    /**
     * The directed broadcast of an IPv4 [address] with a [prefix]-bit network, such as 192.168.1.255 for
     * 192.168.1.20/24. Null for anything but IPv4, or a prefix that leaves no host part (/31, /32) or is out of range.
     */
    fun broadcastOf(address: ByteArray, prefix: Int): String? {
        if (address.size != 4 || prefix !in 0..30) return null
        val ip = address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val hostMask = (1L shl (32 - prefix)) - 1
        val broadcast = ip or hostMask
        return (3 downTo 0).joinToString(".") { ((broadcast shr (it * 8)) and 0xFF).toString() }
    }

    /**
     * Sends to every address, on each of [broadcasts] and the general one, ports 9 and 7. [bind] ties the socket to a
     * network first (the phone's Wi-Fi, so it doesn't leave through a VPN or mobile data); when it fails nothing is
     * sent. True when one packet went out.
     */
    fun send(macs: List<String>, broadcasts: List<String?>, bind: ((DatagramSocket) -> Unit)? = null): Boolean {
        val targets = (broadcasts.filterNotNull() + "255.255.255.255").distinct()
            .mapNotNull { runCatching { InetAddress.getByName(it) }.getOrNull() }
        var sent = false
        runCatching {
            DatagramSocket(null).use { socket ->
                bind?.invoke(socket)
                socket.broadcast = true
                if (!socket.isBound) socket.bind(null)
                for (mac in macs) {
                    val data = packet(mac) ?: continue
                    for (target in targets) for (port in listOf(9, 7)) {
                        // A couple of tries, since UDP can drop and a sleeping Wi-Fi card may miss one.
                        repeat(2) { if (runCatching { socket.send(DatagramPacket(data, data.size, target, port)) }.isSuccess) sent = true }
                    }
                }
            }
        }.onFailure { CoreLog.w("PhoneLink", "Wake-on-LAN not sent", it) }
        return sent
    }
}

/** Where Wake-on-LAN goes out: the phone's Wi-Fi, its broadcast address, and how to tie a socket to it. */
class WakeRoute(val broadcast: String?, val bind: (DatagramSocket) -> Unit)

/** One try at waking a Mac, as the device page and the widget report it. */
sealed interface WakeState {
    /** Nothing went out: no address known, or no network to send on. */
    data object NotSent : WakeState

    /** Sent at [sentAt]; waiting for the Mac to connect. [viaWifi] is false when no Wi-Fi was found to send on. */
    data class Waiting(val sentAt: Long, val viaWifi: Boolean) : WakeState

    /** The Mac connected after it was sent. */
    data object Woke : WakeState

    /** Sent, but the Mac didn't connect within [WakeFlow.WAIT_MS]. */
    data class DidntWake(val wakeEnabled: Boolean?, val viaWifi: Boolean, val onBattery: Boolean?) : WakeState
}

/** What a wake try does next, and what to tell the user about it. */
object WakeFlow {
    /** A sleeping Mac that hears the packet usually connects in 10 to 30 seconds. */
    const val WAIT_MS = 45_000L

    fun sent(sent: Boolean, now: Long, viaWifi: Boolean): WakeState = if (sent) WakeState.Waiting(now, viaWifi) else WakeState.NotSent

    /**
     * The next state of a try. [connected] means the Mac connected and answers (not a connection that went quiet).
     * [onBattery] is how it was last seen, when known.
     */
    fun step(state: WakeState, connected: Boolean, now: Long, wakeEnabled: Boolean?, onBattery: Boolean? = null): WakeState = when (state) {
        is WakeState.Waiting -> when {
            connected -> WakeState.Woke
            now - state.sentAt >= WAIT_MS -> WakeState.DidntWake(wakeEnabled, state.viaWifi, onBattery)
            else -> state
        }
        else -> state
    }

    /** One line, for a toast. */
    fun summary(state: WakeState, name: String): String = when (state) {
        WakeState.NotSent -> "Couldn't send the wake signal. Check that this phone is on Wi-Fi."
        is WakeState.Waiting -> "Wake signal sent to $name. Waiting for it to connect…"
        WakeState.Woke -> "$name woke up and connected"
        is WakeState.DidntWake -> "Your Mac didn't wake up. Open DyskLink to see why."
    }

    /** Why the Mac may not have woken, most likely first, tailored to what's known about it. */
    fun reasons(state: WakeState.DidntWake): List<String> = buildList {
        if (!state.viaWifi) add("This phone wasn't on Wi-Fi, so the signal may not have reached the Mac's network. Join the same Wi-Fi as the Mac and try again.")
        if (state.wakeEnabled == false) {
            add("\"Wake for network access\" is off on the Mac. Turn it on in System Settings › Battery › Options.")
        }
        add(
            "A MacBook on Wi-Fi only wakes this way while it's on the charger" +
                (if (state.onBattery == true) " (it was on battery when it last connected)" else "") +
                ", and only with an Apple TV or HomePod on the same network to answer for it while it sleeps.",
        )
        add("Over an Ethernet cable, a Mac on power wakes directly.")
        if (state.wakeEnabled == null) add("Check that \"Wake for network access\" is on in the Mac's System Settings › Battery › Options.")
        add("A Mac that is shut down can't be woken from the phone.")
    }
}

/**
 * Lets the redial wait of one device's targets end early: after a wake signal, its next try starts at once and from
 * the shortest wait. Kicks are numbered, so one that comes while a target is dialing isn't missed.
 */
class RedialKicks {
    private val kicks = MutableStateFlow<Map<String, Long>>(emptyMap())
    private var counter = 0L

    /** The number of the latest kick, to pass to [wait] as `since`. */
    val latest: Long get() = synchronized(this) { counter }

    /** Ends the wait of [peerId]'s targets, and of targets whose device isn't known yet. */
    fun kick(peerId: String) {
        val number = synchronized(this) { ++counter }
        kicks.update { it + (peerId to number) }
    }

    /** Whether [peerId] (null: not known yet, any kick counts) was kicked after [since]. */
    fun kickedSince(since: Long, peerId: String?): Boolean = kicked(kicks.value, since, peerId)

    /** Waits [ms], or less when kicked after [since]. True when the wait ended by a kick. */
    suspend fun wait(ms: Long, since: Long, peerId: String?): Boolean =
        withTimeoutOrNull(ms) { kicks.first { kicked(it, since, peerId) } } != null

    private fun kicked(map: Map<String, Long>, since: Long, peerId: String?): Boolean =
        if (peerId == null) map.values.any { it > since } else (map[peerId] ?: 0L) > since
}
