package dev.nyandroid.terminal.emulator

/**
 * The terminal screen model: a grid of cells plus cursor and pen state.
 *
 * Storage is parallel primitive arrays (`row * cols + col`) to keep snapshots
 * cheap and allocation-free on the hot path. This is the equivalent of kitty's
 * line buffer, trimmed to what the PoC needs:
 *
 *  - DECAWM-style deferred wrap at the right margin
 *  - scroll regions (DECSTBM)
 *  - a primary + alternate screen (so `vi`/`less` behave)
 *  - SGR attributes resolved to RGB
 *
 * Not thread-safe by itself; [TerminalEmulator] owns the lock.
 */
class TerminalGrid(cols: Int, rows: Int) {

    var cols = cols.coerceAtLeast(1)
        private set
    var rows = rows.coerceAtLeast(1)
        private set

    private var cp = IntArray(this.cols * this.rows)
    private var fg = IntArray(cp.size)
    private var bg = IntArray(cp.size)
    private var fl = IntArray(cp.size)

    var cursorRow = 0
        private set
    var cursorCol = 0
        private set
    var cursorVisible = true
        private set

    private var penFg = TerminalColors.DEFAULT_FG
    private var penBg = TerminalColors.DEFAULT_BG
    private var penFlags = 0

    private var scrollTop = 0
    private var scrollBottom = this.rows - 1

    /** Deferred wrap: set after writing the last column (xterm semantics). */
    private var wrapPending = false

    // Saved cursor (DECSC / DECRC and CSI s/u).
    private var savedRow = 0
    private var savedCol = 0
    private var savedFg = penFg
    private var savedBg = penBg
    private var savedFlags = 0

    // Alternate screen state.
    private var onAltScreen = false
    private var altSavedCp: IntArray? = null
    private var altSavedFg: IntArray? = null
    private var altSavedBg: IntArray? = null
    private var altSavedFl: IntArray? = null
    private var altSavedCursorRow = 0
    private var altSavedCursorCol = 0

    init {
        clearAll()
    }

    private fun idx(row: Int, col: Int) = row * cols + col

    private fun blank(i: Int, background: Int = penBg) {
        cp[i] = ' '.code
        fg[i] = penFg
        bg[i] = background
        fl[i] = 0
    }

    private fun clearAll() {
        for (i in cp.indices) {
            cp[i] = ' '.code
            fg[i] = TerminalColors.DEFAULT_FG
            bg[i] = TerminalColors.DEFAULT_BG
            fl[i] = 0
        }
    }

    // --- Printing -----------------------------------------------------------

    fun putCodePoint(codePoint: Int) {
        if (wrapPending) {
            carriageReturn()
            lineFeed()
        }
        val i = idx(cursorRow, cursorCol)
        cp[i] = codePoint
        fg[i] = penFg
        bg[i] = penBg
        fl[i] = penFlags
        if (cursorCol >= cols - 1) {
            wrapPending = true
        } else {
            cursorCol++
        }
    }

    // --- Cursor motion ------------------------------------------------------

    fun carriageReturn() {
        cursorCol = 0
        wrapPending = false
    }

