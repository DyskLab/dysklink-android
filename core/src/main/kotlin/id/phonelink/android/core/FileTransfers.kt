package id.phonelink.android.core

import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

@Serializable
enum class TransferStatus { Active, Done, Failed }

@Serializable
data class Transfer(
    val id: String,
    val peerId: String,
    val name: String,
    val size: Long,
    val transferred: Long,
    val outgoing: Boolean,
    val status: TransferStatus,
    val detail: String? = null,
    /** Where a received file can be opened from, once it is saved. */
    val uri: String? = null,
    /** When the transfer started, in epoch milliseconds. 0 for history saved before this was kept. */
    val time: Long = 0,
)

/** Destination for an incoming file. It only appears at its final location after [commit]. */
interface FileSink {
    fun write(bytes: ByteArray)
    /** Returns a description of the file's location. */
    fun commit(): String
    fun abort()
    /** A content URI for the saved file, available after [commit]. */
    val openUri: String? get() = null
}

fun interface FileSinkFactory {
    fun open(name: String, size: Long): FileSink
}

object FileNames {
    private const val MAX_LENGTH = 200

    /** File names come from another device, so they must not be able to point at another folder. */
    fun sanitize(raw: String?): String {
        val base = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        val clean = base.filterNot { it.isISOControl() || it in "<>:\"|?*" }.trim().trimStart('.')
        return clean.take(MAX_LENGTH).ifEmpty { "file" }
    }
}

