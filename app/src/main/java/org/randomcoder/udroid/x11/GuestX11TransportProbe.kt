package org.randomcoder.udroid.x11

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.randomcoder.udroid.BuildConfig
import org.randomcoder.udroid.install.ProotRuntime
import org.randomcoder.udroid.runtime.ANDROID_PROOT_BIND_MOUNTS
import org.randomcoder.udroid.runtime.AndroidExecutableCommand
import org.randomcoder.udroid.runtime.NativeProbeInstaller
import org.randomcoder.udroid.runtime.ProotPathContract
import org.randomcoder.udroid.runtime.ProotTerminalLaunchBuilder
import java.io.File
import java.util.concurrent.TimeUnit

private const val EACCES = 13

internal sealed interface GuestX11TransportResult {
    data class Ready(
        val protocolMajor: Int,
        val protocolMinor: Int,
    ) : GuestX11TransportResult

    data class Failed(
        val stage: String,
        val errno: Int?,
        val detail: String,
    ) : GuestX11TransportResult {
        val userMessage: String
            get() =
                if (errno == EACCES) {
                    "X11 guest transport denied: $detail"
                } else {
                    "X11 guest transport failed: $detail"
                }
    }
}

/** Verifies the display socket through the same PRoot alias used by Linux clients. */
internal class GuestX11TransportProbe(private val context: Context) {
    fun query(
        runtime: ProotRuntime,
        rootfs: File,
        endpoint: X11DisplayEndpoint,
    ): GuestX11TransportResult {
        val nativeProbe = NativeProbeInstaller.install(context)
        val arguments =
            GuestX11ProbeCommand.buildArguments(
                prootPath = runtime.executable.absolutePath,
                rootfsPath = ProotPathContract.rootfsPath(context, rootfs),
                socketDirectory = endpoint.socketDirectory.absolutePath,
                bindSocket = endpoint.requiresGuestBind,
                nativeProbe = nativeProbe.absolutePath,
                forceDenied = BuildConfig.X11_GUEST_PROBE_FAULT == "deny",
            )
        val temporaryDirectory =
            File(context.cacheDir, "proot").apply {
                check(mkdirs() || isDirectory) { "Could not prepare PRoot temporary storage" }
            }
        val environment =
            ProotTerminalLaunchBuilder
                .buildEnvironment(
                    androidHome = context.filesDir.absolutePath,
                    loaderPath = runtime.loader.absolutePath,
                    temporaryDirectory = temporaryDirectory.absolutePath,
                ).associate { value ->
                    val separator = value.indexOf('=')
                    value.substring(0, separator) to value.substring(separator + 1)
                }
        val command =
            AndroidExecutableCommand.create(
                runtime.executable,
                *arguments.drop(1).toTypedArray(),
            )
        val process =
            ProcessBuilder(command)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(environment)
                }.start()
        if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return GuestX11TransportResult.Failed(
                stage = "timeout",
                errno = null,
                detail = "guest handshake timed out",
            )
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return GuestX11ProbeOutput.parse(output)
    }

    private companion object {
        const val PROBE_TIMEOUT_SECONDS = 3L
    }
}

internal object GuestX11ProbeCommand {
    const val GUEST_SOCKET = "/tmp/.X11-unix/X0"
    const val GUEST_PROBE = "/tmp/.udroid-x11-probe"

    fun buildArguments(
        prootPath: String,
        rootfsPath: String,
        socketDirectory: String,
        bindSocket: Boolean,
        nativeProbe: String,
        forceDenied: Boolean,
        androidBindMounts: List<String> = ANDROID_PROOT_BIND_MOUNTS,
        systemLinkerPath: String = AndroidExecutableCommand.systemLinkerPath(),
    ): List<String> =
        buildList {
            add(prootPath)
            add("--link2symlink")
            add("--kill-on-exit")
            add("--root-id")
            add("--rootfs=$rootfsPath")
            androidBindMounts.forEach { path ->
                add("-b")
                add(path)
            }
            if (bindSocket) {
                add("-b")
                add("$socketDirectory:/tmp/.X11-unix")
            }
            add("-b")
            add("$nativeProbe:$GUEST_PROBE")
            add("--cwd=/")
            add(systemLinkerPath)
            add(GUEST_PROBE)
            add(if (forceDenied) "--x11-deny" else "--x11")
            add(GUEST_SOCKET)
        }
}

internal object GuestX11ProbeOutput {
    fun parse(output: String): GuestX11TransportResult {
        val record =
            output
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .mapNotNull { line ->
                    runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
                }.firstOrNull { value ->
                    value["event"]?.jsonPrimitive?.content == "x11_guest_probe"
                }
                ?: return GuestX11TransportResult.Failed(
                    stage = "probe_output",
                    errno = null,
                    detail = output.trim().takeIf(String::isNotEmpty) ?: "probe returned no result",
                )
        val status = record["status"]?.jsonPrimitive?.content.orEmpty()
        if (status == "ready") {
            return GuestX11TransportResult.Ready(
                protocolMajor = record["protocol_major"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                protocolMinor = record["protocol_minor"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            )
        }
        return GuestX11TransportResult.Failed(
            stage = status.ifBlank { "unknown" },
            errno = record["errno"]?.jsonPrimitive?.content?.toIntOrNull(),
            detail = record["detail"]?.jsonPrimitive?.content ?: status.ifBlank { "unknown failure" },
        )
    }
}
