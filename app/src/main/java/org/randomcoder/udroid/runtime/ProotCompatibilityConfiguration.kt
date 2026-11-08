package org.randomcoder.udroid.runtime

import android.content.Context

data class ProotCompatibilityConfiguration(
    val sysvipcEnabled: Boolean = false,
    val memfdFallbackEnabled: Boolean = false,
)

class ProotCompatibilityConfigurationStore(context: Context) {
    private val preferences =
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(systemId: String): ProotCompatibilityConfiguration =
        ProotCompatibilityConfiguration(
            sysvipcEnabled = preferences.getBoolean(key(systemId, KEY_SYSVIPC), false),
            memfdFallbackEnabled = preferences.getBoolean(key(systemId, KEY_MEMFD_FALLBACK), false),
        )

    fun save(
        systemId: String,
        configuration: ProotCompatibilityConfiguration,
    ): ProotCompatibilityConfiguration {
        check(
            preferences
                .edit()
                .putBoolean(key(systemId, KEY_SYSVIPC), configuration.sysvipcEnabled)
                .putBoolean(key(systemId, KEY_MEMFD_FALLBACK), configuration.memfdFallbackEnabled)
                .commit(),
        ) {
            "Could not save PRoot compatibility settings for $systemId"
        }
        return configuration
    }

    fun remove(systemId: String) {
        val prefix = "$systemId:"
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith(prefix) }
            .forEach(editor::remove)
        check(editor.commit()) {
            "Could not clear PRoot compatibility settings for $systemId"
        }
    }

    private fun key(
        systemId: String,
        setting: String,
    ): String = "$systemId:$setting"

    private companion object {
        const val PREFERENCES_NAME = "proot-compatibility-configuration"
        const val KEY_SYSVIPC = "sysvipc"
        const val KEY_MEMFD_FALLBACK = "memfd-fallback"
    }
}

internal fun MutableList<String>.addProotCompatibilityOptions(
    configuration: ProotCompatibilityConfiguration,
) {
    if (configuration.sysvipcEnabled) add("--sysvipc")
    if (configuration.memfdFallbackEnabled) add("--ashmem-memfd")
}
