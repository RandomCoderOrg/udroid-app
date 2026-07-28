package org.randomcoder.udroid.install

import android.content.Context
import android.os.Build
import android.os.StatFs
import org.randomcoder.udroid.runtime.AndroidExecutableCommand
import org.randomcoder.udroid.runtime.ProotPathContract
import org.randomcoder.udroid.runtime.addAndroidProotBindMounts
import org.tukaani.xz.XZInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.math.max

data class ProotRuntime(
    val executable: File,
    val loader: File,
    val tarExecutable: File,
)

object ProotRuntimeInstaller {
    private const val PROOT_VERSION = "5.1.107.86-1"
    private const val TAR_VERSION = "1.35-1"
    private val supportedAbis = setOf("arm64-v8a", "armeabi-v7a", "x86_64")

    fun install(context: Context): ProotRuntime {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in supportedAbis }
        checkNotNull(abi) {
            "No packaged PRoot supports ${Build.SUPPORTED_ABIS.joinToString()}"
        }
        val runtimeDirectory =
            File(context.filesDir, "runtime/proot-$PROOT_VERSION-$abi").apply {
                mkdirs()
        }
        val destination = File(runtimeDirectory, "proot")
        val tarDestination = File(runtimeDirectory, "tar-$TAR_VERSION")
        val loader = File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so")
        check(loader.isFile && loader.canExecute()) {
            "Packaged PRoot loader is unavailable"
        }
        installExecutable(context, "runtime/$abi/proot", destination, "PRoot")
        installExecutable(context, "runtime/$abi/tar", tarDestination, "GNU tar")
        return ProotRuntime(destination, loader, tarDestination)
    }

    private fun installExecutable(
        context: Context,
        assetPath: String,
        destination: File,
        label: String,
    ) {
        if (destination.isFile && destination.canExecute()) return
        val staging = File(destination.parentFile, "${destination.name}.staging")
        staging.delete()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(staging).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        check(staging.setReadable(true, true)) { "Could not make $label readable" }
        check(staging.setWritable(true, true)) { "Could not make $label writable" }
        check(staging.setExecutable(true, true)) { "Could not make $label executable" }
        if (destination.exists()) check(destination.delete()) {
            "Could not replace the previous $label runtime"
        }
        check(staging.renameTo(destination)) { "Could not atomically activate $label" }
    }
}

