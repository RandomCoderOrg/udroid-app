package org.randomcoder.udroid.runtime

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ProotTerminalLaunchTest {
    @Test
    fun `absolute guest shell symlink is accepted without resolving against host root`() {
        val rootfs = Files.createTempDirectory("udroid-shell-link").toFile()
        try {
            val bin = rootfs.resolve("bin").apply { mkdirs() }
            Files.createSymbolicLink(bin.resolve("sh").toPath(), Path.of("/bin/busybox"))

            assertEquals("/bin/sh", ProotTerminalLaunchBuilder.findGuestShell(rootfs))
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `linker remains argv zero and starts proot`() {
        val arguments =
            ProotTerminalLaunchBuilder.buildArguments(
                linker = "/system/bin/linker64",
                prootPath = "/data/user/0/udroid/files/runtime/proot",
                rootfsPath = "/data/user/0/udroid/files/rootfs/jammy",
                guestHome = "/root",
                guestShell = "/bin/bash",
            )

        assertEquals("/system/bin/linker64", arguments[0])
        assertEquals("/data/user/0/udroid/files/runtime/proot", arguments[1])
        assertTrue("--rootfs=/data/user/0/udroid/files/rootfs/jammy" in arguments)
        assertTrue("--cwd=/root" in arguments)
        assertTrue(arguments.toList().windowed(2).contains(listOf("/usr/bin/env", "-i")))
        assertArrayEquals(
            arrayOf("/bin/bash", "--login"),
            arguments.takeLast(2).toTypedArray(),
        )
    }

    @Test
    fun `all Android and guest bridge mounts are explicit`() {
        val arguments =
            ProotTerminalLaunchBuilder.buildArguments(
                linker = "linker64",
                prootPath = "proot",
                rootfsPath = "rootfs",
                guestHome = "/",
                guestShell = "/bin/sh",
            )

        val bindings =
            arguments
                .toList()
                .windowed(2)
                .filter { it[0] == "-b" }
                .map { it[1] }

        assertEquals(
            listOf(
                "/system",
                "/apex",
                "/dev",
                "/proc",
                "/sys",
                "/linkerconfig/ld.config.txt",
            ),
            bindings,
        )
        assertEquals("/bin/sh", arguments.last())
    }

    @Test
    fun `embedded X11 socket is mounted and display is exported`() {
        val arguments =
            ProotTerminalLaunchBuilder.buildArguments(
                linker = "linker64",
                prootPath = "proot",
                rootfsPath = "rootfs",
                guestHome = "/root",
                guestShell = "/bin/bash",
                x11SocketDirectory = "/data/user/0/udroid/files/runtime/x11/.X11-unix",
            )

        assertTrue(
            arguments
                .toList()
                .windowed(2)
                .contains(
                    listOf(
                        "-b",
                        "/data/user/0/udroid/files/runtime/x11/.X11-unix:/tmp/.X11-unix",
                    ),
                ),
        )
        assertTrue(
            arguments
                .toList()
                .windowed(2)
                .contains(
                    listOf(
                        "-b",
                        "/data/user/0/udroid/files/runtime/x11/.X0-lock:/tmp/.X0-lock",
                    ),
                ),
        )
        assertTrue("DISPLAY=:0" in arguments)
    }

    @Test
    fun `authenticated loopback audio is exported to the guest`() {
        val arguments =
            ProotTerminalLaunchBuilder.buildArguments(
                linker = "linker64",
                prootPath = "proot",
                rootfsPath = "rootfs",
                guestHome = "/root",
                guestShell = "/bin/bash",
                audioAuthDirectory = "/data/user/0/udroid/files/audio/transport",
            )

        assertTrue(
            arguments
                .toList()
                .windowed(2)
                .contains(
                    listOf(
                        "-b",
                        "/data/user/0/udroid/files/audio/transport:/tmp/.udroid-pulse",
                    ),
                ),
        )
        assertTrue("PULSE_SERVER=tcp:127.0.0.1:4713" in arguments)
        assertTrue("PULSE_COOKIE=/tmp/.udroid-pulse/cookie" in arguments)
    }

    @Test
    fun `software profile wraps the login shell environment`() {
        val arguments =
            ProotTerminalLaunchBuilder.buildArguments(
                linker = "linker64",
                prootPath = "proot",
                rootfsPath = "rootfs",
                guestHome = "/root",
                guestShell = "/bin/bash",
                launchProfile =
                    EnvironmentProotLaunchProfile.from(DesktopGraphicsProfile.SOFTWARE),
            )

        assertEquals(
            listOf(
                "/usr/bin/env",
                "LIBGL_ALWAYS_SOFTWARE=1",
                "GALLIUM_DRIVER=llvmpipe",
                "/bin/bash",
                "--login",
            ),
            arguments.takeLast(5),
        )
    }

    @Test
    fun `managed override wins over the selected graphics profile`() {
        val arguments =
            ProotTerminalLaunchBuilder.buildArguments(
                linker = "linker64",
                prootPath = "proot",
                rootfsPath = "rootfs",
                guestHome = "/root",
                guestShell = "/bin/bash",
                launchProfile =
                    EnvironmentProotLaunchProfile.from(DesktopGraphicsProfile.SOFTWARE),
                managedEnvironment = listOf("GALLIUM_DRIVER=custom"),
            ).toList()

        assertEquals(
            listOf(
                "/usr/bin/env",
                "LIBGL_ALWAYS_SOFTWARE=1",
                "GALLIUM_DRIVER=llvmpipe",
                "/usr/bin/env",
                "GALLIUM_DRIVER=custom",
                "/bin/bash",
                "--login",
            ),
            arguments.takeLast(7),
        )
    }

    @Test
    fun `zink profile wraps the login shell environment`() {
        val arguments =
            ProotTerminalLaunchBuilder.buildArguments(
                linker = "linker64",
                prootPath = "proot",
                rootfsPath = "rootfs",
                guestHome = "/root",
                guestShell = "/bin/bash",
                launchProfile = EnvironmentProotLaunchProfile.from(DesktopGraphicsProfile.ZINK),
            )

        assertEquals(
            listOf(
                "/usr/bin/env",
                "MESA_LOADER_DRIVER_OVERRIDE=zink",
                "GALLIUM_DRIVER=zink",
                "LIBGL_KOPPER_DRI2=true",
                "/bin/bash",
                "--login",
            ),
            arguments.takeLast(6),
        )
    }

    @Test
    fun `terminal receives saved guest variables before locked session and graphics variables`() {
        val arguments =
            ProotTerminalLaunchBuilder.buildArguments(
                linker = "linker64",
                prootPath = "proot",
                rootfsPath = "rootfs",
                guestHome = "/root",
                guestShell = "/bin/bash",
                x11SocketDirectory = "/data/x11/.X11-unix",
                guestEnvironment =
                    ProotEnvironmentResolver.resolve(
                        ProotEnvironmentProfile(
                            defaultOverrides = mapOf("TERM" to "screen-256color"),
                            customVariables =
                                listOf(
                                    ProotCustomEnvironmentVariable("custom", "EDITOR", "code --wait"),
                                ),
                        ),
                    ),
                launchProfile =
                    EnvironmentProotLaunchProfile.from(DesktopGraphicsProfile.SOFTWARE),
            )

        assertTrue(arguments.indexOf("HOME=/root") < arguments.indexOf("TERM=screen-256color"))
        assertTrue(arguments.indexOf("TERM=screen-256color") < arguments.indexOf("EDITOR=code --wait"))
        assertTrue(arguments.indexOf("EDITOR=code --wait") < arguments.indexOf("DISPLAY=:0"))
        assertTrue(arguments.indexOf("DISPLAY=:0") < arguments.lastIndexOf("/usr/bin/env"))
    }

    @Test
    fun `profile bindings and wrapper apply to the complete login shell command`() {
        val profile =
            object : ProotLaunchProfile {
                override fun addBindings(arguments: MutableList<String>) {
                    arguments += "-b"
                    arguments += "/data/virgl:/tmp/.virgl"
                }

                override fun wrapGuestCommand(command: List<String>): List<String> =
                    listOf("/usr/bin/env", "GALLIUM_DRIVER=virpipe") + command
            }
        val arguments =
            ProotTerminalLaunchBuilder.buildArguments(
                linker = "linker64",
                prootPath = "proot",
                rootfsPath = "rootfs",
                guestHome = "/root",
                guestShell = "/bin/bash",
                launchProfile = profile,
            )

        assertTrue("/data/virgl:/tmp/.virgl" in arguments)
        assertTrue(
            arguments.indexOf("/data/virgl:/tmp/.virgl") < arguments.indexOf("--cwd=/root"),
        )
        assertEquals(
            listOf(
                "/usr/bin/env",
                "GALLIUM_DRIVER=virpipe",
                "/bin/bash",
                "--login",
            ),
            arguments.takeLast(4),
        )
    }

    @Test
    fun `proot loader and temporary storage stay app private`() {
        val environment =
            ProotTerminalLaunchBuilder.buildEnvironment(
                androidHome = "/data/user/0/udroid/files",
                loaderPath = "/data/user/0/udroid/files/runtime/libproot-loader.so",
                temporaryDirectory = "/data/user/0/udroid/cache/proot",
            )

        assertTrue("ANDROID_ROOT=/system" in environment)
        assertTrue(
            "PROOT_LOADER=/data/user/0/udroid/files/runtime/libproot-loader.so" in environment,
        )
        assertTrue("PROOT_TMP_DIR=/data/user/0/udroid/cache/proot" in environment)
        assertTrue("TMPDIR=/data/user/0/udroid/cache/proot" in environment)
    }
}
