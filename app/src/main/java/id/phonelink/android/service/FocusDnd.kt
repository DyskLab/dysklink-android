package id.phonelink.android.service

import android.app.NotificationManager
import android.content.Context
import id.phonelink.android.core.NotchSnapshot
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Turns on Do Not Disturb (priority only) while a Pomodoro focus round runs on a paired Mac, and turns it off
 * again when the round ends. It only turns off what it turned on, so a Do Not Disturb the user set stays.
 * Off by default, and it needs Do Not Disturb access from Settings.
 */
object FocusDnd {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private var turnedOn = false
    private var loaded = false

    private fun flag(context: Context) = File(context.filesDir, "focus_dnd")

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        _enabled.value = flag(context).exists()
    }

    fun hasAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun setEnabled(context: Context, on: Boolean) {
        _enabled.value = on
        if (on) flag(context).createNewFile() else flag(context).delete()
        if (!on) release(context)
    }

    fun sync(context: Context, notch: Map<String, NotchSnapshot>) {
        val focusing = notch.values.any { it.state.focus.running && it.state.focus.phase == "focus" }
        if (focusing && _enabled.value) hold(context) else release(context)
    }

    private fun hold(context: Context) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (turnedOn || !notifications.isNotificationPolicyAccessGranted) return
        if (notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        runCatching { notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY) }
            .onSuccess { turnedOn = true }
    }

    private fun release(context: Context) {
        if (!turnedOn) return
        turnedOn = false
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (notifications.isNotificationPolicyAccessGranted &&
            notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY
        ) {
            runCatching { notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
        }
    }
}
