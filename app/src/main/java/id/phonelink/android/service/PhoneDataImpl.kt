package id.phonelink.android.service

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import id.phonelink.android.MainActivity
import id.phonelink.android.R
import id.phonelink.android.core.ContactItem
import id.phonelink.android.core.ControlEvent
import id.phonelink.android.core.NotificationItem
import id.phonelink.android.core.PhoneBridge
import id.phonelink.android.core.PhoneData
import id.phonelink.android.core.PhoneFeature
import id.phonelink.android.core.SmsItem
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class PhoneDataImpl(context: Context) : PhoneData {
    private val context = context.applicationContext

    override val storageRoot: File get() = Environment.getExternalStorageDirectory()

    override fun isGranted(feature: PhoneFeature): Boolean = when (feature) {
        PhoneFeature.Notifications ->
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
        PhoneFeature.Files -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            hasPermissions(feature)
        }
        // The Mac can only ask. The Android dialog still appears every session.
        PhoneFeature.Screen -> true
        PhoneFeature.Control -> DyskAccessibilityService.isRunning
        else -> hasPermissions(feature)
    }

    private fun hasPermissions(feature: PhoneFeature) = runtimePermissions(feature).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun activeNotifications(): List<NotificationItem> = DyskNotificationListener.activeItems()

    override fun dismissNotification(key: String) = DyskNotificationListener.dismiss(key)

    override fun readSms(limit: Int, afterId: Long): List<SmsItem> {
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE,
        )
        // Drafts (type 3) are excluded because they aren't messages yet.
        val selection = "${Telephony.Sms._ID} > ? AND ${Telephony.Sms.TYPE} IN (1, 2, 4, 5, 6)"
        val result = ArrayList<SmsItem>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI, projection, selection, arrayOf(afterId.toString()), "${Telephony.Sms._ID} DESC",
        )?.use { cursor ->
            while (result.size < limit && cursor.moveToNext()) {
                val address = cursor.getString(2).orEmpty()
                if (address.isEmpty()) continue
                result.add(
                    SmsItem(
                        id = cursor.getLong(0),
                        threadId = cursor.getLong(1),
                        address = address,
                        body = cursor.getString(3).orEmpty().take(MAX_SMS_BODY),
                        time = cursor.getLong(4),
                        outgoing = cursor.getInt(5) != Telephony.Sms.MESSAGE_TYPE_INBOX,
                    ),
                )
            }
        }
        return result
    }

    @Suppress("DEPRECATION") // SmsManager.getDefault() is still needed below Android 12.
    override fun sendSms(address: String, body: String, done: (String?) -> Unit) {
        val manager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
        val parts = manager.divideMessage(body)
        val action = "${context.packageName}.SMS_SENT.${UUID.randomUUID()}"
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        var remaining = parts.size
        var failure: String? = null

        lateinit var receiver: BroadcastReceiver
        val finish = { error: String? ->
            if (finished.compareAndSet(false, true)) {
                handler.removeCallbacksAndMessages(action)
                runCatching { context.unregisterReceiver(receiver) }
                done(error)
            }
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ignored: Context, intent: Intent) {
                if (resultCode != Activity.RESULT_OK) failure = "Carrier rejected the SMS, code $resultCode"
                remaining -= 1
                if (remaining == 0) finish(failure)
            }
        }
        // The outcome is only known once the carrier replies, so it isn't assumed to succeed.
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        handler.postDelayed({ finish("The phone got no answer from the carrier") }, action, SEND_TIMEOUT_MS)
        val intents = ArrayList(
            parts.indices.map { index ->
                PendingIntent.getBroadcast(
                    context, index, Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                )
            },
        )
        try {
            manager.sendMultipartTextMessage(address, null, parts, intents, null)
        } catch (error: RuntimeException) {
            finish(error.message ?: "Failed to send SMS")
        }
    }

    override fun readContacts(): List<ContactItem> {
        val projection = arrayOf(Phone.CONTACT_ID, Phone.DISPLAY_NAME, Phone.NUMBER)
        val contacts = LinkedHashMap<String, Pair<String, LinkedHashSet<String>>>()
        context.contentResolver.query(Phone.CONTENT_URI, projection, null, null, "${Phone.DISPLAY_NAME} ASC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val number = cursor.getString(2)?.takeIf { it.isNotBlank() } ?: continue
                val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: number
                contacts.getOrPut(id) { name to LinkedHashSet() }.second.add(number)
            }
        }
        return contacts.map { (id, value) -> ContactItem(id, value.first, value.second.toList()) }
    }

    override fun requestScreen(peerId: String) {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_SCREEN_PEER, peerId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val notification = Notification.Builder(context, PhoneLinkService.SCREEN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_glyph)
            .setContentTitle("Mac is requesting the phone screen")
            .setContentText("Tap to choose. Nothing is shared until you agree.")
            .setContentIntent(
                PendingIntent.getActivity(
                    context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(PhoneLinkService.SCREEN_REQUEST_NOTIFICATION_ID, notification)
    }

    override fun syncScreen(peerId: String) = ScreenCaptureService.requestSync(peerId)

    override fun stopScreen(peerId: String) = ScreenCaptureService.stop(context, peerId)

    override fun control(event: ControlEvent) = DyskAccessibilityService.handle(event)

    companion object {
        private const val MAX_SMS_BODY = 4_000
        private const val SEND_TIMEOUT_MS = 60_000L

        /** Android permissions requested by dialog. Notifications and files have their own Settings pages. */
        fun runtimePermissions(feature: PhoneFeature): List<String> = when (feature) {
            PhoneFeature.Notifications -> emptyList()
            PhoneFeature.Sms -> listOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
            PhoneFeature.Calls -> listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG)
            PhoneFeature.Contacts -> listOf(Manifest.permission.READ_CONTACTS)
            PhoneFeature.Files -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            PhoneFeature.Screen, PhoneFeature.Control -> emptyList()
        }
    }
}

