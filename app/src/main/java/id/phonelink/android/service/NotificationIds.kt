package id.phonelink.android.service

/**
 * Every notification ID the app posts, in one place so no two features share one: a shared ID makes one
 * notification silently replace or cancel the other.
 */
object NotificationIds {
    const val CONNECTION = 1
    const val PAIRING = 2
    const val SCREEN_REQUEST = 3
    const val SCREEN_SHARING = 4
    const val CAMERA = 6
    const val CAMERA_REQUEST = 7
    const val CHARGE_REMINDER = 8
    const val PRAYER = 9
    const val UPDATES = 10
    const val RINGER = 11
    const val PHONE_SOUND = 17

    val all = listOf(CONNECTION, PAIRING, SCREEN_REQUEST, SCREEN_SHARING, CAMERA, CAMERA_REQUEST, CHARGE_REMINDER, PRAYER, UPDATES, RINGER, PHONE_SOUND)
}
