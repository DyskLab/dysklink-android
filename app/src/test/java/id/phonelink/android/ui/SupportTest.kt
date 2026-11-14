package id.phonelink.android.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupportTest {
    private val facts = Support.Facts(
        appVersion = "1.5.0", versionCode = 12, androidVersion = "16", sdk = 36,
        manufacturer = "samsung", model = "SM-S921B", connectedComputers = 1,
        privateNames = listOf("Budi's Galaxy"),
    )

    @Test
    fun messageHasVersionAndSystem() {
        val text = Support.message(facts)
        assertTrue(text.startsWith("Hi, I found a problem in DyskLink."))
        assertTrue(text.contains("DyskLink for Android 1.5.0 (12)"))
        assertTrue(text.contains("Android 16 (API 36), Samsung SM-S921B"))
        assertTrue(text.contains("Connected: 1 computer"))
        assertTrue(Support.message(facts.copy(connectedComputers = 2)).contains("Connected: 2 computers"))
    }

    @Test
    fun modelThatStartsWithTheMakerIsNotDoubled() {
        assertTrue(Support.message(facts.copy(manufacturer = "vivo", model = "vivo 1902")).contains(", vivo 1902\n"))
    }

    @Test
    fun messageLeavesOutThePhoneName() {
        val text = Support.message(facts.copy(model = "Budi's Galaxy"))
        assertFalse(text.contains("Budi"))
        assertFalse(Support.message(facts).contains("Budi"))
    }

    @Test
    fun urlEncodesNewlinesAmpersandAndHash() {
        assertEquals("https://wa.me/6285117241230?text=a%20b%0Ac%26d%23e%2Bf", Support.url("a b\nc&d#e+f"))
        val url = Support.url(Support.message(facts))
        assertTrue(url.startsWith("https://wa.me/${Support.WHATSAPP_NUMBER}?text=Hi%2C%20I%20found"))
        assertTrue(url.contains("%0A%0AWhat%20happened%3A"))
        assertFalse(url.contains("\n") || url.contains(" ") || url.contains("#"))
    }
}
