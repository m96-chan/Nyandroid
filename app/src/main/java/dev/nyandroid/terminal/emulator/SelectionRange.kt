package dev.nyandroid.terminal.emulator

/**
 * Describes a text selection in viewport coordinates.
 * Start is always before end (normalized at creation time).
 */
data class SelectionRange(
    val startRow: Int,
    val startCol: Int,
    val endRow: Int,
    val endCol: Int,
    /** The viewport offset at the time the selection was made. */
    val viewportOffset: Int,
) {
    companion object {
        /** Creates a normalized range (start ≤ end). */
        fun normalized(
            row1: Int, col1: Int,
            row2: Int, col2: Int,
            viewportOffset: Int,
        ): SelectionRange {
            return if (row1 < row2 || (row1 == row2 && col1 <= col2)) {
                SelectionRange(row1, col1, row2, col2, viewportOffset)
            } else {
                SelectionRange(row2, col2, row1, col1, viewportOffset)
            }
        }
    }
}
