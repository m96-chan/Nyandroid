package dev.nyandroid.terminal.emulator

/**
 * Fixed-capacity ring buffer that stores lines scrolled off the top of the
 * terminal screen. Each line is stored as four parallel [IntArray] segments
 * (codepoint, fg, bg, style flags) — the same layout as [TerminalGrid].
 *
 * The buffer holds at most [maxLines] lines. When full, the oldest line is
 * silently evicted. Retrieval is indexed from the most recent line
 * (`linesBack = 1`) to the oldest (`linesBack = storedLines`).
 */
class ScrollbackBuffer(val maxLines: Int, internal var cols: Int) {

    internal var cpBuf = IntArray(maxLines * cols)
    private var fgBuf = IntArray(maxLines * cols)
    private var bgBuf = IntArray(maxLines * cols)
    private var flBuf = IntArray(maxLines * cols)

    /** Next write position in the ring (0..maxLines-1). */
    private var head = 0

    /** Total lines ever pushed (may exceed maxLines). */
    var totalLines = 0
        private set

    /** Number of lines currently retrievable (capped at maxLines). */
    val storedLines: Int get() = minOf(totalLines, maxLines)

    /**
     * Saves one row from the grid arrays into the ring buffer.
     *
     * @param cp source codepoints array
     * @param fg source foreground array
     * @param bg source background array
     * @param fl source flags array
     * @param srcOffset start index in the source arrays (typically `row * cols`)
     * @param lineCols number of columns in the source line
     */
    fun pushLine(
        cp: IntArray, fg: IntArray, bg: IntArray, fl: IntArray,
        srcOffset: Int, lineCols: Int,
    ) {
        val dst = head * cols
        val copyLen = minOf(lineCols, cols)
        System.arraycopy(cp, srcOffset, cpBuf, dst, copyLen)
        System.arraycopy(fg, srcOffset, fgBuf, dst, copyLen)
        System.arraycopy(bg, srcOffset, bgBuf, dst, copyLen)
        System.arraycopy(fl, srcOffset, flBuf, dst, copyLen)
        // Blank any remaining columns if the source line is narrower.
        for (i in copyLen until cols) {
            cpBuf[dst + i] = ' '.code
            fgBuf[dst + i] = TerminalColors.DEFAULT_FG
            bgBuf[dst + i] = TerminalColors.DEFAULT_BG
            flBuf[dst + i] = 0
        }
        head = (head + 1) % maxLines
        totalLines++
    }

    /**
     * Copies a historical line into destination arrays.
     *
     * @param linesBack 1 = most recently pushed line, [storedLines] = oldest
     * @param dstCp destination codepoints array
     * @param dstFg destination foreground array
     * @param dstBg destination background array
     * @param dstFl destination flags array
     * @param dstOffset start index in destination arrays
     * @param dstCols number of columns to write
     */
    fun copyLineInto(
        linesBack: Int,
        dstCp: IntArray, dstFg: IntArray, dstBg: IntArray, dstFl: IntArray,
        dstOffset: Int, dstCols: Int,
    ) {
        val ringIdx = (head - linesBack).mod(maxLines)
        val src = ringIdx * cols
        val copyLen = minOf(cols, dstCols)
        System.arraycopy(cpBuf, src, dstCp, dstOffset, copyLen)
        System.arraycopy(fgBuf, src, dstFg, dstOffset, copyLen)
        System.arraycopy(bgBuf, src, dstBg, dstOffset, copyLen)
        System.arraycopy(flBuf, src, dstFl, dstOffset, copyLen)
        for (i in copyLen until dstCols) {
            dstCp[dstOffset + i] = ' '.code
            dstFg[dstOffset + i] = TerminalColors.DEFAULT_FG
            dstBg[dstOffset + i] = TerminalColors.DEFAULT_BG
            dstFl[dstOffset + i] = 0
        }
    }

    /** Discards all stored lines. */
    fun clear() {
        head = 0
        totalLines = 0
    }

    /**
     * Searches for [query] in the scrollback, returning the line offset
     * (linesBack) of the first match at or after [startLinesBack].
     * Returns -1 if not found.
     */
    fun search(query: String, startLinesBack: Int = 1, forward: Boolean = false): Int {
        val count = storedLines
        if (count == 0 || query.isEmpty()) return -1
        val lowerQuery = query.lowercase()

        if (forward) {
            // Search from startLinesBack toward more recent lines.
            var lb = startLinesBack
            while (lb >= 1) {
                if (lineContains(lb, lowerQuery)) return lb
                lb--
            }
        } else {
            // Search from startLinesBack toward older lines.
            var lb = startLinesBack
            while (lb <= count) {
                if (lineContains(lb, lowerQuery)) return lb
                lb++
            }
        }
        return -1
    }

    private fun lineContains(linesBack: Int, lowerQuery: String): Boolean {
        val ringIdx = (head - linesBack).mod(maxLines)
        val src = ringIdx * cols
        val sb = StringBuilder(cols)
        for (c in 0 until cols) {
            val cp = cpBuf[src + c]
            if (cp > 0) sb.appendCodePoint(cp)
        }
        return sb.toString().lowercase().contains(lowerQuery)
    }

    /**
     * Returns a new buffer with [newCols] width, copying all stored lines
     * (truncating or padding as needed).
     */
    fun resize(newCols: Int): ScrollbackBuffer {
        val newBuf = ScrollbackBuffer(maxLines, newCols)
        val count = storedLines
        for (i in count downTo 1) {
            val ringIdx = (head - i).mod(maxLines)
            val src = ringIdx * cols
            newBuf.pushLine(cpBuf, fgBuf, bgBuf, flBuf, src, cols)
        }
        return newBuf
    }
}
