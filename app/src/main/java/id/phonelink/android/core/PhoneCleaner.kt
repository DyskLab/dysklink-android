package id.phonelink.android.core

import java.io.File
import java.io.IOException

/** What the storage cleaner looks for. Nothing is removed until the user picks files and confirms. */
enum class CleanKind(val title: String, val blurb: String) {
    Large("Large files", "Files of 100 MB or more, biggest first."),
    OldDownloads("Old downloads", "Files in Download that haven't changed in 90 days."),
    Apks("Leftover installers", "APK files that are only needed while installing an app."),
    Empty("Empty folders", "Folders with nothing inside."),
}

data class CleanItem(val path: String, val size: Long, val modified: Long, val kind: CleanKind, val isFolder: Boolean = false) {
    val name: String get() = path.substringAfterLast('/')
}

data class CleanReport(val items: List<CleanItem>, val scannedFiles: Int, val totalBytes: Long) {
    fun of(kind: CleanKind): List<CleanItem> = items.filter { it.kind == kind }
    fun size(kind: CleanKind): Long = of(kind).sumOf { it.size }
}

/** Walks shared storage. Pure file logic, so it is tested on the computer with a temporary folder. */
class PhoneCleaner(root: File, private val now: () -> Long = System::currentTimeMillis) {
    private val root: File = root.canonicalFile

    fun scan(isCancelled: () -> Boolean = { false }): CleanReport {
        val large = mutableListOf<CleanItem>()
        val old = mutableListOf<CleanItem>()
        val apks = mutableListOf<CleanItem>()
        val empty = mutableListOf<CleanItem>()
        var files = 0
        var total = 0L
        val cutoff = now() - OLD_DAYS * DAY_MS

        // Returns true when the folder ended up with nothing in it.
        fun walk(folder: File, depth: Int): Boolean {
            if (isCancelled() || depth > MAX_DEPTH) return false
            val children = folder.listFiles() ?: return false
            var hasContent = false
            for (child in children) {
                if (child.name.startsWith(".")) {
                    hasContent = true
                    continue
                }
                if (child.isDirectory) {
                    if (relative(child) in SKIPPED) {
                        hasContent = true
                        continue
                    }
                    if (walk(child, depth + 1)) {
                        empty += CleanItem(relative(child), 0, child.lastModified(), CleanKind.Empty, isFolder = true)
                    } else {
                        hasContent = true
                    }
                    continue
                }
                hasContent = true
                files++
                val size = child.length()
                total += size
                val path = relative(child)
                val modified = child.lastModified()
                when {
                    child.name.endsWith(".apk", ignoreCase = true) -> apks += CleanItem(path, size, modified, CleanKind.Apks)
                    size >= LARGE_BYTES -> large += CleanItem(path, size, modified, CleanKind.Large)
                    path.startsWith("Download/") && modified in 1 until cutoff -> old += CleanItem(path, size, modified, CleanKind.OldDownloads)
                }
            }
            return !hasContent
        }
        walk(root, 0)
        val items = large.sortedByDescending { it.size }.take(MAX_PER_KIND) +
            old.sortedByDescending { it.size }.take(MAX_PER_KIND) +
            apks.sortedByDescending { it.size }.take(MAX_PER_KIND) +
            // Only the outermost empty folder, since removing it removes the ones inside.
            empty.filter { folder -> empty.none { other -> other !== folder && folder.path.startsWith(other.path + "/") } }
                .sortedBy { it.path }.take(MAX_PER_KIND)
        return CleanReport(items, files, total)
    }

    /** Deletes the picked items. Returns how many bytes were freed and which ones failed. */
    fun delete(items: List<CleanItem>): Pair<Long, List<CleanItem>> {
        var freed = 0L
        val failed = mutableListOf<CleanItem>()
        for (item in items) {
            val file = runCatching { resolve(item.path) }.getOrNull()
            val ok = when {
                file == null || file == root || !file.exists() -> false
                item.isFolder -> file.isDirectory && file.walkBottomUp().all { it.isDirectory && it.delete() }
                else -> file.isFile && file.delete()
            }
            if (ok) freed += item.size else failed += item
        }
        return freed to failed
    }

    private fun relative(file: File) = file.path.removePrefix(root.path).trimStart('/')

    private fun resolve(path: String): File {
        if ('\u0000' in path || path.isBlank()) throw IOException("Location not allowed")
        val target = File(root, path).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) throw IOException("Location not allowed")
        if (SKIPPED.any { target.path == File(root, it).path || target.path.startsWith(File(root, it).path + File.separator) }) {
            throw IOException("Location not allowed")
        }
        return target
    }

    companion object {
        const val LARGE_BYTES = 100L * 1024 * 1024
        const val OLD_DAYS = 90L
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MAX_DEPTH = 12
        private const val MAX_PER_KIND = 200
        /** App data and system folders, which apps need even when they look empty. */
        val SKIPPED = setOf("Android")
    }
}
