package id.dysklink.desktop

/**
 * What the phone needs before the PC can use one of its features, said the way the phone's own screens say it.
 * A feature is missing from the phone's list when its switch is off there or Android's permission for it isn't given.
 */
object PhoneHints {
    /** The switch's name on the phone, in DyskLink, this PC's page, Phone data for Mac. */
    private fun switchName(feature: String): String = when (feature) {
        "notifications" -> "Notifications"
        "sms" -> "SMS"
        "contacts" -> "Contacts"
        "files" -> "Files"
        "screen" -> "Screen requests"
        "control" -> "Control from Mac"
        "gallery" -> "Gallery"
        "screentime" -> "Screen time"
        "calls" -> "Calls"
        else -> feature
    }

    /** What Android must allow for it, after the switch is on. */
    private fun permission(feature: String): String? = when (feature) {
        "notifications" -> "Notification access for DyskLink"
        "sms" -> "the SMS permission"
        "contacts" -> "the Contacts permission"
        "files" -> "All files access"
        "control" -> "Accessibility: Android Settings, Accessibility, DyskLink, turn it on"
        "gallery" -> "Photos and videos"
        "screentime" -> "Usage access"
        "calls" -> "the Phone permission"
        else -> null
    }

    /** For a feature the phone doesn't offer this PC: where to turn it on there. */
    fun featureOff(feature: String, what: String, phone: String, pc: String): HostHint = HostHint(
        "$what: turned off on $phone",
        "On $phone: open DyskLink, choose $pc, then Phone data for Mac, and turn on ${switchName(feature)}." +
            (permission(feature)?.let { " If it's on already, Android still has to allow $it." } ?: "") +
            " It can only be turned on from the phone; it applies to every paired computer.",
    )

    /** More help for a reason the phone sent back, when the reason alone doesn't say what to do. */
    fun refusalHelp(error: String, phone: String): String? {
        val text = error.lowercase()
        return when {
            "turned off on the phone" in text || "turned off there" in text ->
                "On $phone: open DyskLink, choose this PC, then Phone data for Mac, and turn it on."
            "didn't allow" in text -> "Try again and tap Allow on $phone."
            "camera couldn't" in text || "couldn't open" in text ->
                "Check that DyskLink may use the camera: on $phone, Android Settings, Apps, DyskLink, Permissions, Camera."
            "already sharing its screen" in text -> "Stop the other sharing from the notification on $phone, then try again."
            "accessibility" in text -> "On $phone: Android Settings, Accessibility, DyskLink, turn it on."
            else -> null
        }
    }
}
