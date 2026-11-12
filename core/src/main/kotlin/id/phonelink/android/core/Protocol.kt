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
    /** A phone that can share its screen with another phone, and take taps from it (see [PhoneRules]). Since 1.3. */
    const val CAP_P2P_SCREEN = "p2p-screen"
    /** A phone that can stream its camera to another phone and take photos for it. Since 1.3. */
    const val CAP_P2P_CAMERA = "p2p-camera"
    /** A phone that can forward its notifications and calls to another phone, and take dismiss and reply. Since 1.4. */
    const val CAP_P2P_NOTIFY = "p2p-notify"
    /** A phone that plays push-to-talk voice from another phone (`talk.*`). Since 1.4. */
    const val CAP_P2P_TALK = "p2p-talk"
    /** A phone that plays another phone's sound (`audio.*` between phones). Since 1.4. */
    const val CAP_P2P_SOUND = "p2p-sound"
    /** A phone that takes links from another phone and offers to open them (`link.open`). Since 1.5. */
    const val CAP_P2P_OPEN = "p2p-open"
    /** A phone whose music another phone can control (`media.remote`, `media.state`). Since 1.5. */
    const val CAP_P2P_MEDIA = "p2p-media"
    /** A phone that shares its screen time with another phone (`monitor.*`), whose battery it watches. Since 1.5. */
    const val CAP_P2P_MONITOR = "p2p-monitor"
    /** A phone another phone can silence (`quiet.*`). Since 1.5. */
    const val CAP_P2P_QUIET = "p2p-quiet"
    /** A phone that answers another phone with its location (`locate.*`), and flashes while it rings. Since 1.5. */
    const val CAP_P2P_LOCATE = "p2p-locate"
    /** A phone whose SMS another phone can read and send through (`psms.*`). Since 1.6. */
    const val CAP_P2P_SMS = "p2p-sms"
    /** A phone that takes a move from an old phone: contacts, photos, and files (`move.*`). Since 1.6. */
    const val CAP_P2P_MOVE = "p2p-move"
    /** A phone that keeps another phone's photo backup (`file.offer` with `kind: "photo"`). Since 1.6. */
    const val CAP_P2P_BACKUP = "p2p-backup"
    /**
     * A Windows or Linux computer that shows the phone's screen as JPEG frames and controls it (see [PcRules]). The
     * phone lists it too, meaning it serves that to PCs. Macs never list the `pc-*` caps and keep H.264.
     */
    const val CAP_PC_SCREEN = "pc-screen"
    /** A Windows or Linux computer that shows the phone's camera as JPEG frames. */
    const val CAP_PC_CAMERA = "pc-camera"
    /** A Windows or Linux computer that browses the phone's gallery (`gallery.*`). */
    const val CAP_PC_GALLERY = "pc-gallery"
    /** A Windows or Linux computer that reads and sends the phone's SMS (`sms.*`). */
    const val CAP_PC_SMS = "pc-sms"
    /** A Windows or Linux computer that reads the phone's contacts (`contacts.list`). */
    const val CAP_PC_CONTACTS = "pc-contacts"
    /** A Windows or Linux computer that browses the phone's files and downloads them (`fs.list`, `fs.get`). */
    const val CAP_PC_FILES = "pc-files"
    val PC_FEATURE_CAPS: List<String> = listOf(CAP_PC_SCREEN, CAP_PC_CAMERA, CAP_PC_GALLERY, CAP_PC_SMS, CAP_PC_CONTACTS, CAP_PC_FILES)
    /** A Windows or Linux computer that sends its own sound to a phone that asks (`audio.listen`). */
    const val CAP_PC_SOUND_OUT = "pc-sound-out"
    /** A Windows or Linux computer that lists commands a phone may run (`commands.list`, `command.*`). */
    const val CAP_PC_COMMANDS = "pc-commands"
    /** A Windows or Linux computer that opens a terminal for a phone (`terminal.*`). */
    const val CAP_PC_TERMINAL = "pc-terminal"
    /** A Windows or Linux computer that a Mac's mouse and keyboard can move into (`remote.*`). */
    const val CAP_PC_SHARED_INPUT = "pc-shared-input"
    /** A Windows or Linux computer that shows its own screen on a phone (`macscreen.*`, as JPEG frames). */
    const val CAP_PC_SCREEN_OUT = "pc-screen-out"
    /**
     * A Windows or Linux computer that uses the phone as an extra display (`macscreen.start` with `extend`). Not listed
     * by any build yet: Windows needs an indirect display driver and Linux a virtual output for that.
     */
    const val CAP_PC_DISPLAY = "pc-display"
    /** A Windows or Linux computer that runs the phone's Stream Deck: Mac shortcuts made PC ones, media, and typing. */
    const val CAP_PC_DECK = "pc-deck"
    /** A Windows or Linux computer with the notch tools: Todo, water, and Pomodoro (`notch.state`, `notch.action`). */
    const val CAP_PC_NOTCH = "pc-notch"
    /** A Windows or Linux computer that types what the phone scans or hears (barcodes and voice typing, `input`). */
    const val CAP_PC_TYPE = "pc-type"
    /** A Windows or Linux computer that keeps document scans (`file.offer` with `kind: "scan"`) in its own folder. */
    const val CAP_PC_SCAN = "pc-scan"
    /**
     * What a PC serves, as opposed to what it shows from the phone. Only the desktop app lists these; the phone shows
     * Hear the PC, Commands, Terminal, Stream Deck, the notch tools, and scanning and voice typing to the PC for a PC
     * that lists the matching one.
     */
    val PC_SERVICE_CAPS: List<String> = listOf(
        CAP_PC_SOUND_OUT, CAP_PC_COMMANDS, CAP_PC_TERMINAL, CAP_PC_SHARED_INPUT, CAP_PC_SCREEN_OUT,
        CAP_PC_DECK, CAP_PC_NOTCH, CAP_PC_TYPE, CAP_PC_SCAN,
    )
    /** What DyskLink for Windows and Linux advertises: [CAPS], the phone features it shows, and what it serves. */
    val PC_CAPS: List<String> = CAPS + PC_FEATURE_CAPS + PC_SERVICE_CAPS
    /** What the Android app advertises: [CAPS], the phone-to-phone features, and the PC features it serves. */
    val PHONE_CAPS: List<String> = CAPS + listOf(
        CAP_P2P_SCREEN, CAP_P2P_CAMERA, CAP_P2P_NOTIFY, CAP_P2P_TALK, CAP_P2P_SOUND,
        CAP_P2P_OPEN, CAP_P2P_MEDIA, CAP_P2P_MONITOR, CAP_P2P_QUIET, CAP_P2P_LOCATE,
        CAP_P2P_SMS, CAP_P2P_MOVE, CAP_P2P_BACKUP,
    ) + PC_FEATURE_CAPS
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
    /** From a computer: it takes another computer's mouse and keyboard now (`remote.*`). */
    val remoteControl: Boolean? = null,
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
    /** Between phones, on `file.offer` of a move: the folder under the receiver's DyskLink folder, `/`-separated. */
    val folder: String? = null,
    /** On `file.result`: the receiver already had this file (same name, size, and SHA-256), so nothing was sent. */
    val skipped: Boolean? = null,
    /** On a refused `file.result` between phones: `low-space`, `ask`, or `off` (see [PhoneKinds]). */
    val reason: String? = null,
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
    /** Between phones: the notification has a reply action, so `notification.reply` can answer it. */
    val reply: Boolean? = null,
    /** Between phones: the notification has a "mark as read" action, for `notification.read`. */
    val markRead: Boolean? = null,
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

