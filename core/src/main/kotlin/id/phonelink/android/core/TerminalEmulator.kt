package id.phonelink.android.core

/**
 * An xterm-style screen: turns the bytes a shell prints into rows of styled cells.
 * It covers what shells, vim, nano, less, top, htop, and tmux use; mouse reporting is left out.
 */
class TerminalEmulator(cols: Int, rows: Int, private val maxScrollback: Int = 3000) {
    class Line(cols: Int) {
        var chars = IntArray(cols) { ' '.code }
        var fg = IntArray(cols) { DEFAULT }
        var bg = IntArray(cols) { DEFAULT }
        var flags = ByteArray(cols)
        /** The text went on to the next line by itself, so copying joins them. */
        var wrapped = false

        val size get() = chars.size

        fun resize(cols: Int) {
            if (cols == chars.size) return
            val old = chars.size
            chars = chars.copyOf(cols).also { if (cols > old) it.fill(' '.code, old, cols) }
            fg = fg.copyOf(cols).also { if (cols > old) it.fill(DEFAULT, old, cols) }
            bg = bg.copyOf(cols).also { if (cols > old) it.fill(DEFAULT, old, cols) }
            flags = flags.copyOf(cols)
        }

        fun clear(from: Int, to: Int, background: Int) {
            for (x in from.coerceAtLeast(0) until to.coerceAtMost(chars.size)) {
                chars[x] = ' '.code
                fg[x] = DEFAULT
                bg[x] = background
                flags[x] = 0
            }
        }

        fun text(): String {
            val out = StringBuilder()
            for (x in chars.indices) if (chars[x] != WIDE_TAIL) out.appendCodePoint(chars[x])
            return out.toString().trimEnd()
        }
    }

    var cols = cols.coerceAtLeast(2)
        private set
    var rows = rows.coerceAtLeast(2)
        private set

    private var main = MutableList(this.rows) { Line(this.cols) }
    private var alt = MutableList(this.rows) { Line(this.cols) }
    private val scrollback = ArrayDeque<Line>()
    var isAltScreen = false
        private set
    private val screen get() = if (isAltScreen) alt else main

    var cursorX = 0
        private set
    var cursorY = 0
        private set
    var cursorVisible = true
        private set
    var appCursorKeys = false
        private set
    var bracketedPaste = false
        private set
    var title = ""
        private set
    /** Goes up each time the screen changes, so the view knows to redraw. */
    var version = 0L
        private set

    /** Replies the shell asked for, such as the cursor position. They go back to the Mac as typed input. */
    var onReply: (String) -> Unit = {}
    var onBell: () -> Unit = {}

    private var fg = DEFAULT
    private var bg = DEFAULT
    private var flags = 0
    private var wrapPending = false
    private var autoWrap = true
    private var originMode = false
    private var insertMode = false
    private var top = 0
    private var bottom = this.rows - 1
    private var lineDrawing = false
    private var lastPrinted = ' '.code
    private var tabStops = BooleanArray(this.cols) { it % 8 == 0 }

    private data class Saved(val x: Int, val y: Int, val fg: Int, val bg: Int, val flags: Int, val origin: Boolean, val lineDrawing: Boolean)
    private var saved: Saved? = null
    private var savedMain: Saved? = null

    // Parser
    private enum class State { Ground, Escape, EscapeCharset, Csi, Osc, OscEscape, Dcs, DcsEscape }
    private var state = State.Ground
    private val params = StringBuilder()
    private var csiPrivate = 0.toChar()
    private var csiIntermediate = 0.toChar()
    private val osc = StringBuilder()
    private var utf8Need = 0
    private var utf8Value = 0

    val scrollbackSize get() = scrollback.size

    /** Row [index] counting the scrollback first, so 0 is the oldest line kept. */
    fun line(index: Int): Line = if (index < scrollback.size) scrollback.elementAt(index) else screen[index - scrollback.size]

    fun screenLine(row: Int): Line = screen[row]

