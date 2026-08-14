package org.randomcoder.udroid.x11

import android.content.Context
import android.os.SystemClock
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
        val addressBytes: Int? = null,
        val elapsedMs: Long? = null,
    ) : GuestX11TransportResult

    data class Failed(
        val stage: String,
        val errno: Int?,
        val detail: String,
        val addressBytes: Int? = null,
        val elapsedMs: Long? = null,
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

internal data class GuestX11ProbeReport(
    val abstractSocket: GuestX11TransportResult,
    val filesystemSocket: GuestX11TransportResult,
    val client: GuestX11ClientProbeResult,
)

internal data class GuestX11ClientProbeResult(
    val status: String,
    val exitCode: Int?,
    val elapsedMs: Long,
    val fields: Map<String, String>,
    val output: String,
)

internal enum class GuestX11SocketNamespace {
    ABSTRACT,
    FILESYSTEM,
}

/** Verifies the display socket through the same PRoot alias used by Linux clients. */
internal class GuestX11TransportProbe(private val context: Context) {
    fun query(
        runtime: ProotRuntime,
        rootfs: File,
        endpoint: X11DisplayEndpoint,
    ): GuestX11ProbeReport {
        val nativeProbe = NativeProbeInstaller.install(context)
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
        fun startProbe(namespace: GuestX11SocketNamespace): Process {
            val arguments =
                GuestX11ProbeCommand.buildArguments(
                    prootPath = runtime.executable.absolutePath,
                    rootfsPath = ProotPathContract.rootfsPath(context, rootfs),
                    socketDirectory = endpoint.socketDirectory.absolutePath,
                    bindSocket = endpoint.requiresGuestBind,
                    nativeProbe = nativeProbe.absolutePath,
                    forceDenied =
                        BuildConfig.X11_GUEST_PROBE_FAULT == "deny" &&
                            namespace == GuestX11SocketNamespace.FILESYSTEM,
                    socketNamespace = namespace,
                )
            val command =
                AndroidExecutableCommand.create(
                    runtime.executable,
                    *arguments.drop(1).toTypedArray(),
                )
            return ProcessBuilder(command)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(environment)
                }.start()
        }
        fun awaitProbe(
            process: Process,
            namespace: GuestX11SocketNamespace,
        ): GuestX11TransportResult {
            if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return GuestX11TransportResult.Failed(
                    stage = "timeout",
                    errno = null,
                    detail = "guest handshake timed out",
                )
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }
            return GuestX11ProbeOutput.parse(output, namespace)
        }

        // Start both at once so a blocked abstract connect cannot delay or mask the
        // filesystem transport that actually gates desktop startup.
        val abstractProcess = startProbe(GuestX11SocketNamespace.ABSTRACT)
        val filesystemProcess = startProbe(GuestX11SocketNamespace.FILESYSTEM)
        return GuestX11ProbeReport(
            abstractSocket =
                awaitProbe(
                    abstractProcess,
                    GuestX11SocketNamespace.ABSTRACT,
                ),
            filesystemSocket =
                awaitProbe(
                    filesystemProcess,
                    GuestX11SocketNamespace.FILESYSTEM,
                ),
            client = queryGuestClient(runtime, rootfs, endpoint, environment),
        )
    }

    private fun queryGuestClient(
        runtime: ProotRuntime,
        rootfs: File,
        endpoint: X11DisplayEndpoint,
        hostEnvironment: Map<String, String>,
    ): GuestX11ClientProbeResult {
        val guestHome = if (File(rootfs, "root").isDirectory) "/root" else "/"
        val arguments =
            GuestX11ClientProbeCommand.buildArguments(
                prootPath = runtime.executable.absolutePath,
                rootfsPath = ProotPathContract.rootfsPath(context, rootfs),
                socketDirectory = endpoint.socketDirectory.absolutePath,
                bindSocket = endpoint.requiresGuestBind,
                guestHome = guestHome,
            )
        val command =
            AndroidExecutableCommand.create(
                runtime.executable,
                *arguments.drop(1).toTypedArray(),
            )
        val startedMs = SystemClock.elapsedRealtime()
        val process =
            ProcessBuilder(command)
                .directory(context.filesDir)
                .redirectErrorStream(true)
                .apply {
                    environment().clear()
                    environment().putAll(hostEnvironment)
                }.start()
        if (!process.waitFor(CLIENT_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return GuestX11ClientProbeResult(
                status = "timeout",
                exitCode = null,
                elapsedMs = SystemClock.elapsedRealtime() - startedMs,
                fields = emptyMap(),
                output = "guest xrdb probe timed out",
            )
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val fields = GuestX11ClientProbeOutput.parseFields(output)
        val boundedOutput =
            fields["xrdb_output"]
                ?: output
                    .lineSequence()
                    .joinToString(" ")
                    .trim()
                    .take(CLIENT_PROBE_OUTPUT_CHARS)
        return GuestX11ClientProbeResult(
            status = fields["xrdb_status"] ?: "probe_output_missing",
            exitCode = process.exitValue(),
            elapsedMs = SystemClock.elapsedRealtime() - startedMs,
            fields = fields,
            output = boundedOutput,
        )
    }

    private companion object {
        const val PROBE_TIMEOUT_SECONDS = 3L
        const val CLIENT_PROBE_TIMEOUT_SECONDS = 8L
        const val CLIENT_PROBE_OUTPUT_CHARS = 1_024
    }
}

internal object GuestX11ClientProbeCommand {
    private const val RECORD_PREFIX = "UDROID_X11|"

    fun buildArguments(
        prootPath: String,
        rootfsPath: String,
        socketDirectory: String,
        bindSocket: Boolean,
        guestHome: String,
        androidBindMounts: List<String> = ANDROID_PROOT_BIND_MOUNTS,
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
            add("--cwd=/")
            add("/usr/bin/env")
            add("-i")
            add("HOME=$guestHome")
            add("USER=root")
            add("LOGNAME=root")
            add("SHELL=/bin/sh")
            add("LANG=C.UTF-8")
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            add("DISPLAY=:0")
            add("/bin/sh")
            add("-c")
            add(probeScript())
        }

    private fun probeScript(): String =
        """
        out=/tmp/.udroid-xrdb-probe.${'$'}${'$'}
        trap 'rm -f "${'$'}out" "${'$'}out".*' EXIT HUP INT TERM
        record() { printf '${RECORD_PREFIX}%s=%s\n' "${'$'}1" "${'$'}2"; }
        record client_display "${'$'}DISPLAY"
        record selinux_context "${'$'}(cat /proc/self/attr/current 2>/dev/null | tr '\n\r' '  ' | head -c 192)"
        record selinux_enforcing "${'$'}(cat /sys/fs/selinux/enforce 2>/dev/null | head -c 8)"
        record mount_namespace "${'$'}(readlink /proc/self/ns/mnt 2>/dev/null | head -c 96)"
        record process_security "${'$'}(grep -E '^(NoNewPrivs|Seccomp):' /proc/self/status 2>/dev/null | tr '\n\r' ',,' | head -c 96)"
        record socket_stat "${'$'}(stat -Lc '%F mode=%a uid=%u gid=%g size=%s' /tmp/.X11-unix/X0 2>&1 | tr '\n\r' '  ' | head -c 256)"
        record socket_directory_stat "${'$'}(stat -Lc '%F mode=%a uid=%u gid=%g' /tmp/.X11-unix 2>&1 | tr '\n\r' '  ' | head -c 256)"
        record xrdb_path "${'$'}(command -v xrdb 2>/dev/null | head -c 256)"
        if command -v dpkg-query >/dev/null 2>&1; then
            record libxcb_package "${'$'}(dpkg-query -W libxcb1 2>&1 | tr '\n\r\t' '   ' | head -c 256)"
        elif command -v apk >/dev/null 2>&1; then
            record libxcb_package "${'$'}(apk info -v libxcb 2>&1 | tr '\n\r' '  ' | head -c 256)"
        else
            record libxcb_package unavailable
        fi
        if command -v xrdb >/dev/null 2>&1; then
            run_xrdb() {
                label="${'$'}1"
                target="${'$'}2"
                probe_out="${'$'}out.${'$'}label"
                if command -v timeout >/dev/null 2>&1; then
                    timeout 2 xrdb -display "${'$'}target" -query >"${'$'}probe_out" 2>&1
                else
                    xrdb -display "${'$'}target" -query >"${'$'}probe_out" 2>&1
                fi
                rc=${'$'}?
                record "${'$'}{label}_display" "${'$'}target"
                record "${'$'}{label}_exit" "${'$'}rc"
                if [ "${'$'}rc" -eq 0 ]; then
                    record "${'$'}{label}_status" ready
                    record "${'$'}{label}_output" ''
                else
                    record "${'$'}{label}_status" failed
                    record "${'$'}{label}_output" "${'$'}(tr '\n\r' '  ' <"${'$'}probe_out" | head -c 512)"
                fi
            }
            run_xrdb xrdb ':0'
            run_xrdb xrdb_unix 'unix/:0'
            run_xrdb xrdb_path '/tmp/.X11-unix/X0'
        else
            record xrdb_exit 127
            record xrdb_status unavailable
            record xrdb_output 'xrdb is not installed'
        fi
        """.trimIndent()
}

internal object GuestX11ClientProbeOutput {
    private const val RECORD_PREFIX = "UDROID_X11|"

    fun parseFields(output: String): Map<String, String> =
        output
            .lineSequence()
            .filter { it.startsWith(RECORD_PREFIX) }
            .mapNotNull { line ->
                val value = line.removePrefix(RECORD_PREFIX)
                val separator = value.indexOf('=')
                if (separator <= 0) null else value.substring(0, separator) to value.substring(separator + 1)
            }.toMap()
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
        socketNamespace: GuestX11SocketNamespace = GuestX11SocketNamespace.FILESYSTEM,
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
            add(
                when {
                    forceDenied -> "--x11-deny"
                    socketNamespace == GuestX11SocketNamespace.ABSTRACT -> "--x11-abstract"
                    else -> "--x11"
                },
            )
            add(GUEST_SOCKET)
        }
}

