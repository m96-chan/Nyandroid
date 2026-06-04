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
        }
        atlas.init()
        setupBuffers()

        GLES30.glDisable(GLES30.GL_BLEND)
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
        GLES30.glClearColor(bgR, bgG, bgB, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        if (cellCount == 0) {
            egl.swapBuffers()
            return
        }

        ensureInstanceCapacity(cellCount)
        val data = instanceData
        var p = 0
        for (i in 0 until cellCount) {
            val col = i % cols
            val row = i / cols
            val flags = frame.styleFlags[i]
            val bold = flags and TerminalGrid.BOLD != 0
            val italic = flags and TerminalGrid.ITALIC != 0
            val sprite = atlas.spriteFor(frame.codePoints[i], bold, italic)
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
        atlas.bind(0)

        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, cellCount)
        GLES30.glBindVertexArray(0)

        egl.swapBuffers()
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
        const val FLOATS_PER_INSTANCE = 12

        val VERTEX_SRC = """
            #version 300 es
            layout(location = 0) in vec2 a_corner;
            layout(location = 1) in vec2 a_gridPos;
            layout(location = 2) in vec4 a_uv;
            layout(location = 3) in vec3 a_fg;
            layout(location = 4) in vec3 a_bg;
            uniform vec2 u_cellPx;
            uniform vec2 u_viewportPx;
            out vec2 v_uv;
            out vec3 v_fg;
            out vec3 v_bg;
            void main() {
                vec2 px = (a_gridPos + a_corner) * u_cellPx;
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
            out vec4 fragColor;
            void main() {
                float coverage = texture(u_atlas, v_uv).r;
                fragColor = vec4(mix(v_bg, v_fg, coverage), 1.0);
            }
        """.trimIndent()
    }
}
