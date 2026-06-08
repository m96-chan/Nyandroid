package dev.nyandroid.terminal.view

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small search bar overlay for scrollback search (kitty `show_scrollback`,
 * #17). Pure `android.widget`; the activity wires [onQuery]/[onClose].
 */
class SearchOverlay(context: Context) : LinearLayout(context) {

    /** (query, forward) -> found. */
    var onQuery: ((String, Boolean) -> Boolean)? = null
    var onClose: (() -> Unit)? = null

    private val input: EditText

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(0xEE1A1B26.toInt())
        val dp = resources.displayMetrics.density
        val pad = (8 * dp).toInt()
        setPadding(pad, pad, pad, pad)

        input = EditText(context).apply {
            hint = "Search scrollback"
            setHintTextColor(0xFF888888.toInt())
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        addView(input)
        addView(button("↑") { runQuery(forward = false) })
        addView(button("↓") { runQuery(forward = true) })
        addView(button("✕") { hide() })
    }

    private fun button(label: String, onClick: () -> Unit): TextView {
        val dp = resources.displayMetrics.density
        return TextView(context).apply {
            text = label
            setTextColor(0xFF33CC66.toInt())
            textSize = 18f
            val p = (12 * dp).toInt()
            setPadding(p, 0, p, 0)
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun runQuery(forward: Boolean) {
        val q = input.text?.toString().orEmpty()
        if (q.isNotEmpty()) onQuery?.invoke(q, forward)
    }

    fun show() {
        visibility = View.VISIBLE
        input.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hide() {
        visibility = View.GONE
        onClose?.invoke()
    }
}
