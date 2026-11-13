package id.dysklink.desktop

import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Lyrics for the island's Music page, from LRCLIB (lrclib.net) as on the Mac (Lyrics.swift): an open lyrics database
// with timed lines and no API key. Spotify and YouTube have no public lyrics API, so the song's title and artist are
// looked up there instead. Off by default, because it sends the title and artist to lrclib.net.

data class LyricLine(val timeMs: Long, val text: String)

sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data object NotFound : LyricsState
    data object Failed : LyricsState
    data class Timed(val lines: List<LyricLine>) : LyricsState
    data class Plain(val lines: List<String>) : LyricsState
}

object LyricsParser {
    private val tag = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    /** Reads `[mm:ss.xx] text` lines. A line can carry several times. */
    fun parse(lrc: String): List<LyricLine> {
        val result = mutableListOf<LyricLine>()
        for (raw in lrc.lines()) {
            val matches = tag.findAll(raw).toList()
            val last = matches.lastOrNull() ?: continue
            val text = raw.substring(last.range.last + 1).trim()
            for (match in matches) {
                val minutes = match.groupValues[1].toLongOrNull() ?: continue
                val seconds = match.groupValues[2].toLongOrNull() ?: continue
                val digits = match.groupValues[3]
                val fraction = if (digits.isEmpty()) 0L else digits.toLong() * 1000 / pow10(digits.length)
                result += LyricLine(minutes * 60_000 + seconds * 1000 + fraction, text)
            }
        }
        return result.sortedBy { it.timeMs }
    }

    private fun pow10(n: Int): Long { var v = 1L; repeat(n) { v *= 10 }; return v }

    /** The line being sung at [elapsedMs], or null before the first one. */
    fun index(lines: List<LyricLine>, elapsedMs: Long): Int? =
        lines.indexOfLast { it.timeMs <= elapsedMs + 250 }.takeIf { it >= 0 }

    /**
     * The artist and title to look up. In a browser the artist is the channel: for YouTube Music it's the artist;
     * for YouTube, a video titled "Artist - Song" is split instead, else the channel loses "VEVO" and " - Topic".
     */
    fun song(playing: NowPlaying): Pair<String, String> {
        if (!playing.browser) return split(playing.title, playing.artist)
        val channel = cleanChannel(playing.artist)
        if (playing.url?.contains("music.youtube.com") == true) return split(playing.title, channel)
        val fromTitle = split(playing.title, "")
        if (fromTitle.first.isNotEmpty()) return fromTitle
        return channel to fromTitle.second
    }

    fun cleanChannel(name: String): String = name
        .replace(Regex("""\s*-\s*Topic$"""), "")
        .replace(Regex("""\s*VEVO$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+(Official|Music|Official Music)$""", RegexOption.IGNORE_CASE), "")
        .trim()

