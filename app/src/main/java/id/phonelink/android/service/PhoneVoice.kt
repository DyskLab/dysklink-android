package id.phonelink.android.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import id.phonelink.android.MainActivity
import id.phonelink.android.R
import id.phonelink.android.core.AudioKind
import id.phonelink.android.core.Envelope
import id.phonelink.android.core.MessageType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Voice and sound from other phones, played here once allowed: push-to-talk (`talk.*`) and a phone's sound
 * (`audio.*`). Also takes `talk.stopped` for this phone's own push-to-talk.
 */
object PeerAudio {
    private val _talking = MutableStateFlow<String?>(null)
    /** The phone whose voice plays here now. */
    val talking: StateFlow<String?> = _talking.asStateFlow()
    private val _sound = MutableStateFlow<String?>(null)
    /** The phone whose sound plays here now. */
    val sound: StateFlow<String?> = _sound.asStateFlow()

    private var voicePlayer: MacSoundPlayer? = null
    private var soundPlayer: MacSoundPlayer? = null

    private const val CHANNEL_VOICE = "walkie"
    private const val CHANNEL_ASK = "phone_requests"

    @Synchronized
    fun handle(context: Context, peerId: String, envelope: Envelope) {
        when (envelope.type) {
            MessageType.TALK_START -> {
                voicePlayer?.stop()
                val (rate, channels) = format(envelope, Talk.RATE, 1)
                // A short queue: a walkie-talkie should sound close to live.
                voicePlayer = MacSoundPlayer(rate, channels, 6, chunkBytes(rate, channels)).also { it.start() }
                _talking.value = peerId
                chirp(start = true)
                showVoice(context, peerId)
            }
            MessageType.TALK_FRAME -> if (_talking.value == peerId) envelope.payload()?.let { voicePlayer?.feed(it) }
            MessageType.TALK_STOP -> if (_talking.value == peerId) {
                voicePlayer?.stop()
                voicePlayer = null
                _talking.value = null
                chirp(start = false)
                notifications(context).cancel(NotificationIds.TALK_HERE)
            }
            MessageType.AUDIO_STARTED -> {
                soundPlayer?.stop()
                val (rate, channels) = format(envelope, AudioShare.RATE, AudioShare.CHANNELS)
                // The same cushion as for a Mac's sound: music may lag a little but must not stutter.
                soundPlayer = MacSoundPlayer(rate, channels, 12, chunkBytes(rate, channels)).also { it.start() }
                _sound.value = peerId
                showSound(context, peerId)
            }
            MessageType.AUDIO_FRAME -> if (_sound.value == peerId) envelope.payload()?.let { soundPlayer?.feed(it) }
            MessageType.AUDIO_STOPPED -> if (_sound.value == peerId) {
                soundPlayer?.stop()
                soundPlayer = null
                _sound.value = null
                notifications(context).cancel(NotificationIds.PEER_SOUND)
            }
            MessageType.TALK_STOPPED -> Talk.ended(context, peerId, envelope.error)
        }
    }

    private fun format(envelope: Envelope, rate: Int, channels: Int): Pair<Int, Int> =
        (envelope.audioRate?.takeIf { it in 8_000..48_000 } ?: rate) to (envelope.audioChannels?.takeIf { it in 1..2 } ?: channels)

    /** 40 ms of 16-bit PCM. */
    fun chunkBytes(rate: Int, channels: Int) = rate / 25 * channels * 2

