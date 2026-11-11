package id.phonelink.android.service

import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import id.phonelink.android.core.AppEntry
import id.phonelink.android.core.PermissionGroup

/** Reads installed apps, their size, last use, and what they are allowed to do. Read only. */
object PhoneApps {
    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessIntent(context: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            // Some phones open DyskLink's own switch with this; others show the list.
            data = Uri.parse("package:${context.packageName}")
        }.takeIf { it.resolveActivity(context.packageManager) != null }
            ?: Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    fun appInfoIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Free and total bytes of shared storage. */
    fun storage(): Pair<Long, Long> = runCatching {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        stat.availableBytes to stat.totalBytes
    }.getOrDefault(0L to 0L)

    fun load(context: Context): List<AppEntry> {
        val manager = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val packages = manager.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.distinct()
            .filter { it != context.packageName }
        val usage = hasUsageAccess(context)
        val now = System.currentTimeMillis()
        val lastUsed: Map<String, Long> = if (usage) {
            runCatching {
                context.getSystemService(UsageStatsManager::class.java)
                    .queryAndAggregateUsageStats(now - 365L * 24 * 60 * 60 * 1000, now)
                    .mapValues { it.value.lastTimeUsed }
            }.getOrDefault(emptyMap())
        } else {
            emptyMap()
        }
        val stats = if (usage) context.getSystemService(StorageStatsManager::class.java) else null
        val notifications = enabledPackages(context, "enabled_notification_listeners")
        val accessibility = enabledPackages(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)

        return packages.mapNotNull { name ->
            val info: PackageInfo = runCatching { packageInfo(manager, name) }.getOrNull() ?: return@mapNotNull null
            val app = info.applicationInfo ?: return@mapNotNull null
            val requested = info.requestedPermissions.orEmpty()
            val flags = info.requestedPermissionsFlags ?: IntArray(0)
            val granted = requested.filterIndexed { index, _ ->
                flags.getOrElse(index) { 0 } and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0
            }.toSet()
            var groups = PermissionGroup.of(granted)
            if (name in notifications) groups = groups + PermissionGroup.Notifications
            if (name in accessibility) groups = groups + PermissionGroup.Accessibility
            val size = stats?.let { manager ->
                runCatching {
                    val result = manager.queryStatsForPackage(StorageManager.UUID_DEFAULT, name, Process.myUserHandle())
                    result.appBytes + result.dataBytes + result.cacheBytes
                }.getOrNull()
            }
            AppEntry(
                packageName = name,
                name = app.loadLabel(context.packageManager).toString(),
                isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0 && app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0,
                sizeBytes = size,
                lastUsed = if (usage) lastUsed[name] ?: 0L else null,
                installed = info.firstInstallTime,
                groups = groups,
            )
        }
    }

    private fun packageInfo(manager: PackageManager, name: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageInfo(name, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(name, PackageManager.GET_PERMISSIONS)
        }

    /** Packages with a service turned on in a settings list such as Accessibility. */
    private fun enabledPackages(context: Context, key: String): Set<String> =
        Settings.Secure.getString(context.contentResolver, key).orEmpty().split(':')
            .mapNotNull { ComponentName.unflattenFromString(it)?.packageName }.toSet()
}
