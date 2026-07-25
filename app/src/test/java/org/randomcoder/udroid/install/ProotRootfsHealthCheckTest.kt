package org.randomcoder.udroid.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.randomcoder.udroid.runtime.ANDROID_PROOT_BIND_MOUNTS

class ProotRootfsHealthCheckTest {
    @Test
    fun `health probe uses the complete Android mount contract`() {
        val arguments =
            ProotRootfsHealthCheck.buildArguments(
                rootfsPath = "/data/user/0/udroid/files/rootfs/jammy",
                shellPath = "/bin/sh",
            )

        val bindings =
            arguments
                .toList()
                .windowed(2)
                .filter { it[0] == "-b" }
                .map { it[1] }

        assertEquals(ANDROID_PROOT_BIND_MOUNTS, bindings)
        assertTrue("/dev" in bindings)
        assertTrue(arguments.toList().windowed(2).contains(listOf("/usr/bin/env", "-i")))
    }
}
