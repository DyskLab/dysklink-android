package id.phonelink.android.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** What a Stream Deck button does on the Mac. Each maps to a message the Mac already accepts. */
@Serializable
enum class DeckKind(val label: String) {
    Shortcut("Shortcut"),
    Media("Media"),
    Text("Type text"),
    Command("Mac command"),
    Obs("OBS"),
}

@Serializable
data class DeckButton(
    val id: String,
    val label: String,
    /** Kept so older saved buttons still load. Buttons are shown as text only. */
    val emoji: String = "",
    val color: Int,
    val kind: DeckKind,
    /** "cmd+c" for a shortcut, "play_pause" for media, the text to type, or a Mac command's id. */
    val value: String,
    /** A flat icon by name, or empty for text only. */
    val icon: String = "",
)

object Deck {
    const val MAX_BUTTONS = 24
    const val MAX_TEXT = 1_000

    /** Indexes into the palette, so saved buttons don't depend on exact colors. */
    const val COLORS = 6

    val media = listOf(
        "play_pause" to "Play / pause",
        "next" to "Next",
        "previous" to "Previous",
        "volume_up" to "Volume up",
        "volume_down" to "Volume down",
        "mute" to "Mute",
    )

    private val modifierNames = mapOf(
        "cmd" to "cmd", "command" to "cmd", "shift" to "shift",
        "opt" to "opt", "option" to "opt", "alt" to "opt",
        "ctrl" to "ctrl", "control" to "ctrl", "fn" to "fn",
    )
    /** In the order the Mac shows them. */
    val modifierOrder = listOf("fn", "ctrl", "opt", "shift", "cmd")
    private val symbols = mapOf("fn" to "fn ", "ctrl" to "⌃", "opt" to "⌥", "shift" to "⇧", "cmd" to "⌘")

    /** Modifier names for the editor's buttons. */
    val modifierLabels = mapOf("ctrl" to "⌃ Control", "opt" to "⌥ Option", "shift" to "⇧ Shift", "cmd" to "⌘ Command", "fn" to "fn")

    /** Keys offered as buttons in the editor, besides typing a letter or number. */
    val specialKeys = listOf(
        "return" to "Return", "tab" to "Tab", "space" to "Space", "escape" to "Esc", "delete" to "Delete",
        "left" to "←", "right" to "→", "up" to "↑", "down" to "↓",
    ) + (1..12).map { "f$it" to "F$it" }

    /** Ready-made shortcuts, so nobody has to remember how to write them. */
    val commonShortcuts: List<Pair<String, String>> = listOf(
        "Copy" to "cmd+c",
        "Paste" to "cmd+v",
        "Cut" to "cmd+x",
        "Undo" to "cmd+z",
        "Redo" to "shift+cmd+z",
        "Select all" to "cmd+a",
        "Save" to "cmd+s",
        "Find" to "cmd+f",
        "Print" to "cmd+p",
        "New tab" to "cmd+t",
        "Reopen tab" to "shift+cmd+t",
        "Close tab" to "cmd+w",
        "Next tab" to "ctrl+tab",
        "Previous tab" to "ctrl+shift+tab",
        "Reload page" to "cmd+r",
        "New window" to "cmd+n",
        "Minimize" to "cmd+m",
        "Hide app" to "cmd+h",
        "Quit app" to "cmd+q",
        "Full screen" to "ctrl+cmd+f",
        "Zoom in" to "cmd+=",
        "Zoom out" to "cmd+-",
        "Spotlight" to "cmd+space",
        "Mission Control" to "ctrl+up",
        "App windows" to "ctrl+down",
        "Desktop left" to "ctrl+left",
        "Desktop right" to "ctrl+right",
        "Show desktop" to "fn+f11",
        "Screenshot area" to "shift+cmd+4",
        "Screenshot screen" to "shift+cmd+3",
        "Screenshot tools" to "shift+cmd+5",
        "Emoji picker" to "ctrl+cmd+space",
        "Force quit" to "opt+cmd+escape",
        "Lock screen" to "ctrl+cmd+q",
        "DyskLink Launcher" to "opt+space",
    )

    /** Splits a saved shortcut into its modifiers and key, for the editor. */
    fun splitShortcut(shortcut: String): Pair<Set<String>, String>? {
        val normal = normalizeShortcut(shortcut) ?: return null
        val parts = normal.split('+')
        // "cmd+=" splits fine; only a trailing "+" key would be a problem, and normalizing never makes one.
        return parts.dropLast(1).toSet() to parts.last()
    }

    fun joinShortcut(modifiers: Set<String>, key: String): String? =
        normalizeShortcut((modifierOrder.filter { it in modifiers } + key.lowercase()).joinToString("+"))

