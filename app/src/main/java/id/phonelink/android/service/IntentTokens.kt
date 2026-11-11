package id.phonelink.android.service

import java.security.SecureRandom

/**
 * One-time tokens for notification taps that start something (camera, screen).
 *
 * MainActivity is exported, so any app can send it an intent with the same extras. Only an intent
 * carrying a token this process made for that exact request is acted on, and each token works once.
 */
class IntentTokens(
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val lifetimeMillis: Long = 60 * 60 * 1000L,
) {
    private data class Entry(val purpose: String, val peerId: String, val expires: Long)

    private val issued = HashMap<String, Entry>()

    @Synchronized
    fun issue(purpose: String, peerId: String): String {
        val now = clock()
        issued.entries.removeAll { it.value.expires <= now }
        val bytes = ByteArray(16).also(random::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        issued[token] = Entry(purpose, peerId, now + lifetimeMillis)
        return token
    }

    /** True only once, for a live token issued for this purpose and peer. A wrong guess doesn't burn a real token. */
    @Synchronized
    fun consume(token: String?, purpose: String, peerId: String): Boolean {
        if (token.isNullOrEmpty()) return false
        val entry = issued[token] ?: return false
        if (entry.purpose != purpose || entry.peerId != peerId) return false
        issued.remove(token)
        return entry.expires > clock()
    }

    companion object {
        const val CAMERA = "camera"
        const val SCREEN = "screen"
        val shared = IntentTokens()
    }
}
