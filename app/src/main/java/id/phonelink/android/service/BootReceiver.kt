package id.phonelink.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts PhoneLink again after the phone reboots or the app updates. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                PhoneLinkService.start(context)
                PrayerWeatherStore.schedule(context)
            }
            // Alarms are set in real time, so a clock or zone change moves the next prayer reminder.
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                PrayerWeatherStore.schedule(context)
                PrayerWidget.render(context)
            }
        }
    }
}
