package id.phonelink.android.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import id.phonelink.android.MainActivity
import id.phonelink.android.core.Envelope
import id.phonelink.android.core.MessageType
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sound between this phone and a Mac, as 16-bit stereo PCM at 48 kHz in 40 ms chunks.
 * Listening plays the Mac's sound here. Sending records what this phone plays (Android 10+) and plays it on the Mac.
 */
object AudioShare {
    const val RATE = 48_000
    const val CHANNELS = 2
    const val CHUNK_BYTES = 1_920 * CHANNELS * 2

    private val _listening = MutableStateFlow<String?>(null)
    /** The Mac whose sound plays here now. */
    val listening: StateFlow<String?> = _listening.asStateFlow()
    private val _sending = MutableStateFlow<String?>(null)
    /** The Mac this phone's sound goes to now. */
    val sending: StateFlow<String?> = _sending.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    private var player: MacSoundPlayer? = null
    /** Listening started from the Mac screen keeps a shorter buffer, so sound stays close to the picture. */
    private var tight = false
    /** True when the Mac screen turned listening on, so leaving it turns listening off again. */
    var fromScreen = false
        private set
    private var pump: PlaybackPump? = null

    val canSend: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private const val PREFS = "sound"
    private const val WITH_SCREEN = "with_screen"

    /** Whether this phone's sound goes with its screen to the Mac. On by default; needs Android 10 and the audio permission. */
    fun soundWithScreen(context: Context): Boolean =
        canSend && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(WITH_SCREEN, true) &&
            context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun wantsSoundWithScreen(context: Context): Boolean =
        canSend && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(WITH_SCREEN, true)

    fun setSoundWithScreen(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(WITH_SCREEN, on).apply()
    }

    fun listen(peerId: String, fromScreen: Boolean = false) {
        _problem.value = null
        tight = fromScreen
        this.fromScreen = fromScreen
        PhoneLinkService.connectionManager.value?.post(peerId, Envelope(MessageType.AUDIO_LISTEN))
    }

    fun stopListening(peerId: String) {
        PhoneLinkService.connectionManager.value?.post(peerId, Envelope(MessageType.AUDIO_STOP))
        endPlayer()
    }

    @Synchronized
    private fun endPlayer() {
        player?.stop()
        player = null
        _listening.value = null
        fromScreen = false
    }

    /** Messages from a Mac: its sound starting, a chunk of it, its end, or a request to stop sending ours. */
    @Synchronized
    fun handle(context: Context, peerId: String, envelope: Envelope) {
        when (envelope.type) {
            MessageType.AUDIO_STARTED -> {
                player?.stop()
                player = MacSoundPlayer(envelope.audioRate ?: RATE, envelope.audioChannels ?: CHANNELS, if (tight) 4 else 12).also { it.start() }
                _listening.value = peerId
                _problem.value = null
            }
            MessageType.AUDIO_FRAME -> if (_listening.value == peerId) {
                envelope.audio?.let { runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull() }?.let { player?.feed(it) }
            }
            MessageType.AUDIO_STOPPED -> if (_listening.value == peerId || _listening.value == null) {
                envelope.error?.let { _problem.value = it }
                endPlayer()
            }
            // The Mac asked to stop hearing this phone: the screen goes on without sound.
            MessageType.AUDIO_STOP -> if (_sending.value == peerId) {
                pump?.stop()
                PhoneSoundService.stop(context)
            }
        }
    }

    /** Ends sound with any device that isn't in [connected] any more: the player here, and the sound sent there. */
    @Synchronized
    fun keepOnly(context: Context, connected: Set<String>) {
        _listening.value?.takeIf { it !in connected }?.let { endPlayer() }
        val target = _sending.value
        if (target != null && target !in connected) {
            pump?.stop()
            PhoneSoundService.stop(context)
        }
    }

    @Synchronized internal fun attach(sound: PlaybackPump, peerId: String) {
        pump?.takeIf { it !== sound }?.stop()
        pump = sound
        _sending.value = peerId
        _problem.value = null
    }

    @Synchronized internal fun detach(sound: PlaybackPump) {
        if (pump !== sound) return
        pump = null
        _sending.value = null
    }
    internal fun setProblem(text: String?) { _problem.value = text }
}

/** Plays a Mac's sound. A writer thread keeps a short queue; when the network bunches chunks up, old ones are dropped. */
private class MacSoundPlayer(rate: Int, private val channels: Int, private val maxQueued: Int) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = true
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAudioFormat(
            AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO).build(),
        )
        .setBufferSizeInBytes(maxOf(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT), AudioShare.CHUNK_BYTES * 4))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    private val writer = Thread({
        while (running) {
            val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            track.write(chunk, 0, chunk.size)
        }
    }, "dysk-audio-play")

    fun start() {
        track.play()
        writer.start()
    }

    fun feed(chunk: ByteArray) {
        // At most about half a second behind, or 160 ms beside the Mac screen, so the sound stays close to the Mac.
        while (queue.size > maxQueued) queue.poll()
        queue.offer(chunk)
    }

    fun stop() {
        running = false
        runCatching { track.pause(); track.flush(); track.release() }
    }
}