    private fun chirp(start: Boolean) {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 40)
            tone.startTone(if (start) ToneGenerator.TONE_PROP_BEEP else ToneGenerator.TONE_PROP_ACK, 100)
            Handler(Looper.getMainLooper()).postDelayed({ tone.release() }, 400)
        }
    }

    private fun notifications(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun nameOf(peerId: String): String =
        PhoneLinkService.connectionManager.value?.peers?.value?.firstOrNull { it.id == peerId }?.name ?: "Another phone"

    private fun open(context: Context) =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun showVoice(context: Context, peerId: String) {
        val manager = notifications(context)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_VOICE, "Walkie-talkie", NotificationManager.IMPORTANCE_LOW))
        val name = nameOf(peerId)
        manager.notify(
            NotificationIds.TALK_HERE,
            Notification.Builder(context, CHANNEL_VOICE)
                .setSmallIcon(R.drawable.ic_logo_glyph)
                .setContentTitle("$name is talking")
                .setContentText("Their voice plays on this phone while they hold Talk.")
                .setContentIntent(open(context))
                .addAction(Notification.Action.Builder(null, "Stop", PeerActionReceiver.stopVoice(context, peerId)).build())
                .setOngoing(true)
                .build(),
        )
    }

    private fun showSound(context: Context, peerId: String) {
        val manager = notifications(context)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_VOICE, "Walkie-talkie", NotificationManager.IMPORTANCE_LOW))
        val name = nameOf(peerId)
        manager.notify(
            NotificationIds.PEER_SOUND,
            Notification.Builder(context, CHANNEL_VOICE)
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle("Playing sound from $name")
                .setContentText("Music and videos on $name are heard on this phone.")
                .setContentIntent(open(context))
                .addAction(Notification.Action.Builder(null, "Stop", PeerActionReceiver.stopSound(context, peerId)).build())
                .setOngoing(true)
                .build(),
        )
    }

    /** The first time a phone wants to talk or play sound here: Allow, Not now, Never. */
    fun ask(context: Context, peerId: String, kind: AudioKind) {
        val manager = notifications(context)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ASK, "Requests from phones", NotificationManager.IMPORTANCE_HIGH))
        val name = nameOf(peerId)
        val talk = kind == AudioKind.Talk
        manager.notify(
            PeerActionReceiver.askTag(peerId, kind), NotificationIds.PHONE_ASK,
            Notification.Builder(context, CHANNEL_ASK)
                .setSmallIcon(R.drawable.ic_logo_glyph)
                .setContentTitle(if (talk) "Allow $name to talk to this phone?" else "Allow $name to play its sound here?")
                .setContentText(
                    if (talk) "When allowed, $name's voice plays here while they hold Talk. You can turn it off on the page for $name."
                    else "When allowed, music and videos on $name play on this phone. You can turn it off on the page for $name.",
                )
                .setStyle(Notification.BigTextStyle())
                .setContentIntent(open(context))
                .setAutoCancel(true)
                .addAction(Notification.Action.Builder(null, "Allow", PeerActionReceiver.answer(context, peerId, kind, PeerActionReceiver.ALLOW)).build())
                .addAction(Notification.Action.Builder(null, "Not now", PeerActionReceiver.answer(context, peerId, kind, PeerActionReceiver.NOT_NOW)).build())
                .addAction(Notification.Action.Builder(null, "Never", PeerActionReceiver.answer(context, peerId, kind, PeerActionReceiver.NEVER)).build())
                .build(),
        )
    }

    /** Ends what plays from phones that aren't connected any more. The core also sends the stops itself. */
    @Synchronized
    fun keepOnly(context: Context, connected: Set<String>) {
        _talking.value?.takeIf { it !in connected }?.let { handle(context, it, Envelope(MessageType.TALK_STOP)) }
        _sound.value?.takeIf { it !in connected }?.let { handle(context, it, Envelope(MessageType.AUDIO_STOPPED)) }
    }
}

/** This phone's push-to-talk: hold Talk on another phone's page, and the microphone goes there. */
object Talk {
    const val RATE = 16_000
    /** 40 ms, mono. */
    val CHUNK_BYTES = PeerAudio.chunkBytes(RATE, 1)

    private val _to = MutableStateFlow<String?>(null)
    /** The phone this one talks to now. */
    val to: StateFlow<String?> = _to.asStateFlow()
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    fun canRecord(context: Context) = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun start(context: Context, peerId: String) {
        if (!canRecord(context)) return
        _problem.value = null
        _to.value = peerId
        TalkService.wantStop = false
        TalkService.start(context, peerId)
    }

