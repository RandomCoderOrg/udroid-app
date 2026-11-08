package org.randomcoder.udroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ProotLaunchFlagsTest {
    @Test
    fun `missing file adds no launch arguments`() {
        val directory = Files.createTempDirectory("udroid-launch-flags").toFile()
        try {
            assertEquals(emptyList<String>(), ProotLaunchFlags.load(directory.resolve("missing.txt")))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `each non-empty non-comment line is one literal argument`() {
        val file = Files.createTempFile("udroid-launch-flags", ".txt").toFile()
        try {
            file.writeText(
                """
                # Extra bindings for every Linux system

                -b
                /storage/emulated/0/My Files:/mnt/my files
                -v
                """.trimIndent(),
            )

            assertEquals(
                listOf("-b", "/storage/emulated/0/My Files:/mnt/my files", "-v"),
                ProotLaunchFlags.load(file),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `all proot arguments are passed through literally`() {
        val file = Files.createTempFile("udroid-launch-flags", ".txt").toFile()
        try {
            val arguments = listOf("--", "-r", "--rootfs=/tmp/root", "-R/tmp/root", "--cwd=/tmp")
            file.writeText(arguments.joinToString("\n"))

            assertEquals(arguments, ProotLaunchFlags.load(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `global arguments reach terminal desktop and application launches`() {
        val extra = listOf("-v", "2")
        val terminal =
            ProotTerminalLaunchBuilder.buildArguments(
                linker = "linker64",
                prootPath = "proot",
                rootfsPath = "rootfs",
                guestHome = "/root",
                guestShell = "/bin/sh",
                additionalProotArguments = extra,
            ).toList()
        val desktop =
            ProotDesktopLaunchBuilder.buildArguments(
                prootPath = "proot",
                rootfsPath = "rootfs",
                x11SocketDirectory = "/x11/.X11-unix",
                guestHome = "/root",
                environment =
                    DesktopEnvironment(
                        id = "xfce",
                        name = "Xfce",
                        command = listOf("startxfce4"),
                        desktopFilePath = "/usr/share/xsessions/xfce.desktop",
                        kind = DesktopEnvironmentKind.XFCE,
                    ),
                configuration =
                    DesktopConfiguration(
                        environmentId = "xfce",
                        compositingEnabled = false,
                        touchScaleEnabled = false,
                    ),
                hasDbusRunSession = false,
                additionalProotArguments = extra,
            )
        val application =
            ProotApplicationLaunchBuilder.buildArguments(
                prootPath = "proot",
                rootfsPath = "rootfs",
                x11SocketDirectory = "/x11/.X11-unix",
                guestHome = "/root",
                guestWorkingDirectory = "/root",
                applicationArguments = listOf("/usr/bin/demo"),
                additionalProotArguments = extra,
            )

        listOf(terminal, desktop, application).forEach { arguments ->
            assertTrue(arguments.indexOf("-v") > arguments.indexOf("--rootfs=rootfs"))
            assertTrue(arguments.indexOf("-v") > arguments.indexOfFirst { it.startsWith("--cwd=") })
            assertTrue(arguments.indexOf("-v") < arguments.indexOf("/usr/bin/env"))
            assertEquals("2", arguments[arguments.indexOf("-v") + 1])
        }
    }
}
