package id.phonelink.android.core

/**
 * Recognizes a short button label or toast that means text was just copied, such as "Copy" or "Salin".
 * Long text is ignored, so tapping a message or article that mentions copying doesn't count.
 * A wrong match only makes the phone check the clipboard, and unchanged text is not sent again.
 */
object CopyDetector {
    private const val MAX_LABEL = 40
    private val words = Regex("\\b(copy|copied|salin|disalin|tersalin|menyalin)\\b", RegexOption.IGNORE_CASE)

    fun isCopyLabel(label: CharSequence?): Boolean {
        val text = label?.trim() ?: return false
        return text.isNotEmpty() && text.length <= MAX_LABEL && words.containsMatchIn(text)
    }
}
