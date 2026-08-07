package org.randomcoder.udroid.audio

import android.content.Context

data class AudioConfiguration(
    val outputEnabled: Boolean = true,
    val microphoneEnabled: Boolean = false,
)

class AudioConfigurationStore(context: Context) {
    private val preferences =
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(rootfsName: String): AudioConfiguration =
        AudioConfiguration(
            outputEnabled =
                preferences.getBoolean(key(rootfsName, KEY_OUTPUT), true),
            microphoneEnabled =
                preferences.getBoolean(key(rootfsName, KEY_MICROPHONE), false),
        )

    fun save(
        rootfsName: String,
        configuration: AudioConfiguration,
    ): AudioConfiguration {
        check(
            preferences
                .edit()
                .putBoolean(key(rootfsName, KEY_OUTPUT), configuration.outputEnabled)
                .putBoolean(
                    key(rootfsName, KEY_MICROPHONE),
                    configuration.microphoneEnabled,
                ).commit(),
        ) {
            "Could not save audio settings for $rootfsName"
        }
        return configuration
    }

    fun remove(rootfsName: String) {
        val prefix = "$rootfsName:"
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith(prefix) }
            .forEach(editor::remove)
        check(editor.commit()) {
            "Could not clear audio settings for $rootfsName"
        }
    }

    private fun key(
        rootfsName: String,
        setting: String,
    ): String = "$rootfsName:$setting"

    private companion object {
        const val PREFERENCES_NAME = "audio-configuration"
        const val KEY_OUTPUT = "output"
        const val KEY_MICROPHONE = "microphone"
    }
}
