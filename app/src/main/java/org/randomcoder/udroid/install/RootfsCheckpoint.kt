package org.randomcoder.udroid.install

import android.content.Context
import org.randomcoder.udroid.runtime.ANDROID_PROOT_BIND_MOUNTS
import org.randomcoder.udroid.runtime.AndroidExecutableCommand
import org.randomcoder.udroid.runtime.ProotPathContract
import org.randomcoder.udroid.runtime.RootfsTreeDeleter
import org.randomcoder.udroid.runtime.addAndroidProotBindMounts
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class RootfsCheckpointArchiver(
    private val context: Context,
    private val runtime: ProotRuntime,
) {
    fun archive(
        rootfs: File,
        output: OutputStream,
        onProgress: (bytes: Long) -> Unit = {},
    ) {
        require(File(rootfs, RootfsInstallationPipeline.READY_MARKER).isFile) {
            "Linux system ${rootfs.name} is not ready"
        }
        val runtimeMount = File(rootfs, RUNTIME_MOUNT_DIRECTORY)
        check(!Files.isSymbolicLink(runtimeMount.toPath())) {
            "Unsafe checkpoint runtime path"
        }
        check(runtimeMount.mkdirs() || runtimeMount.isDirectory) {
            "Could not prepare checkpoint runtime"
        }
        val tarMount = File(runtimeMount, "tar").apply {
            if (exists()) check(delete()) { "Could not clear the previous GNU tar mount" }
            check(createNewFile()) { "Could not prepare the GNU tar mount" }
        }
        val command =
            AndroidExecutableCommand.create(
                runtime.executable,
                *buildArguments(
                    rootfsPath = ProotPathContract.rootfsPath(context, rootfs),
                    tarExecutablePath = runtime.tarExecutable.absolutePath,
                    tarMountPath = "/$RUNTIME_MOUNT_DIRECTORY/${tarMount.name}",
                ).toTypedArray(),
            )
        val temporaryDirectory =
            File(context.cacheDir, "proot").apply {
                check(mkdirs() || isDirectory) { "Could not create PRoot temporary storage" }
            }
        val process =
            ProcessBuilder(command)
                .directory(context.filesDir)
                .redirectErrorStream(false)
                .apply {
                    environment().remove("LD_PRELOAD")
                    environment()["HOME"] = context.filesDir.absolutePath
                    environment()["TMPDIR"] = temporaryDirectory.absolutePath
                    environment()["PROOT_TMP_DIR"] = temporaryDirectory.absolutePath
                    environment()["PROOT_LOADER"] = runtime.loader.absolutePath
                }.start()
        val stderr = mutableListOf<String>()
        val stderrThread =
            Thread {
                BufferedReader(InputStreamReader(process.errorStream)).useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stderr) {
                            if (stderr.size == MAX_DIAGNOSTIC_LINES) stderr.removeAt(0)
                            stderr += line
                        }
                    }
                }
            }.apply {
                name = "udroid-checkpoint-stderr"
                isDaemon = true
                start()
            }

        try {
            process.inputStream.buffered().use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                var written = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) {
                        throw InterruptedIOException("Checkpoint backup interrupted")
                    }
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    written += count
                    onProgress(written)
                }
            }
            val exitCode = process.waitFor()
            stderrThread.join(DIAGNOSTIC_JOIN_MS)
            check(exitCode == 0) {
                synchronized(stderr) {
                    stderr.lastOrNull { it.isNotBlank() }
                        ?.let { "GNU tar failed ($exitCode): $it" }
                        ?: "GNU tar failed with exit code $exitCode"
                }
            }
        } catch (error: Throwable) {
            process.destroy()
            if (!process.waitFor(PROCESS_STOP_GRACE_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
            }
            stderrThread.join(DIAGNOSTIC_JOIN_MS)
            throw error
        } finally {
            tarMount.delete()
            runtimeMount.delete()
        }
    }

    companion object {
        internal fun buildArguments(
            rootfsPath: String,
            tarExecutablePath: String,
            tarMountPath: String,
        ): List<String> =
            buildList {
                add("--link2symlink")
                add("--kill-on-exit")
                add("--root-id")
                add("--rootfs=$rootfsPath")
                addAndroidProotBindMounts()
                add("-b")
                add("$tarExecutablePath:$tarMountPath")
                add("--cwd=/")
                add(tarMountPath)
                add("--one-file-system")
                add("--numeric-owner")
                add("--exclude=${RootfsInstallationPipeline.READY_MARKER}")
                add("--exclude=${RootfsInstallationPipeline.INSTALLING_MARKER}")
                add("--exclude=$RUNTIME_MOUNT_DIRECTORY")
                add("--exclude=.udroid-extract")
                add("--exclude=*.l2s.*")
                ANDROID_PROOT_BIND_MOUNTS.forEach { path ->
                    add("--exclude=${path.removePrefix("/")}")
                    add("--exclude=${path.removePrefix("/")}/*")
                }
                listOf("run", "tmp", "mnt", "media").forEach { path ->
                    add("--exclude=$path/*")
                }
                add("-cpf")
                add("-")
                add(".")
            }

        private const val RUNTIME_MOUNT_DIRECTORY = ".udroid-backup"
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MAX_DIAGNOSTIC_LINES = 40
        private const val DIAGNOSTIC_JOIN_MS = 1_000L
        private const val PROCESS_STOP_GRACE_MS = 1_000L
    }
}

