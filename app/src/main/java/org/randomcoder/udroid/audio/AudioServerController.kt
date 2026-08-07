package org.randomcoder.udroid.audio

import android.content.Context
import org.randomcoder.udroid.runtime.AndroidExecutableCommand
import org.randomcoder.udroid.runtime.EventJournal
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicReference

data class AudioEndpoint(
    val hostAuthDirectory: File,
) {
    val guestAuthDirectory: String = GUEST_AUTH_DIRECTORY
    val guestServer: String = GUEST_SERVER
    val guestCookie: String = "$GUEST_AUTH_DIRECTORY/$COOKIE_NAME"

    companion object {
        const val GUEST_AUTH_DIRECTORY = "/tmp/.udroid-pulse"
        const val GUEST_SERVER = "tcp:127.0.0.1:4713"
        const val COOKIE_NAME = "cookie"
        const val PORT = 4713
    }
}

data class AudioSessionSnapshot(
    val running: Boolean = false,
    val outputEnabled: Boolean = false,
    val microphoneEnabled: Boolean = false,
    val message: String = "Audio is stopped",
)

class AudioServerController(
    context: Context,
    private val journal: EventJournal,
    private val executor: ExecutorService,
) {
    private val appContext = context.applicationContext
    private val ownedProcess = AtomicReference<OwnedAudioProcess?>(null)
    private val lock = Any()
    private val authDirectory =
        File(appContext.filesDir, "audio/transport").apply {
            check(mkdirs() || isDirectory) { "Could not prepare PulseAudio authentication" }
        }
    private val stateDirectory =
        File(appContext.filesDir, "audio/state").apply {
            check(mkdirs() || isDirectory) { "Could not prepare PulseAudio state" }
        }

    fun endpoint(): AudioEndpoint = AudioEndpoint(authDirectory)

    fun current(): AudioSessionSnapshot {
        val owned = ownedProcess.get()
        return if (owned?.process?.isAlive == true) {
            AudioSessionSnapshot(
                running = true,
                outputEnabled = owned.configuration.outputEnabled,
                microphoneEnabled = owned.configuration.microphoneEnabled,
                message =
                    when {
                        owned.configuration.microphoneEnabled ->
                            "Speaker and microphone are connected"
                        owned.configuration.outputEnabled -> "Speaker is connected"
                        else -> "Audio bridge is connected"
                    },
            )
        } else {
            AudioSessionSnapshot()
        }
    }

    fun apply(
        configuration: AudioConfiguration,
        bootId: String?,
    ): AudioSessionSnapshot =
        synchronized(lock) {
            val current = ownedProcess.get()
            if (current?.process?.isAlive == true && current.configuration == configuration) {
                return@synchronized this.current()
            }
            stopLocked(bootId)
            if (!configuration.outputEnabled && !configuration.microphoneEnabled) {
                return@synchronized AudioSessionSnapshot(message = "Audio is disabled")
            }

            val runtime = AudioRuntimeInstaller.install(appContext)
            val cookie = File(authDirectory, AudioEndpoint.COOKIE_NAME)
            val configurationFile =
                File(appContext.cacheDir, "audio/default.pa").apply {
                    parentFile?.mkdirs()
                    writeText(PulseAudioConfiguration.render(configuration, cookie))
                }
            val command =
                AndroidExecutableCommand.create(
                    runtime.executable,
                    "--daemonize=no",
                    "--fail=yes",
                    "--exit-idle-time=-1",
                    "--disable-shm=yes",
                    "--log-target=stderr",
                    "--log-level=notice",
                    "--dl-search-path=${runtime.moduleDirectory.absolutePath}",
                    "--file=${configurationFile.absolutePath}",
                )
            val process =
                ProcessBuilder(command)
                    .directory(appContext.filesDir)
                    .redirectErrorStream(true)
                    .apply {
                        environment().clear()
                        environment().putAll(
                            mapOf(
                                "ANDROID_DATA" to "/data",
                                "ANDROID_ROOT" to "/system",
                                "ANDROID_RUNTIME_ROOT" to "/apex/com.android.runtime",
                                "ANDROID_TZDATA_ROOT" to "/apex/com.android.tzdata",
                                "HOME" to appContext.filesDir.absolutePath,
                                "LD_LIBRARY_PATH" to runtime.libraryDirectory.absolutePath,
                                "PATH" to "/system/bin",
                                "PULSE_RUNTIME_PATH" to authDirectory.absolutePath,
                                "PULSE_STATE_PATH" to stateDirectory.absolutePath,
                                "TMPDIR" to appContext.cacheDir.absolutePath,
                            ),
                        )
                    }.start()
            val owned = OwnedAudioProcess(process, configuration)
            check(ownedProcess.compareAndSet(null, owned)) {
                process.destroyForcibly()
                "Another audio server won the ownership race"
            }
            monitor(owned, bootId)
            journal.append(
                component = "audio",
                severity = "info",
                event = "server_started",
                message = "PulseAudio started on authenticated loopback transport",
                bootId = bootId,
                fields =
                    mapOf(
                        "output" to configuration.outputEnabled,
                        "microphone" to configuration.microphoneEnabled,
                        "transport" to "tcp_loopback",
                    ),
            )
            current()
        }

    fun stop(bootId: String?) {
        synchronized(lock) { stopLocked(bootId) }
    }

    private fun stopLocked(bootId: String?) {
        val owned = ownedProcess.getAndSet(null) ?: return
        if (owned.process.isAlive) {
            owned.process.destroy()
            if (owned.process.isAlive) owned.process.destroyForcibly()
        }
        journal.append(
            component = "audio",
            severity = "info",
            event = "server_stopped",
            message = "PulseAudio stopped",
            bootId = bootId,
        )
    }

    private fun monitor(
        owned: OwnedAudioProcess,
        bootId: String?,
    ) {
        executor.execute {
            var lines = 0
            runCatching {
                BufferedReader(InputStreamReader(owned.process.inputStream)).useLines { output ->
                    output.forEach { line ->
                        if (lines++ < MAX_LOG_LINES) {
                            journal.append(
                                component = "audio",
                                severity = "debug",
                                event = "server_output",
                                message = line.take(MAX_LOG_CHARS),
                                bootId = bootId,
                            )
                        }
                    }
                }
            }.onFailure { error ->
                // Closing the process stream interrupts a blocking reader during
                // normal stop. Report only reads that fail while we still own a
                // live server.
                if (ownedProcess.get() === owned && owned.process.isAlive) {
                    journal.append(
                        component = "audio",
                        severity = "warning",
                        event = "server_log_read_failed",
                        message = error.message ?: "PulseAudio output could not be read",
                        bootId = bootId,
                        fields = mapOf("exception" to error.javaClass.name),
                    )
                }
            }
        }
        executor.execute {
            val exitCode = runCatching { owned.process.waitFor() }.getOrNull() ?: return@execute
            if (!ownedProcess.compareAndSet(owned, null)) return@execute
            journal.append(
                component = "audio",
                severity = if (exitCode == 0) "info" else "error",
                event = "server_exited",
                message = "PulseAudio exited with code $exitCode",
                bootId = bootId,
                fields = mapOf("exit_code" to exitCode),
            )
        }
    }

    private data class OwnedAudioProcess(
        val process: Process,
        val configuration: AudioConfiguration,
    )

    private companion object {
        const val MAX_LOG_LINES = 200
        const val MAX_LOG_CHARS = 2_000
    }
}

internal object PulseAudioConfiguration {
    fun render(
        configuration: AudioConfiguration,
        cookie: File,
    ): String =
        buildList {
            add(".fail")
            if (configuration.outputEnabled) {
                add("load-module module-sles-sink sink_name=udroid_output")
            }
            if (configuration.microphoneEnabled) {
                add("load-module module-sles-source source_name=udroid_input")
            }
            add(
                "load-module module-native-protocol-tcp " +
                    "listen=127.0.0.1 port=${AudioEndpoint.PORT} " +
                    "auth-cookie=${quotePulseArgument(cookie.absolutePath)} " +
                    "auth-cookie-enabled=1",
            )
        }.joinToString(separator = "\n", postfix = "\n")

    private fun quotePulseArgument(value: String): String {
        require('\n' !in value && '\r' !in value) { "Unsafe PulseAudio path" }
        return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }
}
