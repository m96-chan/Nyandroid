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

    // Fallback fonts for glyphs missing from the primary font.
    private val fallbackPaints = arrayOfNulls<Paint>(4)
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

    private fun fallbackPaintFor(bold: Boolean, italic: Boolean): Paint {
        val key = (if (bold) 1 else 0) or (if (italic) 2 else 0)
        fallbackPaints[key]?.let { return it }
        val style = when {
            bold && italic -> android.graphics.Typeface.BOLD_ITALIC
            bold -> android.graphics.Typeface.BOLD
            italic -> android.graphics.Typeface.ITALIC
            else -> android.graphics.Typeface.NORMAL
        }
        // Use system default sans-serif which has broad Unicode coverage.
        val typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT, style,
        )
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = spec.textSizePx
            color = Color.WHITE
        }.also { fallbackPaints[key] = it }
    }

    /** Returns the best paint for the given code point, falling back if needed. */
    private fun bestPaintFor(codePoint: Int, bold: Boolean, italic: Boolean): Paint {
        val primary = paintFor(bold, italic)
        if (primary.hasGlyph(String(Character.toChars(codePoint)))) return primary
        return fallbackPaintFor(bold, italic)
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
        c.drawText(chars, 0, chars.size, 0f, metrics.baseline.toFloat(), bestPaintFor(codePoint, bold, italic))
        out.clear()
        bmp.copyPixelsToBuffer(out)
        out.flip()
        return glyphWidth * metrics.height
    }

    /**
     * Rasterises a ligature (sequence of codepoints rendered as one glyph).
     * The result spans [cellCount] cells wide.
     */
    fun rasterizeLigatureInto(
        codePoints: IntArray, bold: Boolean, italic: Boolean,
        out: java.nio.ByteBuffer, cellCount: Int,
    ): Int {
        val glyphWidth = metrics.width * cellCount
        val bmp = ensureWideBitmap(glyphWidth)
        bmp.eraseColor(Color.TRANSPARENT)
        val c = Canvas(bmp)
        val text = String(codePoints, 0, codePoints.size)
        val paint = paintFor(bold, italic)
        c.drawText(text, 0f, metrics.baseline.toFloat(), paint)
        out.clear()
        bmp.copyPixelsToBuffer(out)
        out.flip()
        return glyphWidth * metrics.height
    }

    /**
     * Checks if a sequence of codepoints would form a ligature
     * (rendered differently than individual glyphs).
     */
    fun hasLigature(codePoints: IntArray, bold: Boolean, italic: Boolean): Boolean {
        val paint = paintFor(bold, italic)
        val text = String(codePoints, 0, codePoints.size)
        val totalWidth = paint.measureText(text)
        var sumWidth = 0f
        for (cp in codePoints) {
            sumWidth += paint.measureText(String(Character.toChars(cp)))
        }
        return totalWidth < sumWidth * 0.95f
    }

    private var wideBitmap: Bitmap? = null

    private fun ensureWideBitmap(width: Int): Bitmap {
        wideBitmap?.let { if (it.width == width) return it }
        val bmp = Bitmap.createBitmap(width, metrics.height, Bitmap.Config.ALPHA_8)
        wideBitmap = bmp
        return bmp
    }

    // --- Colour glyphs (emoji) ----------------------------------------------

    private var colorPaint: Paint? = null

    /**
     * Rasterises a colour glyph (emoji) into a fresh ARGB_8888 bitmap spanning
     * [widthCells] cells. The system emoji font (via [android.graphics.Typeface.DEFAULT])
     * supplies colour glyphs; the single-channel atlas can't hold these, so the
     * GPU backend uploads the result as an RGBA texture.
     */
    fun rasterizeColorBitmap(codePoint: Int, widthCells: Int): Bitmap {
        val w = (metrics.width * widthCells).coerceAtLeast(1)
        val h = metrics.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = colorPaint ?: Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.DEFAULT
            textSize = spec.textSizePx
        }.also { colorPaint = it }
        val chars = Character.toChars(codePoint)
        c.drawText(chars, 0, chars.size, 0f, metrics.baseline.toFloat(), paint)
        return bmp
    }
}
