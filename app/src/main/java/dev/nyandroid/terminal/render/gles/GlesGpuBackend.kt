package dev.nyandroid.terminal.render.gles

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import android.view.Surface
import dev.nyandroid.terminal.emulator.FrameSnapshot
import dev.nyandroid.terminal.emulator.TerminalColors
import dev.nyandroid.terminal.emulator.TerminalGrid
import dev.nyandroid.terminal.font.GlyphRasterizer
import dev.nyandroid.terminal.render.GpuBackend
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL ES 3.x implementation of [GpuBackend].
 *
 * Rendering model (kitty-inspired): one instanced unit quad per cell carrying
 * grid position, fg/bg, glyph UV, cell width, and a packed decoration mask +
 * underline colour. A single `glDrawArraysInstanced` paints the whole screen;
 * the fragment shader blends glyph coverage over the background and draws
 * underline/strikethrough/overline decorations procedurally.
 *
 * On top of the cell pass:
 *  - only the dirty row span is re-uploaded each frame (partial `glBufferSubData`);
 *  - ligatures render as multi-cell sprites;
 *  - colour emoji and kitty-graphics images render as RGBA textured quads.
 */
class GlesGpuBackend(rasterizer: GlyphRasterizer) : GpuBackend {

    private var rasterizer = rasterizer
    private var metrics = rasterizer.metrics
    private val egl = EglCore()
    private var atlas = GlyphAtlas(rasterizer)

    /** A new rasteriser to swap in on the render thread (live font resize). */
    @Volatile
    private var pendingRasterizer: GlyphRasterizer? = null

    /** Schedules a font/metrics change; applied at the next frame. */
    fun scheduleFontChange(newRasterizer: GlyphRasterizer) {
        pendingRasterizer = newRasterizer
    }

    private fun applyFontChange(newRasterizer: GlyphRasterizer) {
        atlas.release()
        if (emojiTextures.isNotEmpty()) {
            GLES30.glDeleteTextures(emojiTextures.size, emojiTextures.values.toIntArray(), 0)
            emojiTextures.clear()
        }
        rasterizer = newRasterizer
        metrics = newRasterizer.metrics
        atlas = GlyphAtlas(newRasterizer)
        atlas.init()
        lastCols = 0; lastRows = 0 // force full rebuild
    }

    private var program: ShaderProgram? = null
    private var imageProgram: ShaderProgram? = null
    private var vao = 0
    private var cornerVbo = 0
    private var instanceVbo = 0
    private var cursorVao = 0
    private var cursorVbo = 0
    private var imageVao = 0
    private var imageVbo = 0

    private var uCellPx = -1
    private var uViewportPx = -1
    private var uAtlas = -1
    private var uBgOpacity = -1
    private var uImgViewport = -1
    private var uImgTex = -1

    /** Background opacity: 1.0 = opaque (default). */
    var backgroundOpacity = 1.0f

    /** Whether programming ligatures are rendered. */
    var ligaturesEnabled = true

    private var viewportW = 0
    private var viewportH = 0

    private var cols = 0
    private var rows = 0
    private var lastCols = 0
    private var lastRows = 0

    private var instanceData = FloatArray(0)
    private var instanceBuffer: FloatBuffer = FloatBuffer.allocate(0)
    private var rowCovered = BooleanArray(0)

    // RGBA texture caches for colour emoji and graphics images.
    private val emojiTextures = HashMap<Long, Int>()
    private val graphicsTextures = HashMap<Int, Int>()
    private val emojiDraws = ArrayList<EmojiDraw>()
    private var imageQuad = ByteBuffer.allocateDirect(16 * 4)
        .order(ByteOrder.nativeOrder()).asFloatBuffer()

    private class EmojiDraw(
        val codePoint: Int, val bold: Boolean, val italic: Boolean,
        val col: Int, val row: Int, val wCells: Int,
    )

