package dev.nyandroid.terminal.emulator

/**
 * xterm-style 256-colour palette plus terminal defaults.
 *
 * Colours are stored as packed `0xRRGGBB` ints (alpha is always opaque for
 * resolved cell colours). The emulator resolves SGR colour indices to RGB
 * here so the renderer never has to know about palettes.
 */
object TerminalColors {

    const val DEFAULT_FG = 0xD0D0D0
    const val DEFAULT_BG = 0x0B0B0B
    const val CURSOR = 0x33CC66

    /** Index of the first colour in the 256-colour cube (after the 16 base). */
    private const val CUBE_START = 16
    private const val GRAYSCALE_START = 232

    /** The 16 ANSI base colours (normal 0-7, bright 8-15). */
    private val BASE_16 = intArrayOf(
        0x000000, 0xCD0000, 0x00CD00, 0xCDCD00, 0x0000EE, 0xCD00CD, 0x00CDCD, 0xE5E5E5,
        0x7F7F7F, 0xFF0000, 0x00FF00, 0xFFFF00, 0x5C5CFF, 0xFF00FF, 0x00FFFF, 0xFFFFFF,
    )

    /** Fully expanded 256-entry palette, built once. */
    val palette: IntArray = IntArray(256).also { p ->
        for (i in 0 until 16) p[i] = BASE_16[i]
        // 6x6x6 colour cube.
        val steps = intArrayOf(0, 95, 135, 175, 215, 255)
        var idx = CUBE_START
        for (r in 0 until 6) for (g in 0 until 6) for (b in 0 until 6) {
            p[idx++] = (steps[r] shl 16) or (steps[g] shl 8) or steps[b]
        }
        // 24-step grayscale ramp.
        for (i in 0 until 24) {
            val v = 8 + i * 10
            p[GRAYSCALE_START + i] = (v shl 16) or (v shl 8) or v
        }
    }

    fun indexed(i: Int): Int = palette[i.coerceIn(0, 255)]

    fun rgb(r: Int, g: Int, b: Int): Int =
        ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
}