class RootfsCheckpointRestorer(
    private val context: Context,
    private val runtime: ProotRuntime,
) {
    fun restore(
        archive: File,
        rootfsName: String,
        operationId: String,
        onExtractionProgress: (completedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        onStage: (RootfsCheckpointStage, String) -> Unit = { _, _ -> },
    ) {
        require(archive.isFile) { "Selected rootfs backup is unavailable" }
        require(SAFE_NAME.matches(rootfsName)) { "Unsafe rootfs name: $rootfsName" }
        require(SAFE_OPERATION_ID.matches(operationId)) { "Unsafe checkpoint operation id" }
        val rootfsDirectory = File(context.filesDir, "rootfs")
        val paths = RootfsCheckpointSwap.paths(rootfsDirectory, rootfsName, operationId)
        RootfsCheckpointSwap.recover(paths)
        require(File(paths.current, RootfsInstallationPipeline.READY_MARKER).isFile) {
            "Linux system $rootfsName is no longer installed"
        }
        check(paths.staging.mkdir()) { "Could not create restore staging filesystem" }

        try {
            onStage(RootfsCheckpointStage.EXTRACTING, "Extracting the checkpoint")
            ProotTarExtractor(context, runtime).extract(
                archive = archive,
                destination = paths.staging,
                onProgress = onExtractionProgress,
            )
            onStage(RootfsCheckpointStage.CONFIGURING, "Applying current uDroid integration")
            AndroidRootfsConfigurator().configure(paths.staging)
            ProotRootfsHealthCheck(context, runtime).check(paths.staging)
            RootfsCheckpointReadyMarker.write(paths.staging, rootfsName, operationId)
            onStage(RootfsCheckpointStage.SWAPPING, "Activating the restored filesystem")
            RootfsCheckpointSwap.swapValidated(paths)
        } catch (error: Throwable) {
            runCatching { RootfsCheckpointSwap.recover(paths) }
                .onFailure { recovery -> error.addSuppressed(recovery) }
            throw error
        }
    }

    companion object {
        private val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
        private val SAFE_OPERATION_ID = Regex("[A-Za-z0-9-]{1,64}")
    }
}

internal data class RootfsCheckpointPaths(
    val current: File,
    val staging: File,
    val previous: File,
)

internal object RootfsCheckpointSwap {
    fun paths(
        rootfsDirectory: File,
        rootfsName: String,
        operationId: String,
    ): RootfsCheckpointPaths {
        require(SAFE_NAME.matches(rootfsName)) { "Unsafe rootfs name: $rootfsName" }
        require(SAFE_OPERATION_ID.matches(operationId)) { "Unsafe checkpoint operation id" }
        return RootfsCheckpointPaths(
            current = File(rootfsDirectory, rootfsName),
            staging = File(rootfsDirectory, "restore-staging-$operationId"),
            previous = File(rootfsDirectory, "restore-previous-$operationId"),
        )
    }

    fun swapValidated(paths: RootfsCheckpointPaths) {
        check(File(paths.current, RootfsInstallationPipeline.READY_MARKER).isFile) {
            "Current filesystem is not ready"
        }
        check(File(paths.staging, RootfsInstallationPipeline.READY_MARKER).isFile) {
            "Restored filesystem did not pass validation"
        }
        check(!paths.previous.exists()) { "Previous restore cleanup is incomplete" }
        check(paths.current.renameTo(paths.previous)) { "Could not preserve the current filesystem" }
        val previousMarker = File(paths.previous, RootfsInstallationPipeline.READY_MARKER)
        val rollbackMarker = File(paths.previous, ROLLBACK_MARKER)
        try {
            check(previousMarker.renameTo(rollbackMarker)) {
                "Could not prepare the current filesystem for rollback"
            }
            check(paths.staging.renameTo(paths.current)) {
                "Could not activate the restored filesystem"
            }
        } catch (error: Throwable) {
            rollbackMarker.renameTo(previousMarker)
            if (!paths.current.exists()) paths.previous.renameTo(paths.current)
            throw error
        }
        runCatching { deleteTree(paths.previous) }
    }

    fun recover(paths: RootfsCheckpointPaths) {
        if (paths.previous.exists()) {
            if (!paths.current.exists()) {
                if (File(paths.staging, RootfsInstallationPipeline.READY_MARKER).isFile) {
                    check(paths.staging.renameTo(paths.current)) {
                        "Could not finish activating the restored filesystem"
                    }
                } else {
                    val rollbackMarker = File(paths.previous, ROLLBACK_MARKER)
                    val readyMarker = File(paths.previous, RootfsInstallationPipeline.READY_MARKER)
                    if (rollbackMarker.isFile) {
                        check(rollbackMarker.renameTo(readyMarker)) {
                            "Could not recover the previous filesystem marker"
                        }
                    }
                    check(paths.previous.renameTo(paths.current)) {
                        "Could not recover the previous filesystem"
                    }
                }
            }
            if (paths.current.exists()) runCatching { deleteTree(paths.previous) }
        }
        if (paths.current.exists() && paths.staging.exists()) {
            runCatching { deleteTree(paths.staging) }
        }
    }

    private fun deleteTree(directory: File) {
        if (directory.exists()) {
            RootfsTreeDeleter.delete(requireNotNull(directory.parentFile).toPath(), directory.name)
        }
    }

    private const val ROLLBACK_MARKER = ".udroid-rollback-ready"
    private val SAFE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
    private val SAFE_OPERATION_ID = Regex("[A-Za-z0-9-]{1,64}")
}

private object RootfsCheckpointReadyMarker {
    fun write(
        rootfs: File,
        rootfsName: String,
        operationId: String,
    ) {
        val marker = File(rootfs, RootfsInstallationPipeline.READY_MARKER)
        FileOutputStream(marker).use { output ->
            output.write(
                buildString {
                    appendLine("format=1")
                    appendLine("name=$rootfsName")
                    appendLine("operation=$operationId")
                    appendLine("source=checkpoint")
                }.toByteArray(StandardCharsets.UTF_8),
            )
            output.fd.sync()
        }
    }
}
