package org.randomcoder.udroid.runtime

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

data class ProotManagedEnvironmentVariable(
    val name: String,
    val defaultValue: String,
)

val PROOT_DEFAULT_ENVIRONMENT_VARIABLES =
    listOf(
        ProotManagedEnvironmentVariable("LANG", "C.UTF-8"),
        ProotManagedEnvironmentVariable(
            "PATH",
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        ),
        ProotManagedEnvironmentVariable("TERM", "xterm-256color"),
        ProotManagedEnvironmentVariable("COLORTERM", "truecolor"),
    )

val PROOT_LOCKED_ENVIRONMENT_VARIABLE_NAMES =
    setOf(
        "HOME",
        "USER",
        "LOGNAME",
        "SHELL",
        "DISPLAY",
        "PULSE_SERVER",
        "PULSE_COOKIE",
        "XDG_SESSION_TYPE",
        "XDG_CURRENT_DESKTOP",
        "DESKTOP_SESSION",
        "GDK_BACKEND",
        "QT_QPA_PLATFORM",
        "GDK_SCALE",
        "QT_SCALE_FACTOR",
        "XCURSOR_SIZE",
        "LIBGL_ALWAYS_SOFTWARE",
        "GALLIUM_DRIVER",
        "MESA_LOADER_DRIVER_OVERRIDE",
        "LIBGL_KOPPER_DRI2",
    )

data class ProotCustomEnvironmentVariable(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val value: String,
)

data class ProotEnvironmentProfile(
    val defaultOverrides: Map<String, String> = emptyMap(),
    val managedOverrides: Map<String, String> = emptyMap(),
    val customVariables: List<ProotCustomEnvironmentVariable> = emptyList(),
)

object ProotEnvironmentProfileValidator {
    fun requireValid(profile: ProotEnvironmentProfile): ProotEnvironmentProfile {
        val defaultNames = PROOT_DEFAULT_ENVIRONMENT_VARIABLES.mapTo(mutableSetOf()) { it.name }
        require(profile.defaultOverrides.keys.all(defaultNames::contains)) {
            "The profile contains an unknown default environment variable"
        }
        profile.defaultOverrides.values.forEach(::requireValidValue)
        require(profile.managedOverrides.keys.all(PROOT_LOCKED_ENVIRONMENT_VARIABLE_NAMES::contains)) {
            "The profile contains an unknown uDroid-managed environment variable"
        }
        profile.managedOverrides.values.forEach(::requireValidValue)

        require(profile.customVariables.size <= MAX_CUSTOM_VARIABLES) {
            "An environment profile supports at most $MAX_CUSTOM_VARIABLES custom variables"
        }
        require(profile.customVariables.map { it.id }.distinct().size == profile.customVariables.size) {
            "Custom environment variable IDs must be unique"
        }
        require(profile.customVariables.map { it.name }.distinct().size == profile.customVariables.size) {
            "Custom environment variable names must be unique"
        }
        profile.customVariables.forEach { variable ->
            require(SAFE_ID.matches(variable.id)) { "Invalid custom environment variable ID" }
            require(variable.name.length <= MAX_NAME_LENGTH && VARIABLE_NAME.matches(variable.name)) {
                "Invalid environment variable name"
            }
            require(variable.name !in defaultNames) {
                "Custom variables cannot replace editable defaults"
            }
            require(variable.name !in PROOT_LOCKED_ENVIRONMENT_VARIABLE_NAMES) {
                "${variable.name} is managed by uDroid"
            }
            requireValidValue(variable.value)
        }
        return profile
    }

    private fun requireValidValue(value: String) {
        require(value.length <= MAX_VALUE_LENGTH) { "Environment variable value is too long" }
        require('\u0000' !in value) { "Environment variable value contains NUL" }
    }

    private const val MAX_CUSTOM_VARIABLES = 64
    private const val MAX_NAME_LENGTH = 128
    private const val MAX_VALUE_LENGTH = 8192
    private val VARIABLE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
}

object ProotEnvironmentResolver {
    fun resolve(profile: ProotEnvironmentProfile = ProotEnvironmentProfile()): List<String> {
        ProotEnvironmentProfileValidator.requireValid(profile)
        return buildList {
            PROOT_DEFAULT_ENVIRONMENT_VARIABLES.forEach { variable ->
                add("${variable.name}=${profile.defaultOverrides[variable.name] ?: variable.defaultValue}")
            }
            profile.customVariables.forEach { add("${it.name}=${it.value}") }
        }
    }