class ProotTarExtractor(
    private val context: Context,
    private val runtime: ProotRuntime,
    private val stripComponents: Int = 0,
    private val excludeOciWhiteouts: Boolean = false,
    private val onDiagnostic: (String) -> Unit = {},
) : RootfsExtractor {
    override fun extract(
        archive: File,
        destination: File,
        onProgress: (completedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        val xzCompressed = archive.name.endsWith(".tar.xz", ignoreCase = true)
        val gzipCompressed = archive.name.endsWith(".tar.gz", ignoreCase = true)
        check(!archive.name.endsWith(".tar.zst", ignoreCase = true)) {
            "Zstandard rootfs archives are not supported yet"
        }
        check(xzCompressed || gzipCompressed || archive.name.endsWith(".tar", ignoreCase = true)) {
            "Unsupported rootfs archive: ${archive.name}"
        }
        require(stripComponents in 0..4) {
            "Unsafe archive strip depth: $stripComponents"
        }
        check(File(destination, "linkerconfig").mkdirs()) {
            "Could not prepare Android linker configuration mount"
        }
        val runtimeMount =
            File(destination, RUNTIME_MOUNT_DIRECTORY).apply {
                check(mkdirs() || isDirectory) {
                    "Could not prepare the rootfs extraction runtime mount"
                }
            }
        val tarMount = File(runtimeMount, "tar").apply {
            if (!exists()) {
                check(createNewFile()) { "Could not prepare the GNU tar mount target" }
            }
        }
        val prootArguments =
            buildArguments(
                rootfsPath = ProotPathContract.rootfsPath(context, destination),
                tarExecutablePath = runtime.tarExecutable.absolutePath,
                tarMountPath = "/$RUNTIME_MOUNT_DIRECTORY/${tarMount.name}",
                stripComponents = stripComponents,
                excludeOciWhiteouts = excludeOciWhiteouts,
            )
        val command =
            AndroidExecutableCommand.create(
                runtime.executable,
                *prootArguments.toTypedArray(),
            )
        val prootTemporaryDirectory =
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
                    environment()["TMPDIR"] = prootTemporaryDirectory.absolutePath
                    environment()["PROOT_TMP_DIR"] = prootTemporaryDirectory.absolutePath
                    environment()["PROOT_LOADER"] = runtime.loader.absolutePath
                }
                .start()
        val stderrLines = mutableListOf<String>()
        val stderrThread =
            Thread {
                BufferedReader(InputStreamReader(process.errorStream)).useLines { lines ->
                    lines.forEach { line ->
                        synchronized(stderrLines) {
                            if (stderrLines.size == MAX_DIAGNOSTIC_LINES) {
                                stderrLines.removeAt(0)
                            }
                            stderrLines += line
                        }
                        onDiagnostic(line)
                    }
                }
            }.apply {
                name = "udroid-proot-stderr"
                isDaemon = true
                start()
            }

        try {
            val compressedInput = CountingInputStream(archive.inputStream().buffered())
            val archiveInput: InputStream =
                when {
                    xzCompressed -> XZInputStream(compressedInput)
                    gzipCompressed -> GZIPInputStream(compressedInput)
                    else -> compressedInput
                }
            archiveInput.use { input ->
                process.outputStream.buffered().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedIOException("Rootfs extraction interrupted")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        onProgress(compressedInput.bytesRead, archive.length())
                    }
                }
            }
            val exitCode = process.waitFor()
            stderrThread.join(DIAGNOSTIC_JOIN_MS)
            check(exitCode == 0) {
                synchronized(stderrLines) {
                    diagnosticSummary(exitCode, stderrLines)
                }
            }
        } catch (error: Throwable) {
            process.destroy()
            if (!process.waitFor(PROCESS_STOP_GRACE_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
            }
            throw error
        } finally {
            tarMount.delete()
            runtimeMount.delete()
        }
    }

    private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
        var bytesRead: Long = 0
            private set

        override fun read(): Int =
            super.read().also { value ->
                if (value >= 0) bytesRead++
            }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int =
            super.read(buffer, offset, length).also { count ->
                if (count > 0) bytesRead += count
            }
    }

    companion object {
        internal fun diagnosticSummary(
            exitCode: Int,
            stderrLines: List<String>,
        ): String {
            val detail =
                stderrLines.firstOrNull { line ->
                    line.isNotBlank() &&
                        !line.substringAfterLast(": ").lowercase().let { message ->
                            message == "had errors" ||
                                message == "exiting with failure status due to previous errors"
                        }
                }
            return detail
                ?.let { "PRoot tar failed ($exitCode): $it" }
                ?: "PRoot tar failed with exit code $exitCode"
        }

        internal fun buildArguments(
            rootfsPath: String,
            tarExecutablePath: String,
            tarMountPath: String,
            stripComponents: Int,
            excludeOciWhiteouts: Boolean = false,
        ): List<String> =
            buildList {
                add("--link2symlink")
                add("--rootfs=$rootfsPath")
                add("-b")
                add("/system")
                add("-b")
                add("/apex")
                add("-b")
                add("/dev")
                add("-b")
                add("/linkerconfig/ld.config.txt")
                add("-b")
                add("$tarExecutablePath:$tarMountPath")
                add("--cwd=/")
                add(tarMountPath)
                add("--warning=no-unknown-keyword")
                add("--delay-directory-restore")
                add("--preserve-permissions")
                if (stripComponents > 0) {
                    add("--strip-components=$stripComponents")
                }
                add("-xf")
                add("-")
                add("-C")
                add("/")
                // /dev is an Android bind mount, not an archive-owned directory.
                add("--exclude=dev")
                add("--exclude=$RUNTIME_MOUNT_DIRECTORY")
                if (excludeOciWhiteouts) {
                    add("--wildcards")
                    add("--exclude=.wh.*")
                    add("--exclude=*/.wh.*")
                }
            }

        private const val RUNTIME_MOUNT_DIRECTORY = ".udroid-extract"
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val MAX_DIAGNOSTIC_LINES = 40
        const val DIAGNOSTIC_JOIN_MS = 1_000L
        const val PROCESS_STOP_GRACE_MS = 1_000L
    }
}