/** Android itself runs this service once the user grants notification access. */
class DyskNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        current = this
        PhoneLinkService.connectionManager.value?.phone?.refresh()
    }

    override fun onListenerDisconnected() {
        if (current === this) current = null
        PhoneLinkService.connectionManager.value?.phone?.refresh()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val item = toItem(this, sbn) ?: return
        PhoneLinkService.connectionManager.value?.phone?.notificationPosted(item)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        PhoneLinkService.connectionManager.value?.phone?.notificationRemoved(sbn.key)
    }

    companion object {
        private const val MAX_TITLE = 200
        private const val MAX_TEXT = 2_000
        @Volatile private var current: DyskNotificationListener? = null

        fun activeItems(): List<NotificationItem> {
            val listener = current ?: return emptyList()
            return runCatching { listener.activeNotifications.orEmpty().mapNotNull { toItem(listener, it) } }
                .getOrDefault(emptyList())
        }

        fun dismiss(key: String) {
            runCatching { current?.cancelNotification(key) }
        }

        private fun toItem(context: Context, sbn: StatusBarNotification): NotificationItem? {
            val notification = sbn.notification
            val isSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
            // Notifications their app marks as secret are never sent.
            if (sbn.packageName == context.packageName || sbn.isOngoing || isSummary ||
                notification.visibility == Notification.VISIBILITY_SECRET
            ) {
                return null
            }
            val extras = notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(MAX_TITLE)
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString()?.take(MAX_TEXT)
            if (title.isNullOrBlank() && text.isNullOrBlank()) return null
            return NotificationItem(
                key = sbn.key,
                app = appName(context, sbn.packageName),
                title = title,
                text = text,
                time = sbn.postTime,
                silent = (notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0).takeIf { it },
            )
        }

        private fun appName(context: Context, packageName: String): String = runCatching {
            val manager = context.packageManager
            manager.getApplicationLabel(manager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }
}

/** SMS and call watchers only live while their feature is on. */
class PhoneWatchers(context: Context, private val bridge: PhoneBridge) {
    private val context = context.applicationContext
    private var smsObserver: ContentObserver? = null
    private var callReceiver: BroadcastReceiver? = null

    fun sync(active: Set<PhoneFeature>) {
        syncSms(PhoneFeature.Sms in active)
        syncCalls(PhoneFeature.Calls in active)
    }

    private fun syncSms(on: Boolean) {
        if (on == (smsObserver != null)) return
        val current = smsObserver
        if (current != null) {
            context.contentResolver.unregisterContentObserver(current)
            smsObserver = null
            return
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = bridge.smsChanged()
        }
        bridge.markSmsSeen()
        context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        smsObserver = observer
    }

    @Suppress("DEPRECATION") // The caller number is only available through this broadcast without being the phone app.
    private fun syncCalls(on: Boolean) {
        if (on == (callReceiver != null)) return
        val current = callReceiver
        if (current != null) {
            context.unregisterReceiver(current)
            callReceiver = null
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ignored: Context, intent: Intent) {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
                bridge.callStateChanged(state, intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER))
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        callReceiver = receiver
    }

    fun stop() = sync(emptySet())
}
