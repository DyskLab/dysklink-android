package id.phonelink.android.core

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * What one paired phone may do on this phone. [screen], [camera], [talk], and [sound]: null until the first request,
 * which asks; true after "Allow"; false after "Never" or the switch was turned off. [control] is only ever turned on
 * by hand, and so are [notify] and [calls], which send this phone's notifications and calls to that phone.
 */
@Serializable
data class PeerAccess(
    val screen: Boolean? = null,
    val control: Boolean = false,
    val camera: Boolean? = null,
    /** This phone's notifications go to that phone, except from the apps in [muted]. */
    val notify: Boolean = false,
    /** This phone's incoming calls are shown on that phone. */
    val calls: Boolean = false,
    /** Apps (packages) whose notifications are never sent to that phone. */
    val muted: List<String> = emptyList(),
    /** That phone's push-to-talk voice plays here. */
    val talk: Boolean? = null,
    /** That phone's sound plays here. */
    val sound: Boolean? = null,
    /** Links from that phone show here as a notification with Open (never opened by themselves). */
    val links: Boolean? = null,
    /** That phone controls the music here and sees what plays. */
    val media: Boolean? = null,
    /** That phone sees this phone's screen time today. Battery needs no switch: it goes in `info` already. */
    val usage: Boolean? = null,
    /** That phone sets the ringer mode and Do Not Disturb here. */
    val quiet: Boolean? = null,
    /** That phone gets this phone's location when it asks. */
    val locate: Boolean? = null,
    /** That phone reads this phone's SMS and sends SMS through its SIM. Guarded more than the rest (see [SmsShare]). */
    val sms: Boolean? = null,
    /** That phone (an old one) may move its contacts, photos, and files here. */
    val move: Boolean? = null,
    /** That phone's photo backup is kept here, in Pictures/DyskLink/<its name>. */
    val backup: Boolean? = null,
)

/** The per-phone switches, kept in `phone_access.json`. Nothing is allowed until the user allows it on this phone. */
class PhoneAccess(directory: File) {
    private val file = File(directory, "phone_access.json")
    private val _all = MutableStateFlow(load())
    val all: StateFlow<Map<String, PeerAccess>> = _all.asStateFlow()

    fun of(peerId: String): PeerAccess = _all.value[peerId] ?: PeerAccess()

    /** Returns the access before and after the change. */
    @Synchronized
    fun update(peerId: String, change: (PeerAccess) -> PeerAccess): Pair<PeerAccess, PeerAccess> {
        val before = of(peerId)
        val after = change(before)
        if (after != before) {
            _all.value = if (after == PeerAccess()) _all.value - peerId else _all.value + (peerId to after)
            save()
        }
        return before to after
    }

    /** Unpaired: whatever was allowed is gone, so pairing again starts from nothing. */
    @Synchronized
    fun forget(peerId: String) {
        if (peerId !in _all.value) return
        _all.value = _all.value - peerId
        save()
    }

    private fun load(): Map<String, PeerAccess> = runCatching {
        protocolJson.decodeFromString(serializer, file.readText())
    }.getOrDefault(emptyMap())

    private fun save() {
        runCatching { file.writeText(protocolJson.encodeToString(serializer, _all.value)) }
    }

    private companion object {
        val serializer = MapSerializer(String.serializer(), PeerAccess.serializer())
    }
}

/** What this phone is doing with another phone's screen or camera, as the one watching. */
enum class P2pKind { Screen, Camera }

/** Sound between phones: push-to-talk voice, or everything the sending phone plays. */
enum class AudioKind { Talk, Sound }

/**
 * The phone-to-phone allow-list beyond files, clipboard, and ringing: another phone's screen and camera.
 * Pure, so the rules can be tested without sockets. Mac-only messages never reach these.
 */
object PhoneRules {
    enum class Verdict {
        /** Hand it to the screen, camera, or touch code. */
        Deliver,
        /** Answer with `screen.stopped` or `camera.stopped`, since the user said no to that phone. */
        Refuse,
        /** Ignore it without an answer. */
        Drop,
        /** Never asked: refuse this one, and ask the user on this phone (Allow, Not now, Never). */
        Ask,
    }

