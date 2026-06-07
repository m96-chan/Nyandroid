package dev.nyandroid.terminal.font

import android.content.Context
import android.graphics.Typeface

/**
 * Describes the monospace font used to render cells.
 *
 * By default, loads JetBrainsMono Nerd Font (Mono) from assets.
 * Falls back to the platform monospace face if the bundled font is unavailable.
 */
data class FontSpec(
    val textSizePx: Float,
    val typeface: Typeface = Typeface.MONOSPACE,
) {
    companion object {
        private const val FONT_REGULAR = "fonts/JetBrainsMonoNerdFontMono-Regular.ttf"

        /**
         * Creates a [FontSpec] with the bundled Nerd Font, falling back to
         * system monospace if loading fails.
         */
        fun create(context: Context, textSizePx: Float): FontSpec {
            val typeface = try {
                Typeface.createFromAsset(context.assets, FONT_REGULAR)
            } catch (_: Exception) {
                Typeface.MONOSPACE
            }
            return FontSpec(textSizePx, typeface)
        }
    }
}
