package id.phonelink.android.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import id.phonelink.android.ClipboardSendActivity
import id.phonelink.android.core.ControlEvent
import id.phonelink.android.core.CopyDetector
import id.phonelink.android.core.StrokeQueue

/**
 * Carries out control from the Mac. This service does not read or send screen content through
 * Accessibility. The focused field is only read to insert typed text. Only while automatic copy is on,
 * the labels of tapped buttons and toasts are compared against copy words. They are not stored or sent.
 */
class DyskAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val strokes = StrokeQueue()
    private val multi = id.phonelink.android.core.MultiTouch()
    private var multiStrokes = HashMap<Int, GestureDescription.StrokeDescription>()
    private val multiTick = Runnable { pumpMulti() }
    private var stroke: GestureDescription.StrokeDescription? = null
    private val releaseStroke = Runnable {
        strokes.release()
        pump()
    }

    private val sendClipboard = Runnable {
        runCatching { startActivity(ClipboardSendActivity.autoIntent(this)) }
    }

    override fun onServiceConnected() {
        current = this
        applyCopyDetection()
        PhoneLinkService.connectionManager.value?.phone?.refresh()
    }

    /** While automatic copy is off, the service doesn't receive tap or toast events at all. */
    private fun applyCopyDetection() {
        val on = PhoneLinkService.connectionManager.value?.clipboardAuto?.value == true
        val info = serviceInfo ?: return
        info.eventTypes = if (on) {
            AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
        } else {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        }
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = if (on) COPY_EVENT_TIMEOUT_MS else IDLE_EVENT_TIMEOUT_MS
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val copied = event ?: return
        val isTap = copied.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
        val isToast = copied.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            copied.parcelableData !is Notification
        if ((!isTap && !isToast) || copied.isPassword || copied.packageName == packageName) return
        val connections = PhoneLinkService.connectionManager.value ?: return
        if (!connections.clipboardAuto.value || !connections.clipboardEnabled.value) return
        val labels = copied.text + copied.contentDescription
        if (labels.none(CopyDetector::isCopyLabel)) return
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        // The app writes to the clipboard just after the tap, so the read waits a moment.
        main.removeCallbacks(sendClipboard)
        main.postDelayed(sendClipboard, COPY_READ_DELAY_MS)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        if (current === this) current = null
        main.removeCallbacks(releaseStroke)
        main.removeCallbacks(sendClipboard)
        PhoneLinkService.connectionManager.value?.phone?.refresh()
    }

    private fun perform(event: ControlEvent) {
        // While locked, taps from the Mac could enter a PIN, so all control is refused.
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            releaseStroke.run()
            return
        }
        when (event.kind) {
            "down", "drag", "up" -> {
                if (multi.isActive) { multi.releaseAll(); pumpMulti() }
                touch(event)
            }
            "tdown", "tmove", "tup" -> multiTouch(event)
            "text" -> insertText(event.text.orEmpty())
            "key" -> pressKey(event.key.orEmpty())
        }
    }

    @Suppress("DEPRECATION") // currentWindowMetrics only exists since Android 11.
    private fun screenSize(): Pair<Int, Int> {
        val manager = getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            DisplayMetrics().also { manager.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
        }
    }

    private fun touch(event: ControlEvent) {
        val x = event.x ?: return
        val y = event.y ?: return
        when (event.kind) {
            "down" -> strokes.down(x, y, SystemClock.uptimeMillis())
            "drag" -> strokes.drag(x, y)
            "up" -> strokes.up(x, y)
        }
        // A lost connection must not leave a finger pressed on the screen.
        main.removeCallbacks(releaseStroke)
        if (event.kind != "up") main.postDelayed(releaseStroke, STROKE_TIMEOUT_MS)
        pump()
    }

    private fun multiTouch(event: ControlEvent) {
        val x = event.x ?: return
        val y = event.y ?: return
        val finger = event.key?.toIntOrNull() ?: return
        if (strokes.isActive) strokes.release()
        when (event.kind) {
            "tdown" -> multi.down(finger, x, y)
            "tmove" -> multi.move(finger, x, y)
            "tup" -> multi.up(finger, x, y)
        }
        // A lost connection must not leave fingers on the screen.
        main.removeCallbacks(releaseMulti)
        main.postDelayed(releaseMulti, MULTI_TIMEOUT_MS)
        pumpMulti()
    }

    private val releaseMulti = Runnable {
        multi.releaseAll()
        pumpMulti()
    }

    private fun pumpMulti() {
        main.removeCallbacks(multiTick)
        val step = multi.next(SystemClock.uptimeMillis())
        if (step == null) {
            if (multi.isActive) main.postDelayed(multiTick, id.phonelink.android.core.MultiTouch.KEEP_ALIVE_MS)
            return
        }
        val generation = multi.generation
        val (width, height) = screenSize()
        val builder = GestureDescription.Builder()
        val next = HashMap<Int, GestureDescription.StrokeDescription>()
        val dispatched = runCatching {
            for (finger in step.fingers) {
                val path = Path()
                finger.points.forEachIndexed { index, (x, y) ->
                    val px = (x * width).coerceAtMost(width - 1f)
                    val py = (y * height).coerceAtMost(height - 1f)
                    if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                val previous = multiStrokes[finger.finger]
                val stroke = if (finger.first || previous == null) {
                    GestureDescription.StrokeDescription(path, 0, step.duration, !finger.last)
                } else {
                    previous.continueStroke(path, 0, step.duration, !finger.last)
                }
                if (!finger.last) next[finger.finger] = stroke
                builder.addStroke(stroke)
            }
            multiStrokes = next
            dispatchGesture(builder.build(), object : GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    if (generation != multi.generation) return
                    multi.completed()
                    pumpMulti()
                }
                override fun onCancelled(description: GestureDescription?) {
                    if (generation != multi.generation) return
                    multiStrokes = HashMap()
                    multi.cancelled()
                    pumpMulti()
                }
            }, main)
        }.getOrDefault(false)
        if (!dispatched) {
            multiStrokes = HashMap()
            multi.cancelled()
        }
    }

    private fun pump() {
        val segment = strokes.next(SystemClock.uptimeMillis()) ?: return
        val generation = strokes.generation
        val (width, height) = screenSize()
        val path = Path()
        segment.points.forEachIndexed { index, (x, y) ->
            val px = (x * width).coerceAtMost(width - 1f)
            val py = (y * height).coerceAtMost(height - 1f)
            if (index == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        val callback = object : GestureResultCallback() {
            override fun onCompleted(description: GestureDescription?) {
                if (generation != strokes.generation) return
                strokes.completed()
                pump()
            }

            override fun onCancelled(description: GestureDescription?) {
                if (generation != strokes.generation) return
                strokes.cancelled()
                pump()
            }
        }
        val dispatched = runCatching {
            val previous = stroke
            val next = if (segment.first || previous == null) {
                GestureDescription.StrokeDescription(path, 0, segment.duration, !segment.last)
            } else {
                previous.continueStroke(path, 0, segment.duration, !segment.last)
            }
            stroke = next
            dispatchGesture(GestureDescription.Builder().addStroke(next).build(), callback, main)
        }.getOrDefault(false)
        if (!dispatched) strokes.cancelled()
    }

    private fun focusedField(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable && !it.isPassword }

    private fun insertText(text: String) {
        val node = focusedField() ?: return
        val current = editableText(node)
        val (start, end) = selection(node, current)
        setField(node, current.replaceRange(start, end, text), start + text.length)
    }

    private fun pressKey(key: String) {
        when (key) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "backspace" -> focusedField()?.let { node ->
                val current = editableText(node)
                val (start, end) = selection(node, current)
                when {
                    start != end -> setField(node, current.removeRange(start, end), start)
                    start > 0 -> setField(node, current.removeRange(start - 1, start), start - 1)
                }
            }
            "enter" -> focusedField()?.let { node ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !node.isMultiLine) {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else {
                    insertText("\n")
                }
            }
            "left", "right" -> focusedField()?.let { node ->
                val current = editableText(node)
                val (start, end) = selection(node, current)
                val target = if (key == "left") (start - 1).coerceAtLeast(0) else (end + 1).coerceAtMost(current.length)
                select(node, target)
            }
        }
    }

    /** Empty fields report their hint as content, so the two must be told apart. */
    private fun editableText(node: AccessibilityNodeInfo): String =
        if (node.isShowingHintText) "" else node.text?.toString().orEmpty()

    private fun selection(node: AccessibilityNodeInfo, text: String): Pair<Int, Int> {
        val start = node.textSelectionStart
        val end = node.textSelectionEnd
        if (start < 0 || end < 0) return text.length to text.length
        return minOf(start, end).coerceAtMost(text.length) to maxOf(start, end).coerceAtMost(text.length)
    }

    private fun setField(node: AccessibilityNodeInfo, text: String, cursor: Int) {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        select(node, cursor)
    }

    private fun select(node: AccessibilityNodeInfo, position: Int) {
        val arguments = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, position)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, position)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
    }

    companion object {
        private const val STROKE_TIMEOUT_MS = 10_000L
        /** The Mac repeats held game keys every 1.5 seconds; silence for longer means it's gone. */
        private const val MULTI_TIMEOUT_MS = 5_000L
        private const val COPY_READ_DELAY_MS = 350L
        private const val COPY_EVENT_TIMEOUT_MS = 50L
        private const val IDLE_EVENT_TIMEOUT_MS = 500L

        @Volatile private var current: DyskAccessibilityService? = null

        val isRunning: Boolean get() = current != null

        fun refreshCopyDetection() {
            val service = current ?: return
            service.main.post { service.applyCopyDetection() }
        }

        fun handle(event: ControlEvent) {
            val service = current ?: return
            service.main.post { runCatching { service.perform(event) } }
        }
    }
}
