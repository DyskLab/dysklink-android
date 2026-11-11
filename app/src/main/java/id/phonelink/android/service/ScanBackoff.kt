package id.phonelink.android.service

/**
 * How long the LAN scan waits between sweeps while it finds nothing: 20 s, 1 min, 5 min, then every 30 min.
 * A network change, a lost connection, or opening the app starts over from the shortest wait.
 */
class ScanBackoff(private val steps: List<Long> = STEPS) {
    private var index = 0

    /** The wait after this sweep; each call without a [reset] moves one step longer. */
    fun next(): Long = steps[index].also { if (index < steps.lastIndex) index++ }

    fun reset() {
        index = 0
    }

    companion object {
        val STEPS = listOf(20_000L, 60_000L, 300_000L, 1_800_000L)
    }
}

/** What one turn of the scan loop does. */
enum class ScanAction {
    /** Every paired device is connected: nothing to look for. */
    Idle,
    /** Only try the addresses where devices were found before (cheap; used while the screen is off). */
    KnownHosts,
    /** Known addresses first, then the whole /24. */
    Sweep,
}

object ScanPolicy {
    /**
     * [pairedCount] paired devices, [connectedPaired] of them connected. With nothing paired yet, the scan keeps looking
     * so a first pairing can find the computer.
     */
    fun decide(pairedCount: Int, connectedPaired: Int, screenOn: Boolean): ScanAction = when {
        pairedCount > 0 && connectedPaired >= pairedCount -> ScanAction.Idle
        !screenOn -> ScanAction.KnownHosts
        else -> ScanAction.Sweep
    }

    /** Newest first, without duplicates, at most [limit]. */
    fun rememberHosts(known: List<String>, found: List<String>, limit: Int = 8): List<String> =
        (found + known).distinct().take(limit)
}