/**
 * Records what this phone plays from a MediaProjection and sends it to a Mac.
 * Used on its own by [PhoneSoundService], and together with the screen by [ScreenCaptureService], so one Android prompt covers both.
 */
class PlaybackPump(
    private val peerId: String,
    /** Called on the recording thread when recording ends by itself (the recorder failed), with why. */
    private val onEnded: ((String) -> Unit)? = null,
) {
    @Volatile private var running = false
    private var recorder: AudioRecord? = null

    /** Starts recording, or returns why it can't. */
    @SuppressLint("MissingPermission")
    fun start(projection: MediaProjection): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "Needs Android 10 or newer"
        return try {
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val format = AudioFormat.Builder().setSampleRate(AudioShare.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val record = AudioRecord.Builder().setAudioPlaybackCaptureConfig(config).setAudioFormat(format)
                .setBufferSizeInBytes(AudioShare.CHUNK_BYTES * 4).build()
            record.startRecording()
            recorder = record
            running = true
            AudioShare.attach(this, peerId)
            send(Envelope(MessageType.AUDIO_STARTED, audioRate = AudioShare.RATE, audioChannels = AudioShare.CHANNELS))
            Thread({ pump(record) }, "dysk-audio-send").start()
            null
        } catch (error: Exception) {
            error.message ?: "Can't record the phone's sound"
        }
    }

    private fun pump(record: AudioRecord) {
        val chunk = ByteArray(AudioShare.CHUNK_BYTES)
        var failures = 0
        while (running) {
            var filled = 0
            while (running && filled < chunk.size) {
                val read = record.read(chunk, filled, chunk.size - filled)
                if (read < 0) {
                    // The recorder is dead (projection ended, device lost): stop instead of spinning.
                    if (!running) return
                    val why = "Recording stopped (error $read)"
                    stop(why)
                    onEnded?.invoke(why)
                    return
                }
                if (read == 0) {
                    Thread.sleep(5)
                    continue
                }
                filled += read
            }
            if (filled < chunk.size) continue
            if (send(Envelope(MessageType.AUDIO_FRAME, audio = java.util.Base64.getEncoder().encodeToString(chunk)))) {
                failures = 0
            } else if (++failures >= MAX_SEND_FAILURES) {
                // The Mac is gone: recording on for nobody only costs battery.
                if (!running) return
                val why = "The connection was lost"
                stop(why)
                onEnded?.invoke(why)
                return
            }
        }
    }

    /** Stops recording and tells the Mac. Safe to call twice. */
    fun stop(error: String? = null) {
        if (!running) return
        running = false
        runCatching { recorder?.stop(); recorder?.release() }
        recorder = null
        PhoneLinkService.connectionManager.value?.post(peerId, Envelope(MessageType.AUDIO_STOPPED, error = error))
        AudioShare.detach(this)
    }

    private fun send(envelope: Envelope): Boolean =
        PhoneLinkService.connectionManager.value?.sendDirect(peerId, envelope) ?: false

    private companion object {
        /** About two seconds of sound that couldn't be sent. */
        const val MAX_SEND_FAILURES = 50
    }
}

/** Sends this phone's sound to a Mac without its screen. Android asks first, and apps that block recording stay silent. */
class PhoneSoundService : Service() {
    private var projection: MediaProjection? = null
    private var pump: PlaybackPump? = null
    private var started = false
    private val main = Handler(Looper.getMainLooper())

    /** The user stopped the recording from the system (status bar chip, lock screen): end the share. */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            main.post { stopSelf() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
        val peer = intent?.getStringExtra(EXTRA_PEER)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || code != Activity.RESULT_OK || data == null || peer == null || started) {
            if (!started) stopSelf()
            return START_NOT_STICKY
        }
        started = true
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val capture = runCatching { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data) }.getOrNull()
        if (capture == null) {
            AudioShare.setProblem("Recording permission is unavailable")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = capture
        capture.registerCallback(projectionCallback, main)
        val sound = PlaybackPump(peer) { why ->
            AudioShare.setProblem(why)
            main.post { stopSelf() }
        }
        val failure = sound.start(capture)
        if (failure != null) {
            AudioShare.setProblem(failure)
            PhoneLinkService.connectionManager.value?.post(peer, Envelope(MessageType.AUDIO_STOPPED, error = failure))
            stopSelf()
        } else {
            pump = sound
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sound to Mac", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, PhoneSoundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Playing this phone's sound on the Mac")
            .setContentText("Music and videos on this phone are heard on the Mac.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        // Tells the computer the sound ended (no-op if it already did).
        pump?.stop()
        pump = null
        projection?.let { capture ->
            runCatching { capture.unregisterCallback(projectionCallback) }
            runCatching { capture.stop() }
        }
        projection = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "id.phonelink.android.STOP_SOUND"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_PEER = "peer"
        private const val CHANNEL = "sound"
        private const val NOTIFICATION_ID = NotificationIds.PHONE_SOUND

        fun start(context: Context, code: Int, data: Intent, peerId: String) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, PhoneSoundService::class.java).putExtra(EXTRA_CODE, code).putExtra(EXTRA_DATA, data).putExtra(EXTRA_PEER, peerId),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PhoneSoundService::class.java))
        }
    }
}
