package id.phonelink.android.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The phone that forwards (B): sends its notifications and calls to each paired phone whose switch is on here
 * ([PeerAccess.notify], [PeerAccess.calls]), and acts on that phone's dismiss, reply, and "mark as read". The same
 * notifications as for a Mac: never the ones their app marks secret, ongoing ones, or DyskLink's own.
 */
class NotificationRelay(
    private val scope: CoroutineScope,
    private val access: PhoneAccess,
    private val data: PhoneData,
    /** Paired phones connected now that list `p2p-notify`. */
    private val phones: () -> List<String>,
    private val send: (peerId: String, envelope: Envelope) -> Unit,
) {
    /** What each phone was sent and still has: only those can be dismissed or answered from there. */
    private val sent = HashMap<String, LinkedHashSet<String>>()

    private fun granted(): Boolean = runCatching { data.isGranted(PhoneFeature.Notifications) }.getOrDefault(false)

    private fun allows(peerId: String, item: NotificationItem): Boolean {
        val rules = access.of(peerId)
        return rules.notify && (item.pkg ?: PhoneRules.keyPackage(item.key)) !in rules.muted
    }

    fun posted(item: NotificationItem) {
        if (!granted()) return
        phones().forEach { peerId ->
            if (!allows(peerId, item)) return@forEach
            remember(peerId, item.key)
            send(peerId, Envelope(MessageType.NOTIFICATION_POSTED, notification = item))
        }
    }

    fun removed(key: String) {
        phones().forEach { peerId ->
            if (forget(peerId, key)) send(peerId, Envelope(MessageType.NOTIFICATION_REMOVED, key = key))
        }
    }

    /** Whether any phone wants this phone's calls, so the call watcher should run. */
    fun wantsCalls(): Boolean = access.all.value.values.any { it.calls }

    fun call(event: CallEvent) {
        phones().forEach { peerId ->
            if (access.of(peerId).calls) send(peerId, Envelope(MessageType.CALL, call = event))
        }
    }

    /** A phone connected: it gets what shows now, without popping up again. */
    fun peerReady(peerId: String) {
        if (access.of(peerId).notify) sendActive(peerId)
    }

    fun disconnected(peerId: String) = synchronized(sent) { sent.remove(peerId) }

    /** The switch or the muted apps changed: that phone drops what it had and gets what it may have now. */
    fun changed(peerId: String, before: PeerAccess, after: PeerAccess) {
        val was = before.notify
        val now = after.notify
        val mutedChanged = before.muted != after.muted
        if (was && (!now || mutedChanged)) {
            synchronized(sent) { sent.remove(peerId) }
            send(peerId, Envelope(MessageType.NOTIFICATION_CLEARED))
        }
        if (now && (!was || mutedChanged) && peerId in phones()) sendActive(peerId)
    }

    fun forwarded(peerId: String, key: String?): Boolean =
        key != null && synchronized(sent) { sent[peerId]?.contains(key) == true }

    /** A dismiss, reply, or "mark as read" that [PhoneRules.toSource] let through. */
    fun handle(peerId: String, envelope: Envelope) {
        val key = envelope.key ?: return
        when (envelope.type) {
            MessageType.NOTIFICATION_DISMISS -> runCatching { data.dismissNotification(key) }
            MessageType.NOTIFICATION_READ -> runCatching { data.markNotificationRead(key) }
            MessageType.NOTIFICATION_REPLY -> {
                val text = envelope.text?.trim().orEmpty()
                val error = when {
                    text.isEmpty() || text.length > MAX_REPLY_CHARS -> "Invalid message text"
                    else -> runCatching { data.replyNotification(key, text) }.getOrElse { it.message ?: "Couldn't reply" }
                }
                send(peerId, Envelope(MessageType.NOTIFICATION_RESULT, key = key, error = error))
            }
        }
    }

    private fun sendActive(peerId: String) {
        if (!granted()) return
        scope.launch(Dispatchers.IO) {
            runCatching { data.activeNotifications() }.getOrDefault(emptyList()).forEach { item ->
                if (!allows(peerId, item)) return@forEach
                remember(peerId, item.key)
                send(peerId, Envelope(MessageType.NOTIFICATION_POSTED, notification = item.copy(silent = true)))
            }
        }
    }

    private fun remember(peerId: String, key: String) = synchronized(sent) {
        val keys = sent.getOrPut(peerId) { LinkedHashSet() }
        keys.remove(key)
        keys.add(key)
        while (keys.size > MAX_KEYS) keys.remove(keys.first())
    }

    private fun forget(peerId: String, key: String): Boolean = synchronized(sent) { sent[peerId]?.remove(key) == true }

    companion object {
        const val MAX_REPLY_CHARS = 2_000
        private const val MAX_KEYS = 500

        fun refusal(key: String?, localName: String) =
            Envelope(MessageType.NOTIFICATION_RESULT, key = key, error = "Notifications from $localName are turned off there.")
    }
}

/** What one phone forwards here: its notifications, newest first, and its last call event. */
data class MirrorFeed(val notifications: List<NotificationItem> = emptyList(), val call: CallEvent? = null)

/**
 * The phone that shows them (A): another phone's notifications and calls, kept only while it's connected.
 * Removed there, removed here.
 */
class NotificationMirror {
    private val _feeds = MutableStateFlow<Map<String, MirrorFeed>>(emptyMap())
    val feeds: StateFlow<Map<String, MirrorFeed>> = _feeds.asStateFlow()

    private val _results = MutableSharedFlow<Pair<String, Envelope>>(extraBufferCapacity = 16)
    /** Answers to replies sent from here: peer and `notification.result`. */
    val results: SharedFlow<Pair<String, Envelope>> = _results.asSharedFlow()

    fun handle(peerId: String, envelope: Envelope) {
        when (envelope.type) {
            MessageType.NOTIFICATION_POSTED -> {
                val item = envelope.notification?.takeIf { it.key.isNotBlank() && it.key.length <= MAX_KEY } ?: return
                change(peerId) { feed ->
                    feed.copy(notifications = (listOf(item) + feed.notifications.filter { it.key != item.key }).take(MAX_ITEMS))
                }
            }
            MessageType.NOTIFICATION_REMOVED -> {
                val key = envelope.key ?: return
                change(peerId) { feed -> feed.copy(notifications = feed.notifications.filter { it.key != key }) }
            }
            MessageType.NOTIFICATION_CLEARED -> change(peerId) { it.copy(notifications = emptyList()) }
            MessageType.CALL -> envelope.call?.let { event -> change(peerId) { it.copy(call = event) } }
            MessageType.NOTIFICATION_RESULT -> _results.tryEmit(peerId to envelope)
        }
    }

    /** Dropped here, for a dismiss sent from here or one the user swiped away. */
    fun remove(peerId: String, key: String) = change(peerId) { feed -> feed.copy(notifications = feed.notifications.filter { it.key != key }) }

    /** The call alert was handled here. */
    fun endCall(peerId: String) = change(peerId) { it.copy(call = null) }

    /** Disconnected or unpaired: nothing from that phone stays. */
    fun clear(peerId: String) = _feeds.update { it - peerId }

    private fun change(peerId: String, block: (MirrorFeed) -> MirrorFeed) = _feeds.update { all ->
        val next = block(all[peerId] ?: MirrorFeed())
        if (next.notifications.isEmpty() && next.call == null) all - peerId else all + (peerId to next)
    }

    private companion object {
        const val MAX_ITEMS = 200
        const val MAX_KEY = 1_024
    }
}
