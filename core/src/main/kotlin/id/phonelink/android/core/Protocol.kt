package id.phonelink.android.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object PhoneLinkProtocol {
    /** 3: pairing by CPace on the typed code. 2: the code is random and typed on the device that asked to pair. */
    const val VERSION = 3
    const val SERVICE_TYPE = "_phonelink._tcp."
    /** Platforms that act as a computer: phones serve them their data. Anything else is another phone. */
    val COMPUTERS = setOf("macos", "windows", "linux")
    const val PORT = 47821
    /** The Mac runs `adb reverse` so this port on 127.0.0.1 leads to it over a USB cable. */
    const val USB_PORT = 47823
    const val USB_HOST = "127.0.0.1"
    /** Phones listen here for other phones. Not the Mac port, so scanning for Macs never finds a phone. */
    const val PHONE_PORT = 47822
    const val MAX_FRAME_BYTES = 1024 * 1024
    /**
     * Capability for binary frames (see [BinaryFrames]). Sent in `hello.caps`; binary frames go to a peer only when
     * both hellos list it. Apps before 1.2 don't send `caps` and ignore it, so they keep getting JSON.
     */
    const val CAP_BINARY = "bin1"
    /** What this build advertises in `hello.caps`. */
    val CAPS: List<String> = listOf(CAP_BINARY)
    const val PAIRING_TIMEOUT_MS = 60_000L
    const val MAX_CLIPBOARD_CHARS = 256 * 1024
}

@Serializable
data class Hello(
    val name: String,
    val platform: String,
    val model: String,
    val protocolVersion: Int,
    val paired: Boolean,
    /** The app version, such as "0.2.0". Missing from apps older than 0.2.0. */
    val appVersion: String? = null,
    /** Optional protocol features this side understands, such as [PhoneLinkProtocol.CAP_BINARY]. Missing before 1.2. */
    val caps: List<String>? = null,
)

@Serializable
data class DeviceInfo(
    val batteryPercent: Int? = null,
    val isCharging: Boolean? = null,
    val network: String? = null,
    val storageFreeBytes: Long? = null,
    val storageTotalBytes: Long? = null,
    val osVersion: String? = null,
    val model: String? = null,
    val carrier: String? = null,
    val cellType: String? = null,
    val signalLevel: Int? = null,
    val wifiLevel: Int? = null,
    /** From the Mac: whether Accessibility is on, so input from the phone can run. */
    val inputTrusted: Boolean? = null,
    /** From the Mac: Keep Awake is `active`, `idle`, or `unavailable` while the tool is off. */
    val keepAwake: String? = null,
    /** From the Mac: its charge control, when the helper is installed. */
    val charge: MacCharge? = null,
    /** From the Mac: hardware addresses and the network's broadcast address, for Wake-on-LAN. */
    val wakeMacs: List<String>? = null,
    val wakeBroadcast: String? = null,
    /** From the Mac: whether "Wake for network access" is on. */
    val wakeEnabled: Boolean? = null,
)

@Serializable
data class FileMessage(
    val id: String,
    val name: String? = null,
    val size: Long? = null,
    val data: String? = null,
    val sha256: String? = null,
    val ok: Boolean? = null,
    val error: String? = null,
    /** "photo" for photo sync, which the Mac saves into Pictures instead of Downloads. */
    val kind: String? = null,
)

@Serializable
data class InputEvent(
    val kind: String,
    val dx: Float? = null,
    val dy: Float? = null,
    val button: String? = null,
    val text: String? = null,
    val key: String? = null,
)

@Serializable
data class CommandItem(val id: String, val name: String)

@Serializable
data class CommandResult(val id: String, val exitCode: Int, val output: String)

