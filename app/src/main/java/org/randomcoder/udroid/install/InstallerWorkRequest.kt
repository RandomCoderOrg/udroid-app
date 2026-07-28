package org.randomcoder.udroid.install

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.randomcoder.udroid.catalog.DistroProvider
import org.randomcoder.udroid.catalog.DistroVariant
import org.randomcoder.udroid.catalog.LinuxDistribution
import org.randomcoder.udroid.oci.OciImageReference
import org.randomcoder.udroid.oci.OciPlatform

sealed interface InstallerWorkRequest {
    val operationId: String
    val installationName: String
    val displayName: String
    val architecture: String

    data class Archive(
        val distro: DistroVariant,
        override val operationId: String,
    ) : InstallerWorkRequest {
        override val installationName: String = distro.internalName
        override val displayName: String = distro.releaseName
        override val architecture: String = distro.architecture
    }

    data class Oci(
        val reference: OciImageReference,
        val platform: OciPlatform,
        override val installationName: String,
        override val displayName: String,
        override val architecture: String,
        override val operationId: String,
    ) : InstallerWorkRequest
}

/**
 * A small, versioned wire format keeps the foreground service independent of
 * catalogue objects and avoids Android Serializable/Parcelable compatibility
 * traps when the application is upgraded while an operation is redelivered.
 */
object InstallerWorkRequestCodec {
    fun encode(request: InstallerWorkRequest): String =
        buildJsonObject {
            put("format", FORMAT)
            put("operation_id", request.operationId)
            put("installation_name", request.installationName)
            put("display_name", request.displayName)
            put("architecture", request.architecture)
            when (request) {
                is InstallerWorkRequest.Archive -> {
                    put("source", SOURCE_ARCHIVE)
                    put("suite", request.distro.suite)
                    put("variant", request.distro.variant)
                    put("friendly_name", request.distro.friendlyName)
                    put("download_url", request.distro.downloadUrl)
                    put("sha256", request.distro.sha256)
                    put("distribution", request.distro.distribution.id)
                    put("provider", request.distro.provider.name)
                    request.distro.releaseLabel?.let { put("release_label", it) }
                    put(
                        "archive_strip_components",
                        request.distro.archiveStripComponents,
                    )
                }

                is InstallerWorkRequest.Oci -> {
                    put("source", SOURCE_OCI)
                    put("reference", request.reference.toString())
                    put("platform_os", request.platform.os)
                    put("platform_architecture", request.platform.architecture)
                    request.platform.variant?.let { put("platform_variant", it) }
                }
            }
        }.toString()

    fun decode(encoded: String): InstallerWorkRequest {
        require(encoded.length <= MAX_ENCODED_LENGTH) {
            "Installer work request is too large"
        }
        val value = Json.parseToJsonElement(encoded).jsonObject
        require(value.requiredString("format") == FORMAT) {
            "Unsupported installer work request format"
        }
        val operationId = value.requiredString("operation_id")
        require(SAFE_OPERATION_ID.matches(operationId)) {
            "Unsafe installer operation id"
        }
        val installationName = value.requiredString("installation_name")
        require(SAFE_INSTALLATION_NAME.matches(installationName)) {
            "Unsafe installation name"
        }
        val displayName = value.requiredString("display_name")
        val architecture = value.requiredString("architecture")
        require(displayName.isNotBlank() && displayName.length <= 160) {
            "Invalid installer display name"
        }
        require(architecture.isNotBlank() && architecture.length <= 32) {
            "Invalid installer architecture"
        }

        return when (value.requiredString("source")) {
            SOURCE_ARCHIVE ->
                InstallerWorkRequest.Archive(
                    operationId = operationId,
                    distro =
                        DistroVariant(
                            suite = value.requiredString("suite"),
                            variant = value.requiredString("variant"),
                            internalName = installationName,
                            friendlyName = value.requiredString("friendly_name"),
                            architecture = architecture,
                            downloadUrl = value.requiredString("download_url"),
                            sha256 = value.requiredString("sha256"),
                            distribution =
                                value["distribution"]
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                                    ?.let { id ->
                                        LinuxDistribution.entries.firstOrNull { it.id == id }
                                    }
                                    ?: LinuxDistribution.UBUNTU,
                            provider =
                                value["provider"]
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                                    ?.let { name ->
                                        DistroProvider.entries.firstOrNull { it.name == name }
                                    }
                                    ?: DistroProvider.UDROID,
                            releaseLabel =
                                value["release_label"]
                                    ?.jsonPrimitive
                                    ?.contentOrNull,
                            archiveStripComponents =
                                value["archive_strip_components"]
                                    ?.jsonPrimitive
                                    ?.intOrNull
                                    ?: 0,
                        ),
                )

            SOURCE_OCI ->
                InstallerWorkRequest.Oci(
                    operationId = operationId,
                    installationName = installationName,
                    displayName = displayName,
                    architecture = architecture,
                    reference = OciImageReference.parse(value.requiredString("reference")),
                    platform =
                        OciPlatform(
                            os = value.requiredString("platform_os"),
                            architecture = value.requiredString("platform_architecture"),
                            variant =
                                value["platform_variant"]
                                    ?.jsonPrimitive
                                    ?.contentOrNull,
                        ),
                )

            else -> error("Unsupported installer source")
        }
    }

    private fun JsonObject.requiredString(key: String): String =
        getValue(key).jsonPrimitive.content

    private const val FORMAT = "1"
    private const val SOURCE_ARCHIVE = "archive"
    private const val SOURCE_OCI = "oci"
    private const val MAX_ENCODED_LENGTH = 32 * 1024
    private val SAFE_OPERATION_ID = Regex("[A-Za-z0-9-]{1,64}")
    private val SAFE_INSTALLATION_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
}
