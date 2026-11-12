package id.phonelink.android.core

import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

/**
 * SMS through another phone (1.6). B has the SIM, A (a tablet, say) reads B's threads, gets B's new SMS while it
 * watches, and sends SMS through B. Nothing here for a Mac: its `sms.*` messages stay Mac-only.
 */
object SmsThreads {
    const val THREADS = 100
    const val THREAD_MESSAGES = 200
    const val MAX_CHARS = 2_000
    const val MAX_ID = 64
    private const val MIN_DIGITS = 3
    private const val MAX_DIGITS = 20

    /** The newest message of each thread, newest thread first. */
    fun latest(messages: List<SmsItem>, max: Int = THREADS): List<SmsItem> =
        messages.sortedByDescending { it.time }.distinctBy { it.threadId }.take(max)

    /** The newest messages that fit in one frame with room to spare, newest first. */
    fun fit(items: List<SmsItem>, bytes: Int = PhoneBridge.PAGE_BYTES): List<SmsItem> {
        var total = 0
        return items.takeWhile { item ->
            total += protocolJson.encodeToString(SmsItem.serializer(), item).encodeToByteArray().size + 1
            total <= bytes
        }
    }

    /** A number to send to: digits with spaces, dashes, dots, and brackets, `+` only first. No `*` or `#`. */
    fun validAddress(raw: String?): Boolean {
        val text = raw?.trim() ?: return false
        if (text.isEmpty() || text.length > 32 || text.any { !(it in '0'..'9') && it !in "+ -()." }) return false
        if (text.indexOf('+', 1) != -1) return false
        return text.count { it in '0'..'9' } in MIN_DIGITS..MAX_DIGITS
    }

    fun validBody(body: String?): Boolean = !body.isNullOrBlank() && body.length <= MAX_CHARS
}

/** At most [max] SMS from each phone in any [windowMs]. */
class SendRateLimit(
    private val max: Int = MAX_PER_MINUTE,
    private val windowMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sent = HashMap<String, ArrayDeque<Long>>()

    /** True and counted when another may go now. */
    @Synchronized
    fun take(peerId: String): Boolean {
        val times = trimmed(peerId)
        if (times.size >= max) return false
        times.addLast(clock())
        return true
    }

    /** How long until the next one may go, or 0. */
    @Synchronized
    fun waitMs(peerId: String): Long {
        val times = trimmed(peerId)
        if (times.size < max) return 0
        return (times.first() + windowMs - clock()).coerceAtLeast(0)
    }

    @Synchronized
    fun forget(peerId: String) {
        sent.remove(peerId)
    }

    private fun trimmed(peerId: String): ArrayDeque<Long> {
        val times = sent.getOrPut(peerId) { ArrayDeque() }
        val now = clock()
        while (times.isNotEmpty() && times.first() <= now - windowMs) times.removeFirst()
        return times
    }

    companion object {
        const val MAX_PER_MINUTE = 10
    }
}

/** One SMS another phone sent through this one, or tried to. Kept on this phone (B). */
@Serializable
data class SmsLogEntry(
    val peerId: String,
    val peerName: String,
    val address: String,
    val time: Long,
    val chars: Int,
    val ok: Boolean,
    val error: String? = null,
)

/** The SMS other phones sent through this one, newest first, kept in `phone_sms_log.json`. Kept after unpairing. */
class SmsSendLog(directory: File?, private val max: Int = MAX_ENTRIES) {
    private val file = directory?.let { File(it, "phone_sms_log.json") }
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<SmsLogEntry>> = _entries.asStateFlow()

    @Synchronized
    fun add(entry: SmsLogEntry) {
        _entries.value = (listOf(entry) + _entries.value).take(max)
        save()
    }

    fun of(peerId: String): List<SmsLogEntry> = _entries.value.filter { it.peerId == peerId }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        save()
    }

    private fun load(): List<SmsLogEntry> = runCatching {
        protocolJson.decodeFromString(ListSerializer(SmsLogEntry.serializer()), file!!.readText())
    }.getOrDefault(emptyList())

    private fun save() {
        val target = file ?: return
        runCatching { target.writeText(protocolJson.encodeToString(ListSerializer(SmsLogEntry.serializer()), _entries.value)) }
    }

    companion object {
        const val MAX_ENTRIES = 300
    }
}

