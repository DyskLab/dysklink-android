package id.dysklink.desktop

import java.util.Locale

/**
 * Emoji for the Launcher, as on the Mac: found by their Unicode name in English or by common Indonesian words, every
 * country flag by its English or Indonesian name or code, common joined emoji, and skin tones by a tone word
 * (terang, langsat, sawo, cokelat, gelap, or tone1 to tone5; kuning goes back to plain).
 */
object LauncherEmoji {
    data class Entry(val emoji: String, val name: String, val words: List<String>)

    val all: List<Entry> by lazy { build() }
    private val byKey: Map<String, Entry> by lazy { all.associateBy { normalized(it.emoji) } }

    /** Common Indonesian (and a few English) words the Unicode names don't contain. */
    val aliases: Map<String, List<String>> = mapOf(
        "senyum" to listOf("😊", "🙂", "😄", "😁"), "tawa" to listOf("😂", "🤣", "😆"), "ketawa" to listOf("😂", "🤣", "😆"), "ngakak" to listOf("🤣", "😂"),
        "lol" to listOf("😂", "🤣"), "wkwk" to listOf("🤣", "😂"), "sedih" to listOf("😢", "😭", "😞", "🥺"), "nangis" to listOf("😭", "😢"), "menangis" to listOf("😭", "😢"),
        "marah" to listOf("😡", "😠", "🤬"), "kesal" to listOf("😤", "😒"), "kaget" to listOf("😮", "😱", "😲"), "takut" to listOf("😨", "😱"), "malu" to listOf("😳", "☺️"),
        "ngantuk" to listOf("😴", "🥱"), "tidur" to listOf("😴", "💤"), "bingung" to listOf("😕", "🤔"), "mikir" to listOf("🤔"), "pikir" to listOf("🤔"),
        "keren" to listOf("😎", "🔥"), "cium" to listOf("😘", "😚"), "sayang" to listOf("🥰", "❤️", "😘"), "cinta" to listOf("❤️", "😍", "🥰", "💕"), "love" to listOf("❤️", "😍", "🥰"),
        "hati" to listOf("❤️", "💖", "💕"), "heart" to listOf("❤️"), "merah" to listOf("❤️", "🔴"), "red" to listOf("❤️", "🔴"), "patah" to listOf("💔"), "sakit" to listOf("🤒", "🤕", "😷"),
        "muntah" to listOf("🤮"), "mual" to listOf("🤢"), "setan" to listOf("😈", "👿"), "hantu" to listOf("👻"), "tengkorak" to listOf("💀"), "mati" to listOf("💀"), "badut" to listOf("🤡"),
        "jempol" to listOf("👍"), "mantap" to listOf("👍", "🔥", "💯"), "oke" to listOf("👌", "👍"), "ok" to listOf("👌", "👍"), "tepuk" to listOf("👏"), "salam" to listOf("🙏", "👋"),
        "doa" to listOf("🙏", "🤲"), "terima" to listOf("🙏"), "makasih" to listOf("🙏"), "thanks" to listOf("🙏"), "lambai" to listOf("👋"), "dadah" to listOf("👋"), "halo" to listOf("👋"),
        "kuat" to listOf("💪"), "otot" to listOf("💪"), "tunjuk" to listOf("👉", "👆", "👇", "👈"), "mata" to listOf("👀"), "lihat" to listOf("👀"), "diam" to listOf("🤫"),
        "api" to listOf("🔥"), "panas" to listOf("🔥", "🥵"), "dingin" to listOf("🥶", "❄️"), "bintang" to listOf("⭐", "🌟"), "bulan" to listOf("🌙"), "matahari" to listOf("☀️"),
        "hujan" to listOf("🌧️", "☔"), "awan" to listOf("☁️"), "petir" to listOf("⚡"), "pelangi" to listOf("🌈"), "salju" to listOf("❄️"), "bunga" to listOf("🌸", "🌹", "🌺", "🌻"),
        "pohon" to listOf("🌳", "🌴"), "daun" to listOf("🍃", "🍂"), "kucing" to listOf("🐱", "🐈"), "anjing" to listOf("🐶", "🐕"), "ikan" to listOf("🐟", "🐠"),
        "burung" to listOf("🐦"), "ayam" to listOf("🐔", "🍗"), "sapi" to listOf("🐄"), "kopi" to listOf("☕"), "teh" to listOf("🍵"), "makan" to listOf("🍽️", "🍚", "🍜"),
        "nasi" to listOf("🍚", "🍛"), "mie" to listOf("🍜"), "roti" to listOf("🍞"), "kue" to listOf("🍰", "🎂"), "ulang" to listOf("🎂", "🎉"), "tahun" to listOf("🎂"),
        "pesta" to listOf("🎉", "🥳"), "selamat" to listOf("🎉", "🥳", "👏"), "kado" to listOf("🎁"), "hadiah" to listOf("🎁"), "balon" to listOf("🎈"), "uang" to listOf("💰", "💵", "💸"),
        "duit" to listOf("💰", "💸"), "rumah" to listOf("🏠"), "kantor" to listOf("🏢"), "sekolah" to listOf("🏫"), "masjid" to listOf("🕌"), "mobil" to listOf("🚗"), "motor" to listOf("🏍️", "🛵"),
        "pesawat" to listOf("✈️"), "kereta" to listOf("🚆"), "kapal" to listOf("🚢"), "roket" to listOf("🚀"), "centang" to listOf("✅", "✔️"), "benar" to listOf("✅"), "silang" to listOf("❌"),
        "salah" to listOf("❌"), "awas" to listOf("⚠️"), "peringatan" to listOf("⚠️"), "jam" to listOf("⏰", "🕐"), "waktu" to listOf("⏰", "⌛"), "telepon" to listOf("📞", "📱"),
        "hp" to listOf("📱"), "laptop" to listOf("💻"), "buku" to listOf("📖", "📚"), "musik" to listOf("🎵", "🎶"), "lagu" to listOf("🎵"), "foto" to listOf("📷"), "kamera" to listOf("📷"),
        "surat" to listOf("✉️", "📧"), "kunci" to listOf("🔑", "🔒"), "lampu" to listOf("💡"), "ide" to listOf("💡"), "seratus" to listOf("💯"), "bola" to listOf("⚽"), "game" to listOf("🎮"),
        "baterai" to listOf("🔋"), "bendera" to listOf("🚩", "🏁"), "indonesia" to listOf("🇮🇩"), "pin" to listOf("📌"), "catatan" to listOf("📝"),
    )

