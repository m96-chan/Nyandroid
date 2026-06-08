package dev.nyandroid.terminal.render.gles

import android.opengl.GLES30
import android.util.Log
import dev.nyandroid.terminal.font.GlyphRasterizer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Single-channel GPU glyph cache, kitty-style: each unique (codepoint, style)
 * is rasterised once and stored as a sprite in a texture atlas; cells then
 * reference it by UV. Render cost scales with unique glyphs, not characters.
 *
 * Since the grid is monospace, every sprite is exactly one cell, so the atlas
 * is a simple fixed grid of slots — no rectangle packer needed.
 */
class GlyphAtlas(private val rasterizer: GlyphRasterizer) {

    class Sprite(val u0: Float, val v0: Float, val u1: Float, val v1: Float)

    private var textureId = 0
    private val cellW = rasterizer.metrics.width
    private val cellH = rasterizer.metrics.height
    private val slotsPerRow = (ATLAS_SIZE / cellW).coerceAtLeast(1)
    private val slotsPerCol = (ATLAS_SIZE / cellH).coerceAtLeast(1)
    private val capacity = slotsPerRow * slotsPerCol

    private val cache = HashMap<Long, Sprite>()
    private var nextSlot = 0

    private val coverage: ByteBuffer =
        ByteBuffer.allocateDirect(cellW * cellH).order(ByteOrder.nativeOrder())
    /** Buffer large enough for wide (2-cell) glyphs. */
    private val wideCoverage: ByteBuffer =
        ByteBuffer.allocateDirect(cellW * 2 * cellH).order(ByteOrder.nativeOrder())

    fun init() {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8,
            ATLAS_SIZE, ATLAS_SIZE, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, null,
        )
        // NEAREST avoids coverage bleeding between adjacent sprites.
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    fun bind(unit: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
    }

    fun spriteFor(codePoint: Int, bold: Boolean, italic: Boolean, wide: Boolean = false): Sprite {
        val styleBits = (if (bold) 1 else 0) or (if (italic) 2 else 0) or (if (wide) 4 else 0)
        val key = (codePoint.toLong() and 0xFFFFFFFFL) or (styleBits.toLong() shl 40)
        cache[key]?.let { return it }

        val glyphW = if (wide) cellW * 2 else cellW
        val slotsNeeded = if (wide) 2 else 1

        if (nextSlot + slotsNeeded > capacity) {
            Log.w(TAG, "Glyph atlas full ($capacity slots); clearing cache")
            cache.clear()
            nextSlot = 0
        }

        val slot = nextSlot
        nextSlot += slotsNeeded
        val sc = slot % slotsPerRow
        val sr = slot / slotsPerRow

        // If wide glyph would overflow the row, move to next row.
        val x: Int
        val y: Int
        if (wide && sc + 2 > slotsPerRow) {
            // Skip remaining slot in this row.
            nextSlot = (sr + 1) * slotsPerRow + slotsNeeded
            x = 0
            y = (sr + 1) * cellH
        } else {
            x = sc * cellW
            y = sr * cellH
        }

        rasterizer.rasterizeInto(codePoint, bold, italic, wideCoverage, glyphW)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, x, y, glyphW, cellH,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, wideCoverage,
        )

        val sprite = Sprite(
            x.toFloat() / ATLAS_SIZE,
            y.toFloat() / ATLAS_SIZE,
            (x + glyphW).toFloat() / ATLAS_SIZE,
            (y + cellH).toFloat() / ATLAS_SIZE,
        )
        cache[key] = sprite
        return sprite
    }

    /**
     * Returns a sprite for a ligature rendered across [cellCount] cells.
     * Allocates [cellCount] consecutive atlas slots within one row.
     */
    fun ligatureSprite(codePoints: IntArray, bold: Boolean, italic: Boolean, cellCount: Int): Sprite {
        val styleBits = (if (bold) 1 else 0) or (if (italic) 2 else 0)
        var h = 1125899906842597L // FNV-ish seed
        for (cpv in codePoints) h = h * 31 + cpv
        h = h * 31 + styleBits + cellCount.toLong() * 1000003
        val key = (h and 0x0FFFFFFFFFFFFFFFL) or (1L shl 62) // mark as ligature
        cache[key]?.let { return it }

        val glyphW = cellW * cellCount
        if (nextSlot + cellCount > capacity) {
            cache.clear(); nextSlot = 0
        }
        var slot = nextSlot
        var sc = slot % slotsPerRow
        var sr = slot / slotsPerRow
        if (sc + cellCount > slotsPerRow) { // keep the run within one row
            sr += 1; sc = 0; slot = sr * slotsPerRow
        }
        nextSlot = slot + cellCount
        val x = sc * cellW
        val y = sr * cellH
        if (y + cellH > ATLAS_SIZE) { // overflow guard
            cache.clear(); nextSlot = 0
            return ligatureSprite(codePoints, bold, italic, cellCount)
        }

        val buf = ByteBuffer.allocateDirect(glyphW * cellH).order(ByteOrder.nativeOrder())
        rasterizer.rasterizeLigatureInto(codePoints, bold, italic, buf, cellCount)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexSubImage2D(
            GLES30.GL_TEXTURE_2D, 0, x, y, glyphW, cellH,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, buf,
        )
        val sprite = Sprite(
            x.toFloat() / ATLAS_SIZE, y.toFloat() / ATLAS_SIZE,
            (x + glyphW).toFloat() / ATLAS_SIZE, (y + cellH).toFloat() / ATLAS_SIZE,
        )
        cache[key] = sprite
        return sprite
    }

    fun release() {
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        cache.clear()
        nextSlot = 0
    }

    private companion object {
        const val TAG = "GlyphAtlas"
        const val ATLAS_SIZE = 1024
    }
}
