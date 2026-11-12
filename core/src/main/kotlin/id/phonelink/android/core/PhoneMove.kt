package id.phonelink.android.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable

/** What a file from another phone is for, in `file.kind`, and why one was refused, in `file.reason`. */
object PhoneKinds {
    /** Photo backup (1.6), as photo sync to a Mac. Kept in Pictures/DyskLink/<sender>. */
    const val PHOTO = "photo"
    /** A move (1.6): the contacts as one .vcf, photos and videos, and files. */
    const val MOVE_CONTACTS = "move.contacts"
    const val MOVE_MEDIA = "move.media"
    const val MOVE_FILE = "move.file"
    val MOVE = setOf(MOVE_CONTACTS, MOVE_MEDIA, MOVE_FILE)

    const val LOW_SPACE = "low-space"
    const val ASK = "ask"
    const val OFF = "off"
}

/** A phone keeps at least 1 GB free: photo backup and moves stop before that. */
object StorageGuard {
    const val MIN_FREE_BYTES = 1L shl 30

    fun enough(freeBytes: Long, incomingBytes: Long): Boolean = freeBytes - incomingBytes.coerceAtLeast(0) >= MIN_FREE_BYTES

    fun lowSpaceText(localName: String, freeBytes: Long): String =
        "Less than 1 GB would be free on $localName (${freeBytes / (1L shl 20)} MB free now). Free up space there, then try again."
}

/** What a move holds, on `move.request`; on `move.done`, also how it went. */
@Serializable
data class MoveSummary(
    val id: String,
    val contacts: Int = 0,
    val photos: Int = 0,
    val files: Int = 0,
    val bytes: Long = 0,
    val sent: Int? = null,
    val skipped: Int? = null,
    val failed: Int? = null,
    /** On `move.done`: the name of the contacts file sent, to import on the new phone. */
    val contactsFile: String? = null,
) {
    fun valid(): Boolean = id.isNotBlank() && id.length <= 64 && bytes >= 0 && contacts >= 0 && photos >= 0 && files >= 0
}

/** One contact in a .vcf, as a move carries it. */
@Serializable
data class VCardContact(
    val name: String,
    val phones: List<String> = emptyList(),
    val emails: List<String> = emptyList(),
    val organization: String? = null,
    val note: String? = null,
)

/**
 * vCard 3.0, as the contacts of a move: written on the old phone, opened with the Contacts app on the new one
 * (which imports it). [parse] reads what [write] wrote, for the checks; the new phone itself never writes contacts.
 */
object VCard {
    private const val FOLD = 75

    fun write(contacts: List<VCardContact>): String = buildString {
        contacts.filter { it.name.isNotBlank() || it.phones.isNotEmpty() || it.emails.isNotEmpty() }.forEach { contact ->
            line("BEGIN:VCARD")
            line("VERSION:3.0")
            val name = contact.name.ifBlank { contact.phones.firstOrNull() ?: contact.emails.firstOrNull().orEmpty() }
            line("FN:${escape(name)}")
            line("N:;${escape(name)};;;")
            contact.phones.filter { it.isNotBlank() }.forEach { line("TEL;TYPE=CELL:${escape(it.trim())}") }
            contact.emails.filter { it.isNotBlank() }.forEach { line("EMAIL;TYPE=INTERNET:${escape(it.trim())}") }
            contact.organization?.takeIf { it.isNotBlank() }?.let { line("ORG:${escape(it)}") }
            contact.note?.takeIf { it.isNotBlank() }?.let { line("NOTE:${escape(it)}") }
            line("END:VCARD")
        }
    }