    /** Emoji made of several joined together: emoji, name, more words. */
    val joined: List<Triple<String, String, String>> = listOf(
        Triple("👨‍👩‍👧", "family man woman girl", "keluarga anak"), Triple("👨‍👩‍👦", "family man woman boy", "keluarga anak"),
        Triple("👨‍👩‍👧‍👦", "family man woman girl boy", "keluarga anak"), Triple("👩‍👧", "family woman girl", "keluarga ibu anak"),
        Triple("👨‍👧", "family man girl", "keluarga ayah anak"), Triple("👩‍❤️‍👨", "couple with heart woman man", "pasangan cinta"),
        Triple("🧑‍💻", "technologist", "programmer coding komputer developer"), Triple("👨‍💻", "man technologist", "programmer coding komputer developer"),
        Triple("👩‍💻", "woman technologist", "programmer coding komputer developer"), Triple("🧑‍🍳", "cook", "koki masak chef"),
        Triple("🧑‍⚕️", "health worker", "dokter perawat"), Triple("🧑‍🏫", "teacher", "guru dosen"), Triple("🧑‍🎓", "student", "siswa mahasiswa wisuda"),
        Triple("🧑‍🔧", "mechanic", "montir bengkel"), Triple("🧑‍🌾", "farmer", "petani"), Triple("🧑‍🎨", "artist", "seniman pelukis"),
        Triple("🧑‍🚀", "astronaut", "astronot"), Triple("🧑‍🚒", "firefighter", "pemadam kebakaran"), Triple("🧑‍✈️", "pilot", "pilot"),
        Triple("🧑‍💼", "office worker", "karyawan kantor"), Triple("🧑‍🔬", "scientist", "ilmuwan"), Triple("🧑‍🎤", "singer", "penyanyi"),
        Triple("👮‍♂️", "man police officer", "polisi"), Triple("👮‍♀️", "woman police officer", "polisi polwan"),
        Triple("🏳️‍🌈", "rainbow flag", "bendera pelangi pride"), Triple("🏴‍☠️", "pirate flag", "bendera bajak laut"),
        Triple("❤️‍🔥", "heart on fire", "hati api cinta"), Triple("❤️‍🩹", "mending heart", "hati sembuh"),
        Triple("😮‍💨", "face exhaling", "lega hela napas"), Triple("😶‍🌫️", "face in clouds", "bingung awan"), Triple("😵‍💫", "face with spiral eyes", "pusing bingung"),
        Triple("🐈‍⬛", "black cat", "kucing hitam"), Triple("🐻‍❄️", "polar bear", "beruang kutub"), Triple("🧑‍🤝‍🧑", "people holding hands", "teman bergandengan"),
        Triple("🙋‍♂️", "man raising hand", "angkat tangan"), Triple("🙋‍♀️", "woman raising hand", "angkat tangan"),
        Triple("🤷‍♂️", "man shrugging", "entah bodo"), Triple("🤷‍♀️", "woman shrugging", "entah bodo"),
        Triple("🤦‍♂️", "man facepalming", "tepok jidat"), Triple("🤦‍♀️", "woman facepalming", "tepok jidat"),
        Triple("🧘‍♀️", "woman in lotus position", "yoga meditasi"),
    )

