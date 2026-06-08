package dev.nyandroid.terminal.render.gles

/**
 * Maps box-drawing / block-element codepoints to a compact code the shader
 * renders procedurally, so lines and blocks stay pixel-crisp regardless of the
 * font (kitty-style box rendering, #32).
 *
 * Codes:
 *  - 0: not a box glyph (use the glyph atlas)
 *  - 1..15: light line segment mask — bit0 left, bit1 right, bit2 up, bit3 down
 *  - 16: full block, 17: left half, 18: right half, 19: top half, 20: bottom half
 *  - 25/26/27: light/medium/dark shade
 */
object BoxGlyphs {

    private const val L = 1
    private const val R = 2
    private const val U = 4
    private const val D = 8

    fun codeFor(cp: Int): Int = when (cp) {
        // Light lines, corners, tees, cross.
        0x2500 -> L or R          // ─
        0x2502 -> U or D          // │
        0x250C -> R or D          // ┌
        0x2510 -> L or D          // ┐
        0x2514 -> R or U          // └
        0x2518 -> L or U          // ┘
        0x251C -> R or U or D     // ├
        0x2524 -> L or U or D     // ┤
        0x252C -> L or R or D     // ┬
        0x2534 -> L or R or U     // ┴
        0x253C -> L or R or U or D // ┼
        // Block elements.
        0x2588 -> 16              // █ full block
        0x258C -> 17              // ▌ left half
        0x2590 -> 18              // ▐ right half
        0x2580 -> 19              // ▀ upper half
        0x2584 -> 20              // ▄ lower half
        0x2591 -> 25              // ░ light shade
        0x2592 -> 26              // ▒ medium shade
        0x2593 -> 27              // ▓ dark shade
        else -> 0
    }
}
