package id.phonelink.android.service

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import id.phonelink.android.MainActivity
import id.phonelink.android.R
import id.phonelink.android.core.AudioKind
import id.phonelink.android.core.CallEvent
import id.phonelink.android.core.MirrorFeed
import id.phonelink.android.core.NotificationItem

/**
 * Another phone's notifications and calls as notifications here, under a channel group per phone. They follow the
 * other phone: removed there, removed here; gone here when it disconnects. Swiping one away only hides it here;
 * Dismiss dismisses it there too.
 */
object MirrorNotifications {
    private var shown: Map<String, Map<String, NotificationItem>> = emptyMap()
    private var calls: Map<String, CallEvent> = emptyMap()
    private val silenced = HashSet<String>()
    /** Replies sent from here, shown under the notification until it changes. */
    private val history = HashMap<String, CharSequence>()

    private fun tag(peerId: String, key: String) = "m:$peerId:$key"
    private fun callTag(peerId: String) = "c:$peerId"
    private fun channel(peerId: String) = "mirror_$peerId"
    private fun callChannel(peerId: String) = "mirror_call_$peerId"

    private fun nameOf(peerId: String): String =
        PhoneLinkService.connectionManager.value?.peers?.value?.firstOrNull { it.id == peerId }?.name ?: "Another phone"

    @Synchronized
    fun sync(context: Context, feeds: Map<String, MirrorFeed>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        feeds.forEach { (peerId, feed) ->
            val before = shown[peerId].orEmpty()
            if (before.isEmpty() && calls[peerId] == null) ensureChannels(manager, peerId)
            feed.notifications.forEach { item -> if (before[item.key] != item) post(context, manager, peerId, item) }
            val now = feed.notifications.map { it.key }.toSet()
            before.keys.filter { it !in now }.forEach { key ->
                manager.cancel(tag(peerId, key), NotificationIds.MIRROR)
                history.remove(tag(peerId, key))
            }
            val call = feed.call
            if (call != calls[peerId]) showCall(context, manager, peerId, call)
        }
        (shown.keys + calls.keys).filter { it !in feeds }.forEach { peerId ->
            shown[peerId].orEmpty().keys.forEach { key -> manager.cancel(tag(peerId, key), NotificationIds.MIRROR) }
            manager.cancel(callTag(peerId), NotificationIds.MIRROR_CALL)
            silenced.remove(peerId)
        }
        shown = feeds.mapValues { (_, feed) -> feed.notifications.associateBy { it.key } }
        calls = feeds.mapNotNull { (peerId, feed) -> feed.call?.let { peerId to it } }.toMap()
    }

    private fun ensureChannels(manager: NotificationManager, peerId: String) {
        val name = nameOf(peerId)
        manager.createNotificationChannelGroup(NotificationChannelGroup("mirror_group_$peerId", "From $name"))
        manager.createNotificationChannel(
            NotificationChannel(channel(peerId), "Notifications from $name", NotificationManager.IMPORTANCE_HIGH).apply { group = "mirror_group_$peerId" },
        )
        manager.createNotificationChannel(
            NotificationChannel(callChannel(peerId), "Calls on $name", NotificationManager.IMPORTANCE_HIGH).apply { group = "mirror_group_$peerId" },
        )
    }

