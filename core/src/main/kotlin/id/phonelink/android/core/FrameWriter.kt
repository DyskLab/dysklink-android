package id.phonelink.android.core

import java.io.DataOutputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.builtins.serializer

/** A frame's JSON, already encoded: the first [length] bytes of [bytes]. [release] runs once, after it's written or dropped. */
class OutFrame(val bytes: ByteArray, val length: Int, val release: (() -> Unit)? = null)

/**
 * Writes one connection's frames on its own thread, so a peer that stops reading only holds up itself.
 *
 * Two queues: live messages (input, pointer, sound, screen, ping, pairing, everything small) always go before file data.
 * File data waits in a small window, so a big upload never puts more than a few chunks between a key press and the
 * network. The wire format is unchanged: the same frames, only in a different order across the two queues. Each queue
 * keeps its own order, and every message of a file transfer (offer, chunks, done, cancel) is in the file queue.
 */
internal class FrameWriter(output: OutputStream, name: String, private val closeSocket: () -> Unit) {
    private class Item(var bytes: ByteArray, var length: Int, var mergeable: Envelope?, var seq: Long, val release: (() -> Unit)?)

    private val out = DataOutputStream(output)
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val live = ArrayDeque<Item>()
    private val bulk = ArrayDeque<Item>()
    private var liveBytes = 0L
    private var bulkBytes = 0L
    private var liveSeq = 0L
    private var liveWritten = 0L
    private var liveStreak = 0L
    @Volatile private var closing = false
    private var closed = false

    /** True once [close] was called or writing failed. Nothing new is accepted. */
    val isClosing: Boolean get() = closing

    /**
     * Queues without waiting, for the main thread and for sending to several peers at once. False when the
     * connection is closing, the queue is full, or the message is too big for one frame.
     */
    fun post(envelope: Envelope): Boolean = enqueue(envelope, wait = false)

    /**
     * Waits like a direct write did: a live message until it's written, so video and sound producers keep their
     * backpressure; a file message until the file window has room.
     */
    fun send(envelope: Envelope): Boolean = enqueue(envelope, wait = true)

    /** A pre-encoded file frame, such as a `file.chunk`. Waits for room in the file window. */
    fun sendBulk(frame: OutFrame): Boolean {
        if (frame.length > PhoneLinkProtocol.MAX_FRAME_BYTES) {
            frame.release?.invoke()
            return false
        }
        return admitBulk(Item(frame.bytes, frame.length, null, 0, frame.release), wait = true)
    }

    private fun enqueue(envelope: Envelope, wait: Boolean): Boolean {
        if (closing) return false
        val bytes = encode(envelope) ?: return false
        if (envelope.type in BULK_TYPES) return admitBulk(Item(bytes, bytes.size, null, 0, null), wait)
        val mergeable = envelope.takeIf(::isMergeable)
        val seq: Long
        lock.withLock {
            if (closing) return false
            val last = live.peekLast()
            val merged = if (mergeable != null && last?.mergeable != null) merge(last.mergeable!!, mergeable) else null
            if (last != null && merged != null) {
                // Not written yet, so the newer move joins it instead of waiting behind it.
                val encoded = encode(merged) ?: return false
                liveBytes += encoded.size - last.length
                last.bytes = encoded
                last.length = encoded.size
                last.mergeable = merged
                last.seq = ++liveSeq
                seq = last.seq
            } else {
                while (live.size >= MAX_LIVE_MESSAGES || (live.isNotEmpty() && liveBytes + bytes.size > MAX_LIVE_BYTES)) {
                    if (!wait) {
                        CoreLog.w(TAG, "SEND_QUEUE_FULL ${envelope.type} ${live.size} messages, $liveBytes bytes")
                        return false
                    }
                    changed.await()
                    if (closing) return false
                }
                seq = ++liveSeq
                live.addLast(Item(bytes, bytes.size, mergeable, seq, null))
                liveBytes += bytes.size
            }
            changed.signalAll()
            if (!wait) return true
            while (liveWritten < seq && !closed) changed.await()
            return liveWritten >= seq
        }
    }

