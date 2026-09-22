package org.randomcoder.udroid.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.randomcoder.udroid.runtime.ANDROID_PROOT_BIND_MOUNTS
import org.randomcoder.udroid.runtime.ResolvedProotMount

class ProotRootfsHealthCheckTest {
    @Test
    fun `health probe uses the complete Android mount contract`() {
        val arguments =
            ProotRootfsHealthCheck.buildArguments(
                rootfsPath = "/data/user/0/udroid/files/rootfs/jammy",
                shellPath = "/bin/sh",
                mounts =
                    ANDROID_PROOT_BIND_MOUNTS.map {
                        ResolvedProotMount(it, it, "default:test")
                    } +
                        ResolvedProotMount(
                            "/data/user/0/udroid/no_backup/proot/jammy/sysdata/pci_devices",
                            "/proc/bus/pci/devices",
                            "runtime:compatibility",
                        ),
            )

        val bindings =
            arguments
                .toList()
                .windowed(2)
                .filter { it[0] == "-b" }
                .map { it[1] }

        assertEquals(
            ANDROID_PROOT_BIND_MOUNTS +
                "/data/user/0/udroid/no_backup/proot/jammy/sysdata/pci_devices:/proc/bus/pci/devices",
            bindings,
        )
        assertTrue("/dev" in bindings)
        assertTrue(arguments.toList().windowed(2).contains(listOf("/usr/bin/env", "-i")))
    }
}
