package dev.nyandroid.terminal.view

import dev.nyandroid.terminal.emulator.FrameSnapshot
import dev.nyandroid.terminal.emulator.TerminalGrid

/**
 * Underlines regex-detected URLs in a rendered frame so they read as links
 * (kitty's `detect_urls`). Runs on the render thread over the snapshot arrays;
 * pure aside from the regex in [UrlDetector].
 */
object UrlHighlighter {

    fun apply(frame: FrameSnapshot) {
        val cols = frame.cols
        val rows = frame.rows
        if (cols == 0 || rows == 0) return
        val sb = StringBuilder(cols)
        for (r in 0 until rows) {
            sb.setLength(0)
            val base = r * cols
            for (c in 0 until cols) {
                val cp = frame.codePoints[base + c]
                sb.append(if (cp in 32..0x10FFFF) cp.toChar() else ' ')
            }
            val urls = UrlDetector.findUrls(sb.toString(), r)
            for (u in urls) {
                for (c in u.startCol..u.endCol.coerceAtMost(cols - 1)) {
                    frame.styleFlags[base + c] = frame.styleFlags[base + c] or TerminalGrid.UNDERLINE
                }
            }
        }
    }
}
