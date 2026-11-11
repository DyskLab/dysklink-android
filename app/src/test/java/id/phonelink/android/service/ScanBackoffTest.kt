package id.phonelink.android.service

import kotlin.test.Test
import kotlin.test.assertEquals

class ScanBackoffTest {
    @Test
    fun waitsGrowThenStayAtHalfAnHour() {
        val backoff = ScanBackoff()
        val waits = List(6) { backoff.next() }
        assertEquals(listOf(20_000L, 60_000L, 300_000L, 1_800_000L, 1_800_000L, 1_800_000L), waits)
    }

    @Test
    fun resetStartsOverFromTheShortestWait() {
        val backoff = ScanBackoff()
        repeat(3) { backoff.next() }
        backoff.reset()
        assertEquals(20_000L, backoff.next())
        assertEquals(60_000L, backoff.next())
    }

    @Test
    fun idleWhenEveryPairedDeviceIsConnected() {
        assertEquals(ScanAction.Idle, ScanPolicy.decide(pairedCount = 2, connectedPaired = 2, screenOn = true))
        assertEquals(ScanAction.Idle, ScanPolicy.decide(pairedCount = 1, connectedPaired = 1, screenOn = false))
    }

    @Test
    fun screenOffOnlyTriesKnownHosts() {
        assertEquals(ScanAction.KnownHosts, ScanPolicy.decide(pairedCount = 2, connectedPaired = 1, screenOn = false))
        assertEquals(ScanAction.Sweep, ScanPolicy.decide(pairedCount = 2, connectedPaired = 1, screenOn = true))
    }

    @Test
    fun nothingPairedKeepsLookingForAFirstPairing() {
        assertEquals(ScanAction.Sweep, ScanPolicy.decide(pairedCount = 0, connectedPaired = 0, screenOn = true))
    }

    @Test
    fun knownHostsPutNewestFirstWithoutDuplicates() {
        val hosts = ScanPolicy.rememberHosts(known = listOf("10.0.0.2", "10.0.0.3"), found = listOf("10.0.0.3", "10.0.0.9"), limit = 3)
        assertEquals(listOf("10.0.0.3", "10.0.0.9", "10.0.0.2"), hosts)
    }
}