@Serializable
data class NotificationItem(
    val key: String,
    val app: String,
    val title: String? = null,
    val text: String? = null,
    val time: Long,
    /** True for old or updated notifications, which needn't pop up again on the Mac. */
    val silent: Boolean? = null,
    /** The app's package, such as com.whatsapp. */
    val pkg: String? = null,
    /** The app icon as a small base64 PNG. */
    val icon: String? = null,
    /** The sender's photo or the app's large icon, as a base64 PNG. */
    val avatar: String? = null,
    /** A picture in the notification, such as a photo in a chat, as a small base64 JPEG. */
    val picture: String? = null,
    /** The app's accent color, as 0xRRGGBB. */
    val color: Int? = null,
)

@Serializable
data class SmsItem(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val time: Long,
    val outgoing: Boolean,
)

@Serializable
data class SmsSend(val id: String, val address: String, val body: String)

@Serializable
data class SmsResult(val id: String, val ok: Boolean, val error: String? = null)

@Serializable
data class CallEvent(val state: String, val number: String? = null, val time: Long)

/** A photo or video in the phone's gallery. [thumb] is a small JPEG in base64. */
@Serializable
data class GalleryItem(
    val id: Long,
    val name: String,
    val date: Long,
    val size: Long,
    val video: Boolean = false,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val thumb: String? = null,
)

/** One of the Mac's displays the phone can show. */
@Serializable
data class DisplayChoice(val id: Long, val name: String, val main: Boolean = false)

/** The Mac's charge control: the limit when it's on, what it's doing now, and whether Top Up runs. */
@Serializable
data class MacCharge(val limit: Int? = null, val status: String, val topUp: Boolean = false, val limiting: Boolean = false)

/** One app in today's screen time. [icon] is a small base64 PNG. */
@Serializable
data class UsageApp(val pkg: String, val name: String, val ms: Long, val icon: String? = null)

/** Screen time since midnight: the total, how often the phone was unlocked, and the most used apps. */
@Serializable
data class UsageReport(val since: Long, val totalMs: Long, val apps: List<UsageApp>, val unlocks: Int? = null)

@Serializable
data class ContactItem(val id: String, val name: String, val phones: List<String>)

@Serializable
data class FsEntry(val name: String, val isDirectory: Boolean, val size: Long, val modified: Long)

@Serializable
data class ScreenInfo(val width: Int, val height: Int)

/** The `x` and `y` coordinates range from 0 to 1, relative to the phone screen. */
@Serializable
data class ControlEvent(
    val kind: String,
    val x: Float? = null,
    val y: Float? = null,
    val text: String? = null,
    val key: String? = null,
)

/** The Mac notch's Todo list, water, and Pomodoro. The Mac holds the data, and the phone asks for changes. */
@Serializable
data class NotchState(
    val todos: List<NotchTask> = emptyList(),
    val water: NotchWater = NotchWater(),
    val focus: NotchFocus = NotchFocus(),
    /** The quick note shared with the notch, and when it last changed in milliseconds since 1970. */
    val note: String? = null,
    val noteTime: Long? = null,
    /** What plays on the Mac. */
    val music: NotchMusic? = null,
)

@Serializable
data class NotchMusic(val title: String = "", val artist: String = "", val playing: Boolean = false)

@Serializable
data class NotchTask(val id: String, val title: String, val done: Boolean)

@Serializable
data class NotchWater(val totalMl: Int = 0, val goalMl: Int = 2000, val glassMl: Int = 250)

@Serializable
data class NotchFocus(
    /** `focus`, `shortBreak`, or `longBreak`. */
    val phase: String = "focus",
    val session: Int = 1,
    val sessions: Int = 4,
    val running: Boolean = false,
    /** Time left when the Mac sent it. The phone counts down from when it arrives. */
    val remainingMs: Long = 0,
    val lengthMs: Long = 0,
)

/**
 * `kind`: `todo.add` (`text`), `todo.toggle`, `todo.delete`, `todo.rename` (`id`, `text`), `water.drink`,
 * `water.undo`, `focus.toggle`, and `focus.reset`.
 */
@Serializable
data class NotchAction(val kind: String, val id: String? = null, val text: String? = null)