    /** Released. A service still starting stops itself once it's in the foreground, as Android requires. */
    fun stop(context: Context) {
        TalkService.wantStop = true
        if (TalkService.started) TalkService.stop(context)
        _to.value = null
    }

    internal fun ended(context: Context, peerId: String, error: String?) {
        if (_to.value != peerId) return
        error?.let { _problem.value = it }
        stop(context)
    }

    internal fun stopped() {
        _to.value = null
    }

    internal fun setProblem(text: String) { _problem.value = text }
}

/**
 * Records the microphone while Talk is held and sends it to one phone as `talk.frame`. A foreground service of type
 * microphone, as Android 14 requires; it only starts from a press on the Talk button.
 */
class TalkService : Service() {
    @Volatile private var running = false
    private var thread: Thread? = null
    private var peer: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val target = intent?.getStringExtra(EXTRA_PEER)
        if (intent?.action == ACTION_STOP || target == null || !Talk.canRecord(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Walkie-talkie", NotificationManager.IMPORTANCE_LOW))
        val name = PhoneLinkService.connectionManager.value?.peers?.value?.firstOrNull { it.id == target }?.name ?: "the other phone"
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_logo_glyph)
            .setContentTitle("Talking to $name")
            .setContentText("The microphone is on while you hold Talk.")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        runCatching { ServiceCompat.startForeground(this, NotificationIds.TALK, notification, type) }.onFailure {
            Talk.setProblem("Android didn't allow the microphone right now")
            stopSelf()
            return START_NOT_STICKY
        }
        started = true
        if (wantStop) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running && peer == target) return START_NOT_STICKY
        stopRecording()
        peer = target
        running = true
        thread = Thread({ record(target) }, "dysk-talk").also { it.start() }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun record(target: String) {
        val connections = PhoneLinkService.connectionManager.value ?: return finish(target, "Not connected")
        val minimum = AudioRecord.getMinBufferSize(Talk.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, Talk.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, Talk.CHUNK_BYTES * 4))
                .also { it.startRecording() }
        }.getOrNull()
        if (recorder == null || recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            recorder?.release()
            return finish(target, "Can't use the microphone")
        }
        try {
            if (!connections.startTalk(target, Talk.RATE, 1)) return finish(target, "Not connected")
            var failures = 0
            while (running) {
                val chunk = ByteArray(Talk.CHUNK_BYTES)
                var filled = 0
                while (running && filled < chunk.size) {
                    val read = recorder.read(chunk, filled, chunk.size - filled)
                    if (read < 0) return finish(target, "The microphone stopped (error $read)")
                    filled += read
                }
                if (filled < chunk.size) break
                if (connections.sendTalk(target, chunk)) failures = 0 else if (++failures >= 25) return finish(target, "The connection was lost")
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            connections.stopTalk(target)
        }
    }

    private fun finish(target: String, why: String) {
        running = false
        Talk.setProblem(why)
        Handler(Looper.getMainLooper()).post { if (peer == target) stopSelf() }
    }

    private fun stopRecording() {
        running = false
        thread?.join(500)
        thread = null
    }

    override fun onDestroy() {
        stopRecording()
        started = false
        Talk.stopped()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "walkie"
        private const val EXTRA_PEER = "peer"
        private const val ACTION_STOP = "id.phonelink.android.STOP_TALK"

        /** In the foreground now, so stopping it is safe. */
        @Volatile var started = false
            private set
        /** Talk was released, maybe before the service got going. */
        @Volatile var wantStop = false

        fun start(context: Context, peerId: String) {
            ContextCompat.startForegroundService(context, Intent(context, TalkService::class.java).putExtra(EXTRA_PEER, peerId))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TalkService::class.java))
        }
    }
}
