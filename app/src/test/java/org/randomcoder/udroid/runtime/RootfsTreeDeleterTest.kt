package org.randomcoder.udroid.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RootfsTreeDeleterTest {
    @Test
    fun `deletes a rootfs without following guest symlinks`() {
        val parent = Files.createTempDirectory("udroid-rootfs-delete")
        val external = Files.createTempDirectory("udroid-rootfs-external")
        try {
            val rootfs = Files.createDirectories(parent.resolve("debian"))
            Files.createDirectories(rootfs.resolve("usr/bin"))
            Files.write(rootfs.resolve("usr/bin/example"), "guest".toByteArray())
            Files.write(external.resolve("keep"), "host".toByteArray())
            Files.createSymbolicLink(rootfs.resolve("external"), external)
            rootfs.resolve("usr/bin").toFile().setWritable(false, false)

            RootfsTreeDeleter.delete(parent, "debian")

            assertFalse(Files.exists(rootfs))
            assertTrue(Files.exists(external.resolve("keep")))
        } finally {
            parent.toFile().deleteRecursively()
            external.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects traversal and a symlink used as the rootfs`() {
        val parent = Files.createTempDirectory("udroid-rootfs-delete")
        val external = Files.createTempDirectory("udroid-rootfs-external")
        try {
            Files.createSymbolicLink(parent.resolve("linked"), external)

            assertThrows(IllegalArgumentException::class.java) {
                RootfsTreeDeleter.delete(parent, "../outside")
            }
            assertThrows(IllegalArgumentException::class.java) {
                RootfsTreeDeleter.delete(parent, "linked")
            }
            assertTrue(Files.exists(external))
        } finally {
            parent.toFile().deleteRecursively()
            external.toFile().deleteRecursively()
        }
    }
}
