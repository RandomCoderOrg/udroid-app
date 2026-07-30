package org.randomcoder.udroid.install

import android.content.Context
import org.randomcoder.udroid.oci.OciImageReference
import org.randomcoder.udroid.oci.OciPlatform
import java.io.File
import java.util.Locale
import java.util.UUID

class InstalledRootfsSourceStore(context: Context) {
    private val preferences =
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(work: InstallerWorkRequest) {
        check(
            preferences
                .edit()
                .putString(work.installationName, InstallerWorkRequestCodec.encode(work))
                .commit(),
        ) {
            "Could not remember the install source for ${work.installationName}"
        }
    }

    fun load(installationName: String): InstallerWorkRequest? =
        preferences
            .getString(installationName, null)
            ?.let(InstallerWorkRequestCodec::decode)
            ?.takeIf { it.installationName == installationName }

    fun loadOrRecover(
        installationName: String,
        rootfs: File,
    ): InstallerWorkRequest? {
        load(installationName)?.let { return it }
        val recovered =
            InstalledRootfsSourceRecovery.fromReadyMarker(
                installationName = installationName,
                marker = File(rootfs, RootfsInstallationPipeline.READY_MARKER),
            ) ?: return null
        save(recovered)
        return recovered
    }

    fun remove(installationName: String) {
        check(preferences.edit().remove(installationName).commit()) {
            "Could not clear the install source for $installationName"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "installed-rootfs-sources"
    }
}

internal object InstalledRootfsSourceRecovery {
    fun fromReadyMarker(
        installationName: String,
        marker: File,
    ): InstallerWorkRequest.Oci? =
        runCatching {
            require(marker.isFile && marker.length() in 1..MAX_MARKER_BYTES)
            val values =
                marker
                    .readLines()
                    .mapNotNull { line ->
                        val separator = line.indexOf('=')
                        if (separator <= 0) {
                            null
                        } else {
                            line.substring(0, separator) to line.substring(separator + 1)
                        }
                    }.toMap()
            require(values["source"] == "oci")
            require(values["name"] == installationName)
            val reference = OciImageReference.parse(values.getValue("reference"))
            val platformParts = values.getValue("platform").split('/')
            require(platformParts.size in 2..3)
            val platform =
                OciPlatform(
                    os = platformParts[0],
                    architecture = platformParts[1],
                    variant = platformParts.getOrNull(2)?.takeIf(String::isNotBlank),
                )
            InstallerWorkRequest.Oci(
                reference = reference,
                platform = platform,
                installationName = installationName,
                displayName = displayName(reference),
                architecture =
                    when (platform.architecture) {
                        "arm64" -> "aarch64"
                        "arm" -> "armhf"
                        else -> platform.architecture
                    },
                operationId =
                    values["operation"]
                        ?.takeIf(SAFE_OPERATION_ID::matches)
                        ?: UUID.randomUUID().toString(),
            )
        }.getOrNull()

    private fun displayName(reference: OciImageReference): String {
        val repository = reference.repository.substringAfterLast('/')
        val name =
            when (repository) {
                "ubuntu" -> "Ubuntu"
                "debian" -> "Debian"
                "alpine" -> "Alpine Linux"
                "archlinux" -> "Arch Linux"
                "fedora" -> "Fedora"
                "almalinux" -> "AlmaLinux"
                "rockylinux" -> "Rocky Linux"
                "amazonlinux" -> "Amazon Linux"
                "oraclelinux" -> "Oracle Linux"
                else ->
                    repository
                        .replace('-', ' ')
                        .replaceFirstChar { it.titlecase(Locale.US) }
            }
        return listOfNotNull(name, reference.tag).joinToString(" ")
    }

    private const val MAX_MARKER_BYTES = 32 * 1024L
    private val SAFE_OPERATION_ID = Regex("[A-Za-z0-9-]{1,64}")
}
