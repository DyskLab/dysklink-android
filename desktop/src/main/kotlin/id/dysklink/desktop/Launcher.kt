package id.dysklink.desktop

import java.net.URLEncoder
import kotlinx.serialization.Serializable

// The Launcher, as on the Mac (Raycast or Alfred): one shortcut opens a search box for apps, files, folders, sums,
// units, emoji, DyskLink's own actions, and the web. Everything here is plain logic; LauncherService does the rest.

/** Arithmetic with + - * / % ^ and brackets, by a small parser of its own. Null for anything that isn't a sum. */
object LauncherMath {
    fun evaluate(text: String): Double? {
        val cleaned = text.replace(",", ".").replace("×", "*").replace("÷", "/").replace(" ", "")
        if (cleaned.isEmpty() || cleaned.none { it in "+-*/^%(" } || !cleaned.all { it in "0123456789.+-*/^%()" }) return null
        val parser = Parser(cleaned)
        val value = parser.expression() ?: return null
        return value.takeIf { parser.index == cleaned.length && it.isFinite() }
    }

    fun format(value: Double): String {
        if (value == Math.rint(value) && kotlin.math.abs(value) < 1e15) return value.toLong().toString()
        return java.math.BigDecimal(value).setScale(8, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    private class Parser(val chars: String) {
        var index = 0
        var depth = 0

        fun expression(): Double? {
            var value = term() ?: return null
            while (index < chars.length && (chars[index] == '+' || chars[index] == '-')) {
                val op = chars[index++]
                val right = term() ?: return null
                value = if (op == '+') value + right else value - right
            }
            return value
        }

        fun term(): Double? {
            var value = power() ?: return null
            while (index < chars.length && chars[index] in "*/%") {
                val op = chars[index++]
                val right = power() ?: return null
                value = when (op) {
                    '*' -> value * right
                    '/' -> if (right == 0.0) return null else value / right
                    else -> if (right == 0.0) return null else value % right
                }
            }
            return value
        }

        fun power(): Double? {
            val base = unary() ?: return null
            if (index < chars.length && chars[index] == '^') {
                index++
                val exponent = power() ?: return null
                return Math.pow(base, exponent)
            }
            return base
        }

        fun unary(): Double? {
            if (index < chars.length && chars[index] == '-') { index++; return unary()?.let { -it } }
            if (index < chars.length && chars[index] == '+') { index++; return unary() }
            return primary()
        }

        fun primary(): Double? {
            if (index >= chars.length) return null
            if (chars[index] == '(') {
                if (++depth >= 50) return null
                index++
                val value = expression()
                if (index >= chars.length || chars[index] != ')') return null
                index++
                depth--
                return value
            }
            val start = index
            while (index < chars.length && (chars[index].isDigit() || chars[index] == '.')) index++
            return chars.substring(start, index).toDoubleOrNull()
        }
    }
}

/** "10 km to mi", "70 f in c", "2.5 kg ke lb". */
object LauncherUnits {
    private enum class Kind { Length, Mass, Volume, Speed, Data, Temperature }
    private class Unit(val kind: Kind, val factor: Double)

    private val units: Map<String, Unit> = buildMap {
        fun add(kind: Kind, factor: Double, vararg names: String) = names.forEach { put(it, Unit(kind, factor)) }
        add(Kind.Length, 0.001, "mm"); add(Kind.Length, 0.01, "cm"); add(Kind.Length, 1.0, "m"); add(Kind.Length, 1000.0, "km")
        add(Kind.Length, 0.0254, "in", "inch"); add(Kind.Length, 0.3048, "ft"); add(Kind.Length, 0.9144, "yd"); add(Kind.Length, 1609.344, "mi", "mile", "miles")
        add(Kind.Mass, 0.001, "mg"); add(Kind.Mass, 1.0, "g"); add(Kind.Mass, 1000.0, "kg"); add(Kind.Mass, 453.59237, "lb", "lbs")
        add(Kind.Mass, 28.349523125, "oz"); add(Kind.Mass, 1_000_000.0, "ton")
        add(Kind.Volume, 0.001, "ml"); add(Kind.Volume, 1.0, "l"); add(Kind.Volume, 3.785411784, "gal"); add(Kind.Volume, 0.2365882365, "cup")
        add(Kind.Speed, 1 / 3.6, "kmh", "km/h"); add(Kind.Speed, 0.44704, "mph"); add(Kind.Speed, 1.0, "m/s")
        add(Kind.Data, 1.0, "b"); add(Kind.Data, 1e3, "kb"); add(Kind.Data, 1e6, "mb"); add(Kind.Data, 1e9, "gb"); add(Kind.Data, 1e12, "tb")
        listOf("c", "°c", "f", "°f", "k").forEach { put(it, Unit(Kind.Temperature, 1.0)) }
    }
    private val pattern = Regex("""^\s*(-?[\d.,]+)\s*([a-z°/]+)\s+(?:to|in|ke)\s+([a-z°/]+)\s*$""", RegexOption.IGNORE_CASE)

    fun convert(text: String): String? {
        val match = pattern.matchEntire(text) ?: return null
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val fromName = match.groupValues[2].lowercase()
        val toName = match.groupValues[3].lowercase()
        val from = units[fromName] ?: return null
        val to = units[toName] ?: return null
        if (from.kind != to.kind) return null
        val result = if (from.kind == Kind.Temperature) kelvinTo(toName, toKelvin(fromName, value)) else value * from.factor / to.factor
        return "${LauncherMath.format(Math.round(result * 10_000) / 10_000.0)} ${match.groupValues[3]}"
    }

    private fun toKelvin(unit: String, v: Double) = when (unit.trimStart('°')) { "c" -> v + 273.15; "f" -> (v - 32) * 5 / 9 + 273.15; else -> v }
    private fun kelvinTo(unit: String, k: Double) = when (unit.trimStart('°')) { "c" -> k - 273.15; "f" -> (k - 273.15) * 9 / 5 + 32; else -> k }
}

/** How well a name fits what was typed: the whole name, its start, a word's start, initials, then anywhere. */
object LauncherScore {
    fun score(name: String, query: String): Int? {
        val n = name.lowercase()
        val q = query.lowercase().trim()
        if (q.isEmpty()) return null
        if (n == q) return 100
        if (n.startsWith(q)) return 80
        val words = n.split(' ', '-', '.', '_').filter { it.isNotEmpty() }
        if (words.any { it.startsWith(q) }) return 60
        // Initials, like "vsc" for Visual Studio Code.
        if (q.length >= 2 && words.mapNotNull { it.firstOrNull() }.joinToString("").startsWith(q)) return 55
        if (q in n) return 40
        return null
    }
}

/** Web searches and addresses typed in the Launcher. */
object LauncherWeb {
    data class Engine(val prefix: String, val name: String, val url: String)
    data class Search(val name: String, val terms: String, val url: String)

    val engines = listOf(
        Engine("g", "Google", "https://www.google.com/search?q="),
        Engine("yt", "YouTube", "https://www.youtube.com/results?search_query="),
        Engine("w", "Wikipedia", "https://id.wikipedia.org/w/index.php?search="),
        Engine("gh", "GitHub", "https://github.com/search?q="),
        Engine("maps", "Google Maps", "https://www.google.com/maps/search/"),
        Engine("ddg", "DuckDuckGo", "https://duckduckgo.com/?q="),
    )

    /** "yt lofi" searches YouTube, anything else Google. A prefix only counts when words follow it. */
    fun search(query: String): Search? {
        val text = query.trim()
        if (text.isEmpty()) return null
        var engine = engines[0]
        var terms = text
        val space = text.indexOf(' ')
        if (space > 0) {
            val match = engines.firstOrNull { it.prefix == text.substring(0, space).lowercase() }
            val rest = text.substring(space).trim()
            if (match != null && rest.isNotEmpty()) { engine = match; terms = rest }
        }
        return Search(engine.name, terms, engine.url + URLEncoder.encode(terms, Charsets.UTF_8).replace("+", "%20"))
    }

    private val fileEndings = setOf("txt", "pdf", "png", "jpg", "jpeg", "gif", "doc", "docx", "xls", "xlsx", "csv", "zip", "md", "json", "kt", "py", "js", "html", "mov", "mp4", "mp3", "exe", "lnk", "msi")

    /** "github.com", "example.co.id/path", or a full http(s) link. Anything with a space isn't an address. */
    fun address(query: String): String? {
        val text = query.trim()
        if (text.isEmpty() || ' ' in text || text.length > 2_000) return null
        val lower = text.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return text.takeIf { runCatching { java.net.URI(it).host }.getOrNull() != null }
        }
        val labels = lower.substringBefore('/').split('.')
        val last = labels.last()
        if (labels.size < 2 || last.length < 2 || !last.all { it.isLetter() } || last in fileEndings) return null
        if (!labels.all { label -> label.isNotEmpty() && label.all { it.isLetterOrDigit() || it == '-' } }) return null
        return "https://$text"
    }
}

/** Prefixes: "f report" files and folders only, "d report" folders only, ":" or "e " emoji. */
object LauncherSearch {
    data class Scope(val terms: String, val foldersOnly: Boolean)

