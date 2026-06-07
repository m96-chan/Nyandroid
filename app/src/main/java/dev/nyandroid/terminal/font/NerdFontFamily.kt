package dev.nyandroid.terminal.font

import android.content.Context
import android.graphics.Typeface

/**
 * Loads the four JetBrainsMono Nerd Font Mono variants from assets.
 * Falls back to the system monospace face if any variant is missing.
 */
class NerdFontFamily private constructor(
    val regular: Typeface,
    val bold: Typeface,
    val italic: Typeface,
    val boldItalic: Typeface,
) {
    fun forStyle(bold: Boolean, italic: Boolean): Typeface = when {
        bold && italic -> boldItalic
        bold -> this.bold
        italic -> this.italic
        else -> regular
    }

    companion object {
        private const val DIR = "fonts"
        private const val REGULAR = "$DIR/JetBrainsMonoNerdFontMono-Regular.ttf"
        private const val BOLD = "$DIR/JetBrainsMonoNerdFontMono-Bold.ttf"
        private const val ITALIC = "$DIR/JetBrainsMonoNerdFontMono-Italic.ttf"
        private const val BOLD_ITALIC = "$DIR/JetBrainsMonoNerdFontMono-BoldItalic.ttf"

        fun load(context: Context): NerdFontFamily {
            return try {
                NerdFontFamily(
                    regular = Typeface.createFromAsset(context.assets, REGULAR),
                    bold = Typeface.createFromAsset(context.assets, BOLD),
                    italic = Typeface.createFromAsset(context.assets, ITALIC),
                    boldItalic = Typeface.createFromAsset(context.assets, BOLD_ITALIC),
                )
            } catch (_: Exception) {
                // Fallback to system monospace.
                NerdFontFamily(
                    regular = Typeface.MONOSPACE,
                    bold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
                    italic = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC),
                    boldItalic = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC),
                )
            }
        }
    }
}