    fun parse(text: String): List<VCardContact> {
        // Unfold: a line break followed by a space or tab continues the line before.
        val lines = text.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "").split('\n')
        val contacts = ArrayList<VCardContact>()
        var current: VCardContact? = null
        for (raw in lines) {
            val colon = raw.indexOf(':')
            if (colon <= 0) continue
            val property = raw.substring(0, colon).substringBefore(';').uppercase()
            val value = raw.substring(colon + 1)
            when (property) {
                "BEGIN" -> if (value.equals("VCARD", true)) current = VCardContact("")
                "END" -> if (value.equals("VCARD", true)) current?.let(contacts::add).also { current = null }
                "FN" -> current = current?.copy(name = unescape(value))
                "TEL" -> current = current?.let { it.copy(phones = it.phones + unescape(value)) }
                "EMAIL" -> current = current?.let { it.copy(emails = it.emails + unescape(value)) }
                "ORG" -> current = current?.copy(organization = unescape(value))
                "NOTE" -> current = current?.copy(note = unescape(value))
            }
        }
        return contacts
    }

    fun escape(value: String): String = value
        .replace("\\", "\\\\").replace(",", "\\,").replace(";", "\\;")
        .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")

    fun unescape(value: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                val next = value[i + 1]
                out.append(if (next == 'n' || next == 'N') '\n' else next)
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    /** Folds at 75 bytes of UTF-8, never inside a character. */
    private fun StringBuilder.line(text: String) {
        var bytes = 0
        var first = true
        for (ch in text) {
            val size = ch.toString().toByteArray(Charsets.UTF_8).size
            val limit = if (first) FOLD else FOLD - 1
            if (bytes + size > limit && !ch.isLowSurrogate()) {
                append("\r\n ")
                bytes = 0
                first = false
            }
            append(ch)
            bytes += size
        }
        append("\r\n")
    }
}

/**
 * What each phone has sent here for backup or a move (folder, name, size, SHA-256), so a copy offered again is
 * skipped. Appended to `phone_received.txt`; forgotten when that phone is unpaired.
 */
class ReceivedIndex(directory: File?) {
    private val file = directory?.let { File(it, "phone_received.txt") }
    private val keys = HashMap<String, LinkedHashSet<String>>()

    init {
        runCatching {
            file?.takeIf { it.exists() }?.forEachLine { line ->
                val peer = line.substringBefore('\t', "")
                val key = line.substringAfter('\t', "")
                if (peer.isNotEmpty() && key.isNotEmpty()) keys.getOrPut(peer) { LinkedHashSet() }.add(key)
            }
        }
    }

    fun key(folder: String?, name: String, size: Long, sha256: String): String =
        "${folder.orEmpty()}/${FileNames.sanitize(name)}|$size|${sha256.lowercase()}"

    @Synchronized
    fun has(peerId: String, folder: String?, name: String, size: Long, sha256: String?): Boolean =
        sha256 != null && keys[peerId]?.contains(key(folder, name, size, sha256)) == true

    @Synchronized
    fun add(peerId: String, folder: String?, name: String, size: Long, sha256: String) {
        val key = key(folder, name, size, sha256)
        val set = keys.getOrPut(peerId) { LinkedHashSet() }
        if (!set.add(key)) return
        if (set.size > MAX_PER_PEER) {
            set.remove(set.first())
            rewrite()
        } else {
            runCatching { file?.appendText("$peerId\t$key\n") }
        }
    }

    @Synchronized
    fun forget(peerId: String) {
        if (keys.remove(peerId) != null) rewrite()
    }

    private fun rewrite() {
        val target = file ?: return
        runCatching { target.writeText(keys.entries.joinToString("") { (peer, set) -> set.joinToString("") { "$peer\t$it\n" } }) }
    }

    companion object {
        const val MAX_PER_PEER = 50_000
    }
}

@Serializable
enum class MoveKind { Contacts, Media, File }

@Serializable
enum class MoveItemStatus { Pending, Sent, Skipped, Failed }

/** One thing to move: [source] is where the old phone reads it (a content URI or a path). */
@Serializable
data class MoveItem(
    val id: String,
    val kind: MoveKind,
    val name: String,
    val size: Long,
    val source: String,
    val folder: String? = null,
    val status: MoveItemStatus = MoveItemStatus.Pending,
    val tries: Int = 0,
    val error: String? = null,
)

/**
 * Asking: waits for the new phone's answer. Running: sends. Paused: by the user, or because the new phone said no or
 * is full ([MovePlan.error] says why). Waiting: the connection dropped, it goes on when it's back. Done: all tried.
 */
@Serializable
enum class MovePhase { Asking, Running, Paused, Waiting, Done }

@Serializable
data class MovePlan(
    val id: String,
    val peerId: String,
    val peerName: String,
    val items: List<MoveItem>,
    val phase: MovePhase = MovePhase.Asking,
    val error: String? = null,
    val createdAt: Long = 0,
    /** Time spent sending, for the ETA. */
    val activeMs: Long = 0,
    val runningSince: Long? = null,
)

