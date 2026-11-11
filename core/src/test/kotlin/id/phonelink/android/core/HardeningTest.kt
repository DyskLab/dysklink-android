package id.phonelink.android.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HardeningTest {
    @Test
    fun fiveWrongCodesLockPairingForLongerEachTime() {
        var now = 1_000_000L
        val guard = PairingGuard { now }
        repeat(4) { assertFalse(guard.wrong("a")) }
        assertEquals(0, guard.waitMs("a"))
        assertTrue(guard.wrong("a"), "The fifth wrong code must lock")
        assertEquals(60_000, guard.waitMs("a"))
        now += 60_000
        assertEquals(0, guard.waitMs("a"))
        repeat(4) { guard.wrong("a") }
        assertTrue(guard.wrong("a"))
        assertEquals(120_000, guard.waitMs("a"), "The second lock is twice as long")
        assertEquals(PairingGuard.MAX_COOLDOWN_MS, PairingGuard.cooldownMs(20), "Never more than an hour")
        // Typing the right code starts that device over.
        now += 120_000
        guard.succeeded("a")
        repeat(4) { assertFalse(guard.wrong("a")) }
    }

    @Test
    fun wrongCodesFromManyDevicesLockEveryone() {
        var now = 0L
        val guard = PairingGuard { now }
        val locked = (1..5).map { guard.wrong("device-$it") }
        assertEquals(listOf(false, false, false, false, true), locked)
        assertTrue(guard.waitMs("someone-new") > 0, "A fresh certificate must not get around the lock")
        now += PairingGuard.FIRST_COOLDOWN_MS
        assertEquals(0, guard.waitMs("someone-new"))
    }

    @Test
    fun reconnectingResetsTheBackoffOnlyAfterAConnectionThatHeld() {
        assertEquals(8_000, ConnectionManager.backoffAfter(8_000, null), "A failed dial keeps backing off")
        assertEquals(8_000, ConnectionManager.backoffAfter(8_000, 5_000), "A connection that dropped at once keeps backing off")
        assertEquals(2_000, ConnectionManager.backoffAfter(8_000, 31_000), "A connection that held starts over quickly")
    }

    @Test
    fun queuedTouchpadMovesMergeButClicksAndOtherPeersDont() {
        fun input(peer: String, kind: String, dx: Float? = null, dy: Float? = null) =
            peer to Envelope(MessageType.INPUT, input = InputEvent(kind, dx = dx, dy = dy))
        val merged = ConnectionManager.coalesce(input("mac", "move", 1.5f, 2f), input("mac", "move", 0.25f, -1f))
        assertEquals(1.75f, merged?.second?.input?.dx)
        assertEquals(1f, merged?.second?.input?.dy)
        assertEquals(-30f, ConnectionManager.coalesce(input("mac", "scroll", dy = -10f), input("mac", "scroll", dy = -20f))?.second?.input?.dy)
        assertNull(ConnectionManager.coalesce(input("mac", "move", 1f, 1f), input("mac", "click")))
        assertNull(ConnectionManager.coalesce(input("mac", "move", 1f, 1f), input("other", "move", 1f, 1f)))
        assertNull(ConnectionManager.coalesce(input("mac", "move", 1f, 1f), input("mac", "scroll", dy = 1f)))
    }

    @Test
    fun aMessageTooBigForTheOtherSideIsNotSent() {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        assertFalse(Frames.write(out, Envelope(MessageType.CLIPBOARD, text = "x".repeat(PhoneLinkProtocol.MAX_FRAME_BYTES))))
        assertEquals(0, bytes.size())
        assertTrue(Frames.write(out, Envelope(MessageType.CLIPBOARD, text = "small")))
        assertTrue(bytes.size() > 0)
    }
}
