package id.phonelink.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import id.phonelink.android.R

/**
 * Rings loudly so the phone can be found from the Mac. It plays on the alarm stream, which silent mode doesn't
 * mute, raises that stream to full for the ring, and puts the volume back afterwards. It stops after 30 seconds.
 */
object PhoneRinger {
    private const val TAG = "PhoneLink"
    private const val CHANNEL_ID = "find_phone"
    private const val NOTIFICATION_ID = NotificationIds.RINGER
    private const val RING_MS = 30_000L
    const val ACTION_STOP = "id.phonelink.android.STOP_RING"

    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var savedVolume: Int? = null
    private val timeout: Runnable = Runnable { appContext?.let { end(it) } }
    private var appContext: Context? = null

    val isRinging: Boolean get() = player != null

    fun start(context: Context, from: String) {
        main.post { begin(context, from) }
    }

    fun stop(context: Context) {
        main.post { end(context) }
    }

    private fun begin(context: Context, from: String) {
        val app = context.applicationContext
        appContext = app
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, RING_MS)
        if (player != null) return
        val audio = app.getSystemService(AudioManager::class.java)
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        val sound = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(app, sound)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { Log.w(TAG, "Can't play the ring", it) }.getOrNull()
        showNotification(app, from)
    }

    private fun end(context: Context) {
        main.removeCallbacks(timeout)
        val wasRinging = player != null
        player?.runCatching { stop(); release() }
        player = null
        val app = context.applicationContext
        savedVolume?.let { volume ->
            runCatching { app.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
        }
        savedVolume = null
        app.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        if (wasRinging) PhoneLinkService.connectionManager.value?.ringStopped()
    }

    private fun showNotification(context: Context, from: String) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Find phone", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) },
        )
        val stop = PendingIntent.getBroadcast(
            context, 7, Intent(context, RingStopReceiver::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo_glyph)
            .setContentTitle("$from is looking for this phone")
            .setContentText("Tap Stop to end the sound.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(stop)
            .setDeleteIntent(stop)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        notifications.notify(NOTIFICATION_ID, notification)
    }
}

class RingStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == PhoneRinger.ACTION_STOP) PhoneRinger.stop(context)
    }
}
