package dev.nyandroid.terminal.view

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A horizontal bar of extra modifier keys (ESC, Ctrl, Alt, Fn, Tab, arrows)
 * displayed above the soft keyboard.
 *
 * Toggle keys (Ctrl, Alt, Fn) are sticky: tap once to activate for the next
 * keypress, then auto-clear. The bar visually highlights active toggles.
 */
class ExtraKeysBar(
    context: Context,
    private val terminalView: TerminalView,
) : HorizontalScrollView(context) {

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val toggleButtons = mutableMapOf<String, TextView>()

    init {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(BAR_BG)
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))

        addKey("ESC") { sendKeyCode(KeyEvent.KEYCODE_ESCAPE) }
        addToggle("Ctrl") { terminalView.virtualCtrl = it }
        addToggle("Alt") { terminalView.virtualAlt = it }
        addToggle("Fn") { terminalView.virtualFn = it }
        addKey("TAB") { sendKeyCode(KeyEvent.KEYCODE_TAB) }
        addKey("-") { sendChar('-') }
        addKey("|") { sendChar('|') }
        addKey("/") { sendChar('/') }
        addKey("~") { sendChar('~') }
        addKey("\u2190") { sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT) }  // ←
        addKey("\u2191") { sendKeyCode(KeyEvent.KEYCODE_DPAD_UP) }    // ↑
        addKey("\u2193") { sendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN) }  // ↓
        addKey("\u2192") { sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT) } // →
        addKey("\u2699") { onSettingsPressed?.invoke() } // ⚙
    }

    /** Callback when the settings gear is pressed. */
    var onSettingsPressed: (() -> Unit)? = null

    private fun addKey(label: String, action: () -> Unit) {
        val tv = createButton(label)
        tv.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action()
        }
        row.addView(tv)
    }

    private fun addToggle(label: String, onToggle: (Boolean) -> Unit) {
        val tv = createButton(label)
        toggleButtons[label] = tv
        tv.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val active = !isToggleActive(label)
            onToggle(active)
            updateToggleAppearance(tv, active)
        }
        row.addView(tv)
    }

    private fun isToggleActive(label: String): Boolean = when (label) {
        "Ctrl" -> terminalView.virtualCtrl
        "Alt" -> terminalView.virtualAlt
        "Fn" -> terminalView.virtualFn
        else -> false
    }

    private fun createButton(label: String): TextView {
        val dp = resources.displayMetrics.density
        return TextView(context).apply {
            text = label
            setTextColor(KEY_FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            minWidth = (40 * dp).toInt()
            val hPad = (8 * dp).toInt()
            val vPad = (4 * dp).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            isClickable = true
            isFocusable = false
        }
    }

    private fun updateToggleAppearance(tv: TextView, active: Boolean) {
        tv.setTextColor(if (active) TOGGLE_ACTIVE_FG else KEY_FG)
        tv.setBackgroundColor(if (active) TOGGLE_ACTIVE_BG else Color.TRANSPARENT)
    }

    /** Called after virtual modifiers are consumed to reset toggle UI. */
    fun syncToggleState() {
        toggleButtons["Ctrl"]?.let { updateToggleAppearance(it, terminalView.virtualCtrl) }
        toggleButtons["Alt"]?.let { updateToggleAppearance(it, terminalView.virtualAlt) }
        toggleButtons["Fn"]?.let { updateToggleAppearance(it, terminalView.virtualFn) }
    }

    private fun sendKeyCode(code: Int) {
        val event = KeyEvent(KeyEvent.ACTION_DOWN, code)
        terminalView.sendKey(event)
    }

    private fun sendChar(c: Char) {
        terminalView.sendText(c.toString())
    }

    private companion object {
        const val BAR_BG = 0xFF1A1A2E.toInt()
        const val KEY_FG = 0xFFCCCCCC.toInt()
        const val TOGGLE_ACTIVE_FG = 0xFF00FF00.toInt()
        const val TOGGLE_ACTIVE_BG = 0xFF2A2A4E.toInt()
    }
}
