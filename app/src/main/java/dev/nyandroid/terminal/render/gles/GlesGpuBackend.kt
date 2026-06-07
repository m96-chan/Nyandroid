package dev.nyandroid.terminal.render.gles

import android.opengl.GLES30
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
 * Rendering model (kitty-inspired): one instanced unit quad per cell. Each
 * instance carries its grid position, fg/bg colour and the UV rect of its
 * glyph sprite in the [GlyphAtlas]. A single `glDrawArraysInstanced` draws the
 * whole screen; the fragment shader blends the glyph coverage over the cell
 * background, so background fill and text are one pass.
 */
class GlesGpuBackend(rasterizer: GlyphRasterizer) : GpuBackend {

    private val metrics = rasterizer.metrics
    private val egl = EglCore()
    private val atlas = GlyphAtlas(rasterizer)

    private var program: ShaderProgram? = null
    private var vao = 0
    private var cornerVbo = 0
    private var instanceVbo = 0

    private var uCellPx = -1
    private var uViewportPx = -1
    private var uAtlas = -1
    private var uBgOpacity = -1

    /** Background opacity: 1.0 = fully opaque (default), 0.0 = fully transparent. */
    var backgroundOpacity = 1.0f

    private var viewportW = 0
    private var viewportH = 0

    private var instanceData = FloatArray(0)
    private var instanceBuffer: FloatBuffer = FloatBuffer.allocate(0)

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
        atlas.init()
        setupBuffers()

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glViewport(0, 0, width, height)
    }

    private fun setupBuffers() {
        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vao = vaos[0]
        GLES30.glBindVertexArray(vao)

        // Static unit-quad corners as a triangle strip.
        val corners = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        val cornerBuf = ByteBuffer.allocateDirect(corners.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().put(corners).also { it.flip() }
        val vbos = IntArray(2)
        GLES30.glGenBuffers(2, vbos, 0)
        cornerVbo = vbos[0]
        instanceVbo = vbos[1]

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, cornerVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, corners.size * 4, cornerBuf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)
        GLES30.glVertexAttribDivisor(0, 0)

        // Per-instance attributes (stride = FLOATS_PER_INSTANCE floats).
        val stride = FLOATS_PER_INSTANCE * 4
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        bindInstanceAttrib(1, 2, 0, stride) // a_gridPos
        bindInstanceAttrib(2, 4, 2, stride) // a_uv (u0,v0,u1,v1)
        bindInstanceAttrib(3, 3, 6, stride) // a_fg
        bindInstanceAttrib(4, 3, 9, stride) // a_bg
        bindInstanceAttrib(5, 1, 12, stride) // a_cellWidth (1.0 or 2.0)

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
        val cols = frame.cols
        val rows = frame.rows
        val cellCount = cols * rows

        val bgR = ((TerminalColors.DEFAULT_BG shr 16) and 0xFF) / 255f
        val bgG = ((TerminalColors.DEFAULT_BG shr 8) and 0xFF) / 255f
        val bgB = (TerminalColors.DEFAULT_BG and 0xFF) / 255f
        GLES30.glClearColor(bgR, bgG, bgB, backgroundOpacity)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        if (cellCount == 0) {
            egl.swapBuffers()
            return
        }

        ensureInstanceCapacity(cellCount)
        val data = instanceData
        var p = 0
        var instanceCount = 0
        for (i in 0 until cellCount) {
            val cpVal = frame.codePoints[i]
            if (cpVal == TerminalGrid.WIDE_DUMMY) continue // Skip right-half dummy cells.

            val col = i % cols
            val row = i / cols
            val flags = frame.styleFlags[i]
            val bold = flags and TerminalGrid.BOLD != 0
            val italic = flags and TerminalGrid.ITALIC != 0
            val isWide = flags and TerminalGrid.WIDE != 0
            val sprite = atlas.spriteFor(cpVal, bold, italic, wide = isWide)
            val fg = frame.fg[i]
            val bg = frame.bg[i]

            data[p++] = col.toFloat()
            data[p++] = row.toFloat()
            data[p++] = sprite.u0
            data[p++] = sprite.v0
            data[p++] = sprite.u1
            data[p++] = sprite.v1
            data[p++] = ((fg shr 16) and 0xFF) / 255f
            data[p++] = ((fg shr 8) and 0xFF) / 255f
            data[p++] = (fg and 0xFF) / 255f
            data[p++] = ((bg shr 16) and 0xFF) / 255f
            data[p++] = ((bg shr 8) and 0xFF) / 255f
            data[p++] = (bg and 0xFF) / 255f
            data[p++] = if (isWide) 2f else 1f // a_cellWidth
            instanceCount++
        }

        instanceBuffer.clear()
        instanceBuffer.put(data, 0, p)
        instanceBuffer.flip()

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, p * 4, instanceBuffer, GLES30.GL_DYNAMIC_DRAW)

        program.use()
        GLES30.glUniform2f(uCellPx, metrics.width.toFloat(), metrics.height.toFloat())
        GLES30.glUniform2f(uViewportPx, viewportW.toFloat(), viewportH.toFloat())
        GLES30.glUniform1i(uAtlas, 0)
        GLES30.glUniform1f(uBgOpacity, backgroundOpacity)
        atlas.bind(0)

        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, instanceCount)

        // Draw beam/underline cursor as an additional instance.
        drawNonBlockCursor(frame, program)

        GLES30.glBindVertexArray(0)

        egl.swapBuffers()
    }

    /**
     * Draws beam (|) or underline (_) cursor as a colored rectangle overlay.
     * Block cursors are baked into the snapshot; this handles shapes 3-6.
     */
    private fun drawNonBlockCursor(frame: FrameSnapshot, program: ShaderProgram) {
        if (frame.cursorRow < 0 || frame.cursorCol < 0) return
        val shape = frame.cursorShape
        val isBlock = shape <= TerminalGrid.CURSOR_BLOCK_STEADY
        if (isBlock) return // Already baked in snapshot.

        // Blink: odd shapes blink, even shapes are steady.
        val blinks = shape % 2 == 1
        if (blinks) {
            val phase = (System.currentTimeMillis() / CURSOR_BLINK_MS) % 2
            if (phase == 1L) return // Hidden phase.
        }

        val cellW = metrics.width.toFloat()
        val cellH = metrics.height.toFloat()
        val col = frame.cursorCol.toFloat()
        val row = frame.cursorRow.toFloat()

        // Cursor color.
        val cr = ((TerminalColors.CURSOR shr 16) and 0xFF) / 255f
        val cg = ((TerminalColors.CURSOR shr 8) and 0xFF) / 255f
        val cb = (TerminalColors.CURSOR and 0xFF) / 255f

        // Use a blank glyph sprite (space) to draw a solid rect.
        val blankSprite = atlas.spriteFor(' '.code, bold = false, italic = false)

        // Compute fractional position for thin cursors.
        val isBeam = shape >= CURSOR_BEAM_BLINK
        val cursorData: FloatArray
        if (isBeam) {
            // Beam: 2px wide bar at left edge of cell.
            val beamWidth = 2f / cellW // Fraction of cell width.
            cursorData = floatArrayOf(
                col, row,
                blankSprite.u0, blankSprite.v0, blankSprite.u1, blankSprite.v1,
                cr, cg, cb,
                cr, cg, cb,
                1f, // a_cellWidth
            )
        } else {
            // Underline: 2px tall bar at bottom of cell.
            cursorData = floatArrayOf(
                col, row,
                blankSprite.u0, blankSprite.v0, blankSprite.u1, blankSprite.v1,
                cr, cg, cb,
                cr, cg, cb,
                1f, // a_cellWidth
            )
        }

        // For simplicity, draw beam/underline using scissor test to clip the
        // full-cell instance to just the cursor region.
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        if (isBeam) {
            val px = (col * cellW).toInt()
            val py = viewportH - ((row + 1) * cellH).toInt()
            GLES30.glScissor(px, py, 2, cellH.toInt())
        } else {
            val px = (col * cellW).toInt()
            val py = viewportH - ((row + 1) * cellH).toInt()
            GLES30.glScissor(px, py, cellW.toInt(), 2)
        }

        ensureInstanceCapacity(1)
        instanceBuffer.clear()
        instanceBuffer.put(cursorData)
        instanceBuffer.flip()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, cursorData.size * 4, instanceBuffer, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, 1)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    }

    private fun ensureInstanceCapacity(cellCount: Int) {
        val needed = cellCount * FLOATS_PER_INSTANCE
        if (instanceData.size < needed) {
            instanceData = FloatArray(needed)
            instanceBuffer = ByteBuffer.allocateDirect(needed * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
    }

    override fun onSurfaceDestroyed() {
        program?.release()
        atlas.release()
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        if (cornerVbo != 0 || instanceVbo != 0) {
            GLES30.glDeleteBuffers(2, intArrayOf(cornerVbo, instanceVbo), 0)
        }
        program = null
        vao = 0; cornerVbo = 0; instanceVbo = 0
        egl.release()
    }

    private companion object {
        const val FLOATS_PER_INSTANCE = 13
        const val CURSOR_BLINK_MS = 530L
        const val CURSOR_BEAM_BLINK = 5

        val VERTEX_SRC = """
            #version 300 es
            layout(location = 0) in vec2 a_corner;
            layout(location = 1) in vec2 a_gridPos;
            layout(location = 2) in vec4 a_uv;
            layout(location = 3) in vec3 a_fg;
            layout(location = 4) in vec3 a_bg;
            layout(location = 5) in float a_cellWidth;
            uniform vec2 u_cellPx;
            uniform vec2 u_viewportPx;
            out vec2 v_uv;
            out vec3 v_fg;
            out vec3 v_bg;
            void main() {
                vec2 scaledCorner = vec2(a_corner.x * a_cellWidth, a_corner.y);
                vec2 px = (a_gridPos + scaledCorner) * u_cellPx;
                vec2 ndc = vec2(
                    px.x / u_viewportPx.x * 2.0 - 1.0,
                    1.0 - px.y / u_viewportPx.y * 2.0
                );
                gl_Position = vec4(ndc, 0.0, 1.0);
                v_uv = mix(a_uv.xy, a_uv.zw, a_corner);
                v_fg = a_fg;
                v_bg = a_bg;
            }
        """.trimIndent()

        val FRAGMENT_SRC = """
            #version 300 es
            precision mediump float;
            in vec2 v_uv;
            in vec3 v_fg;
            in vec3 v_bg;
            uniform sampler2D u_atlas;
            uniform float u_bgOpacity;
            out vec4 fragColor;
            void main() {
                float coverage = texture(u_atlas, v_uv).r;
                vec3 color = mix(v_bg, v_fg, coverage);
                float alpha = mix(u_bgOpacity, 1.0, coverage);
                fragColor = vec4(color, alpha);
            }
        """.trimIndent()
    }
}
