package id.phonelink.android.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import id.phonelink.android.core.TerminalEmulator
import id.phonelink.android.core.TerminalKeys
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws a [TerminalEmulator] and turns the keyboard into terminal input.
 * Drag scrolls back through earlier output, pinch changes the text size, and a tap brings up the keyboard.
 */
@SuppressLint("ViewConstructor")
class TerminalView(context: Context, val emulator: TerminalEmulator) : View(context) {
    /** Bytes for the shell, already encoded. */
    var onInput: (String) -> Unit = {}
    /** The grid size changed, in columns and rows. */
    var onGridSize: (Int, Int) -> Unit = { _, _ -> }
    var onLongPress: () -> Unit = {}
    /** Ctrl or Alt turned on from the extra keys row, used once by the next key. */
    var stickyCtrl = false
    var stickyAlt = false
    var onStickyUsed: () -> Unit = {}

    private val density = resources.displayMetrics.scaledDensity
    var fontSp = 12.5f
        set(value) {
            field = value.coerceIn(7f, 26f)
            measureCell()
            updateGrid(width, height)
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val fill = Paint()
    private var cellWidth = 1f
    private var cellHeight = 1f
    private var baseline = 0f
    /** How many rows up from the live screen the view is scrolled. */
    private var scrollRows = 0
    private var scrollRemainder = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        measureCell()
    }

    private fun measureCell() {
        paint.textSize = fontSp * density
        cellWidth = paint.measureText("M")
        val metrics = paint.fontMetrics
        cellHeight = (metrics.descent - metrics.ascent + metrics.leading).let { max(it, paint.textSize * 1.15f) }
        baseline = -metrics.ascent + (cellHeight - (metrics.descent - metrics.ascent)) / 2
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = updateGrid(w, h)

    private fun updateGrid(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val cols = (w / cellWidth).toInt().coerceAtLeast(10)
        val rows = (h / cellHeight).toInt().coerceAtLeast(4)
        if (cols != emulator.cols || rows != emulator.rows) {
            emulator.resize(cols, rows)
            onGridSize(cols, rows)
        }
    }

    /** New output arrived. Jumps back to the live screen, like a terminal on the Mac does. */
    fun refresh() {
        scrollRows = 0
        invalidate()
    }

    // Drawing

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(BACKGROUND)
        val rows = emulator.rows
        val first = emulator.scrollbackSize - scrollRows
        for (row in 0 until rows) {
            val index = first + row
            if (index < 0) continue
            drawLine(canvas, emulator.line(index), row * cellHeight)
        }
        if (scrollRows == 0 && emulator.cursorVisible && hasFocus()) {
            val x = emulator.cursorX * cellWidth
            val y = emulator.cursorY * cellHeight
            fill.color = CURSOR
            fill.alpha = 170
            canvas.drawRect(x, y, x + cellWidth, y + cellHeight, fill)
            fill.alpha = 255
        } else if (scrollRows == 0 && emulator.cursorVisible) {
            val x = emulator.cursorX * cellWidth
            val y = emulator.cursorY * cellHeight
            fill.color = CURSOR
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = density
            canvas.drawRect(x, y, x + cellWidth, y + cellHeight, fill)
            fill.style = Paint.Style.FILL
        }
        if (scrollRows > 0) {
            // A small marker that this is earlier output.
            paint.color = 0xFF5EF2C2.toInt()
            val label = "↑ $scrollRows lines up · new output jumps back"
            canvas.drawText(label, width - paint.measureText(label) - 8 * density, height - 8 * density, paint)
        }
    }

    private val run = StringBuilder()

    private fun drawLine(canvas: Canvas, line: TerminalEmulator.Line, y: Float) {
        val cols = minOf(line.size, emulator.cols)
        var x = 0
        while (x < cols) {
            // A run of cells with the same look is drawn in one go.
            val fg = line.fg[x]
            val bg = line.bg[x]
            val flags = line.flags[x].toInt()
            var end = x + 1
            while (end < cols && line.fg[end] == fg && line.bg[end] == bg && line.flags[end].toInt() == flags) end++
            val inverse = flags and TerminalEmulator.INVERSE != 0
            var foreground = color(fg, true, flags)
            var background = if (bg == TerminalEmulator.DEFAULT) BACKGROUND else color(bg, false, 0)
            if (inverse) foreground = background.also { background = foreground }
            val left = x * cellWidth
            val right = end * cellWidth
            if (background != BACKGROUND) {
                fill.color = background
                canvas.drawRect(left, y, right, y + cellHeight, fill)
            }
            if (flags and TerminalEmulator.HIDDEN == 0) {
                paint.color = foreground
                paint.isFakeBoldText = flags and TerminalEmulator.BOLD != 0
                paint.textSkewX = if (flags and TerminalEmulator.ITALIC != 0) -0.2f else 0f
                paint.isUnderlineText = flags and TerminalEmulator.UNDERLINE != 0
                paint.isStrikeThruText = flags and TerminalEmulator.STRIKE != 0
                // Each letter is placed in its own cell, so wide letters and odd fonts never drift.
                for (cell in x until end) {
                    val c = line.chars[cell]
                    if (c == TerminalEmulator.WIDE_TAIL || c == ' '.code) {
                        if (c == ' '.code && (paint.isUnderlineText || paint.isStrikeThruText)) {
                            canvas.drawText(" ", cell * cellWidth, y + baseline, paint)
                        }
                        continue
                    }
                    run.setLength(0)
                    run.appendCodePoint(c)
                    canvas.drawText(run, 0, run.length, cell * cellWidth, y + baseline, paint)
                }
            }
            x = end
        }
    }

