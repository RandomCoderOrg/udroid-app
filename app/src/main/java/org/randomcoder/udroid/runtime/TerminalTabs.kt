package org.randomcoder.udroid.runtime

data class TerminalTabSnapshot(
    val id: String,
    val title: String,
    val rootfsName: String,
    val pid: Long?,
    val running: Boolean,
    val active: Boolean,
)

internal data class TerminalTab<T>(
    val id: String,
    var title: String,
    val rootfsName: String,
    val value: T,
)

internal class TerminalTabRegistry<T> {
    private val tabs = LinkedHashMap<String, TerminalTab<T>>()

    var activeId: String? = null
        private set

    fun all(): List<TerminalTab<T>> = tabs.values.toList()

    fun active(): TerminalTab<T>? = activeId?.let(tabs::get)

    fun get(id: String): TerminalTab<T>? = tabs[id]

    fun findByValue(value: T): TerminalTab<T>? =
        tabs.values.firstOrNull { it.value === value }

    fun add(tab: TerminalTab<T>): TerminalTab<T> {
        require(tab.id !in tabs) { "Duplicate terminal tab id ${tab.id}" }
        tabs[tab.id] = tab
        activeId = tab.id
        return tab
    }

    fun select(id: String): TerminalTab<T>? {
        val tab = tabs[id] ?: return null
        activeId = id
        return tab
    }

    fun rename(
        id: String,
        title: String,
    ): TerminalTab<T>? {
        val tab = tabs[id] ?: return null
        tab.title = title
        return tab
    }

    fun remove(id: String): TerminalTab<T>? {
        val index = tabs.keys.indexOf(id)
        val removed = tabs.remove(id) ?: return null
        if (activeId == id) {
            val remaining = tabs.values.toList()
            activeId = remaining.getOrNull(index.coerceAtMost(remaining.lastIndex))?.id
        }
        return removed
    }

    fun clear(): List<TerminalTab<T>> =
        tabs.values.toList().also {
            tabs.clear()
            activeId = null
        }

    fun size(): Int = tabs.size
}
