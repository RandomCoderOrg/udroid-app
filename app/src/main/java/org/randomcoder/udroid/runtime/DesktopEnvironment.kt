package org.randomcoder.udroid.runtime

import android.content.Context
import org.randomcoder.udroid.linuxapps.DesktopExecParser
import java.io.File

enum class DesktopEnvironmentKind(
    val desktopName: String,
    val compositorSupport: DesktopCompositorSupport,
) {
    XFCE("XFCE", DesktopCompositorSupport.CONFIGURABLE),
    PLASMA("KDE", DesktopCompositorSupport.CONFIGURABLE),
    GNOME("GNOME", DesktopCompositorSupport.REQUIRED),
    MATE("MATE", DesktopCompositorSupport.CONFIGURABLE),
    LXQT("LXQt", DesktopCompositorSupport.EXTERNAL_OR_NONE),
    OTHER("X11", DesktopCompositorSupport.UNKNOWN),
}

enum class DesktopCompositorSupport {
    CONFIGURABLE,
    REQUIRED,
    EXTERNAL_OR_NONE,
    UNKNOWN,
}

data class DesktopEnvironment(
    val id: String,
    val name: String,
    val command: List<String>,
    val desktopFilePath: String,
    val kind: DesktopEnvironmentKind,
)

data class DesktopConfiguration(
    val environmentId: String?,
    val compositingEnabled: Boolean,
    val touchScaleEnabled: Boolean,
)

enum class DesktopSessionPhase {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    CRASHED,
}

data class DesktopSessionSnapshot(
    val phase: DesktopSessionPhase = DesktopSessionPhase.STOPPED,
    val desiredRunning: Boolean = false,
    val rootfsName: String? = null,
    val environmentId: String? = null,
    val environmentName: String? = null,
    val displayNumber: Int? = null,
    val message: String = "No desktop session is running",
)

class DesktopConfigurationStore(context: Context) {
    private val preferences =
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(
        rootfsName: String,
        environments: List<DesktopEnvironment>,
    ): DesktopConfiguration {
        val selected =
            preferences
                .getString(key(rootfsName, KEY_ENVIRONMENT), null)
                ?.let { id -> environments.firstOrNull { it.id == id } }
                ?: environments.firstOrNull()
        val defaultCompositing =
            selected?.kind?.compositorSupport == DesktopCompositorSupport.REQUIRED
        val compositorKey =
            key(
                rootfsName,
                "$KEY_COMPOSITING:${selected?.id ?: "default"}",
            )
        val legacyCompositorKey = key(rootfsName, KEY_COMPOSITING)
        return DesktopConfiguration(
            environmentId = selected?.id,
            compositingEnabled =
                when {
                    preferences.contains(compositorKey) ->
                        preferences.getBoolean(compositorKey, defaultCompositing)
                    preferences.contains(legacyCompositorKey) ->
                        preferences.getBoolean(legacyCompositorKey, defaultCompositing)
                    else -> defaultCompositing
                },
            touchScaleEnabled =
                preferences.getBoolean(key(rootfsName, KEY_TOUCH_SCALE), true),
        )
    }

    fun save(
        rootfsName: String,
        configuration: DesktopConfiguration,
    ): DesktopConfiguration {
        check(
            preferences
                .edit()
                .putNullableString(
                    key(rootfsName, KEY_ENVIRONMENT),
                    configuration.environmentId,
                ).putBoolean(
                    key(
                        rootfsName,
                        "$KEY_COMPOSITING:${configuration.environmentId ?: "default"}",
                    ),
                    configuration.compositingEnabled,
                ).putBoolean(
                    key(rootfsName, KEY_TOUCH_SCALE),
                    configuration.touchScaleEnabled,
                ).commit(),
        ) {
            "Could not save desktop settings for $rootfsName"
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
            "Could not clear desktop settings for $rootfsName"
        }
    }

    private fun key(
        rootfsName: String,
        setting: String,
    ): String = "$rootfsName:$setting"

    private fun android.content.SharedPreferences.Editor.putNullableString(
        key: String,
        value: String?,
    ): android.content.SharedPreferences.Editor =
        if (value == null) remove(key) else putString(key, value)

