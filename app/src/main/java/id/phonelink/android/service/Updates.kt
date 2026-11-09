package id.phonelink.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import id.phonelink.android.R
import id.phonelink.android.core.AppRelease
import id.phonelink.android.core.UpdateCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks for a newer release once a day. It only reads the public release list,
 * sends nothing about the phone, and never downloads or installs anything by itself.
 */
object Updates {
    data class State(
        val auto: Boolean = true,
        val available: AppRelease? = null,
        val isChecking: Boolean = false,
        val status: String? = null,
    )

    const val CHANNEL_ID = "updates"
    private const val NOTIFICATION_ID = 4
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val FIRST_DELAY_MS = 15_000L

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val lock = Mutex()

    @Volatile var currentVersion: String = "0"
        private set

    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** Reads the installed version and creates the notification channel. */
    fun prepare(context: Context) {
        currentVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        mutableState.value = mutableState.value.copy(auto = prefs(context).getBoolean("auto", true))
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    /** Runs for as long as the service does, checking once a day while automatic checks are on. */
    suspend fun runLoop(context: Context) {
        while (true) {
            val last = prefs(context).getLong("lastCheck", 0)
            delay(maxOf(FIRST_DELAY_MS, DAY_MS - (System.currentTimeMillis() - last)))
            if (state.value.auto) check(context, manual = false)
            else delay(DAY_MS)
        }
    }

    fun setAuto(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("auto", on).apply()
        mutableState.value = mutableState.value.copy(auto = on)
    }

    suspend fun check(context: Context, manual: Boolean) {
        if (!lock.tryLock()) return
        try {
            mutableState.value = mutableState.value.copy(isChecking = true, status = if (manual) null else state.value.status)
            val result = runCatching { withContext(Dispatchers.IO) { fetch() } }
            val prefs = prefs(context)
            mutableState.value = result.fold(
                onSuccess = { release ->
                    prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply()
                    val skipped = prefs.getString("skipped", null)
                    if (release != null && (manual || release.version != skipped)) {
                        notifyOnce(context, release)
                        State(state.value.auto, release, false, null)
                    } else {
                        State(state.value.auto, null, false, if (manual) "DyskLink $currentVersion is the newest version." else null)
                    }
                },
                // A failed automatic check stays quiet and tries again tomorrow.
                onFailure = {
                    state.value.copy(isChecking = false, status = if (manual) "Couldn't reach GitHub. Try again later." else state.value.status)
                },
            )
        } finally {
            lock.unlock()
        }
    }

    private fun fetch(): AppRelease? {
        val connection = URL(UpdateCheck.RELEASES_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "DyskLink-Android/$currentVersion")
        try {
            if (connection.responseCode != 200) error("HTTP ${connection.responseCode}")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return UpdateCheck.newest(body, currentVersion)
        } finally {
            connection.disconnect()
        }
    }

    /** Hides this version until a newer one comes out. */
    fun skip(context: Context) {
        val release = state.value.available ?: return
        prefs(context).edit().putString("skipped", release.version).apply()
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        mutableState.value = state.value.copy(available = null)
    }

    /** Opens the APK link in the browser, which downloads it. Android then asks before installing. */
    fun downloadIntent(release: AppRelease): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl ?: release.pageUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun notifyOnce(context: Context, release: AppRelease) {
        val prefs = prefs(context)
        if (prefs.getString("notified", null) == release.version) return
        prefs.edit().putString("notified", release.version).apply()
        val download = PendingIntent.getActivity(
            context, 4, downloadIntent(release), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_glyph)
            .setContentTitle("DyskLink ${release.version} is available")
            .setContentText("Tap to download the update.")
            .setContentIntent(download)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
    }
}