    /** Sent by the phone that views or uses this one. Decided by this phone's switches for the sender. */
    val FROM_VIEWER: Set<String> = setOf(
        MessageType.SCREEN_REQUEST, MessageType.SCREEN_SYNC, MessageType.SCREEN_STOP, MessageType.CONTROL,
        MessageType.CAMERA_REQUEST, MessageType.CAMERA_SYNC, MessageType.CAMERA_STOP, MessageType.CAMERA_CONTROL,
        MessageType.CAMERA_ZOOM, MessageType.CAMERA_SHUTTER,
    )

    /** Sent back by a phone this one asked. Only taken while this phone is watching that kind on it. */
    val TO_VIEWER: Map<String, P2pKind> = mapOf(
        MessageType.SCREEN_STARTED to P2pKind.Screen,
        MessageType.SCREEN_FRAME to P2pKind.Screen,
        MessageType.SCREEN_STOPPED to P2pKind.Screen,
        MessageType.CAMERA_STARTED to P2pKind.Camera,
        MessageType.CAMERA_FRAME to P2pKind.Camera,
        MessageType.CAMERA_STOPPED to P2pKind.Camera,
        MessageType.CAMERA_SHOT to P2pKind.Camera,
    )

    /** Sent by a phone this one forwards its notifications to. Decided by this phone's [PeerAccess.notify] for it. */
    val TO_SOURCE: Set<String> = setOf(MessageType.NOTIFICATION_DISMISS, MessageType.NOTIFICATION_REPLY, MessageType.NOTIFICATION_READ)

    /** Sent by a phone that forwards its notifications and calls here. The sender's own switch decided they go. */
    val FROM_SOURCE: Set<String> = setOf(
        MessageType.NOTIFICATION_POSTED, MessageType.NOTIFICATION_REMOVED, MessageType.NOTIFICATION_CLEARED,
        MessageType.NOTIFICATION_RESULT, MessageType.CALL,
    )

    /** Sent by a phone that talks or plays its sound here. Decided by this phone's [PeerAccess.talk] and [PeerAccess.sound]. */
    val FROM_SPEAKER: Map<String, AudioKind> = mapOf(
        MessageType.TALK_START to AudioKind.Talk,
        MessageType.TALK_FRAME to AudioKind.Talk,
        MessageType.TALK_STOP to AudioKind.Talk,
        MessageType.AUDIO_STARTED to AudioKind.Sound,
        MessageType.AUDIO_FRAME to AudioKind.Sound,
        MessageType.AUDIO_STOPPED to AudioKind.Sound,
    )

    /** Sent back to the phone that talks or plays sound: stop sending. Only ever ends this phone's own stream. */
    val TO_SPEAKER: Map<String, AudioKind> = mapOf(
        MessageType.TALK_STOPPED to AudioKind.Talk,
        MessageType.AUDIO_STOP to AudioKind.Sound,
    )

    /** Requests from a phone that uses this one as a remote (1.5). Decided by this phone's switch of that kind for it. */
    val FROM_CONTROLLER: Map<String, PeerAsk> = mapOf(
        MessageType.LINK_OPEN to PeerAsk.Links,
        MessageType.MEDIA_REMOTE to PeerAsk.Media,
        MessageType.MONITOR_REQUEST to PeerAsk.Usage,
        MessageType.QUIET_SET to PeerAsk.Quiet,
        MessageType.LOCATE_REQUEST to PeerAsk.Locate,
        MessageType.MOVE_REQUEST to PeerAsk.Move,
    )

    /** The answers, back to the phone that asked. Only taken while this phone waits for one of that kind. */
    val TO_CONTROLLER: Map<String, PeerAsk> = mapOf(
        MessageType.LINK_RESULT to PeerAsk.Links,
        MessageType.MEDIA_STATE to PeerAsk.Media,
        MessageType.MONITOR_REPORT to PeerAsk.Usage,
        MessageType.QUIET_RESULT to PeerAsk.Quiet,
        MessageType.LOCATE_RESULT to PeerAsk.Locate,
        MessageType.MOVE_ANSWER to PeerAsk.Move,
    )

    /** The answer type for each kind of request. */
    val ANSWER: Map<PeerAsk, String> = TO_CONTROLLER.entries.associate { (type, kind) -> kind to type }

    /** The kinds asked with a request and its one answer, through [FROM_CONTROLLER]. */
    val REMOTE_KINDS: Set<PeerAsk> = FROM_CONTROLLER.values.toSet()

