package dev.nyandroid.terminal.render.gles

/**
 * Programming-font ligatures. Detection is purely codepoint-sequence based
 * (a small curated table), then the matched run is rendered as one multi-cell
 * glyph via the font's shaper. Mirrors kitty's ligature handling at the level a
 * PoC needs; honoured only when ligatures are enabled.
 */
object Ligatures {

    // Longest first so longest-match wins.
    private val SEQUENCES: List<IntArray> = listOf(
        "<=>", "<->", "===", "!==", "<!--", "-->", "...", ">>=", "<<=", "|||",
        "->", "=>", "==", "!=", ">=", "<=", "<-", "::", ":=", "++", "--",
        "&&", "||", "//", "/*", "*/", "|>", "<|", ">>", "<<", "++", "##",
        "www", "=~", "!~", "?:", "?.",
    ).map { s -> IntArray(s.length) { s[it].code } }
        .sortedByDescending { it.size }

    private val MAX_LEN = SEQUENCES.maxOf { it.size }

    /**
     * Returns the length (in cells) of the longest ligature that starts at
     * [start] in [cp], within [end] (exclusive), or 0 if none. Only ASCII
     * single-width cells participate.
     */
    fun matchAt(cp: IntArray, start: Int, end: Int): Int {
        val limit = minOf(end, start + MAX_LEN)
        for (seq in SEQUENCES) {
            val n = seq.size
            if (start + n > limit) continue
            var ok = true
            for (k in 0 until n) {
                if (cp[start + k] != seq[k]) { ok = false; break }
            }
            if (ok) return n
        }
        return 0
    }
}