    fun scoped(query: String): Scope? {
        val lower = query.lowercase()
        for ((prefix, folders) in listOf("f " to false, "d " to true)) {
            if (lower.startsWith(prefix)) return query.substring(prefix.length).trim().takeIf { it.isNotEmpty() }?.let { Scope(it, folders) }
        }
        return null
    }

    /** The words to look for in emoji, when the query asks for emoji. Empty shows recent ones. */
    fun emojiTerms(query: String): String? {
        val trimmed = query.trim()
        if (trimmed.startsWith(":")) return trimmed.drop(1).trim()
        if (query.lowercase().startsWith("e ")) return query.drop(2).trim()
        if (trimmed.equals("emoji", ignoreCase = true)) return ""
        return null
    }
}

// Results

enum class LauncherKind(val label: String) {
    Answer("Answer"), Action("DyskLink"), App("App"), Clip("Clipboard"), File("File"), Folder("Folder"), Web("Web"), Emoji("Emoji"), Setting("Settings"),
}

/** How an app is started: a Start menu shortcut, an app from Get-StartApps, or a Linux .desktop file. */
sealed interface AppLaunch {
    data class Shortcut(val path: String) : AppLaunch
    data class AppsFolder(val appId: String) : AppLaunch
    data class DesktopFile(val path: String, val exec: List<String>, val terminal: Boolean = false, val workDir: String? = null) : AppLaunch
}

data class LauncherApp(val id: String, val name: String, val launch: AppLaunch, val keywords: String = "", val detail: String = "") {
    /** A file the app comes from, for "show in folder" and "copy path". */
    val location: String? get() = when (launch) {
        is AppLaunch.Shortcut -> launch.path
        is AppLaunch.DesktopFile -> launch.path
        is AppLaunch.AppsFolder -> launch.appId.takeIf { ':' in it && '\\' in it }
    }
}

/** What a row does. */
sealed interface LauncherTarget {
    data class App(val app: LauncherApp) : LauncherTarget
    data class Path(val path: String, val folder: Boolean) : LauncherTarget
    data class Url(val url: String) : LauncherTarget
    data class Copy(val text: String) : LauncherTarget
    /** [plain] is the emoji without a skin tone, for the recent list. */
    data class Emoji(val emoji: String, val plain: String) : LauncherTarget
    /** One of DyskLink's own actions, by id: "ring:<phone>", "layout:<id>", "settings"... */
    data class Action(val id: String) : LauncherTarget
    data class Clip(val id: String) : LauncherTarget
    /** A program with arguments, such as a settings page of the desktop. */
    data class Command(val argv: List<String>) : LauncherTarget
}

data class LauncherItem(
    val id: String,
    val kind: LauncherKind,
    val title: String,
    val subtitle: String = "",
    val target: LauncherTarget,
    /** More words that find it, not shown. */
    val keywords: String = "",
    val score: Int = 0,
    /** Drawn large in place of an icon. */
    val emoji: String? = null,
)

/** Enter does the row's main thing; Ctrl+Enter shows it in its folder; Shift+Enter copies its path. */
enum class LauncherModifier { None, Reveal, CopyPath }

sealed interface LauncherEffect {
    data class Launch(val app: LauncherApp) : LauncherEffect
    data class Open(val path: String) : LauncherEffect
    data class Reveal(val path: String) : LauncherEffect
    data class Browse(val url: String) : LauncherEffect
    data class Copy(val text: String) : LauncherEffect
    /** Typed into the app the Launcher was opened over. */
    data class Type(val text: String) : LauncherEffect
    data class Run(val actionId: String) : LauncherEffect
    data class PasteClip(val id: String) : LauncherEffect
    data class CopyClip(val id: String) : LauncherEffect
    data class Exec(val argv: List<String>) : LauncherEffect
}

object LauncherRouter {
    /** What a row does with the keys held. [canType] is false where DyskLink can't press keys in other apps. */
    fun route(target: LauncherTarget, modifier: LauncherModifier, canType: Boolean): LauncherEffect = when (target) {
        is LauncherTarget.App -> {
            val location = target.app.location
            when (modifier) {
                LauncherModifier.None -> LauncherEffect.Launch(target.app)
                LauncherModifier.Reveal -> location?.let { LauncherEffect.Reveal(it) } ?: LauncherEffect.Launch(target.app)
                LauncherModifier.CopyPath -> LauncherEffect.Copy(location ?: (target.app.launch as? AppLaunch.AppsFolder)?.let { "shell:AppsFolder\\${it.appId}" } ?: target.app.name)
            }
        }
        is LauncherTarget.Path -> when (modifier) {
            LauncherModifier.None -> LauncherEffect.Open(target.path)
            LauncherModifier.Reveal -> LauncherEffect.Reveal(target.path)
            LauncherModifier.CopyPath -> LauncherEffect.Copy(target.path)
        }
        is LauncherTarget.Url -> if (modifier == LauncherModifier.None) LauncherEffect.Browse(target.url) else LauncherEffect.Copy(target.url)
        is LauncherTarget.Copy -> LauncherEffect.Copy(target.text)
        is LauncherTarget.Emoji -> if (modifier == LauncherModifier.None && canType) LauncherEffect.Type(target.emoji) else LauncherEffect.Copy(target.emoji)
        is LauncherTarget.Action -> LauncherEffect.Run(target.id)
        is LauncherTarget.Clip -> if (modifier == LauncherModifier.None && canType) LauncherEffect.PasteClip(target.id) else LauncherEffect.CopyClip(target.id)
        is LauncherTarget.Command -> LauncherEffect.Exec(target.argv)
    }
}

// Recent use

@Serializable
data class LauncherUse(val count: Int, val last: Long)

/** How often and how lately each row was chosen; often and lately used ones rise. */
data class Frecency(val uses: Map<String, LauncherUse> = emptyMap()) {
    /** Points added to a match: up to 30, less as the last use gets older. */
    fun bonus(id: String, now: Long): Int {
        val use = uses[id] ?: return 0
        val age = now - use.last
        val weight = when {
            age < DAY -> 1.0
            age < 7 * DAY -> 0.8
            age < 30 * DAY -> 0.5
            else -> 0.25
        }
        return (use.count.coerceAtMost(10) * 3 * weight).toInt().coerceAtMost(30)
    }

