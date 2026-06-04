package dev.nyandroid.terminal.font

import android.graphics.Paint
import kotlin.math.ceil

/**
 * Fixed cell geometry (in device pixels) derived from a [FontSpec].
 *
 * Because the grid is monospace, every glyph occupies exactly [width] x
 * [height] pixels, which lets the glyph atlas use a trivial fixed-slot packer.
 */
class CellMetrics(
    val width: Int,
    val height: Int,
    /** Distance from the cell top to the text baseline, in pixels. */
    val baseline: Int,
) {
    companion object {
        fun measure(spec: FontSpec): CellMetrics {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = spec.typeface
                textSize = spec.textSizePx
            }
            val fm = paint.fontMetricsInt
            val height = ceil((fm.descent - fm.ascent).toDouble()).toInt().coerceAtLeast(1)
            // Advance width of a representative monospace glyph.
            val width = ceil(paint.measureText("M").toDouble()).toInt().coerceAtLeast(1)
            val baseline = -fm.ascent
            return CellMetrics(width, height, baseline)
        }
    }
}