    override fun onSurfaceCreated(surface: Surface, width: Int, height: Int) {
        egl.createSurface(surface)
        viewportW = width
        viewportH = height

        program = ShaderProgram(VERTEX_SRC, FRAGMENT_SRC).also {
            uCellPx = it.uniform("u_cellPx")
            uViewportPx = it.uniform("u_viewportPx")
            uAtlas = it.uniform("u_atlas")
            uBgOpacity = it.uniform("u_bgOpacity")
        }
        imageProgram = ShaderProgram(IMG_VERTEX_SRC, IMG_FRAGMENT_SRC).also {
            uImgViewport = it.uniform("u_viewportPx")
            uImgTex = it.uniform("u_tex")
        }
        atlas.init()
        setupBuffers()

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glViewport(0, 0, width, height)
        lastCols = 0; lastRows = 0
    }

    private fun setupBuffers() {
        val corners = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        val cornerBuf = ByteBuffer.allocateDirect(corners.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(corners).also { it.flip() }

        val vbos = IntArray(4)
        GLES30.glGenBuffers(4, vbos, 0)
        cornerVbo = vbos[0]; instanceVbo = vbos[1]; cursorVbo = vbos[2]; imageVbo = vbos[3]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cornerVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, corners.size * 4, cornerBuf, GLES30.GL_STATIC_DRAW)

        val vaos = IntArray(3)
        GLES30.glGenVertexArrays(3, vaos, 0)
        vao = vaos[0]; cursorVao = vaos[1]; imageVao = vaos[2]

        // Cell VAO + cursor VAO share the corner buffer and the instance layout.
        bindCellVao(vao, instanceVbo)
        bindCellVao(cursorVao, cursorVbo)

        // Image VAO: interleaved pos(2)+uv(2).
        GLES30.glBindVertexArray(imageVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, imageVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 16 * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, 8)
        GLES30.glBindVertexArray(0)
    }

