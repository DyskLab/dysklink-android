package id.phonelink.android.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScanTextTest {
    @Test
    fun cleansScannedText() {
        assertEquals("8991002101234", ScanText.clean("8991002101234"))
        assertEquals("line 1\nline 2", ScanText.clean("line 1\r\nline 2\r\n"))
        assertEquals("ab", ScanText.clean("a\u0000\u001bb"))
        assertEquals("a\tb", ScanText.clean("a\tb"))
        assertNull(ScanText.clean("  \n "))
        assertNull(ScanText.clean(null))
        assertEquals(ScanText.MAX_LENGTH, ScanText.clean("x".repeat(5_000))?.length)
    }

    @Test
    fun typesThenPressesTheSuffix() {
        assertEquals(listOf(InputEvent("text", text = "123"), InputEvent("key", key = "return")), ScanText.events("123", ScanSuffix.Enter))
        assertEquals("tab", ScanText.events("123", ScanSuffix.Tab).last().key)
        assertEquals(1, ScanText.events("123", ScanSuffix.None).size)
    }

    @Test
    fun previewsOnOneLine() {
        assertEquals("a b", ScanText.preview("a\nb"))
        assertEquals("abcd…", ScanText.preview("abcdefgh", 5))
    }
}
