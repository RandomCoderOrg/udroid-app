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
    fun `app owned media bridge is mounted and exported to the guest`() {
        val arguments =
            ProotTerminalLaunchBuilder.buildArguments(
                linker = "linker64",
                prootPath = "proot",
                rootfsPath = "rootfs",
                guestHome = "/root",
                guestShell = "/bin/bash",
                mediaHostDirectory = "/data/user/0/udroid/files/media/transport",
            )

        assertTrue(
            arguments
                .toList()
                .windowed(2)
                .contains(
                    listOf(
                        "-b",
                        "/data/user/0/udroid/files/media/transport:/tmp/.udroid-media",
                    ),
                ),
        )
        assertTrue("FMA_SOCKET=/tmp/.udroid-media/fake-media-accel.sock" in arguments)
        assertTrue("FMA_VA_SYNC_DIRECT_OUTPUT=1" in arguments)
        assertTrue("LIBVA_DRIVERS_PATH=/tmp/.udroid-media" in arguments)
        assertTrue("LIBVA_DRIVER_NAME=fma" in arguments)
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
