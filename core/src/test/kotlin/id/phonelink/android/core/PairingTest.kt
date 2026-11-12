package id.phonelink.android.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingTest {
    @Test
    fun codeDoesNotDependOnOrder() {
        assertEquals(Pairing.code("aaa", "bbb"), Pairing.code("bbb", "aaa"))
    }

    @Test
    fun codeMatchesProtocolFormula() {
        // Value calculated separately from the formula in docs/PROTOCOL.md in dysklink-mac.
        assertEquals(EXPECTED_ALPHA_BETA, Pairing.code("alpha", "beta"))
    }

    @Test
    fun envelopeIgnoresUnknownFields() {
        val parsed = protocolJson.decodeFromString(
            Envelope.serializer(),
            """{"type":"info","future":1,"info":{"batteryPercent":50,"other":"x"}}""",
        )
        assertEquals(50, parsed.info?.batteryPercent)
    }

    private companion object {
        const val EXPECTED_ALPHA_BETA = "184714"
    }
}

class TypedPairingTest {
    @org.junit.Test
    fun codesAreRandomSixDigits() {
        val codes = (1..200).map { Pairing.newCode() }
        kotlin.test.assertTrue(codes.all { it.length == 6 && it.all(Char::isDigit) })
        kotlin.test.assertTrue(codes.toSet().size > 150)
    }

    @Test
    fun oneShowerPerPromptAndANewNonceOnlyAfterAWrongCodeOrAWhile() {
        var now = 1_000L
        val slot = ShowerSlot { now }
        val ci = Pake.channel("aa", "bb")
        val first = ByteArray(16) { 1 }
        val plan = slot.start("123456", first, ci)
        // Nothing to resend while it's still being built.
        assertEquals(ShowerSlot.Renewal.Ignore, slot.renew("123456", first, ci))
        assertTrue(slot.install(plan, plan.build()))
        assertTrue(slot.renew("123456", first, ci) is ShowerSlot.Renewal.Resend)
        // New nonces soon after are ignored.
        assertEquals(ShowerSlot.Renewal.Ignore, slot.renew("123456", ByteArray(16) { 2 }, ci))
        now += ShowerSlot.RENEW_MS - 1
        assertEquals(ShowerSlot.Renewal.Ignore, slot.renew("123456", ByteArray(16) { 3 }, ci))
        // After a while, one goes through, and the clock starts again.
        now += 1
        val later = slot.renew("123456", ByteArray(16) { 4 }, ci) as ShowerSlot.Renewal.Build
        assertEquals(ShowerSlot.Renewal.Ignore, slot.renew("123456", ByteArray(16) { 5 }, ci))
        assertTrue(slot.install(later.plan, later.plan.build()))
        // A wrong code allows one right away, once.
        slot.wrongCode()
        val retry = slot.renew("123456", ByteArray(16) { 6 }, ci) as ShowerSlot.Renewal.Build
        assertEquals(ShowerSlot.Renewal.Ignore, slot.renew("123456", ByteArray(16) { 7 }, ci))
        // A plan from before the prompt closed isn't installed.
        slot.clear()
        assertFalse(slot.install(retry.plan, retry.plan.build()))
        assertNull(slot.shower)
    }
}