    /** Shown before anything is typed: recent ones, then these. */
    val starters = listOf("😂", "❤️", "👍", "🙏", "😊", "🔥", "😭", "🥰", "😎", "🎉", "✅", "👀", "🤔", "😅", "👏", "💯")

    // Which code points are emoji. Java 17 has no Character.isEmoji, so the lists are written out (Unicode 13).

    /** Emoji outside the big emoji blocks: [codepoint] or [from, to], with true when drawn as emoji by default. */
    private val basic: List<Triple<Int, Int, Boolean>> = listOf(
        t(0xA9), t(0xAE), t(0x203C), t(0x2049), t(0x2122), t(0x2139), t(0x2194, 0x2199), t(0x21A9, 0x21AA), e(0x231A, 0x231B), t(0x2328), t(0x23CF),
        e(0x23E9, 0x23EC), t(0x23ED, 0x23EF), e(0x23F0), t(0x23F1, 0x23F2), e(0x23F3), t(0x23F8, 0x23FA), t(0x24C2), t(0x25AA, 0x25AB), t(0x25B6), t(0x25C0),
        t(0x25FB, 0x25FC), e(0x25FD, 0x25FE), t(0x2600, 0x2604), t(0x260E), t(0x2611), e(0x2614, 0x2615), t(0x2618), t(0x261D), t(0x2620), t(0x2622, 0x2623),
        t(0x2626), t(0x262A), t(0x262E, 0x262F), t(0x2638, 0x263A), t(0x2640), t(0x2642), e(0x2648, 0x2653), t(0x265F, 0x2660), t(0x2663), t(0x2665, 0x2666),
        t(0x2668), t(0x267B), t(0x267E), e(0x267F), t(0x2692), e(0x2693), t(0x2694, 0x2697), t(0x2699), t(0x269B, 0x269C), t(0x26A0), e(0x26A1), t(0x26A7),
        e(0x26AA, 0x26AB), t(0x26B0, 0x26B1), e(0x26BD, 0x26BE), e(0x26C4, 0x26C5), t(0x26C8), e(0x26CE), t(0x26CF), t(0x26D1), t(0x26D3), e(0x26D4), t(0x26E9),
        e(0x26EA), t(0x26F0, 0x26F1), e(0x26F2, 0x26F3), t(0x26F4), e(0x26F5), t(0x26F7, 0x26F9), e(0x26FA), e(0x26FD), t(0x2702), e(0x2705), t(0x2708, 0x2709),
        e(0x270A, 0x270B), t(0x270C, 0x270D), t(0x270F), t(0x2712), t(0x2714), t(0x2716), t(0x271D), t(0x2721), e(0x2728), t(0x2733, 0x2734), t(0x2744), t(0x2747),
        e(0x274C), e(0x274E), e(0x2753, 0x2755), e(0x2757), t(0x2763, 0x2764), e(0x2795, 0x2797), t(0x27A1), e(0x27B0), e(0x27BF), t(0x2934, 0x2935),
        t(0x2B05, 0x2B07), e(0x2B1B, 0x2B1C), e(0x2B50), e(0x2B55), t(0x3030), t(0x303D), t(0x3297), t(0x3299),
        e(0x1F004), e(0x1F0CF), t(0x1F170, 0x1F171), t(0x1F17E, 0x1F17F), e(0x1F18E), e(0x1F191, 0x1F19A), e(0x1F201), t(0x1F202), e(0x1F21A), e(0x1F22F),
        e(0x1F232, 0x1F236), t(0x1F237), e(0x1F238, 0x1F23A), e(0x1F250, 0x1F251), e(0x1F7E0, 0x1F7EB),
    )

