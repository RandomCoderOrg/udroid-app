package org.randomcoder.udroid.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MediaAccelerationRuntimeTest {
    @Test
    fun `detects a glibc rootfs without recursively scanning it`() {
        val rootfs = Files.createTempDirectory("udroid-glibc-rootfs").toFile()
        try {
            val libc = rootfs.resolve("usr/lib/aarch64-linux-gnu/libc.so.6")
            requireNotNull(libc.parentFile).mkdirs()
            libc.writeText("fixture")

            assertTrue(MediaAccelerationRuntimeInstaller.supportsRootfs(rootfs))
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `does not expose the glibc VA driver to a musl rootfs`() {
        val rootfs = Files.createTempDirectory("udroid-musl-rootfs").toFile()
        try {
            val musl = rootfs.resolve("lib/ld-musl-aarch64.so.1")
            requireNotNull(musl.parentFile).mkdirs()
            musl.writeText("fixture")

            assertFalse(MediaAccelerationRuntimeInstaller.supportsRootfs(rootfs))
        } finally {
            rootfs.deleteRecursively()
        }
    }
}
