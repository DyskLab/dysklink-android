package id.phonelink.android.core

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun proofIsTheSameFromBothSides() {
        kotlin.test.assertEquals(Pairing.proof("123456", "aaa", "bbb"), Pairing.proof("123456", "bbb", "aaa"))
        kotlin.test.assertNotEquals(Pairing.proof("123456", "aaa", "bbb"), Pairing.proof("123457", "aaa", "bbb"))
        // Tied to the devices: someone in the middle has a different certificate, so the proof doesn't match.
        kotlin.test.assertNotEquals(Pairing.proof("123456", "aaa", "bbb"), Pairing.proof("123456", "aaa", "ccc"))
    }

    @org.junit.Test
    fun codesAreRandomSixDigits() {
        val codes = (1..200).map { Pairing.newCode() }
        kotlin.test.assertTrue(codes.all { it.length == 6 && it.all(Char::isDigit) })
        kotlin.test.assertTrue(codes.toSet().size > 150)
    }
}
