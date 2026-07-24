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
            )

        assertEquals("/data/proot", arguments.first())
        assertTrue(arguments.contains("DISPLAY=:0"))
        assertTrue(arguments.contains("GDK_BACKEND=x11"))
        assertTrue(arguments.contains("--cwd=/root/Documents"))
        assertEquals(
            listOf("/usr/bin/demo", "--title", "Hello; touch /tmp/not-a-command"),
            arguments.takeLast(3),
        )
        assertFalse(arguments.contains("sh"))
        assertFalse(arguments.contains("-c"))
    }
}