    /** Sent by a phone that reads this phone's SMS (1.6). Decided by [PeerAccess.sms] for it (see [SmsShare]). */
    val FROM_SMS_READER: Set<String> = setOf(MessageType.PSMS_LIST, MessageType.PSMS_SEND, MessageType.PSMS_WATCH, MessageType.PSMS_UNWATCH)

    /** The answers and new SMS, back to the reading phone. Only taken for what it asked (see [SmsRemote]). */
    val TO_SMS_READER: Set<String> = setOf(MessageType.PSMS_LISTING, MessageType.PSMS_RESULT, MessageType.PSMS_WATCHING, MessageType.PSMS_NEW)

    /** Sent by an old phone that moved here. Decided by [PeerAccess.move]. */
    val FROM_MOVER: Set<String> = setOf(MessageType.MOVE_DONE)

    val ALL: Set<String> = FROM_VIEWER + TO_VIEWER.keys + TO_SOURCE + FROM_SOURCE + FROM_SPEAKER.keys + TO_SPEAKER.keys +
        FROM_CONTROLLER.keys + TO_CONTROLLER.keys + FROM_SMS_READER + TO_SMS_READER + FROM_MOVER

    /**
     * An SMS request from a phone. Only [PeerAccess.sms] lets it through: never asked asks here and refuses, and
     * `psms.unwatch` only ever stops what that phone gets.
     */
    fun fromSmsReader(type: String, access: PeerAccess): Verdict = when {
        type !in FROM_SMS_READER -> Verdict.Drop
        type == MessageType.PSMS_UNWATCH -> Verdict.Deliver
        access.sms == true -> Verdict.Deliver
        access.sms == false -> Verdict.Refuse
        else -> Verdict.Ask
    }

    /** The answer type for an SMS request. */
    fun smsAnswer(type: String): String? = when (type) {
        MessageType.PSMS_LIST -> MessageType.PSMS_LISTING
        MessageType.PSMS_SEND -> MessageType.PSMS_RESULT
        MessageType.PSMS_WATCH -> MessageType.PSMS_WATCHING
        else -> null
    }

    /**
     * A file a phone offers. Plain files (no kind, or one this doesn't know) go to Downloads as before; photo backup
     * needs [PeerAccess.backup], and the files of a move need [PeerAccess.move].
     */
    fun fileFromPhone(kind: String?, access: PeerAccess): Verdict {
        val allowed = when {
            kind == PhoneKinds.PHOTO -> access.backup
            kind in PhoneKinds.MOVE -> access.move
            else -> return Verdict.Deliver
        }
        return when (allowed) {
            true -> Verdict.Deliver
            false -> Verdict.Refuse
            null -> Verdict.Ask
        }
    }

    /** This phone's switch for that kind: null never asked, true allowed, false refused. */
    fun allowed(access: PeerAccess, kind: PeerAsk): Boolean? = when (kind) {
        PeerAsk.Links -> access.links
        PeerAsk.Media -> access.media
        PeerAsk.Usage -> access.usage
        PeerAsk.Quiet -> access.quiet
        PeerAsk.Locate -> access.locate
        PeerAsk.Move -> access.move
        PeerAsk.Sms -> access.sms
        PeerAsk.Backup -> access.backup
    }

    /** [access] with the switch for [kind] set to [on]. */
    fun withAllowed(access: PeerAccess, kind: PeerAsk, on: Boolean?): PeerAccess = when (kind) {
        PeerAsk.Links -> access.copy(links = on)
        PeerAsk.Media -> access.copy(media = on)
        PeerAsk.Usage -> access.copy(usage = on)
        PeerAsk.Quiet -> access.copy(quiet = on)
        PeerAsk.Locate -> access.copy(locate = on)
        PeerAsk.Move -> access.copy(move = on)
        PeerAsk.Sms -> access.copy(sms = on)
        PeerAsk.Backup -> access.copy(backup = on)
    }

    /** A link, music button, screen time, quiet, or location request from a phone: only its own switch lets it act. */
    fun fromController(type: String, access: PeerAccess): Verdict {
        val kind = FROM_CONTROLLER[type] ?: return Verdict.Drop
        return when (allowed(access, kind)) {
            true -> Verdict.Deliver
            false -> Verdict.Refuse
            null -> Verdict.Ask
        }
    }