data class MoveProgress(
    val totalItems: Int,
    val doneItems: Int,
    val totalBytes: Long,
    val doneBytes: Long,
    val sent: Int,
    val skipped: Int,
    val failed: Int,
    /** Null until something was sent to measure the speed by. */
    val etaMs: Long?,
)

/**
 * The move wizard's state on the old phone, kept in `phone_move.json` after each change, so it goes on from where it
 * stopped after a dropped connection, a pause, or the app being closed. Pure, so it can be tested without sockets.
 */
class MoveJob(private val file: File?, private val clock: () -> Long = System::currentTimeMillis) {
    private val _plan = MutableStateFlow(load())
    val plan: StateFlow<MovePlan?> = _plan.asStateFlow()

    @Synchronized
    fun start(peerId: String, peerName: String, items: List<MoveItem>): MovePlan {
        val plan = MovePlan(UUID.randomUUID().toString(), peerId, peerName, items.distinctBy { it.id }, createdAt = clock())
        set(plan)
        return plan
    }

    /** The new phone said yes. */
    @Synchronized
    fun accepted() = change(MovePhase.Asking) { it.copy(phase = MovePhase.Running, error = null, runningSince = clock()) }

    /** The new phone said no, is full, or didn't answer: paused with why, until resumed. */
    @Synchronized
    fun refused(error: String) = change(MovePhase.Asking, MovePhase.Running) { stopClock(it).copy(phase = MovePhase.Paused, error = error) }

    @Synchronized
    fun pause() = change(MovePhase.Asking, MovePhase.Running, MovePhase.Waiting) { stopClock(it).copy(phase = MovePhase.Paused, error = null) }

    /** Asks the new phone again, then goes on with what's left. */
    @Synchronized
    fun resume() = change(MovePhase.Paused) { it.copy(phase = MovePhase.Asking, error = null) }

    @Synchronized
    fun disconnected() = change(MovePhase.Asking, MovePhase.Running) { stopClock(it).copy(phase = MovePhase.Waiting) }

    @Synchronized
    fun connected() = change(MovePhase.Waiting) { it.copy(phase = MovePhase.Asking) }

    /** The next item to send, only while running. */
    fun next(): MoveItem? = _plan.value?.takeIf { it.phase == MovePhase.Running }?.items?.firstOrNull { it.status == MoveItemStatus.Pending }

    /**
     * An item's transfer ended. A failure while not running (paused, or the connection dropped: [lost]) doesn't count:
     * it's sent again later. Otherwise it's tried [MAX_TRIES] times before it's left out.
     */
    @Synchronized
    fun finished(itemId: String, ok: Boolean, skipped: Boolean = false, error: String? = null, lost: Boolean = false) {
        val plan = _plan.value ?: return
        val item = plan.items.firstOrNull { it.id == itemId && it.status == MoveItemStatus.Pending } ?: return
        val updated = when {
            ok -> item.copy(status = if (skipped) MoveItemStatus.Skipped else MoveItemStatus.Sent, error = null)
            lost || plan.phase != MovePhase.Running -> return
            item.tries + 1 >= MAX_TRIES -> item.copy(status = MoveItemStatus.Failed, tries = item.tries + 1, error = error)
            else -> item.copy(tries = item.tries + 1, error = error)
        }
        var next = plan.copy(items = plan.items.map { if (it.id == itemId) updated else it })
        if (next.phase == MovePhase.Running && next.items.none { it.status == MoveItemStatus.Pending }) {
            next = stopClock(next).copy(phase = MovePhase.Done)
        }
        set(next)
    }

    /** Gone: the wizard starts over. */
    @Synchronized
    fun clear() {
        _plan.value = null
        runCatching { file?.delete() }
    }

    fun progress(plan: MovePlan? = _plan.value, now: Long = clock()): MoveProgress? {
        plan ?: return null
        val items = plan.items
        val done = items.filter { it.status != MoveItemStatus.Pending }
        val sentBytes = items.filter { it.status == MoveItemStatus.Sent }.sumOf { it.size }
        val remaining = items.filter { it.status == MoveItemStatus.Pending }.sumOf { it.size }
        val active = plan.activeMs + (plan.runningSince?.let { now - it } ?: 0L)
        val eta = if (sentBytes > 0 && active > 0) (remaining.toDouble() * active / sentBytes).toLong() else null
        return MoveProgress(
            totalItems = items.size, doneItems = done.size,
            totalBytes = items.sumOf { it.size }, doneBytes = done.sumOf { it.size },
            sent = items.count { it.status == MoveItemStatus.Sent }, skipped = items.count { it.status == MoveItemStatus.Skipped },
            failed = items.count { it.status == MoveItemStatus.Failed }, etaMs = if (remaining == 0L) 0L else eta,
        )
    }

