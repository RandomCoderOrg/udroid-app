package org.randomcoder.udroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Test
import org.randomcoder.udroid.install.RootfsInstallationPipeline
import java.nio.file.Files

class InstalledRootfsDiscoveryTest {
    @Test
    fun `discovers every ready rootfs and ignores incomplete directories`() {
        val parent = Files.createTempDirectory("udroid-rootfs-registry").toFile()
        try {
            val ubuntu = parent.resolve("udroid-jammy").apply { mkdirs() }
            val alpine = parent.resolve("proot-alpine-3.22").apply { mkdirs() }
            parent.resolve("incomplete").mkdirs()
            ubuntu.resolve(RootfsInstallationPipeline.READY_MARKER)
                .writeText("ready")
            alpine.resolve(RootfsInstallationPipeline.READY_MARKER)
                .writeText("ready")
            ubuntu.resolve(RootfsInstallationPipeline.READY_MARKER).setLastModified(10)
            alpine.resolve(RootfsInstallationPipeline.READY_MARKER).setLastModified(20)

            assertEquals(
                listOf("proot-alpine-3.22", "udroid-jammy"),
                InstalledRootfsDiscovery.scan(parent).map(InstalledRootfs::name),
            )
        } finally {
            parent.deleteRecursively()
        }
    }
}
