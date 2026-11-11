package id.phonelink.android.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IntentTokensTest {
    @Test
    fun aTokenWorksOnceForItsOwnRequest() {
        val tokens = IntentTokens()
        val token = tokens.issue(IntentTokens.CAMERA, "mac-1")
        assertTrue(tokens.consume(token, IntentTokens.CAMERA, "mac-1"))
        assertFalse(tokens.consume(token, IntentTokens.CAMERA, "mac-1"), "A token must not work twice")
    }

    @Test
    fun anIntentWithoutTheTokenStartsNothing() {
        val tokens = IntentTokens()
        tokens.issue(IntentTokens.SCREEN, "mac-1")
        assertFalse(tokens.consume(null, IntentTokens.SCREEN, "mac-1"))
        assertFalse(tokens.consume("", IntentTokens.SCREEN, "mac-1"))
        assertFalse(tokens.consume("00".repeat(16), IntentTokens.SCREEN, "mac-1"))
    }

    @Test
    fun aTokenIsBoundToItsPurposeAndPeer() {
        val tokens = IntentTokens()
        val token = tokens.issue(IntentTokens.SCREEN, "mac-1")
        assertFalse(tokens.consume(token, IntentTokens.CAMERA, "mac-1"))
        assertFalse(tokens.consume(token, IntentTokens.SCREEN, "mac-2"))
        // The wrong tries didn't use it up.
        assertTrue(tokens.consume(token, IntentTokens.SCREEN, "mac-1"))
    }

    @Test
    fun anOldTokenExpires() {
        var now = 1_000L
        val tokens = IntentTokens(clock = { now }, lifetimeMillis = 10_000L)
        val token = tokens.issue(IntentTokens.CAMERA, "mac-1")
        now += 10_001L
        assertFalse(tokens.consume(token, IntentTokens.CAMERA, "mac-1"))
    }

    @Test
    fun tokensAreRandom() {
        val tokens = IntentTokens()
        val a = tokens.issue(IntentTokens.CAMERA, "mac-1")
        val b = tokens.issue(IntentTokens.CAMERA, "mac-1")
        assertNotEquals(a, b)
        assertTrue(a.length == 32)
    }
}
