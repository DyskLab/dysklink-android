package id.phonelink.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import id.phonelink.android.MainActivity
import id.phonelink.android.R
import id.phonelink.android.core.MoveSummary
import id.phonelink.android.core.PhoneKinds
import id.phonelink.android.core.SmsItem
import id.phonelink.android.core.SmsSession
import id.phonelink.android.core.Transfer
import id.phonelink.android.core.TransferStatus

/** The SMS screen for another phone opens only after the screen lock, once per session (see [SmsSession]). */
object SmsLock {
    val session = SmsSession()
}

/**
 * Notifications for SMS through another phone, photo backup, and moves: "A sent an SMS using this phone" on B, new
 * SMS from B on A, a phone nearly full, and a finished move with Import contacts.
 */
object PhoneNotices {
    private const val CHANNEL_SMS_SENT = "phone_sms_sent"
    private const val CHANNEL_SMS = "phone_sms"
    private const val CHANNEL_STORAGE = "phone_storage"
    private const val CHANNEL_MOVE = "phone_move"

    private fun nameOf(peerId: String): String =
        PhoneLinkService.connectionManager.value?.peers?.value?.firstOrNull { it.id == peerId }?.name ?: "Another phone"

    private fun manager(context: Context, channel: String, title: String, importance: Int): NotificationManager =
        context.getSystemService(NotificationManager::class.java).also {
            it.createNotificationChannel(NotificationChannel(channel, title, importance))
        }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    /** On B, every time: another phone used this phone's SIM. Not hidden on the lock screen, on purpose. */
    fun smsSent(context: Context, peerId: String, peerName: String, address: String) {
        val manager = manager(context, CHANNEL_SMS_SENT, "SMS sent by other phones", NotificationManager.IMPORTANCE_DEFAULT)
        manager.notify(
            "smssent:$peerId:${System.currentTimeMillis()}", NotificationIds.PHONE_SMS_SENT,
            Notification.Builder(context, CHANNEL_SMS_SENT)
                .setSmallIcon(R.drawable.ic_logo_glyph)
                .setContentTitle("$peerName sent an SMS to $address using this phone")
                .setContentText("See every SMS it sent on the page for $peerName. Turn it off there if you didn't expect this.")
                .setStyle(Notification.BigTextStyle())
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** On A: new SMS on B, private on the lock screen. */
    fun smsReceived(context: Context, peerId: String, messages: List<SmsItem>) {
        if (messages.isEmpty()) return
        val manager = manager(context, CHANNEL_SMS, "SMS from other phones", NotificationManager.IMPORTANCE_HIGH)
        val name = nameOf(peerId)
        messages.groupBy { it.address }.forEach { (address, items) ->
            val text = items.sortedBy { it.time }.joinToString("\n") { it.body }
            val public = Notification.Builder(context, CHANNEL_SMS)
                .setSmallIcon(R.drawable.ic_logo_glyph)
                .setContentTitle("New SMS on $name")
                .build()
            manager.notify(
                "sms:$peerId:$address", NotificationIds.PHONE_SMS,
                Notification.Builder(context, CHANNEL_SMS)
                    .setSmallIcon(R.drawable.ic_logo_glyph)
                    .setContentTitle("$address, on $name")
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setPublicVersion(public)
                    .setContentIntent(openApp(context))
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    /** On the receiving phone: a backup or move stopped because less than 1 GB would be left. */
    fun lowSpace(context: Context, peerId: String, kind: String?) {
        val manager = manager(context, CHANNEL_STORAGE, "Storage for other phones", NotificationManager.IMPORTANCE_DEFAULT)
        val name = nameOf(peerId)
        val what = if (kind == PhoneKinds.PHOTO) "Photo backup from $name" else "The move from $name"
        manager.notify(
            "lowspace:$peerId", NotificationIds.PHONE_LOW_SPACE,
            Notification.Builder(context, CHANNEL_STORAGE)
                .setSmallIcon(R.drawable.ic_logo_glyph)
                .setContentTitle("$what stopped")
                .setContentText("Less than 1 GB would be free on this phone. Free up space, then it can go on.")
                .setStyle(Notification.BigTextStyle())
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** On A: photo backup to a phone stopped, with why. */
    fun backupStopped(context: Context, peerId: String, why: String) {
        val manager = manager(context, CHANNEL_STORAGE, "Storage for other phones", NotificationManager.IMPORTANCE_DEFAULT)
        manager.notify(
            "backup:$peerId", NotificationIds.PHOTO_BACKUP,
            Notification.Builder(context, CHANNEL_STORAGE)
                .setSmallIcon(R.drawable.ic_logo_glyph)
                .setContentTitle("Photo backup to ${nameOf(peerId)} stopped")
                .setContentText(why)
                .setStyle(Notification.BigTextStyle().bigText(why))
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** On the new phone: what came, and Import contacts, which opens the .vcf in the Contacts app. */
    fun moveFinished(context: Context, peerId: String, summary: MoveSummary, transfers: List<Transfer>) {
        val manager = manager(context, CHANNEL_MOVE, "Moves from other phones", NotificationManager.IMPORTANCE_HIGH)
        val name = nameOf(peerId)
        val text = listOfNotNull(
            "${summary.sent ?: 0} sent",
            summary.skipped?.takeIf { it > 0 }?.let { "$it already here" },
            summary.failed?.takeIf { it > 0 }?.let { "$it failed" },
        ).joinToString(", ") + ". Photos are in Pictures › DyskLink › $name, files in Download › DyskLink › $name."
        val builder = Notification.Builder(context, CHANNEL_MOVE)
            .setSmallIcon(R.drawable.ic_logo_glyph)
            .setContentTitle("Moved from $name")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
        val vcf = summary.contactsFile?.let { file ->
            transfers.firstOrNull { it.peerId == peerId && !it.outgoing && it.status == TransferStatus.Done && it.name == file && it.uri != null }
        }
        vcf?.uri?.let { uri ->
            val import = importContactsIntent(Uri.parse(uri))
            val tap = PendingIntent.getActivity(context, "import:$peerId".hashCode(), import, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(Notification.Action.Builder(null, "Import contacts", tap).build())
        }
        manager.notify("move:$peerId", NotificationIds.PHONE_MOVE, builder.build())
    }

    /** The system Contacts app imports the .vcf after the user confirms; DyskLink never writes contacts. */
    fun importContactsIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "text/x-vcard")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
}