    private fun admitBulk(item: Item, wait: Boolean): Boolean {
        val accepted = lock.withLock {
            val limit = if (wait) BULK_WINDOW_BYTES else MAX_BULK_BYTES
            while (!closing && bulk.isNotEmpty() && bulkBytes + item.length > limit) {
                if (!wait) break
                changed.await()
            }
            if (closing || (bulk.isNotEmpty() && bulkBytes + item.length > limit)) return@withLock false
            bulk.addLast(item)
            bulkBytes += item.length
            changed.signalAll()
            true
        }
        if (!accepted) item.release?.invoke()
        return accepted
    }

    /**
     * Stops taking messages. Live messages already queued, such as a `pair.reject` before hanging up, still go out,
     * including one being written right now; queued file data is dropped. The writer closes the socket once it has
     * flushed them, which is at once when nothing is queued. Only a peer that stopped reading makes it wait for
     * the grace timer, which then closes the socket anyway.
     */
    fun close() {
        lock.withLock {
            if (closing) return
            closing = true
            changed.signalAll()
            if (closed) return
        }
        graceTimer.schedule({ closeSocket() }, CLOSE_GRACE_MS, TimeUnit.MILLISECONDS)
    }

    /** The next frame to write, live first, or null once closing with no live messages left. */
    private fun take(): Taken? = lock.withLock {
        while (true) {
            // Live messages go first, but a long run of them, such as video, still lets a file chunk through now and then.
            val fileTurn = bulk.isNotEmpty() && !closing && liveStreak >= MAX_LIVE_STREAK_BYTES
            val next = if (fileTurn) null else live.pollFirst()
            if (next != null) {
                liveBytes -= next.length
                liveStreak += next.length
                return@withLock Taken(next, true, live.isNotEmpty() || (!closing && bulk.isNotEmpty()))
            }
            if (closing) return@withLock null
            val file = bulk.pollFirst()
            if (file != null) {
                bulkBytes -= file.length
                liveStreak = 0
                // Room in the window for the uploader's next chunk.
                changed.signalAll()
                return@withLock Taken(file, false, live.isNotEmpty() || bulk.isNotEmpty())
            }
            changed.await()
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }

    private class Taken(val item: Item, val live: Boolean, val more: Boolean)

    private fun run() {
        try {
            while (true) {
                val taken = take() ?: break
                val item = taken.item
                try {
                    out.writeInt(item.length)
                    out.write(item.bytes, 0, item.length)
                    // Small messages queued together go out in one flush.
                    if (!taken.more) out.flush()
                } finally {
                    item.release?.invoke()
                }
                if (taken.live) lock.withLock {
                    liveWritten = item.seq
                    changed.signalAll()
                }
            }
            out.flush()
        } catch (_: Exception) {
            // The connection broke. The reader sees it too and ends the session.
        } finally {
            finish()
        }
    }

    private fun finish() {
        val dropped = lock.withLock {
            closing = true
            closed = true
            val items = live + bulk
            live.clear()
            bulk.clear()
            liveBytes = 0
            bulkBytes = 0
            changed.signalAll()
            items
        }
        dropped.forEach { it.release?.invoke() }
        closeSocket()
    }

    // Started last, once every field above is set.
    private val thread = Thread(::run, "dysklink-writer-$name").apply {
        isDaemon = true
        start()
    }

    internal companion object {
        private const val TAG = "PhoneLink"
        /** Everything of a file transfer, in one queue so its order holds. `file.result` is a reply and goes live. */
        val BULK_TYPES = setOf(MessageType.FILE_OFFER, MessageType.FILE_CHUNK, MessageType.FILE_DONE, MessageType.FILE_CANCEL)
        /** About three 64 KB chunks waiting; one more is being written. */
        const val BULK_WINDOW_BYTES = 256L * 1024
        /** For file messages posted without waiting. */
        const val MAX_BULK_BYTES = 4L * 1024 * 1024
        const val MAX_LIVE_MESSAGES = 4096
        const val MAX_LIVE_BYTES = 4L * 1024 * 1024
        const val CLOSE_GRACE_MS = 1_000L
        /** Live bytes written in a row before one waiting file chunk gets a turn. */
        const val MAX_LIVE_STREAK_BYTES = 1L * 1024 * 1024

        private val graceTimer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "dysklink-close").apply { isDaemon = true }
        }

        /** Null, with a log line, for a message bigger than one frame: the other side would drop the connection over it. */
        fun encode(envelope: Envelope): ByteArray? {
            val payload = protocolJson.encodeToString(Envelope.serializer(), envelope).encodeToByteArray()
            if (payload.size > PhoneLinkProtocol.MAX_FRAME_BYTES) {
                CoreLog.w(TAG, "FRAME_TOO_BIG ${envelope.type} ${payload.size} bytes, not sent")
                return null
            }
            return payload
        }

