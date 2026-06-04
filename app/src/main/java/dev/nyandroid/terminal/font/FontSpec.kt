package dev.nyandroid.terminal.font

import android.graphics.Typeface

/**
 * Describes the monospace font used to render cells.
 *
 * The PoC uses the platform monospace face; swapping in a bundled font (e.g. a
 * Nerd Font for powerline glyphs) is just a different [typeface] here.
 */
data class FontSpec(
    val textSizePx: Float,
    val typeface: Typeface = Typeface.MONOSPACE,
)
