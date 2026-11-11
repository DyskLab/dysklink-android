package id.phonelink.android.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckTest {
    @Test
    fun normalizesShortcuts() {
        assertEquals("shift+cmd+4", Deck.normalizeShortcut("Cmd + Shift + 4"))
        assertEquals("ctrl+cmd+q", Deck.normalizeShortcut("command+control+q"))
        assertEquals("opt+left", Deck.normalizeShortcut("alt+left"))
        assertEquals("shift+cmd+=", Deck.normalizeShortcut("cmd++"))
        assertEquals("f5", Deck.normalizeShortcut("F5"))
        assertNull(Deck.normalizeShortcut("cmd+shift"))
        assertNull(Deck.normalizeShortcut("hyper+c"))
        assertNull(Deck.normalizeShortcut("cmd+volumeup"))
        assertNull(Deck.normalizeShortcut(""))
    }

    @Test
    fun describesShortcutsLikeTheMac() {
        assertEquals("⇧⌘4", Deck.describeShortcut("shift+cmd+4"))
        assertEquals("⌃↑", Deck.describeShortcut("ctrl+up"))
        assertEquals("⌘Space", Deck.describeShortcut("cmd+space"))
    }

    @Test
    fun defaultsAreValidAndSurviveSaving() {
        assertTrue(Deck.defaults.all(Deck::isValid))
        assertEquals(Deck.defaults.size, Deck.defaults.map { it.id }.toSet().size)
        assertEquals(Deck.defaults, Deck.decode(Deck.encode(Deck.defaults)))
        assertEquals(Deck.defaults, Deck.decode("not json"))
        assertEquals(Deck.defaults, Deck.decode(null))
        val custom = listOf(DeckButton("x", "Hi", "👋", 2, DeckKind.Text, "Halo\n"))
        assertEquals(custom, Deck.decode(Deck.encode(custom)))
    }

    @Test
    fun checksButtons() {
        assertFalse(Deck.isValid(DeckButton("a", "", "⭐", 0, DeckKind.Shortcut, "cmd+c")))
        assertFalse(Deck.isValid(DeckButton("a", "X", "⭐", 0, DeckKind.Media, "eject")))
        assertFalse(Deck.isValid(DeckButton("a", "X", "⭐", 0, DeckKind.Text, "")))
        assertEquals("Command not on this Mac", Deck.detail(DeckButton("a", "X", "⭐", 0, DeckKind.Command, "gone"), null))
        assertEquals("Types Halo dunia", Deck.detail(DeckButton("a", "X", "⭐", 0, DeckKind.Text, "Halo dunia"), null))
    }

    @Test
    fun readyMadeShortcutsWork() {
        Deck.commonShortcuts.forEach { (name, shortcut) ->
            assertEquals(shortcut, Deck.normalizeShortcut(shortcut), name)
            val (mods, key) = Deck.splitShortcut(shortcut)!!
            assertEquals(shortcut, Deck.joinShortcut(mods, key), name)
        }
        assertEquals(Deck.commonShortcuts.size, Deck.commonShortcuts.map { it.first }.toSet().size)
        assertEquals("fn+f11", Deck.joinShortcut(setOf("fn"), "f11"))
        assertEquals("fn F11", Deck.describeShortcut("fn+f11"))
        assertEquals("ctrl+shift+cmd+4", Deck.joinShortcut(setOf("cmd", "shift", "ctrl"), "4"))
        assertEquals(null, Deck.joinShortcut(setOf("cmd"), ""))
    }
}
