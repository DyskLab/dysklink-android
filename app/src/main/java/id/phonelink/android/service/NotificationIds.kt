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
    /** This phone's push-to-talk, while Talk is held. */
    const val TALK = 20
    /** Another phone's voice playing here. */
    const val TALK_HERE = 21
    /** Another phone's sound playing here. */
    const val PEER_SOUND = 22
    /** "Allow this phone to talk / play sound here?", tagged per phone and kind. */
    const val PHONE_ASK = 23
    /** Another phone's notifications, tagged per phone and notification. */
    const val MIRROR = 24
    /** Another phone's incoming or missed call, tagged per phone. */
    const val MIRROR_CALL = 25
    /** "A sent a link", tagged per phone and link. */
    const val PHONE_LINK = 26
    /** "A turned on Do Not Disturb" with Undo. */
    const val PHONE_QUIET = 27
    /** Another phone's battery is low or full, tagged per phone. */
    const val PHONE_BATTERY = 28
    /** One more step after allowing a phone: give Android access. */
    const val PHONE_SETUP = 29
    /** Another phone sent an SMS through this one. */
    const val PHONE_SMS_SENT = 30
    /** A new SMS on another phone, shown here. */
    const val PHONE_SMS = 31
    /** A phone's backup or move stopped: this phone is nearly full. */
    const val PHONE_LOW_SPACE = 32
    /** An old phone finished moving here. */
    const val PHONE_MOVE = 33
    /** Photo backup to another phone stopped. */
    const val PHOTO_BACKUP = 34
    /** "Time to drink water", from the phone's own Water tool. */
    const val WATER = 35
    /** The phone's own Pomodoro countdown. */
    const val POMODORO = 36

    val all = listOf(
        CONNECTION, PAIRING, SCREEN_REQUEST, SCREEN_SHARING, CAMERA, CAMERA_REQUEST, CHARGE_REMINDER, PRAYER, UPDATES, RINGER, PHONE_SOUND,
        TALK, TALK_HERE, PEER_SOUND, PHONE_ASK, MIRROR, MIRROR_CALL, PHONE_LINK, PHONE_QUIET, PHONE_BATTERY, PHONE_SETUP,
        PHONE_SMS_SENT, PHONE_SMS, PHONE_LOW_SPACE, PHONE_MOVE, PHOTO_BACKUP, WATER, POMODORO,
    )
}