    fun resolveManagedOverrides(
        profile: ProotEnvironmentProfile = ProotEnvironmentProfile(),
    ): List<String> {
        ProotEnvironmentProfileValidator.requireValid(profile)
        return PROOT_LOCKED_ENVIRONMENT_VARIABLE_NAMES.mapNotNull { name ->
            profile.managedOverrides[name]?.let { value -> "$name=$value" }
        }
    }

    fun applyManagedOverrides(
        command: List<String>,
        overrides: List<String>,
    ): List<String> =
        if (overrides.isEmpty()) command else listOf("/usr/bin/env") + overrides + command
}

class ProotEnvironmentProfileStore(context: Context) {
    private val systemsDirectory = File(context.applicationContext.filesDir, "linux-systems")

    @Synchronized
    fun load(systemId: String): ProotEnvironmentProfile {
        val file = profileFile(systemId)
        if (!file.isFile) return ProotEnvironmentProfile()
        return ProotEnvironmentProfileCodec.decode(file.readText())
    }

    @Synchronized
    fun save(
        systemId: String,
        profile: ProotEnvironmentProfile,
    ): ProotEnvironmentProfile {
        requireSafeSystemId(systemId)
        val validated = ProotEnvironmentProfileValidator.requireValid(profile)
        val encoded = ProotEnvironmentProfileCodec.encode(validated)
        val target = profileFile(systemId)
        check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true) {
            "Could not create environment profile storage for $systemId"
        }
        val temporary = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(encoded.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        return validated
    }

    @Synchronized
    fun restoreDefaults(systemId: String): ProotEnvironmentProfile =
        save(systemId, ProotEnvironmentProfile())

    @Synchronized
    fun remove(systemId: String) {
        val file = profileFile(systemId)
        if (file.exists()) check(file.delete()) { "Could not delete environment profile for $systemId" }
        file.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
    }

    private fun profileFile(systemId: String): File {
        requireSafeSystemId(systemId)
        return File(File(systemsDirectory, systemId), PROFILE_FILE_NAME)
    }

    private fun requireSafeSystemId(systemId: String) {
        require(SAFE_SYSTEM_ID.matches(systemId)) { "Unsafe Linux system ID: $systemId" }
    }

    private companion object {
        const val PROFILE_FILE_NAME = "environment.json"
        val SAFE_SYSTEM_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
    }
}

internal object ProotEnvironmentProfileCodec {
    fun encode(profile: ProotEnvironmentProfile): String {
        ProotEnvironmentProfileValidator.requireValid(profile)
        val encoded =
            buildJsonObject {
                put("format", FORMAT)
                put("defaults_revision", DEFAULTS_REVISION)
                put(
                    "default_overrides",
                    JsonObject(profile.defaultOverrides.mapValues { JsonPrimitive(it.value) }),
                )
                put(
                    "managed_overrides",
                    JsonObject(profile.managedOverrides.mapValues { JsonPrimitive(it.value) }),
                )
                put(
                    "custom_variables",
                    JsonArray(
                        profile.customVariables.map { variable ->
                            buildJsonObject {
                                put("id", variable.id)
                                put("name", variable.name)
                                put("value", variable.value)
                            }
                        },
                    ),
                )
            }.toString()
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED_BYTES) {
            "Environment profile is too large"
        }
        return encoded
    }

    fun decode(encoded: String): ProotEnvironmentProfile {
        require(encoded.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED_BYTES) {
            "Environment profile is too large"
        }
        val value = Json.parseToJsonElement(encoded).jsonObject
        require(value.requiredString("format") == FORMAT) {
            "Unsupported environment profile format"
        }
        val overrides =
            value["default_overrides"]
                ?.jsonObject
                ?.mapValues { (_, entry) -> entry.jsonPrimitive.content }
                .orEmpty()
        val managedOverrides =
            value["managed_overrides"]
                ?.jsonObject
                ?.mapValues { (_, entry) -> entry.jsonPrimitive.content }
                .orEmpty()
        val customVariables =
            value["custom_variables"]
                ?.jsonArray
                ?.map { element ->
                    val variable = element.jsonObject
                    ProotCustomEnvironmentVariable(
                        id = variable.requiredString("id"),
                        name = variable.requiredString("name"),
                        value = variable.requiredString("value"),
                    )
                }.orEmpty()
        return ProotEnvironmentProfileValidator.requireValid(
            ProotEnvironmentProfile(
                defaultOverrides = overrides,
                managedOverrides = managedOverrides,
                customVariables = customVariables,
            ),
        )
    }

    private fun JsonObject.requiredString(key: String): String = getValue(key).jsonPrimitive.content

    private const val FORMAT = "1"
    private const val DEFAULTS_REVISION = 1
    private const val MAX_ENCODED_BYTES = 128 * 1024
}
