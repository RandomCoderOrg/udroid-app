package org.randomcoder.udroid.runtime

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
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

data class ProotDefaultMount(
    val id: String,
    val hostSource: String,
    val guestTarget: String = hostSource,
    val label: String,
)

val PROOT_DEFAULT_MOUNTS =
    listOf(
        ProotDefaultMount("android.system", "/system", label = "Device system"),
        ProotDefaultMount("android.apex", "/apex", label = "Android runtime"),
        ProotDefaultMount("android.dev", "/dev", label = "Device interfaces"),
        ProotDefaultMount("android.proc", "/proc", label = "Process information"),
        ProotDefaultMount("android.sys", "/sys", label = "Kernel information"),
        ProotDefaultMount(
            "android.linkerconfig",
            "/linkerconfig/ld.config.txt",
            label = "Android linker configuration",
        ),
    )

data class ProotCustomMount(
    val id: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val hostSource: String,
    val guestTarget: String,
)

data class ProotMountProfile(
    val name: String = "Default profile",
    val sourceSystemId: String? = null,
    val defaultOverrides: Map<String, Boolean> = emptyMap(),
    val customMounts: List<ProotCustomMount> = emptyList(),
) {
    fun isDefaultEnabled(id: String): Boolean = defaultOverrides[id] ?: true

    fun withDefaultEnabled(
        id: String,
        enabled: Boolean,
    ): ProotMountProfile {
        require(PROOT_DEFAULT_MOUNTS.any { it.id == id }) { "Unknown default mount $id" }
        val overrides = defaultOverrides.toMutableMap()
        if (enabled) overrides.remove(id) else overrides[id] = false
        return copy(defaultOverrides = overrides)
    }

    fun independentCopy(): ProotMountProfile =
        copy(customMounts = customMounts.map { it.copy(id = UUID.randomUUID().toString()) })
}

data class ResolvedProotMount(
    val hostSource: String,
    val guestTarget: String,
    val origin: String,
) {
    val argument: String
        get() = if (hostSource == guestTarget) hostSource else "$hostSource:$guestTarget"
}

object ProotMountProfileValidator {
    fun requireValid(profile: ProotMountProfile): ProotMountProfile {
        require(profile.name.isNotBlank() && profile.name == profile.name.trim()) {
            "Profile name must not be blank"
        }
        require(profile.name.length <= MAX_PROFILE_NAME_LENGTH) {
            "Profile name is too long"
        }
        require(profile.sourceSystemId == null || SAFE_SYSTEM_ID.matches(profile.sourceSystemId)) {
            "Profile source system ID is invalid"
        }
        require(profile.customMounts.size <= MAX_CUSTOM_MOUNTS) {
            "A mount profile supports at most $MAX_CUSTOM_MOUNTS custom mappings"
        }
        val knownDefaults = PROOT_DEFAULT_MOUNTS.mapTo(mutableSetOf(), ProotDefaultMount::id)
        require(profile.defaultOverrides.keys.all(knownDefaults::contains)) {
            "The profile contains an unknown default mount"
        }
        require(profile.customMounts.map(ProotCustomMount::id).distinct().size == profile.customMounts.size) {
            "Custom mount IDs must be unique"
        }
        profile.customMounts.forEach { mount ->
            require(SAFE_ID.matches(mount.id)) { "Invalid custom mount ID" }
            requireSafePath(mount.hostSource, "Host source")
            requireSafePath(mount.guestTarget, "Guest target")
            require(mount.guestTarget !in RESERVED_GUEST_TARGETS) {
                "${mount.guestTarget} is managed by the uDroid runtime"
            }
        }

        val enabledTargets =
            buildList {
                PROOT_DEFAULT_MOUNTS
                    .filter { profile.isDefaultEnabled(it.id) }
                    .forEach { add(it.guestTarget) }
                profile.customMounts.filter(ProotCustomMount::enabled).forEach {
                    add(it.guestTarget)
                }
            }
        require(enabledTargets.distinct().size == enabledTargets.size) {
            "Enabled mappings must use unique guest destinations"
        }
        return profile
    }

    private fun requireSafePath(
        path: String,
        label: String,
    ) {
        require(path.isNotBlank() && path == path.trim()) { "$label must not be blank" }
        require(path.startsWith('/')) { "$label must be an absolute path" }
        require(path.length <= MAX_PATH_LENGTH) { "$label is too long" }
        require('\u0000' !in path && '\n' !in path && '\r' !in path) {
            "$label contains unsupported characters"
        }
        require(':' !in path) { "$label cannot contain ':' because PRoot uses it as a delimiter" }
        require(path.split('/').none { it == "." || it == ".." }) {
            "$label must not contain '.' or '..' segments"
        }
    }

    val RESERVED_GUEST_TARGETS = setOf("/tmp/.X11-unix", "/tmp/.udroid-pulse")

    private const val MAX_CUSTOM_MOUNTS = 64
    private const val MAX_PROFILE_NAME_LENGTH = 64
    private const val MAX_PATH_LENGTH = 1024
    private val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
    private val SAFE_SYSTEM_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
}

object ProotMountResolver {
    fun resolve(
        profile: ProotMountProfile,
        sessionMounts: List<ResolvedProotMount> = emptyList(),
    ): List<ResolvedProotMount> {
        ProotMountProfileValidator.requireValid(profile)
        val resolved =
            buildList {
                PROOT_DEFAULT_MOUNTS
                    .filter { profile.isDefaultEnabled(it.id) }
                    .forEach {
                        add(
                            ResolvedProotMount(
                                hostSource = it.hostSource,
                                guestTarget = it.guestTarget,
                                origin = "default:${it.id}",
                            ),
                        )
                    }
                profile.customMounts.filter(ProotCustomMount::enabled).forEach {
                    add(
                        ResolvedProotMount(
                            hostSource = it.hostSource,
                            guestTarget = it.guestTarget,
                            origin = "custom:${it.id}",
                        ),
                    )
                }
                addAll(sessionMounts)
            }
        val targets = resolved.map(ResolvedProotMount::guestTarget)
        require(targets.distinct().size == targets.size) {
            "Resolved mappings contain duplicate guest destinations"
        }
        return resolved
    }

