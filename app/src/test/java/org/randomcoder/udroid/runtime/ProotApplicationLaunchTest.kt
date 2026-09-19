package org.randomcoder.udroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProotApplicationLaunchTest {
    @Test
    fun buildsArgumentVectorWithoutAShell() {
        val arguments =
            ProotApplicationLaunchBuilder.buildArguments(
                prootPath = "/data/proot",
                rootfsPath = "/data/rootfs",
                x11SocketDirectory = "/data/x11/.X11-unix",
                guestHome = "/root",
                guestWorkingDirectory = "/root/Documents",
                applicationArguments =
                    listOf(
                        "/usr/bin/demo",
                        "--title",
                        "Hello; touch /tmp/not-a-command",
                    ),
                audioAuthDirectory = "/data/audio/transport",
            )

        assertEquals("/data/proot", arguments.first())
        assertTrue(arguments.contains("DISPLAY=:0"))
        assertTrue(arguments.contains("/data/x11/.X0-lock:/tmp/.X0-lock"))
        assertTrue(arguments.contains("GDK_BACKEND=x11"))
        assertTrue(arguments.contains("--cwd=/root/Documents"))
        assertTrue(arguments.contains("/data/audio/transport:/tmp/.udroid-pulse"))
        assertTrue(arguments.contains("PULSE_SERVER=tcp:127.0.0.1:4713"))
        assertTrue(arguments.contains("PULSE_COOKIE=/tmp/.udroid-pulse/cookie"))
        assertEquals(
            listOf("/usr/bin/demo", "--title", "Hello; touch /tmp/not-a-command"),
            arguments.takeLast(3),
        )
        assertFalse(arguments.contains("sh"))
        assertFalse(arguments.contains("-c"))
    }

    @Test
    fun appliesAnOptionalProfileOnlyToTheSelectedCommand() {
        val profile =
            object : ProotLaunchProfile {
                override fun addBindings(arguments: MutableList<String>) {
                    arguments += "-b"
                    arguments += "/data/runtime:/opt/runtime"
                }

                override fun wrapGuestCommand(command: List<String>): List<String> =
                    listOf("/opt/runtime/bin/run") + command
            }

        val arguments =
            ProotApplicationLaunchBuilder.buildArguments(
                prootPath = "/data/proot",
                rootfsPath = "/data/rootfs",
                x11SocketDirectory = "/data/x11/.X11-unix",
                guestHome = "/root",
                guestWorkingDirectory = "/root",
                applicationArguments = listOf("/usr/bin/vulkaninfo", "--summary"),
                launchProfile = profile,
            )

        assertTrue(arguments.contains("/data/runtime:/opt/runtime"))
        assertTrue(
            arguments.indexOf("/data/runtime:/opt/runtime") < arguments.indexOf("--cwd=/root"),
        )
        assertEquals(
            listOf(
                "/opt/runtime/bin/run",
                "/usr/bin/vulkaninfo",
                "--summary",
            ),
            arguments.takeLast(3),
        )
    }

    @Test
    fun wrapsGraphicalApplicationsWithTheSelectedGraphicsEnvironment() {
        val arguments =
            ProotApplicationLaunchBuilder.buildArguments(
                prootPath = "/data/proot",
                rootfsPath = "/data/rootfs",
                x11SocketDirectory = "/data/x11/.X11-unix",
                guestHome = "/root",
                guestWorkingDirectory = "/root",
                applicationArguments = listOf("/usr/bin/glxinfo", "-B"),
                launchProfile =
                    EnvironmentProotLaunchProfile.from(DesktopGraphicsProfile.ZINK),
            )

        assertEquals(
            listOf(
                "/usr/bin/env",
                "MESA_LOADER_DRIVER_OVERRIDE=zink",
                "GALLIUM_DRIVER=zink",
                "LIBGL_KOPPER_DRI2=true",
                "/usr/bin/glxinfo",
                "-B",
            ),
            arguments.takeLast(6),
        )
    }

    @Test
    fun `graphical application receives saved guest variables without shell parsing`() {
        val arguments =
            ProotApplicationLaunchBuilder.buildArguments(
                prootPath = "/data/proot",
                rootfsPath = "/data/rootfs",
                x11SocketDirectory = "/data/x11/.X11-unix",
                guestHome = "/root",
                guestWorkingDirectory = "/root",
                applicationArguments = listOf("/usr/bin/demo"),
                guestEnvironment =
                    ProotEnvironmentResolver.resolve(
                        ProotEnvironmentProfile(
                            customVariables =
                                listOf(
                                    ProotCustomEnvironmentVariable("custom", "APP_FLAGS", "one two=three"),
                                ),
                        ),
                    ),
            )

        assertTrue("APP_FLAGS=one two=three" in arguments)
        assertTrue(arguments.indexOf("APP_FLAGS=one two=three") < arguments.indexOf("DISPLAY=:0"))
        assertEquals("/usr/bin/demo", arguments.last())
    }
}
