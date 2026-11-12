package id.phonelink.android.core

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A scanned document kept on the phone: a PDF, and the text read from it when there was any. */
data class DocScan(val pdf: File, val text: File?, val pages: Int) {
    val name: String get() = pdf.nameWithoutExtension
    val size: Long get() = pdf.length()
    val created: Long get() = pdf.lastModified()
    fun readText(): String = text?.takeIf { it.exists() }?.readText().orEmpty()
}

/** Scans live in the app's own folder until deleted, so they are private to DyskLink. */
class DocScans(private val folder: File) {
    init { folder.mkdirs() }

    fun list(): List<DocScan> = folder.listFiles { file -> file.extension == "pdf" }.orEmpty()
        .sortedByDescending { it.lastModified() }
        .map { pdf ->
            val text = File(folder, pdf.nameWithoutExtension + ".txt").takeIf { it.exists() }
            val pages = File(folder, pdf.nameWithoutExtension + ".pages").takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 1
            DocScan(pdf, text, pages)
        }

    /** A name like "Scan 2026-11-10 2130", with a number added when it is taken. */
    fun newName(now: LocalDateTime = LocalDateTime.now()): String = uniqueName(baseName(now)) { File(folder, "$it.pdf").exists() }

    /** JPEG copies of the pages, kept next to the PDF when the user asked for them. */
    fun images(scan: DocScan): List<File> = folder.listFiles { file ->
        file.extension == "jpg" && file.name.startsWith("${scan.name} page ")
    }.orEmpty().sortedBy { it.nameWithoutExtension.substringAfterLast(' ').toIntOrNull() ?: 0 }

    /** Copies page [number] (from 1) into the folder next to [scan]. */
    fun saveImage(scan: DocScan, number: Int, write: (File) -> Unit): File =
        File(folder, "${scan.name} page $number.jpg").also(write)

    private fun related(name: String): List<File> =
        listOf("pdf", "txt", "pages").map { File(folder, "$name.$it") } +
            folder.listFiles { file -> file.extension == "jpg" && file.name.startsWith("$name page ") }.orEmpty()

    fun save(name: String, pdf: (File) -> Unit, pages: Int, text: String): DocScan {
        val target = File(folder, "${clean(name)}.pdf")
        pdf(target)
        File(folder, "${target.nameWithoutExtension}.pages").writeText(pages.toString())
        val textFile = File(folder, "${target.nameWithoutExtension}.txt")
        if (text.isNotBlank()) textFile.writeText(text) else textFile.delete()
        return DocScan(target, textFile.takeIf { it.exists() }, pages)
    }

    fun rename(scan: DocScan, to: String): DocScan? {
        val wanted = clean(to)
        if (wanted.isEmpty() || wanted == scan.name) return null
        if (File(folder, "$wanted.pdf").exists()) return null
        related(scan.name).filter { it.exists() }.forEach { file ->
            file.renameTo(File(folder, wanted + file.name.removePrefix(scan.name)))
        }
        return list().firstOrNull { it.name == wanted }
    }

    fun delete(scan: DocScan) {
        related(scan.name).forEach { it.delete() }
    }

    companion object {
        /** "Scan yyyy-MM-dd HHmm", the name a new scan starts with. */
        fun baseName(now: LocalDateTime): String = "Scan " + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm", java.util.Locale.ENGLISH))

        /** [base], or "[base] (2)", "[base] (3)", … when [taken]. */
        fun uniqueName(base: String, taken: (String) -> Boolean): String {
            if (!taken(base)) return base
            var number = 2
            while (taken("$base ($number)")) number++
            return "$base ($number)"
        }

        /** Keeps a file name that works on the phone, the Mac, and Windows. */
        fun clean(name: String): String = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ")
            .replace(Regex("""\s+"""), " ").trim().trimStart('.').take(80).trim()

        /** Text from each page, with a marker between pages when there are several. */
        fun joinPages(pages: List<String>): String {
            val filled = pages.map { it.trim() }
            if (filled.all { it.isEmpty() }) return ""
            if (filled.size == 1) return filled.first()
            return filled.mapIndexed { index, text -> "— Page ${index + 1} —\n" + text.ifEmpty { "(no text)" } }.joinToString("\n\n")
        }
    }
}
