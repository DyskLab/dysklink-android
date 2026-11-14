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
        // Android may refuse to start it from a widget update; the widget then says to open DyskLink.
        val started = PhoneLinkService.start(context)
        render(context, PhoneLinkService.connectionManager.value?.peers?.value.orEmpty(), serviceOff = !started)
    }

    companion object {
        const val ACTION_LOCK = "id.phonelink.android.WIDGET_LOCK"

        fun render(context: Context, peers: List<Peer>, serviceOff: Boolean = false) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MacWidget::class.java))
            if (ids.isEmpty()) return
            val mac = peers.firstOrNull { it.isMac && it.isPaired && it.isConnected } ?: peers.firstOrNull { it.isMac && it.isPaired }
            val views = RemoteViews(context.packageName, R.layout.widget_mac)
            views.setTextViewText(R.id.widget_name, mac?.name ?: if (serviceOff) "DyskLink" else "No Mac paired")
            views.setTextViewText(
                R.id.widget_status,
                when {
                    serviceOff && mac?.isConnected != true -> "Open DyskLink to connect"
                    mac == null -> "Open DyskLink to pair"
                    !mac.isConnected -> "Not connected"
                    mac.maybeAsleep -> "Not answering, may be asleep"
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
            Widgets.ACTION -> {
                val kind = intent.getStringExtra(Widgets.EXTRA_KIND)
                // Wake Mac works even while the service isn't running, so it doesn't need the manager here.
                if (kind == WidgetShortcut.WakeMac.name) Widgets.wake(context, goAsync())
                else Widgets.handle(context, kind, intent.getStringExtra(Widgets.EXTRA_VALUE))
            }
        }
    }
}
