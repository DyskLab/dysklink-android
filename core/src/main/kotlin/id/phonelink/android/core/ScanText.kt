package id.phonelink.android.core

/** What is pressed on the Mac after a scanned code is typed. */
enum class ScanSuffix(val label: String, val key: String?) {
    Enter("Enter", "return"),
    Tab("Tab", "tab"),
    None("Nothing", null),
}

object ScanText {
    const val MAX_LENGTH = 2_000

    /**
     * Makes a scanned value safe to type: line breaks become \n, other control characters go,
     * and very long QR codes are cut so they can't type for minutes.
     */
    fun clean(raw: String?): String? {
        val text = raw.orEmpty()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .filter { it == '\n' || it == '\t' || !it.isISOControl() }
            .trimEnd('\n')
            .take(MAX_LENGTH)
        return text.takeIf { it.isNotBlank() }
    }

    /** The events that type one scan on the Mac, in order. */
    fun events(text: String, suffix: ScanSuffix): List<InputEvent> =
        listOf(InputEvent("text", text = text)) + listOfNotNull(suffix.key?.let { InputEvent("key", key = it) })

    /** A short preview for the list, on one line. */
    fun preview(text: String, max: Int = 80): String {
        val line = text.replace('\n', ' ').replace('\t', ' ')
        return if (line.length > max) line.take(max - 1) + "…" else line
    }
}
