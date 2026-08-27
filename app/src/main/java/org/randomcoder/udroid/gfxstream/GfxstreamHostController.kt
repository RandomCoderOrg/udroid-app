package org.randomcoder.udroid.gfxstream

import android.content.Context
import org.randomcoder.udroid.runtime.AndroidExecutableCommand
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class GfxstreamHostSnapshot(
    val state: String = "stopped",
    val detail: String = "gfxstream host is stopped",
)

data class GfxstreamHostSession(
    val guestRuntime: GfxstreamGuestRuntime,
    val gpuSocket: File,
)

internal object GfxstreamHostLaunch {
    fun arguments(
        gpuSocket: File,
        presenterSocket: File? = null,
    ): List<String> =
        buildList {
            add("--capset-names=gfxstream-vulkan")
            add("--gpu-socket-path=${gpuSocket.absolutePath}")
            presenterSocket?.let {
                add("--presenter-socket-path=${it.absolutePath}")
            }
        }

    fun environment(
        home: File,
        libraryDirectory: File,
        temporaryDirectory: File,
        is64Bit: Boolean,
    ): Map<String, String> =
        mapOf(
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "ANDROID_RUNTIME_ROOT" to "/apex/com.android.runtime",
            "ANDROID_TZDATA_ROOT" to "/apex/com.android.tzdata",
            "ANDROID_EMUGL_VERBOSE" to "1",
            "ANDROID_EMU_VK_LOADER_PATH" to
                if (is64Bit) "/system/lib64/libvulkan.so" else "/system/lib/libvulkan.so",
            "HOME" to home.absolutePath,
            "LD_LIBRARY_PATH" to libraryDirectory.absolutePath,
            "PATH" to "/system/bin",
            "TMPDIR" to temporaryDirectory.absolutePath,
        )
}

/** Owns one optional gfxstream host process for a selected runtime consumer. */
class GfxstreamHostController(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val process = AtomicReference<Process?>(null)
    private val guestRuntime = AtomicReference<GfxstreamGuestRuntime?>(null)
    private val snapshot = AtomicReference(GfxstreamHostSnapshot())
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val graphicsDirectory =
        File(appContext.noBackupFilesDir, "graphics").apply {
            check(mkdirs() || isDirectory) { "Could not prepare the private graphics directory" }
        }
    private val gpuSocket = File(graphicsDirectory, "kumquat-gpu.sock")
    private val logFile = File(graphicsDirectory, "kumquat.log")

    fun startAsync(presenterSocket: File? = null) {
        executor.execute {
            runCatching { start(presenterSocket) }
        }
    }

    /** Starts Kumquat and returns only after its private guest socket accepts launches. */
    fun start(presenterSocket: File? = null): GfxstreamHostSession {
        synchronized(lock) {
            check(!closed.get()) { "The gfxstream host controller is closed" }
            check(snapshot.get().state == "stopped") { "The gfxstream host is already active" }
            snapshot.set(GfxstreamHostSnapshot("starting", "installing the Android gfxstream host"))
        }
        return try {
            val guest = GfxstreamGuestRuntimeInstaller.install(appContext)
            val runtime = GfxstreamHostRuntimeInstaller.install(appContext)
            gpuSocket.delete()
            logFile.delete()
            val command =
                AndroidExecutableCommand.create(
                    runtime.executable,
                    *GfxstreamHostLaunch.arguments(gpuSocket, presenterSocket).toTypedArray(),
                )
            val launched =
                ProcessBuilder(command)
                    .directory(graphicsDirectory)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile)
                    .apply {
                        environment().clear()
                        environment().putAll(
                            GfxstreamHostLaunch.environment(
                                home = appContext.filesDir,
                                libraryDirectory = runtime.libraryDirectory,
                                temporaryDirectory = appContext.cacheDir,
                                is64Bit = android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty(),
                            ),
                        )
                    }.start()
            synchronized(lock) {
                check(!closed.get() && process.compareAndSet(null, launched)) {
                    launched.destroyForcibly()
                    "The gfxstream host was cancelled before startup completed"
                }
                guestRuntime.set(guest)
            }
            awaitGuestSocket(launched)
            synchronized(lock) {
                check(!closed.get() && process.get() === launched) {
                    "The gfxstream host was cancelled during startup"
                }
                snapshot.set(
                    GfxstreamHostSnapshot(
                        "running",
                        "Kumquat ${runtime.version} · guest ${guest.version} · " +
                            "socket ${gpuSocket.name}",
                    ),
                )
            }
            executor.execute { monitor(launched) }
            GfxstreamHostSession(guest, gpuSocket)
        } catch (error: Throwable) {
            process.getAndSet(null)?.let { owned ->
                if (owned.isAlive) owned.destroyForcibly()
            }
            guestRuntime.set(null)
            gpuSocket.delete()
            if (!closed.get()) {
                snapshot.set(
                    GfxstreamHostSnapshot(
                        "failed",
                        error.message ?: error.javaClass.simpleName,
                    ),
                )
            }
            throw error
        }
    }

    private fun awaitGuestSocket(launched: Process) {
        repeat(GUEST_SOCKET_ATTEMPTS) {
            if (gpuSocket.exists()) return
            check(launched.isAlive) {
                "Kumquat exited before publishing its guest socket · ${logFile.absolutePath}"
            }
            Thread.sleep(GUEST_SOCKET_POLL_MS)
        }
        error("Kumquat did not publish its guest socket · ${logFile.absolutePath}")
    }

    private fun monitor(launched: Process) {
        val exitCode =
            try {
                launched.waitFor()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        if (process.compareAndSet(launched, null)) {
            guestRuntime.set(null)
            gpuSocket.delete()
            snapshot.set(
                GfxstreamHostSnapshot(
                    if (exitCode == 0) "stopped" else "failed",
                    "Kumquat exited with $exitCode · ${logFile.absolutePath}",
                ),
            )
        }
    }

    fun current(): GfxstreamHostSnapshot = snapshot.get()

    fun currentGuestRuntime(): GfxstreamGuestRuntime? = guestRuntime.get()

    fun currentGpuSocket(): File? =
        gpuSocket.takeIf { snapshot.get().state == "running" && it.exists() }

    override fun close() {
        synchronized(lock) {
            closed.set(true)
            process.getAndSet(null)?.let { owned ->
                if (owned.isAlive) {
                    owned.destroy()
                    if (owned.isAlive) owned.destroyForcibly()
                }
            }
            guestRuntime.set(null)
            snapshot.set(GfxstreamHostSnapshot())
        }
        gpuSocket.delete()
        executor.shutdownNow()
    }

    private companion object {
        const val GUEST_SOCKET_ATTEMPTS = 250
        const val GUEST_SOCKET_POLL_MS = 20L
    }
}
