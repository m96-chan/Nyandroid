package dev.nyandroid.terminal.view

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import dev.nyandroid.terminal.font.FontSpec

/**
 * A recursive container that manages terminal pane splits.
 *
 * A SplitContainer is either:
 * - A **leaf** holding a single [TerminalView]
 * - A **branch** holding two child [SplitContainer]s with a divider
 */
class SplitContainer(
    context: Context,
    private val fontSpec: FontSpec,
    private val scrollbackLines: Int,
) : FrameLayout(context) {

    /** The terminal view for leaf nodes. */
    var terminalView: TerminalView? = null
        private set
    var controller: TerminalController? = null
        private set

    private var splitLayout: LinearLayout? = null
    private var first: SplitContainer? = null
    private var second: SplitContainer? = null
    private var horizontal = true // true = side by side, false = top/bottom

    /** The currently focused pane in this subtree. */
    var activePaneView: TerminalView? = null
        private set

    var onPaneFocused: ((TerminalView, TerminalController) -> Unit)? = null

    /** Create this container as a leaf with a new terminal session. */
    fun initAsLeaf() {
        val tv = TerminalView(context)
        val ctrl = TerminalController(context, fontSpec, scrollbackLines)
        terminalView = tv
        controller = ctrl
        tv.setController(ctrl)
        tv.setOnClickListener {
            focusPane(tv, ctrl)
        }
        addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        activePaneView = tv
    }

    /** Wire up the terminal view callbacks (called by the activity). */
    fun setupCallbacks(setup: (TerminalView) -> Unit) {
        terminalView?.let { setup(it) }
        first?.setupCallbacks(setup)
        second?.setupCallbacks(setup)
    }

    /**
     * Split the current leaf into two panes.
     * @param horiz true for horizontal (side-by-side), false for vertical (top-bottom)
     */
    fun split(horiz: Boolean): SplitContainer? {
        // Can only split a leaf.
        val tv = terminalView ?: return null
        val ctrl = controller ?: return null

        horizontal = horiz
        removeAllViews()

        // Move existing content into first child.
        val firstChild = SplitContainer(context, fontSpec, scrollbackLines)
        firstChild.terminalView = tv
        firstChild.controller = ctrl
        firstChild.activePaneView = tv
        firstChild.addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        firstChild.onPaneFocused = { view, c -> propagateFocus(view, c) }
        tv.setOnClickListener { firstChild.focusPane(tv, ctrl) }

        // Create second child as new session.
        val secondChild = SplitContainer(context, fontSpec, scrollbackLines)
        secondChild.initAsLeaf()
        secondChild.onPaneFocused = { view, c -> propagateFocus(view, c) }

        first = firstChild
        second = secondChild
        terminalView = null
        controller = null

        // Create split layout.
        val layout = LinearLayout(context).apply {
            orientation = if (horiz) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        }
        val weight = 1f
        val divider = View(context).apply {
            setBackgroundColor(DIVIDER_COLOR)
        }
        val dividerSize = (2 * resources.displayMetrics.density).toInt()

        layout.addView(firstChild, LinearLayout.LayoutParams(
            if (horiz) 0 else LayoutParams.MATCH_PARENT,
            if (horiz) LayoutParams.MATCH_PARENT else 0,
            weight,
        ))
        layout.addView(divider, LinearLayout.LayoutParams(
            if (horiz) dividerSize else LayoutParams.MATCH_PARENT,
            if (horiz) LayoutParams.MATCH_PARENT else dividerSize,
        ))
        layout.addView(secondChild, LinearLayout.LayoutParams(
            if (horiz) 0 else LayoutParams.MATCH_PARENT,
            if (horiz) LayoutParams.MATCH_PARENT else 0,
            weight,
        ))

        splitLayout = layout
        addView(layout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Focus the new pane.
        secondChild.terminalView?.let { newTv ->
            secondChild.controller?.let { newCtrl ->
                focusPane(newTv, newCtrl)
            }
        }

        return secondChild
    }

    /** Returns true if this is a leaf node. */
    fun isLeaf(): Boolean = terminalView != null

    /** The leaf whose terminal view is [tv], searching this subtree. */
    fun leafContaining(tv: TerminalView): SplitContainer? = when {
        terminalView === tv -> this
        else -> first?.leafContaining(tv) ?: second?.leafContaining(tv)
    }

    /** The first (top-left) leaf in this subtree. */
    fun firstLeaf(): SplitContainer = if (isLeaf()) this else first!!.firstLeaf()

    /** All leaves in this subtree, in order. */
    fun leaves(): List<SplitContainer> = if (isLeaf()) listOf(this) else {
        (first?.leaves() ?: emptyList()) + (second?.leaves() ?: emptyList())
    }

    /**
     * Re-orients this branch (kitty layout switch: tall ⇄ fat). No-op on leaves.
     */
    fun toggleOrientation() {
        val layout = splitLayout ?: return
        val f = first ?: return
        val s = second ?: return
        horizontal = !horizontal
        layout.orientation = if (horizontal) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        val dividerSize = (2 * resources.displayMetrics.density).toInt()
        // child 0 = first, 1 = divider, 2 = second
        layout.getChildAt(0).layoutParams = LinearLayout.LayoutParams(
            if (horizontal) 0 else LayoutParams.MATCH_PARENT,
            if (horizontal) LayoutParams.MATCH_PARENT else 0, 1f,
        )
        layout.getChildAt(1).layoutParams = LinearLayout.LayoutParams(
            if (horizontal) dividerSize else LayoutParams.MATCH_PARENT,
            if (horizontal) LayoutParams.MATCH_PARENT else dividerSize,
        )
        layout.getChildAt(2).layoutParams = LinearLayout.LayoutParams(
            if (horizontal) 0 else LayoutParams.MATCH_PARENT,
            if (horizontal) LayoutParams.MATCH_PARENT else 0, 1f,
        )
        layout.requestLayout()
        f.toggleOrientation()
        s.toggleOrientation()
    }

    /** Get all controllers in this subtree. */
    fun allControllers(): List<TerminalController> {
        val result = mutableListOf<TerminalController>()
        controller?.let { result.add(it) }
        first?.let { result.addAll(it.allControllers()) }
        second?.let { result.addAll(it.allControllers()) }
        return result
    }

    /** Destroy all controllers in this subtree. */
    fun destroyAll() {
        controller?.destroy()
        first?.destroyAll()
        second?.destroyAll()
    }

    private fun focusPane(tv: TerminalView, ctrl: TerminalController) {
        activePaneView = tv
        tv.requestFocus()
        onPaneFocused?.invoke(tv, ctrl)
    }

    private fun propagateFocus(tv: TerminalView, ctrl: TerminalController) {
        activePaneView = tv
        onPaneFocused?.invoke(tv, ctrl)
    }

    private companion object {
        const val DIVIDER_COLOR = 0xFF333355.toInt()
    }
}
