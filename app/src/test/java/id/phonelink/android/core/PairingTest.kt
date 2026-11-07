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
