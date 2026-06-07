package dev.nyandroid.terminal.view

import android.content.Context
import dev.nyandroid.terminal.font.FontSpec

/**
 * Manages multiple terminal sessions (tabs). Each tab has a [SplitContainer]
 * that can hold one or more terminal panes.
 */
class TabManager(
    private val context: Context,
    private val fontSpec: FontSpec,
    private val scrollbackLines: Int = 10_000,
) {
    data class Tab(
        val id: Int,
        val splitContainer: SplitContainer,
        var title: String = "Terminal",
    ) {
        /** The first (or only) controller, for backwards compatibility. */
        val controller: TerminalController
            get() = splitContainer.allControllers().first()
    }

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
        val split = SplitContainer(context, fontSpec, scrollbackLines)
        split.initAsLeaf()
        val tab = Tab(nextId++, split, "Terminal ${tabs.size + 1}")
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
        tab.splitContainer.destroyAll()
        if (activeIndex >= tabs.size) {
            activeIndex = tabs.size - 1
        } else if (activeIndex > index) {
            activeIndex--
        }
        onTabsChanged?.invoke()
    }

    fun destroyAll() {
        tabs.forEach { it.splitContainer.destroyAll() }
        tabs.clear()
    }
}