    private fun bindCellVao(vaoId: Int, instVbo: Int) {
        GLES30.glBindVertexArray(vaoId)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cornerVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glVertexAttribDivisor(0, 0)

        val stride = FLOATS_PER_INSTANCE * 4
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instVbo)
        bindInstanceAttrib(1, 2, 0, stride)   // a_gridPos
        bindInstanceAttrib(2, 4, 2, stride)   // a_uv
        bindInstanceAttrib(3, 3, 6, stride)   // a_fg
        bindInstanceAttrib(4, 3, 9, stride)   // a_bg
        bindInstanceAttrib(5, 1, 12, stride)  // a_cellWidth
        bindInstanceAttrib(6, 1, 13, stride)  // a_deco
        bindInstanceAttrib(7, 3, 14, stride)  // a_ulColor
        bindInstanceAttrib(8, 1, 17, stride)  // a_box
        GLES30.glBindVertexArray(0)
    }

    private fun bindInstanceAttrib(location: Int, size: Int, floatOffset: Int, stride: Int) {
        GLES30.glEnableVertexAttribArray(location)
        GLES30.glVertexAttribPointer(location, size, GLES30.GL_FLOAT, false, stride, floatOffset * 4)
        GLES30.glVertexAttribDivisor(location, 1)
    }

    override fun onSurfaceChanged(width: Int, height: Int) {
        viewportW = width
        viewportH = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun renderFrame(frame: FrameSnapshot) {
        val program = this.program ?: return
        pendingRasterizer?.let { applyFontChange(it); pendingRasterizer = null }
        cols = frame.cols
        rows = frame.rows
        val cellCount = cols * rows

        val bgR = ((TerminalColors.DEFAULT_BG shr 16) and 0xFF) / 255f
        val bgG = ((TerminalColors.DEFAULT_BG shr 8) and 0xFF) / 255f
        val bgB = (TerminalColors.DEFAULT_BG and 0xFF) / 255f
        GLES30.glClearColor(bgR, bgG, bgB, backgroundOpacity)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        if (cellCount == 0) { egl.swapBuffers(); return }

        ensureCapacity(cellCount)
        val blank = atlas.spriteFor(' '.code, bold = false, italic = false)

        val geometryChanged = cols != lastCols || rows != lastRows
        val full = geometryChanged
        val dt = if (full) 0 else frame.dirtyTop.coerceIn(0, rows - 1)
        val db = if (full) rows - 1 else frame.dirtyBottom.coerceIn(-1, rows - 1)

        if (full || db >= dt) {
            for (r in dt..db) buildRow(r, frame, blank)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
            if (full) {
                instanceBuffer.clear()
                instanceBuffer.put(instanceData, 0, cellCount * FLOATS_PER_INSTANCE)
                instanceBuffer.flip()
                GLES30.glBufferData(
                    GLES30.GL_ARRAY_BUFFER, cellCount * FLOATS_PER_INSTANCE * 4,
                    instanceBuffer, GLES30.GL_DYNAMIC_DRAW,
                )
            } else {
                val startFloat = dt * cols * FLOATS_PER_INSTANCE
                val floatCount = (db - dt + 1) * cols * FLOATS_PER_INSTANCE
                instanceBuffer.clear()
                instanceBuffer.put(instanceData, startFloat, floatCount)
                instanceBuffer.flip()
                GLES30.glBufferSubData(
                    GLES30.GL_ARRAY_BUFFER, startFloat * 4, floatCount * 4, instanceBuffer,
                )
            }
        }
        lastCols = cols; lastRows = rows

        program.use()
        GLES30.glUniform2f(uCellPx, metrics.width.toFloat(), metrics.height.toFloat())
        GLES30.glUniform2f(uViewportPx, viewportW.toFloat(), viewportH.toFloat())
        GLES30.glUniform1i(uAtlas, 0)
        GLES30.glUniform1f(uBgOpacity, backgroundOpacity)
        atlas.bind(0)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, cellCount)
        GLES30.glBindVertexArray(0)

        drawNonBlockCursor(frame, program)
        collectEmoji(frame)
        drawImages(frame)

        egl.swapBuffers()
    }

    /** Scans the whole grid each frame so emoji on unchanged rows still draw. */
    private fun collectEmoji(frame: FrameSnapshot) {
        emojiDraws.clear()
        val cellCount = cols * rows
        for (i in 0 until cellCount) {
            val cpv = frame.codePoints[i]
            if (!isColorEmoji(cpv)) continue
            val flags = frame.styleFlags[i]
            val bold = flags and TerminalGrid.BOLD != 0
            val italic = flags and TerminalGrid.ITALIC != 0
            val w = if (flags and TerminalGrid.WIDE != 0) 2 else 1
            emojiDraws.add(EmojiDraw(cpv, bold, italic, i % cols, i / cols, w))
        }
    }

    /** Builds the instance data for one row in place (handles wide/ligature/emoji). */
    private fun buildRow(row: Int, frame: FrameSnapshot, blank: GlyphAtlas.Sprite) {
        java.util.Arrays.fill(rowCovered, false)
        val rowStart = row * cols
        val rowEnd = rowStart + cols
        var c = 0
        while (c < cols) {
            val i = rowStart + c
            val base = i * FLOATS_PER_INSTANCE
            if (rowCovered[c]) { degenerate(base); c++; continue }

            val cpv = frame.codePoints[i]
            val flags = frame.styleFlags[i]
            if (cpv == TerminalGrid.WIDE_DUMMY) { degenerate(base); c++; continue }

            val bold = flags and TerminalGrid.BOLD != 0
            val italic = flags and TerminalGrid.ITALIC != 0
            val fg = frame.fg[i]
            val bg = frame.bg[i]
            val deco = (flags and DECO_MASK).toFloat()
            val ul = frame.underlineColor[i].let { if (it < 0) fg else it }
            val wide = flags and TerminalGrid.WIDE != 0

            if (isColorEmoji(cpv)) {
                // Background only; the colour glyph is drawn in the image pass
                // (collected by a full-grid scan, not per dirty row).
                val w = if (wide) 2 else 1
                writeInstance(base, c, row, w, blank, fg, bg, deco, ul)
                for (k in 1 until w) if (c + k < cols) rowCovered[c + k] = true
                c += w; continue
            }
            if (wide) {
                val sprite = atlas.spriteFor(cpv, bold, italic, wide = true, combining = frame.combining[i])
                writeInstance(base, c, row, 2, sprite, fg, bg, deco, ul)
                if (c + 1 < cols) rowCovered[c + 1] = true
                c += 2; continue
            }
            // Box-drawing / block elements: render procedurally, not via atlas (#32).
            val box = BoxGlyphs.codeFor(cpv)
            if (box != 0) {
                writeInstance(base, c, row, 1, blank, fg, bg, deco, ul, box)
                c++; continue
            }
            if (ligaturesEnabled && cpv in 0x21..0x7E) {
                val runLen = Ligatures.matchAt(frame.codePoints, i, rowEnd)
                if (runLen >= 2 && uniformRun(frame, i, runLen, flags, fg)) {
                    val cps = IntArray(runLen) { frame.codePoints[i + it] }
                    val sprite = atlas.ligatureSprite(cps, bold, italic, runLen)
                    writeInstance(base, c, row, runLen, sprite, fg, bg, deco, ul)
                    for (k in 1 until runLen) rowCovered[c + k] = true
                    c += runLen; continue
                }
            }
            val sprite = atlas.spriteFor(cpv, bold, italic, combining = frame.combining[i])
            writeInstance(base, c, row, 1, sprite, fg, bg, deco, ul)
            c++
        }
    }

    private fun uniformRun(frame: FrameSnapshot, start: Int, len: Int, flags: Int, fg: Int): Boolean {
        for (k in 1 until len) {
            if (frame.styleFlags[start + k] != flags || frame.fg[start + k] != fg) return false
        }
        return true
    }

    private fun writeInstance(
        base: Int, col: Int, row: Int, cellWidth: Int,
        sprite: GlyphAtlas.Sprite, fg: Int, bg: Int, deco: Float, ul: Int, box: Int = 0,
    ) {
        val d = instanceData
        d[base] = col.toFloat()
        d[base + 1] = row.toFloat()
        d[base + 2] = sprite.u0; d[base + 3] = sprite.v0
        d[base + 4] = sprite.u1; d[base + 5] = sprite.v1
        d[base + 6] = ((fg shr 16) and 0xFF) / 255f
        d[base + 7] = ((fg shr 8) and 0xFF) / 255f
        d[base + 8] = (fg and 0xFF) / 255f
        d[base + 9] = ((bg shr 16) and 0xFF) / 255f
        d[base + 10] = ((bg shr 8) and 0xFF) / 255f
        d[base + 11] = (bg and 0xFF) / 255f
        d[base + 12] = cellWidth.toFloat()
        d[base + 13] = deco
        d[base + 14] = ((ul shr 16) and 0xFF) / 255f
        d[base + 15] = ((ul shr 8) and 0xFF) / 255f
        d[base + 16] = (ul and 0xFF) / 255f
        d[base + 17] = box.toFloat()
    }

    /** Zero-area instance (cellWidth 0) so a covered cell draws nothing. */
    private fun degenerate(base: Int) {
        for (k in 0 until FLOATS_PER_INSTANCE) instanceData[base + k] = 0f
    }

    private fun drawNonBlockCursor(frame: FrameSnapshot, program: ShaderProgram) {
        if (frame.cursorRow < 0 || frame.cursorCol < 0) return
        val shape = frame.cursorShape
        if (shape <= TerminalGrid.CURSOR_BLOCK_STEADY) return // block baked into snapshot

        if (shape % 2 == 1) { // blinking shapes
            if ((System.currentTimeMillis() / CURSOR_BLINK_MS) % 2 == 1L) return
        }

        val cellW = metrics.width.toFloat()
        val cellH = metrics.height.toFloat()
        val col = frame.cursorCol.toFloat()
        val row = frame.cursorRow.toFloat()
        val cr = ((TerminalColors.CURSOR shr 16) and 0xFF) / 255f
        val cg = ((TerminalColors.CURSOR shr 8) and 0xFF) / 255f
        val cb = (TerminalColors.CURSOR and 0xFF) / 255f
        val blank = atlas.spriteFor(' '.code, bold = false, italic = false)
        val isBeam = shape >= TerminalGrid.CURSOR_BEAM_BLINK

        val cursorData = floatArrayOf(
            col, row, blank.u0, blank.v0, blank.u1, blank.v1,
            cr, cg, cb, cr, cg, cb, 1f, 0f, cr, cg, cb, 0f,
        )

        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        val px = (col * cellW).toInt()
        val py = viewportH - ((row + 1) * cellH).toInt()
        if (isBeam) GLES30.glScissor(px, py, 2, cellH.toInt())
        else GLES30.glScissor(px, py, cellW.toInt(), 2)

        program.use()
        GLES30.glUniform2f(uCellPx, cellW, cellH)
        GLES30.glUniform2f(uViewportPx, viewportW.toFloat(), viewportH.toFloat())
        GLES30.glUniform1i(uAtlas, 0)
        GLES30.glUniform1f(uBgOpacity, 1f)
        atlas.bind(0)
        GLES30.glBindVertexArray(cursorVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cursorVbo)
        instanceBuffer.clear(); instanceBuffer.put(cursorData); instanceBuffer.flip()
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, cursorData.size * 4, instanceBuffer, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, 1)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    }

    /** Draws colour-emoji glyphs and kitty-graphics images as RGBA quads. */
    private fun drawImages(frame: FrameSnapshot) {
        val imgProgram = imageProgram ?: return
        if (emojiDraws.isEmpty() && frame.graphics.isEmpty()) return

        imgProgram.use()
        GLES30.glUniform2f(uImgViewport, viewportW.toFloat(), viewportH.toFloat())
        GLES30.glUniform1i(uImgTex, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindVertexArray(imageVao)
        val cellW = metrics.width.toFloat()
        val cellH = metrics.height.toFloat()

        for (e in emojiDraws) {
            val tex = emojiTexture(e)
            if (tex == 0) continue
            drawTexturedQuad(tex, e.col * cellW, e.row * cellH, e.wCells * cellW, cellH)
        }
        for (g in frame.graphics) {
            val tex = graphicsTexture(g)
            if (tex == 0) continue
            val w = (g.width.coerceAtLeast(1)) * cellW
            val h = (g.height.coerceAtLeast(1)) * cellH
            drawTexturedQuad(tex, g.col * cellW, g.row * cellH, w, h)
        }
        GLES30.glBindVertexArray(0)
    }

    private fun drawTexturedQuad(tex: Int, x: Float, y: Float, w: Float, h: Float) {
        val quad = floatArrayOf(
            x, y, 0f, 0f,
            x + w, y, 1f, 0f,
            x, y + h, 0f, 1f,
            x + w, y + h, 1f, 1f,
        )
        imageQuad.clear(); imageQuad.put(quad); imageQuad.flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, imageVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, imageQuad, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun emojiTexture(e: EmojiDraw): Int {
        val key = (e.codePoint.toLong() and 0xFFFFFFFFL) or
            ((if (e.bold) 1L else 0L) shl 40) or ((if (e.italic) 1L else 0L) shl 41)
        emojiTextures[key]?.let { return it }
        val bmp = try {
            rasterizer.rasterizeColorBitmap(e.codePoint, e.wCells)
        } catch (_: Throwable) { return 0 }
        val tex = uploadTexture(bmp)
        bmp.recycle()
        emojiTextures[key] = tex
        return tex
    }

    private fun graphicsTexture(g: TerminalGrid.GraphicsPlacement): Int {
        graphicsTextures[g.id]?.let { return it }
        val bmp = try {
            BitmapFactory.decodeByteArray(g.data, 0, g.data.size)
        } catch (_: Throwable) { null } ?: return 0
        val tex = uploadTexture(bmp)
        bmp.recycle()
        graphicsTextures[g.id] = tex
        return tex
    }

    private fun uploadTexture(bmp: Bitmap): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val tex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        return tex
    }

    private fun ensureCapacity(cellCount: Int) {
        val needed = cellCount * FLOATS_PER_INSTANCE
        if (instanceData.size < needed) {
            instanceData = FloatArray(needed)
            instanceBuffer = ByteBuffer.allocateDirect(needed * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
        if (rowCovered.size < cols) rowCovered = BooleanArray(cols)
    }

    override fun onSurfaceDestroyed() {
        program?.release()
        imageProgram?.release()
        atlas.release()
        if (vao != 0 || cursorVao != 0 || imageVao != 0) {
            GLES30.glDeleteVertexArrays(3, intArrayOf(vao, cursorVao, imageVao), 0)
        }
        GLES30.glDeleteBuffers(4, intArrayOf(cornerVbo, instanceVbo, cursorVbo, imageVbo), 0)
        if (emojiTextures.isNotEmpty()) {
            GLES30.glDeleteTextures(emojiTextures.size, emojiTextures.values.toIntArray(), 0)
            emojiTextures.clear()
        }
        if (graphicsTextures.isNotEmpty()) {
            GLES30.glDeleteTextures(graphicsTextures.size, graphicsTextures.values.toIntArray(), 0)
            graphicsTextures.clear()
        }
        program = null; imageProgram = null
        vao = 0; cursorVao = 0; imageVao = 0
        cornerVbo = 0; instanceVbo = 0; cursorVbo = 0; imageVbo = 0
        lastCols = 0; lastRows = 0
        egl.release()
    }

    private companion object {
        const val FLOATS_PER_INSTANCE = 18
        const val CURSOR_BLINK_MS = 530L

        // Decoration bits we forward to the shader (must match TerminalGrid).
        const val DECO_MASK = TerminalGrid.UNDERLINE or TerminalGrid.STRIKETHROUGH or
            TerminalGrid.OVERLINE or TerminalGrid.DOUBLE_UNDERLINE or
            TerminalGrid.CURLY_UNDERLINE or TerminalGrid.DOTTED_UNDERLINE or
            TerminalGrid.DASHED_UNDERLINE

        fun isColorEmoji(cp: Int): Boolean =
            cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF

        val VERTEX_SRC = """
            #version 300 es
            layout(location = 0) in vec2 a_corner;
            layout(location = 1) in vec2 a_gridPos;
            layout(location = 2) in vec4 a_uv;
            layout(location = 3) in vec3 a_fg;
            layout(location = 4) in vec3 a_bg;
            layout(location = 5) in float a_cellWidth;
            layout(location = 6) in float a_deco;
            layout(location = 7) in vec3 a_ulColor;
            layout(location = 8) in float a_box;
            uniform vec2 u_cellPx;
            uniform vec2 u_viewportPx;
            out vec2 v_uv;
            out vec2 v_local;
            out vec3 v_fg;
            out vec3 v_bg;
            flat out int v_deco;
            out vec3 v_ulColor;
            out float v_cellW;
            flat out int v_box;
            void main() {
                vec2 scaledCorner = vec2(a_corner.x * a_cellWidth, a_corner.y);
                vec2 px = (a_gridPos + scaledCorner) * u_cellPx;
                vec2 ndc = vec2(px.x / u_viewportPx.x * 2.0 - 1.0,
                                1.0 - px.y / u_viewportPx.y * 2.0);
                gl_Position = vec4(ndc, 0.0, 1.0);
                v_uv = mix(a_uv.xy, a_uv.zw, a_corner);
                v_local = a_corner;
                v_fg = a_fg;
                v_bg = a_bg;
                v_deco = int(a_deco + 0.5);
                v_ulColor = a_ulColor;
                v_cellW = a_cellWidth;
                v_box = int(a_box + 0.5);
            }
        """.trimIndent()

        val FRAGMENT_SRC = """
            #version 300 es
            precision mediump float;
            in vec2 v_uv;
            in vec2 v_local;
            in vec3 v_fg;
            in vec3 v_bg;
            flat in int v_deco;
            in vec3 v_ulColor;
            in float v_cellW;
            flat in int v_box;
            uniform sampler2D u_atlas;
            uniform vec2 u_cellPx;
            uniform float u_bgOpacity;
            out vec4 fragColor;

            const int UNDERLINE = 8;
            const int STRIKE = 64;
            const int OVERLINE = 128;
            const int DOUBLE_UL = 256;
            const int CURLY_UL = 512;
            const int DOTTED_UL = 1024;
            const int DASHED_UL = 2048;

            // Procedural box-drawing / block-element coverage (#32).
            float boxCoverage(int box, vec2 local, vec2 cellPx, float cellW) {
                float w = cellPx.x * max(cellW, 1.0);
                float h = cellPx.y;
                float px = local.x * w;
                float py = local.y * h;
                float cx = w * 0.5;
                float cy = h * 0.5;
                if (box <= 15) {
                    float t = max(1.0, h / 10.0);
                    float cov = 0.0;
                    if (abs(py - cy) <= t) {
                        if ((box & 1) != 0 && px <= cx) cov = 1.0;
                        if ((box & 2) != 0 && px >= cx) cov = 1.0;
                    }
                    if (abs(px - cx) <= t) {
                        if ((box & 4) != 0 && py <= cy) cov = 1.0;
                        if ((box & 8) != 0 && py >= cy) cov = 1.0;
                    }
                    return cov;
                }
                if (box == 16) return 1.0;                       // full block
                if (box == 17) return px <= cx ? 1.0 : 0.0;      // left half
                if (box == 18) return px >= cx ? 1.0 : 0.0;      // right half
                if (box == 19) return py <= cy ? 1.0 : 0.0;      // top half
                if (box == 20) return py >= cy ? 1.0 : 0.0;      // bottom half
                if (box == 25) return 0.25;                      // light shade
                if (box == 26) return 0.5;                       // medium shade
                if (box == 27) return 0.75;                      // dark shade
                return 0.0;
            }

            void main() {
                float coverage = (v_box > 0)
                    ? boxCoverage(v_box, v_local, u_cellPx, v_cellW)
                    : texture(u_atlas, v_uv).r;
                vec3 color = mix(v_bg, v_fg, coverage);
                float alpha = mix(u_bgOpacity, 1.0, coverage);

                float xPx = v_local.x * u_cellPx.x * max(v_cellW, 1.0);
                float yPx = v_local.y * u_cellPx.y;
                float th = max(1.0, u_cellPx.y / 14.0);
                bool decorated = false;

                int ulBits = UNDERLINE | DOUBLE_UL | CURLY_UL | DOTTED_UL | DASHED_UL;
                if ((v_deco & ulBits) != 0) {
                    if ((v_deco & CURLY_UL) != 0) {
                        float cy = u_cellPx.y - th * 1.5;
                        float w = sin(xPx / u_cellPx.x * 6.2831853);
                        if (abs(yPx - (cy + th * w)) <= th) decorated = true;
                    } else if ((v_deco & DOUBLE_UL) != 0) {
                        if (yPx >= u_cellPx.y - th ||
                            (yPx >= u_cellPx.y - 3.0 * th && yPx <= u_cellPx.y - 2.0 * th))
                            decorated = true;
                    } else if (yPx >= u_cellPx.y - th) {
                        bool on = true;
                        if ((v_deco & DOTTED_UL) != 0) on = mod(floor(xPx / (th * 1.5)), 2.0) < 1.0;
                        else if ((v_deco & DASHED_UL) != 0) on = mod(floor(xPx / (th * 4.0)), 2.0) < 1.0;
                        if (on) decorated = true;
                    }
                    if (decorated) color = v_ulColor;
                }
                if ((v_deco & STRIKE) != 0 && abs(yPx - u_cellPx.y * 0.55) <= th * 0.5) {
                    color = v_fg; decorated = true;
                }
                if ((v_deco & OVERLINE) != 0 && yPx <= th) {
                    color = v_fg; decorated = true;
                }
                if (decorated) alpha = 1.0;
                fragColor = vec4(color, alpha);
            }
        """.trimIndent()

        val IMG_VERTEX_SRC = """
            #version 300 es
            layout(location = 0) in vec2 a_pos;
            layout(location = 1) in vec2 a_uv;
            uniform vec2 u_viewportPx;
            out vec2 v_uv;
            void main() {
                vec2 ndc = vec2(a_pos.x / u_viewportPx.x * 2.0 - 1.0,
                                1.0 - a_pos.y / u_viewportPx.y * 2.0);
                gl_Position = vec4(ndc, 0.0, 1.0);
                v_uv = a_uv;
            }
        """.trimIndent()

        val IMG_FRAGMENT_SRC = """
            #version 300 es
            precision mediump float;
            in vec2 v_uv;
            uniform sampler2D u_tex;
            out vec4 fragColor;
            void main() { fragColor = texture(u_tex, v_uv); }
        """.trimIndent()
    }
}
