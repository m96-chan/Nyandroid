package dev.nyandroid.terminal.view

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A horizontal tab bar that shows terminal session tabs with a "+" button
 * for creating new tabs. Long-press on a tab closes it.
 */
class TabBar(
    context: Context,
    private val tabManager: TabManager,
) : HorizontalScrollView(context) {

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    var onTabSwitch: ((Int) -> Unit)? = null
    var onNewTab: (() -> Unit)? = null

    init {
        isHorizontalScrollBarEnabled = false
        setBackgroundColor(BAR_BG)
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        rebuild()
    }

    fun rebuild() {
        row.removeAllViews()
        val tabs = tabManager.getTabs()
        val dp = resources.displayMetrics.density

        for ((index, tab) in tabs.withIndex()) {
            val isActive = index == tabManager.activeIndex
            val stateIcon = when (tab.controller.connectionState) {
                "connected" -> "\u25CF " // ● green-ish (rendered by text color)
                "connecting" -> "\u25CB " // ○
                "reconnecting" -> "\u21BB " // ↻
                else -> "\u2716 " // ✖
            }
            val tv = TextView(context).apply {
                text = "$stateIcon${tab.title}"
                setTextColor(if (isActive) ACTIVE_FG else INACTIVE_FG)
                setBackgroundColor(if (isActive) ACTIVE_BG else BAR_BG)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                typeface = Typeface.MONOSPACE
                gravity = Gravity.CENTER
                val hPad = (12 * dp).toInt()
                val vPad = (4 * dp).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                isClickable = true
                isFocusable = false
            }
            tv.setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                tabManager.switchTo(index)
                onTabSwitch?.invoke(index)
            }
            tv.setOnLongClickListener {
                if (tabManager.tabCount > 1) {
                    tabManager.closeTab(index)
                }
                true
            }
            row.addView(tv)
        }

        // "+" button for new tab.
        val addBtn = TextView(context).apply {
            text = " + "
            setTextColor(INACTIVE_FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            val hPad = (10 * dp).toInt()
            val vPad = (4 * dp).toInt()
            setPadding(hPad, vPad, hPad, vPad)
            isClickable = true
            isFocusable = false
        }
        addBtn.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onNewTab?.invoke()
        }
        row.addView(addBtn)
    }

    private companion object {
        const val BAR_BG = 0xFF0E0E1E.toInt()
        const val ACTIVE_BG = 0xFF1A1A2E.toInt()
        const val ACTIVE_FG = 0xFFFFFFFF.toInt()
        const val INACTIVE_FG = 0xFF888888.toInt()
    }
}