    /** The big blocks, where nearly everything is an emoji drawn in color. */
    private val blocks = listOf(0x1F300..0x1F64F, 0x1F680..0x1F6FF, 0x1F90C..0x1F9FF, 0x1FA70..0x1FAFF)

    /** Code points in those blocks that aren't emoji at all. */
    private val notEmoji: Set<Int> = ranges(
        0x1F322..0x1F323, 0x1F394..0x1F395, 0x1F398..0x1F398, 0x1F39C..0x1F39D, 0x1F3F1..0x1F3F2, 0x1F3F6..0x1F3F6, 0x1F3FB..0x1F3FF, 0x1F4FE..0x1F4FE,
        0x1F53E..0x1F548, 0x1F54F..0x1F54F, 0x1F568..0x1F56E, 0x1F571..0x1F572, 0x1F57B..0x1F586, 0x1F588..0x1F589, 0x1F58E..0x1F58F, 0x1F591..0x1F594,
        0x1F597..0x1F5A3, 0x1F5A6..0x1F5A7, 0x1F5A9..0x1F5B0, 0x1F5B3..0x1F5BB, 0x1F5BD..0x1F5C1, 0x1F5C5..0x1F5D0, 0x1F5D4..0x1F5DB, 0x1F5DF..0x1F5E0,
        0x1F5E2..0x1F5E2, 0x1F5E4..0x1F5E7, 0x1F5E9..0x1F5EE, 0x1F5F0..0x1F5F2, 0x1F5F4..0x1F5F9, 0x1F6C6..0x1F6CA, 0x1F6D3..0x1F6D4, 0x1F6E6..0x1F6E8,
        0x1F6EA..0x1F6EA, 0x1F6F1..0x1F6F2, 0x1F93B..0x1F93B, 0x1F946..0x1F946,
    )

    /** Emoji in those blocks drawn as text unless the variation selector follows. */
    private val textDefault: Set<Int> = ranges(
        0x1F321..0x1F321, 0x1F324..0x1F32C, 0x1F336..0x1F336, 0x1F37D..0x1F37D, 0x1F396..0x1F397, 0x1F399..0x1F39B, 0x1F39E..0x1F39F, 0x1F3CB..0x1F3CE,
        0x1F3D4..0x1F3DF, 0x1F3F3..0x1F3F3, 0x1F3F5..0x1F3F5, 0x1F3F7..0x1F3F7, 0x1F43F..0x1F43F, 0x1F441..0x1F441, 0x1F4FD..0x1F4FD, 0x1F549..0x1F54A,
        0x1F56F..0x1F570, 0x1F573..0x1F579, 0x1F587..0x1F587, 0x1F58A..0x1F58D, 0x1F590..0x1F590, 0x1F5A5..0x1F5A5, 0x1F5A8..0x1F5A8, 0x1F5B1..0x1F5B2,
        0x1F5BC..0x1F5BC, 0x1F5C2..0x1F5C4, 0x1F5D1..0x1F5D3, 0x1F5DC..0x1F5DE, 0x1F5E1..0x1F5E1, 0x1F5E3..0x1F5E3, 0x1F5E8..0x1F5E8, 0x1F5EF..0x1F5EF,
        0x1F5F3..0x1F5F3, 0x1F5FA..0x1F5FA, 0x1F6CB..0x1F6CB, 0x1F6CD..0x1F6CF, 0x1F6E0..0x1F6E5, 0x1F6E9..0x1F6E9, 0x1F6F0..0x1F6F0, 0x1F6F3..0x1F6F3,
    )