    fun screenText(): String = screen.joinToString("\n") { it.text() }.trimEnd()

    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        for (i in 0 until length) {
            val b = bytes[i].toInt() and 0xFF
            if (utf8Need > 0) {
                if (b and 0xC0 == 0x80) {
                    utf8Value = (utf8Value shl 6) or (b and 0x3F)
                    if (--utf8Need == 0) accept(utf8Value)
                    continue
                }
                utf8Need = 0
                accept(0xFFFD)
            }
            when {
                b < 0x80 -> accept(b)
                b and 0xE0 == 0xC0 -> { utf8Need = 1; utf8Value = b and 0x1F }
                b and 0xF0 == 0xE0 -> { utf8Need = 2; utf8Value = b and 0x0F }
                b and 0xF8 == 0xF0 -> { utf8Need = 3; utf8Value = b and 0x07 }
                else -> accept(0xFFFD)
            }
        }
        version++
    }

    fun feed(text: String) = feed(text.toByteArray())

    private fun accept(c: Int) {
        when (state) {
            State.Ground -> ground(c)
            State.Escape -> escape(c)
            State.EscapeCharset -> {
                lineDrawing = c == '0'.code && pendingCharsetIsG0
                state = State.Ground
            }
            State.Csi -> csi(c)
            State.Osc -> when (c) {
                0x07 -> endOsc()
                0x1B -> state = State.OscEscape
                else -> if (osc.length < 4096) osc.appendCodePoint(c)
            }
            State.OscEscape -> if (c == '\\'.code) endOsc() else { state = State.Osc; osc.appendCodePoint(c) }
            State.Dcs -> if (c == 0x1B) state = State.DcsEscape else if (c == 0x07) state = State.Ground
            State.DcsEscape -> state = if (c == '\\'.code) State.Ground else State.Dcs
        }
    }

    private var pendingCharsetIsG0 = true

    private fun ground(c: Int) {
        when (c) {
            0x1B -> state = State.Escape
            0x07 -> onBell()
            0x08 -> { if (cursorX > 0) cursorX--; wrapPending = false }
            0x09 -> tab(1)
            0x0A, 0x0B, 0x0C -> index()
            0x0D -> { cursorX = 0; wrapPending = false }
            0x0E, 0x0F, 0x00 -> Unit
            in 0x01..0x1F, 0x7F -> Unit
            else -> print(c)
        }
    }

    private fun escape(c: Int) {
        state = State.Ground
        when (c.toChar()) {
            '[' -> { params.clear(); csiPrivate = 0.toChar(); csiIntermediate = 0.toChar(); state = State.Csi }
            ']' -> { osc.clear(); state = State.Osc }
            'P', 'X', '^', '_' -> state = State.Dcs
            '(' -> { pendingCharsetIsG0 = true; state = State.EscapeCharset }
            ')', '*', '+' -> { pendingCharsetIsG0 = false; state = State.EscapeCharset }
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> index()
            'E' -> { cursorX = 0; index() }
            'M' -> reverseIndex()
            'H' -> if (cursorX < cols) tabStops[cursorX] = true
            'c' -> reset()
            '=', '>' -> Unit
            else -> Unit
        }
    }

    private fun endOsc() {
        state = State.Ground
        val text = osc.toString()
        val split = text.indexOf(';')
        if (split > 0) {
            val code = text.substring(0, split)
            if (code == "0" || code == "2") title = text.substring(split + 1).take(200)
        }
    }

    private fun csi(c: Int) {
        val ch = c.toChar()
        when {
            ch in '0'..'9' || ch == ';' || ch == ':' -> if (params.length < 256) params.append(ch)
            ch == '?' || ch == '>' || ch == '=' || ch == '<' -> csiPrivate = ch
            ch in ' '..'/' -> csiIntermediate = ch
            c in 0x40..0x7E -> { state = State.Ground; runCsi(ch) }
            c == 0x1B -> state = State.Escape
            c < 0x20 -> ground(c)
            else -> state = State.Ground
        }
    }

    private fun param(list: List<Int>, index: Int, default: Int): Int = list.getOrNull(index)?.takeIf { it > 0 } ?: default

    private fun runCsi(final: Char) {
        val list = if (params.isEmpty()) emptyList() else params.split(';').map { it.substringBefore(':').toIntOrNull() ?: 0 }
        val n = param(list, 0, 1)
        if (csiIntermediate != 0.toChar()) return // Cursor shape and the like.
        if (csiPrivate == '?' && (final == 'h' || final == 'l')) return privateMode(list, final == 'h')
        if (csiPrivate == '>' || csiPrivate == '=' || csiPrivate == '<') {
            if (final == 'c') onReply("\u001b[>0;0;0c")
            return
        }
        when (final) {
            '@' -> insertChars(n)
            'A' -> moveTo(cursorX, cursorY - n, clampToRegion = true)
            'B', 'e' -> moveTo(cursorX, cursorY + n, clampToRegion = true)
            'C', 'a' -> moveTo(cursorX + n, cursorY)
            'D' -> moveTo(cursorX - n, cursorY)
            'E' -> moveTo(0, cursorY + n, clampToRegion = true)
            'F' -> moveTo(0, cursorY - n, clampToRegion = true)
            'G', '`' -> moveTo(n - 1, cursorY)
            'H', 'f' -> {
                val row = param(list, 0, 1) - 1 + if (originMode) top else 0
                moveTo(param(list, 1, 1) - 1, row)
            }
            'I' -> tab(n)
            'Z' -> repeat(n) { var x = cursorX - 1; while (x > 0 && !tabStops[x]) x--; cursorX = x.coerceAtLeast(0) }
            'J' -> eraseDisplay(list.getOrNull(0) ?: 0)
            'K' -> eraseLine(list.getOrNull(0) ?: 0)
            'L' -> if (cursorY in top..bottom) { scrollDown(n, cursorY, bottom); cursorX = 0 }
            'M' -> if (cursorY in top..bottom) { scrollUp(n, cursorY, bottom); cursorX = 0 }
            'P' -> deleteChars(n)
            'S' -> scrollUp(n, top, bottom)
            'T' -> scrollDown(n, top, bottom)
            'X' -> screen[cursorY].clear(cursorX, cursorX + n, bg)
            'b' -> repeat(n.coerceAtMost(cols * rows)) { print(lastPrinted) }
            'c' -> onReply("\u001b[?62;22c")
            'd' -> moveTo(cursorX, param(list, 0, 1) - 1 + if (originMode) top else 0)
            'g' -> when (list.getOrNull(0) ?: 0) {
                0 -> if (cursorX < cols) tabStops[cursorX] = false
                3 -> tabStops.fill(false)
            }
            'h', 'l' -> if ((list.getOrNull(0) ?: 0) == 4) insertMode = final == 'h'
            'm' -> sgr(params.toString())
            'n' -> when (list.getOrNull(0) ?: 0) {
                5 -> onReply("\u001b[0n")
                6 -> onReply("\u001b[${cursorY + 1 - if (originMode) top else 0};${cursorX + 1}R")
            }
            'r' -> {
                val newTop = param(list, 0, 1) - 1
                val newBottom = param(list, 1, rows) - 1
                if (newTop < newBottom && newBottom < rows) {
                    top = newTop
                    bottom = newBottom
                    moveTo(0, if (originMode) top else 0)
                }
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            else -> Unit
        }
    }

    private fun privateMode(list: List<Int>, on: Boolean) {
        for (mode in list) when (mode) {
            1 -> appCursorKeys = on
            6 -> { originMode = on; moveTo(0, if (on) top else 0) }
            7 -> autoWrap = on
            25 -> cursorVisible = on
            2004 -> bracketedPaste = on
            47, 1047 -> switchScreen(on, clear = mode == 1047 && !on)
            1048 -> if (on) saveCursor() else restoreCursor()
            1049 -> {
                if (on) {
                    savedMain = Saved(cursorX, cursorY, fg, bg, flags, originMode, lineDrawing)
                    switchScreen(true, clear = true)
                } else {
                    switchScreen(false, clear = false)
                    savedMain?.let { restore(it) }
                }
            }
        }
    }

    private fun switchScreen(toAlt: Boolean, clear: Boolean) {
        if (toAlt == isAltScreen) return
        isAltScreen = toAlt
        if (toAlt) alt.forEach { it.clear(0, cols, DEFAULT) }
        if (clear && !toAlt) alt.forEach { it.clear(0, cols, DEFAULT) }
        top = 0
        bottom = rows - 1
        wrapPending = false
    }

    private fun sgr(text: String) {
        if (text.isEmpty()) return resetStyle()
        // 38:2::r:g:b and 38;2;r;g;b both mean a true color.
        val parts = text.split(';')
        var i = 0
        val values = ArrayList<List<Int?>>()
        for (part in parts) values += part.split(':').map { it.toIntOrNull() }
        while (i < values.size) {
            val group = values[i]
            val code = group.firstOrNull() ?: 0
            when (code) {
                0 -> resetStyle()
                1 -> flags = flags or BOLD
                2 -> flags = flags or DIM
                3 -> flags = flags or ITALIC
                4 -> flags = flags or UNDERLINE
                7 -> flags = flags or INVERSE
                8 -> flags = flags or HIDDEN
                9 -> flags = flags or STRIKE
                21, 22 -> flags = flags and (BOLD or DIM).inv()
                23 -> flags = flags and ITALIC.inv()
                24 -> flags = flags and UNDERLINE.inv()
                27 -> flags = flags and INVERSE.inv()
                28 -> flags = flags and HIDDEN.inv()
                29 -> flags = flags and STRIKE.inv()
                in 30..37 -> fg = code - 30
                39 -> fg = DEFAULT
                in 40..47 -> bg = code - 40
                49 -> bg = DEFAULT
                in 90..97 -> fg = code - 90 + 8
                in 100..107 -> bg = code - 100 + 8
                38, 48 -> {
                    val color: Int?
                    if (group.size > 1) {
                        // Colon form, all in one group.
                        val rest = group.drop(1)
                        color = when (rest.firstOrNull()) {
                            5 -> rest.getOrNull(1)?.coerceIn(0, 255)
                            2 -> rest.drop(1).takeLast(3).takeIf { it.size == 3 && it.all { v -> v != null } }
                                ?.let { rgb(it[0]!!, it[1]!!, it[2]!!) }
                            else -> null
                        }
                    } else {
                        val kind = values.getOrNull(i + 1)?.firstOrNull()
                        color = when (kind) {
                            5 -> values.getOrNull(i + 2)?.firstOrNull()?.coerceIn(0, 255).also { i += 2 }
                            2 -> {
                                val r = values.getOrNull(i + 2)?.firstOrNull() ?: 0
                                val g = values.getOrNull(i + 3)?.firstOrNull() ?: 0
                                val b = values.getOrNull(i + 4)?.firstOrNull() ?: 0
                                i += 4
                                rgb(r, g, b)
                            }
                            else -> null
                        }
                    }
                    if (color != null) if (code == 38) fg = color else bg = color
                }
            }
            i++
        }
    }

    private fun resetStyle() {
        fg = DEFAULT
        bg = DEFAULT
        flags = 0
    }

    private fun print(raw: Int) {
        var c = raw
        if (lineDrawing && c in 0x5F..0x7E) c = LINE_DRAWING[c - 0x5F]
        val width = charWidth(c)
        if (width == 0) return
        lastPrinted = c
        if (wrapPending) {
            if (autoWrap) {
                screen[cursorY].wrapped = true
                cursorX = 0
                index()
            }
            wrapPending = false
        }
        if (width == 2 && cursorX == cols - 1) {
            // A wide letter doesn't fit in the last cell, so it starts on the next line.
            screen[cursorY].clear(cursorX, cols, bg)
            if (autoWrap) {
                screen[cursorY].wrapped = true
                cursorX = 0
                index()
            } else {
                return
            }
        }
        val line = screen[cursorY]
        if (insertMode) shiftRight(line, cursorX, width)
        put(line, cursorX, c)
        if (width == 2 && cursorX + 1 < cols) put(line, cursorX + 1, WIDE_TAIL)
        if (cursorX + width >= cols) {
            cursorX = cols - 1
            wrapPending = true
        } else {
            cursorX += width
        }
    }

    private fun put(line: Line, x: Int, c: Int) {
        line.chars[x] = c
        line.fg[x] = fg
        line.bg[x] = bg
        line.flags[x] = flags.toByte()
    }

    private fun shiftRight(line: Line, from: Int, count: Int) {
        for (x in cols - 1 downTo from + count) {
            line.chars[x] = line.chars[x - count]
            line.fg[x] = line.fg[x - count]
            line.bg[x] = line.bg[x - count]
            line.flags[x] = line.flags[x - count]
        }
    }

    private fun insertChars(n: Int) {
        val line = screen[cursorY]
        val count = n.coerceAtMost(cols - cursorX)
        shiftRight(line, cursorX, count)
        line.clear(cursorX, cursorX + count, bg)
        wrapPending = false
    }

    private fun deleteChars(n: Int) {
        val line = screen[cursorY]
        val count = n.coerceAtMost(cols - cursorX)
        for (x in cursorX until cols - count) {
            line.chars[x] = line.chars[x + count]
            line.fg[x] = line.fg[x + count]
            line.bg[x] = line.bg[x + count]
            line.flags[x] = line.flags[x + count]
        }
        line.clear(cols - count, cols, bg)
        wrapPending = false
    }

    private fun tab(n: Int) {
        repeat(n) {
            var x = cursorX + 1
            while (x < cols - 1 && !tabStops[x]) x++
            cursorX = x.coerceAtMost(cols - 1)
        }
        wrapPending = false
    }

    private fun moveTo(x: Int, y: Int, clampToRegion: Boolean = false) {
        cursorX = x.coerceIn(0, cols - 1)
        val (low, high) = if (originMode || (clampToRegion && cursorY in top..bottom)) top to bottom else 0 to rows - 1
        cursorY = y.coerceIn(low, high)
        wrapPending = false
    }

    /** Line feed: down one row, scrolling the region at its bottom. */
    private fun index() {
        if (cursorY == bottom) scrollUp(1, top, bottom) else if (cursorY < rows - 1) cursorY++
        wrapPending = false
    }

    private fun reverseIndex() {
        if (cursorY == top) scrollDown(1, top, bottom) else if (cursorY > 0) cursorY--
        wrapPending = false
    }

    private fun scrollUp(n: Int, from: Int, to: Int) {
        val count = n.coerceAtMost(to - from + 1)
        repeat(count) {
            val gone = screen.removeAt(from)
            // Only lines leaving the whole main screen are kept to scroll back to, like xterm.
            if (!isAltScreen && from == 0 && to == rows - 1) {
                scrollback.addLast(gone)
                if (scrollback.size > maxScrollback) scrollback.removeFirst()
            }
            screen.add(to, Line(cols).also { it.clear(0, cols, bg) })
        }
    }

    private fun scrollDown(n: Int, from: Int, to: Int) {
        val count = n.coerceAtMost(to - from + 1)
        repeat(count) {
            screen.removeAt(to)
            screen.add(from, Line(cols).also { it.clear(0, cols, bg) })
        }
    }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                screen[cursorY].clear(cursorX, cols, bg)
                for (y in cursorY + 1 until rows) screen[y].clear(0, cols, bg)
            }
            1 -> {
                for (y in 0 until cursorY) screen[y].clear(0, cols, bg)
                screen[cursorY].clear(0, cursorX + 1, bg)
            }
            2 -> screen.forEach { it.clear(0, cols, bg); it.wrapped = false }
            3 -> scrollback.clear()
        }
        wrapPending = false
    }

    private fun eraseLine(mode: Int) {
        val line = screen[cursorY]
        when (mode) {
            0 -> line.clear(cursorX, cols, bg)
            1 -> line.clear(0, cursorX + 1, bg)
            2 -> line.clear(0, cols, bg)
        }
        wrapPending = false
    }

    private fun saveCursor() {
        saved = Saved(cursorX, cursorY, fg, bg, flags, originMode, lineDrawing)
    }

    private fun restoreCursor() {
        saved?.let { restore(it) } ?: moveTo(0, 0)
    }

    private fun restore(s: Saved) {
        cursorX = s.x.coerceIn(0, cols - 1)
        cursorY = s.y.coerceIn(0, rows - 1)
        fg = s.fg
        bg = s.bg
        flags = s.flags
        originMode = s.origin
        lineDrawing = s.lineDrawing
        wrapPending = false
    }

    private fun reset() {
        main = MutableList(rows) { Line(cols) }
        alt = MutableList(rows) { Line(cols) }
        scrollback.clear()
        isAltScreen = false
        resetStyle()
        cursorX = 0
        cursorY = 0
        top = 0
        bottom = rows - 1
        autoWrap = true
        originMode = false
        insertMode = false
        cursorVisible = true
        appCursorKeys = false
        bracketedPaste = false
        lineDrawing = false
        wrapPending = false
        tabStops = BooleanArray(cols) { it % 8 == 0 }
    }

    /** New size from the view. The shell redraws its prompt after it learns the size. */
    fun resize(newCols: Int, newRows: Int) {
        val c = newCols.coerceAtLeast(2)
        val r = newRows.coerceAtLeast(2)
        if (c == cols && r == rows) return
        for (list in listOf(main, alt)) {
            list.forEach { it.resize(c) }
        }
        scrollback.forEach { it.resize(c) }
        // Fewer rows: lines above the cursor go to the scrollback first, so the prompt stays in view.
        while (main.size > r) {
            if (cursorY > 0 && !isAltScreen) {
                val gone = main.removeAt(0)
                scrollback.addLast(gone)
                cursorY--
            } else if (cursorY > 0 && isAltScreen) {
                main.removeAt(0)
            } else {
                main.removeAt(main.size - 1)
            }
        }
        while (main.size < r) main.add(Line(c))
        while (alt.size > r) alt.removeAt(if (isAltScreen && cursorY > 0) 0.also { cursorY-- } else alt.size - 1)
        while (alt.size < r) alt.add(Line(c))
        while (scrollback.size > maxScrollback) scrollback.removeFirst()
        cols = c
        rows = r
        top = 0
        bottom = r - 1
        tabStops = BooleanArray(c) { it % 8 == 0 }
        cursorX = cursorX.coerceIn(0, c - 1)
        cursorY = cursorY.coerceIn(0, r - 1)
        wrapPending = false
        version++
    }

    companion object {
        const val DEFAULT = -1
        /** The right half of a wide letter. */
        const val WIDE_TAIL = 0
        const val BOLD = 1
        const val DIM = 2
        const val ITALIC = 4
        const val UNDERLINE = 8
        const val INVERSE = 16
        const val HIDDEN = 32
        const val STRIKE = 64

        /** True colors are stored with the top bit set, so they never clash with the 256 palette numbers. */
        fun rgb(r: Int, g: Int, b: Int): Int = (0x01 shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
        fun isRgb(color: Int) = color != DEFAULT && color ushr 24 == 1

        /** DEC line drawing for 0x5F..0x7E, used by tmux, htop, and dialog boxes. */
        private val LINE_DRAWING = intArrayOf(
            ' '.code, '◆'.code, '▒'.code, '␉'.code, '␌'.code, '␍'.code, '␊'.code, '°'.code, '±'.code, '␤'.code, '␋'.code,
            '┘'.code, '┐'.code, '┌'.code, '└'.code, '┼'.code, '⎺'.code, '⎻'.code, '─'.code, '⎼'.code, '⎽'.code,
            '├'.code, '┤'.code, '┴'.code, '┬'.code, '│'.code, '≤'.code, '≥'.code, 'π'.code, '≠'.code, '£'.code, '·'.code,
        )

        /** Columns a letter takes: 2 for CJK and emoji, 0 for joiners and accents that sit on the letter before. */
        fun charWidth(c: Int): Int = when {
            c in 0x0300..0x036F || c in 0x200B..0x200F || c == 0x200D || c in 0xFE00..0xFE0F || c in 0x20D0..0x20FF -> 0
            c in 0x1100..0x115F || c in 0x2E80..0x303E || c in 0x3041..0x33FF || c in 0x3400..0x4DBF || c in 0x4E00..0x9FFF ||
                c in 0xA000..0xA4CF || c in 0xAC00..0xD7A3 || c in 0xF900..0xFAFF || c in 0xFE30..0xFE4F || c in 0xFF00..0xFF60 ||
                c in 0xFFE0..0xFFE6 || c in 0x1F300..0x1F64F || c in 0x1F680..0x1F6FF || c in 0x1F900..0x1F9FF ||
                c in 0x1FA70..0x1FAFF || c in 0x20000..0x3FFFD -> 2
            else -> 1
        }
    }
}

