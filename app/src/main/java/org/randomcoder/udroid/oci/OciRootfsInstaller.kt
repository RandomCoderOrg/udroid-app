package org.randomcoder.udroid.oci

import android.content.Context
import org.randomcoder.udroid.install.AndroidRootfsConfigurator
import org.randomcoder.udroid.install.ByteProgress
import org.randomcoder.udroid.install.ProotRootfsHealthCheck
import org.randomcoder.udroid.install.ProotRuntimeInstaller
import org.randomcoder.udroid.install.RootfsInstallationPipeline
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class OciRootfsInstallRequest(
    val reference: OciImageReference,
    val platform: OciPlatform,
    val rootfsDirectory: File,
    val blobCacheDirectory: File,
    val installationName: String,
    val operationId: String,
)

data class OciRootfsInstallResult(
    val rootfs: File,
    val manifestDigest: String,
    val compressedBytes: Long,
    val reusedInstallation: Boolean,
)

enum class OciInstallStage {
    RESOLVING,
    DOWNLOADING,
    VERIFYING,
    ASSEMBLING,
    CONFIGURING,
    HEALTH_CHECKING,
    ACTIVATING,
    READY,
}

data class OciInstallEvent(
    val stage: OciInstallStage,
    val detail: String,
    val completedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val resumed: Boolean = false,
)

class OciRootfsInstaller(private val context: Context) {
    fun install(
        request: OciRootfsInstallRequest,
        onEvent: (OciInstallEvent) -> Unit = {},
    ): OciRootfsInstallResult {
        validate(request)
        val events = OciEventPublisher(onEvent)
        check(request.rootfsDirectory.mkdirs() || request.rootfsDirectory.isDirectory) {
            "Could not create rootfs storage"
        }
        val active = File(request.rootfsDirectory, request.installationName)
        val readyMarker = File(active, RootfsInstallationPipeline.READY_MARKER)
        if (readyMarker.isFile) {
            return OciRootfsInstallResult(
                rootfs = active,
                manifestDigest =
                    markerValue(readyMarker, "manifest")
                        ?: "unknown",
                compressedBytes = 0L,
                reusedInstallation = true,
            )
        }
        check(!active.exists()) {
            "A non-ready directory already exists at ${active.name}; it was left untouched"
        }

        val staging =
            File(
                request.rootfsDirectory,
                ".${request.installationName}.oci-staging",
            )
        clearOwnedStaging(staging, request)

        events.emit(
            OciInstallEvent(
                OciInstallStage.RESOLVING,
                "Resolving ${request.reference} for ${platformLabel(request.platform)}",
            ),
        )
        val pull =
            OciImagePuller().pull(
                reference = request.reference,
                platform = request.platform,
                cacheDirectory = request.blobCacheDirectory,
                onBlobDownloadProgress = { descriptor, progress ->
                    events.emit(
                        OciInstallEvent(
                            stage = OciInstallStage.DOWNLOADING,
                            detail = "Downloading ${shortDigest(descriptor.digest)}",
                            completedBytes = progress.completedBytes,
                            totalBytes = progress.totalBytes,
                            resumed = progress.resumed,
                        ),
                    )
                },
                onBlobVerifyProgress = { descriptor, progress ->
                    events.emit(
                        OciInstallEvent(
                            stage = OciInstallStage.VERIFYING,
                            detail = "Verifying ${shortDigest(descriptor.digest)}",
                            completedBytes = progress.completedBytes,
                            totalBytes = progress.totalBytes,
                            resumed = progress.resumed,
                        ),
                    )
                },
            )

        try {
            check(staging.mkdirs()) { "Could not create the OCI rootfs staging directory" }
            writeMarker(
                File(staging, INSTALLING_MARKER),
                markerBody(request, pull.image.manifestDigest),
            )
            val runtime = ProotRuntimeInstaller.install(context)
            events.emit(
                OciInstallEvent(
                    OciInstallStage.ASSEMBLING,
                    "Applying ${pull.layers.size} verified filesystem layer(s)",
                ),
            )
            OciLayerRootfsAssembler(context, runtime).assemble(
                layers = pull.layers,
                destination = staging,
            ) { index, count, completed, total ->
                events.emit(
                    OciInstallEvent(
                        stage = OciInstallStage.ASSEMBLING,
                        detail = "Applying layer ${index + 1} of $count",
                        completedBytes = completed,
                        totalBytes = total,
                    ),
                )
            }

            events.emit(
                OciInstallEvent(
                    OciInstallStage.CONFIGURING,
                    "Applying Android and PRoot compatibility files",
                ),
            )
            AndroidRootfsConfigurator().configure(staging)

            events.emit(
                OciInstallEvent(
                    OciInstallStage.HEALTH_CHECKING,
                    "Running the first-boot health probe",
                ),
            )
            ProotRootfsHealthCheck(context, runtime).check(staging)

            writeMarker(
                File(staging, RootfsInstallationPipeline.READY_MARKER),
                markerBody(request, pull.image.manifestDigest),
            )
            check(File(staging, INSTALLING_MARKER).delete()) {
                "Could not finalize OCI installation metadata"
            }
            events.emit(
                OciInstallEvent(
                    OciInstallStage.ACTIVATING,
                    "Activating ${request.installationName}",
                ),
            )
            activate(staging, active)
            cleanupVerifiedBlobs(pull)
            events.emit(
                OciInstallEvent(
                    OciInstallStage.READY,
                    "${request.installationName} is ready",
                ),
            )
            return OciRootfsInstallResult(
                rootfs = active,
                manifestDigest = pull.image.manifestDigest,
                compressedBytes = pull.layers.sumOf { it.descriptor.size },
                reusedInstallation = false,
            )
        } catch (error: Throwable) {
            if (staging.exists()) {
                check(staging.deleteRecursively()) {
                    "OCI installation failed and its staging rootfs could not be removed"
                }
            }
            throw error
        }
    }