internal object GuestX11ProbeOutput {
    fun parse(
        output: String,
        namespace: GuestX11SocketNamespace,
    ): GuestX11TransportResult {
        val namespaceValue = namespace.name.lowercase()
        val record =
            output
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .mapNotNull { line ->
                    runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
                }.firstOrNull { value ->
                    value["event"]?.jsonPrimitive?.content == "x11_guest_probe" &&
                        value["socket_namespace"]?.jsonPrimitive?.content == namespaceValue
                }
        val fallbackDetail = output.trim().takeIf(String::isNotEmpty) ?: "probe returned no result"
        if (record == null) {
            return GuestX11TransportResult.Failed(
                stage = "probe_output",
                errno = null,
                detail = "$namespaceValue probe returned no result: $fallbackDetail",
            )
        }
        return parseRecord(record)
    }

    private fun parseRecord(record: kotlinx.serialization.json.JsonObject): GuestX11TransportResult {
        val status = record["status"]?.jsonPrimitive?.content.orEmpty()
        if (status == "ready") {
            return GuestX11TransportResult.Ready(
                protocolMajor = record["protocol_major"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                protocolMinor = record["protocol_minor"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                addressBytes = record["address_bytes"]?.jsonPrimitive?.content?.toIntOrNull(),
                elapsedMs = record["elapsed_ms"]?.jsonPrimitive?.content?.toLongOrNull(),
            )
        }
        return GuestX11TransportResult.Failed(
            stage = status.ifBlank { "unknown" },
            errno = record["errno"]?.jsonPrimitive?.content?.toIntOrNull(),
            detail = record["detail"]?.jsonPrimitive?.content ?: status.ifBlank { "unknown failure" },
            addressBytes = record["address_bytes"]?.jsonPrimitive?.content?.toIntOrNull(),
            elapsedMs = record["elapsed_ms"]?.jsonPrimitive?.content?.toLongOrNull(),
        )
    }
}