/** Bytes for keys, the way xterm sends them. */
object TerminalKeys {
    const val ESC = "\u001b"

    enum class Key { Up, Down, Left, Right, Home, End, PageUp, PageDown, Insert, Delete, Enter, Backspace, Tab, Escape, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12 }

    fun key(key: Key, appCursor: Boolean, shift: Boolean = false, alt: Boolean = false, ctrl: Boolean = false): String {
        val mod = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        fun cursor(letter: Char) = when {
            mod > 1 -> "$ESC[1;$mod$letter"
            appCursor -> "${ESC}O$letter"
            else -> "$ESC[$letter"
        }
        fun tilde(n: Int) = if (mod > 1) "$ESC[$n;$mod~" else "$ESC[$n~"
        return when (key) {
            Key.Up -> cursor('A')
            Key.Down -> cursor('B')
            Key.Right -> cursor('C')
            Key.Left -> cursor('D')
            Key.Home -> cursor('H')
            Key.End -> cursor('F')
            Key.Insert -> tilde(2)
            Key.Delete -> tilde(3)
            Key.PageUp -> tilde(5)
            Key.PageDown -> tilde(6)
            Key.Enter -> (if (alt) ESC else "") + "\r"
            Key.Backspace -> (if (alt) ESC else "") + if (ctrl) "\u0008" else "\u007f"
            Key.Tab -> if (shift) "$ESC[Z" else "\t"
            Key.Escape -> ESC
            Key.F1 -> "${ESC}OP"
            Key.F2 -> "${ESC}OQ"
            Key.F3 -> "${ESC}OR"
            Key.F4 -> "${ESC}OS"
            Key.F5 -> tilde(15)
            Key.F6 -> tilde(17)
            Key.F7 -> tilde(18)
            Key.F8 -> tilde(19)
            Key.F9 -> tilde(20)
            Key.F10 -> tilde(21)
            Key.F11 -> tilde(23)
            Key.F12 -> tilde(24)
        }
    }