    fun used(id: String, now: Long): Frecency {
        val next = uses + (id to LauncherUse((uses[id]?.count ?: 0) + 1, now))
        // The least useful go when there are too many.
        return Frecency(if (next.size <= MAX) next else next.entries.sortedByDescending { bonus(it.key, now) * 10L + it.value.last / DAY }.take(MAX).associate { it.toPair() })
    }

    /** Ids by how much they'd rise now, best first. */
    fun top(now: Long, limit: Int): List<String> =
        uses.keys.sortedWith(compareByDescending<String> { bonus(it, now) }.thenByDescending { uses[it]!!.last }).take(limit)

    companion object {
        const val DAY = 86_400_000L
        const val MAX = 400
    }
}

/** What the Launcher shows for a query: [items] first; then files found for [files], if any; then [web]. */
data class LauncherResults(val items: List<LauncherItem>, val web: List<LauncherItem> = emptyList(), val files: LauncherSearch.Scope? = null, val fileLimit: Int = 6)

object LauncherQuery {
    /**
     * The rows for [query]. [candidates] are actions, apps and settings, ranked by name; [clips] finds clipboard
     * entries; [emoji] the emoji rows for emoji queries.
     */
    fun results(
        query: String,
        candidates: List<LauncherItem>,
        frecency: Frecency,
        now: Long,
        clips: (String) -> List<LauncherItem> = { emptyList() },
        emoji: (String) -> List<LauncherItem> = { emptyList() },
    ): LauncherResults {
        val q = query.trim()
        LauncherSearch.emojiTerms(q)?.let { return LauncherResults(emoji(it)) }
        if (q.isEmpty()) {
            val byId = candidates.associateBy { it.id }
            val recent = frecency.top(now, 5).mapNotNull { byId[it] }
            val actions = candidates.filter { it.kind == LauncherKind.Action && it !in recent }.take(6)
            return LauncherResults(recent + actions)
        }
        LauncherSearch.scoped(q)?.let { return LauncherResults(emptyList(), files = it, fileLimit = 20) }
        val answers = buildList {
            LauncherMath.evaluate(q)?.let { val text = LauncherMath.format(it); add(LauncherItem("calc", LauncherKind.Answer, text, "Enter copies it", LauncherTarget.Copy(text))) }
            LauncherUnits.convert(q)?.let { add(LauncherItem("unit", LauncherKind.Answer, it, "Enter copies it", LauncherTarget.Copy(it.substringBefore(' ')))) }
        }
        val ranked = candidates.mapNotNull { item ->
            val base = LauncherScore.score(item.title, q)
                ?: item.subtitle.takeIf { it.isNotEmpty() }?.let { LauncherScore.score(it, q) }?.minus(25)
                ?: keywordScore(item.keywords, q)
                ?: return@mapNotNull null
            item.copy(score = base + (if (item.kind == LauncherKind.App) 5 else 0) + frecency.bonus(item.id, now))
        }.sortedWith(compareByDescending<LauncherItem> { it.score }.thenBy { it.title.length })
        val wantsFiles = q.length >= 2 && answers.isEmpty()
        return LauncherResults(
            items = answers + ranked.take(8) + (if (q.length >= 2) clips(q).take(3) else emptyList()),
            web = webItems(q),
            files = if (wantsFiles) LauncherSearch.Scope(q, false) else null,
        )
    }

