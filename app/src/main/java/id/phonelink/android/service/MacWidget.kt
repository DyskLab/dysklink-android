package id.phonelink.android.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import id.phonelink.android.ClipboardSendActivity
import id.phonelink.android.MainActivity
import id.phonelink.android.R
import id.phonelink.android.core.Peer

/** A home screen widget with the Mac's battery, and buttons to send the clipboard and lock the Mac. */
class MacWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        PhoneLinkService.start(context)
        render(context, PhoneLinkService.connectionManager.value?.peers?.value.orEmpty())
    }

    companion object {
        const val ACTION_LOCK = "id.phonelink.android.WIDGET_LOCK"

        fun render(context: Context, peers: List<Peer>) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MacWidget::class.java))
            if (ids.isEmpty()) return
            val mac = peers.firstOrNull { it.isMac && it.isPaired && it.isConnected } ?: peers.firstOrNull { it.isMac && it.isPaired }
            val views = RemoteViews(context.packageName, R.layout.widget_mac)
            views.setTextViewText(R.id.widget_name, mac?.name ?: "No Mac paired")
            views.setTextViewText(
                R.id.widget_status,
                when {
                    mac == null -> "Open DyskLink to pair"
                    !mac.isConnected -> "Not connected"
                    else -> listOfNotNull(
                        mac.info?.batteryPercent?.let { "Battery $it%" + if (mac.info?.isCharging == true) ", charging" else "" },
                        mac.info?.keepAwake?.takeIf { it == "active" }?.let { "Keep Awake on" },
                    ).joinToString(" · ").ifEmpty { "Connected" }
                },
            )
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, 20, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            views.setOnClickPendingIntent(
                R.id.widget_clipboard,
                PendingIntent.getActivity(context, 21, Intent(context, ClipboardSendActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            views.setOnClickPendingIntent(
                R.id.widget_lock,
                PendingIntent.getBroadcast(
                    context, 22, Intent(context, WidgetActionReceiver::class.java).setAction(ACTION_LOCK), PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            manager.updateAppWidget(ids, views)
        }
    }
}

class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            MacWidget.ACTION_LOCK -> PhoneLinkService.connectionManager.value?.sendMacAction("lock")
            Widgets.ACTION -> Widgets.handle(context, intent.getStringExtra(Widgets.EXTRA_KIND), intent.getStringExtra(Widgets.EXTRA_VALUE))
        }
    }
}