/**
 * The phone camera's state, on `camera.started` between phones: zoom from 1 to [maxZoom], whether the lens has a
 * flash and the torch is on, whether a video is being recorded, and whether full-size photos can be taken.
 */
@Serializable
data class CameraState(
    val zoom: Float? = null,
    val maxZoom: Float? = null,
    val flash: Boolean? = null,
    val torch: Boolean? = null,
    val recording: Boolean? = null,
    val stills: Boolean? = null,
)

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
    /** On `audio.frame` and `talk.frame`: 16-bit interleaved PCM in base64. On `audio.started` and `talk.start`: the rate and channels that follow. */
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
    /**
     * On `screen.started`, `screen.frame`, `camera.started`, and `camera.frame` to a PC that listed `pc-screen` or
     * `pc-camera`: `jpeg`, each frame a whole JPEG picture. Missing means H.264, as for a Mac.
     */
    val format: String? = null,
    /** Phone camera: clockwise degrees that turn the picture upright. */
    val rotation: Int? = null,
    /** On `screen.started` to another phone: whether its taps reach this screen (see [PhoneRules]). */
    val canControl: Boolean? = null,
    /** On `camera.zoom`: the zoom wanted, from 1 up to the camera's `maxZoom`. */
    val zoom: Float? = null,
    /** On `camera.started` to another phone: zoom, torch, recording, and whether photos can be taken. */
    val cameraState: CameraState? = null,
    val control: ControlEvent? = null,
    /** On `media.state` between phones: what plays there now. */
    val nowPlaying: NowPlaying? = null,
    /** On `quiet.set` (what to set) and `quiet.result` (what was set, and until when). */
    val quiet: QuietRequest? = null,
    /** On `locate.result`: where the phone is. */
    val location: LocationFix? = null,
    /** On `psms.list`: one thread's messages instead of the list of threads. */
    val smsThread: Long? = null,
    /** On `move.request` and `move.done`: what the move holds. */
    val move: MoveSummary? = null,
    val notch: NotchState? = null,
    val notchAction: NotchAction? = null,
    /** On `ring`: `start`, `stop`, or `stopped` (the ringing side says it ended). */
    val ring: String? = null,
    /** On `mac.action`: `lock`, `awake.start`, or `awake.stop`. */
    val macAction: String? = null,
    /** On the `terminal.*` messages. */
    val terminal: TerminalMessage? = null,
    /** On the `remote.*` messages: one mouse and keyboard across computers. */
    val remote: RemoteEvent? = null,
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
            MessageType.AUDIO_FRAME, MessageType.TALK_FRAME -> audio
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
/**
 * One input from the computer whose mouse and keyboard are in use, as the Mac sends it (see PROTOCOL.md, One mouse
 * and keyboard). Key codes are Mac virtual key codes; [flags] is the raw CGEventFlags value.
 */
