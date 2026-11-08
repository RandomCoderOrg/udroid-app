package org.randomcoder.udroid.runtime

data class TerminalTabSnapshot(
    val id: String,
    val title: String,
    val hasCustomTitle: Boolean,
    val rootfsName: String,
    val pid: Long?,
    val running: Boolean,
    val commandRunning: Boolean,
    val active: Boolean,
    val closeState: TerminalTabCloseState = TerminalTabCloseState.OPEN,
    val closeError: String? = null,
)

enum class TerminalTabCloseState {
    OPEN,
    CLOSING,
    FORCE_CLOSING,
    FAILED,
}

fun terminalDistroTitle(rawName: String?): String =
    when {
        rawName.isNullOrBlank() -> "Linux"
        rawName.contains("focal", ignoreCase = true) -> "Ubuntu Focal"
        rawName.contains("jammy", ignoreCase = true) -> "Ubuntu Jammy"
        rawName.contains("noble", ignoreCase = true) -> "Ubuntu Noble"
        rawName.contains("resolute", ignoreCase = true) -> "Ubuntu Resolute"
        else ->
            rawName
                .removePrefix("udroid-")
                .removeSuffix("-raw")
                .split('-')
                .joinToString(" ") { word -> word.replaceFirstChar(Char::titlecase) }
    }

internal data class TerminalTab<T>(
    val id: String,
    val defaultTitle: String,
    val rootfsName: String,
    val value: T,
    var shellTitle: String? = null,
    var customTitle: String? = null,
    var commandRunning: Boolean = false,
    var closeState: TerminalTabCloseState = TerminalTabCloseState.OPEN,
    var closeError: String? = null,
) {
    val title: String
        get() = customTitle ?: shellTitle ?: defaultTitle
}

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
        tab.customTitle = title
        return tab
    }

    fun updateShellTitle(
        id: String,
        title: String?,
    ): TerminalTab<T>? {
        val tab = tabs[id] ?: return null
        if (tab.shellTitle == title) return null
        tab.shellTitle = title
        return tab
    }

    fun followShellTitle(id: String): TerminalTab<T>? {
        val tab = tabs[id]?.takeIf { it.customTitle != null } ?: return null
        tab.customTitle = null
        return tab
    }

    fun updateCommandRunning(
        id: String,
        running: Boolean,
    ): TerminalTab<T>? {
        val tab = tabs[id] ?: return null
        if (tab.commandRunning == running) return null
        tab.commandRunning = running
        return tab
    }

    fun beginClose(id: String): TerminalTab<T>? {
        val tab = tabs[id] ?: return null
        if (tab.closeState == TerminalTabCloseState.CLOSING ||
            tab.closeState == TerminalTabCloseState.FORCE_CLOSING
        ) {
            return null
        }
        tab.closeState = TerminalTabCloseState.CLOSING
        tab.closeError = null
        return tab
    }

    fun markForceClosing(id: String): TerminalTab<T>? =
        tabs[id]?.also {
            it.closeState = TerminalTabCloseState.FORCE_CLOSING
            it.closeError = null
        }

    fun markCloseFailed(
        id: String,
        message: String,
    ): TerminalTab<T>? =
        tabs[id]?.also {
            it.closeState = TerminalTabCloseState.FAILED
            it.closeError = message
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

internal fun normalizeTerminalTitle(rawTitle: String?): String? =
    rawTitle
        ?.filter { character ->
            !character.isISOControl() &&
                Character.getType(character) != Character.FORMAT.toInt()
        }?.trim()
        ?.take(MAX_TERMINAL_TITLE_CHARS)
        ?.ifEmpty { null }

internal const val MAX_TERMINAL_TITLE_CHARS = 40