    private fun validate(request: OciRootfsInstallRequest) {
        require(SAFE_NAME.matches(request.installationName)) {
            "Unsafe OCI rootfs name: ${request.installationName}"
        }
        require(SAFE_OPERATION_ID.matches(request.operationId)) {
            "Unsafe OCI operation id"
        }
        require(request.rootfsDirectory.absolutePath != request.blobCacheDirectory.absolutePath) {
            "OCI rootfs and blob cache directories must differ"
        }
    }

    private fun clearOwnedStaging(
        staging: File,
        request: OciRootfsInstallRequest,
    ) {
        if (!staging.exists()) return
        val marker = File(staging, INSTALLING_MARKER)
        check(
            marker.isFile &&
                markerValue(marker, "source") == "oci" &&
                markerValue(marker, "name") == request.installationName,
        ) {
            "An unrecognized OCI staging directory exists at ${staging.name}; it was left untouched"
        }
        check(staging.deleteRecursively()) {
            "Could not clear the interrupted OCI staging rootfs"
        }
    }

    private fun activate(
        staging: File,
        active: File,
    ) {
        check(!active.exists()) { "OCI installation target appeared during activation" }
        try {
            Files.move(
                staging.toPath(),
                active.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            check(staging.renameTo(active)) {
                "Could not atomically activate the OCI rootfs"
            }
        }
    }

    private fun cleanupVerifiedBlobs(pull: OciPullResult) {
        runCatching {
            (pull.layers.map(VerifiedOciLayer::file) + pull.configFile).forEach { file ->
                if (file.exists()) check(file.delete()) {
                    "Could not remove verified OCI blob ${file.name}"
                }
            }
        }
    }

    private fun markerBody(
        request: OciRootfsInstallRequest,
        manifestDigest: String,
    ): String =
        buildString {
            appendLine("format=1")
            appendLine("source=oci")
            appendLine("name=${request.installationName}")
            appendLine("operation=${request.operationId}")
            appendLine("reference=${request.reference}")
            appendLine("manifest=$manifestDigest")
            appendLine("platform=${platformLabel(request.platform)}")
        }

    private fun writeMarker(
        marker: File,
        body: String,
    ) {
        FileOutputStream(marker).use { output ->
            output.write(body.toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
    }

    private fun markerValue(
        marker: File,
        key: String,
    ): String? =
        marker.useLines { lines ->
            lines.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')
        }

    private fun platformLabel(platform: OciPlatform): String =
        listOfNotNull(platform.os, platform.architecture, platform.variant).joinToString("/")

    private fun shortDigest(digest: String): String =
        digest.substringAfter(':').take(12)

    private class OciEventPublisher(
        private val consumer: (OciInstallEvent) -> Unit,
    ) {
        private var lastKey: String? = null
        private var lastCompletedBytes = -1L
        private var lastEmissionNanos = 0L

        fun emit(event: OciInstallEvent) {
            val key = "${event.stage}:${event.detail}"
            val now = System.nanoTime()
            val progressEvent = event.totalBytes >= 0L
            val firstForKey = key != lastKey
            val completed = progressEvent && event.completedBytes >= event.totalBytes
            val advancedEnough =
                progressEvent &&
                    event.completedBytes - lastCompletedBytes >= MIN_PROGRESS_BYTES
            val waitedEnough =
                progressEvent &&
                    now - lastEmissionNanos >= MAX_PROGRESS_SILENCE_NANOS
            if (
                !progressEvent ||
                firstForKey ||
                completed ||
                advancedEnough ||
                waitedEnough
            ) {
                runCatching { consumer(event) }
                lastKey = key
                lastCompletedBytes = event.completedBytes
                lastEmissionNanos = now
            }
        }

        private companion object {
            const val MIN_PROGRESS_BYTES = 1024L * 1024L
            const val MAX_PROGRESS_SILENCE_NANOS = 250L * 1000L * 1000L
        }
    }

    private companion object {
        const val INSTALLING_MARKER = ".udroid-oci-installing"
        val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
        val SAFE_OPERATION_ID = Regex("[A-Za-z0-9-]{1,64}")
    }
}