    fun lineFeed() {
        wrapPending = false
        if (cursorRow == scrollBottom) {
            scrollUp(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    fun reverseIndex() {
        wrapPending = false
        if (cursorRow == scrollTop) {
            scrollDown(1)
        } else if (cursorRow > 0) {
            cursorRow--
        }
    }

    fun backspace() {
        if (cursorCol > 0) cursorCol--
        wrapPending = false
    }

    fun tab() {
        wrapPending = false
        cursorCol = ((cursorCol / TAB_WIDTH) + 1) * TAB_WIDTH
        if (cursorCol >= cols) cursorCol = cols - 1
    }

    fun moveCursor(dRow: Int, dCol: Int) {
        wrapPending = false
        cursorRow = (cursorRow + dRow).coerceIn(0, rows - 1)
        cursorCol = (cursorCol + dCol).coerceIn(0, cols - 1)
    }

    /** 1-based row/col as received from CSI; either may be 0 meaning "default". */
    fun setCursor(row1: Int, col1: Int) {
        wrapPending = false
        cursorRow = (row1 - 1).coerceIn(0, rows - 1)
        cursorCol = (col1 - 1).coerceIn(0, cols - 1)
    }

    fun setCursorCol(col1: Int) {
        wrapPending = false
        cursorCol = (col1 - 1).coerceIn(0, cols - 1)
    }

    fun setCursorRow(row1: Int) {
        wrapPending = false
        cursorRow = (row1 - 1).coerceIn(0, rows - 1)
    }

    fun setCursorVisible(visible: Boolean) {
        cursorVisible = visible
    }

    // --- Scrolling ----------------------------------------------------------

    fun setScrollRegion(top1: Int, bottom1: Int) {
        val top = (top1 - 1).coerceIn(0, rows - 1)
        val bottom = (bottom1 - 1).coerceIn(0, rows - 1)
        if (top < bottom) {
            scrollTop = top
            scrollBottom = bottom
        } else {
            scrollTop = 0
            scrollBottom = rows - 1
        }
        cursorRow = scrollTop
        cursorCol = 0
        wrapPending = false
    }

    fun scrollUp(n: Int) {
        val count = n.coerceIn(1, scrollBottom - scrollTop + 1)
        for (row in scrollTop..(scrollBottom - count)) {
            val src = idx(row + count, 0)
            val dst = idx(row, 0)
            System.arraycopy(cp, src, cp, dst, cols)
            System.arraycopy(fg, src, fg, dst, cols)
            System.arraycopy(bg, src, bg, dst, cols)
            System.arraycopy(fl, src, fl, dst, cols)
        }
        for (row in (scrollBottom - count + 1)..scrollBottom) blankRow(row)
    }

    fun scrollDown(n: Int) {
        val count = n.coerceIn(1, scrollBottom - scrollTop + 1)
        for (row in scrollBottom downTo (scrollTop + count)) {
            val src = idx(row - count, 0)
            val dst = idx(row, 0)
            System.arraycopy(cp, src, cp, dst, cols)
            System.arraycopy(fg, src, fg, dst, cols)
            System.arraycopy(bg, src, bg, dst, cols)
            System.arraycopy(fl, src, fl, dst, cols)
        }
        for (row in scrollTop until (scrollTop + count)) blankRow(row)
    }

    private fun blankRow(row: Int) {
        val start = idx(row, 0)
        for (c in 0 until cols) blank(start + c)
    }

    // --- Erase / edit -------------------------------------------------------

    fun eraseInLine(mode: Int) {
        val rowStart = idx(cursorRow, 0)
        when (mode) {
            0 -> for (c in cursorCol until cols) blank(rowStart + c)
            1 -> for (c in 0..cursorCol) blank(rowStart + c)
            2 -> for (c in 0 until cols) blank(rowStart + c)
        }
    }

    fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (i in idx(cursorRow + 1, 0) until cp.size) blank(i)
            }
            1 -> {
                for (i in 0 until idx(cursorRow, 0)) blank(i)
                eraseInLine(1)
            }
            else -> for (i in cp.indices) blank(i)
        }
    }

    fun eraseChars(n: Int) {
        val count = n.coerceAtLeast(1)
        val rowStart = idx(cursorRow, 0)
        var c = cursorCol
        var k = 0
        while (c < cols && k < count) {
            blank(rowStart + c); c++; k++
        }
    }

    fun insertChars(n: Int) {
        val count = n.coerceIn(1, cols - cursorCol)
        val rowStart = idx(cursorRow, 0)
        val move = cols - cursorCol - count
        if (move > 0) {
            shiftWithin(rowStart + cursorCol, rowStart + cursorCol + count, move)
        }
        for (c in cursorCol until cursorCol + count) blank(rowStart + c)
    }

    fun deleteChars(n: Int) {
        val count = n.coerceIn(1, cols - cursorCol)
        val rowStart = idx(cursorRow, 0)
        val move = cols - cursorCol - count
        if (move > 0) {
            shiftWithin(rowStart + cursorCol + count, rowStart + cursorCol, move)
        }
        for (c in (cols - count) until cols) blank(rowStart + c)
    }

