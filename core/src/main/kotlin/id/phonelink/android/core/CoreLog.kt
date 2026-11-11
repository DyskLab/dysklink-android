package id.phonelink.android.core

/**
 * Logging for the shared core, which can't use android.util.Log. Each app points [sink] at its own log;
 * until then nothing is written, as in tests.
 */
object CoreLog {
    enum class Level { INFO, WARN }

    @Volatile var sink: (level: Level, tag: String, message: String, error: Throwable?) -> Unit = { _, _, _, _ -> }

    fun i(tag: String, message: String) = sink(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = sink(Level.WARN, tag, message, error)
}