    /** People and hands, which take a skin tone. */
    private val toneBases: Set<Int> = ranges(
        0x261D..0x261D, 0x26F9..0x26F9, 0x270A..0x270D, 0x1F385..0x1F385, 0x1F3C2..0x1F3C4, 0x1F3C7..0x1F3C7, 0x1F3CA..0x1F3CC, 0x1F442..0x1F443,
        0x1F446..0x1F450, 0x1F466..0x1F478, 0x1F47C..0x1F47C, 0x1F481..0x1F483, 0x1F485..0x1F487, 0x1F48F..0x1F48F, 0x1F491..0x1F491, 0x1F4AA..0x1F4AA,
        0x1F574..0x1F575, 0x1F57A..0x1F57A, 0x1F590..0x1F590, 0x1F595..0x1F596, 0x1F645..0x1F647, 0x1F64B..0x1F64F, 0x1F6A3..0x1F6A3, 0x1F6B4..0x1F6B6,
        0x1F6C0..0x1F6C0, 0x1F6CC..0x1F6CC, 0x1F90C..0x1F90C, 0x1F90F..0x1F90F, 0x1F918..0x1F91F, 0x1F926..0x1F926, 0x1F930..0x1F939, 0x1F93C..0x1F93E,
        0x1F977..0x1F977, 0x1F9B5..0x1F9B6, 0x1F9B8..0x1F9B9, 0x1F9BB..0x1F9BB, 0x1F9CD..0x1F9CF, 0x1F9D1..0x1F9DD,
    )

    private fun t(from: Int, to: Int = from) = Triple(from, to, false)
    private fun e(from: Int, to: Int = from) = Triple(from, to, true)
    private fun ranges(vararg r: IntRange): Set<Int> = r.flatMap { it.toList() }.toSet()

    private fun words(text: String) = text.lowercase().split(' ', '-', ',', '.', '(', ')', '’', '\'').filter { it.isNotEmpty() }

    private fun build(): List<Entry> {
        val result = mutableListOf<Entry>()
        fun add(cp: Int, colorful: Boolean) {
            val name = runCatching { Character.getName(cp) }.getOrNull()?.lowercase() ?: return
            val emoji = String(Character.toChars(cp)) + if (colorful) "" else "️"
            result += Entry(emoji, name, words(name))
        }
        basic.forEach { (from, to, colorful) -> for (cp in from..to) if (Character.isDefined(cp)) add(cp, colorful) }
        blocks.forEach { block -> for (cp in block) if (Character.isDefined(cp) && cp !in notEmoji) add(cp, cp !in textDefault) }
        result += flags()
        result += joined.map { (emoji, name, extra) -> Entry(emoji, name, words("$name $extra")) }
        return result
    }

    /** A flag is two regional letters; found by its code, its English name, and its Indonesian name. */
    fun flags(): List<Entry> {
        val indonesian = Locale("id")
        return Locale.getISOCountries().mapNotNull { code ->
            val region = Locale("", code)
            val name = region.getDisplayCountry(Locale.ENGLISH).takeIf { it.isNotBlank() && it != code } ?: return@mapNotNull null
            val local = region.getDisplayCountry(indonesian)
            val flag = code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
            Entry(flag, "flag ${name.lowercase()}", words("flag bendera $name $local $code"))
        }
    }

    // Skin tones

    val toneWords = mapOf(
        "tone1" to 1, "terang" to 1, "tone2" to 2, "langsat" to 2, "tone3" to 3, "sawo" to 3, "tone4" to 4, "cokelat" to 4, "coklat" to 4,
        "tone5" to 5, "gelap" to 5, "tone0" to 0, "kuning" to 0,
    )

