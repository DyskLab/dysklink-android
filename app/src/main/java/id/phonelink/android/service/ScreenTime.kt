package id.phonelink.android.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import id.phonelink.android.core.ScreenTimeMath
import id.phonelink.android.core.UsageApp
import id.phonelink.android.core.UsageReport
import java.util.Calendar

/** Today's screen time from Android's usage events. Needs Usage access. Read only. */
object ScreenTime {
    private const val TOP_APPS = 8

    fun today(context: Context): UsageReport? {
        if (!PhoneApps.hasUsageAccess(context)) return null
        val since = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val now = System.currentTimeMillis()
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val math = ScreenTimeMath(since)
        runCatching {
            val events = manager.queryEvents(since, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val kind = when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> ScreenTimeMath.Kind.Resumed
                    UsageEvents.Event.ACTIVITY_PAUSED -> ScreenTimeMath.Kind.Paused
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> ScreenTimeMath.Kind.ScreenOff
                    UsageEvents.Event.KEYGUARD_HIDDEN -> ScreenTimeMath.Kind.Unlocked
                    else -> null
                } ?: continue
                math.add(kind, event.packageName.orEmpty(), event.className.orEmpty(), event.timeStamp)
            }
        }.onFailure { return null }
        // The home screen and the system bars aren't apps you spend time in, as in Digital Wellbeing.
        val home = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        val totals = math.result(now) { it == home || it == "com.android.systemui" || it.isEmpty() }
        val apps = totals.take(TOP_APPS).map { (pkg, ms) ->
            UsageApp(pkg, label(context, pkg), ms, NotificationImages.appIcon(context, pkg))
        }
        return UsageReport(since, totals.sumOf { it.second }, apps, math.unlocks)
    }

    private fun label(context: Context, pkg: String): String = runCatching {
        val manager = context.packageManager
        manager.getApplicationLabel(manager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg).take(60)
}