    /** The answer to a request this phone didn't carry out: why, naming this phone, with the request's `key`. */
    fun requestRefusal(kind: PeerAsk, localName: String, verdict: Verdict, key: String? = null): Envelope {
        val what = when (kind) {
            PeerAsk.Links -> "Links from this phone are"
            PeerAsk.Media -> "Controlling the music is"
            PeerAsk.Usage -> "Sharing screen time is"
            PeerAsk.Quiet -> "Silencing the phone is"
            PeerAsk.Locate -> "Sharing the location is"
            PeerAsk.Move -> "Moving to this phone is"
            PeerAsk.Sms -> "SMS from this phone is"
            PeerAsk.Backup -> "Photo backup to this phone is"
        }
        val why = if (verdict == Verdict.Ask) "Waiting for $localName to allow it. Tap Allow there, then try again."
        else "$what turned off on $localName."
        return Envelope(ANSWER[kind] ?: MessageType.PSMS_RESULT, key = key, error = why)
    }

    /** Why a request of [kind] wasn't carried out, as text. */
    fun refusalText(kind: PeerAsk, localName: String, verdict: Verdict): String = requestRefusal(kind, localName, verdict).error!!

    /**
     * A dismiss, reply, or "mark as read" from a phone, for a notification this phone forwarded to it.
     * [forwarded]: this phone sent that notification to that phone and it's still there.
     */
    fun toSource(type: String, access: PeerAccess, forwarded: Boolean): Verdict = when {
        type !in TO_SOURCE -> Verdict.Drop
        access.notify && forwarded -> Verdict.Deliver
        // A reply gets an answer, so the other phone doesn't wait for nothing.
        type == MessageType.NOTIFICATION_REPLY -> Verdict.Refuse
        else -> Verdict.Drop
    }

    /**
     * Voice or sound from a phone. [hearing]: what this phone plays from that phone now. [busyWith]: another phone
     * already plays that kind here, so this one is refused.
     */
    fun fromSpeaker(type: String, access: PeerAccess, hearing: Set<AudioKind>, busyWith: String? = null): Verdict {
        val kind = FROM_SPEAKER[type] ?: return Verdict.Drop
        val allowed = if (kind == AudioKind.Talk) access.talk else access.sound
        return when (type) {
            MessageType.TALK_START, MessageType.AUDIO_STARTED -> when {
                allowed == false -> Verdict.Refuse
                allowed == null -> Verdict.Ask
                busyWith != null -> Verdict.Refuse
                else -> Verdict.Deliver
            }
            MessageType.TALK_FRAME, MessageType.AUDIO_FRAME -> if (allowed == true && kind in hearing) Verdict.Deliver else Verdict.Drop
            // Stopping only ends that phone's own stream, and only one that plays.
            else -> if (kind in hearing) Verdict.Deliver else Verdict.Drop
        }
    }

    /** The answer to refused or ended voice or sound: `talk.stopped` or `audio.stop`, with why. */
    fun audioRefusal(kind: AudioKind, why: String?): Envelope =
        Envelope(if (kind == AudioKind.Talk) MessageType.TALK_STOPPED else MessageType.AUDIO_STOP, error = why)

    /** Why voice or sound was refused: turned off, still to be allowed, or another phone plays here now. */
    fun audioRefusalText(kind: AudioKind, localName: String, verdict: Verdict, busyName: String? = null): String = when {
        verdict == Verdict.Ask -> "Waiting for $localName to allow it. Tap Allow there, then try again."
        busyName != null -> if (kind == AudioKind.Talk) "$busyName is talking to $localName now." else "$localName is playing sound from $busyName now."
        kind == AudioKind.Talk -> "Talking to $localName is turned off there."
        else -> "Playing sound on $localName is turned off there."
    }

    /** The app package in a notification key (`user|package|id|tag|uid`), or null. */
    fun keyPackage(key: String): String? = key.split('|').getOrNull(1)?.takeIf { it.isNotBlank() }

