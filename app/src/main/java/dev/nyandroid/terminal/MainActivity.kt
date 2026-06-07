package dev.nyandroid.terminal

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.nyandroid.terminal.config.KittyConfig
import dev.nyandroid.terminal.font.FontSpec
import dev.nyandroid.terminal.view.ExtraKeysBar
import dev.nyandroid.terminal.view.TabBar
import dev.nyandroid.terminal.view.TabManager
import dev.nyandroid.terminal.view.TerminalView

/**
 * Single-screen host for the terminal. Supports multiple tabs, each with
 * its own SSH session.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var terminalView: TerminalView
    private lateinit var extraKeysBar: ExtraKeysBar
    private lateinit var tabManager: TabManager
    private lateinit var tabBar: TabBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val config = KittyConfig.load(this)
        KittyConfig.applyColors(config)

        val density = resources.displayMetrics.density
        val fontSpec = FontSpec.create(this, config.fontSize * density)

        tabManager = TabManager(this, fontSpec, config.scrollbackLines)
        terminalView = TerminalView(this)
        extraKeysBar = ExtraKeysBar(this, terminalView)
        terminalView.extraKeysBar = extraKeysBar
        terminalView.keyBindings = config.keyBindings
        terminalView.onKeyBindingAction = { action, args -> handleKeyAction(action, args) }
        tabBar = TabBar(this, tabManager)

        // Create initial tab and set controller BEFORE setContentView, because
        // SurfaceView callbacks fire during layout and access the controller.
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
            terminalView,
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
            extraKeysBar.visibility = if (imeInsets.bottom > 0) View.VISIBLE else View.GONE
            WindowInsetsCompat.CONSUMED
        }

        terminalView.requestFocus()
    }

    private fun createNewTab() {
        tabManager.createTab()
        switchToActiveTab()
    }

    private fun switchToActiveTab() {
        val tab = tabManager.activeTab ?: return
        terminalView.setController(tab.controller)
    }

    private fun handleKeyAction(action: String, args: String): Boolean = when (action) {
        "copy_to_clipboard" -> {
            terminalView.performCopy(); true
        }
        "paste_from_clipboard" -> {
            terminalView.performPaste(); true
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
        "change_font_size" -> {
            // TODO: runtime font size change requires renderer rebuild
            true
        }
        "scroll_line_up" -> {
            terminalView.controller.scrollViewport(-1); true
        }
        "scroll_line_down" -> {
            terminalView.controller.scrollViewport(1); true
        }
        else -> false
    }

    override fun onDestroy() {
        tabManager.destroyAll()
        super.onDestroy()
    }
}