/**
 * The phone with the SIM (B): answers the SMS requests [PhoneRules.fromSmsReader] let through. It still needs
 * Android's SMS permission here; each send is rate limited, logged, and shown in a notification through [onSent].
 */
class SmsShare(
    private val access: PhoneAccess,
    private val data: PhoneData,
    val log: SmsSendLog,
    private val send: (peerId: String, envelope: Envelope) -> Unit,
    private val localName: () -> String,
    private val limiter: SendRateLimit = SendRateLimit(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A phone sent an SMS through this one: show it here. Called after the carrier took it. */
    @Volatile var onSent: ((peerId: String, peerName: String, address: String) -> Unit)? = null

    private val _watchers = MutableStateFlow<Set<String>>(emptySet())
    /** Phones that get this phone's new SMS now; while any does, the SMS watcher runs. */
    val watchers: StateFlow<Set<String>> = _watchers.asStateFlow()
    @Volatile private var lastId = 0L

    private fun granted(): Boolean = runCatching { data.isGranted(PhoneFeature.Sms) }.getOrDefault(false)

    private fun permissionText() = "SMS needs the SMS permission for DyskLink on ${localName()}. Turn it on there."

    /** A request [PhoneRules.fromSmsReader] let through. Blocking: call it off the reading thread. */
    fun handle(peerId: String, peerName: String, envelope: Envelope) {
        when (envelope.type) {
            MessageType.PSMS_UNWATCH -> unwatch(peerId)
            MessageType.PSMS_LIST -> {
                fun reply(items: List<SmsItem>, error: String? = null) =
                    send(peerId, Envelope(MessageType.PSMS_LISTING, key = envelope.key, smsThread = envelope.smsThread, sms = items, error = error))
                if (!granted()) return reply(emptyList(), permissionText())
                val thread = envelope.smsThread
                val items = runCatching {
                    if (thread != null) data.readSmsThread(thread, SmsThreads.THREAD_MESSAGES)
                    else SmsThreads.latest(data.readSms(PhoneData.SMS_SCAN))
                }.getOrElse { return reply(emptyList(), it.message ?: "Couldn't read SMS on ${localName()}.") }
                reply(SmsThreads.fit(items))
            }
            MessageType.PSMS_SEND -> sendSms(peerId, peerName, envelope.smsSend)
            MessageType.PSMS_WATCH -> {
                if (!granted()) return send(peerId, Envelope(MessageType.PSMS_WATCHING, key = envelope.key, error = permissionText()))
                if (lastId == 0L) lastId = runCatching { data.readSms(1).firstOrNull()?.id }.getOrNull() ?: 0L
                _watchers.update { it + peerId }
                send(peerId, Envelope(MessageType.PSMS_WATCHING, key = envelope.key))
            }
        }
    }

    private fun sendSms(peerId: String, peerName: String, request: SmsSend?) {
        request ?: return
        val id = request.id.takeIf { it.isNotBlank() && it.length <= SmsThreads.MAX_ID } ?: return
        fun reply(error: String?) = send(peerId, Envelope(MessageType.PSMS_RESULT, smsResult = SmsResult(id, error == null, error)))
        fun refuse(why: String) {
            log.add(SmsLogEntry(peerId, peerName, request.address.take(32), clock(), request.body.length, ok = false, error = why))
            reply(why)
        }
        when {
            !SmsThreads.validAddress(request.address) -> return reply("Invalid recipient number")
            !SmsThreads.validBody(request.body) -> return reply("Invalid message text")
            !granted() -> return refuse(permissionText())
            !limiter.take(peerId) -> {
                val seconds = (limiter.waitMs(peerId) + 999) / 1000
                return refuse("Too many SMS from $peerName: at most ${SendRateLimit.MAX_PER_MINUTE} a minute. Try again in $seconds s.")
            }
        }
        val address = request.address.trim()
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val finished: (String?) -> Unit = { error ->
            if (done.compareAndSet(false, true)) {
                log.add(SmsLogEntry(peerId, peerName, address, clock(), request.body.length, ok = error == null, error = error))
                if (error == null) runCatching { onSent?.invoke(peerId, peerName, address) }
                reply(error)
            }
        }
        runCatching { data.sendSms(address, request.body, finished) }.onFailure { finished(it.message ?: "Failed to send SMS") }
    }

    /** New SMS here: to every phone that watches and is still allowed. */
    fun changed() {
        val targets = _watchers.value.filter { access.of(it).sms == true }
        if (targets.isEmpty() || !granted()) return
        val fresh = runCatching { data.readSms(PUSH_LIMIT, afterId = lastId) }.getOrDefault(emptyList())
        val newest = fresh.maxOfOrNull { it.id } ?: return
        lastId = newest
        targets.forEach { send(it, Envelope(MessageType.PSMS_NEW, sms = fresh)) }
    }

    /** The switch changed: a phone no longer allowed stops getting SMS, and is told. */
    fun accessChanged(peerId: String, after: PeerAccess) {
        if (after.sms == true || peerId !in _watchers.value) return
        unwatch(peerId)
        send(peerId, Envelope(MessageType.PSMS_WATCHING, error = "SMS from ${localName()} is turned off there."))
    }

    fun disconnected(peerId: String) = unwatch(peerId)

    fun forget(peerId: String) {
        unwatch(peerId)
        limiter.forget(peerId)
    }

    private fun unwatch(peerId: String) = _watchers.update { it - peerId }

    companion object {
        const val PUSH_LIMIT = 20
    }
}

/** Another phone's SMS as this phone (A) sees it, while connected. [threads] is null until they came. */
data class SmsView(
    val threads: List<SmsItem>? = null,
    val threadId: Long? = null,
    val messages: List<SmsItem> = emptyList(),
    val watching: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /** Answers to the SMS sent from here, by their ID. */
    val results: Map<String, SmsResult> = emptyMap(),
)

/**
 * The phone that reads (A): asks, and takes only what it asked for. A listing or watch answer needs the key it sent
 * and comes within [ANSWER_MS]; a send result needs an SMS ID sent from here within [SEND_MS]; new SMS only while
 * the other phone took this phone's watch. "Show new SMS here" is kept per phone in `phone_sms_follow.json`.
 */
class SmsRemote(directory: File?, private val clock: () -> Long = System::currentTimeMillis) {
    private val followFile = directory?.let { File(it, "phone_sms_follow.json") }
    private val _views = MutableStateFlow<Map<String, SmsView>>(emptyMap())
    val views: StateFlow<Map<String, SmsView>> = _views.asStateFlow()

    private val _incoming = MutableSharedFlow<Pair<String, List<SmsItem>>>(extraBufferCapacity = 16)
    /** New SMS from a watched phone: peer and the messages. */
    val incoming: SharedFlow<Pair<String, List<SmsItem>>> = _incoming.asSharedFlow()

    private val _follow = MutableStateFlow(loadFollow())
    /** Phones whose new SMS show here while connected. */
    val follow: StateFlow<Set<String>> = _follow.asStateFlow()

    private val keys = HashMap<String, HashMap<String, Long>>()
    private val sends = HashMap<String, HashMap<String, Long>>()
    private val watching = HashSet<String>()

    @Synchronized
    fun list(peerId: String, thread: Long? = null): Envelope {
        val key = expect(keys, peerId, ANSWER_MS)
        change(peerId) { it.copy(loading = true, error = null) }
        return Envelope(MessageType.PSMS_LIST, key = key, smsThread = thread)
    }

    /** The `psms.send` for this SMS, or null when the number or text can't go. */
    @Synchronized
    fun send(peerId: String, address: String, body: String): Envelope? {
        if (!SmsThreads.validAddress(address) || !SmsThreads.validBody(body)) return null
        val id = expect(sends, peerId, SEND_MS)
        return Envelope(MessageType.PSMS_SEND, smsSend = SmsSend(id, address.trim(), body))
    }

    @Synchronized
    fun watch(peerId: String): Envelope = Envelope(MessageType.PSMS_WATCH, key = expect(keys, peerId, ANSWER_MS))

    @Synchronized
    fun unwatch(peerId: String): Envelope {
        watching.remove(peerId)
        change(peerId) { it.copy(watching = false) }
        return Envelope(MessageType.PSMS_UNWATCH)
    }

    fun setFollow(peerId: String, on: Boolean) {
        _follow.update { if (on) it + peerId else it - peerId }
        saveFollow()
    }

    /** True when the message was one this phone waited for. */
    @Synchronized
    fun handle(peerId: String, envelope: Envelope): Boolean {
        when (envelope.type) {
            MessageType.PSMS_LISTING -> {
                if (!take(keys, peerId, envelope.key)) return false
                val items = envelope.sms.orEmpty()
                change(peerId) { view ->
                    when {
                        envelope.error != null -> view.copy(loading = false, error = envelope.error)
                        envelope.smsThread != null -> view.copy(loading = false, threadId = envelope.smsThread, messages = items.sortedBy { it.time })
                        else -> view.copy(loading = false, threads = items)
                    }
                }
            }
            MessageType.PSMS_RESULT -> {
                val result = envelope.smsResult ?: return false
                if (!take(sends, peerId, result.id)) return false
                change(peerId) { it.copy(results = (it.results + (result.id to result)).entries.toList().takeLast(MAX_RESULTS).associate { e -> e.key to e.value }) }
            }
            MessageType.PSMS_WATCHING -> {
                // Without a key: the other phone stopped sending them. That only ever stops something.
                if (envelope.key == null) {
                    if (envelope.error == null || !watching.remove(peerId)) return false
                } else {
                    if (!take(keys, peerId, envelope.key)) return false
                    if (envelope.error == null) watching.add(peerId) else watching.remove(peerId)
                }
                change(peerId) { it.copy(watching = peerId in watching, error = envelope.error ?: it.error) }
            }
            MessageType.PSMS_NEW -> {
                if (peerId !in watching) return false
                val fresh = envelope.sms.orEmpty().take(SmsShare.PUSH_LIMIT)
                if (fresh.isEmpty()) return false
                change(peerId) { view ->
                    val threads = view.threads?.let { SmsThreads.latest(fresh + it) }
                    val messages = if (view.threadId == null) view.messages
                    else (view.messages + fresh.filter { it.threadId == view.threadId }).distinctBy { it.id }.sortedBy { it.time }
                    view.copy(threads = threads, messages = messages)
                }
                _incoming.tryEmit(peerId to fresh.filter { !it.outgoing })
            }
            else -> return false
        }
        return true
    }

    fun isWatching(peerId: String): Boolean = synchronized(this) { peerId in watching }

    @Synchronized
    fun disconnected(peerId: String) {
        keys.remove(peerId)
        sends.remove(peerId)
        watching.remove(peerId)
        _views.update { it - peerId }
    }

    fun forget(peerId: String) {
        disconnected(peerId)
        if (peerId in _follow.value) setFollow(peerId, false)
    }

    private fun expect(map: HashMap<String, HashMap<String, Long>>, peerId: String, timeoutMs: Long): String {
        val id = UUID.randomUUID().toString()
        val waiting = map.getOrPut(peerId) { HashMap() }
        val now = clock()
        waiting.values.removeAll { it < now }
        while (waiting.size >= MAX_WAITING) waiting.remove(waiting.minByOrNull { it.value }!!.key)
        waiting[id] = now + timeoutMs
        return id
    }

    private fun take(map: HashMap<String, HashMap<String, Long>>, peerId: String, id: String?): Boolean {
        id ?: return false
        val until = map[peerId]?.remove(id) ?: return false
        return until >= clock()
    }

    private fun change(peerId: String, block: (SmsView) -> SmsView) = _views.update { it + (peerId to block(it[peerId] ?: SmsView())) }

    private fun loadFollow(): Set<String> = runCatching {
        protocolJson.decodeFromString(SetSerializer(String.serializer()), followFile!!.readText())
    }.getOrDefault(emptySet())

    private fun saveFollow() {
        val target = followFile ?: return
        runCatching { target.writeText(protocolJson.encodeToString(SetSerializer(String.serializer()), _follow.value)) }
    }

    companion object {
        const val ANSWER_MS = 20_000L
        /** The carrier can take a while to say it took an SMS. */
        const val SEND_MS = 120_000L
        const val MAX_WAITING = 32
        private const val MAX_RESULTS = 50
    }
}

/**
 * The SMS screen on A opens only after the screen lock (fingerprint, face, or PIN) is confirmed here, for one
 * session: until [lock] (the app left the screen, or the screen turned off), or [idleMs] without use.
 */
class SmsSession(private val idleMs: Long = IDLE_MS, private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile private var until = 0L

    fun unlock() {
        until = clock() + idleMs
    }

    val isUnlocked: Boolean get() = clock() < until

    /** Used now: the session lasts another [idleMs], if it's still open. */
    fun touch() {
        if (isUnlocked) unlock()
    }

    fun lock() {
        until = 0L
    }

    companion object {
        const val IDLE_MS = 5 * 60_000L
    }
}