    /**
     * A message from a paired phone that wants this phone's screen or camera.
     * [sharingScreenWithSender]: this phone's screen is being shared with that phone right now.
     * [controlReady]: the input mechanism (the Accessibility service) is running.
     */
    fun fromViewer(type: String, access: PeerAccess, sharingScreenWithSender: Boolean, controlReady: Boolean): Verdict = when (type) {
        // A request only shows a notification. The first one asks; "Never" or the switch off refuses.
        MessageType.SCREEN_REQUEST -> if (access.screen == false) Verdict.Refuse else Verdict.Deliver
        MessageType.CAMERA_REQUEST -> if (access.camera == false) Verdict.Refuse else Verdict.Deliver
        // Stopping only ever ends the sender's own session.
        MessageType.SCREEN_STOP, MessageType.CAMERA_STOP -> Verdict.Deliver
        MessageType.SCREEN_SYNC -> if (access.screen == true) Verdict.Deliver else Verdict.Drop
        MessageType.CONTROL ->
            if (access.screen == true && access.control && sharingScreenWithSender && controlReady) Verdict.Deliver else Verdict.Drop
        MessageType.CAMERA_SYNC, MessageType.CAMERA_CONTROL, MessageType.CAMERA_ZOOM, MessageType.CAMERA_SHUTTER ->
            if (access.camera == true) Verdict.Deliver else Verdict.Drop
        else -> Verdict.Drop
    }

    /** Whether [type] from a phone may reach this phone's viewer, given what this phone asked that phone for. */
    fun toViewer(type: String, watching: Set<P2pKind>): Boolean = TO_VIEWER[type]?.let { it in watching } == true

    /** Whether taps from that phone reach this screen, as told to it on `screen.started`. */
    fun canControl(access: PeerAccess, controlReady: Boolean): Boolean = access.screen == true && access.control && controlReady

    /** The peer lists the capability: its buttons show. Phones from 1.2.0 and older list none of these. */
    fun supports(hello: Hello?, cap: String): Boolean = hello?.platform !in PhoneLinkProtocol.COMPUTERS && hello?.caps?.contains(cap) == true

    /** The answer to a refused request, naming this phone. */
    fun refusal(type: String, localName: String): Envelope = if (type == MessageType.SCREEN_REQUEST) {
        Envelope(MessageType.SCREEN_STOPPED, error = "Viewing the screen of $localName is turned off there.")
    } else {
        Envelope(MessageType.CAMERA_STOPPED, error = "Using the camera of $localName is turned off there.")
    }
}

/**
 * Photos another phone asked for with `camera.shutter`, answered in order with `camera.shot`. The phone with the
 * camera saves each photo in its own gallery, then sends it with the usual file messages.
 */
class ShotQueue(private val max: Int = MAX_PENDING) {
    private val pending = ArrayDeque<String>()

    /** Null when the photo will be taken; otherwise the `camera.shot` refusing it. */
    @Synchronized
    fun ask(key: String?): Envelope? {
        val id = key?.takeIf { it.isNotBlank() && it.length <= MAX_KEY } ?: return shot(key, error = "Bad request")
        if (pending.size >= max) return shot(id, error = "Still taking the last photo")
        pending.addLast(id)
        return null
    }

    /** The oldest photo was saved as [name]: its `camera.shot`, or null when nobody asked. */
    @Synchronized
    fun taken(name: String): Envelope? = pending.removeFirstOrNull()?.let { shot(it, name = name) }

    @Synchronized
    fun failed(error: String): Envelope? = pending.removeFirstOrNull()?.let { shot(it, error = error) }

    /** Every photo still waiting fails, for a camera that stopped or switched lens. */
    @Synchronized
    fun clear(error: String): List<Envelope> = pending.map { shot(it, error = error) }.also { pending.clear() }

    @get:Synchronized
    val size: Int get() = pending.size

    companion object {
        const val MAX_PENDING = 3
        private const val MAX_KEY = 64

        fun shot(key: String?, name: String? = null, error: String? = null, kind: String = "photo") = Envelope(
            MessageType.CAMERA_SHOT, key = key, text = kind, error = error,
            file = name?.let { FileMessage(id = key.orEmpty(), name = it) },
        )

        /** `DyskLink_20261112_093015.jpg`, in this phone's time zone. */
        fun fileName(time: Long, extension: String = "jpg"): String {
            val format = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            return "DyskLink_${format.format(java.util.Date(time))}.$extension"
        }
    }
}
