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
class TerminalGrid(cols: Int, rows: Int, scrollbackLines: Int = DEFAULT_SCROLLBACK) {

    var cols = cols.coerceAtLeast(1)
        private set
    var rows = rows.coerceAtLeast(1)
        private set

    private var cp = IntArray(this.cols * this.rows)
    private var fg = IntArray(cp.size)
    private var bg = IntArray(cp.size)
    private var fl = IntArray(cp.size)

    /** Scrollback history for the primary screen. */
    var scrollback = ScrollbackBuffer(scrollbackLines, this.cols)
        private set

    var cursorRow = 0
        private set
    var cursorCol = 0
        private set
    var cursorVisible = true
        private set
    /** DECSCUSR cursor shape: 0/1=blinking block, 2=steady block,
     *  3=blinking underline, 4=steady underline, 5=blinking beam, 6=steady beam. */
    var cursorShape = CURSOR_BLOCK_BLINK
        private set

    private var penFg = TerminalColors.DEFAULT_FG
    private var penBg = TerminalColors.DEFAULT_BG
    private var penFlags = 0

    private var scrollTop = 0
    private var scrollBottom = this.rows - 1

    /** Deferred wrap: set after writing the last column (xterm semantics). */
    private var wrapPending = false

    var bracketedPasteMode = false
        private set

    /** DECCKM (mode ?1): when true, cursor keys send SS3 A/B/C/D instead of CSI. */
    var applicationCursorKeys = false
        private set

    /** DECKPAM/DECKPNM: when true, keypad sends application sequences. */
    var applicationKeypad = false
        private set

    // Mouse reporting modes (mutually exclusive tracking modes + format flag).
    /** ?1000: X10-compatible button press/release reporting. */
    var mouseTrackingMode = MOUSE_NONE
        private set
    /** ?1006: SGR extended mouse format (CSI < ... M/m). */
    var mouseSgrFormat = false
        private set

    // Shell integration state.
    var currentWorkingDirectory: String? = null
    var lastCommandExitCode = 0
        private set
    /** Row index where the last prompt started (for prompt-to-prompt jump). */
    var promptStartRow = -1
        private set
    /** Row index where the last command output started. */
    var outputStartRow = -1
        private set

    fun shellMarkPromptStart() { promptStartRow = cursorRow }
    fun shellMarkCommandStart() { /* command begins at cursor */ }
    fun shellMarkOutputStart() { outputStartRow = cursorRow }
    fun shellMarkCommandEnd(exitCode: Int) { lastCommandExitCode = exitCode }

    // Saved cursor (DECSC / DECRC and CSI s/u).
    private var savedRow = 0
    private var savedCol = 0
    private var savedFg = penFg
    private var savedBg = penBg
    private var savedFlags = 0

    // Alternate screen state.
    var onAltScreen = false
        private set
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
        val width = CharWidth.of(codePoint)

        // Combining character (zero-width): merge with the preceding cell.
        if (width == 0 && codePoint >= 0x0300) return // TODO: proper combining

        if (wrapPending) {
            carriageReturn()
            lineFeed()
        }