    /** What the move holds, for `move.request`; with the counts once done, for `move.done`. */
    fun summary(plan: MovePlan, done: Boolean = false): MoveSummary {
        val items = plan.items
        return MoveSummary(
            id = plan.id,
            contacts = items.count { it.kind == MoveKind.Contacts }, photos = items.count { it.kind == MoveKind.Media },
            files = items.count { it.kind == MoveKind.File },
            bytes = items.filter { it.status == MoveItemStatus.Pending }.sumOf { it.size },
            sent = if (done) items.count { it.status == MoveItemStatus.Sent } else null,
            skipped = if (done) items.count { it.status == MoveItemStatus.Skipped } else null,
            failed = if (done) items.count { it.status == MoveItemStatus.Failed } else null,
            contactsFile = if (done) items.firstOrNull { it.kind == MoveKind.Contacts && it.status != MoveItemStatus.Failed }?.name else null,
        )
    }

    private fun stopClock(plan: MovePlan): MovePlan {
        val since = plan.runningSince ?: return plan
        return plan.copy(activeMs = plan.activeMs + (clock() - since).coerceAtLeast(0), runningSince = null)
    }

    private fun change(vararg from: MovePhase, block: (MovePlan) -> MovePlan): Boolean {
        val plan = _plan.value ?: return false
        if (plan.phase !in from) return false
        set(block(plan))
        return true
    }

    private fun set(plan: MovePlan) {
        _plan.value = plan
        runCatching { file?.writeText(protocolJson.encodeToString(MovePlan.serializer(), plan)) }
    }

    private fun load(): MovePlan? = runCatching {
        val plan = protocolJson.decodeFromString(MovePlan.serializer(), file!!.readText())
        // The app was closed while it ran: it goes on once the new phone is connected again.
        if (plan.phase == MovePhase.Running || plan.phase == MovePhase.Asking) plan.copy(phase = MovePhase.Waiting, runningSince = null) else plan
    }.getOrNull()

    companion object {
        const val MAX_TRIES = 3
    }
}

/** Where the old phone reads what it moves. Set by the app; null pauses the move until it's back. */
interface MoveSource {
    fun open(item: MoveItem): InputStream

    /** The size now: the gallery's can be out of date. */
    fun length(item: MoveItem): Long = item.size

    /** For the new phone to skip what it has. */
    fun sha256(item: MoveItem): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        open(item).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}

/**
 * Runs the move on the old phone: asks the new phone with `move.request`, then sends each item with the usual file
 * messages (kind [PhoneKinds.MOVE_CONTACTS], [PhoneKinds.MOVE_MEDIA], or [PhoneKinds.MOVE_FILE]) one at a time, and
 * ends with `move.done`. A dropped connection waits; when it's back, it asks again and goes on.
 */
