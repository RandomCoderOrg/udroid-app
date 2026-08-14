package org.randomcoder.udroid.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class MediaAccelerationRuntimeTest {
    @Test
    fun `reads the runtime directory revision from the packaged manifest`() {
        assertEquals(
            "132c7bb7292ba8e337236db303e0e3c31972a28f",
            MediaAccelerationRuntimeInstaller.parseSourceRevision(
                "format=1\nsource_revision=132c7bb7292ba8e337236db303e0e3c31972a28f\n",
            ),
        )
    }

    @Test
    fun `rejects a missing or ambiguous runtime revision`() {
        assertThrows(IllegalStateException::class.java) {
            MediaAccelerationRuntimeInstaller.parseSourceRevision("format=1\n")
        }
        assertThrows(IllegalStateException::class.java) {
            MediaAccelerationRuntimeInstaller.parseSourceRevision(
                "source_revision=${"a".repeat(40)}\nsource_revision=${"b".repeat(40)}\n",
            )
        }
    }

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