class AndroidRootfsConfigurator : RootfsConfigurator {
    override fun configure(rootfs: File) {
        listOf("dev", "dev/shm", "proc", "sys", "tmp", "etc", "etc/profile.d").forEach {
            check(File(rootfs, it).mkdirs() || File(rootfs, it).isDirectory) {
                "Could not prepare /$it"
            }
        }
        File(rootfs, "proc").apply {
            check(setReadable(true, true) && setWritable(true, true) && setExecutable(true, true)) {
                "Could not prepare rootfs /proc compatibility directory"
            }
        }

        replaceFile(
            File(rootfs, "etc/hosts"),
            """
            127.0.0.1 localhost
            127.0.0.1 localhost.localdomain
            ::1 localhost ip6-localhost ip6-loopback
            """.trimIndent() + "\n",
        )
        replaceFile(
            File(rootfs, "etc/resolv.conf"),
            "nameserver 1.1.1.1\nnameserver 8.8.8.8\n",
        )
        replaceFile(
            File(rootfs, "proc/.version"),
            "Linux version 5.4.0-udroid-faked (udroid@android)\n",
        )
        replaceFile(File(rootfs, "proc/.uptime"), "0.00 0.00\n")
        replaceFile(File(rootfs, "proc/.loadavg"), "0.00 0.00 0.00 1/1 1\n")
        replaceFile(File(rootfs, "proc/.stat"), "cpu  1 0 1 1 0 0 0 0 0 0\n")
        replaceFile(File(rootfs, "proc/.vmstat"), "nr_free_pages 0\n")

        replaceFile(
            File(rootfs, "etc/profile.d/udroid.sh"),
            """
            export ANDROID_ART_ROOT=${'$'}{ANDROID_ART_ROOT-}
            export ANDROID_DATA=${'$'}{ANDROID_DATA-}
            export ANDROID_ROOT=${'$'}{ANDROID_ROOT-/system}
            export ANDROID_RUNTIME_ROOT=${'$'}{ANDROID_RUNTIME_ROOT-}
            export ANDROID_TZDATA_ROOT=${'$'}{ANDROID_TZDATA_ROOT-}
            export BOOTCLASSPATH=${'$'}{BOOTCLASSPATH-}
            export LANG=${'$'}{LANG-C.UTF-8}
            export PATH=${'$'}{PATH}:/system/bin:/system/xbin
            export PULSE_SERVER=127.0.0.1
            export TERM=${'$'}{TERM-xterm-256color}
            export TMPDIR=/tmp
            """.trimIndent() + "\n",
            executable = true,
        )
        appendAndroidGroups(File(rootfs, "etc/group"))
        File(rootfs, "usr/bin/sudo").takeIf(File::exists)?.setExecutable(true, false)
    }

    private fun appendAndroidGroups(groupFile: File) {
        val existing = if (groupFile.isFile) groupFile.readLines() else emptyList()
        val existingGids =
            existing.mapNotNull { line -> line.split(':').getOrNull(2)?.toIntOrNull() }.toSet()
        val gids =
            File("/proc/self/status")
                .takeIf(File::isFile)
                ?.readLines()
                ?.firstOrNull { it.startsWith("Groups:") }
                ?.substringAfter(':')
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.mapNotNull(String::toIntOrNull)
                ?.filterNot(existingGids::contains)
                ?.distinct()
                .orEmpty()
        if (gids.isEmpty()) return
        groupFile.parentFile?.mkdirs()
        groupFile.appendText(
            gids.joinToString(separator = "", transform = { "aid_$it:x:$it:root\n" }),
        )
    }

    private fun replaceFile(
        target: File,
        contents: String,
        executable: Boolean = false,
    ) {
        target.parentFile?.mkdirs()
        if (target.exists() || target.isSymbolicLink()) {
            check(target.delete()) { "Could not replace ${target.path}" }
        }
        FileOutputStream(target).use { output ->
            output.write(contents.toByteArray())
            output.fd.sync()
        }
        target.setReadable(true, false)
        target.setWritable(true, true)
        if (executable) target.setExecutable(true, false)
    }

