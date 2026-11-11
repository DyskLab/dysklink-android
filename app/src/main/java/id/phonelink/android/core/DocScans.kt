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

    /** A name like "Scan 10 Nov 2026 21.30", with a number added when it is taken. */
    fun newName(now: LocalDateTime = LocalDateTime.now()): String {
        val base = "Scan " + now.format(DateTimeFormatter.ofPattern("d MMM yyyy HH.mm", java.util.Locale.ENGLISH))
        if (!File(folder, "$base.pdf").exists()) return base
        var number = 2
        while (File(folder, "$base ($number).pdf").exists()) number++
        return "$base ($number)"
    }

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
        listOf("pdf", "txt", "pages").forEach { ext ->
            File(folder, "${scan.name}.$ext").takeIf { it.exists() }?.renameTo(File(folder, "$wanted.$ext"))
        }
        return list().firstOrNull { it.name == wanted }
    }

    fun delete(scan: DocScan) {
        listOf("pdf", "txt", "pages").forEach { File(folder, "${scan.name}.$it").delete() }
    }

    companion object {
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
