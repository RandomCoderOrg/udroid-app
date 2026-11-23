package org.randomcoder.udroid.install

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.randomcoder.udroid.runtime.ANDROID_PROOT_BIND_MOUNTS
import java.io.File
import java.nio.file.Files

class RootfsCheckpointTest {
    @Test
    fun `backup command excludes app markers and Android bind mounts`() {
        val arguments =
            RootfsCheckpointArchiver.buildArguments(
                rootfsPath = "/data/user/0/org.randomcoder.udroid/files/rootfs/ubuntu",
                tarExecutablePath = "/data/user/0/org.randomcoder.udroid/files/runtime/tar",
                tarMountPath = "/.udroid-backup/tar",
            )

        assertTrue("--root-id" in arguments)
        assertTrue("--one-file-system" in arguments)
        assertTrue("--exclude=.udroid-ready" in arguments)
        assertTrue("--exclude=.udroid-installing" in arguments)
        assertTrue("--exclude=.udroid-backup" in arguments)
        ANDROID_PROOT_BIND_MOUNTS.forEach { path ->
            assertTrue("--exclude=${path.removePrefix("/")}" in arguments)
        }
        assertTrue(
            arguments.windowed(2).contains(
                listOf(
                    "-b",
                    "/data/user/0/org.randomcoder.udroid/files/runtime/tar:/.udroid-backup/tar",
                ),
            ),
        )
    }

    @Test
    fun `validated restore replaces the rootfs and removes rollback data`() {
        val directory = Files.createTempDirectory("udroid-checkpoint-swap").toFile()
        try {
            val paths = RootfsCheckpointSwap.paths(directory, "ubuntu", OPERATION_ID)
            ready(paths.current, "old")
            ready(paths.staging, "new")

            RootfsCheckpointSwap.swapValidated(paths)

            assertTrue(File(paths.current, "new").isFile)
            assertFalse(File(paths.current, "old").exists())
            assertFalse(paths.staging.exists())
            assertFalse(paths.previous.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `recovery finishes a validated swap after the current rootfs was moved`() {
        val directory = Files.createTempDirectory("udroid-checkpoint-recover").toFile()
        try {
            val paths = RootfsCheckpointSwap.paths(directory, "ubuntu", OPERATION_ID)
            ready(paths.current, "old")
            ready(paths.staging, "new")
            assertTrue(paths.current.renameTo(paths.previous))

            RootfsCheckpointSwap.recover(paths)

            assertTrue(File(paths.current, "new").isFile)
            assertFalse(paths.staging.exists())
            assertFalse(paths.previous.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `recovery discards an incomplete staging rootfs without touching current`() {
        val directory = Files.createTempDirectory("udroid-checkpoint-partial").toFile()
        try {
            val paths = RootfsCheckpointSwap.paths(directory, "ubuntu", OPERATION_ID)
            ready(paths.current, "old")
            paths.staging.mkdirs()
            File(paths.staging, "partial").writeText("incomplete")

            RootfsCheckpointSwap.recover(paths)

            assertTrue(File(paths.current, "old").isFile)
            assertFalse(paths.staging.exists())
            assertFalse(paths.previous.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun ready(
        rootfs: File,
        content: String,
    ) {
        rootfs.mkdirs()
        File(rootfs, RootfsInstallationPipeline.READY_MARKER).writeText("format=1\n")
        File(rootfs, content).writeText(content)
    }

    private companion object {
        const val OPERATION_ID = "12345678-1234-1234-1234-123456789012"
    }
}
