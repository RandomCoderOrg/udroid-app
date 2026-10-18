package org.randomcoder.udroid

import android.content.Context

internal data class DebugCommand(
    val action: String,
    val rootfsName: String? = null,
    val environmentId: String? = null,
)

internal object DebugCommandStore {
    private const val PREFERENCES_NAME = "adb-debug-command"
    private const val MAX_AGE_MS = 30_000L
    private val actions =
        setOf(
            "open-system",
            "open-desktop",
            "start-terminal",
            "stop-terminal",
            "start-desktop",
            "stop-desktop",
        )

    fun isSupported(action: String): Boolean = action in actions

    @Synchronized
    fun enqueue(context: Context, command: DebugCommand): Boolean {
        require(isSupported(command.action))
        val preferences = preferences(context)
        val age = System.currentTimeMillis() - preferences.getLong("created-at", 0L)
        if (preferences.contains("action") && age in 0..MAX_AGE_MS) return false
        return preferences.edit()
            .putString("action", command.action)
            .putString("rootfs", command.rootfsName)
            .putString("environment", command.environmentId)
            .putLong("created-at", System.currentTimeMillis())
            .commit()
    }

    @Synchronized
    fun take(context: Context): DebugCommand? {
        val preferences = preferences(context)
        val action = preferences.getString("action", null) ?: return null
        val age = System.currentTimeMillis() - preferences.getLong("created-at", 0L)
        val command =
            DebugCommand(
                action = action,
                rootfsName = preferences.getString("rootfs", null),
                environmentId = preferences.getString("environment", null),
            )
        check(preferences.edit().clear().commit()) { "Could not clear the debug command" }
        return command.takeIf { age in 0..MAX_AGE_MS && isSupported(action) }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