    private fun open(context: Context) =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    private fun post(context: Context, manager: NotificationManager, peerId: String, item: NotificationItem, quiet: Boolean = false) {
        val name = nameOf(peerId)
        val tag = tag(peerId, item.key)
        val hidden = NotificationCompat.Builder(context, channel(peerId))
            .setSmallIcon(R.drawable.ic_logo_glyph)
            .setContentTitle("Notification from $name")
            .setContentText(item.app)
            .build()
        val builder = NotificationCompat.Builder(context, channel(peerId))
            .setSmallIcon(R.drawable.ic_logo_glyph)
            .setContentTitle(item.title ?: item.app)
            .setContentText(item.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.text))
            .setSubText("${item.app} on $name")
            .setWhen(item.time)
            .setShowWhen(true)
            .setGroup("mirror:$peerId")
            .setOnlyAlertOnce(true)
            .setSilent(quiet || item.silent == true)
            // The lock screen shows only who it came from, as for the app's own private notifications.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(hidden)
            .setContentIntent(open(context))
            .setAutoCancel(false)
            .addAction(0, "Dismiss", PeerActionReceiver.dismiss(context, peerId, item.key))
        item.color?.let { builder.setColor(0xFF000000.toInt() or it) }
        item.avatar?.let { avatar ->
            runCatching { java.util.Base64.getDecoder().decode(avatar) }.getOrNull()
                ?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                ?.let(builder::setLargeIcon)
        }
        if (item.reply == true) {
            val input = RemoteInput.Builder(PeerActionReceiver.REPLY_TEXT).setLabel("Reply on $name").build()
            builder.addAction(
                NotificationCompat.Action.Builder(0, "Reply", PeerActionReceiver.reply(context, peerId, item.key)).addRemoteInput(input).build(),
            )
        }
        if (item.markRead == true) builder.addAction(0, "Mark as read", PeerActionReceiver.read(context, peerId, item.key))
        history[tag]?.let { builder.setRemoteInputHistory(arrayOf(it)) }
        manager.notify(tag, NotificationIds.MIRROR, builder.build())
    }

    /** A reply was sent from the notification: shown under it, quietly, so its spinner ends. */
    @Synchronized
    fun replied(context: Context, peerId: String, key: String, text: CharSequence) {
        history[tag(peerId, key)] = text
        val item = shown[peerId]?.get(key) ?: return
        post(context, context.getSystemService(NotificationManager::class.java), peerId, item, quiet = true)
    }

    private fun showCall(context: Context, manager: NotificationManager, peerId: String, call: CallEvent?) {
        val tag = callTag(peerId)
        if (call == null || (call.state != "ringing" && call.state != "missed")) {
            manager.cancel(tag, NotificationIds.MIRROR_CALL)
            silenced.remove(peerId)
            return
        }
        ensureChannels(manager, peerId)
        val name = nameOf(peerId)
        val who = call.number ?: "Unknown number"
        val ringing = call.state == "ringing"
        val builder = NotificationCompat.Builder(context, callChannel(peerId))
            .setSmallIcon(R.drawable.ic_logo_glyph)
            .setContentTitle(if (ringing) "Incoming call on $name" else "Missed call on $name")
            .setContentText(if (ringing) "$who. Answer it on $name." else who)
            .setCategory(if (ringing) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_MISSED_CALL)
            .setWhen(call.time)
            .setShowWhen(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open(context))
            .setOngoing(ringing)
            .setAutoCancel(!ringing)
            .setSilent(!ringing || peerId in silenced)
        if (ringing && peerId !in silenced) builder.addAction(0, "Silence", PeerActionReceiver.silence(context, peerId))
        manager.notify(tag, NotificationIds.MIRROR_CALL, builder.build())
    }

    /** The ringing alert keeps showing, without sound, until the call ends there. */
    @Synchronized
    fun silence(context: Context, peerId: String) {
        silenced.add(peerId)
        val call = calls[peerId] ?: return
        showCall(context, context.getSystemService(NotificationManager::class.java), peerId, call)
    }
}

/**
 * Buttons on DyskLink's notifications about other phones. Not exported: only DyskLink's own notifications reach it,
 * with the extras they were made with.
 */
class PeerActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val peerId = intent.getStringExtra(EXTRA_PEER) ?: return
        // Undo and the end time work even while the connection is down.
        when (intent.action) {
            ACTION_QUIET_UNDO, ACTION_QUIET_EXPIRE -> return QuietMode.undo(context)
            ACTION_QUIET_FORGET -> return QuietMode.forget(context)
        }
        val key = intent.getStringExtra(EXTRA_KEY)
        val connections = PhoneLinkService.connectionManager.value ?: return
        when (intent.action) {
            ACTION_DISMISS -> key?.let { connections.dismissMirrored(peerId, it) }
            ACTION_READ -> key?.let { connections.markMirroredRead(peerId, it) }
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY_TEXT)?.toString()?.trim()
                if (key != null && !text.isNullOrEmpty()) {
                    connections.replyMirrored(peerId, key, text)
                    MirrorNotifications.replied(context, peerId, key, text)
                }
            }
            ACTION_SILENCE -> MirrorNotifications.silence(context, peerId)
            ACTION_STOP_SOUND -> connections.stopHearing(peerId, AudioKind.Sound)
            ACTION_STOP_VOICE -> connections.stopHearing(peerId, AudioKind.Talk)
            ACTION_ANSWER -> {
                val request = id.phonelink.android.core.PeerAsk.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_KIND) }
                if (request != null) {
                    context.getSystemService(NotificationManager::class.java).cancel(askTag(peerId, request), NotificationIds.PHONE_ASK)
                    val allowed = when (intent.getStringExtra(EXTRA_ANSWER)) {
                        ALLOW -> true
                        NEVER -> false
                        else -> return
                    }
                    connections.setPhoneAccess(peerId) { id.phonelink.android.core.PhoneRules.withAllowed(it, request, allowed) }
                    if (allowed) RemoteAsks.needsSetup(context, peerId, request)
                    return
                }
                val kind = AudioKind.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_KIND) } ?: return
                context.getSystemService(NotificationManager::class.java).cancel(askTag(peerId, kind), NotificationIds.PHONE_ASK)
                val allowed = when (intent.getStringExtra(EXTRA_ANSWER)) {
                    ALLOW -> true
                    NEVER -> false
                    else -> return
                }
                connections.setPhoneAccess(peerId) { if (kind == AudioKind.Talk) it.copy(talk = allowed) else it.copy(sound = allowed) }
            }
        }
    }

    companion object {
        const val REPLY_TEXT = "reply"
        const val ALLOW = "allow"
        const val NOT_NOW = "not_now"
        const val NEVER = "never"
        private const val ACTION_DISMISS = "id.phonelink.android.MIRROR_DISMISS"
        private const val ACTION_READ = "id.phonelink.android.MIRROR_READ"
        private const val ACTION_REPLY = "id.phonelink.android.MIRROR_REPLY"
        private const val ACTION_SILENCE = "id.phonelink.android.MIRROR_SILENCE"
        private const val ACTION_STOP_SOUND = "id.phonelink.android.PEER_STOP_SOUND"
        private const val ACTION_STOP_VOICE = "id.phonelink.android.PEER_STOP_VOICE"
        private const val ACTION_ANSWER = "id.phonelink.android.PEER_ANSWER"
        private const val ACTION_QUIET_UNDO = "id.phonelink.android.QUIET_UNDO"
        private const val ACTION_QUIET_EXPIRE = "id.phonelink.android.QUIET_EXPIRE"
        private const val ACTION_QUIET_FORGET = "id.phonelink.android.QUIET_FORGET"
        private const val EXTRA_PEER = "peer"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_ANSWER = "answer"

        fun askTag(peerId: String, kind: AudioKind) = "ask:$peerId:${kind.name}"
        fun askTag(peerId: String, kind: id.phonelink.android.core.PeerAsk) = "ask:$peerId:${kind.name}"

        fun answer(context: Context, peerId: String, kind: id.phonelink.android.core.PeerAsk, answer: String) = pending(
            context,
            intent(context, ACTION_ANSWER, peerId).putExtra(EXTRA_KIND, kind.name).putExtra(EXTRA_ANSWER, answer),
            "a:$peerId:${kind.name}:$answer",
        )

        fun quietUndo(context: Context, peerId: String) = pending(context, intent(context, ACTION_QUIET_UNDO, peerId), "qu")

        fun quietForget(context: Context, peerId: String) = pending(context, intent(context, ACTION_QUIET_FORGET, peerId), "qf")

        /** One alarm at a time, whoever set it: the same request code, so a new one replaces it and cancel finds it. */
        fun quietExpire(context: Context, peerId: String) = pending(context, intent(context, ACTION_QUIET_EXPIRE, peerId), "qx")

        private fun intent(context: Context, action: String, peerId: String) =
            Intent(context, PeerActionReceiver::class.java).setAction(action).putExtra(EXTRA_PEER, peerId)

        /** One request code per action and notification, so their extras don't replace each other. */
        private fun pending(context: Context, intent: Intent, code: String, mutable: Boolean = false): PendingIntent =
            PendingIntent.getBroadcast(
                context, code.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE,
            )

        fun dismiss(context: Context, peerId: String, key: String) =
            pending(context, intent(context, ACTION_DISMISS, peerId).putExtra(EXTRA_KEY, key), "d:$peerId:$key")

        fun read(context: Context, peerId: String, key: String) =
            pending(context, intent(context, ACTION_READ, peerId).putExtra(EXTRA_KEY, key), "r:$peerId:$key")

        /** Mutable: Android adds the typed text to it. */
        fun reply(context: Context, peerId: String, key: String) =
            pending(context, intent(context, ACTION_REPLY, peerId).putExtra(EXTRA_KEY, key), "p:$peerId:$key", mutable = true)

        fun silence(context: Context, peerId: String) = pending(context, intent(context, ACTION_SILENCE, peerId), "s:$peerId")

        fun stopSound(context: Context, peerId: String) = pending(context, intent(context, ACTION_STOP_SOUND, peerId), "ss:$peerId")

        fun stopVoice(context: Context, peerId: String) = pending(context, intent(context, ACTION_STOP_VOICE, peerId), "sv:$peerId")

        fun answer(context: Context, peerId: String, kind: AudioKind, answer: String) = pending(
            context,
            intent(context, ACTION_ANSWER, peerId).putExtra(EXTRA_KIND, kind.name).putExtra(EXTRA_ANSWER, answer),
            "a:$peerId:${kind.name}:$answer",
        )
    }
}
