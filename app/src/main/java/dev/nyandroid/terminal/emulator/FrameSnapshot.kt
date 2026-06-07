package dev.nyandroid.terminal.emulator

/**
 * A flat, render-ready copy of the screen at one instant.
 *
 * The emulator produces these under its lock; the render thread consumes them
 * without touching live emulator state. Reverse-video and the block cursor are
 * already baked into [fg]/[bg], so the renderer only needs to: draw [bg], then
 * blend the glyph for [codePoints] in [fg]. [styleFlags] is kept solely so the
 * renderer can pick a bold/italic glyph variant.
 *
 * Buffers are reused across frames where the geometry matches, so callers must
 * read them only while holding the frame (they are swapped, not mutated, by
 * the producer).
 */
class FrameSnapshot {
    var cols: Int = 0
        private set
    var rows: Int = 0
        private set

    var codePoints: IntArray = IntArray(0)
        private set
    var fg: IntArray = IntArray(0)
        private set
    var bg: IntArray = IntArray(0)
        private set
    var styleFlags: IntArray = IntArray(0)
        private set

    /** Monotonically increasing; lets the renderer skip unchanged frames. */
    var revision: Long = 0
        private set

    /** Cursor row in the snapshot (-1 if invisible). */
    var cursorRow: Int = -1
    /** Cursor column in the snapshot (-1 if invisible). */
    var cursorCol: Int = -1
    /** DECSCUSR cursor shape (0-6). */
    var cursorShape: Int = 0

    fun ensureCapacity(cols: Int, rows: Int) {
        if (this.cols == cols && this.rows == rows) return
        this.cols = cols
        this.rows = rows
        val n = cols * rows
        codePoints = IntArray(n)
        fg = IntArray(n)
        bg = IntArray(n)
        styleFlags = IntArray(n)
    }

    fun markUpdated(revision: Long) {
        this.revision = revision
    }
}
