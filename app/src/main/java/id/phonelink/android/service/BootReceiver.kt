package id.phonelink.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts PhoneLink again after the phone reboots or the app updates. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> PhoneLinkService.start(context)
        }
    }
}
