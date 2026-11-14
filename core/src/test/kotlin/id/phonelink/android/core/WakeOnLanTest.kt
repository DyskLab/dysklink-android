package id.phonelink.android.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class WakeOnLanTest {
    @Test
    fun magicPacketIsSixFfThenTheAddressSixteenTimes() {
        val packet = WakeOnLan.packet("68:5e:dd:75:79:2c")!!
        assertEquals(102, packet.size)
        assertContentEquals(ByteArray(6) { 0xFF.toByte() }, packet.copyOfRange(0, 6))
        val mac = byteArrayOf(0x68, 0x5e, 0xdd.toByte(), 0x75, 0x79, 0x2c)
        for (i in 0 until 16) assertContentEquals(mac, packet.copyOfRange(6 + i * 6, 12 + i * 6))
        assertEquals(102, WakeOnLan.packet("68-5E-DD-75-79-2C")?.size)
        assertNull(WakeOnLan.packet("02:00:00"))
    }

    @Test
    fun oldRecordsAndInfoStillRead() {
        val record = protocolJson.decodeFromString(PairedRecord.serializer(), """{"name":"Mac","platform":"macos","model":"M","pairedAt":1}""")
        assertEquals(emptyList(), record.wakeMacs)
        val info = protocolJson.decodeFromString(DeviceInfo.serializer(), """{"wakeMacs":["68:5e:dd:75:79:2c"],"wakeBroadcast":"192.168.1.255","wakeEnabled":true}""")
        assertEquals("192.168.1.255", info.wakeBroadcast)
        assertEquals(true, info.wakeEnabled)
    }

    @Test
    fun broadcastComesFromTheAddressAndPrefix() {
        fun ip(vararg parts: Int) = parts.map { it.toByte() }.toByteArray()
        assertEquals("192.168.1.255", WakeOnLan.broadcastOf(ip(192, 168, 1, 20), 24))
        assertEquals("10.0.15.255", WakeOnLan.broadcastOf(ip(10, 0, 8, 7), 20))
        assertEquals("172.16.255.255", WakeOnLan.broadcastOf(ip(172, 16, 4, 1), 16))
        assertEquals("192.168.1.127", WakeOnLan.broadcastOf(ip(192, 168, 1, 100), 25))
        assertEquals("255.255.255.255", WakeOnLan.broadcastOf(ip(1, 2, 3, 4), 0))
        assertNull(WakeOnLan.broadcastOf(ip(192, 168, 1, 1), 31), "No host part")
        assertNull(WakeOnLan.broadcastOf(ip(192, 168, 1, 1), 33))
        assertNull(WakeOnLan.broadcastOf(ByteArray(16), 64), "IPv6 has no broadcast")
    }

    @Test
    fun wakeTryEndsWokeOrDidntWake() {
        val sent = WakeFlow.sent(true, now = 1_000, viaWifi = true)
        assertEquals(WakeState.Waiting(1_000, true), sent)
        assertEquals(WakeState.NotSent, WakeFlow.sent(false, 1_000, true))
        // Still waiting a moment before the limit; woke as soon as it connects.
        assertEquals(sent, WakeFlow.step(sent, connected = false, now = 1_000 + WakeFlow.WAIT_MS - 1, wakeEnabled = true))
        assertEquals(WakeState.Woke, WakeFlow.step(sent, connected = true, now = 20_000, wakeEnabled = true))
        val failed = WakeFlow.step(sent, connected = false, now = 1_000 + WakeFlow.WAIT_MS, wakeEnabled = false, onBattery = true)
        assertEquals(WakeState.DidntWake(false, true, true), failed)
        // A finished try stays as it is, and connecting later doesn't turn a failure into a success.
        assertEquals(failed, WakeFlow.step(failed, connected = true, now = 99_000, wakeEnabled = true))
        assertEquals(WakeState.NotSent, WakeFlow.step(WakeState.NotSent, true, 99_000, true))
    }

    @Test
    fun reasonsFollowWhatTheMacReported() {
        val off = WakeFlow.reasons(WakeState.DidntWake(wakeEnabled = false, viaWifi = true, onBattery = true))
        assertTrue(off.first().contains("Wake for network access"), "The switch that is off comes first")
        assertTrue(off.any { it.contains("charger") && it.contains("on battery") })
        assertTrue(off.any { it.contains("Apple TV") && it.contains("HomePod") })
        assertTrue(off.any { it.contains("Ethernet") })
        val on = WakeFlow.reasons(WakeState.DidntWake(wakeEnabled = true, viaWifi = true, onBattery = null))
        assertFalse(on.any { it.contains("Wake for network access") }, "Not suggested when it's on")
        assertFalse(on.any { it.contains("on battery") })
        val unknown = WakeFlow.reasons(WakeState.DidntWake(wakeEnabled = null, viaWifi = false, onBattery = null))
        assertTrue(unknown.first().contains("Wi-Fi"))
        assertTrue(unknown.any { it.contains("Check that \"Wake for network access\"") })
        assertEquals("Your Mac didn't wake up. Open DyskLink to see why.", WakeFlow.summary(WakeState.DidntWake(null, true, null), "Mac"))
        assertTrue(WakeFlow.summary(WakeState.Waiting(0, true), "Air").contains("sent to Air"))
    }

    @Test
    fun aWakeSignalResetsTheRedialWait() = runBlocking {
        assertEquals(2_000, ConnectionManager.nextBackoff(16_000, kicked = true))
        assertEquals(30_000, ConnectionManager.nextBackoff(16_000, kicked = false))
        assertEquals(30_000, ConnectionManager.nextBackoff(30_000, kicked = false))

        val kicks = RedialKicks()
        val since = kicks.latest
        // Not kicked: the whole wait.
        assertFalse(kicks.wait(50, since, "mac"))
        // Kicked during the wait: it ends early.
        val waiting = async { kicks.wait(10_000, since, "mac") }
        delay(20)
        kicks.kick("mac")
        assertTrue(waiting.await())
        // A kick that came while dialing (before the wait started) still counts.
        val before = kicks.latest
        kicks.kick("mac")
        assertTrue(kicks.wait(10_000, before, "mac"))
        // Another device's kick doesn't end this one's wait; a target whose device isn't known yet takes any.
        val later = kicks.latest
        kicks.kick("other")
        assertFalse(kicks.wait(50, later, "mac"))
        assertTrue(kicks.kickedSince(later, null))
    }
}
