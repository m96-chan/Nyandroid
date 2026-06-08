package dev.nyandroid.terminal.view

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.ActionMode
import android.view.DragEvent
import android.view.GestureDetector
import android.view.InputDevice
import android.view.ScaleGestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import android.widget.PopupMenu
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import dev.nyandroid.terminal.config.KeyBinding
import dev.nyandroid.terminal.emulator.SelectionRange
import dev.nyandroid.terminal.emulator.TerminalGrid
import dev.nyandroid.terminal.input.KeyEncoder
import dev.nyandroid.terminal.input.MouseEncoder

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

    /** The active controller. Always set before the view receives input. */
    internal lateinit var controller: TerminalController
    private val handler = Handler(Looper.getMainLooper())

    private val gestureDetector: GestureDetector
    private val scaleDetector: ScaleGestureDetector
    private val scroller = OverScroller(context)
    private var scrollAccumulator = 0f
    private var lastFlingY = 0
    private var scaling = false

    // Selection state.
    private var selecting = false
    private var selectionAnchorRow = 0
    private var selectionAnchorCol = 0
    private var actionMode: ActionMode? = null

    // Long-press detection (manual, because GestureDetector's longpress blocks scroll).
    private var longPressDownX = 0f
    private var longPressDownY = 0f
    private var longPressDownTime = 0L
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** Callback for font size changes via pinch zoom. */
    var onFontSizeChanged: ((Float) -> Unit)? = null

    /** Invoked on the UI thread when the terminal rings a visual bell (#21). */
    var onVisualBell: (() -> Unit)? = null

    init {
        gestureDetector = GestureDetector(context, TerminalGestureListener())
        gestureDetector.setIsLongpressEnabled(false) // We handle long-press ourselves.
        scaleDetector = ScaleGestureDetector(context, PinchZoomListener())

        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private var pendingSurface: Triple<android.view.Surface, Int, Int>? = null

    /**
     * Swap to a different [TerminalController] (tab switch). If the surface
     * is already available, the new controller is wired up immediately.
     */
    fun setController(newController: TerminalController) {
        if (::controller.isInitialized) {
            if (controller === newController) return
            // Detach old controller from surface.
            controller.onSurfaceDestroyed()
        }
        controller = newController
        // Visual bell fires on the feed thread; hop to the UI thread.
        newController.onVisualBell = { handler.post { onVisualBell?.invoke() } }
        // Reattach to current surface if available.
        pendingSurface?.let { (surface, w, h) ->
            newController.onSurfaceAvailable(surface, w, h)
        }
    }

    // --- Surface lifecycle --------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        pendingSurface = Triple(holder.surface, width, height)
        if (!::controller.isInitialized) return
        if (!surfaceReady) {
            surfaceReady = true
            controller.onSurfaceAvailable(holder.surface, width, height)
        } else {
            controller.onSurfaceChanged(width, height)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        pendingSurface = null
        if (::controller.isInitialized) controller.onSurfaceDestroyed()
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

    // Virtual modifier state from extra-keys toolbar.
    internal var virtualCtrl = false
    internal var virtualAlt = false
    internal var virtualFn = false

    /** Key bindings from config. */
    internal var keyBindings: List<KeyBinding> = emptyList()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Check key bindings first.
        for (binding in keyBindings) {
            if (binding.matches(event, virtualCtrl, virtualAlt)) {
                if (onKeyBindingAction?.invoke(binding.action, binding.args) == true) {
                    virtualCtrl = false
                    virtualAlt = false
                    virtualFn = false
                    extraKeysBar?.syncToggleState()
                    return true
                }
            }
        }
        val bytes = KeyEncoder.encode(
            event,
            applicationCursorKeys = controller.isApplicationCursorKeys(),
            virtualCtrl = virtualCtrl,
            virtualAlt = virtualAlt,
            virtualFn = virtualFn,
            kittyFlags = controller.kittyKeyboardFlags(),
        )
        // Clear sticky modifiers after use.
        if (bytes != null) {
            virtualCtrl = false
            virtualAlt = false
            virtualFn = false
            extraKeysBar?.syncToggleState()
        }
        return if (bytes != null) {
            controller.write(bytes)
            true
        } else {
            super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Key-release events are only emitted under the kitty keyboard protocol
        // Level 2 (#39); legacy mode ignores key-up.
        KeyEncoder.encodeKittyEvent(
            event, controller.kittyKeyboardFlags(), virtualCtrl, virtualAlt,
        )?.let { controller.write(it); return true }
        return super.onKeyUp(keyCode, event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // Focus reporting (DECSET 1004, #35).
        if (::controller.isInitialized) controller.reportFocus(hasWindowFocus)
    }

    internal var extraKeysBar: ExtraKeysBar? = null

    /**
     * Callback for key binding actions. Returns true if the action was handled.
     * Set by the activity to route actions like copy, paste, new_tab, etc.
     */
    var onKeyBindingAction: ((action: String, args: String) -> Boolean)? = null

    // --- Touch (scroll + selection + mouse reporting) -----------------------

    /** Whether a mouse button is currently "pressed" for drag tracking. */
    private var mouseButtonDown = false

    private fun isMouseMode(): Boolean =
        controller.mouseTrackingMode() != TerminalGrid.MOUSE_NONE

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Right/middle mouse buttons are handled in onGenericMotionEvent (#42).
        if (event.isFromSource(InputDevice.SOURCE_MOUSE) &&
            event.buttonState and (MotionEvent.BUTTON_SECONDARY or MotionEvent.BUTTON_TERTIARY) != 0
        ) {
            return true
        }
        // Pinch zoom always gets a chance.
        scaleDetector.onTouchEvent(event)
        if (scaling) return true

        // If mouse tracking is active, route touch events as mouse reports.
        if (isMouseMode()) {
            return handleMouseEvent(event)
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                longPressDownX = event.x
                longPressDownY = event.y
                longPressDownTime = event.eventTime
                handler.removeCallbacks(longPressRunnable)
                if (actionMode == null) {
                    handler.postDelayed(longPressRunnable, longPressTimeout)
                }
                // Stop any ongoing fling.
                scroller.forceFinished(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (selecting) {
                    val (row, col) = touchToCell(event.x, event.y)
                    updateSelection(row, col)
                    return true
                }
                // Cancel long-press if finger moved too far.
                val dx = event.x - longPressDownX
                val dy = event.y - longPressDownY
                if (dx * dx + dy * dy > touchSlop * touchSlop) {
                    handler.removeCallbacks(longPressRunnable)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                scrollAccumulator = 0f
                if (selecting) {
                    selecting = false
                }
            }
        }

        if (!selecting) {
            gestureDetector.onTouchEvent(event)
        }
        return true
    }

    private fun handleMouseEvent(event: MotionEvent): Boolean {
        val (row, col) = touchToCell(event.x, event.y)
        val row1 = row + 1 // 1-based for mouse protocol
        val col1 = col + 1
        val sgr = controller.mouseSgrFormat()
        val mode = controller.mouseTrackingMode()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                mouseButtonDown = true
                // Also show keyboard on tap in mouse mode.
                requestFocus()
                showKeyboard()
                controller.write(
                    MouseEncoder.encode(MouseEncoder.BUTTON_LEFT, col1, row1, release = false, sgr),
                )
            }
            MotionEvent.ACTION_MOVE -> {
                if (mouseButtonDown && mode >= TerminalGrid.MOUSE_BUTTON) {
                    controller.write(
                        MouseEncoder.encode(
                            MouseEncoder.BUTTON_LEFT or MouseEncoder.MODIFIER_DRAG,
                            col1, row1, release = false, sgr,
                        ),
                    )
                } else if (!mouseButtonDown && mode >= TerminalGrid.MOUSE_ANY) {
                    controller.write(
                        MouseEncoder.encode(
                            MouseEncoder.BUTTON_RELEASE or MouseEncoder.MODIFIER_DRAG,
                            col1, row1, release = false, sgr,
                        ),
                    )
                }
            }
            MotionEvent.ACTION_UP -> {
                mouseButtonDown = false
                controller.write(
                    MouseEncoder.encode(MouseEncoder.BUTTON_LEFT, col1, row1, release = true, sgr),
                )
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                mouseButtonDown = false
            }
        }
        return true
    }

    private val longPressRunnable = Runnable {
        val (row, col) = touchToCell(longPressDownX, longPressDownY)
        startSelection(row, col)
    }

    // --- Fling animation (driven by Handler, not View.computeScroll) --------

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                val currentY = scroller.currY
                val dy = currentY - lastFlingY
                lastFlingY = currentY
                if (dy != 0) applyScrollDelta(dy.toFloat())
                handler.postDelayed(this, 16) // ~60fps
            }
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
        // Get the line text and find word boundaries around col.
        val lineText = controller.getLineText(row)
        if (lineText != null && col < lineText.length) {
            var start = col
            var end = col
            // Expand left.
            while (start > 0 && isWordChar(lineText[start - 1])) start--
            // Expand right.
            while (end < lineText.length - 1 && isWordChar(lineText[end + 1])) end++
            // Only select if we found a word.
            if (isWordChar(lineText[col])) {
                val range = SelectionRange.normalized(
                    row, start, row, end,
                    controller.currentViewportOffset(),
                )
                controller.setSelection(range)
                showActionMode()
                return
            }
        }
        // Fallback: select the whole line.
        val range = SelectionRange.normalized(
            row, 0, row, controller.cols - 1,
            controller.currentViewportOffset(),
        )
        controller.setSelection(range)
        showActionMode()
    }

    private fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '/' || c == '~'

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

    // --- Public copy/paste API -----------------------------------------------

    fun performCopy() = copySelection()
    fun performPaste() = pasteFromClipboard()

    // --- Public API ---------------------------------------------------------

    fun sendText(text: CharSequence) {
        var bytes = KeyEncoder.encodeText(text)
        if (virtualAlt) bytes = byteArrayOf(0x1B) + bytes
        controller.write(bytes)
        virtualCtrl = false
        virtualAlt = false
        virtualFn = false
        extraKeysBar?.syncToggleState()
    }

    fun sendKey(event: KeyEvent) {
        KeyEncoder.encode(
            event,
            applicationCursorKeys = controller.isApplicationCursorKeys(),
            virtualCtrl = virtualCtrl,
            virtualAlt = virtualAlt,
            virtualFn = virtualFn,
            kittyFlags = controller.kittyKeyboardFlags(),
        )?.let {
            controller.write(it)
            virtualCtrl = false
            virtualAlt = false
            virtualFn = false
            extraKeysBar?.syncToggleState()
        }
    }

    private fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    // --- Desktop: precise mouse / trackpad (#42) ----------------------------

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (::controller.isInitialized && event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            when (event.actionMasked) {
                MotionEvent.ACTION_BUTTON_PRESS -> when (event.actionButton) {
                    MotionEvent.BUTTON_SECONDARY -> { showMouseMenu(event.x, event.y); return true }
                    MotionEvent.BUTTON_TERTIARY -> { pasteFromClipboard(); return true }
                }
                MotionEvent.ACTION_SCROLL -> {
                    val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    if (v != 0f) {
                        if (isMouseMode()) {
                            val (row, col) = touchToCell(event.x, event.y)
                            val btn = if (v > 0) MouseEncoder.BUTTON_SCROLL_UP else MouseEncoder.BUTTON_SCROLL_DOWN
                            controller.write(
                                MouseEncoder.encode(btn, col + 1, row + 1, release = false, controller.mouseSgrFormat()),
                            )
                        } else {
                            controller.scrollViewport(if (v > 0) WHEEL_LINES else -WHEEL_LINES)
                        }
                        return true
                    }
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /** I-beam over text, hand over a URL/hyperlink (desktop pointer feedback). */
    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon {
        val type = if (::controller.isInitialized) {
            val (row, col) = touchToCell(event.x, event.y)
            val onUrl = controller.hyperlinkAt(row, col) != null || controller.urlAt(row, col) != null
            if (onUrl) PointerIcon.TYPE_HAND else PointerIcon.TYPE_TEXT
        } else {
            PointerIcon.TYPE_TEXT
        }
        return PointerIcon.getSystemIcon(context, type)
    }

    private fun showMouseMenu(x: Float, y: Float) {
        val popup = PopupMenu(context, this)
        popup.menu.add(0, MENU_COPY, 0, "Copy")
        popup.menu.add(0, MENU_PASTE, 1, "Paste")
        popup.menu.add(0, MENU_FIND, 2, "Find")
        popup.menu.add(0, MENU_NEW_TAB, 3, "New Tab")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_COPY -> onKeyBindingAction?.invoke("copy_to_clipboard", "")
                MENU_PASTE -> { pasteFromClipboard(); true }
                MENU_FIND -> onKeyBindingAction?.invoke("show_scrollback", "")
                MENU_NEW_TAB -> onKeyBindingAction?.invoke("new_tab", "")
                else -> false
            } ?: false
        }
        popup.show()
    }

    // --- Desktop: drag & drop (#43) -----------------------------------------

    override fun onDragEvent(event: DragEvent): Boolean {
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> return true
            DragEvent.ACTION_DROP -> {
                if (!::controller.isInitialized) return false
                val clip = event.clipData ?: return false
                val sb = StringBuilder()
                for (i in 0 until clip.itemCount) {
                    val item = clip.getItemAt(i)
                    val uri = item.uri
                    if (uri != null) {
                        if (sb.isNotEmpty()) sb.append(' ')
                        sb.append(shellQuote(uri.path ?: uri.toString()))
                    } else {
                        item.coerceToText(context)?.toString()?.let { sb.append(it) }
                    }
                }
                if (sb.isNotEmpty()) controller.paste(sb.toString())
                return true
            }
        }
        return true
    }

    private fun shellQuote(s: String): String =
        if (s.any { it == ' ' || it == '\'' || it == '"' || it == '(' || it == ')' || it == '&' }) {
            "'" + s.replace("'", "'\\''") + "'"
        } else {
            s
        }

    fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        // Controller lifecycle is managed by TabManager, not here.
    }

    /** Makes the surface translucent so background opacity (#22) shows through. */
    fun setBackgroundTranslucent(translucent: Boolean) {
        if (translucent) {
            setZOrderOnTop(true)
            holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        }
    }

    // --- Gesture listener ---------------------------------------------------

    private inner class TerminalGestureListener : GestureDetector.SimpleOnGestureListener() {

        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (actionMode != null) {
                dismissSelection()
                return true
            }
            // Tap on a URL (OSC 8 hyperlink or detected) opens it (#16).
            val (row, col) = touchToCell(e.x, e.y)
            val url = controller.hyperlinkAt(row, col) ?: controller.urlAt(row, col)
            if (url != null) {
                UrlDetector.openUrl(context, url)
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

        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float,
        ): Boolean {
            if (isMouseMode()) {
                // In mouse mode, scroll → wheel events.
                val cellHeight = controller.metrics.height.toFloat()
                if (cellHeight <= 0f) return true
                scrollAccumulator += distanceY
                val lines = (scrollAccumulator / cellHeight).toInt()
                if (lines != 0) {
                    scrollAccumulator -= lines * cellHeight
                    val (row, col) = touchToCell(e2.x, e2.y)
                    val sgr = controller.mouseSgrFormat()
                    val button = if (lines > 0) MouseEncoder.BUTTON_SCROLL_UP
                        else MouseEncoder.BUTTON_SCROLL_DOWN
                    repeat(kotlin.math.abs(lines)) {
                        controller.write(
                            MouseEncoder.encode(button, col + 1, row + 1, release = false, sgr),
                        )
                    }
                }
                return true
            }
            applyScrollDelta(distanceY)
            return true
        }

        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float,
        ): Boolean {
            if (isMouseMode()) return true // No fling in mouse mode.
            lastFlingY = 0
            scroller.fling(
                0, 0, 0, (-velocityY).toInt(),
                0, 0, Int.MIN_VALUE / 2, Int.MAX_VALUE / 2,
            )
            handler.removeCallbacks(flingRunnable)
            handler.post(flingRunnable)
            return true
        }
    }

    override fun performClick(): Boolean = super.performClick()

    // --- Pinch zoom ----------------------------------------------------------

    private inner class PinchZoomListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var startFontSize = 0f

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            startFontSize = controller.fontSizePx
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newSize = (startFontSize * detector.scaleFactor).coerceIn(MIN_FONT_PX, MAX_FONT_PX)
            // Apply live so the grid re-lays out immediately (#6).
            controller.setFontSize(newSize)
            onFontSizeChanged?.invoke(newSize)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaling = false
        }
    }

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
        const val MIN_FONT_PX = 16f
        const val MAX_FONT_PX = 80f
        const val MENU_FIND = 3
        const val MENU_NEW_TAB = 4
        const val WHEEL_LINES = 3
    }
}