    private fun keywordScore(keywords: String, query: String): Int? =
        // Capped, so an extra word never beats a word of the name itself.
        keywords.split(' ').filter { it.isNotEmpty() }.mapNotNull { LauncherScore.score(it, query) }.maxOrNull()?.coerceAtMost(70)?.minus(30)

    fun webItems(query: String): List<LauncherItem> = buildList {
        LauncherWeb.address(query)?.let { add(LauncherItem("web-open", LauncherKind.Web, "Open ${java.net.URI(it).host ?: query}", it, LauncherTarget.Url(it))) }
        LauncherWeb.search(query)?.let { add(LauncherItem("web-search", LauncherKind.Web, "Search ${it.name} for \"${it.terms}\"", "Opens in your browser", LauncherTarget.Url(it.url))) }
    }

    /** Files and folders found, as rows; name matches score as apps do, newer first among equals. */
    fun fileItems(found: List<IndexedFile>, home: String): List<LauncherItem> = found.map { f ->
        val parent = f.path.substringBeforeLast(java.io.File.separatorChar, "")
        val shown = if (home.isNotEmpty() && parent.startsWith(home)) "~" + parent.removePrefix(home) else parent
        LauncherItem("file:${f.path}", if (f.folder) LauncherKind.Folder else LauncherKind.File, f.name, shown, LauncherTarget.Path(f.path, f.folder))
    }
}

/** The Launcher's keys: typing, moving, and Enter with or without Ctrl or Shift. */
sealed interface LauncherKey {
    data object Up : LauncherKey
    data object Down : LauncherKey
    data object PageUp : LauncherKey
    data object PageDown : LauncherKey
    data class Enter(val modifier: LauncherModifier = LauncherModifier.None) : LauncherKey
    data object Escape : LauncherKey
    data object Backspace : LauncherKey
    /** Ctrl+Backspace: the whole query. */
    data object Clear : LauncherKey
    data class Type(val text: String) : LauncherKey
}

data class LauncherState(val query: String = "", val selection: Int = 0)

sealed interface LauncherStep {
    data object None : LauncherStep
    data object Close : LauncherStep
    data class Run(val index: Int, val modifier: LauncherModifier) : LauncherStep
}

object LauncherKeys {
    private const val PAGE = 8

