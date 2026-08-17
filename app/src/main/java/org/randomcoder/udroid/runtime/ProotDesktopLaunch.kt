package org.randomcoder.udroid.runtime

import android.content.Context
import org.randomcoder.udroid.audio.AudioEndpoint
import org.randomcoder.udroid.install.ProotRuntime
import org.randomcoder.udroid.install.RootfsInstallationPipeline
import java.io.File

object ProotDesktopLaunchBuilder {
    fun create(
        context: Context,
        runtime: ProotRuntime,
        rootfs: File,
        x11SocketDirectory: File,
        environment: DesktopEnvironment,
        configuration: DesktopConfiguration,
        audioEndpoint: AudioEndpoint? = null,
    ): ProotApplicationLaunch {
        require(File(rootfs, RootfsInstallationPipeline.READY_MARKER).isFile) {
            "The selected Linux image is not ready"
        }
        require(x11SocketDirectory.isDirectory) { "The X11 socket directory is unavailable" }
        val guestHome = if (File(rootfs, "root").isDirectory) "/root" else "/"
        val mounts =
            ProotMountResolver.resolve(
                profile = ProotMountProfileStore(context).load(rootfs.name),
                sessionMounts =
                    ProotMountResolver.sessionMounts(
                        x11SocketDirectory = x11SocketDirectory.absolutePath,
                        audioAuthDirectory = audioEndpoint?.hostAuthDirectory?.absolutePath,
                    ),
            )
        val arguments =
            buildArguments(
                prootPath = runtime.executable.absolutePath,
                rootfsPath = ProotPathContract.rootfsPath(context, rootfs),
                x11SocketDirectory = x11SocketDirectory.absolutePath,
                guestHome = guestHome,
                environment = environment,
                configuration = configuration,
                audioAuthDirectory = audioEndpoint?.hostAuthDirectory?.absolutePath,
                mounts = mounts,
                hasDbusRunSession =
                    File(rootfs, "usr/bin/dbus-run-session").isFile ||
                        File(rootfs, "bin/dbus-run-session").isFile,
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
                    *arguments.drop(1).toTypedArray(),
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
            mounts = mounts,
        )
    }

    internal fun buildArguments(
        prootPath: String,
        rootfsPath: String,
        x11SocketDirectory: String,
        guestHome: String,
        environment: DesktopEnvironment,
        configuration: DesktopConfiguration,
        hasDbusRunSession: Boolean,
        audioAuthDirectory: String? = null,
        mounts: List<ResolvedProotMount> =
            ProotMountResolver.defaults(x11SocketDirectory, audioAuthDirectory),
    ): List<String> =
        buildList {
            add(prootPath)
            add("--link2symlink")
            add("--kill-on-exit")
            add("--root-id")
            add("--rootfs=$rootfsPath")
            addProotBindMounts(mounts)
            add("--cwd=$guestHome")
            add("/usr/bin/env")
            add("-i")
            add("HOME=$guestHome")
            add("USER=root")
            add("LOGNAME=root")
            add("SHELL=/bin/sh")
            add("LANG=C.UTF-8")
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            add("DISPLAY=:0")
            if (audioAuthDirectory != null) {
                add("PULSE_SERVER=${AudioEndpoint.GUEST_SERVER}")
                add("PULSE_COOKIE=${AudioEndpoint.GUEST_AUTH_DIRECTORY}/${AudioEndpoint.COOKIE_NAME}")
            }
            add("XDG_SESSION_TYPE=x11")
            add("XDG_CURRENT_DESKTOP=${environment.kind.desktopName}")
            add("DESKTOP_SESSION=${environment.id}")
            add("GDK_BACKEND=x11")
            add("QT_QPA_PLATFORM=xcb")
            if (configuration.touchScaleEnabled) {
                add("GDK_SCALE=2")
                add("QT_SCALE_FACTOR=2")
                add("XCURSOR_SIZE=48")
            }
            if (hasDbusRunSession) {
                add("/usr/bin/dbus-run-session")
                add("--")
            }
            add("/bin/sh")
            add("-lc")
            add(compositorScript(environment.kind, configuration.compositingEnabled))
            add("udroid-desktop")
            addAll(environment.command)
        }

    internal fun compositorScript(
        kind: DesktopEnvironmentKind,
        enabled: Boolean,
    ): String {
        val value = enabled.toString()
        if (kind == DesktopEnvironmentKind.XFCE) {
            return "\"\$@\" & desktop_pid=\$!; " +
                "if command -v xfconf-query >/dev/null 2>&1; then " +
                "sleep 1; attempt=0; " +
                "while [ \"\$attempt\" -lt 5 ]; do " +
                "xfconf-query -c xfwm4 -p /general/use_compositing " +
                "-n -t bool -s $value >/dev/null 2>&1 || true; " +
                "attempt=\$((attempt + 1)); sleep 0.2; " +
                "done; fi; wait \"\$desktop_pid\""
        }
        val configuration =
            when (kind) {
                DesktopEnvironmentKind.XFCE -> error("Handled above")
                DesktopEnvironmentKind.PLASMA ->
                    "if command -v kwriteconfig6 >/dev/null 2>&1; then " +
                        "kwriteconfig6 --file kwinrc --group Compositing " +
                        "--key Enabled $value; " +
                        "elif command -v kwriteconfig5 >/dev/null 2>&1; then " +
                        "kwriteconfig5 --file kwinrc --group Compositing " +
                        "--key Enabled $value; fi"
                DesktopEnvironmentKind.MATE ->
                    "if command -v gsettings >/dev/null 2>&1; then " +
                        "gsettings set org.mate.Marco.general compositing-manager " +
                        "$value >/dev/null 2>&1 || true; fi"
                DesktopEnvironmentKind.GNOME,
                DesktopEnvironmentKind.LXQT,
                DesktopEnvironmentKind.OTHER,
                -> ""
            }
        return listOf(configuration, "exec \"\$@\"")
            .filter(String::isNotBlank)
            .joinToString("; ")
    }
}
