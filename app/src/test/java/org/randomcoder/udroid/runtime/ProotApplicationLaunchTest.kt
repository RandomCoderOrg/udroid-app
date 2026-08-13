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
    fun `direct rootfs display does not add an X11 bind alias`() {
        val socketDirectory = "/data/rootfs/tmp/.X11-unix"
        val arguments =
            ProotApplicationLaunchBuilder.buildArguments(
                prootPath = "/data/proot",
                rootfsPath = "/data/rootfs",
                x11SocketDirectory = socketDirectory,
                bindX11Socket = false,
                guestHome = "/root",
                guestWorkingDirectory = "/root",
                applicationArguments = listOf("/usr/bin/demo"),
            )

        assertTrue("DISPLAY=:0" in arguments)
        assertTrue(arguments.none { socketDirectory in it })
    }
}