    private fun shiftWithin(src: Int, dst: Int, len: Int) {
        System.arraycopy(cp, src, cp, dst, len)
        System.arraycopy(fg, src, fg, dst, len)
        System.arraycopy(bg, src, bg, dst, len)
        System.arraycopy(fl, src, fl, dst, len)
    }

    fun insertLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val count = n.coerceIn(1, scrollBottom - cursorRow + 1)
        for (row in scrollBottom downTo (cursorRow + count)) {
            val src = idx(row - count, 0)
            val dst = idx(row, 0)
            shiftWithin(src, dst, cols)
        }
        for (row in cursorRow until (cursorRow + count)) blankRow(row)
    }

    fun deleteLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val count = n.coerceIn(1, scrollBottom - cursorRow + 1)
        for (row in cursorRow..(scrollBottom - count)) {
            val src = idx(row + count, 0)
            val dst = idx(row, 0)
            shiftWithin(src, dst, cols)
        }
        for (row in (scrollBottom - count + 1)..scrollBottom) blankRow(row)
    }

    // --- Pen / SGR ----------------------------------------------------------

    fun resetPen() {
        penFg = TerminalColors.DEFAULT_FG
        penBg = TerminalColors.DEFAULT_BG
        penFlags = 0
    }

    /** Applies an SGR sequence; [params] holds [count] decoded numeric params. */
    fun applySgr(params: IntArray, count: Int) {
        if (count == 0) {
            resetPen()
            return
        }
        var i = 0
        while (i < count) {
            when (val p = params[i]) {
                0 -> resetPen()
                1 -> penFlags = penFlags or BOLD
                2 -> penFlags = penFlags or DIM
                3 -> penFlags = penFlags or ITALIC
                4 -> penFlags = penFlags or UNDERLINE
                7 -> penFlags = penFlags or REVERSE
                22 -> penFlags = penFlags and (BOLD or DIM).inv()
                23 -> penFlags = penFlags and ITALIC.inv()
                24 -> penFlags = penFlags and UNDERLINE.inv()
                27 -> penFlags = penFlags and REVERSE.inv()
                in 30..37 -> penFg = TerminalColors.indexed(p - 30)
                in 90..97 -> penFg = TerminalColors.indexed(p - 90 + 8)
                39 -> penFg = TerminalColors.DEFAULT_FG
                in 40..47 -> penBg = TerminalColors.indexed(p - 40)
                in 100..107 -> penBg = TerminalColors.indexed(p - 100 + 8)
                49 -> penBg = TerminalColors.DEFAULT_BG
                38 -> i = consumeExtendedColor(params, count, i) { penFg = it }
                48 -> i = consumeExtendedColor(params, count, i) { penBg = it }
            }
            i++
        }
    }

    /** Handles `38;5;n` / `38;2;r;g;b`; returns the index of the last param used. */
    private inline fun consumeExtendedColor(
        params: IntArray,
        count: Int,
        start: Int,
        set: (Int) -> Unit,
    ): Int {
        if (start + 1 >= count) return start
        return when (params[start + 1]) {
            5 -> {
                if (start + 2 < count) set(TerminalColors.indexed(params[start + 2]))
                start + 2
            }
            2 -> {
                if (start + 4 < count) {
                    set(TerminalColors.rgb(params[start + 2], params[start + 3], params[start + 4]))
                }
                start + 4
            }
            else -> start + 1
        }
    }

    // --- Save / restore / alt screen ---------------------------------------

    fun saveCursor() {
        savedRow = cursorRow
        savedCol = cursorCol
        savedFg = penFg
        savedBg = penBg
        savedFlags = penFlags
    }

    fun restoreCursor() {
        cursorRow = savedRow.coerceIn(0, rows - 1)
        cursorCol = savedCol.coerceIn(0, cols - 1)
        penFg = savedFg
        penBg = savedBg
        penFlags = savedFlags
        wrapPending = false
    }

    fun setAltScreen(on: Boolean) {
        if (on == onAltScreen) return
        if (on) {
            altSavedCp = cp.copyOf()
            altSavedFg = fg.copyOf()
            altSavedBg = bg.copyOf()
            altSavedFl = fl.copyOf()
            altSavedCursorRow = cursorRow
            altSavedCursorCol = cursorCol
            clearAll()
            cursorRow = 0
            cursorCol = 0
        } else {
            altSavedCp?.let { System.arraycopy(it, 0, cp, 0, cp.size) }
            altSavedFg?.let { System.arraycopy(it, 0, fg, 0, fg.size) }
            altSavedBg?.let { System.arraycopy(it, 0, bg, 0, bg.size) }
            altSavedFl?.let { System.arraycopy(it, 0, fl, 0, fl.size) }
            cursorRow = altSavedCursorRow.coerceIn(0, rows - 1)
            cursorCol = altSavedCursorCol.coerceIn(0, cols - 1)
            altSavedCp = null; altSavedFg = null; altSavedBg = null; altSavedFl = null
        }
        onAltScreen = on
        scrollTop = 0
        scrollBottom = rows - 1
        wrapPending = false
    }

    fun fullReset() {
        resetPen()
        clearAll()
        cursorRow = 0
        cursorCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
        cursorVisible = true
        wrapPending = false
        onAltScreen = false
    }

    // --- Geometry -----------------------------------------------------------

    fun resize(newCols: Int, newRows: Int) {
        val nc = newCols.coerceAtLeast(1)
        val nr = newRows.coerceAtLeast(1)
        if (nc == cols && nr == rows) return

        val newCp = IntArray(nc * nr)
        val newFg = IntArray(newCp.size)
        val newBg = IntArray(newCp.size)
        val newFl = IntArray(newCp.size)
        for (i in newCp.indices) {
            newCp[i] = ' '.code
            newFg[i] = TerminalColors.DEFAULT_FG
            newBg[i] = TerminalColors.DEFAULT_BG
        }
        val copyRows = minOf(rows, nr)
        val copyCols = minOf(cols, nc)
        for (r in 0 until copyRows) {
            val srcStart = r * cols
            val dstStart = r * nc
            System.arraycopy(cp, srcStart, newCp, dstStart, copyCols)
            System.arraycopy(fg, srcStart, newFg, dstStart, copyCols)
            System.arraycopy(bg, srcStart, newBg, dstStart, copyCols)
            System.arraycopy(fl, srcStart, newFl, dstStart, copyCols)
        }

        cp = newCp; fg = newFg; bg = newBg; fl = newFl
        cols = nc; rows = nr
        scrollTop = 0
        scrollBottom = rows - 1
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        wrapPending = false
        // Alt-screen save buffers are invalidated by a resize.
        altSavedCp = null; altSavedFg = null; altSavedBg = null; altSavedFl = null
        onAltScreen = false
    }

    // --- Snapshot -----------------------------------------------------------

    /**
     * Copies the visible screen into [out], baking in reverse-video and a block
     * cursor so the renderer can stay oblivious to terminal semantics.
     */
    fun snapshotInto(out: FrameSnapshot) {
        out.ensureCapacity(cols, rows)
        val oc = out.codePoints
        val of = out.fg
        val ob = out.bg
        val os = out.styleFlags
        val cursorIdx = if (cursorVisible) idx(cursorRow, cursorCol) else -1
        for (i in cp.indices) {
            var f = fg[i]
            var b = bg[i]
            val flags = fl[i]
            if (flags and REVERSE != 0) {
                val t = f; f = b; b = t
            }
            if (i == cursorIdx) {
                // Block cursor: glyph in the cell background colour on a
                // cursor-coloured block.
                f = bg[i]
                b = TerminalColors.CURSOR
            }
            oc[i] = cp[i]
            of[i] = f
            ob[i] = b
            os[i] = flags
        }
    }

    companion object {
        const val TAB_WIDTH = 8

        const val BOLD = 1
        const val DIM = 2
        const val ITALIC = 4
        const val UNDERLINE = 8
        const val REVERSE = 16
    }
}