        if (width == 2) {
            // Wide character needs 2 columns. If at the last column, wrap first.
            if (cursorCol >= cols - 1) {
                // Blank the current last column and wrap.
                val i = idx(cursorRow, cursorCol)
                cp[i] = ' '.code; fg[i] = penFg; bg[i] = penBg; fl[i] = 0
                carriageReturn()
                lineFeed()
            }
            val i = idx(cursorRow, cursorCol)
            cp[i] = codePoint
            fg[i] = penFg
            bg[i] = penBg
            fl[i] = penFlags or WIDE
            // Right-half dummy cell.
            val j = idx(cursorRow, cursorCol + 1)
            cp[j] = WIDE_DUMMY
            fg[j] = penFg
            bg[j] = penBg
            fl[j] = penFlags
            if (cursorCol >= cols - 2) {
                wrapPending = true
            } else {
                cursorCol += 2
            }
        } else {
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

    fun setCursorShape(shape: Int) {
        cursorShape = shape.coerceIn(0, 6)
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
        // Save lines about to scroll off the top into scrollback (primary
        // screen, full-width scroll region only — partial regions like tmux
        // status bars must not pollute history).
        if (!onAltScreen && scrollTop == 0) {
            for (row in 0 until count) {
                scrollback.pushLine(cp, fg, bg, fl, idx(row, 0), cols)
            }
        }
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
        cursorShape = CURSOR_BLOCK_BLINK
        wrapPending = false
        onAltScreen = false
        bracketedPasteMode = false
        applicationCursorKeys = false
        applicationKeypad = false
        mouseTrackingMode = MOUSE_NONE
        mouseSgrFormat = false
        scrollback.clear()
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
        scrollback = scrollback.resize(nc)
    }

    // --- Snapshot -----------------------------------------------------------

    /**
     * Copies the visible screen into [out], baking in reverse-video and a block
     * cursor so the renderer can stay oblivious to terminal semantics.
     *
     * @param viewportOffset number of scrollback lines above the screen top.
     *   0 = live view (default), >0 = scrolled back into history.
     */
    fun snapshotInto(out: FrameSnapshot, viewportOffset: Int = 0) {
        out.ensureCapacity(cols, rows)
        val oc = out.codePoints
        val of = out.fg
        val ob = out.bg
        val os = out.styleFlags
        val total = cols * rows

        if (viewportOffset <= 0) {
            // Fast path: copy screen directly.
            System.arraycopy(cp, 0, oc, 0, total)
            System.arraycopy(fg, 0, of, 0, total)
            System.arraycopy(bg, 0, ob, 0, total)
            System.arraycopy(fl, 0, os, 0, total)
        } else {
            // Compose from scrollback (top) + screen (bottom).
            val scrollbackRows = minOf(viewportOffset, rows)
            val screenRows = rows - scrollbackRows

            for (r in 0 until scrollbackRows) {
                val linesBack = viewportOffset - r
                if (linesBack <= scrollback.storedLines) {
                    scrollback.copyLineInto(linesBack, oc, of, ob, os, r * cols, cols)
                } else {
                    blankSnapshotRow(oc, of, ob, os, r * cols)
                }
            }
            if (screenRows > 0) {
                val dstOff = scrollbackRows * cols
                System.arraycopy(cp, 0, oc, dstOff, screenRows * cols)
                System.arraycopy(fg, 0, of, dstOff, screenRows * cols)
                System.arraycopy(bg, 0, ob, dstOff, screenRows * cols)
                System.arraycopy(fl, 0, os, dstOff, screenRows * cols)
            }
        }

        // Apply reverse-video.
        for (i in 0 until total) {
            if (os[i] and REVERSE != 0) {
                val t = of[i]; of[i] = ob[i]; ob[i] = t
            }
        }

        // Apply cursor.
        if (cursorVisible && viewportOffset == 0) {
            out.cursorRow = cursorRow
            out.cursorCol = cursorCol
            out.cursorShape = cursorShape
            val isBlock = cursorShape <= CURSOR_BLOCK_STEADY
            if (isBlock) {
                val i = idx(cursorRow, cursorCol)
                of[i] = ob[i]
                ob[i] = TerminalColors.CURSOR
            }
            // Beam and underline cursors are drawn by the GPU backend.
        } else {
            out.cursorRow = -1
            out.cursorCol = -1
        }
    }

    private fun blankSnapshotRow(
        oc: IntArray, of: IntArray, ob: IntArray, os: IntArray, offset: Int,
    ) {
        for (c in 0 until cols) {
            oc[offset + c] = ' '.code
            of[offset + c] = TerminalColors.DEFAULT_FG
            ob[offset + c] = TerminalColors.DEFAULT_BG
            os[offset + c] = 0
        }
    }

    fun setBracketedPasteMode(enable: Boolean) {
        bracketedPasteMode = enable
    }

    fun setApplicationCursorKeys(enable: Boolean) {
        applicationCursorKeys = enable
    }

    fun setApplicationKeypad(enable: Boolean) {
        applicationKeypad = enable
    }

    fun setMouseTracking(mode: Int) {
        mouseTrackingMode = mode
    }

    fun setMouseSgrFormat(enable: Boolean) {
        mouseSgrFormat = enable
    }

    // --- Selection -----------------------------------------------------------

    /**
     * Returns the text content within the given viewport row/col range.
     * Trailing spaces on each line are trimmed; lines are joined with newline.
     *
     * @param startRow viewport row (0-based, top of visible area)
     * @param startCol column
     * @param endRow viewport row
     * @param endCol column (inclusive)
     * @param viewportOffset current scrollback offset
     */
    fun getTextInRange(
        startRow: Int, startCol: Int,
        endRow: Int, endCol: Int,
        viewportOffset: Int,
    ): String {
        val sb = StringBuilder()
        for (r in startRow..endRow) {
            val c1 = if (r == startRow) startCol else 0
            val c2 = if (r == endRow) endCol else cols - 1
            val line = StringBuilder()
            for (c in c1..c2.coerceAtMost(cols - 1)) {
                val cp = getCellCodePoint(r, viewportOffset)?.get(c) ?: ' '.code
                if (cp != 0) line.appendCodePoint(cp) else line.append(' ')
            }
            // Trim trailing spaces.
            var end = line.length
            while (end > 0 && line[end - 1] == ' ') end--
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line, 0, end)
        }
        return sb.toString()
    }