    /** A typed letter with Ctrl and Alt applied: Ctrl-C is 0x03, Alt-x is Esc then x. */
    fun text(text: String, ctrl: Boolean, alt: Boolean): String {
        if (!ctrl && !alt) return text
        val out = StringBuilder()
        text.codePoints().forEach { cp ->
            var c = cp
            if (ctrl) {
                c = when (cp) {
                    in 'a'.code..'z'.code -> cp - 'a'.code + 1
                    in 'A'.code..'Z'.code -> cp - 'A'.code + 1
                    ' '.code, '2'.code, '@'.code -> 0
                    '['.code, '3'.code -> 27
                    '\\'.code, '4'.code -> 28
                    ']'.code, '5'.code -> 29
                    '^'.code, '6'.code -> 30
                    '_'.code, '7'.code, '/'.code, '-'.code -> 31
                    '8'.code, '?'.code -> 127
                    else -> cp
                }
            }
            if (alt) out.append(ESC)
            out.appendCodePoint(c)
        }
        return out.toString()
    }

    /** Pasted text, wrapped so the shell doesn't run each line as it arrives. */
    fun paste(text: String, bracketed: Boolean): String {
        val clean = text.replace("\r\n", "\r").replace('\n', '\r')
        return if (bracketed) "$ESC[200~" + clean.replace("$ESC[201~", "") + "$ESC[201~" else clean
    }
}
