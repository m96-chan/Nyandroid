package dev.nyandroid.terminal

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
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

        val density = resources.displayMetrics.density
        val fontSpec = FontSpec.create(this, 14f * density)

        tabManager = TabManager(this, fontSpec)
        terminalView = TerminalView(this)
        extraKeysBar = ExtraKeysBar(this, terminalView)
        terminalView.extraKeysBar = extraKeysBar
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

    override fun onDestroy() {
        tabManager.destroyAll()
        super.onDestroy()
    }
}
