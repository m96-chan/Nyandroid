package dev.nyandroid.terminal.input

import java.nio.charset.StandardCharsets

/**
 * Encodes mouse events into xterm-compatible escape sequences.
 *
 * Supports SGR extended format (?1006) which handles coordinates > 223
 * and distinguishes press from release:
 *   CSI < Pb ; Px ; Py M   (press/motion)
 *   CSI < Pb ; Px ; Py m   (release)
 *
 * Also supports legacy X10 format for compatibility:
 *   CSI M Cb Cx Cy  (all encoded as Cb+32, Cx+32+1, Cy+32+1)
 */
object MouseEncoder {

    const val BUTTON_LEFT = 0
    const val BUTTON_MIDDLE = 1
    const val BUTTON_RIGHT = 2
    const val BUTTON_RELEASE = 3
    const val BUTTON_SCROLL_UP = 64
    const val BUTTON_SCROLL_DOWN = 65
    const val MODIFIER_DRAG = 32

    /**
     * Encodes a mouse event.
     *
     * @param button Button code (BUTTON_* constants, possibly OR'd with MODIFIER_DRAG).
     * @param col 1-based column.
     * @param row 1-based row.
     * @param release true if this is a button release event.
     * @param sgrFormat true to use SGR extended format (?1006).
     */
    fun encode(button: Int, col: Int, row: Int, release: Boolean, sgrFormat: Boolean): ByteArray {
        return if (sgrFormat) {
            encodeSgr(button, col, row, release)
        } else {
            encodeLegacy(button, col, row)
        }
    }

    private fun encodeSgr(button: Int, col: Int, row: Int, release: Boolean): ByteArray {
        val final = if (release) 'm' else 'M'
        return "\u001B[<${button};${col};${row}${final}".toByteArray(StandardCharsets.US_ASCII)
    }

    private fun encodeLegacy(button: Int, col: Int, row: Int): ByteArray {
        val cb = (button + 32).coerceIn(32, 255).toByte()
        val cx = (col + 32).coerceIn(33, 255).toByte()
        val cy = (row + 32).coerceIn(33, 255).toByte()
        return byteArrayOf(0x1B, '['.code.toByte(), 'M'.code.toByte(), cb, cx, cy)
    }
}
