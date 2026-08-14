package org.randomcoder.udroid.media

import android.content.Context

data class MediaAccelerationConfiguration(
    val enabled: Boolean = false,
)

class MediaAccelerationConfigurationStore(context: Context) {
    private val preferences =
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(rootfsName: String): MediaAccelerationConfiguration =
        MediaAccelerationConfiguration(
            enabled = preferences.getBoolean(key(rootfsName), false),
        )

    fun save(
        rootfsName: String,
        configuration: MediaAccelerationConfiguration,
    ): MediaAccelerationConfiguration {
        check(
            preferences
                .edit()
                .putBoolean(key(rootfsName), configuration.enabled)
                .commit(),
        ) {
            "Could not save media acceleration settings for $rootfsName"
        }
        return configuration
    }

    fun remove(rootfsName: String) {
        check(preferences.edit().remove(key(rootfsName)).commit()) {
            "Could not clear media acceleration settings for $rootfsName"
        }
    }

    private fun key(rootfsName: String): String = "$rootfsName:$KEY_ENABLED"

    private companion object {
        const val PREFERENCES_NAME = "media-acceleration-configuration"
        const val KEY_ENABLED = "enabled"
    }
}
