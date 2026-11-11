package id.phonelink.android.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerminalEmulatorTest {
    private val esc = "\u001b"

    @Test
    fun textWrapsAndScrolls() {
        val term = TerminalEmulator(5, 3)
        term.feed("abcdefg\r\nhi\r\nx\r\ny")
        // Five lines on a three-row screen: the first two scroll off and are kept.
        assertEquals(2, term.scrollbackSize)
        assertEquals("abcde", term.line(0).text())
        assertTrue(term.line(0).wrapped)
        assertEquals("fg", term.line(1).text())
        assertEquals(listOf("hi", "x", "y"), (0 until 3).map { term.screenLine(it).text() })
    }

    @Test
    fun cursorMovesAndErases() {
        val term = TerminalEmulator(20, 3)
        term.feed("hello world$esc[1;1Hx$esc[2;3Hy$esc[1;4H$esc[K")
        assertEquals("xel", term.screenLine(0).text())
        assertEquals("  y", term.screenLine(1).text())
        term.feed("$esc[2J")
        assertEquals("", term.screenText())
    }

    @Test
    fun colorsAndStyles() {
        val term = TerminalEmulator(20, 2)
        term.feed("$esc[1;31mA$esc[0m$esc[38;5;208mB$esc[38;2;10;20;30;48;5;4mC$esc[38:2::1:2:3mD$esc[7mE")
        val line = term.screenLine(0)
        assertEquals(1, line.fg[0])
        assertEquals(TerminalEmulator.BOLD, line.flags[0].toInt())
        assertEquals(208, line.fg[1])
        assertEquals(TerminalEmulator.rgb(10, 20, 30), line.fg[2])
        assertEquals(4, line.bg[2])
        assertEquals(TerminalEmulator.rgb(1, 2, 3), line.fg[3])
        assertTrue(line.flags[4].toInt() and TerminalEmulator.INVERSE != 0)
    }

    @Test
    fun fullScreenProgramsUseTheOtherScreen() {
        val term = TerminalEmulator(10, 3)
        term.feed("prompt$ ")
        term.feed("$esc[?1049h$esc[Hvim here")
        assertTrue(term.isAltScreen)
        assertEquals("vim here", term.screenLine(0).text())
        term.feed("$esc[?1049l")
        assertFalse(term.isAltScreen)
        assertEquals("prompt$", term.screenLine(0).text())
        assertEquals(8, term.cursorX)
    }

    @Test
    fun scrollRegionLikeLess() {
        val term = TerminalEmulator(5, 4)
        term.feed("1\r\n2\r\n3\r\n4")
        term.feed("$esc[1;3r$esc[3;1H\n")
        assertEquals(listOf("2", "3", "", "4"), (0 until 4).map { term.screenLine(it).text() })
        // Scrolling inside a region keeps nothing to scroll back to.
        assertEquals(0, term.scrollbackSize)
    }

    @Test
    fun repliesAndUtf8() {
        val term = TerminalEmulator(20, 3)
        val replies = mutableListOf<String>()
        term.onReply = { replies += it }
        term.feed("$esc[2;5H$esc[6n")
        assertEquals("$esc[2;5R", replies.single())
        val bytes = "é漢x".toByteArray()
        // Split in the middle of a letter, as network reads can.
        term.feed(bytes.copyOfRange(0, 1)); term.feed(bytes.copyOfRange(1, bytes.size))
        assertEquals("é漢x", term.screenLine(1).text().trim())
        assertEquals(TerminalEmulator.WIDE_TAIL, term.screenLine(1).chars[6])
        term.feed("$esc(0lqk$esc(B")
        assertTrue(term.screenLine(1).text().endsWith("┌─┐"))
    }

    @Test
    fun resizeKeepsThePrompt() {
        val term = TerminalEmulator(10, 5)
        term.feed("a\r\nb\r\nc\r\nd\r\n$ ")
        term.resize(8, 2)
        assertEquals("$", term.screenLine(1).text())
        assertEquals(1, term.cursorY)
        assertEquals(3, term.scrollbackSize)
    }

    @Test
    fun keys() {
        assertEquals("$esc[A", TerminalKeys.key(TerminalKeys.Key.Up, appCursor = false))
        assertEquals("${esc}OA", TerminalKeys.key(TerminalKeys.Key.Up, appCursor = true))
        assertEquals("$esc[1;5C", TerminalKeys.key(TerminalKeys.Key.Right, appCursor = false, ctrl = true))
        assertEquals("\u0003", TerminalKeys.text("c", ctrl = true, alt = false))
        assertEquals("${esc}b", TerminalKeys.text("b", ctrl = false, alt = true))
        assertEquals("$esc[200~a\rb$esc[201~", TerminalKeys.paste("a\nb", bracketed = true))
    }
}