class FileTransfers(
    private val scope: CoroutineScope,
    private val sinks: FileSinkFactory,
    /** Finished transfers are kept here, so received files can still be opened after the app is closed. */
    private val historyFile: File? = null,
    /** Returns false if the message can't be sent. */
    private val send: (peerId: String, envelope: Envelope) -> Boolean,
) {
    private class Incoming(val peerId: String, val size: Long, val sink: FileSink) {
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        var received = 0L
    }

    private val incoming = ConcurrentHashMap<String, Incoming>()
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    /** A large selection from the Mac waits its turn instead of all sending at once. */
    private val uploads = Semaphore(MAX_UPLOADS)

    private val _transfers = MutableStateFlow(loadHistory())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

    private fun loadHistory(): List<Transfer> {
        val file = historyFile ?: return emptyList()
        return runCatching { protocolJson.decodeFromString(ListSerializer(Transfer.serializer()), file.readText()) }
            .getOrNull().orEmpty().filter { it.status != TransferStatus.Active }.take(MAX_HISTORY)
    }

    private fun saveHistory() {
        val file = historyFile ?: return
        val finished = _transfers.value.filter { it.status != TransferStatus.Active }
        runCatching { file.writeText(protocolJson.encodeToString(ListSerializer(Transfer.serializer()), finished)) }
    }

    /** Removes the finished transfers from the list. The files themselves stay where they are. */
    fun clearHistory() {
        _transfers.update { list -> list.filter { it.status == TransferStatus.Active } }
        saveHistory()
    }

    private val finishedCallbacks = java.util.concurrent.ConcurrentHashMap<String, (Boolean) -> Unit>()

    /** [onFinished] is told once whether the receiver saved the file. */
    fun sendFile(peerId: String, name: String, size: Long, kind: String? = null, onFinished: ((Boolean) -> Unit)? = null, open: () -> InputStream) {
        val id = UUID.randomUUID().toString()
        val clean = FileNames.sanitize(name)
        if (onFinished != null) finishedCallbacks[id] = onFinished
        add(Transfer(id, peerId, clean, size, 0, outgoing = true, status = TransferStatus.Active))
        scope.launch(Dispatchers.IO) {
            val failure = runCatching { uploads.withPermit { upload(id, peerId, clean, size, kind, open) } }
                .fold(onSuccess = { it }, onFailure = { it.message ?: "Failed to read the file" })
            if (failure != null) {
                send(peerId, Envelope(MessageType.FILE_CANCEL, file = FileMessage(id)))
                finish(id, TransferStatus.Failed, failure)
            }
        }
    }

    /** Returns a failure message, or null if all data was sent. */
    private fun upload(id: String, peerId: String, name: String, size: Long, kind: String?, open: () -> InputStream): String? {
        val offer = FileMessage(id, name = name, size = size, kind = kind)
        if (!send(peerId, Envelope(MessageType.FILE_OFFER, file = offer))) return "Connection lost"
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        open().use { input ->
            val buffer = ByteArray(CHUNK_BYTES)
            while (scope.isActive) {
                if (id in cancelled) return "Canceled by the receiver"
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                total += count
                val data = Base64.getEncoder().encodeToString(buffer.copyOf(count))
                val chunk = Envelope(MessageType.FILE_CHUNK, file = FileMessage(id, data = data))
                if (!send(peerId, chunk)) return "Connection lost"
                progress(id, total)
            }
        }
        if (total != size) return "File size changed while sending"
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val done = Envelope(MessageType.FILE_DONE, file = FileMessage(id, sha256 = hash))
        return if (send(peerId, done)) null else "Connection lost"
    }

    /** Only called for paired devices. */
    fun handle(peerId: String, type: String, file: FileMessage?) {
        file ?: return
        when (type) {
            MessageType.FILE_OFFER -> onOffer(peerId, file)
            MessageType.FILE_CHUNK -> onChunk(peerId, file)
            MessageType.FILE_DONE -> onDone(peerId, file)
            MessageType.FILE_CANCEL -> onCancel(peerId, file)
            MessageType.FILE_RESULT -> onResult(file)
        }
    }

    fun onDisconnected(peerId: String) {
        incoming.entries.filter { it.value.peerId == peerId }.forEach { (id, item) ->
            incoming.remove(id)
            item.sink.abort()
            finish(id, TransferStatus.Failed, "Connection lost")
        }
        _transfers.value.filter { it.peerId == peerId && it.outgoing && it.status == TransferStatus.Active }
            .forEach { finish(it.id, TransferStatus.Failed, "Connection lost") }
    }

    private fun onOffer(peerId: String, file: FileMessage) {
        val size = file.size ?: return
        if (size < 0 || incoming.containsKey(file.id)) return
        val name = FileNames.sanitize(file.name)
        val sink = runCatching { sinks.open(name, size) }.getOrElse { error ->
            reply(peerId, file.id, false, error.message ?: "Can't save the file")
            return
        }
        incoming[file.id] = Incoming(peerId, size, sink)
        add(Transfer(file.id, peerId, name, size, 0, outgoing = false, status = TransferStatus.Active))
    }

    private fun onChunk(peerId: String, file: FileMessage) {
        val item = incoming[file.id]?.takeIf { it.peerId == peerId } ?: return
        val failure = runCatching {
            val bytes = Base64.getDecoder().decode(file.data.orEmpty())
            if (item.received + bytes.size > item.size) error("Data exceeds the file size")
            item.sink.write(bytes)
            item.digest.update(bytes)
            item.received += bytes.size
        }.exceptionOrNull()
        if (failure != null) return failIncoming(file.id, item, failure.message ?: "Failed to write the file")
        progress(file.id, item.received)
    }

    private fun onDone(peerId: String, file: FileMessage) {
        val item = incoming[file.id]?.takeIf { it.peerId == peerId } ?: return
        val hash = item.digest.digest().joinToString("") { "%02x".format(it) }
        if (item.received != item.size || hash != file.sha256) {
            return failIncoming(file.id, item, "File was corrupted in transit")
        }
        incoming.remove(file.id)
        val location = runCatching { item.sink.commit() }.getOrElse {
            item.sink.abort()
            reply(peerId, file.id, false, "Failed to save the file")
            return finish(file.id, TransferStatus.Failed, "Failed to save the file")
        }
        reply(peerId, file.id, true, null)
        finish(file.id, TransferStatus.Done, location, item.sink.openUri)
    }

    private fun onCancel(peerId: String, file: FileMessage) {
        cancelled.add(file.id)
        val item = incoming[file.id]?.takeIf { it.peerId == peerId } ?: return
        incoming.remove(file.id)
        item.sink.abort()
        finish(file.id, TransferStatus.Failed, "Canceled by the sender")
    }

    private fun onResult(file: FileMessage) {
        if (file.ok == true) {
            finish(file.id, TransferStatus.Done, "Sent")
        } else {
            cancelled.add(file.id)
            finish(file.id, TransferStatus.Failed, file.error ?: "Rejected by the receiver")
        }
    }

    private fun failIncoming(id: String, item: Incoming, message: String) {
        incoming.remove(id)
        item.sink.abort()
        reply(item.peerId, id, false, message)
        finish(id, TransferStatus.Failed, message)
    }

    private fun reply(peerId: String, id: String, ok: Boolean, error: String?) {
        send(peerId, Envelope(MessageType.FILE_RESULT, file = FileMessage(id, ok = ok, error = error)))
    }

    private fun add(transfer: Transfer) {
        val stamped = if (transfer.time > 0) transfer else transfer.copy(time = System.currentTimeMillis())
        _transfers.update { (listOf(stamped) + it).take(MAX_HISTORY) }
    }

    /** Removes one finished transfer from the list. The file itself stays where it is. */
    fun remove(id: String) {
        _transfers.update { list -> list.filterNot { it.id == id && it.status != TransferStatus.Active } }
        saveHistory()
    }

    private fun progress(id: String, transferred: Long) {
        _transfers.update { list -> list.map { if (it.id == id) it.copy(transferred = transferred) else it } }
    }

    private fun finish(id: String, status: TransferStatus, detail: String?, uri: String? = null) {
        _transfers.update { list ->
            list.map {
                // The receiver's result must not change a transfer that already failed on this side.
                if (it.id == id && it.status == TransferStatus.Active) it.copy(status = status, detail = detail, uri = uri) else it
            }
        }
        saveHistory()
        if (status != TransferStatus.Active) finishedCallbacks.remove(id)?.invoke(status == TransferStatus.Done)
    }

    private companion object {
        private const val MAX_UPLOADS = 2
        const val CHUNK_BYTES = 192 * 1024
        const val MAX_HISTORY = 500
    }
}
