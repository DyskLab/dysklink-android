package id.phonelink.android.core

/** Languages offered for dictation, as BCP 47 tags for Android's recognizer. */
enum class DictationLanguage(val label: String, val tag: String) {
    Indonesian("Indonesia", "id-ID"),
    English("English", "en-US"),
}

/**
 * Turns recognized speech into what is typed on the Mac. Spoken punctuation such as "titik" or
 * "comma" becomes the mark, "baris baru" or "new line" presses Enter, and sentences start with a capital.
 */
object Dictation {
    sealed interface Piece {
        data class Text(val text: String) : Piece
        data class Key(val key: String) : Piece
    }

    private const val NEW_LINE = "\n"
    private const val NEW_PARAGRAPH = "\n\n"

    /** Longest phrases first, so "tanda tanya" wins over a shorter match. */
    private val commands: Map<DictationLanguage, List<Pair<List<String>, String>>> = mapOf(
        DictationLanguage.Indonesian to listOf(
            "paragraf baru" to NEW_PARAGRAPH,
            "baris baru" to NEW_LINE,
            "tanda tanya" to "?",
            "tanda seru" to "!",
            "titik dua" to ":",
            "titik koma" to ";",
            "buka kurung" to "(",
            "tutup kurung" to ")",
            "titik" to ".",
            "koma" to ",",
        ),
        DictationLanguage.English to listOf(
            "new paragraph" to NEW_PARAGRAPH,
            "new line" to NEW_LINE,
            "question mark" to "?",
            "exclamation mark" to "!",
            "exclamation point" to "!",
            "full stop" to ".",
            "semicolon" to ";",
            "colon" to ":",
            "open bracket" to "(",
            "close bracket" to ")",
            "period" to ".",
            "comma" to ",",
        ),
    ).mapValues { (_, list) -> list.map { (phrase, mark) -> phrase.split(' ') to mark }.sortedByDescending { it.first.size } }

    private val attachesLeft = setOf(".", ",", "?", "!", ":", ";", ")")
    private val sentenceEnd = setOf('.', '?', '!', '\n')

    /**
     * Builds the text for one result. [before] is what was typed last in this session, used to
     * decide the space in front and the capital letter. Returns plain text with \n for new lines.
     */
    fun format(spoken: String, language: DictationLanguage, before: String): String {
        val words = spoken.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""
        val table = commands.getValue(language)
        val tokens = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val match = table.firstOrNull { (phrase, _) ->
                i + phrase.size <= words.size &&
                    phrase.indices.all { words[i + it].lowercase().trim(',', '.') == phrase[it] }
            }
            if (match != null) {
                tokens += match.second
                i += match.first.size
            } else {
                tokens += words[i]
                i++
            }
        }
        val out = StringBuilder()
        var last = before
        for (token in tokens) {
            val isNewLine = token == NEW_LINE || token == NEW_PARAGRAPH
            val joined = when {
                isNewLine -> token
                token in attachesLeft -> token
                last.isEmpty() || last.endsWith("\n") || last.endsWith("(") || last.endsWith(" ") -> capitalize(token, last)
                else -> " " + capitalize(token, last)
            }
            out.append(joined)
            last += joined
        }
        return out.toString()
    }

    private fun capitalize(word: String, before: String): String {
        val end = before.trimEnd(' ').lastOrNull()
        return if (end == null || end in sentenceEnd) word.replaceFirstChar { it.uppercase() } else word
    }

    /** Splits text into typing and Enter presses, since a typed \n isn't a reliable new line in every Mac app. */
    fun pieces(text: String): List<Piece> {
        val result = mutableListOf<Piece>()
        text.split('\n').forEachIndexed { index, part ->
            if (index > 0) result += Piece.Key("return")
            if (part.isNotEmpty()) result += Piece.Text(part)
        }
        return result
    }

    fun events(text: String): List<InputEvent> = pieces(text).map {
        when (it) {
            is Piece.Text -> InputEvent("text", text = it.text)
            is Piece.Key -> InputEvent("key", key = it.key)
        }
    }
}
