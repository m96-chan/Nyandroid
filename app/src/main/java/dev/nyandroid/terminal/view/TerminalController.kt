package dev.nyandroid.terminal.view

import android.content.Context
import android.util.Log
import android.view.Surface
import dev.nyandroid.terminal.backend.SshBackend
import dev.nyandroid.terminal.backend.TerminalBackend
import dev.nyandroid.terminal.emulator.SelectionRange
import dev.nyandroid.terminal.emulator.TerminalEmulator
import dev.nyandroid.terminal.font.CellMetrics
import dev.nyandroid.terminal.font.FontSpec
import dev.nyandroid.terminal.font.GlyphRasterizer
import dev.nyandroid.terminal.font.NerdFontFamily
import dev.nyandroid.terminal.render.RenderThread
import dev.nyandroid.terminal.render.gles.GlesGpuBackend

/**
 * Wires together the three swappable seams — backend (PTY), emulator (VT) and
 * GPU renderer — and owns geometry. The view delegates surface and input
 * events here; everything below stays decoupled from Android view plumbing.
 */
class TerminalController(context: Context, fontSpec: FontSpec) {

    val metrics: CellMetrics = CellMetrics.measure(fontSpec)

    private val fontFamily = NerdFontFamily.load(context)
    private val rasterizer = GlyphRasterizer(fontSpec, metrics, fontFamily)
    private val gpu = GlesGpuBackend(rasterizer)
    private val backend: TerminalBackend = SshBackend(context)
    private val emulator = TerminalEmulator(DEFAULT_COLS, DEFAULT_ROWS) { bytes ->
        backend.write(bytes)
    }
    private val renderThread = RenderThread(gpu) { frame -> emulator.snapshot(frame) }

    private var started = false
    var cols = DEFAULT_COLS
        private set
    var rows = DEFAULT_ROWS
        private set

    init {
        emulator.onChange = { renderThread.requestRender() }
        backend.onOutput = { buffer, length -> emulator.feed(buffer, length) }
        backend.onExit = { status -> Log.i(TAG, "Shell exited with status $status") }
        renderThread.start()
    }

    fun onSurfaceAvailable(surface: Surface, width: Int, height: Int) {
        applyGeometry(width, height)
        if (!started) {
            emulator.resize(cols, rows)
            try {
                backend.start(cols, rows)
                Log.i(TAG, "Backend started: ${cols}x${rows}")
            } catch (e: Exception) {
                Log.e(TAG, "Backend failed to start", e)
            }
            started = true
        }
        renderThread.onSurfaceAvailable(surface, width, height)
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        val changed = applyGeometry(width, height)
        if (changed && started) {
            emulator.resize(cols, rows)
            backend.resize(cols, rows, width, height)
        }
        renderThread.onSurfaceChanged(width, height)
    }

    fun onSurfaceDestroyed() {
        renderThread.onSurfaceDestroyed()
    }

    fun write(bytes: ByteArray) {
        backend.write(bytes)
    }

    fun scrollViewport(lines: Int) {
        emulator.scrollViewport(lines)
    }

    fun setSelection(range: SelectionRange) = emulator.setSelection(range)
    fun clearSelection() = emulator.clearSelection()
    fun getSelectedText(): String? = emulator.getSelectedText()
    fun currentViewportOffset(): Int = emulator.currentViewportOffset()

    fun isApplicationCursorKeys(): Boolean = emulator.isApplicationCursorKeys()
    fun isApplicationKeypad(): Boolean = emulator.isApplicationKeypad()

    fun paste(text: String) {
        if (text.isEmpty()) return
        val bytes = if (emulator.isBracketedPasteMode()) {
            "\u001b[200~${text}\u001b[201~".toByteArray(Charsets.UTF_8)
        } else {
            text.toByteArray(Charsets.UTF_8)
        }
        backend.write(bytes)
    }

    fun destroy() {
        backend.close()
        renderThread.quit()
    }

    private fun applyGeometry(width: Int, height: Int): Boolean {
        val newCols = (width / metrics.width).coerceAtLeast(1)
        val newRows = (height / metrics.height).coerceAtLeast(1)
        if (newCols == cols && newRows == rows) return false
        cols = newCols
        rows = newRows
        return true
    }

    private companion object {
        const val TAG = "TerminalController"
        const val DEFAULT_COLS = 80
        const val DEFAULT_ROWS = 24
    }
}
