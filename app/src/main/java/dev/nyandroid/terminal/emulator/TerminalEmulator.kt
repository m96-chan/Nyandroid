package dev.nyandroid.terminal.emulator

/**
 * Owns the [TerminalGrid] + [VtParser] and serialises all access to them.
 *
 * Bytes arrive from the backend reader thread via [feed]; the render thread
 * pulls a consistent view via [snapshot]. A single lock guards the grid, which
 * is cheap because parsing is fast and snapshots are array copies.
 */
class TerminalEmulator(
    cols: Int,
    rows: Int,
    /** Used for terminal replies (DSR/cursor reports). Routed to the backend. */
    private val respond: (ByteArray) -> Unit,
) {
    private val lock = Any()
    private val grid = TerminalGrid(cols, rows)
    private val parser = VtParser(grid, respond)

    private var revision = 0L

    /** Lines scrolled back from the live screen. 0 = at bottom (live). */
    private var viewportOffset = 0

    /** Invoked (on the feeding thread) whenever the screen changed. */
    var onChange: (() -> Unit)? = null

    val cols: Int get() = synchronized(lock) { grid.cols }
    val rows: Int get() = synchronized(lock) { grid.rows }

    fun feed(data: ByteArray, length: Int) {
        synchronized(lock) {
            parser.parse(data, length)
            viewportOffset = 0 // Auto-scroll to bottom on new output.
            revision++
        }
        onChange?.invoke()
    }

    fun resize(cols: Int, rows: Int) {
        synchronized(lock) {
            grid.resize(cols, rows)
            viewportOffset = 0
            revision++
        }
        onChange?.invoke()
    }

    /**
     * Fills [out] with the current screen and returns the revision it reflects.
     * The renderer can compare revisions to skip redundant redraws.
     */
    fun snapshot(out: FrameSnapshot): Long = synchronized(lock) {
        grid.snapshotInto(out, viewportOffset)
        out.markUpdated(revision)
        revision
    }

    /**
     * Scrolls the viewport by [delta] lines (positive = back into history,
     * negative = toward live screen). Called from the UI thread.
     */
    fun scrollViewport(delta: Int) {
        synchronized(lock) {
            val maxOffset = grid.scrollback.storedLines
            viewportOffset = (viewportOffset + delta).coerceIn(0, maxOffset)
            revision++
        }
        onChange?.invoke()
    }

    /** Whether currently on the alternate screen (UI may disable scrollback). */
    fun isAltScreen(): Boolean = synchronized(lock) { grid.onAltScreen }
}