    private fun File.isSymbolicLink(): Boolean =
        Files.isSymbolicLink(toPath())
}

class ProotRootfsHealthCheck(
    private val context: Context,
    private val runtime: ProotRuntime,
) : RootfsHealthCheck {
    override fun check(rootfs: File) {
        val shell =
            listOf("bin/sh", "usr/bin/sh", "bin/bash")
                .map { File(rootfs, it) }
                .firstOrNull { it.isFile || it.isSymbolicLink() }
                ?: error("Extracted rootfs has no shell")
        check(File(rootfs, "usr/bin/env").let { it.isFile || it.isSymbolicLink() }) {
            "Extracted rootfs has no /usr/bin/env"
        }
        val process =
            ProcessBuilder(
                AndroidExecutableCommand.create(
                    runtime.executable,
                    *buildArguments(
                        rootfsPath = ProotPathContract.rootfsPath(context, rootfs),
                        shellPath = "/${shell.relativeTo(rootfs).path}",
                    ),
                ),
            ).apply {
                directory(context.filesDir)
                redirectErrorStream(true)
                environment().remove("LD_PRELOAD")
                val temporaryDirectory =
                    File(context.cacheDir, "proot").apply {
                        check(mkdirs() || isDirectory) {
                            "Could not create PRoot temporary storage"
                        }
                    }
                environment()["TMPDIR"] = temporaryDirectory.absolutePath
                environment()["PROOT_TMP_DIR"] = temporaryDirectory.absolutePath
                environment()["PROOT_LOADER"] = runtime.loader.absolutePath
            }.start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            if (output.isBlank()) {
                "Rootfs health check failed with exit code $exitCode"
            } else {
                "Rootfs health check failed: ${output.lineSequence().last()}"
            }
        }
    }

    private fun File.isSymbolicLink(): Boolean =
        Files.isSymbolicLink(toPath())

    companion object {
        internal fun buildArguments(
            rootfsPath: String,
            shellPath: String,
        ): Array<String> =
            buildList {
                add("--link2symlink")
                add("--kill-on-exit")
                add("--root-id")
                add("--rootfs=$rootfsPath")
                addAndroidProotBindMounts()
                add("--cwd=/")
                add("/usr/bin/env")
                add("-i")
                add("PATH=/usr/bin:/bin")
                add(shellPath)
                add("-c")
                add(
                    "test -x /usr/bin/env && test -r /etc/os-release && " +
                        "if test -e /usr/sbin/dpkg-preconfigure; then " +
                        "test -x /usr/bin/perl && /usr/bin/perl -e 'exit 0'; fi",
                )
            }.toTypedArray()
    }
}

object RootfsStoragePreflight {
    private const val FIXED_HEADROOM_BYTES = 256L * 1024L * 1024L

    fun requireSpace(
        archive: File,
        rootfsDirectory: File,
    ) {
        rootfsDirectory.mkdirs()
        val required = estimatedExpandedBytes(archive) + FIXED_HEADROOM_BYTES
        val available = StatFs(rootfsDirectory.absolutePath).availableBytes
        check(available >= required) {
            "Need ${formatGiB(required)} free for extraction; ${formatGiB(available)} is available"
        }
    }

    internal fun estimatedExpandedBytes(archive: File): Long {
        val compressedFallback = archive.length().coerceAtLeast(1L) * 4L
        if (!archive.name.endsWith(".gz", ignoreCase = true) || archive.length() < 4L) {
            return compressedFallback
        }
        val trailerSize =
            RandomAccessFile(archive, "r").use { input ->
                input.seek(archive.length() - 4L)
                var value = 0L
                repeat(4) { shift ->
                    value = value or ((input.readUnsignedByte().toLong()) shl (shift * 8))
                }
                value
            }
        return max(trailerSize, compressedFallback)
    }

    private fun formatGiB(bytes: Long): String =
        String.format(java.util.Locale.US, "%.1f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
}
