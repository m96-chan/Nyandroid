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

    /** Invoked (on the feeding thread) whenever the screen changed. */
    var onChange: (() -> Unit)? = null

    val cols: Int get() = synchronized(lock) { grid.cols }
    val rows: Int get() = synchronized(lock) { grid.rows }

    fun feed(data: ByteArray, length: Int) {
        synchronized(lock) {
            parser.parse(data, length)
            revision++
        }
        onChange?.invoke()
    }

    fun resize(cols: Int, rows: Int) {
        synchronized(lock) {
            grid.resize(cols, rows)
            revision++
        }
        onChange?.invoke()
    }

    /**
     * Fills [out] with the current screen and returns the revision it reflects.
     * The renderer can compare revisions to skip redundant redraws.
     */
    fun snapshot(out: FrameSnapshot): Long = synchronized(lock) {
        grid.snapshotInto(out)
        out.markUpdated(revision)
        revision
    }
}