        private val POINTER_KINDS = setOf("move", "scroll")

        fun isPointer(envelope: Envelope) = envelope.type == MessageType.INPUT && envelope.input?.kind in POINTER_KINDS

        private fun isMergeable(envelope: Envelope) =
            isPointer(envelope) || (envelope.type == MessageType.TERMINAL_RESIZE && envelope.terminal != null)

        /** Two touchpad moves, or two scrolls, as one; a newer terminal size replaces the older one. Null when they can't merge. */
        fun merge(first: Envelope, second: Envelope): Envelope? {
            if (first.type == MessageType.TERMINAL_RESIZE || second.type == MessageType.TERMINAL_RESIZE) {
                val id = first.terminal?.id
                return if (first.type == second.type && id != null && id == second.terminal?.id) second else null
            }
            if (!isPointer(first) || !isPointer(second)) return null
            val a = first.input ?: return null
            val b = second.input ?: return null
            if (a.kind != b.kind) return null
            fun sum(x: Float?, y: Float?) = if (x == null && y == null) null else (x ?: 0f) + (y ?: 0f)
            return first.copy(input = a.copy(dx = sum(a.dx, b.dx), dy = sum(a.dy, b.dy)))
        }
    }
}

/**
 * Builds a `file.chunk` frame straight from file bytes, with no Envelope, String, or copy of the data. The output is
 * exactly what the JSON encoder makes for `Envelope(FILE_CHUNK, file = FileMessage(id, data = base64))`.
 */
internal object FileChunkFrame {
    private val SUFFIX = "\"}}".encodeToByteArray()

    fun prefix(id: String): ByteArray =
        ("{\"type\":\"${MessageType.FILE_CHUNK}\",\"file\":{\"id\":" + protocolJson.encodeToString(String.serializer(), id) + ",\"data\":\"")
            .encodeToByteArray()

    fun capacity(prefix: ByteArray, rawBytes: Int): Int = prefix.size + Base64Bytes.encodedLength(rawBytes) + SUFFIX.size

    /** Writes the frame for the first [count] bytes of [data] into [into]. Returns its length. */
    fun build(into: ByteArray, prefix: ByteArray, data: ByteArray, count: Int): Int {
        prefix.copyInto(into)
        val end = Base64Bytes.encode(data, 0, count, into, prefix.size)
        SUFFIX.copyInto(into, end)
        return end + SUFFIX.size
    }
}

/** Standard padded Base64 into a caller's buffer, the same output as `java.util.Base64.getEncoder()`. */
internal object Base64Bytes {
    private val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".encodeToByteArray()
    private const val PAD = '='.code.toByte()

    fun encodedLength(count: Int): Int = (count + 2) / 3 * 4

    /** Returns the index after the last byte written. */
    fun encode(src: ByteArray, offset: Int, count: Int, dst: ByteArray, at: Int): Int {
        var i = offset
        val end = offset + count
        var o = at
        while (end - i >= 3) {
            val bits = (src[i].toInt() and 0xff shl 16) or (src[i + 1].toInt() and 0xff shl 8) or (src[i + 2].toInt() and 0xff)
            dst[o] = ALPHABET[bits ushr 18]
            dst[o + 1] = ALPHABET[bits ushr 12 and 0x3f]
            dst[o + 2] = ALPHABET[bits ushr 6 and 0x3f]
            dst[o + 3] = ALPHABET[bits and 0x3f]
            i += 3
            o += 4
        }
        when (end - i) {
            1 -> {
                val bits = src[i].toInt() and 0xff shl 16
                dst[o] = ALPHABET[bits ushr 18]
                dst[o + 1] = ALPHABET[bits ushr 12 and 0x3f]
                dst[o + 2] = PAD
                dst[o + 3] = PAD
                o += 4
            }
            2 -> {
                val bits = (src[i].toInt() and 0xff shl 16) or (src[i + 1].toInt() and 0xff shl 8)
                dst[o] = ALPHABET[bits ushr 18]
                dst[o + 1] = ALPHABET[bits ushr 12 and 0x3f]
                dst[o + 2] = ALPHABET[bits ushr 6 and 0x3f]
                dst[o + 3] = PAD
                o += 4
            }
        }
        return o
    }
}
