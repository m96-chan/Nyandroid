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
    private lateinit var bellOverlay: View
    private lateinit var searchOverlay: dev.nyandroid.terminal.view.SearchOverlay
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
        dev.nyandroid.terminal.backend.ShellIntegration.deployScript(this)
        TerminalService.start(this)

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
        bellOverlay = View(this).apply {
            setBackgroundColor(0x66FFFFFF) // translucent white flash
            visibility = View.GONE
        }
        searchOverlay = dev.nyandroid.terminal.view.SearchOverlay(this).apply {
            visibility = View.GONE
            onQuery = { query, forward -> activeTerminalView?.controller?.searchScrollback(query, forward) ?: false }
            onClose = { visibility = View.GONE }
        }

        // Background blur behind the (optionally translucent) window (#22).
        if (config.backgroundBlur > 0 && android.os.Build.VERSION.SDK_INT >= 31) {
            window.setBackgroundBlurRadius(config.backgroundBlur)
        }

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
        val terminalWrapper = FrameLayout(this).apply {
            addView(terminalArea, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            addView(bellOverlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            addView(searchOverlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        container.addView(
            terminalWrapper,
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

        // Wire connection state changes to update the tab bar.
        for (ctrl in split.allControllers()) {
            ctrl.onConnectionStateChanged = { _ -> runOnUiThread { tabBar.rebuild() } }
        }

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
            // The view already applied the resize live; just remember it.
            currentFontSizePx = newSizePx.coerceIn(MIN_FONT_SIZE_PX, MAX_FONT_SIZE_PX)
        }
        tv.onVisualBell = { flashVisualBell() }
        // Window/tab title from OSC 0/2 (#34).
        tv.controller.onTitle = { t -> runOnUiThread { onTerminalTitleChanged(tv, t) } }
        // Apply config-driven rendering/bell knobs to this pane's controller.
        applyConfigTo(tv)
    }

    private fun onTerminalTitleChanged(tv: TerminalView, newTitle: String) {
        val label = newTitle.ifBlank { "Terminal" }
        tabManager.getTabs().firstOrNull { it.controller === tv.controller }?.let {
            it.title = label
            tabBar.rebuild()
        }
        if (tv === activeTerminalView) title = newTitle.ifBlank { getString(R.string.app_name) }
    }

    private fun applyConfigTo(tv: TerminalView) {
        val ctrl = tv.controller
        ctrl.audioBellEnabled = config.enableAudioBell
        ctrl.backgroundOpacity = config.backgroundOpacity
        ctrl.ligaturesEnabled = config.ligaturesEnabled
        ctrl.shellIntegrationEnabled = config.shellIntegration == "enabled"
        tv.setBackgroundTranslucent(config.backgroundOpacity < 1f)
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
        "next_layout" -> {
            tabManager.activeTab?.splitContainer?.toggleOrientation(); true
        }
        "scroll_to_prompt" -> {
            activeTerminalView?.controller?.scrollToPrompt(); true
        }
        "show_scrollback" -> {
            searchOverlay.show(); true
        }
        else -> false
    }

    /** Briefly flashes the screen for a visual bell (#21). */
    private fun flashVisualBell() {
        bellOverlay.visibility = View.VISIBLE
        bellOverlay.alpha = 1f
        bellOverlay.animate().alpha(0f).setDuration(180).withEndAction {
            bellOverlay.visibility = View.GONE
        }.start()
    }

    /** Find the leaf SplitContainer that currently has focus. */
    private fun findFocusedLeaf(container: SplitContainer): SplitContainer {
        val focused = activeTerminalView
        if (focused != null) {
            container.leafContaining(focused)?.let { return it }
        }
        return container.firstLeaf()
    }

    override fun onDestroy() {
        tabManager.destroyAll()
        TerminalService.stop(this)
        super.onDestroy()
    }

    private companion object {
        const val MIN_FONT_SIZE_PX = 16f
        const val MAX_FONT_SIZE_PX = 80f
    }
}
