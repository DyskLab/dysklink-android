package id.phonelink.android.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object PhoneLinkProtocol {
    const val VERSION = 1
    const val SERVICE_TYPE = "_phonelink._tcp."
    const val PORT = 47821
    const val MAX_FRAME_BYTES = 1024 * 1024
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
    val path: String? = null,
    /** On `fs.list`: also include files whose names start with a dot. */
    val hidden: Boolean? = null,
    val entries: List<FsEntry>? = null,
    val error: String? = null,
    /** True if this list has another page. */
    val more: Boolean? = null,
    val screen: ScreenInfo? = null,
    val video: String? = null,
    val keyFrame: Boolean? = null,
    val control: ControlEvent? = null,
)

object MessageType {
    const val HELLO = "hello"
    const val PAIR_REQUEST = "pair.request"
    const val PAIR_ACCEPT = "pair.accept"
    const val PAIR_REJECT = "pair.reject"
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
    const val FS_LIST = "fs.list"
    const val FS_LISTING = "fs.listing"
    const val FS_GET = "fs.get"
    const val SCREEN_REQUEST = "screen.request"
    const val SCREEN_STARTED = "screen.started"
    const val SCREEN_FRAME = "screen.frame"
    const val SCREEN_SYNC = "screen.sync"
    const val SCREEN_STOP = "screen.stop"
    const val SCREEN_STOPPED = "screen.stopped"
    const val CONTROL = "control"
}

val protocolJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

object Frames {
    fun write(output: DataOutputStream, envelope: Envelope) {
        val payload = protocolJson.encodeToString(Envelope.serializer(), envelope).toByteArray()
        synchronized(output) {
            output.writeInt(payload.size)
            output.write(payload)
            output.flush()
        }
    }

    /** Returns null for a corrupt message. The connection stays usable. */
    fun read(input: DataInputStream): Envelope? {
        val length = input.readInt()
        if (length <= 0 || length > PhoneLinkProtocol.MAX_FRAME_BYTES) {
            throw IOException("Invalid frame size: $length")
        }
        val payload = ByteArray(length)
        input.readFully(payload)
        return runCatching {
            protocolJson.decodeFromString(Envelope.serializer(), payload.decodeToString())
        }.getOrNull()
    }
}

object Pairing {
    /** See docs/PROTOCOL.md in dysklink-mac. The Mac uses the same formula. */
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
