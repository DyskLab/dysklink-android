package id.dysklink.desktop

/**
 * Finds a one-time code (OTP) in an SMS or notification, such as "Kode OTP Anda 482915". The Mac's detector
 * (OneTimeCode.swift), rule for rule, so the island shows the same code the Mac notch would.
 */
object OneTimeCode {
    /** Words that come with a code, in English and Indonesian. */
    val keywords = listOf(
        "otp", "kode", "code", "verif", "sandi", "password", "passcode", "pin", "token", "one-time",
        "sekali pakai", "2fa", "authenticat", "autentikasi", "masuk", "login", "sign in", "konfirmasi", "confirm",
    )

    // 4-8 digits, or two groups of three like 123-456. Not part of a longer number, amount, date, or time.
    private val pattern = Regex("""(?<![\d+])(?<!\d[.,:/\-])(\d{3}[- ]\d{3}|\d{4,8})(?!\d|[.,:/\-]\d)""")

    fun find(text: String): String? {
        val lower = text.lowercase()
        if (keywords.none { lower.contains(it) }) return null
        var best: Pair<String, Int>? = null
        for (match in pattern.findAll(lower)) {
            val group = match.groups[1] ?: continue
            val before = lower.substring(0, group.range.first)
            val near = before.takeLast(5).replace(" ", "")
            // Money and phone numbers.
            if (listOf("rp", "idr", "$", "usd", "+").any { near.endsWith(it) }) continue
            val code = group.value.filter { it.isDigit() }
            var score = if (code.length == 6) 3 else 1
            val context = before.takeLast(40)
            if (keywords.any { context.contains(it) }) score += 2
            // A year like 2026 on its own is rarely the code.
            if (code.length == 4 && (code.startsWith("19") || code.startsWith("20"))) score -= 2
            if (score > (best?.second ?: 0)) best = code to score
        }
        return best?.first
    }

    /** 482915 reads as 482 915. */
    fun spaced(code: String): String = if (code.length == 6) code.take(3) + " " + code.takeLast(3) else code
}

/** A code shown in the island with a Copy button. [source] is the app or sender, such as "BCA" or "Messages". */
data class CodeNotice(val code: String, val source: String, val phone: String, val copied: Boolean = false)

/**
 * The same code often comes twice (the SMS and the messaging app's notification): it's shown once within two
 * minutes, as on the Mac.
 */
class RecentCodes(private val windowMs: Long = 120_000) {
    private val seen = HashMap<String, Long>()

    @Synchronized
    fun isNew(code: String, now: Long): Boolean {
        seen.entries.removeIf { now - it.value >= windowMs }
        if (code in seen) return false
        seen[code] = now
        return true
    }
}
