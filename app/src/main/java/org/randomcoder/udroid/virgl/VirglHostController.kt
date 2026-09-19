package org.randomcoder.udroid.virgl

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.randomcoder.udroid.runtime.AndroidExecutableCommand
import org.randomcoder.udroid.runtime.DesktopGraphicsProfile

internal enum class VirglHostBackend(
    val label: String,
    val socketName: String,
    val logName: String,
) {
    NATIVE_GLES("Android OpenGL ES", "virgl-gles.sock", "virgl-gles.log"),
    ANGLE_VULKAN("ANGLE Vulkan", "virgl-angle-vulkan.sock", "virgl-angle-vulkan.log"),
    ;

    companion object {
        fun from(profile: DesktopGraphicsProfile): VirglHostBackend? =
            when (profile) {
                DesktopGraphicsProfile.VIRGL -> NATIVE_GLES
                DesktopGraphicsProfile.VIRGL_ANGLE -> ANGLE_VULKAN
                else -> null
            }
    }
}

internal data class VirglHostSnapshot(
    val state: String = "stopped",
    val detail: String = "VirGL host is stopped",
)

internal object VirglHostLaunch {
    fun arguments(
        socket: File,
        backend: VirglHostBackend,
    ): List<String> =
        buildList {
            add("--no-fork")
            add("--multi-clients")
            if (backend == VirglHostBackend.ANGLE_VULKAN) add("--angle-vulkan")
            add("--socket-path")
            add(socket.absolutePath)
        }

    fun environment(
        home: File,
        libraryDirectory: File,
        temporaryDirectory: File,
        angleLibraryDirectory: File? = null,
    ): Map<String, String> =
        mapOf(
            "ANDROID_DATA" to "/data",
            "ANDROID_ROOT" to "/system",
            "ANDROID_RUNTIME_ROOT" to "/apex/com.android.runtime",
            "ANDROID_TZDATA_ROOT" to "/apex/com.android.tzdata",
            "HOME" to home.absolutePath,
            "LD_LIBRARY_PATH" to libraryDirectory.absolutePath,
            "PATH" to "/system/bin",
            "TMPDIR" to temporaryDirectory.absolutePath,
        ) +
            if (angleLibraryDirectory == null) {
                emptyMap()
            } else {
                mapOf("VTEST_ANGLE_LIBRARY_PATH" to angleLibraryDirectory.absolutePath)
            }
}

/** Owns one backend-specific VirGL server shared by graphical processes. */
internal class VirglHostController(
    context: Context,
    val backend: VirglHostBackend,
    private val onUnexpectedExit: (VirglHostSnapshot) -> Unit = {},
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val process = AtomicReference<Process?>(null)
    private val snapshot = AtomicReference(VirglHostSnapshot())
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val graphicsDirectory =
        File(appContext.noBackupFilesDir, "graphics").apply {
            check(mkdirs() || isDirectory) { "Could not prepare the private graphics directory" }
        }
    private val socket = File(graphicsDirectory, backend.socketName)
    private val logFile = File(graphicsDirectory, backend.logName)

    fun start(): File {
        synchronized(lock) {
            check(!closed.get()) { "The VirGL host controller is closed" }
            currentSocket()?.let { return it }
            check(process.get() == null) { "The VirGL host is already starting" }
            snapshot.set(VirglHostSnapshot("starting", "installing the VirGL host"))
        }
        return try {
            val runtime = VirglHostRuntimeInstaller.install(appContext)
            val angleRuntime =
                if (backend == VirglHostBackend.ANGLE_VULKAN) {
                    VirglAngleRuntimeInstaller.install(appContext)
                } else {
                    null
                }
            socket.delete()
            logFile.delete()
            val launched =
                ProcessBuilder(
                    AndroidExecutableCommand.create(
                        runtime.executable,
                        *VirglHostLaunch.arguments(socket, backend).toTypedArray(),
                    ),
                ).directory(graphicsDirectory)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile)
                    .apply {
                        environment().clear()
                        environment().putAll(
                            VirglHostLaunch.environment(
                                appContext.filesDir,
                                runtime.libraryDirectory,
                                appContext.cacheDir,
                                angleRuntime?.libraryDirectory,
                            ),
                        )
                    }.start()
            synchronized(lock) {
                check(!closed.get() && process.compareAndSet(null, launched)) {
                    launched.destroyForcibly()
                    "The VirGL host was cancelled before startup completed"
                }
            }
            awaitSocket(launched)
            snapshot.set(
                VirglHostSnapshot(
                    "running",
                    "VirGL ${VirglHostRuntimeInstaller.VERSION} · ${backend.label} · " +
                        "socket ${socket.name}",
                ),
            )
            Log.i(LOG_TAG, "VirGL host ready; output: ${logFile.absolutePath}")
            executor.execute { monitor(launched) }
            socket
        } catch (error: Throwable) {
            process.getAndSet(null)?.let { if (it.isAlive) it.destroyForcibly() }
            socket.delete()
            if (!closed.get()) {
                snapshot.set(VirglHostSnapshot("failed", error.message ?: "VirGL failed"))
            }
            throw error
        }
    }

    fun currentSocket(): File? =
        socket.takeIf { snapshot.get().state == "running" && it.exists() }

    fun current(): VirglHostSnapshot = snapshot.get()

    private fun awaitSocket(launched: Process) {
        repeat(SOCKET_ATTEMPTS) {
            if (socket.exists()) return
            check(launched.isAlive) {
                "VirGL exited before publishing its socket · ${logFile.absolutePath}"
            }
            Thread.sleep(SOCKET_POLL_MS)
        }
        error("VirGL did not publish its socket · ${logFile.absolutePath}")
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
            socket.delete()
            val result =
                VirglHostSnapshot(
                    if (exitCode == 0) "stopped" else "failed",
                    "VirGL exited with $exitCode · ${logFile.absolutePath}",
                )
            snapshot.set(result)
            Log.w(LOG_TAG, result.detail)
            if (!closed.get()) onUnexpectedExit(result)
        }
    }

    override fun close() {
        synchronized(lock) {
            closed.set(true)
            process.getAndSet(null)?.let {
                if (it.isAlive) {
                    it.destroy()
                    if (it.isAlive) it.destroyForcibly()
                }
            }
            snapshot.set(VirglHostSnapshot())
        }
        socket.delete()
        executor.shutdownNow()
    }

    private companion object {
        const val LOG_TAG = "uDroid-VirGL"
        const val SOCKET_ATTEMPTS = 250
        const val SOCKET_POLL_MS = 20L
    }
}
