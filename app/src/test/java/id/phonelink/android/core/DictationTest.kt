package id.phonelink.android.core

import kotlin.test.Test
import kotlin.test.assertEquals

class DictationTest {
    private val id = DictationLanguage.Indonesian
    private val en = DictationLanguage.English

    @Test
    fun turnsSpokenPunctuationIntoMarks() {
        assertEquals("Halo apa kabar?", Dictation.format("halo apa kabar tanda tanya", id, ""))
        assertEquals("Satu, dua.", Dictation.format("satu koma dua titik", id, ""))
        assertEquals("Waktu: sekarang", Dictation.format("waktu titik dua sekarang", id, ""))
        assertEquals("Hello, world.", Dictation.format("hello comma world period", en, ""))
        assertEquals("Wow!", Dictation.format("wow exclamation mark", en, ""))
    }

    @Test
    fun continuesFromWhatWasTyped() {
        // Mid-sentence: a space in front and no capital.
        assertEquals(" lalu pulang", Dictation.format("lalu pulang", id, "Saya makan"))
        // After a full stop: a space and a capital.
        assertEquals(" Besok lagi.", Dictation.format("besok lagi titik", id, "Selesai."))
        // At the start of a new line: no space.
        assertEquals("Baris dua", Dictation.format("baris dua", id, "Satu\n"))
    }

    @Test
    fun newLinesBecomeEnter() {
        val text = Dictation.format("satu baris baru dua", id, "")
        assertEquals("Satu\nDua", text)
        assertEquals(
            listOf(InputEvent("text", text = "Satu"), InputEvent("key", key = "return"), InputEvent("text", text = "Dua")),
            Dictation.events(text),
        )
        assertEquals("A.\n\nB", Dictation.format("a period new paragraph b", en, ""))
        assertEquals(2, Dictation.events("\n\n").size)
    }

    @Test
    fun leavesOrdinaryWordsAlone() {
        // "titikan" is not "titik", and English commands do nothing in Indonesian.
        assertEquals("Titikan period", Dictation.format("titikan period", id, ""))
        assertEquals("", Dictation.format("   ", id, ""))
    }
}