class PhoneMover(
    directory: File?,
    private val scope: CoroutineScope,
    private val files: FileTransfers,
    /** Sends `move.request`; false when that phone isn't connected. */
    private val ask: (peerId: String, Envelope) -> Boolean,
    private val post: (peerId: String, Envelope) -> Boolean,
    private val isConnected: (peerId: String) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val answerTimeoutMs: Long = AwaitedReplies.timeoutMs(PeerAsk.Move),
) {
    val job = MoveJob(directory?.let { File(it, "phone_move.json") }, clock)
    @Volatile var source: MoveSource? = null

    private val lock = Any()
    private var running = false
    private var again = false
    private var askedKey: String? = null
    @Volatile private var current: String? = null

    fun start(peerId: String, peerName: String, items: List<MoveItem>) {
        if (items.isEmpty()) return
        job.start(peerId, peerName, items)
        kick()
    }

    fun pause() {
        job.pause()
        current?.let { files.cancel(it, "Paused") }
    }

    fun resume() {
        if (job.resume()) kick()
    }

    /** Stops and forgets the move. What was sent stays on the new phone. */
    fun cancel() {
        pause()
        job.clear()
    }

    fun connected(peerId: String) {
        if (job.plan.value?.peerId == peerId && job.connected()) kick()
    }

    fun disconnected(peerId: String) {
        if (job.plan.value?.peerId != peerId) return
        synchronized(lock) { askedKey = null }
        job.disconnected()
    }

    /** A `move.answer` this phone waited for. */
    fun answered(peerId: String, envelope: Envelope) {
        val plan = job.plan.value ?: return
        if (plan.peerId != peerId || plan.phase != MovePhase.Asking) return
        synchronized(lock) {
            if (envelope.key == null || envelope.key != askedKey) return
            askedKey = null
        }
        val error = envelope.error
        if (error != null) job.refused(error) else if (job.accepted()) kick()
    }

    /** Runs until it waits for something: an answer, a connection, or the user. */
    fun kick() {
        synchronized(lock) {
            again = true
            if (running) return
            running = true
        }
        scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    synchronized(lock) {
                        if (!again) {
                            running = false
                            return@launch
                        }
                        again = false
                    }
                    runCatching { run() }
                }
            } finally {
                synchronized(lock) { running = false }
            }
        }
    }

    private suspend fun run() {
        while (true) {
            val plan = job.plan.value ?: return
            when (plan.phase) {
                MovePhase.Asking -> return askOnce(plan)
                MovePhase.Running -> {
                    val item = job.next()
                    if (item == null) {
                        post(plan.peerId, Envelope(MessageType.MOVE_DONE, move = job.summary(job.plan.value ?: plan, done = true)))
                        return
                    }
                    send(plan, item)
                    // A run that just ended sends `move.done` on the next turn.
                    job.plan.value?.takeIf { it.phase == MovePhase.Done && it.id == plan.id }?.let {
                        post(it.peerId, Envelope(MessageType.MOVE_DONE, move = job.summary(it, done = true)))
                        return
                    }
                }
                else -> return
            }
        }
    }

    private fun askOnce(plan: MovePlan) {
        if (!isConnected(plan.peerId)) {
            job.disconnected()
            return
        }
        val key = "${plan.id.take(8)}:${UUID.randomUUID().toString().take(8)}"
        synchronized(lock) {
            // Still waiting for the last one.
            if (askedKey != null) return
            askedKey = key
        }
        if (!ask(plan.peerId, Envelope(MessageType.MOVE_REQUEST, key = key, move = job.summary(plan)))) {
            synchronized(lock) { askedKey = null }
            job.disconnected()
            return
        }
        scope.launch {
            delay(answerTimeoutMs)
            val still = synchronized(lock) { (askedKey == key).also { if (it) askedKey = null } }
            if (still && job.plan.value?.phase == MovePhase.Asking) {
                job.refused("${plan.peerName} didn't answer. Update DyskLink there, then try again.")
            }
        }
    }

    private suspend fun send(plan: MovePlan, item: MoveItem) {
        val source = source ?: return run { job.refused("Open DyskLink on this phone to go on.") }
        val size = runCatching { source.length(item) }.getOrDefault(item.size)
        val sha = source.sha256(item)
        if (sha == null && runCatching { source.open(item).close() }.isFailure) {
            return job.finished(item.id, ok = false, error = "Can't read ${item.name}")
        }
        val kind = when (item.kind) {
            MoveKind.Contacts -> PhoneKinds.MOVE_CONTACTS
            MoveKind.Media -> PhoneKinds.MOVE_MEDIA
            MoveKind.File -> PhoneKinds.MOVE_FILE
        }
        var id = ""
        val ok = suspendCancellableCoroutine { done ->
            id = files.sendFile(plan.peerId, item.name, size, kind, onFinished = { done.resume(it) }, folder = item.folder, sha256 = sha) {
                source.open(item)
            }
            current = id
        }
        current = null
        val outcome = files.outcome(id)
        if (ok) return job.finished(item.id, ok = true, skipped = outcome?.skipped == true)
        when (outcome?.reason) {
            PhoneKinds.LOW_SPACE, PhoneKinds.ASK, PhoneKinds.OFF -> job.refused(outcome.error ?: "${plan.peerName} refused the move.")
            else -> {
                val error = files.transfers.value.firstOrNull { it.id == id }?.detail
                val lost = !isConnected(plan.peerId)
                if (lost) job.disconnected()
                job.finished(item.id, ok = false, error = error, lost = lost)
            }
        }
    }
}
