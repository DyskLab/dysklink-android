package id.dysklink.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

data class IndexedFile(val path: String, val name: String, val folder: Boolean, val modified: Long = 0)

/**
 * Files and folders in the home folder, for the Launcher: a list of names made in the background and made again every
 * [maxAgeMs], at most [cap] entries, [maxDepth] folders deep. Hidden folders, app data and package folders are left out.
 */
class FileIndex(private val roots: () -> List<File>, private val cap: Int = 80_000, private val maxDepth: Int = 7, private val maxAgeMs: Long = 15 * 60_000L) {
    @Volatile var files: List<IndexedFile> = emptyList()
        private set
    @Volatile var builtAt = 0L
        private set
    @Volatile private var building = false

    val isStale: Boolean get() = System.currentTimeMillis() - builtAt > maxAgeMs

    /** Builds it again unless that's under way; blocks the calling thread. */
    fun refresh() {
        synchronized(this) { if (building) return; building = true }
        try {
            files = walk(roots(), cap, maxDepth)
            builtAt = System.currentTimeMillis()
        } finally {
            building = false
        }
    }

    fun search(terms: String, limit: Int, foldersOnly: Boolean = false): List<IndexedFile> = rank(files, terms, limit, foldersOnly)

    companion object {
        /** Folder names never looked into. */
        val SKIP = setOf("node_modules", "AppData", "Application Data", "Local Settings", "__pycache__", "site-packages", "Library", "snap", "Cookies", "NetHood", "PrintHood", "Recent", "SendTo", "Templates")

        /** Breadth first, so a cap keeps the shallow, likelier files. Links aren't followed. */
        fun walk(roots: List<File>, cap: Int, maxDepth: Int): List<IndexedFile> {
            val out = ArrayList<IndexedFile>()
            val queue = ArrayDeque<Pair<File, Int>>()
            roots.filter { it.isDirectory }.forEach { queue.add(it to 0) }
            while (queue.isNotEmpty() && out.size < cap) {
                val (dir, depth) = queue.removeFirst()
                val children = runCatching { dir.listFiles() }.getOrNull() ?: continue
                for (child in children.sortedBy { it.name.lowercase() }) {
                    if (out.size >= cap) break
                    val name = child.name
                    if (name.startsWith(".") || name.startsWith("~$") || runCatching { child.isHidden || Files.isSymbolicLink(child.toPath()) }.getOrDefault(true)) continue
                    val folder = child.isDirectory
                    if (folder && name in SKIP) continue
                    out += IndexedFile(child.path, name, folder, runCatching { child.lastModified() }.getOrDefault(0))
                    if (folder && depth + 1 < maxDepth) queue.add(child to depth + 1)
                }
            }
            return out
        }

        /** Names that fit [terms], best fit first, then newest; with several words, each must be in the name. */
        fun rank(files: List<IndexedFile>, terms: String, limit: Int, foldersOnly: Boolean = false): List<IndexedFile> {
            val words = terms.lowercase().split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) return emptyList()
            return files.asSequence()
                .filter { (!foldersOnly || it.folder) && words.all { w -> it.name.lowercase().contains(w) } }
                .map { it to (LauncherScore.score(it.name, terms.trim()) ?: LauncherScore.score(it.name.substringBeforeLast('.'), terms.trim()) ?: 30) }
                .sortedWith(compareByDescending<Pair<IndexedFile, Int>> { it.second }.thenByDescending { it.first.modified })
                .take(limit).map { it.first }.toList()
        }
    }
}

/** plocate or locate, when installed: the whole disk's index, of which the home folder's part is used. */
object Locate {
    /** The command for a search; plocate first, as it's the faster. */
    fun command(tool: String, terms: String, limit: Int): List<String> = listOf(tool, "-i", "-b", "-l", (limit * 10).toString(), "--", terms)

    /**
     * The paths [output] lists that are in [home] and not hidden, as files. [exists] tells whether a path is still there
     * (the index is made once a day) and whether it's a folder.
     */
    fun parse(output: String, home: String, terms: String, limit: Int, foldersOnly: Boolean, stat: (String) -> Boolean?): List<IndexedFile> {
        val prefix = home.trimEnd('/') + "/"
        val found = output.lineSequence().map { it.trim() }.filter { it.startsWith(prefix) }
            .filter { p -> p.removePrefix(prefix).split('/').none { it.startsWith(".") || it in FileIndex.SKIP } }
            .mapNotNull { p -> stat(p)?.let { folder -> IndexedFile(p, p.substringAfterLast('/'), folder) } }
            .filter { !foldersOnly || it.folder }
            .take(limit * 4).toList()
        return FileIndex.rank(found, terms, limit, foldersOnly)
    }

    fun run(tool: String, terms: String, limit: Int): String? = runCatching {
        val process = ProcessBuilder(command(tool, terms, limit)).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = process.inputStream.bufferedReader().readText()
        if (process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() <= 1) out else null
    }.getOrNull()
}
