package org.randomcoder.udroid.install

import org.randomcoder.udroid.oci.OciHubRepository
import org.randomcoder.udroid.oci.OciHubTagPlatform
import org.randomcoder.udroid.oci.OciImageReference
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

object OciInstallationSelection {
    fun initial(
        repository: OciHubRepository,
        tag: OciHubTagPlatform,
        displayArchitecture: String,
    ): InstallProgress {
        val work =
            InstallerWorkRequest.Oci(
                reference =
                    OciImageReference.parse(
                        "docker.io/library/${repository.name}:${tag.tag}@${tag.digest}",
                    ),
                platform = tag.platform,
                installationName = installationName(repository.name, tag.tag),
                displayName = "${displayName(repository)} ${tag.tag}".take(MAX_DISPLAY_NAME_LENGTH),
                architecture = displayArchitecture,
                operationId = UUID.randomUUID().toString(),
            )
        return InstallProgress(
            work = work,
            stage = InstallStage.READY,
            stageProgress = 0f,
            currentDetail =
                "${formatBytes(tag.compressedBytes)} compressed · " +
                    "${platformLabel(tag)}",
            terminalLines =
                listOf(
                    "\$ udroid pull --plan ${work.reference}",
                    "[ready] digest ${tag.digest.take(19)}…",
                    "[ready] ${formatBytes(tag.compressedBytes)} compressed",
                ),
            previewOnly = false,
        )
    }

    fun installationName(
        repository: String,
        tag: String,
    ): String {
        val raw = "oci-$repository-$tag"
        require(SAFE_NAME.matches(raw)) { "OCI selection produced an unsafe installation name" }
        if (raw.length <= MAX_INSTALLATION_NAME_LENGTH) return raw
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(raw.toByteArray())
                .take(5)
                .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
        return raw
            .take(MAX_INSTALLATION_NAME_LENGTH - digest.length - 1)
            .trimEnd('.', '-', '_') +
            "-$digest"
    }

    fun displayName(repository: OciHubRepository): String =
        when (repository.name) {
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
                repository.name
                    .replace('-', ' ')
                    .replaceFirstChar { it.titlecase(Locale.US) }
        }

    private fun platformLabel(tag: OciHubTagPlatform): String =
        listOfNotNull(
            tag.platform.os,
            tag.platform.architecture,
            tag.platform.variant,
        ).joinToString("/")

    private fun formatBytes(bytes: Long): String =
        when {
            bytes >= 1024L * 1024L * 1024L ->
                String.format(Locale.US, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L ->
                String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
            else -> "$bytes B"
        }

    private const val MAX_INSTALLATION_NAME_LENGTH = 96
    private const val MAX_DISPLAY_NAME_LENGTH = 160
    private val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]+")
}
