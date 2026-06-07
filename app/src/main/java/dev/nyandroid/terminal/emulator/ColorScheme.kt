package dev.nyandroid.terminal.emulator

/**
 * A terminal colour theme. Holds the 16 ANSI base colours, foreground,
 * background, and cursor colours. Compatible with kitty's color0-color15,
 * foreground, background, cursor_text_color settings.
 */
data class ColorScheme(
    val name: String,
    val foreground: Int,
    val background: Int,
    val cursor: Int,
    val base16: IntArray,
) {
    override fun equals(other: Any?): Boolean =
        other is ColorScheme && name == other.name
    override fun hashCode(): Int = name.hashCode()

    companion object {
        /** Parse a kitty-compatible color conf string (key-value lines). */
        fun parse(name: String, conf: String): ColorScheme {
            var fg = 0xD0D0D0
            var bg = 0x0B0B0B
            var cursor = 0x33CC66
            val base = intArrayOf(
                0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
                0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
            )
            for (line in conf.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
                val parts = trimmed.split(Regex("\\s+"), limit = 2)
                if (parts.size < 2) continue
                val key = parts[0]
                val value = parseColor(parts[1]) ?: continue
                when (key) {
                    "foreground" -> fg = value
                    "background" -> bg = value
                    "cursor" -> cursor = value
                    else -> {
                        val match = Regex("color(\\d+)").matchEntire(key)
                        if (match != null) {
                            val idx = match.groupValues[1].toInt()
                            if (idx in 0..15) base[idx] = value
                        }
                    }
                }
            }
            return ColorScheme(name, fg, bg, cursor, base)
        }

        private fun parseColor(s: String): Int? {
            val hex = s.trim().removePrefix("#")
            return hex.toIntOrNull(16)
        }

        val DEFAULT = ColorScheme(
            "Default",
            0xD0D0D0, 0x0B0B0B, 0x33CC66,
            intArrayOf(
                0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
                0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
            ),
        )

        val TOKYO_NIGHT = ColorScheme(
            "Tokyo Night",
            0xC0CAF5, 0x1A1B26, 0xC0CAF5,
            intArrayOf(
                0x15161E, 0xF7768E, 0x9ECE6A, 0xE0AF68, 0x7AA2F7, 0xBB9AF7, 0x7DCFFF, 0xA9B1D6,
                0x414868, 0xF7768E, 0x9ECE6A, 0xE0AF68, 0x7AA2F7, 0xBB9AF7, 0x7DCFFF, 0xC0CAF5,
            ),
        )

        val DRACULA = ColorScheme(
            "Dracula",
            0xF8F8F2, 0x282A36, 0xF8F8F2,
            intArrayOf(
                0x21222C, 0xFF5555, 0x50FA7B, 0xF1FA8C, 0xBD93F9, 0xFF79C6, 0x8BE9FD, 0xF8F8F2,
                0x6272A4, 0xFF6E6E, 0x69FF94, 0xFFFFA5, 0xD6ACFF, 0xFF92DF, 0xA4FFFF, 0xFFFFFF,
            ),
        )

        val SOLARIZED_DARK = ColorScheme(
            "Solarized Dark",
            0x839496, 0x002B36, 0x93A1A1,
            intArrayOf(
                0x073642, 0xDC322F, 0x859900, 0xB58900, 0x268BD2, 0xD33682, 0x2AA198, 0xEEE8D5,
                0x002B36, 0xCB4B16, 0x586E75, 0x657B83, 0x839496, 0x6C71C4, 0x93A1A1, 0xFDF6E3,
            ),
        )

        val BUILTIN_SCHEMES = listOf(DEFAULT, TOKYO_NIGHT, DRACULA, SOLARIZED_DARK)
    }
}