    /**
     * Returns the codepoint array for a viewport row, or null if out of range.
     */
    private fun getCellCodePoint(viewportRow: Int, viewportOffset: Int): IntArray? {
        val scrollbackRows = minOf(viewportOffset, rows)
        return if (viewportRow < scrollbackRows) {
            val linesBack = viewportOffset - viewportRow
            if (linesBack <= scrollback.storedLines) {
                IntArray(cols).also { dst ->
                    val ringIdx = (scrollback.totalLines - linesBack).mod(scrollback.maxLines)
                    System.arraycopy(scrollback.cpBuf, ringIdx * cols, dst, 0, minOf(scrollback.cols, cols))
                }
            } else null
        } else {
            val screenRow = viewportRow - scrollbackRows
            if (screenRow in 0 until rows) {
                IntArray(cols).also { dst ->
                    System.arraycopy(cp, screenRow * cols, dst, 0, cols)
                }
            } else null
        }
    }

    /**
     * Applies selection highlight to the snapshot arrays. Called after cursor baking.
     */
    fun applySelectionHighlight(
        of: IntArray, ob: IntArray,
        startRow: Int, startCol: Int,
        endRow: Int, endCol: Int,
    ) {
        for (r in startRow..endRow.coerceAtMost(rows - 1)) {
            if (r < 0) continue
            val c1 = if (r == startRow) startCol.coerceAtLeast(0) else 0
            val c2 = if (r == endRow) endCol.coerceAtMost(cols - 1) else cols - 1
            for (c in c1..c2) {
                val i = r * cols + c
                of[i] = SELECTION_FG
                ob[i] = SELECTION_BG
            }
        }
    }

    companion object {
        const val TAB_WIDTH = 8
        const val DEFAULT_SCROLLBACK = 10_000

        const val BOLD = 1
        const val DIM = 2
        const val ITALIC = 4
        const val UNDERLINE = 8
        const val REVERSE = 16

        const val WIDE = 32
        /** Sentinel codepoint for the right-half cell of a wide character. */
        const val WIDE_DUMMY = 0

        const val SELECTION_FG = 0xFFFFFF
        const val SELECTION_BG = 0x264F78

        // DECSCUSR cursor shapes.
        const val CURSOR_BLOCK_BLINK = 0
        const val CURSOR_BLOCK_STEADY = 2
        const val CURSOR_UNDERLINE_BLINK = 3
        const val CURSOR_UNDERLINE_STEADY = 4
        const val CURSOR_BEAM_BLINK = 5
        const val CURSOR_BEAM_STEADY = 6

        // Mouse tracking modes.
        const val MOUSE_NONE = 0
        const val MOUSE_X10 = 1000       // ?1000: button press/release
        const val MOUSE_BUTTON = 1002    // ?1002: button event (press/release/drag)
        const val MOUSE_ANY = 1003       // ?1003: any event (includes motion)
    }
}
