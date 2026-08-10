package org.randomcoder.udroid.media

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import org.randomcoder.udroid.runtime.AndroidExecutableCommand
import org.randomcoder.udroid.runtime.EventJournal
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class MediaAccelerationSnapshot(
    val running: Boolean = false,
    val available: Boolean = false,
    val message: String = "Media acceleration is stopped",
)

class MediaAccelerationController(
    context: Context,
    private val journal: EventJournal,
    private val executor: ExecutorService,
) {
    private val appContext = context.applicationContext
    private val ownedProcess = AtomicReference<OwnedMediaProcess?>(null)
    private val lock = Any()

    fun current(): MediaAccelerationSnapshot {
        val owned = ownedProcess.get()
        return if (owned?.process?.isAlive == true && owned.socket.exists()) {
            MediaAccelerationSnapshot(
                running = true,
                available = true,
                message = "Android MediaCodec is available to Linux applications",
            )
        } else {
            MediaAccelerationSnapshot()
        }
    }

    fun endpoint(): MediaAccelerationEndpoint? {
        val owned = ownedProcess.get()
        return if (owned?.process?.isAlive == true && owned.socket.exists()) {
            owned.endpoint
        } else {
            null
        }
    }

    fun start(
        rootfs: File,
        bootId: String?,
    ): MediaAccelerationEndpoint? =
        synchronized(lock) {
            if (!MediaAccelerationRuntimeInstaller.supportsRootfs(rootfs)) {
                stopLocked(bootId)
                journal.append(
                    component = "media",
                    severity = "info",
                    event = "rootfs_unsupported",
                    message = "Media acceleration needs a glibc Linux system",
                    bootId = bootId,
                    fields = mapOf("rootfs" to rootfs.name),
                )
                return@synchronized null
            }
            endpoint()?.let { return@synchronized it }
            stopLocked(bootId)
            val startedAtNanos = System.nanoTime()

            val runtime = MediaAccelerationRuntimeInstaller.install(appContext)
            val transport =
                File(appContext.filesDir, "media/transport").apply {
                    check(mkdirs() || isDirectory) {
                        "Could not prepare the media transport directory"
                    }
                }
            val socket = File(transport, MediaAccelerationEndpoint.SOCKET_NAME)
            val pidFile = File(transport, PID_FILE_NAME)
            stopStaleProcess(pidFile, socket, bootId)
            socket.delete()
            val driver = File(transport, MediaAccelerationEndpoint.DRIVER_NAME)
            copyDriver(runtime.vaDriver, driver)
            val process =
                ProcessBuilder(
                    AndroidExecutableCommand.create(runtime.daemon, socket.absolutePath),
                ).directory(appContext.filesDir)
                    .redirectErrorStream(true)
                    .apply {
                        environment().clear()
                        environment().putAll(androidEnvironment(pidFile))
                    }.start()
            val endpoint = MediaAccelerationEndpoint(transport)
            val owned = OwnedMediaProcess(process, socket, pidFile, endpoint, startedAtNanos)
            check(ownedProcess.compareAndSet(null, owned)) {
                process.destroyForcibly()
                "Another media daemon won the ownership race"
            }
            try {
                awaitSocket(owned)
            } catch (error: Throwable) {
                ownedProcess.compareAndSet(owned, null)
                process.destroyForcibly()
                socket.delete()
                deleteOwnedPidFile(owned)
                throw error
            }
            monitor(owned, bootId)
            journal.append(
                component = "media",
                severity = "info",
                event = "server_started",
                message = "fake-media-accel started on an app-private Unix socket",
                bootId = bootId,
                fields =
                    mapOf(
                        "transport" to "unix_fd_passing",
                        "rootfs" to rootfs.name,
                        "startup_ms" to elapsedMillis(startedAtNanos),
                    ),
            )
            endpoint
        }

    fun stop(bootId: String?) {
        synchronized(lock) { stopLocked(bootId) }
    }

    private fun stopLocked(bootId: String?) {
        val owned = ownedProcess.getAndSet(null) ?: return
        if (owned.process.isAlive) {
            owned.process.destroy()
            if (!owned.process.waitFor(STOP_GRACE_MS, TimeUnit.MILLISECONDS)) {
                owned.process.destroyForcibly()
            }
        }
        owned.socket.delete()
        deleteOwnedPidFile(owned)
        journal.append(
            component = "media",
            severity = "info",
            event = "server_stopped",
            message = "fake-media-accel stopped",
            bootId = bootId,
            fields = mapOf("uptime_ms" to elapsedMillis(owned.startedAtNanos)),
        )
    }

    private fun awaitSocket(owned: OwnedMediaProcess) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(START_TIMEOUT_MS)
        while (System.nanoTime() < deadline) {
            check(owned.process.isAlive) { "fake-media-accel exited during startup" }
            // A Unix-domain socket exists in the filesystem but is not a regular file.
            if (owned.socket.exists()) return
            Thread.sleep(START_POLL_MS)
        }
        error("fake-media-accel did not publish its socket")
    }

    private fun monitor(
        owned: OwnedMediaProcess,
        bootId: String?,
    ) {
        executor.execute {
            var lines = 0
            runCatching {
                BufferedReader(InputStreamReader(owned.process.inputStream)).useLines { output ->
                    output.forEach { line ->
                        if (lines++ < MAX_LOG_LINES) {
                            journal.append(
                                component = "media",
                                severity = "debug",
                                event = "server_output",
                                message = line.take(MAX_LOG_CHARS),
                                bootId = bootId,
                            )
                        }
                    }
                }
            }
        }
        executor.execute {
            val exitCode = runCatching { owned.process.waitFor() }.getOrNull() ?: return@execute
            if (!ownedProcess.compareAndSet(owned, null)) return@execute
            owned.socket.delete()
            deleteOwnedPidFile(owned)
            journal.append(
                component = "media",
                severity = if (exitCode == 0) "info" else "error",
                event = "server_exited",
                message = "fake-media-accel exited with code $exitCode",
                bootId = bootId,
                fields =
                    mapOf(
                        "exit_code" to exitCode,
                        "uptime_ms" to elapsedMillis(owned.startedAtNanos),
                    ),
            )
        }
    }

    private fun copyDriver(
        source: File,
        destination: File,
    ) {
        if (
            destination.isFile &&
            destination.length() == source.length() &&
            MessageDigest.isEqual(sha256(source), sha256(destination))
        ) {
            return
        }
        val staging = File(destination.parentFile, "${destination.name}.staging")
        staging.delete()
        source.inputStream().use { input ->
            FileOutputStream(staging).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        check(staging.setReadable(true, true)) { "Could not make the VA driver readable" }
        if (destination.exists()) check(destination.delete()) { "Could not replace the VA driver" }
        check(staging.renameTo(destination)) { "Could not activate the VA driver" }
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun stopStaleProcess(
        pidFile: File,
        socket: File,
        bootId: String?,
    ) {
        val pid =
            runCatching { pidFile.readText().trim().toLong() }
                .getOrNull()
                ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
                ?: run {
                    pidFile.delete()
                    return
                }
        val commandLine =
            runCatching { File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8) }
                .getOrDefault("")
        val ownedDaemon =
            commandLine.contains("fake-media-acceld") &&
                commandLine.contains(socket.absolutePath)
        if (!ownedDaemon) {
            pidFile.delete()
            return
        }
        signal(pid.toInt(), OsConstants.SIGTERM)
        for (attempt in 0 until STALE_STOP_ATTEMPTS) {
            if (!File("/proc/$pid").exists()) break
            Thread.sleep(STALE_STOP_POLL_MS)
        }
        if (File("/proc/$pid").exists()) signal(pid.toInt(), OsConstants.SIGKILL)
        pidFile.delete()
        journal.append(
            component = "media",
            severity = "warning",
            event = "stale_server_stopped",
            message = "Stopped an orphaned fake-media-accel process",
            bootId = bootId,
            fields = mapOf("pid" to pid),
        )
    }

    private fun signal(
        pid: Int,
        signal: Int,
    ) {
        try {
            Os.kill(pid, signal)
        } catch (_: ErrnoException) {
            // The process may have exited between the /proc check and signal.
        }
    }

    private fun deleteOwnedPidFile(owned: OwnedMediaProcess) {
        owned.pidFile.delete()
    }

    private fun androidEnvironment(pidFile: File): Map<String, String> =
        mapOf(
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "ANDROID_RUNTIME_ROOT" to "/apex/com.android.runtime",
            "ANDROID_TZDATA_ROOT" to "/apex/com.android.tzdata",
            "HOME" to appContext.filesDir.absolutePath,
            "PATH" to "/system/bin",
            "FMA_PARENT_DEATH_SIGNAL" to "1",
            "FMA_PID_FILE" to pidFile.absolutePath,
            "TMPDIR" to appContext.cacheDir.absolutePath,
        )

    private fun elapsedMillis(startedAtNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)

    private data class OwnedMediaProcess(
        val process: Process,
        val socket: File,
        val pidFile: File,
        val endpoint: MediaAccelerationEndpoint,
        val startedAtNanos: Long,
    )

    private companion object {
        const val START_TIMEOUT_MS = 1_000L
        const val START_POLL_MS = 10L
        const val STOP_GRACE_MS = 500L
        const val MAX_LOG_LINES = 200
        const val MAX_LOG_CHARS = 2_000
        const val PID_FILE_NAME = "fake-media-accel.pid"
        const val STALE_STOP_ATTEMPTS = 25
        const val STALE_STOP_POLL_MS = 20L
    }
}
