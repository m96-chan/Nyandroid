package dev.nyandroid.terminal

import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.nyandroid.terminal.config.KittyConfig
import dev.nyandroid.terminal.font.FontSpec
import dev.nyandroid.terminal.view.ExtraKeysBar
import dev.nyandroid.terminal.view.SplitContainer
import dev.nyandroid.terminal.view.TabBar
import dev.nyandroid.terminal.view.TabManager
import dev.nyandroid.terminal.view.TerminalView

/**
 * Single-screen host for the terminal. Supports multiple tabs with splits.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var extraKeysBar: ExtraKeysBar
    private lateinit var tabManager: TabManager
    private lateinit var tabBar: TabBar
    private lateinit var terminalArea: FrameLayout
    private lateinit var config: KittyConfig

    /** The currently focused terminal view (in any pane). */
    private var activeTerminalView: TerminalView? = null
    private var currentFontSizePx = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        config = KittyConfig.load(this)
        KittyConfig.applyColors(config)

        val density = resources.displayMetrics.density
        currentFontSizePx = config.fontSize * density
        val fontSpec = FontSpec.create(this, currentFontSizePx)

        tabManager = TabManager(this, fontSpec, config.scrollbackLines)

        // ExtraKeysBar needs a TerminalView — we'll create a temporary one
        // and update it when tabs are created.
        val initialView = TerminalView(this)
        extraKeysBar = ExtraKeysBar(this, initialView)
        extraKeysBar.onSettingsPressed = { SettingsActivity.launch(this) }

        tabBar = TabBar(this, tabManager)
        terminalArea = FrameLayout(this)

        tabManager.onTabsChanged = {
            tabBar.rebuild()
            switchToActiveTab()
        }
        tabBar.onTabSwitch = { switchToActiveTab() }
        tabBar.onNewTab = { createNewTab() }
        createNewTab()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        container.addView(
            tabBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        container.addView(
            terminalArea,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )
        container.addView(
            extraKeysBar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        setContentView(container)

        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val bottomInset = maxOf(imeInsets.bottom, systemBars.bottom)
            view.setPadding(
                systemBars.left, systemBars.top, systemBars.right, bottomInset,
            )
            val hasHardwareKeyboard = resources.configuration.keyboard == Configuration.KEYBOARD_QWERTY
            extraKeysBar.visibility = if (imeInsets.bottom > 0 || hasHardwareKeyboard) View.VISIBLE else View.GONE
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun createNewTab() {
        tabManager.createTab()
        switchToActiveTab()
    }

    private fun switchToActiveTab() {
        val tab = tabManager.activeTab ?: return
        val split = tab.splitContainer

        // Remove old split container from terminal area.
        terminalArea.removeAllViews()
        terminalArea.addView(split, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        // Setup callbacks for all terminal views in this tab.
        split.setupCallbacks { tv -> setupTerminalView(tv) }

        // Wire focus tracking.
        split.onPaneFocused = { tv, ctrl ->
            setActiveTerminalView(tv)
        }

        // Focus the active pane.
        val tv = split.activePaneView ?: split.terminalView
        tv?.let { setActiveTerminalView(it) }
    }

    private fun setupTerminalView(tv: TerminalView) {
        tv.extraKeysBar = extraKeysBar
        tv.keyBindings = config.keyBindings
        tv.onKeyBindingAction = { action, args -> handleKeyAction(action, args) }
        tv.onFontSizeChanged = { newSizePx ->
            val clamped = newSizePx.coerceIn(MIN_FONT_SIZE_PX, MAX_FONT_SIZE_PX)
            if (clamped != currentFontSizePx) {
                currentFontSizePx = clamped
                // Font size changes require full rebuild — deferred.
                Log.i("MainActivity", "Font size pinch to ${clamped}px (restart to apply)")
            }
        }
    }

    private fun setActiveTerminalView(tv: TerminalView) {
        activeTerminalView = tv
        // Re-bind the extra keys bar to the new active view.
        extraKeysBar.rebindTo(tv)
        tv.requestFocus()
    }

    private fun handleKeyAction(action: String, args: String): Boolean = when (action) {
        "copy_to_clipboard" -> {
            activeTerminalView?.performCopy(); true
        }
        "paste_from_clipboard" -> {
            activeTerminalView?.performPaste(); true
        }
        "new_tab" -> {
            createNewTab(); true
        }
        "close_tab" -> {
            if (tabManager.tabCount > 1) {
                tabManager.closeTab(tabManager.activeIndex)
            }
            true
        }
        "next_tab" -> {
            val next = (tabManager.activeIndex + 1) % tabManager.tabCount
            tabManager.switchTo(next)
            switchToActiveTab()
            true
        }
        "previous_tab" -> {
            val prev = (tabManager.activeIndex - 1 + tabManager.tabCount) % tabManager.tabCount
            tabManager.switchTo(prev)
            switchToActiveTab()
            true
        }
        "split_horizontal" -> {
            tabManager.activeTab?.splitContainer?.let { split ->
                val target = findFocusedLeaf(split)
                target?.split(true)?.let { newPane ->
                    newPane.setupCallbacks { tv -> setupTerminalView(tv) }
                }
            }
            true
        }
        "split_vertical" -> {
            tabManager.activeTab?.splitContainer?.let { split ->
                val target = findFocusedLeaf(split)
                target?.split(false)?.let { newPane ->
                    newPane.setupCallbacks { tv -> setupTerminalView(tv) }
                }
            }
            true
        }
        "scroll_line_up" -> {
            activeTerminalView?.controller?.scrollViewport(-1); true
        }
        "scroll_line_down" -> {
            activeTerminalView?.controller?.scrollViewport(1); true
        }
        else -> false
    }

    /** Find the leaf SplitContainer that currently has focus. */
    private fun findFocusedLeaf(container: SplitContainer): SplitContainer? {
        if (container.isLeaf()) return container
        // Recursively search — simplified, returns first leaf for now.
        return null
    }

    override fun onDestroy() {
        tabManager.destroyAll()
        super.onDestroy()
    }

    private companion object {
        const val MIN_FONT_SIZE_PX = 16f
        const val MAX_FONT_SIZE_PX = 80f
    }
}