    private fun color(value: Int, foreground: Boolean, flags: Int): Int {
        if (value == TerminalEmulator.DEFAULT) {
            return if (flags and TerminalEmulator.DIM != 0) DIM_TEXT else if (foreground) TEXT else BACKGROUND
        }
        if (TerminalEmulator.isRgb(value)) return value or 0xFF000000.toInt()
        // Bold text in one of the first 8 colors shows in the bright version, like most terminals.
        val index = if (foreground && flags and TerminalEmulator.BOLD != 0 && value < 8) value + 8 else value
        return PALETTE[index.coerceIn(0, 255)]
    }

    // Touch

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            showKeyboard()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollRemainder -= dy
            val step = (scrollRemainder / cellHeight).toInt()
            if (step != 0) {
                scrollRemainder -= step * cellHeight
                scrollRows = (scrollRows + step).coerceIn(0, emulator.scrollbackSize)
                invalidate()
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) = onLongPress()
    })

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            fontSp *= detector.scaleFactor
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        if (!scale.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    // Keyboard

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // No suggestions or autocorrect, so each letter arrives as it is typed.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                flushEditable()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                flushEditable()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // Some keyboards send this instead of the delete key.
                if (editable.isNullOrEmpty()) repeat(beforeLength.coerceAtLeast(1)) { sendKey(TerminalKeys.Key.Backspace) }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
                return true
            }

            private fun flushEditable() {
                val content = editable ?: return
                if (content.isEmpty()) return
                typeText(content.toString())
                content.clear()
            }
        }
    }

    fun typeText(text: String) {
        val newlines = text.replace('\n', '\r')
        val ctrl = stickyCtrl
        val alt = stickyAlt
        if (ctrl || alt) {
            stickyCtrl = false
            stickyAlt = false
            onStickyUsed()
        }
        onInput(TerminalKeys.text(newlines, ctrl, alt))
    }

    fun sendKey(key: TerminalKeys.Key, shift: Boolean = false) {
        val ctrl = stickyCtrl
        val alt = stickyAlt
        if (ctrl || alt) {
            stickyCtrl = false
            stickyAlt = false
            onStickyUsed()
        }
        onInput(TerminalKeys.key(key, emulator.appCursorKeys, shift = shift, alt = alt, ctrl = ctrl))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val key = when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TerminalKeys.Key.Enter
            KeyEvent.KEYCODE_DEL -> TerminalKeys.Key.Backspace
            KeyEvent.KEYCODE_FORWARD_DEL -> TerminalKeys.Key.Delete
            KeyEvent.KEYCODE_TAB -> TerminalKeys.Key.Tab
            KeyEvent.KEYCODE_ESCAPE -> TerminalKeys.Key.Escape
            KeyEvent.KEYCODE_DPAD_UP -> TerminalKeys.Key.Up
            KeyEvent.KEYCODE_DPAD_DOWN -> TerminalKeys.Key.Down
            KeyEvent.KEYCODE_DPAD_LEFT -> TerminalKeys.Key.Left
            KeyEvent.KEYCODE_DPAD_RIGHT -> TerminalKeys.Key.Right
            KeyEvent.KEYCODE_MOVE_HOME -> TerminalKeys.Key.Home
            KeyEvent.KEYCODE_MOVE_END -> TerminalKeys.Key.End
            KeyEvent.KEYCODE_PAGE_UP -> TerminalKeys.Key.PageUp
            KeyEvent.KEYCODE_PAGE_DOWN -> TerminalKeys.Key.PageDown
            KeyEvent.KEYCODE_INSERT -> TerminalKeys.Key.Insert
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> TerminalKeys.Key.entries[TerminalKeys.Key.F1.ordinal + keyCode - KeyEvent.KEYCODE_F1]
            KeyEvent.KEYCODE_BACK -> return super.onKeyDown(keyCode, event)
            else -> null
        }
        if (key != null) {
            if (event.isCtrlPressed) stickyCtrl = true
            if (event.isAltPressed) stickyAlt = true
            sendKey(key, shift = event.isShiftPressed)
            return true
        }
        // A hardware keyboard: letters with Ctrl or Alt held.
        val unicode = event.getUnicodeChar(event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv())
        if (unicode > 0) {
            if (event.isCtrlPressed) stickyCtrl = true
            if (event.isAltPressed) stickyAlt = true
            typeText(String(Character.toChars(unicode)))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val BACKGROUND = 0xFF090D13.toInt()
        private const val TEXT = 0xFFE6EAF0.toInt()
        private const val DIM_TEXT = 0xFF8A94A4.toInt()
        private const val CURSOR = 0xFF5EF2C2.toInt()

        /** The 16 base colors tuned for the dark background, then the standard 6×6×6 cube and grays. */
        val PALETTE: IntArray = IntArray(256).also { colors ->
            val base = intArrayOf(
                0x1D2430, 0xFF6F7D, 0x5EF2C2, 0xFFC35C, 0x5CA8FF, 0xC68BFF, 0x4CC9F0, 0xD5DAE2,
                0x5B6577, 0xFF8F9A, 0x8DF7D6, 0xFFD58A, 0x8CC2FF, 0xD8ADFF, 0x7FDDF5, 0xFFFFFF,
            )
            for (i in 0 until 16) colors[i] = base[i] or 0xFF000000.toInt()
            val steps = intArrayOf(0, 95, 135, 175, 215, 255)
            for (i in 0 until 216) {
                colors[16 + i] = (0xFF shl 24) or (steps[i / 36] shl 16) or (steps[(i / 6) % 6] shl 8) or steps[i % 6]
            }
            for (i in 0 until 24) {
                val v = 8 + i * 10
                colors[232 + i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }

        fun cellsFor(widthPx: Int, cellPx: Float) = (widthPx / cellPx).roundToInt()
    }
}
