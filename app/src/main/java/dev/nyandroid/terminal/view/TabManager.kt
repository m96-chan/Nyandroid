package dev.nyandroid.terminal.view

import android.content.Context
import dev.nyandroid.terminal.font.FontSpec

/**
 * Manages multiple terminal sessions (tabs). Each tab has its own
 * [TerminalController] with independent backend + emulator + renderer.
 */
class TabManager(
    private val context: Context,
    private val fontSpec: FontSpec,
    private val scrollbackLines: Int = 10_000,
) {
    data class Tab(
        val id: Int,
        val controller: TerminalController,
        var title: String = "Terminal",
    )

    private val tabs = mutableListOf<Tab>()
    private var nextId = 0
    var activeIndex = 0
        private set

    /** Called when the tab list or active tab changes. */
    var onTabsChanged: (() -> Unit)? = null

    val tabCount: Int get() = tabs.size
    val activeTab: Tab? get() = tabs.getOrNull(activeIndex)

    fun getTabs(): List<Tab> = tabs.toList()

    fun createTab(): Tab {
        val controller = TerminalController(context, fontSpec, scrollbackLines)
        val tab = Tab(nextId++, controller, "Terminal ${tabs.size + 1}")
        tabs.add(tab)
        activeIndex = tabs.size - 1
        onTabsChanged?.invoke()
        return tab
    }

    fun switchTo(index: Int) {
        if (index in tabs.indices && index != activeIndex) {
            activeIndex = index
            onTabsChanged?.invoke()
        }
    }

    fun closeTab(index: Int) {
        if (index !in tabs.indices || tabs.size <= 1) return
        val tab = tabs.removeAt(index)
        tab.controller.destroy()
        if (activeIndex >= tabs.size) {
            activeIndex = tabs.size - 1
        } else if (activeIndex > index) {
            activeIndex--
        }
        onTabsChanged?.invoke()
    }

    fun destroyAll() {
        tabs.forEach { it.controller.destroy() }
        tabs.clear()
    }
}
