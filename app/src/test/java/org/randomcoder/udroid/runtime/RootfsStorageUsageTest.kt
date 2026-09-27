package org.randomcoder.udroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class RootfsStorageUsageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `counts regular rootfs files`() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        rootfs.resolve("etc").mkdir()
        rootfs.resolve("etc/config").writeBytes(ByteArray(7))
        rootfs.resolve("payload").writeBytes(ByteArray(13))
        val outside = temporaryFolder.newFile("outside").apply { writeBytes(ByteArray(100)) }
        Files.createSymbolicLink(rootfs.resolve("outside-link").toPath(), outside.toPath())

        assertEquals(20L, RootfsStorageUsage.calculate(rootfs))
    }
}