    fun reduce(state: LauncherState, key: LauncherKey, count: Int): Pair<LauncherState, LauncherStep> {
        val last = (count - 1).coerceAtLeast(0)
        return when (key) {
            LauncherKey.Up -> state.copy(selection = (state.selection - 1).coerceAtLeast(0)) to LauncherStep.None
            LauncherKey.Down -> state.copy(selection = (state.selection + 1).coerceAtMost(last)) to LauncherStep.None
            LauncherKey.PageUp -> state.copy(selection = (state.selection - PAGE).coerceAtLeast(0)) to LauncherStep.None
            LauncherKey.PageDown -> state.copy(selection = (state.selection + PAGE).coerceAtMost(last)) to LauncherStep.None
            is LauncherKey.Enter -> state to (if (state.selection < count) LauncherStep.Run(state.selection, key.modifier) else LauncherStep.None)
            LauncherKey.Escape -> if (state.query.isNotEmpty()) LauncherState() to LauncherStep.None else state to LauncherStep.Close
            LauncherKey.Backspace -> (if (state.query.isEmpty()) state else LauncherState(state.query.dropLast(1))) to LauncherStep.None
            LauncherKey.Clear -> LauncherState() to LauncherStep.None
            is LauncherKey.Type -> {
                val typed = key.text.lineSequence().firstOrNull().orEmpty().filter { it.code >= 32 && it.code != 127 }
                if (typed.isEmpty()) state to LauncherStep.None else LauncherState((state.query + typed).take(200)) to LauncherStep.None
            }
        }
    }
}