@Serializable
data class Envelope(
    val type: String,
    val hello: Hello? = null,
    val info: DeviceInfo? = null,
    val text: String? = null,
    val file: FileMessage? = null,
    val media: String? = null,
    val input: InputEvent? = null,
    val commands: List<CommandItem>? = null,
    val commandId: String? = null,
    val commandResult: CommandResult? = null,
    val features: List<String>? = null,
    val notification: NotificationItem? = null,
    val key: String? = null,
    val sms: List<SmsItem>? = null,
    val smsSend: SmsSend? = null,
    val smsResult: SmsResult? = null,
    val call: CallEvent? = null,
    val contacts: List<ContactItem>? = null,
    /** On `usage`: today's screen time. */
    val usage: UsageReport? = null,
    /** On `audio.frame`: 16-bit interleaved PCM in base64. On `audio.started`: the rate and channels that follow. */
    val audio: String? = null,
    val audioRate: Int? = null,
    val audioChannels: Int? = null,
    /** On `gallery`: a page of photos and videos, newest first. */
    val gallery: List<GalleryItem>? = null,
    /** On `gallery.list`: how many to skip. On `gallery`: where this reply started. */
    val offset: Int? = null,
    /** On `gallery`: no more photos after this reply. */
    val galleryEnd: Boolean? = null,
    /** On `gallery.get`: which photo or video to send. */
    val galleryId: Long? = null,
    val path: String? = null,
    /** On `fs.list`: also include files whose names start with a dot. */
    val hidden: Boolean? = null,
    val entries: List<FsEntry>? = null,
    val error: String? = null,
    /** True if this list has another page. */
    val more: Boolean? = null,
    val screen: ScreenInfo? = null,
    /** On `macscreen.started`: the Mac's displays. On `macscreen.start`: which one to show. */
    val displays: List<DisplayChoice>? = null,
    val displayId: Long? = null,
    val video: String? = null,
    val keyFrame: Boolean? = null,
    /** Phone camera: clockwise degrees that turn the picture upright. */
    val rotation: Int? = null,
    val control: ControlEvent? = null,
    val notch: NotchState? = null,
    val notchAction: NotchAction? = null,
    /** On `ring`: `start`, `stop`, or `stopped` (the ringing side says it ended). */
    val ring: String? = null,
    /** On `mac.action`: `lock`, `awake.start`, or `awake.stop`. */
    val macAction: String? = null,
    /** On the `terminal.*` messages. */
    val terminal: TerminalMessage? = null,
    /** On `pair.request`, `pair.pake`, and `pair.accept` (pairing version 3). */
    val pake: PakeMessage? = null,
    /**
     * Not part of the JSON. The raw media of `screen.frame`, `camera.frame`, `macscreen.frame` (H.264), `audio.frame`
     * (PCM), or `file.chunk` (file bytes): set by the reader for a binary frame, and by senders, whose writer sends it
     * as a binary frame to a `bin1` peer and as the usual base64 field to anyone else. Read it with [payload].
     */
    @kotlinx.serialization.Transient val raw: ByteArray? = null,
) {
    /**
     * The media bytes of a frame message: [raw], or the base64 field decoded (`video`, `audio`, or `file.data`).
     * Null when there is none or the base64 is invalid.
     */
    fun payload(): ByteArray? {
        raw?.let { return it }
        val text = when (type) {
            MessageType.FILE_CHUNK -> file?.data
            MessageType.AUDIO_FRAME -> audio
            else -> video
        } ?: return null
        return runCatching { java.util.Base64.getDecoder().decode(text) }.getOrNull()
    }
}

/**
 * Pairing version 3 (CPace). `v` is the PAKE version, 1. Base64 fields: `nonce` (16 bytes, in `pair.request` and
 * `pair.pake`), `y` (a 32-byte X25519 point, in `pair.pake` and the typing side's `pair.accept`), and `confirm`
 * (32 bytes, in both `pair.accept`s).
 */
@Serializable
data class PakeMessage(
    val v: Int,
    val nonce: String? = null,
    val y: String? = null,
    val confirm: String? = null,
)