    /** Takes a tone word out of the query: the words left, and the tone if one was typed. */
    fun splitTone(query: String): Pair<String, Int?> {
        var tone: Int? = null
        val rest = query.split(' ').filter { word ->
            val found = toneWords[word.lowercase()]
            if (found != null) { tone = found; false } else true
        }
        return rest.filter { it.isNotEmpty() }.joinToString(" ") to tone
    }

    /** The emoji with a skin tone; only an emoji with one person takes it, families and couples stay as they are. */
    fun toned(emoji: String, tone: Int): String {
        if (tone !in 1..5) return emoji
        val points = emoji.codePoints().toArray()
        if (points.count { it in toneBases } != 1 || points.any { it in 0x1F3FB..0x1F3FF }) return emoji
        val out = StringBuilder()
        var i = 0
        while (i < points.size) {
            out.appendCodePoint(points[i])
            if (points[i] in toneBases) {
                out.appendCodePoint(0x1F3FA + tone)
                // The tone takes the variation selector's place.
                if (i + 1 < points.size && points[i + 1] == 0xFE0F) i++
            }
            i++
        }
        return out.toString()
    }

    /** The same emoji with or without the variation selector counts as one. */
    fun normalized(emoji: String): String = emoji.replace("️", "").let { base ->
        val cps = base.codePoints().toArray()
        if (cps.size == 1 && byKeyRaw(cps[0]) == false) base + "️" else base
    }

    /** Whether one code point is an emoji drawn as text by default (true), as emoji (false), or isn't listed (null). */
    private fun byKeyRaw(cp: Int): Boolean? {
        basic.forEach { (from, to, colorful) -> if (cp in from..to) return !colorful }
        if (blocks.any { cp in it } && cp !in notEmoji) return cp in textDefault
        return null
    }

    /** Emoji matching every word typed, best first; Indonesian words before name matches. */
    fun search(query: String, limit: Int = 40): List<Entry> {
        val terms = query.lowercase().split(' ').filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val scores = mutableMapOf<String, Int>()
        if (terms.size == 1) {
            aliases.forEach { (word, list) ->
                if (!word.startsWith(terms[0])) return@forEach
                list.forEachIndexed { index, emoji ->
                    val key = normalized(emoji)
                    val score = (if (word == terms[0]) 300 else 200) - index
                    if (score > (scores[key] ?: 0)) scores[key] = score
                }
            }
        }
        val phrase = terms.joinToString(" ")
        for (entry in all) {
            if (!terms.all { term -> entry.words.any { it.startsWith(term) } }) continue
            var score = 100
            if (entry.name == phrase) score += 60
            if (entry.words.firstOrNull()?.startsWith(terms[0]) == true) score += 20
            if (terms.all { it in entry.words }) score += 15
            score -= entry.words.size
            // Ones that are always colorful are the ones people usually mean.
            if (!entry.emoji.endsWith("️")) score += 5
            val key = normalized(entry.emoji)
            scores[key] = maxOf(scores[key] ?: 0, score)
        }
        return scores.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit).map { entry(it.key) }
    }

    fun entry(emoji: String): Entry {
        val key = normalized(emoji)
        return byKey[key] ?: Entry(emoji, aliases.filter { emoji in it.value }.keys.sorted().take(3).joinToString(", "), emptyList())
    }

    /** The rows for an emoji query, with [tone] from earlier unless the query names one; [recent] first when empty. */
    fun items(terms: String, tone: Int, recent: List<String>, canType: Boolean): Pair<List<LauncherItem>, Int> {
        val (words, typed) = splitTone(terms)
        val useTone = typed ?: tone
        val entries = if (words.isEmpty()) (recent + starters).distinctBy { normalized(it) }.take(40).map { entry(it) } else search(words)
        val how = if (canType) "Enter types it, Ctrl+Enter copies" else "Enter copies it"
        return entries.map { entry ->
            val emoji = toned(entry.emoji, useTone)
            val title = entry.name.ifEmpty { emoji }.replaceFirstChar { it.uppercase() }
            LauncherItem("emoji:${normalized(entry.emoji)}", LauncherKind.Emoji, title, how, LauncherTarget.Emoji(emoji, entry.emoji), emoji = emoji)
        } to useTone
    }
}
