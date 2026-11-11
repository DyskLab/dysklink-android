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

/**
 * One connection to a peer, as an upload sees it. Everything of an upload goes over the connection it was offered on,
 * so an upload never carries on over a newer connection that has never heard of it.
 */
interface TransferLink {
    /** Waits like [PeerConnection.send]. */
    fun send(envelope: Envelope): Boolean
    /** A pre-encoded `file.*` frame. Must run [OutFrame.release] once, sent or not. */
    fun sendFrame(frame: OutFrame): Boolean
    /** False once the connection is closing or closed. */
    val isOpen: Boolean
    /** [sendFrame] takes binary frames ([OutFrame.binary]): both sides listed `bin1` in their hello. */
    val binary: Boolean get() = false
}

class FileTransfers(
    private val scope: CoroutineScope,
    private val sinks: FileSinkFactory,
    /** Finished transfers are kept here, so received files can still be opened after the app is closed. */
    private val historyFile: File? = null,
    /**
     * Sends a pre-encoded `file.chunk` frame behind live messages, waiting while the connection's file window is full.
     * Must run [OutFrame.release] once, sent or not. Null encodes chunks as messages through [send] instead.
     * Not used when [linkFor] is given.
     */
    private val sendFrame: ((peerId: String, frame: OutFrame) -> Boolean)? = null,
    /** The peer's current connection, which an upload keeps to the end. Null sends by peer ID through [send]. */
    private val linkFor: ((peerId: String) -> TransferLink?)? = null,
    /** Queues a reply without waiting, for the reading thread. Null uses [send]. */
    private val post: ((peerId: String, envelope: Envelope) -> Boolean)? = null,
    /** Returns false if the message can't be sent. */
    private val send: (peerId: String, envelope: Envelope) -> Boolean,
) {
    private class Incoming(val peerId: String, val size: Long, val sink: FileSink) {
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        var received = 0L
    }

    /** An upload whose coroutine is still running. [stopReason] set means it must stop sending. */
    private class Upload(val peerId: String) {
        @Volatile var stopReason: String? = null
    }

    private val incoming = ConcurrentHashMap<String, Incoming>()
    /** Uploads still running, by transfer ID. Each removes itself when it ends, so this never outgrows them. */
    private val outgoing = ConcurrentHashMap<String, Upload>()

    /** A large selection from the Mac waits its turn instead of all sending at once. */
    private val uploads = Semaphore(MAX_UPLOADS)

    private val _transfers = MutableStateFlow(loadHistory())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

    private fun loadHistory(): List<Transfer> {
        val file = historyFile ?: return emptyList()
        return runCatching { protocolJson.decodeFromString(ListSerializer(Transfer.serializer()), file.readText()) }
            .getOrNull().orEmpty().filter { it.status != TransferStatus.Active }
            .distinctBy { Triple(it.peerId, it.id, it.time) }.take(MAX_HISTORY)
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
        val upload = Upload(peerId)
        outgoing[id] = upload
        add(Transfer(id, peerId, clean, size, 0, outgoing = true, status = TransferStatus.Active))
        scope.launch(Dispatchers.IO) {
            try {
                val failure = runCatching { uploads.withPermit { upload(id, upload, clean, size, kind, open) } }
                    .fold(onSuccess = { it }, onFailure = { it.message ?: "Failed to read the file" })
                if (failure != null) finish(id, TransferStatus.Failed, failure)
            } finally {
                outgoing.remove(id)
            }
        }
    }

    /** The link for [peerId]: its current connection, or sending by peer ID when connections aren't tracked here. */
    private fun currentLink(peerId: String): TransferLink? {
        linkFor?.let { return it(peerId) }
        return object : TransferLink {
            override fun send(envelope: Envelope) = this@FileTransfers.send(peerId, envelope)
            override fun sendFrame(frame: OutFrame): Boolean {
                val sender = this@FileTransfers.sendFrame
                if (sender == null) frame.release?.invoke()
                return sender?.invoke(peerId, frame) ?: false
            }
            override val isOpen get() = true
        }
    }

    private val usesFrames: Boolean get() = linkFor != null || sendFrame != null

    /** Returns a failure message, or null if all data was sent. Tells the receiver on its own connection when it fails. */
    private fun upload(id: String, state: Upload, name: String, size: Long, kind: String?, open: () -> InputStream): String? {
        // Failed while it waited its turn, such as when the connection dropped: it never starts.
        state.stopReason?.let { return it }
        val link = currentLink(state.peerId) ?: return "Connection lost"
        val failure = try {
            sendOver(link, id, state, name, size, kind, open)
        } catch (error: Exception) {
            error.message ?: "Failed to read the file"
        }
        if (failure != null && link.isOpen) link.send(Envelope(MessageType.FILE_CANCEL, file = FileMessage(id)))
        return failure
    }

    private fun sendOver(link: TransferLink, id: String, state: Upload, name: String, size: Long, kind: String?, open: () -> InputStream): String? {
        fun stopped(): String? = state.stopReason ?: if (!link.isOpen) "Connection lost" else null
        val offer = FileMessage(id, name = name, size = size, kind = kind)
        if (!link.send(Envelope(MessageType.FILE_OFFER, file = offer))) return stopped() ?: "Connection lost"
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val frames = if (usesFrames) ChunkFrames(id, link) else null
        // Without a frame sender, one reused buffer holds the Base64 text.
        val text = if (frames == null) ByteArray(Base64Bytes.encodedLength(CHUNK_BYTES)) else null
        open().use { input ->
            if (frames?.binary == true) {
                // Binary frames: the file is read straight into the frame after its header, with no other copy.
                while (scope.isActive) {
                    stopped()?.let { return it }
                    val frame = frames.take { scope.isActive && stopped() == null } ?: continue
                    val count = input.readUpTo(frame, frames.dataOffset, CHUNK_BYTES)
                    if (count <= 0) {
                        frames.giveBack(frame)
                        break
                    }
                    digest.update(frame, frames.dataOffset, count)
                    total += count
                    if (!frames.sendBinary(frame, count)) return stopped() ?: "Connection lost"
                    progress(id, total)
                }
                return@use
            }
            val buffer = ByteArray(CHUNK_BYTES)
            while (scope.isActive) {
                stopped()?.let { return it }
                val count = input.readUpTo(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
                total += count
                val sent = if (frames != null) {
                    val frame = frames.take { scope.isActive && stopped() == null } ?: continue
                    frames.send(frame, buffer, count)
                } else {
                    val length = Base64Bytes.encode(buffer, 0, count, text!!, 0)
                    val data = String(text, 0, length, Charsets.ISO_8859_1)
                    link.send(Envelope(MessageType.FILE_CHUNK, file = FileMessage(id, data = data)))
                }
                if (!sent) return stopped() ?: "Connection lost"
                progress(id, total)
            }
        }
        stopped()?.let { return it }
        if (total != size) return "File size changed while sending"
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val done = Envelope(MessageType.FILE_DONE, file = FileMessage(id, sha256 = hash))
        return if (link.send(done)) null else stopped() ?: "Connection lost"
    }

    /** Fills [buffer] as far as the stream allows, so chunks are full size. Returns 0 at the end. */
    private fun InputStream.readUpTo(buffer: ByteArray, offset: Int = 0, max: Int = buffer.size - offset): Int {
        var count = 0
        while (count < max) {
            val read = read(buffer, offset + count, max - count)
            if (read < 0) break
            count += read
        }
        return count
    }

    /**
     * A few chunk buffers for one upload, reused as the connection writes them. Each is the whole `file.chunk` frame,
     * so a chunk costs no new arrays, Strings, or Envelopes.
     */
    private class ChunkFrames(id: String, private val link: TransferLink) {
        /** Decided once per upload; a connection only ever turns binary on (at its hello), never off. */
        val binary = link.binary
        private val prefix = if (binary) FileChunkFrame.binaryPrefix(id) else FileChunkFrame.prefix(id)
        private val capacity = if (binary) prefix.size + CHUNK_BYTES else FileChunkFrame.capacity(prefix, CHUNK_BYTES)
        /** Binary frames: where the file bytes start. The header before it is written once per buffer. */
        val dataOffset = prefix.size
        private val free = java.util.concurrent.ArrayBlockingQueue<ByteArray>(FRAMES_PER_UPLOAD)
        private var made = 0

        /** A free buffer, or null once [keepWaiting] says to give up. */
        fun take(keepWaiting: () -> Boolean): ByteArray? {
            free.poll()?.let { return it }
            if (made < FRAMES_PER_UPLOAD) {
                made++
                return ByteArray(capacity).also { if (binary) prefix.copyInto(it) }
            }
            while (keepWaiting()) {
                free.poll(WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)?.let { return it }
            }
            return null
        }

        fun giveBack(frame: ByteArray) {
            free.offer(frame)
        }

        /** [count] file bytes already sit at [dataOffset] in [frame]. */
        fun sendBinary(frame: ByteArray, count: Int): Boolean =
            link.sendFrame(OutFrame(frame, dataOffset + count, release = { free.offer(frame) }, binary = true))

        fun send(frame: ByteArray, data: ByteArray, count: Int): Boolean {
            val length = FileChunkFrame.build(frame, prefix, data, count)
            return link.sendFrame(OutFrame(frame, length, release = { free.offer(frame) }))
        }

        private companion object {
            const val WAIT_MS = 250L
        }
    }

    /** Only called for paired devices. */
    fun handle(peerId: String, type: String, file: FileMessage?, data: ByteArray? = null) {
        file ?: return
        when (type) {
            MessageType.FILE_OFFER -> onOffer(peerId, file)
            MessageType.FILE_CHUNK -> onChunk(peerId, file, data)
            MessageType.FILE_DONE -> onDone(peerId, file)
            MessageType.FILE_CANCEL -> onCancel(peerId, file)
            MessageType.FILE_RESULT -> onResult(peerId, file)
        }
    }

    fun onDisconnected(peerId: String) {
        incoming.entries.filter { it.value.peerId == peerId }.forEach { (id, item) ->
            incoming.remove(id)
            item.sink.abort()
            finish(id, TransferStatus.Failed, "Connection lost")
        }
        // Uploads still sending, or waiting their turn, stop: none of them carries on over a newer connection.
        outgoing.values.filter { it.peerId == peerId }.forEach { it.stopReason = "Connection lost" }
        _transfers.value.filter { it.peerId == peerId && it.outgoing && it.status == TransferStatus.Active }
            .forEach { finish(it.id, TransferStatus.Failed, "Connection lost") }
    }

    /** An active upload of this ID to this peer; another device can't touch it. */
    private fun isOutgoingTo(peerId: String, id: String) =
        _transfers.value.any { it.id == id && it.peerId == peerId && it.outgoing && it.status == TransferStatus.Active }

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

    /** [data] is the raw chunk of a binary frame; otherwise `file.data` holds it in base64. */
    private fun onChunk(peerId: String, file: FileMessage, data: ByteArray?) {
        val item = incoming[file.id]?.takeIf { it.peerId == peerId } ?: return
        val failure = runCatching {
            val bytes = data ?: Base64.getDecoder().decode(file.data.orEmpty())
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
        outgoing[file.id]?.takeIf { it.peerId == peerId }?.stopReason = "Canceled by the receiver"
        if (isOutgoingTo(peerId, file.id)) return finish(file.id, TransferStatus.Failed, "Canceled by the receiver")
        val item = incoming[file.id]?.takeIf { it.peerId == peerId } ?: return
        incoming.remove(file.id)
        item.sink.abort()
        finish(file.id, TransferStatus.Failed, "Canceled by the sender")
    }

    private fun onResult(peerId: String, file: FileMessage) {
        if (!isOutgoingTo(peerId, file.id)) return
        if (file.ok == true) {
            finish(file.id, TransferStatus.Done, "Sent")
        } else {
            outgoing[file.id]?.stopReason = "Rejected by the receiver"
            finish(file.id, TransferStatus.Failed, file.error ?: "Rejected by the receiver")
        }
    }

    private fun failIncoming(id: String, item: Incoming, message: String) {
        incoming.remove(id)
        item.sink.abort()
        reply(item.peerId, id, false, message)
        finish(id, TransferStatus.Failed, message)
    }

    /** Queued, not waited on: this runs on the connection's reading thread. */
    private fun reply(peerId: String, id: String, ok: Boolean, error: String?) {
        (post ?: send)(peerId, Envelope(MessageType.FILE_RESULT, file = FileMessage(id, ok = ok, error = error)))
    }

    private fun add(transfer: Transfer) {
        val stamped = if (transfer.time > 0) transfer else transfer.copy(time = System.currentTimeMillis())
        // A finished entry with the same ID from the same device is replaced, so the list never holds it twice.
        _transfers.update { list ->
            (listOf(stamped) + list.filterNot { it.id == stamped.id && it.peerId == stamped.peerId && it.status != TransferStatus.Active })
                .take(MAX_HISTORY)
        }
    }

    /** Removes one finished transfer from the list. The file itself stays where it is. */
    fun remove(id: String) {
        _transfers.update { list -> list.filterNot { it.id == id && it.status != TransferStatus.Active } }
        saveHistory()
    }

    /** The newest byte count of each active transfer, and when the list last showed it. */
    private class Progress(@Volatile var transferred: Long, @Volatile var shownAt: Long)
    private val progressById = ConcurrentHashMap<String, Progress>()

    /** At most about ten list updates a second for each transfer; the final state always shows. */
    private fun progress(id: String, transferred: Long) {
        val now = System.nanoTime()
        val item = progressById.getOrPut(id) { Progress(0, now - PROGRESS_INTERVAL_NS) }
        item.transferred = transferred
        if (now - item.shownAt < PROGRESS_INTERVAL_NS) return
        item.shownAt = now
        _transfers.update { list -> list.map { if (it.id == id) it.copy(transferred = transferred) else it } }
    }

    private fun finish(id: String, status: TransferStatus, detail: String?, uri: String? = null) {
        val latest = progressById.remove(id)?.transferred
        _transfers.update { list ->
            list.map {
                // The receiver's result must not change a transfer that already failed on this side.
                if (it.id == id && it.status == TransferStatus.Active) {
                    it.copy(status = status, detail = detail, uri = uri, transferred = maxOf(it.transferred, latest ?: 0L))
                } else {
                    it
                }
            }
        }
        saveHistory()
        if (status != TransferStatus.Active) finishedCallbacks.remove(id)?.invoke(status == TransferStatus.Done)
    }

    private companion object {
        private const val MAX_UPLOADS = 2
        /** Small, so live messages wait at most one chunk's write behind a big file. Receivers take any size. */
        const val CHUNK_BYTES = 64 * 1024
        /** Chunk buffers per upload: the connection's file window holds about three, and one is being filled. */
        const val FRAMES_PER_UPLOAD = 5
        const val MAX_HISTORY = 500
        const val PROGRESS_INTERVAL_NS = 100_000_000L
    }
}
