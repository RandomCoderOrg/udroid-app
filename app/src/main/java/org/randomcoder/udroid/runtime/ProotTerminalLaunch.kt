package org.randomcoder.udroid.runtime

import android.content.Context
import org.randomcoder.udroid.install.ProotRuntime
import org.randomcoder.udroid.install.RootfsInstallationPipeline
import java.io.File
import java.nio.file.Files

data class ProotTerminalLaunch(
    val executable: String,
    val workingDirectory: String,
    val arguments: Array<String>,
    val environment: Array<String>,
    val rootfs: File,
)

object InstalledRootfsResolver {
    fun resolve(
        context: Context,
        name: String? = null,
    ): File = InstalledRootfsRegistry(context).resolve(name)
}

object ProotTerminalLaunchBuilder {
    fun create(
        context: Context,
        runtime: ProotRuntime,
        rootfs: File = InstalledRootfsResolver.resolve(context),
        x11SocketDirectory: File? = null,
    ): ProotTerminalLaunch {
        require(File(rootfs, RootfsInstallationPipeline.READY_MARKER).isFile) {
            "The selected Linux image is not ready"
        }
        val linker = AndroidExecutableCommand.systemLinkerPath()
        val temporaryDirectory =
            File(context.cacheDir, "proot").apply {
                check(mkdirs() || isDirectory) {
                    "Could not create PRoot temporary storage"
                }
            }
        val guestHome =
            if (File(rootfs, "root").isDirectory) {
                "/root"
            } else {
                "/"
            }
        val guestShell =
            findGuestShell(rootfs)
                ?: error("The installed Linux image has no supported shell")

        val arguments =
            buildArguments(
                linker = linker,
                prootPath = runtime.executable.absolutePath,
                rootfsPath = ProotPathContract.rootfsPath(context, rootfs),
                guestHome = guestHome,
                guestShell = guestShell,
                x11SocketDirectory = x11SocketDirectory?.absolutePath,
            )
        val environment =
            buildEnvironment(
                androidHome = context.filesDir.absolutePath,
                loaderPath = runtime.loader.absolutePath,
                temporaryDirectory = temporaryDirectory.absolutePath,
            )

        return ProotTerminalLaunch(
            executable = linker,
            workingDirectory = context.filesDir.absolutePath,
            arguments = arguments,
            environment = environment,
            rootfs = rootfs,
        )
    }

    internal fun buildArguments(
        linker: String,
        prootPath: String,
        rootfsPath: String,
        guestHome: String,
        guestShell: String,
        x11SocketDirectory: String? = null,
    ): Array<String> =
        buildList {
            // TerminalSession passes this complete vector to execvp(), including argv[0].
            add(linker)
            add(prootPath)
            add("--link2symlink")
            add("--kill-on-exit")
            add("--root-id")
            add("--rootfs=$rootfsPath")
            addAndroidProotBindMounts()
            if (x11SocketDirectory != null) {
                add("-b")
                add("$x11SocketDirectory:/tmp/.X11-unix")
            }
            add("--cwd=$guestHome")
            add("/usr/bin/env")
            add("-i")
            add("HOME=$guestHome")
            add("USER=root")
            add("LOGNAME=root")
            add("SHELL=$guestShell")
            add("TERM=xterm-256color")
            add("COLORTERM=truecolor")
            add("LANG=C.UTF-8")
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            if (x11SocketDirectory != null) add("DISPLAY=:0")
            add(guestShell)
            if (guestShell.endsWith("bash")) add("--login")
        }.toTypedArray()

    internal fun buildEnvironment(
        androidHome: String,
        loaderPath: String,
        temporaryDirectory: String,
    ): Array<String> =
        arrayOf(
            "ANDROID_DATA=/data",
            "ANDROID_ROOT=/system",
            "ANDROID_RUNTIME_ROOT=/apex/com.android.runtime",
            "ANDROID_TZDATA_ROOT=/apex/com.android.tzdata",
            "HOME=$androidHome",
            "PATH=/system/bin",
            "PROOT_LOADER=$loaderPath",
            "PROOT_TMP_DIR=$temporaryDirectory",
            "TMPDIR=$temporaryDirectory",
        )

    internal fun findGuestShell(rootfs: File): String? =
        listOf("/bin/bash", "/usr/bin/bash", "/bin/sh")
            .firstOrNull { guestPath ->
                File(rootfs, guestPath.removePrefix("/")).let { candidate ->
                    candidate.isFile || Files.isSymbolicLink(candidate.toPath())
                }
            }
}