/** A terminal session on the Mac. `data` is base64 bytes: typed keys, or what the shell printed. */
@Serializable
data class TerminalMessage(
    val id: String,
    val data: String? = null,
    val cols: Int? = null,
    val rows: Int? = null,
    val error: String? = null,
    val exitCode: Int? = null,
)

object MessageType {
    const val HELLO = "hello"
    const val PAIR_REQUEST = "pair.request"
    const val PAIR_ACCEPT = "pair.accept"
    const val PAIR_REJECT = "pair.reject"
    /** The showing side's CPace share, sent once the code shows. */
    const val PAIR_PAKE = "pair.pake"
    const val UNPAIR = "unpair"
    const val INFO = "info"
    const val PING = "ping"
    const val PONG = "pong"
    const val CLIPBOARD = "clipboard"
    const val CLIPBOARD_ACK = "clipboard.ack"
    const val MEDIA = "media"
    const val INPUT = "input"
    const val COMMANDS_LIST = "commands.list"
    const val COMMAND_RUN = "command.run"
    const val COMMAND_RESULT = "command.result"
    const val FILE_OFFER = "file.offer"
    const val FILE_CHUNK = "file.chunk"
    const val FILE_DONE = "file.done"
    const val FILE_CANCEL = "file.cancel"
    const val FILE_RESULT = "file.result"
    const val FEATURES = "features"
    const val NOTIFICATION_POSTED = "notification.posted"
    const val NOTIFICATION_REMOVED = "notification.removed"
    const val NOTIFICATION_DISMISS = "notification.dismiss"
    const val SMS_LIST = "sms.list"
    const val SMS_MESSAGES = "sms.messages"
    const val SMS_NEW = "sms.new"
    const val SMS_SEND = "sms.send"
    const val SMS_RESULT = "sms.result"
    const val CALL = "call"
    const val CONTACTS_LIST = "contacts.list"
    const val CONTACTS = "contacts"
    const val USAGE_LIST = "usage.list"
    const val USAGE = "usage"
    const val AUDIO_LISTEN = "audio.listen"
    const val AUDIO_STARTED = "audio.started"
    const val AUDIO_FRAME = "audio.frame"
    const val AUDIO_STOP = "audio.stop"
    const val AUDIO_STOPPED = "audio.stopped"
    const val GALLERY_LIST = "gallery.list"
    const val GALLERY = "gallery"
    const val GALLERY_GET = "gallery.get"
    const val FS_LIST = "fs.list"
    const val FS_LISTING = "fs.listing"
    const val FS_GET = "fs.get"
    const val SCREEN_REQUEST = "screen.request"
    const val SCREEN_STARTED = "screen.started"
    const val SCREEN_FRAME = "screen.frame"
    const val SCREEN_SYNC = "screen.sync"
    const val SCREEN_STOP = "screen.stop"
    const val SCREEN_STOPPED = "screen.stopped"
    /** The phone camera on a Mac. The Mac asks, the user starts it on the phone, the phone sends video. */
    const val CAMERA_REQUEST = "camera.request"
    const val CAMERA_STARTED = "camera.started"
    const val CAMERA_FRAME = "camera.frame"
    const val CAMERA_SYNC = "camera.sync"
    const val CAMERA_STOP = "camera.stop"
    const val CAMERA_STOPPED = "camera.stopped"
    const val CAMERA_CONTROL = "camera.control"
    /** The Mac's screen on this phone: the phone asks, the Mac sends video and takes taps. */
    const val MACSCREEN_START = "macscreen.start"
    const val MACSCREEN_STARTED = "macscreen.started"
    const val MACSCREEN_FRAME = "macscreen.frame"
    const val MACSCREEN_SYNC = "macscreen.sync"
    const val MACSCREEN_STOP = "macscreen.stop"
    const val MACSCREEN_STOPPED = "macscreen.stopped"
    const val MACSCREEN_POINTER = "macscreen.pointer"
    const val CONTROL = "control"
    const val NOTCH_STATE = "notch.state"
    const val NOTCH_ACTION = "notch.action"
    const val RING = "ring"
    const val MAC_ACTION = "mac.action"
    const val TERMINAL_OPEN = "terminal.open"
    const val TERMINAL_OPENED = "terminal.opened"
    const val TERMINAL_INPUT = "terminal.input"
    const val TERMINAL_OUTPUT = "terminal.output"
    const val TERMINAL_RESIZE = "terminal.resize"
    const val TERMINAL_CLOSE = "terminal.close"
    const val TERMINAL_CLOSED = "terminal.closed"
}

val protocolJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

object Frames {
    /**
     * Returns false, without sending, for a message bigger than [PhoneLinkProtocol.MAX_FRAME_BYTES]: the other side
     * would drop the whole connection over it.
     */
    fun write(output: DataOutputStream, envelope: Envelope): Boolean {
        val payload = FrameWriter.encode(envelope) ?: return false
        synchronized(output) {
            output.writeInt(payload.size)
            output.write(payload)
            output.flush()
        }
        return true
    }

    /** Returns null for a corrupt message. The connection stays usable. Reads binary frames too. */
    fun read(input: DataInputStream): Envelope? = FrameReader(input).read()

    /**
     * Null for a corrupt message. Through a String on purpose: measured with kotlinx.serialization 1.11,
     * `decodeFromStream` was 2-3 times slower and allocated about twice as much for file chunks and video frames.
     */
    fun decode(payload: ByteArray, length: Int): Envelope? = runCatching {
        protocolJson.decodeFromString(Envelope.serializer(), payload.decodeToString(0, length))
    }.getOrNull()
}

/** Reads frames into one buffer that's reused, instead of a new array for every frame. */
class FrameReader(private val input: DataInputStream) {
    private var buffer = ByteArray(INITIAL_BYTES)

    /**
     * Returns null for a corrupt JSON message; the connection stays usable. Throws [IOException] for a frame that
     * breaks the framing (bad length, a binary header that doesn't fit or isn't JSON), which ends the connection.
     */
    fun read(): Envelope? {
        val word = input.readInt()
        if (word and BinaryFrames.FLAG != 0) return readBinary(word and BinaryFrames.FLAG.inv())
        val length = word
        if (length <= 0 || length > PhoneLinkProtocol.MAX_FRAME_BYTES) {
            throw IOException("Invalid frame size: $length")
        }
        ensure(length)
        input.readFully(buffer, 0, length)
        return Frames.decode(buffer, length)
    }

    /** `[UInt16 header length][JSON header][raw payload]`, [length] bytes in all. The payload gets its own array. */
    private fun readBinary(length: Int): Envelope {
        if (length < BinaryFrames.HEADER_LENGTH_BYTES || length > PhoneLinkProtocol.MAX_FRAME_BYTES) {
            throw IOException("Invalid binary frame size: $length")
        }
        val headerLength = input.readUnsignedShort()
        if (headerLength == 0 || headerLength > length - BinaryFrames.HEADER_LENGTH_BYTES) {
            throw IOException("Invalid binary header length: $headerLength of $length")
        }
        ensure(headerLength)
        input.readFully(buffer, 0, headerLength)
        val payload = ByteArray(length - BinaryFrames.HEADER_LENGTH_BYTES - headerLength)
        input.readFully(payload)
        val header = Frames.decode(buffer, headerLength) ?: throw IOException("Invalid binary header")
        return header.copy(raw = payload)
    }

    private fun ensure(length: Int) {
        if (length > buffer.size) {
            buffer = ByteArray(maxOf(length, minOf(buffer.size * 2, PhoneLinkProtocol.MAX_FRAME_BYTES)))
        }
    }

    private companion object {
        const val INITIAL_BYTES = 16 * 1024
    }
}

/**
 * Binary frames, used only toward a peer whose `hello.caps` has [PhoneLinkProtocol.CAP_BINARY] (and only when this
 * side's hello listed it too):
 * `[UInt32 BE: 0x80000000 | length][UInt16 BE: header length][header: UTF-8 JSON envelope][raw payload]`.
 * `length` counts everything after the first word and is at most [PhoneLinkProtocol.MAX_FRAME_BYTES]. The header is the
 * message without its base64 field (`video`, `audio`, or `file.data`); the payload is that field's raw bytes.
 */
