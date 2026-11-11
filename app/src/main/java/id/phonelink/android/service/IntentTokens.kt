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

    /** In the order issued, so the oldest goes first when there are too many. */
    private val issued = LinkedHashMap<String, Entry>()

    /** A new token for this purpose and peer replaces the older one: only the newest request's tap works. */
    @Synchronized
    fun issue(purpose: String, peerId: String): String {
        val now = clock()
        issued.entries.removeAll { it.value.expires <= now || (it.value.purpose == purpose && it.value.peerId == peerId) }
        while (issued.size >= MAX_TOKENS) issued.remove(issued.keys.first())
        val bytes = ByteArray(16).also(random::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        issued[token] = Entry(purpose, peerId, now + lifetimeMillis)
        return token
    }

    @get:Synchronized
    val size: Int get() = issued.size

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
        /** Far more than the requests any number of Macs can have open at once. */
        const val MAX_TOKENS = 32
        /** Shown when a tap's token is missing, used, replaced, or expired. */
        const val EXPIRED_MESSAGE = "Request expired. Ask again on the Mac."
        val shared = IntentTokens()
    }
}
