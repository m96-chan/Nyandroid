package dev.nyandroid.terminal.view

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import dev.nyandroid.terminal.font.FontSpec
import dev.nyandroid.terminal.input.KeyEncoder

/**
 * The on-screen terminal. A [SurfaceView] (not GLSurfaceView) so the GPU API
 * stays behind [TerminalController]; we just hand it the raw [Surface].
 *
 * Input: hardware keys via [onKeyDown] and soft-keyboard text via a
 * [BaseInputConnection], both funnelled through [KeyEncoder] to the backend.
 * Touch: tap to show keyboard, vertical scroll/fling to browse scrollback.
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

    init {
        val density = resources.displayMetrics.density
        val fontSpec = FontSpec(textSizePx = DEFAULT_FONT_SP * density)
        controller = TerminalController(context, fontSpec)

        gestureDetector = GestureDetector(context, TerminalGestureListener())
        gestureDetector.setIsLongpressEnabled(false)

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

    // --- Touch (scroll + tap) -----------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            scrollAccumulator = 0f
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

    private inner class TerminalGestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            requestFocus()
            showKeyboard()
            performClick()
            return true
        }

        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float,
        ): Boolean {
            // distanceY > 0 means finger moved up → scroll back into history
            applyScrollDelta(distanceY)
            return true
        }

        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float,
        ): Boolean {
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
    }
}
