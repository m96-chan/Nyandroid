package dev.nyandroid.terminal.view

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.AttributeSet
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import dev.nyandroid.terminal.emulator.SelectionRange
import dev.nyandroid.terminal.font.FontSpec
import dev.nyandroid.terminal.input.KeyEncoder

/**
 * The on-screen terminal. A [SurfaceView] (not GLSurfaceView) so the GPU API
 * stays behind [TerminalController]; we just hand it the raw [Surface].
 *
 * Gestures:
 * - Tap: show keyboard
 * - Vertical swipe/fling: browse scrollback
 * - Long-press → drag: text selection
 * - Double-tap: word selection
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val controller: TerminalController

    private val gestureDetector: GestureDetector
    private val scroller = OverScroller(context)
    private var scrollAccumulator = 0f
    private var lastScrollY = 0

    // Selection state.
    private var selecting = false
    private var selectionAnchorRow = 0
    private var selectionAnchorCol = 0
    private var actionMode: ActionMode? = null

    init {
        val density = resources.displayMetrics.density
        val fontSpec = FontSpec(textSizePx = DEFAULT_FONT_SP * density)
        controller = TerminalController(context, fontSpec)

        gestureDetector = GestureDetector(context, TerminalGestureListener())
        gestureDetector.setIsLongpressEnabled(true)

        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // --- Surface lifecycle --------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!surfaceReady) {
            surfaceReady = true
            controller.onSurfaceAvailable(holder.surface, width, height)
        } else {
            controller.onSurfaceChanged(width, height)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        controller.onSurfaceDestroyed()
    }

    private var surfaceReady = false

    // --- Input --------------------------------------------------------------

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = EditorInfo.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val bytes = KeyEncoder.encode(event)
        return if (bytes != null) {
            controller.write(bytes)
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }

    // --- Touch (scroll + selection) -----------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                if (selecting) {
                    val (row, col) = touchToCell(event.x, event.y)
                    updateSelection(row, col)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                scrollAccumulator = 0f
                if (selecting) {
                    selecting = false
                    // Keep selection visible; ActionMode handles copy/dismiss.
                }
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val currentY = scroller.currY
            val dy = currentY - lastScrollY
            lastScrollY = currentY
            applyScrollDelta(dy.toFloat())
            postInvalidateOnAnimation()
        }
    }

    private fun applyScrollDelta(dy: Float) {
        val cellHeight = controller.metrics.height.toFloat()
        if (cellHeight <= 0f) return
        scrollAccumulator += dy
        val lines = (scrollAccumulator / cellHeight).toInt()
        if (lines != 0) {
            scrollAccumulator -= lines * cellHeight
            controller.scrollViewport(lines)
        }
    }

    // --- Selection helpers --------------------------------------------------

    private fun touchToCell(x: Float, y: Float): Pair<Int, Int> {
        val col = (x / controller.metrics.width).toInt().coerceIn(0, controller.cols - 1)
        val row = (y / controller.metrics.height).toInt().coerceIn(0, controller.rows - 1)
        return row to col
    }

    private fun startSelection(row: Int, col: Int) {
        selecting = true
        selectionAnchorRow = row
        selectionAnchorCol = col
        updateSelection(row, col)
        showActionMode()
    }

    private fun updateSelection(row: Int, col: Int) {
        val range = SelectionRange.normalized(
            selectionAnchorRow, selectionAnchorCol,
            row, col,
            controller.currentViewportOffset(),
        )
        controller.setSelection(range)
    }

    private fun selectWord(row: Int, col: Int) {
        val text = controller.getSelectedText() // Not useful yet; read from grid.
        // Select word boundaries: expand from (row, col) outward to non-word chars.
        // For simplicity, select the whole line content at this row.
        val range = SelectionRange.normalized(
            row, 0, row, controller.cols - 1,
            controller.currentViewportOffset(),
        )
        controller.setSelection(range)
        showActionMode()
    }

    private fun showActionMode() {
        if (actionMode != null) return
        actionMode = startActionMode(SelectionActionModeCallback())
    }

    private fun dismissSelection() {
        controller.clearSelection()
        actionMode?.finish()
        actionMode = null
        selecting = false
    }

    // --- Clipboard ----------------------------------------------------------

    private fun copySelection() {
        val text = controller.getSelectedText() ?: return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
        dismissSelection()
    }

    private fun pasteFromClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        controller.paste(text)
    }

    // --- Public API ---------------------------------------------------------

    fun sendText(text: CharSequence) {
        controller.write(KeyEncoder.encodeText(text))
    }

    fun sendKey(event: KeyEvent) {
        KeyEncoder.encode(event)?.let { controller.write(it) }
    }

    private fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun shutdown() {
        controller.destroy()
    }

    // --- Gesture listener ---------------------------------------------------

    private inner class TerminalGestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (actionMode != null) {
                dismissSelection()
                return true
            }
            requestFocus()
            showKeyboard()
            performClick()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val (row, col) = touchToCell(e.x, e.y)
            selectWord(row, col)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val (row, col) = touchToCell(e.x, e.y)
            startSelection(row, col)
        }

        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float,
        ): Boolean {
            if (selecting) return false // Don't scroll while selecting.
            applyScrollDelta(distanceY)
            return true
        }

        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float,
        ): Boolean {
            if (selecting) return false
            lastScrollY = 0
            scroller.fling(
                0, 0, 0, (-velocityY).toInt(),
                0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2,
            )
            postInvalidateOnAnimation()
            return true
        }
    }

    override fun performClick(): Boolean = super.performClick()

    // --- ActionMode for copy/paste ------------------------------------------

    private inner class SelectionActionModeCallback : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(0, MENU_COPY, 0, "Copy")
            menu.add(0, MENU_PASTE, 1, "Paste")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                MENU_COPY -> { copySelection(); true }
                MENU_PASTE -> { pasteFromClipboard(); mode.finish(); true }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            controller.clearSelection()
            selecting = false
        }
    }

    /** Bridges IME text/keys into the terminal. */
    private class TerminalInputConnection(
        private val view: TerminalView,
    ) : BaseInputConnection(view, false) {

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            view.sendText(text)
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                view.sendKey(event)
                return true
            }
            return super.sendKeyEvent(event)
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { view.controller.write(byteArrayOf(0x7F)) }
            return true
        }
    }

    private companion object {
        const val DEFAULT_FONT_SP = 14f
        const val MENU_COPY = 1
        const val MENU_PASTE = 2
    }
}
