package id.phonelink.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import id.phonelink.android.service.DeviceActionsImpl
import id.phonelink.android.service.PhoneLinkService

/**
 * Android only allows clipboard reads while the app has focus. This transparent screen appears
 * briefly from the notification button, reads the clipboard, sends it to the Mac, then closes.
 * The system can report focus slightly before it allows the read, so an empty result is retried.
 * When started by automatic copy, it stays silent unless sending fails.
 */
class ClipboardSendActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val giveUp = Runnable { finishWith("Couldn't read the clipboard. Try again.") }
    private var attempts = 0
    private var isDone = false
    private val isAuto get() = intent?.getBooleanExtra(EXTRA_AUTO, false) == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        main.postDelayed(giveUp, FOCUS_TIMEOUT_MS)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && attempts == 0) read()
    }

    private fun read() {
        attempts++
        val text = runCatching { DeviceActionsImpl(this).readClipboard() }.getOrNull()
        if (text == null && attempts < MAX_ATTEMPTS) {
            main.postDelayed(::read, RETRY_MS)
            return
        }
        send(text)
    }

    private fun send(text: String?) {
        val connections = PhoneLinkService.connectionManager.value
        when {
            connections == null || connections.peers.value.none { it.isPaired } -> finishWith("No paired Mac")
            connections.clipboardEnabled.value != true -> finishWith("Automatic clipboard is turned off")
            text == null -> finishWith("No text to send. The clipboard is empty or marked private.")
            else -> {
                val context = applicationContext
                val silent = isAuto
                connections.shareClipboard(text, force = !silent) { sent -> if (!sent || !silent) context.report(sent) }
                finishWith(null)
            }
        }
    }

    private fun Context.report(sent: Boolean) {
        val message = if (sent) "Clipboard sent to Mac" else "Couldn't send the clipboard to the Mac"
        Handler(Looper.getMainLooper()).post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun finishWith(message: String?) {
        if (isDone) return
        isDone = true
        if (!isAuto) message?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        finish()
    }

    companion object {
        private const val EXTRA_AUTO = "auto"
        private const val MAX_ATTEMPTS = 6
        private const val RETRY_MS = 120L
        private const val FOCUS_TIMEOUT_MS = 2_500L

        fun autoIntent(context: Context): Intent = Intent(context, ClipboardSendActivity::class.java)
            .putExtra(EXTRA_AUTO, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