    /** The same keys the Mac accepts, so the editor can tell before sending. */
    private val keys: Set<String> = buildSet {
        addAll(('a'..'z').map { it.toString() })
        addAll(('0'..'9').map { it.toString() })
        addAll(listOf("=", "-", "]", "[", "'", ";", "\\", ",", "/", ".", "`"))
        addAll(listOf("return", "enter", "tab", "space", "delete", "escape", "esc", "forwarddelete", "home", "end", "pageup", "pagedown", "left", "right", "down", "up"))
        addAll((1..12).map { "f$it" })
    }
    private val keySymbols = mapOf(
        "return" to "↩", "enter" to "↩", "tab" to "⇥", "space" to "Space", "delete" to "⌫", "escape" to "Esc", "esc" to "Esc",
        "forwarddelete" to "⌦", "left" to "←", "right" to "→", "up" to "↑", "down" to "↓", "pageup" to "Page Up", "pagedown" to "Page Down",
        "home" to "Home", "end" to "End",
    )

    /** "Cmd + Shift + 4" becomes "cmd+shift+4", or null if the Mac wouldn't accept it. */
    fun normalizeShortcut(text: String): String? {
        val raw = text.lowercase().replace(" ", "")
        if (raw.isEmpty() || raw.length > 40) return null
        val plusKey = raw.endsWith("++")
        val parts = raw.split('+').filter { it.isNotEmpty() }.toMutableList()
        if (plusKey) parts += "="
        val key = parts.removeLastOrNull() ?: return null
        if (key !in keys) return null
        val mods = parts.map { modifierNames[it] ?: return null }.toMutableSet()
        if (plusKey) mods += "shift"
        return (modifierOrder.filter { it in mods } + key).joinToString("+")
    }

    /** "cmd+shift+4" as the Mac shows it: "⇧⌘4". */
    fun describeShortcut(shortcut: String): String {
        val parts = shortcut.split('+')
        val key = parts.last()
        val mods = parts.dropLast(1)
        return modifierOrder.filter { it in mods }.joinToString("") { symbols.getValue(it) } +
            (keySymbols[key] ?: key.uppercase())
    }

    fun isValid(button: DeckButton): Boolean = button.label.isNotBlank() && when (button.kind) {
        DeckKind.Shortcut -> normalizeShortcut(button.value) != null
        DeckKind.Media -> media.any { it.first == button.value }
        DeckKind.Text -> button.value.isNotEmpty() && button.value.length <= MAX_TEXT
        DeckKind.Command -> button.value.isNotBlank()
        DeckKind.Obs -> ObsAction.decode(button.value) != null
    }

    /** OBS buttons talk to OBS directly. Every other kind goes through the Mac. */
    fun needsMac(button: DeckButton): Boolean = button.kind != DeckKind.Obs

    /** A short line under the label, so a button explains itself. */
    fun detail(button: DeckButton, commandName: String?): String = when (button.kind) {
        DeckKind.Shortcut -> normalizeShortcut(button.value)?.let(::describeShortcut) ?: "Not a valid shortcut"
        DeckKind.Media -> media.firstOrNull { it.first == button.value }?.second ?: "Media"
        DeckKind.Text -> "Types " + button.value.replace('\n', ' ').let { if (it.length > 18) it.take(17) + "…" else it }
        DeckKind.Command -> commandName ?: "Command not on this Mac"
        DeckKind.Obs -> ObsAction.decode(button.value)?.let(ObsProtocol::label) ?: "OBS"
    }

    val defaults: List<DeckButton> = listOf(
        DeckButton("copy", "Copy", "", 0, DeckKind.Shortcut, "cmd+c", icon = "copy"),
        DeckButton("paste", "Paste", "", 0, DeckKind.Shortcut, "cmd+v", icon = "paste"),
        DeckButton("undo", "Undo", "", 0, DeckKind.Shortcut, "cmd+z", icon = "undo"),
        DeckButton("spotlight", "Spotlight", "", 1, DeckKind.Shortcut, "cmd+space", icon = "search"),
        DeckButton("mission", "Mission Control", "", 1, DeckKind.Shortcut, "ctrl+up", icon = "windows"),
        DeckButton("screenshot", "Screenshot", "", 1, DeckKind.Shortcut, "cmd+shift+4", icon = "screenshot"),
        DeckButton("play", "Play / pause", "", 2, DeckKind.Media, "play_pause", icon = "play"),
        DeckButton("prev", "Previous", "", 2, DeckKind.Media, "previous", icon = "previous"),
        DeckButton("next", "Next", "", 2, DeckKind.Media, "next", icon = "next"),
        DeckButton("voldown", "Volume down", "", 3, DeckKind.Media, "volume_down", icon = "volume_down"),
        DeckButton("volup", "Volume up", "", 3, DeckKind.Media, "volume_up", icon = "volume_up"),
        DeckButton("lock", "Lock screen", "", 4, DeckKind.Shortcut, "ctrl+cmd+q", icon = "lock"),
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(buttons: List<DeckButton>): String = json.encodeToString(buttons)

    /** Saved buttons, or the defaults when nothing is saved or the data is damaged. */
    fun decode(text: String?): List<DeckButton> =
        text?.let { runCatching { json.decodeFromString<List<DeckButton>>(it) }.getOrNull() }
            ?.filter { it.color in 0 until COLORS }
            ?.take(MAX_BUTTONS)
            ?: defaults
}