@Serializable
data class RemoteEvent(
    /** `move`, `down`, `up`, `scroll`, `key`, `flags`, or on `remote.enter`/`remote.leave` the crossing itself. */
    val kind: String,
    val dx: Double? = null,
    val dy: Double? = null,
    /** 0 left, 1 right, 2 and up for other buttons. */
    val button: Int? = null,
    val keyCode: Int? = null,
    val keyDown: Boolean? = null,
    val flags: Long? = null,
    /** `left`, `right`, `top`, or `bottom`. */
    val edge: String? = null,
    /** How far along that edge, 0 at the top or left to 1. */
    val at: Double? = null,
)

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
    /** Phone to phone: answer a forwarded notification through its reply action (`key`, `text`). */
    const val NOTIFICATION_REPLY = "notification.reply"
    /** Phone to phone: press a forwarded notification's "mark as read" action (`key`). */
    const val NOTIFICATION_READ = "notification.read"
    /** Phone to phone: how `notification.reply` went (`key`, `error` when it failed). */
    const val NOTIFICATION_RESULT = "notification.result"
    /** Phone to phone: the sender stopped forwarding; drop every notification from it. */
    const val NOTIFICATION_CLEARED = "notification.cleared"
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
    /** Phone to phone push-to-talk: `talk.start` (`audioRate`, `audioChannels`), `talk.frame` (PCM), `talk.stop`. */
    const val TALK_START = "talk.start"
    const val TALK_FRAME = "talk.frame"
    const val TALK_STOP = "talk.stop"
    /** From the phone listening: it stopped playing the voice, or refused it (`error`). */
    const val TALK_STOPPED = "talk.stopped"
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
    /** Phone to phone: take a full-size photo now (`key` names it). Answered with `camera.shot`. */
    const val CAMERA_SHUTTER = "camera.shutter"
    /** Phone to phone: set the zoom (`zoom`). */
    const val CAMERA_ZOOM = "camera.zoom"
    /** Phone to phone: a photo or video was saved (`key`, `text` `photo`/`video`, `file.name`) or failed (`error`). */
    const val CAMERA_SHOT = "camera.shot"
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
    /** Phone to phone (1.5): offer a web, map, or phone link (`text`, `key`). Answered with `link.result`. */
    const val LINK_OPEN = "link.open"
    const val LINK_RESULT = "link.result"
    /** Phone to phone (1.5): a music button (`media`), or `state`. Answered with `media.state` (`nowPlaying` or `error`). */
    const val MEDIA_REMOTE = "media.remote"
    const val MEDIA_STATE = "media.state"
    /** Phone to phone (1.5): today's screen time. Answered with `monitor.report` (`usage` or `error`). */
    const val MONITOR_REQUEST = "monitor.request"
    const val MONITOR_REPORT = "monitor.report"
    /** Phone to phone (1.5): ringer mode or Do Not Disturb (`quiet`). Answered with `quiet.result`. */
    const val QUIET_SET = "quiet.set"
    const val QUIET_RESULT = "quiet.result"
    /** Phone to phone (1.5): where the phone is. Answered with `locate.result` (`location` or `error`). */
    const val LOCATE_REQUEST = "locate.request"
    const val LOCATE_RESULT = "locate.result"
    /** Phone to phone (1.6): the threads (`key`), or one thread (`smsThread`). Answered with `psms.listing`. */
    const val PSMS_LIST = "psms.list"
    const val PSMS_LISTING = "psms.listing"
    /** Phone to phone (1.6): send an SMS through the other phone (`smsSend`). Answered with `psms.result`. */
    const val PSMS_SEND = "psms.send"
    const val PSMS_RESULT = "psms.result"
    /** Phone to phone (1.6): send new SMS here while connected (`key`). Answered with `psms.watching`. */
    const val PSMS_WATCH = "psms.watch"
    const val PSMS_WATCHING = "psms.watching"
    const val PSMS_UNWATCH = "psms.unwatch"
    /** Phone to phone (1.6): new SMS on the phone with the SIM, to a phone that watches. */
    const val PSMS_NEW = "psms.new"
    /** Phone to phone (1.6): an old phone asks to move to this one (`key`, `move`). Answered with `move.answer`. */
    const val MOVE_REQUEST = "move.request"
    const val MOVE_ANSWER = "move.answer"
    /** Phone to phone (1.6): the move finished (`move` with the counts). */
    const val MOVE_DONE = "move.done"
    const val MAC_ACTION = "mac.action"
    const val REMOTE_ENTER = "remote.enter"
    const val REMOTE_INPUT = "remote.input"
    const val REMOTE_LEAVE = "remote.leave"
    const val REMOTE_EXIT = "remote.exit"
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
        MessageType.AUDIO_FRAME, MessageType.TALK_FRAME, MessageType.FILE_CHUNK,
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
            MessageType.AUDIO_FRAME, MessageType.TALK_FRAME -> envelope.copy(raw = null, audio = text)
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