    /** "Artist - Song (Official Video) [HD]" becomes ("Artist", "Song"), for YouTube titles with no artist. */
    fun split(title: String, artist: String): Pair<String, String> {
        val cleaned = title
            .replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "")
            .replace(Regex("""\s*\|.*$"""), "")
            .replace(Regex("""\s+(Official\s+)?(Music\s+|Lyric\s+)?(Video|Audio|MV|Lyrics?)$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(ft\.?|feat\.?)\s.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (artist.isNotEmpty()) return artist to cleaned
        for (separator in listOf(" - ", " – ", " — ")) {
            val parts = cleaned.split(separator)
            if (parts.size >= 2) return parts[0].trim() to parts.drop(1).joinToString(separator).trim()
        }
        return "" to cleaned
    }
}

/**
 * The line to highlight. Small steps back from a corrected position, or a moment without one, keep the line where
 * it is, so the lyrics don't hop up and down; a real seek back of more than two seconds still moves it.
 */
class LyricLineTracker {
    private var lastLine: Int? = null
    private var lastElapsed: Long? = null

    fun reset() { lastLine = null; lastElapsed = null }

    fun line(lines: List<LyricLine>, elapsedMs: Long?): Int? {
        if (elapsedMs == null) return lastLine
        val found = LyricsParser.index(lines, elapsedMs)
        val line = lastLine
        val at = lastElapsed
        if (found != null && line != null && at != null && found < line && at - elapsedMs < 2_000) return line
        lastLine = found
        lastElapsed = elapsedMs
        return found
    }
}

/**
 * Japanese kana and Korean Hangul in Latin letters, made on this PC without the internet. The Mac reads kanji with
 * macOS's Japanese dictionary; Windows and Linux have none to ask, so kanji and Chinese characters stay as written.
 */
object Romanizer {
    fun isKana(c: Int) = c in 0x3040..0x30FF

    /** Anything outside Latin letters, punctuation, and symbols. */
    fun needs(text: String): Boolean = text.codePoints().anyMatch { Character.isLetter(it) && it > 127 && it !in 0x00C0..0x024F }

    fun isJapanese(lines: List<String>): Boolean = lines.any { line -> line.codePoints().anyMatch(::isKana) }

    /** Whether there's anything this can turn into Latin letters. */
    fun canRomanize(text: String): Boolean = text.codePoints().anyMatch { isKana(it) || isHangul(it) }

    fun romanize(text: String): String {
        if (!needs(text)) return text
        val out = StringBuilder()
        var i = 0
        var doubleNext = false
        var lastScript = Script.Other
        val chars = text.codePoints().toArray()
        while (i < chars.size) {
            val c = chars[i]
            val script = script(c)
            if (script != lastScript && out.isNotEmpty() && !out.last().isWhitespace() &&
                (script == Script.Kana || script == Script.Han || lastScript == Script.Kana || lastScript == Script.Han) &&
                script != Script.Punct && lastScript != Script.Punct
            ) out.append(' ')
            when {
                c in PUNCTUATION -> {
                    val mark = PUNCTUATION.getValue(c)
                    if (mark == " ") out.append(' ') else { trimEnd(out); out.append(mark); if (mark !in listOf("\"", "(")) out.append(' ') }
                }
                isHangul(c) -> out.append(hangul(c, chars.getOrNull(i + 1)))
                isKana(c) -> {
                    val h = if (c in 0x30A1..0x30F6) c - 0x60 else c
                    when (h) {
                        0x3063 -> doubleNext = true // small tsu
                        0x30FC -> out.lastVowel()?.let { out.append(it) } // long vowel mark
                        else -> {
                            var roman = KANA[h] ?: ""
                            val next = chars.getOrNull(i + 1)?.let { if (it in 0x30A1..0x30F6) it - 0x60 else it }
                            if (next != null && next in SMALL_Y) {
                                roman = combineY(roman, SMALL_Y.getValue(next)); i++
                            } else if (next != null && next in SMALL_VOWEL) {
                                roman = combineVowel(roman, SMALL_VOWEL.getValue(next)); i++
                            }
                            if (doubleNext && roman.isNotEmpty() && roman[0] !in "aeiou") {
                                out.append(if (roman.startsWith("ch")) "t" else roman[0].toString())
                            }
                            doubleNext = false
                            out.append(roman)
                        }
                    }
                }
                else -> out.appendCodePoint(c)
            }
            lastScript = script
            i++
        }
        return out.toString().replace(Regex("""\s{2,}"""), " ").replace(Regex(""" ([,.!?)\]])"""), "$1").trim()
    }

    private fun trimEnd(sb: StringBuilder) { while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1) }

    private fun StringBuilder.lastVowel(): Char? = lastOrNull { it in "aeiou" }

    private enum class Script { Kana, Han, Hangul, Punct, Other }

    private fun script(c: Int): Script = when {
        c in PUNCTUATION -> Script.Punct
        isKana(c) -> Script.Kana
        c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF -> Script.Han
        isHangul(c) -> Script.Hangul
        else -> Script.Other
    }

    private fun combineY(base: String, y: String): String = when (base) {
        "shi" -> "sh" + y.drop(1)
        "chi" -> "ch" + y.drop(1)
        "ji" -> "j" + y.drop(1)
        else -> base.dropLast(1) + y
    }

    private fun combineVowel(base: String, vowel: String): String = when {
        base == "fu" || base == "vu" || base == "tsu" -> base.dropLast(1) + vowel
        base == "te" && vowel == "i" -> "ti"
        base == "de" && vowel == "i" -> "di"
        base == "to" && vowel == "u" -> "tu"
        base == "shi" && vowel == "e" -> "she"
        base == "chi" && vowel == "e" -> "che"
        base == "ji" && vowel == "e" -> "je"
        base == "u" -> "w$vowel"
        else -> base + vowel
    }

    // Hangul: the Revised Romanization, syllable by syllable, with a final consonant moving onto a following vowel.

    fun isHangul(c: Int) = c in 0xAC00..0xD7A3

    private val INITIAL = listOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
    private val MEDIAL = listOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
    private val FINAL = listOf("", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t")
    /** A final consonant as it sounds before a vowel. */
    private val FINAL_LINKED = listOf("", "g", "kk", "ks", "n", "nj", "n", "d", "r", "lg", "lm", "lb", "ls", "lt", "lp", "r", "m", "b", "ps", "s", "ss", "ng", "j", "ch", "k", "t", "p", "")

    private fun hangul(c: Int, next: Int?): String {
        val index = c - 0xAC00
        val initial = index / (21 * 28)
        val medial = index % (21 * 28) / 28
        val final = index % 28
        val nextSilent = next != null && isHangul(next) && (next - 0xAC00) / (21 * 28) == 11
        return INITIAL[initial] + MEDIAL[medial] + if (nextSilent) FINAL_LINKED[final] else FINAL[final]
    }

    private val PUNCTUATION: Map<Int, String> = mapOf(
        '、'.code to ",", '，'.code to ",", '。'.code to ".", '！'.code to "!", '？'.code to "?", '「'.code to "\"", '」'.code to "\"",
        '『'.code to "\"", '』'.code to "\"", '（'.code to "(", '）'.code to ")", '・'.code to " ", '〜'.code to "~", '～'.code to "~",
        '　'.code to " ", '､'.code to ",", '｡'.code to ".",
    )

    private val SMALL_Y = mapOf(0x3083 to "ya", 0x3085 to "yu", 0x3087 to "yo")
    private val SMALL_VOWEL = mapOf(0x3041 to "a", 0x3043 to "i", 0x3045 to "u", 0x3047 to "e", 0x3049 to "o")

    private val KANA: Map<Int, String> = buildMap {
        val rows = listOf(
            "あいうえお" to listOf("a", "i", "u", "e", "o"),
            "かきくけこ" to listOf("ka", "ki", "ku", "ke", "ko"), "がぎぐげご" to listOf("ga", "gi", "gu", "ge", "go"),
            "さしすせそ" to listOf("sa", "shi", "su", "se", "so"), "ざじずぜぞ" to listOf("za", "ji", "zu", "ze", "zo"),
            "たちつてと" to listOf("ta", "chi", "tsu", "te", "to"), "だぢづでど" to listOf("da", "ji", "zu", "de", "do"),
            "なにぬねの" to listOf("na", "ni", "nu", "ne", "no"),
            "はひふへほ" to listOf("ha", "hi", "fu", "he", "ho"), "ばびぶべぼ" to listOf("ba", "bi", "bu", "be", "bo"),
            "ぱぴぷぺぽ" to listOf("pa", "pi", "pu", "pe", "po"),
            "まみむめも" to listOf("ma", "mi", "mu", "me", "mo"),
            "やゆよ" to listOf("ya", "yu", "yo"),
            "らりるれろ" to listOf("ra", "ri", "ru", "re", "ro"),
            "わをん" to listOf("wa", "o", "n"),
            "ゐゑゔ" to listOf("i", "e", "vu"),
            "ぁぃぅぇぉゃゅょゎ" to listOf("a", "i", "u", "e", "o", "ya", "yu", "yo", "wa"),
        )
        for ((kana, roman) in rows) kana.forEachIndexed { i, ch -> put(ch.code, roman[i]) }
    }
}

/** Lyrics through LRCLIB's two endpoints, as the Mac asks them. */
object LrcLib {
    @Serializable
    data class Item(val syncedLyrics: String? = null, val plainLyrics: String? = null, val instrumental: Boolean? = null, val duration: Double? = null)

    private val json = Json { ignoreUnknownKeys = true }

    fun exactUrl(artist: String, title: String, durationMs: Long?): String =
        "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}" +
            (durationMs?.let { "&duration=${(it + 500) / 1000}" } ?: "")

    fun searchUrl(query: String) = "https://lrclib.net/api/search?q=${enc(query)}"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** With the artist first, then the title alone, since a YouTube channel isn't always the artist. */
    fun queries(artist: String, title: String): List<String> =
        listOf(listOf(artist, title).filter { it.isNotEmpty() }.joinToString(" "), title).filter { it.isNotEmpty() }.distinct()

    /** A result about as long as the song comes first; a video can run a little longer with its intro. */
    fun ranked(items: List<Item>, durationMs: Long?): List<Item> {
        val seconds = durationMs?.div(1000.0) ?: return items
        val close = items.filter { it.duration?.let { d -> kotlin.math.abs(d - seconds) <= 8 } ?: false }
        return close + items.filter { it !in close }
    }

    fun state(item: Item): LyricsState? {
        item.syncedLyrics?.let { LyricsParser.parse(it) }?.takeIf { it.isNotEmpty() }?.let { return LyricsState.Timed(it) }
        item.plainLyrics?.takeIf { it.isNotEmpty() }?.let { plain -> return LyricsState.Plain(plain.lines().map { it.trim() }) }
        return null
    }

    fun parseItem(body: String): Item? = runCatching { json.decodeFromString(Item.serializer(), body) }.getOrNull()
    fun parseList(body: String): List<Item> = runCatching { json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Item.serializer()), body) }.getOrDefault(emptyList())

    /** The HTTP status and body, or null when the server can't be reached. */
    fun get(url: String): Pair<Int, String>? = runCatching {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 12_000
        // LRCLIB asks clients to name themselves.
        connection.setRequestProperty("User-Agent", "DyskLink (https://github.com/DyskLab/dysklink)")
        try {
            val code = connection.responseCode
            code to (if (code == 200) connection.inputStream.bufferedReader().readText() else "")
        } finally { connection.disconnect() }
    }.getOrNull()

    /** Looks the song up. [fetch] is there for tests. */
    fun lookup(artist: String, title: String, durationMs: Long?, fetch: (String) -> Pair<Int, String>? = ::get): LyricsState {
        if (artist.isNotEmpty()) {
            val reply = fetch(exactUrl(artist, title, durationMs)) ?: return LyricsState.Failed
            if (reply.first == 200) parseItem(reply.second)?.let(::state)?.let { return it }
        }
        for (query in queries(artist, title)) {
            val reply = fetch(searchUrl(query)) ?: return LyricsState.Failed
            if (reply.first != 200) return LyricsState.Failed
            ranked(parseList(reply.second), durationMs).firstNotNullOfOrNull(::state)?.let { return it }
        }
        return LyricsState.NotFound
    }
}

/**
 * The lyrics column's state: looked up once per song, remembered for the last 50 songs (a failure isn't, so it's
 * tried again next time), and shown in Latin letters on request.
 */
class LyricsService(
    private val settings: Settings?,
    private val fetch: (String) -> Pair<Int, String>? = LrcLib::get,
) {
    private val _isOn = MutableStateFlow(settings?.bool("island.lyrics", false) ?: false)
    val isOn: StateFlow<Boolean> = _isOn.asStateFlow()
    private val _romanizes = MutableStateFlow(settings?.bool("island.lyricsRomaji", false) ?: false)
    val romanizes: StateFlow<Boolean> = _romanizes.asStateFlow()
    private val _state = MutableStateFlow<LyricsState>(LyricsState.Idle)
    val state: StateFlow<LyricsState> = _state.asStateFlow()
    private val cache = LinkedHashMap<String, LyricsState>(16, 0.75f, true)
    @Volatile private var key: String? = null
    val tracker = LyricLineTracker()

    fun setOn(on: Boolean) {
        _isOn.value = on
        settings?.set("island.lyrics", on)
        if (!on) { _state.value = LyricsState.Idle; key = null }
    }

    fun setRomanizes(on: Boolean) {
        _romanizes.value = on
        settings?.set("island.lyricsRomaji", on)
    }

    /** For snapshots. */
    fun show(state: LyricsState) { _state.value = state; tracker.reset() }

    suspend fun load(playing: NowPlaying?) {
        if (!_isOn.value || playing == null || playing.title.isEmpty()) return
        val (artist, title) = LyricsParser.song(playing)
        val songKey = "${artist.lowercase()}|${title.lowercase()}"
        if (songKey == key) return
        key = songKey
        tracker.reset()
        synchronized(cache) { cache[songKey] }?.let { _state.value = it; return }
        _state.value = LyricsState.Loading
        val result = withContext(Dispatchers.IO) { LrcLib.lookup(artist, title, playing.durationMs, fetch) }
        if (key != songKey) return
        if (result != LyricsState.Failed) synchronized(cache) {
            cache[songKey] = result
            while (cache.size > CACHE_LIMIT) cache.remove(cache.keys.first())
        }
        _state.value = result
    }

    companion object {
        const val CACHE_LIMIT = 50

        /** The same lyrics in Latin letters, or null when nothing in them can be. */
        fun romanized(state: LyricsState): LyricsState? = when (state) {
            is LyricsState.Timed -> if (state.lines.none { Romanizer.canRomanize(it.text) }) null
                else LyricsState.Timed(state.lines.map { LyricLine(it.timeMs, Romanizer.romanize(it.text)) })
            is LyricsState.Plain -> if (state.lines.none(Romanizer::canRomanize)) null else LyricsState.Plain(state.lines.map(Romanizer::romanize))
            else -> null
        }
    }
}