    private companion object {
        const val PREFERENCES_NAME = "desktop-configuration"
        const val KEY_ENVIRONMENT = "environment"
        const val KEY_COMPOSITING = "compositing"
        const val KEY_TOUCH_SCALE = "touch-scale"
    }
}

class DesktopEnvironmentScanner {
    fun scan(rootfs: File): List<DesktopEnvironment> {
        require(rootfs.isDirectory) { "Installed rootfs is unavailable" }
        return SESSION_DIRECTORIES
            .asSequence()
            .map { guestPath -> guestPath to File(rootfs, guestPath.removePrefix("/")) }
            .filter { (_, directory) -> directory.isDirectory }
            .flatMap { (guestDirectory, directory) ->
                directory
                    .listFiles { file -> file.isFile && file.extension == "desktop" }
                    .orEmpty()
                    .asSequence()
                    .sortedBy(File::getName)
                    .mapNotNull { file -> parseSession(file, "$guestDirectory/${file.name}") }
            }.distinctBy(DesktopEnvironment::id)
            .sortedWith(
                compareBy<DesktopEnvironment> { kindOrder(it.kind) }
                    .thenBy { it.name.lowercase() },
            ).toList()
    }

    private fun parseSession(
        file: File,
        guestPath: String,
    ): DesktopEnvironment? {
        val values = parseDesktopEntry(file)
        if (values["Type"]?.trim() != "Application") return null
        if (values.boolean("Hidden") || values.boolean("NoDisplay")) return null
        val name = values["Name"]?.unescape()?.trim()?.takeIf(String::isNotBlank) ?: return null
        val rawExec = values["Exec"]?.trim()?.takeIf(String::isNotBlank) ?: return null
        val command =
            DesktopExecParser
                .parse(
                    commandLine = rawExec,
                    applicationName = name,
                    iconName = null,
                    desktopFileGuestPath = guestPath,
                ).getOrNull()
                ?: return null
        val id = file.nameWithoutExtension
        return DesktopEnvironment(
            id = id,
            name = name,
            command = command,
            desktopFilePath = guestPath,
            kind =
                classify(
                    listOf(
                        id,
                        name,
                        values["DesktopNames"].orEmpty(),
                        command.joinToString(" "),
                    ).joinToString(" "),
                ),
        )
    }

    private fun parseDesktopEntry(file: File): Map<String, String> {
        val values = linkedMapOf<String, String>()
        var active = false
        file.useLines(Charsets.UTF_8) { lines ->
            lines.forEach { raw ->
                val line = raw.removeSuffix("\r")
                if (line.isBlank() || line.startsWith("#")) return@forEach
                if (line.startsWith("[") && line.endsWith("]")) {
                    active = line == "[Desktop Entry]"
                    return@forEach
                }
                if (!active) return@forEach
                val separator = line.indexOf('=')
                if (separator > 0) {
                    values.putIfAbsent(
                        line.substring(0, separator),
                        line.substring(separator + 1),
                    )
                }
            }
        }
        return values
    }

    private fun classify(value: String): DesktopEnvironmentKind {
        val normalized = value.lowercase()
        return when {
            "xfce" in normalized -> DesktopEnvironmentKind.XFCE
            "plasma" in normalized || "kde" in normalized -> DesktopEnvironmentKind.PLASMA
            "gnome" in normalized -> DesktopEnvironmentKind.GNOME
            "mate" in normalized -> DesktopEnvironmentKind.MATE
            "lxqt" in normalized -> DesktopEnvironmentKind.LXQT
            else -> DesktopEnvironmentKind.OTHER
        }
    }

    private fun kindOrder(kind: DesktopEnvironmentKind): Int =
        when (kind) {
            DesktopEnvironmentKind.XFCE -> 0
            DesktopEnvironmentKind.LXQT -> 1
            DesktopEnvironmentKind.MATE -> 2
            DesktopEnvironmentKind.PLASMA -> 3
            DesktopEnvironmentKind.GNOME -> 4
            DesktopEnvironmentKind.OTHER -> 5
        }

    private fun Map<String, String>.boolean(key: String): Boolean =
        get(key)?.trim()?.equals("true", ignoreCase = true) == true

    private fun String.unescape(): String =
        replace("\\s", " ")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\\", "\\")

    private companion object {
        val SESSION_DIRECTORIES =
            listOf(
                "/usr/share/xsessions",
                "/usr/local/share/xsessions",
            )
    }
}
