package org.randomcoder.udroid.install

import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.nio.charset.StandardCharsets

data class RootfsInstallRequest(
    val archive: File,
    val rootfsDirectory: File,
    val installationName: String,
    val operationId: String,
)

data class RootfsInstallResult(
    val rootfs: File,
    val reusedInstallation: Boolean,
)

fun interface RootfsExtractor {
    fun extract(
        archive: File,
        destination: File,
        onProgress: (completedBytes: Long, totalBytes: Long) -> Unit,
    )
}

fun interface RootfsConfigurator {
    fun configure(rootfs: File)
}

fun interface RootfsHealthCheck {
    fun check(rootfs: File)
}

class RootfsInstallationPipeline(
    private val extractor: RootfsExtractor,
    private val configurator: RootfsConfigurator,
    private val healthCheck: RootfsHealthCheck,
) {
    fun execute(
        request: RootfsInstallRequest,
        onExtractionProgress: (completedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onConfiguring: (detail: String) -> Unit = {},
    ): RootfsInstallResult {
        require(request.archive.isFile) {
            "Verified archive is missing: ${request.archive.name}"
        }
        require(SAFE_NAME.matches(request.installationName)) {
            "Unsafe rootfs name: ${request.installationName}"
        }
        require(SAFE_OPERATION_ID.matches(request.operationId)) {
            "Unsafe operation id"
        }

        check(request.rootfsDirectory.mkdirs() || request.rootfsDirectory.isDirectory) {
            "Could not create rootfs storage"
        }
        val active = File(request.rootfsDirectory, request.installationName)
        if (File(active, READY_MARKER).isFile) {
            request.archive.delete()
            return RootfsInstallResult(active, reusedInstallation = true)
        }

        clearInterruptedInstallation(request.rootfsDirectory, request.installationName)
        check(!active.exists()) {
            "A non-uDroid directory already exists at ${active.name}; it was left untouched"
        }
        check(active.mkdirs()) { "Could not create ${request.installationName}" }

        try {
            writeInstallingMarker(active, request)
            checkInterrupted()
            extractor.extract(request.archive, active, onExtractionProgress)
            checkInterrupted()

            onConfiguring("Applying Android and PRoot compatibility files")
            configurator.configure(active)
            checkInterrupted()

            onConfiguring("Running the first-boot health probe")
            healthCheck.check(active)
            checkInterrupted()

            writeReadyMarker(active, request)
            check(File(active, INSTALLING_MARKER).delete()) {
                "Could not finish ${request.installationName}"
            }
            request.archive.delete()
            return RootfsInstallResult(active, reusedInstallation = false)
        } catch (error: Throwable) {
            active.deleteRecursively()
            throw error
        }
    }

    private fun writeInstallingMarker(
        rootfs: File,
        request: RootfsInstallRequest,
    ) {
        writeMarker(
            marker = File(rootfs, INSTALLING_MARKER),
            body =
                buildString {
                    appendLine("format=1")
                    appendLine("name=${request.installationName}")
                    appendLine("operation=${request.operationId}")
                },
        )
    }

    private fun writeReadyMarker(
        rootfs: File,
        request: RootfsInstallRequest,
    ) {
        writeMarker(
            marker = File(rootfs, READY_MARKER),
            body =
                buildString {
                    appendLine("format=1")
                    appendLine("name=${request.installationName}")
                    appendLine("operation=${request.operationId}")
                },
        )
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

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("Rootfs installation interrupted")
        }
    }

    companion object {
        const val READY_MARKER = ".udroid-ready"
        internal const val INSTALLING_MARKER = ".udroid-installing"
        private val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
        private val SAFE_OPERATION_ID = Regex("[A-Za-z0-9-]{1,64}")

        internal fun clearInterruptedInstallation(
            rootfsDirectory: File,
            installationName: String,
        ) {
            require(SAFE_NAME.matches(installationName)) {
                "Unsafe rootfs name: $installationName"
            }
            val rootfs = File(rootfsDirectory, installationName)
            if (!File(rootfs, INSTALLING_MARKER).isFile) return
            check(rootfs.deleteRecursively()) {
                "Could not clear the interrupted $installationName installation"
            }
        }
    }
}
