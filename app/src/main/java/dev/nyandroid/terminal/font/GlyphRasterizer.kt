package dev.nyandroid.terminal.font

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * Rasterises individual glyphs into single-channel coverage bitmaps the size of
 * one cell. The GPU backend uploads these into its glyph atlas.
 *
 * Uses [NerdFontFamily] for style-specific typefaces (Regular, Bold, Italic,
 * BoldItalic) so each variant uses the actual font file rather than synthetic
 * styling.
 */
class GlyphRasterizer(
    private val spec: FontSpec,
    val metrics: CellMetrics,
    private val fontFamily: NerdFontFamily? = null,
) {
    // Index by style bits: 0 plain, 1 bold, 2 italic, 3 bold-italic.
    private val paints = arrayOfNulls<Paint>(4)
    private val reusableBitmap: Bitmap =
        Bitmap.createBitmap(metrics.width, metrics.height, Bitmap.Config.ALPHA_8)
    private val canvas = Canvas(reusableBitmap)

    private fun paintFor(bold: Boolean, italic: Boolean): Paint {
        val key = (if (bold) 1 else 0) or (if (italic) 2 else 0)
        paints[key]?.let { return it }
        val typeface = if (fontFamily != null) {
            fontFamily.forStyle(bold, italic)
        } else {
            val style = when {
                bold && italic -> android.graphics.Typeface.BOLD_ITALIC
                bold -> android.graphics.Typeface.BOLD
                italic -> android.graphics.Typeface.ITALIC
                else -> android.graphics.Typeface.NORMAL
            }
            android.graphics.Typeface.create(spec.typeface, style)
        }
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = spec.textSizePx
            color = Color.WHITE
        }.also { paints[key] = it }
    }

    /**
     * Rasterises [codePoint] into [out].
     *
     * @param glyphWidth pixel width to rasterize (cellWidth for normal,
     *   cellWidth*2 for wide/CJK characters).
     */
    fun rasterizeInto(
        codePoint: Int, bold: Boolean, italic: Boolean,
        out: java.nio.ByteBuffer, glyphWidth: Int = metrics.width,
    ): Int {
        val bmp = if (glyphWidth == metrics.width) {
            reusableBitmap
        } else {
            ensureWideBitmap(glyphWidth)
        }
        bmp.eraseColor(Color.TRANSPARENT)
        val c = if (glyphWidth == metrics.width) canvas else Canvas(bmp)
        val chars = Character.toChars(codePoint)
        c.drawText(chars, 0, chars.size, 0f, metrics.baseline.toFloat(), paintFor(bold, italic))
        out.clear()
        bmp.copyPixelsToBuffer(out)
        out.flip()
        return glyphWidth * metrics.height
    }

    private var wideBitmap: Bitmap? = null

    private fun ensureWideBitmap(width: Int): Bitmap {
        wideBitmap?.let { if (it.width == width) return it }
        val bmp = Bitmap.createBitmap(width, metrics.height, Bitmap.Config.ALPHA_8)
        wideBitmap = bmp
        return bmp
    }
}
