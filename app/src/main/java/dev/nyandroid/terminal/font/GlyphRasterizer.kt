package dev.nyandroid.terminal.font

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Rasterises individual glyphs into single-channel coverage bitmaps the size of
 * one cell. The GPU backend uploads these into its glyph atlas.
 *
 * This is the only place that touches Android text APIs; everything downstream
 * deals in coverage bytes, mirroring kitty's "rasterise once, cache forever"
 * sprite model.
 */
class GlyphRasterizer(
    private val spec: FontSpec,
    val metrics: CellMetrics,
) {
    // Index by style bits: 0 plain, 1 bold, 2 italic, 3 bold-italic.
    private val paints = arrayOfNulls<Paint>(4)
    private val reusableBitmap: Bitmap =
        Bitmap.createBitmap(metrics.width, metrics.height, Bitmap.Config.ALPHA_8)
    private val canvas = Canvas(reusableBitmap)

    private fun paintFor(bold: Boolean, italic: Boolean): Paint {
        val key = (if (bold) 1 else 0) or (if (italic) 2 else 0)
        paints[key]?.let { return it }
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(spec.typeface, style)
            textSize = spec.textSizePx
            color = Color.WHITE // coverage goes into the ALPHA_8 channel
        }.also { paints[key] = it }
    }

    /**
     * Rasterises [codePoint] and copies its coverage into [out], which must be
     * at least `metrics.width * metrics.height` bytes. Returns the byte count.
     */
    fun rasterizeInto(codePoint: Int, bold: Boolean, italic: Boolean, out: java.nio.ByteBuffer): Int {
        reusableBitmap.eraseColor(Color.TRANSPARENT)
        val chars = Character.toChars(codePoint)
        canvas.drawText(chars, 0, chars.size, 0f, metrics.baseline.toFloat(), paintFor(bold, italic))
        out.clear()
        reusableBitmap.copyPixelsToBuffer(out)
        out.flip()
        return metrics.width * metrics.height
    }
}
