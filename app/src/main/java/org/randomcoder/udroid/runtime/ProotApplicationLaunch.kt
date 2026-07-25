package org.randomcoder.udroid.runtime

import android.content.Context
import org.randomcoder.udroid.install.ProotRuntime
import org.randomcoder.udroid.linuxapps.LinuxApplication
import java.io.File

data class ProotApplicationLaunch(
    val command: List<String>,
    val workingDirectory: File,
    val environment: Map<String, String>,
)

object ProotApplicationLaunchBuilder {
    fun create(
        context: Context,
        runtime: ProotRuntime,
        rootfs: File,
        x11SocketDirectory: File,
        application: LinuxApplication,
    ): ProotApplicationLaunch {
        require(application.executable.isNotBlank()) { "Application executable is empty" }
        require(x11SocketDirectory.isDirectory) { "The X11 socket directory is unavailable" }
        val guestHome = if (File(rootfs, "root").isDirectory) "/root" else "/"
        val guestWorkingDirectory =
            application.workingDirectory
                .takeIf { guestPathExists(rootfs, it, directory = true) }
                ?: guestHome
        val prootArguments =
            buildArguments(
                prootPath = runtime.executable.absolutePath,
                rootfsPath = rootfs.absolutePath,
                x11SocketDirectory = x11SocketDirectory.absolutePath,
                guestHome = guestHome,
                guestWorkingDirectory = guestWorkingDirectory,
                applicationArguments =
                    listOf(application.executable) + application.arguments,
            )
        val temporaryDirectory =
            File(context.cacheDir, "proot").apply {
                check(mkdirs() || isDirectory) {
                    "Could not prepare PRoot temporary storage"
                }
            }
        return ProotApplicationLaunch(
            command =
                AndroidExecutableCommand.create(
                    runtime.executable,
                    *prootArguments.drop(1).toTypedArray(),
                ),
            workingDirectory = context.filesDir,
            environment =
                ProotTerminalLaunchBuilder
                    .buildEnvironment(
                        androidHome = context.filesDir.absolutePath,
                        loaderPath = runtime.loader.absolutePath,
                        temporaryDirectory = temporaryDirectory.absolutePath,
                    ).associate {
                        val separator = it.indexOf('=')
                        it.substring(0, separator) to it.substring(separator + 1)
                    },
        )
    }

    internal fun buildArguments(
        prootPath: String,
        rootfsPath: String,
        x11SocketDirectory: String,
        guestHome: String,
        guestWorkingDirectory: String,
        applicationArguments: List<String>,
    ): List<String> {
        require(applicationArguments.isNotEmpty())
        return buildList {
            add(prootPath)
            add("--link2symlink")
            add("--kill-on-exit")
            add("--root-id")
            add("--rootfs=$rootfsPath")
            addAndroidProotBindMounts()
            add("-b")
            add("$x11SocketDirectory:/tmp/.X11-unix")
            add("--cwd=$guestWorkingDirectory")
            add("/usr/bin/env")
            add("-i")
            add("HOME=$guestHome")
            add("USER=root")
            add("LOGNAME=root")
            add("SHELL=/bin/sh")
            add("LANG=C.UTF-8")
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            add("DISPLAY=:0")
            add("XDG_CURRENT_DESKTOP=UDROID")
            add("GDK_BACKEND=x11")
            add("QT_QPA_PLATFORM=xcb")
            addAll(applicationArguments)
        }
    }

    private fun guestPathExists(
        rootfs: File,
        guestPath: String,
        directory: Boolean,
    ): Boolean {
        if (!guestPath.startsWith('/')) return false
        val root = rootfs.canonicalFile
        val target = File(root, guestPath.removePrefix("/")).canonicalFile
        if (target != root && !target.toPath().startsWith(root.toPath())) return false
        return if (directory) target.isDirectory else target.isFile
    }
}