    fun defaults(
        x11SocketDirectory: String? = null,
        audioAuthDirectory: String? = null,
    ): List<ResolvedProotMount> =
        resolve(
            profile = ProotMountProfile(),
            sessionMounts = sessionMounts(x11SocketDirectory, audioAuthDirectory),
        )

    fun sessionMounts(
        x11SocketDirectory: String?,
        audioAuthDirectory: String?,
    ): List<ResolvedProotMount> =
        buildList {
            if (x11SocketDirectory != null) {
                add(
                    ResolvedProotMount(
                        hostSource = x11SocketDirectory,
                        guestTarget = "/tmp/.X11-unix",
                        origin = "runtime:x11",
                    ),
                )
            }
            if (audioAuthDirectory != null) {
                add(
                    ResolvedProotMount(
                        hostSource = audioAuthDirectory,
                        guestTarget = "/tmp/.udroid-pulse",
                        origin = "runtime:audio",
                    ),
                )
            }
        }
}

class ProotMountProfileStore(context: Context) {
    private val systemsDirectory = File(context.applicationContext.filesDir, "linux-systems")

    @Synchronized
    fun systemIds(): List<String> =
        systemsDirectory
            .listFiles()
            .orEmpty()
            .asSequence()
            .filter(File::isDirectory)
            .filter { File(it, PROFILE_FILE_NAME).isFile }
            .map(File::getName)
            .filter(SAFE_SYSTEM_ID::matches)
            .sorted()
            .toList()

    @Synchronized
    fun load(systemId: String): ProotMountProfile {
        val file = profileFile(systemId)
        if (!file.isFile) return ProotMountProfile()
        return ProotMountProfileCodec.decode(file.readText())
    }

    @Synchronized
    fun save(
        systemId: String,
        profile: ProotMountProfile,
    ): ProotMountProfile {
        requireSafeSystemId(systemId)
        val validated = ProotMountProfileValidator.requireValid(profile)
        val target = profileFile(systemId)
        check(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true) {
            "Could not create mount profile storage for $systemId"
        }
        val temporary = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(ProotMountProfileCodec.encode(validated).toByteArray())
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
    fun restoreDefaults(systemId: String): ProotMountProfile = save(systemId, ProotMountProfile())

    @Synchronized
    fun copy(
        sourceSystemId: String,
        destinationSystemId: String,
    ): ProotMountProfile {
        val source = load(sourceSystemId)
        return save(
            destinationSystemId,
            source
                .copy(sourceSystemId = source.sourceSystemId ?: sourceSystemId)
                .independentCopy(),
        )
    }

    @Synchronized
    fun remove(systemId: String) {
        val file = profileFile(systemId)
        if (file.exists()) check(file.delete()) { "Could not delete mount profile for $systemId" }
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
        const val PROFILE_FILE_NAME = "mounts.json"
        val SAFE_SYSTEM_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
    }
}

internal object ProotMountProfileCodec {
    fun encode(profile: ProotMountProfile): String =
        buildJsonObject {
            put("format", FORMAT)
            put("defaults_revision", DEFAULTS_REVISION)
            put("name", profile.name)
            profile.sourceSystemId?.let { put("source_system_id", it) }
            put(
                "default_overrides",
                JsonObject(profile.defaultOverrides.mapValues { JsonPrimitive(it.value) }),
            )
            put(
                "custom_mounts",
                JsonArray(
                    profile.customMounts.map { mount ->
                        buildJsonObject {
                            put("id", mount.id)
                            put("enabled", mount.enabled)
                            put("host_source", mount.hostSource)
                            put("guest_target", mount.guestTarget)
                        }
                    },
                ),
            )
        }.toString()

    fun decode(encoded: String): ProotMountProfile {
        require(encoded.length <= MAX_ENCODED_LENGTH) { "Mount profile is too large" }
        val value = Json.parseToJsonElement(encoded).jsonObject
        require(value.requiredString("format") == FORMAT) { "Unsupported mount profile format" }
        val name = value["name"]?.jsonPrimitive?.content ?: "Default profile"
        val sourceSystemId = value["source_system_id"]?.jsonPrimitive?.content
        val overrides =
            value["default_overrides"]
                ?.jsonObject
                ?.mapValues { (_, enabled) -> enabled.jsonPrimitive.boolean }
                .orEmpty()
        val customMounts =
            value["custom_mounts"]
                ?.jsonArray
                ?.map { element ->
                    val mount = element.jsonObject
                    ProotCustomMount(
                        id = mount.requiredString("id"),
                        enabled = mount.getValue("enabled").jsonPrimitive.boolean,
                        hostSource = mount.requiredString("host_source"),
                        guestTarget = mount.requiredString("guest_target"),
                    )
                }.orEmpty()
        return ProotMountProfileValidator.requireValid(
            ProotMountProfile(
                name = name,
                sourceSystemId = sourceSystemId,
                defaultOverrides = overrides,
                customMounts = customMounts,
            ),
        )
    }

    private fun JsonObject.requiredString(key: String): String =
        getValue(key).jsonPrimitive.content

    private const val FORMAT = "1"
    private const val DEFAULTS_REVISION = 1
    private const val MAX_ENCODED_LENGTH = 128 * 1024
}
