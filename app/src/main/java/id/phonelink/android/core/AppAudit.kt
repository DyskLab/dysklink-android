package id.phonelink.android.core

/** Sensitive things an app can be allowed to do, grouped the way Android settings shows them. */
enum class PermissionGroup(val title: String, val blurb: String, val permissions: Set<String>) {
    Location("Location", "Where the phone is.", setOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")),
    BackgroundLocation("Location all the time", "Where the phone is, even when the app is closed.", setOf("android.permission.ACCESS_BACKGROUND_LOCATION")),
    Camera("Camera", "Taking photos and video.", setOf("android.permission.CAMERA")),
    Microphone("Microphone", "Recording sound.", setOf("android.permission.RECORD_AUDIO")),
    Contacts("Contacts", "Reading your contacts.", setOf("android.permission.READ_CONTACTS", "android.permission.WRITE_CONTACTS")),
    Sms("SMS", "Reading or sending text messages.", setOf("android.permission.READ_SMS", "android.permission.SEND_SMS", "android.permission.RECEIVE_SMS")),
    Calls("Phone and call log", "Making calls and seeing who called.", setOf("android.permission.READ_CALL_LOG", "android.permission.CALL_PHONE", "android.permission.READ_PHONE_STATE")),
    Media("Photos and videos", "Reading photos, videos, and files.", setOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_EXTERNAL_STORAGE")),
    Notifications("Reads notifications", "Sees every notification, including messages and codes.", emptySet()),
    Accessibility("Accessibility", "Can see the screen and tap for you.", emptySet()),
    ;

    companion object {
        /** Groups for the permissions an app was granted. Notifications and Accessibility come from settings instead. */
        fun of(granted: Set<String>): Set<PermissionGroup> = entries.filter { group -> group.permissions.any { it in granted } }.toSet()
    }
}

data class AppEntry(
    val packageName: String,
    val name: String,
    val isSystem: Boolean,
    /** App, data, and cache together. Null without usage access. */
    val sizeBytes: Long?,
    /** Null without usage access, 0 when Android has no record of it being opened. */
    val lastUsed: Long?,
    val installed: Long,
    val groups: Set<PermissionGroup>,
)

enum class AppSort(val label: String) { Size("Size"), LastUsed("Last used"), Name("Name") }

object AppAudit {
    const val UNUSED_DAYS = 30L
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Opened before the cutoff, or never, and installed before it, so a new app doesn't count. */
    fun isUnused(app: AppEntry, now: Long, days: Long = UNUSED_DAYS): Boolean {
        val cutoff = now - days * DAY_MS
        val used = app.lastUsed ?: return false
        return used < cutoff && app.installed < cutoff
    }

    fun sorted(apps: List<AppEntry>, sort: AppSort): List<AppEntry> = when (sort) {
        AppSort.Size -> apps.sortedWith(compareByDescending<AppEntry> { it.sizeBytes ?: -1L }.thenBy { it.name.lowercase() })
        // Least recently used first, since those are the ones worth a look.
        AppSort.LastUsed -> apps.sortedWith(compareBy<AppEntry> { it.lastUsed ?: Long.MAX_VALUE }.thenBy { it.name.lowercase() })
        AppSort.Name -> apps.sortedBy { it.name.lowercase() }
    }

    fun lastUsedText(app: AppEntry, now: Long): String {
        val used = app.lastUsed ?: return ""
        if (used <= 0) return "Never opened"
        val days = (now - used) / DAY_MS
        return when {
            days < 1 -> "Used today"
            days < 2 -> "Used yesterday"
            days < 30 -> "Used $days days ago"
            days < 365 -> "Not used in ${days / 30} month" + if (days / 30 > 1) "s" else ""
            else -> "Not used in over a year"
        }
    }
}
