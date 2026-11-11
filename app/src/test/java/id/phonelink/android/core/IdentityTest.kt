package id.phonelink.android.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdentityTest {
    @Test
    fun fileIdentityKeepsItsIdAndMakesTls() {
        val folder = Files.createTempDirectory("dysk-id").toFile()
        val first = LocalIdentity.loadOrCreate(folder, useKeystore = false)
        val again = LocalIdentity.loadOrCreate(folder, useKeystore = false)
        assertEquals(first.deviceId, again.deviceId)
        assertFalse(first.isHardwareBacked)
        assertTrue(first.deviceId.matches(Regex("[0-9a-f]{64}")))
        // The key manager hands out the key, so a TLS engine can be made with it.
        val engine = first.sslContext().createSSLEngine()
        engine.useClientMode = true
        engine.beginHandshake()
        val other = LocalIdentity.loadOrCreate(Files.createTempDirectory("dysk-id2").toFile(), useKeystore = false)
        assertNotEquals(first.deviceId, other.deviceId)
    }
}