object BinaryFrames {
    const val FLAG = 0x80000000.toInt()
    const val HEADER_LENGTH_BYTES = 2
    /** The messages that go binary. Everything else stays JSON. */
    val TYPES = setOf(
        MessageType.SCREEN_FRAME, MessageType.CAMERA_FRAME, MessageType.MACSCREEN_FRAME,
        MessageType.AUDIO_FRAME, MessageType.FILE_CHUNK,
    )

    /** True when [hello] lists binary frames. */
    fun supports(hello: Hello?): Boolean = hello?.caps?.contains(PhoneLinkProtocol.CAP_BINARY) == true

    /** `[UInt16 BE length][JSON]` of [envelope] without its payload, or null when the header is too long. */
    fun header(envelope: Envelope): ByteArray? {
        val bare = envelope.copy(raw = null, video = null, audio = null, file = envelope.file?.copy(data = null))
        return lengthPrefixed(protocolJson.encodeToString(Envelope.serializer(), bare).encodeToByteArray())
    }

    internal fun lengthPrefixed(json: ByteArray): ByteArray? {
        if (json.isEmpty() || json.size > 0xFFFF) return null
        val out = ByteArray(json.size + HEADER_LENGTH_BYTES)
        out[0] = (json.size ushr 8).toByte()
        out[1] = json.size.toByte()
        json.copyInto(out, HEADER_LENGTH_BYTES)
        return out
    }

    /** The JSON form of a message whose payload is in [Envelope.raw]: the payload as the usual base64 field. */
    fun asJson(envelope: Envelope): Envelope {
        val raw = envelope.raw ?: return envelope
        val text = java.util.Base64.getEncoder().encodeToString(raw)
        return when (envelope.type) {
            MessageType.FILE_CHUNK -> envelope.copy(raw = null, file = (envelope.file ?: FileMessage("")).copy(data = text))
            MessageType.AUDIO_FRAME -> envelope.copy(raw = null, audio = text)
            else -> envelope.copy(raw = null, video = text)
        }
    }

    /** The whole frame, length word included, as it goes on the wire. For tests and fixed vectors. */
    fun encode(envelope: Envelope): ByteArray? {
        val header = header(envelope) ?: return null
        val raw = envelope.raw ?: ByteArray(0)
        val length = header.size + raw.size
        if (length > PhoneLinkProtocol.MAX_FRAME_BYTES) return null
        return ByteBuffer.allocate(4 + length).putInt(FLAG or length).put(header).put(raw).array()
    }
}

object Pairing {
    /** Sent in `pair.reject` when the typed code was wrong, so the other side can try again. */
    const val WRONG_CODE = "wrong-code"
    /** Sent in `pair.reject` to a device that can only pair the old way (version 1 or 2). */
    const val UPDATE_REQUIRED = "update-required"
    const val MAX_ATTEMPTS = 5
    /** The first protocol version that pairs with CPace. Older devices must update before they can pair. */
    const val PAKE_SINCE = 3

    /** A random code, shown on the device that was asked to pair. */
    fun newCode(): String = "%06d".format(java.security.SecureRandom().nextInt(1_000_000))

    /** What the user sees when the other device has an older DyskLink. */
    fun updateMessage(name: String): String = "Update DyskLink on $name to pair."

    /** Version 1 code, from both IDs. Kept for the record; new pairings only use CPace ([Pake]). */
    fun code(idA: String, idB: String): String {
        val (first, second) = listOf(idA, idB).sorted()
        val input = "phonelink-pair-v1:$first:$second"
        val hash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        val value = ByteBuffer.wrap(hash, 0, 4).int.toLong() and 0xFFFFFFFFL
        return (value % 1_000_000L).toString().padStart(6, '0')
    }
}

fun sha256Hex(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
