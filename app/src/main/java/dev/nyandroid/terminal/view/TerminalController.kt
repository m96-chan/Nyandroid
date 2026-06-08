package dev.nyandroid.terminal.view

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Surface
import dev.nyandroid.terminal.backend.ShellIntegration
import dev.nyandroid.terminal.backend.SshBackend
import dev.nyandroid.terminal.backend.TerminalBackend
import dev.nyandroid.terminal.emulator.SelectionRange
import dev.nyandroid.terminal.emulator.TerminalEmulator
import dev.nyandroid.terminal.emulator.TerminalGrid
import dev.nyandroid.terminal.font.CellMetrics
import dev.nyandroid.terminal.font.FontSpec
import dev.nyandroid.terminal.font.GlyphRasterizer
import dev.nyandroid.terminal.font.NerdFontFamily
import dev.nyandroid.terminal.notification.TerminalNotification
import dev.nyandroid.terminal.render.RenderThread
import dev.nyandroid.terminal.render.gles.GlesGpuBackend

/**
 * Wires together the three swappable seams — backend (PTY), emulator (VT) and
 * GPU renderer — and owns geometry. The view delegates surface and input
 * events here; everything below stays decoupled from Android view plumbing.
 */
class TerminalController(
    private val appContext: Context,
    private var fontSpec: FontSpec,
    scrollbackLines: Int = TerminalGrid.DEFAULT_SCROLLBACK,
) {

    var metrics: CellMetrics = CellMetrics.measure(fontSpec)
        private set

    /** Current font size in pixels. */
    val fontSizePx: Float get() = fontSpec.textSizePx

    private val fontFamily = NerdFontFamily.load(appContext)
    private val gpu = GlesGpuBackend(GlyphRasterizer(fontSpec, metrics, fontFamily))
    private val backend: TerminalBackend = SshBackend(appContext)
    private val emulator = TerminalEmulator(DEFAULT_COLS, DEFAULT_ROWS, scrollbackLines) { bytes ->
        backend.write(bytes)
    }
    private val renderThread = RenderThread(gpu) { frame ->
        emulator.snapshot(frame)
        UrlHighlighter.apply(frame) // underline URLs in the visible text (#16)
    }

    private var started = false
    private var surfaceW = 0
    private var surfaceH = 0
    var cols = DEFAULT_COLS
        private set
    var rows = DEFAULT_ROWS
        private set

    /** Audio bell on/off (kitty `enable_audio_bell`). */
    var audioBellEnabled = false

    /** Invoked on BEL when a visual bell is desired (flash). On the feed thread. */
    var onVisualBell: (() -> Unit)? = null

    /** GPU background opacity (kitty `background_opacity`). */
    var backgroundOpacity: Float
        get() = gpu.backgroundOpacity
        set(value) { gpu.backgroundOpacity = value }

    /** Whether programming ligatures are rendered. */
    var ligaturesEnabled: Boolean
        get() = gpu.ligaturesEnabled
        set(value) { gpu.ligaturesEnabled = value }

    /** Connection state: "connecting", "connected", "reconnecting", "disconnected". */
    var connectionState = "connecting"
        private set

    /** Callback when connection state changes. */
    var onConnectionStateChanged: ((String) -> Unit)? = null

    init {
        emulator.onChange = { renderThread.requestRender() }
        emulator.onBell = { performBell() }
        emulator.onNotification = { code, payload -> showNotification(code, payload) }
        backend.onOutput = { buffer, length -> emulator.feed(buffer, length) }
        backend.onExit = { status -> Log.i(TAG, "Shell exited with status $status") }
        backend.onStateChanged = { state ->
            connectionState = state
            if (state == "connected" && shellIntegrationEnabled && !shellIntegrationSent) {
                shellIntegrationSent = true
                backend.write(ShellIntegration.injectionBytes())
            }
            onConnectionStateChanged?.invoke(state)
        }
        renderThread.start()
    }

    /** kitty `shell_integration`: inject prompt/CWD reporting on connect (#13). */
    var shellIntegrationEnabled = false
    private var shellIntegrationSent = false

    private fun performBell() {
        // Haptic.
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) { /* no vibrator */ }
        // Audio bell.
        if (audioBellEnabled) {
            try {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            } catch (_: Exception) { /* no audio */ }
        }
        // Visual bell (screen flash) — handled by the view.
        onVisualBell?.invoke()
    }

    private fun showNotification(code: Int, payload: String) {
        val parsed = when (code) {
            99 -> TerminalNotification.parseOsc99(payload)
            777 -> TerminalNotification.parseOsc777(payload)
            else -> null
        } ?: return
        try {
            TerminalNotification.show(appContext, parsed.first, parsed.second)
        } catch (e: Exception) {
            Log.w(TAG, "notification failed", e)
        }
    }

    fun onSurfaceAvailable(surface: Surface, width: Int, height: Int) {
        surfaceW = width; surfaceH = height
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
        surfaceW = width; surfaceH = height
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
    fun getLineText(row: Int): String? = emulator.getLineText(row)
    fun currentViewportOffset(): Int = emulator.currentViewportOffset()

    fun isApplicationCursorKeys(): Boolean = emulator.isApplicationCursorKeys()
    fun isApplicationKeypad(): Boolean = emulator.isApplicationKeypad()
    fun mouseTrackingMode(): Int = emulator.mouseTrackingMode()
    fun mouseSgrFormat(): Boolean = emulator.mouseSgrFormat()
    fun kittyKeyboardFlags(): Int = emulator.kittyKeyboardFlags()

    // --- URLs / hyperlinks (#16) --------------------------------------------

    /** OSC 8 hyperlink at the given viewport cell, if any. */
    fun hyperlinkAt(row: Int, col: Int): String? = emulator.hyperlinkAt(row, col)

    /** Regex-detected URL at the given viewport cell, if any. */
    fun urlAt(row: Int, col: Int): String? {
        val line = emulator.getLineText(row) ?: return null
        return UrlDetector.urlAt(listOf(line), 0, col)
    }

    // --- Live font resize (#6) ----------------------------------------------

    /** Recreates the glyph pipeline at a new font size and re-lays out the grid. */
    fun setFontSize(px: Float) {
        val newSpec = FontSpec.create(appContext, px)
        val newMetrics = CellMetrics.measure(newSpec)
        if (newMetrics.width == metrics.width && newMetrics.height == metrics.height) return
        fontSpec = newSpec
        metrics = newMetrics
        gpu.scheduleFontChange(GlyphRasterizer(newSpec, newMetrics, fontFamily))
        if (applyGeometry(surfaceW, surfaceH) && started) {
            emulator.resize(cols, rows)
            backend.resize(cols, rows, surfaceW, surfaceH)
        }
        renderThread.requestRender()
    }

    // --- Shell integration / search -----------------------------------------

    /** Scrolls so the most recent shell prompt is visible (#13). */
    fun scrollToPrompt() {
        val prompt = emulator.promptRow()
        if (prompt >= 0) emulator.scrollViewport(rows - prompt)
    }

    /** Searches scrollback for [query] (#17). */
    fun searchScrollback(query: String, forward: Boolean = false): Boolean =
        emulator.searchScrollback(query, forward)

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
