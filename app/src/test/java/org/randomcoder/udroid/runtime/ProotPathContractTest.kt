package org.randomcoder.udroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class ProotPathContractTest {
    @Test
    fun `uses matching data alias for files below app data directory`() {
        val parent = Files.createTempDirectory("udroid-data-alias")
        val data = parent.resolve("user-data").toFile().apply { mkdirs() }
        val alias = parent.resolve("legacy-data")
        Files.createSymbolicLink(alias, data.toPath())
        val rootfs = data.resolve("files/rootfs/jammy")

        val result =
            ProotPathContract.preferLegacyDataAlias(
                packageName = "org.randomcoder.udroid",
                dataDirectory = data,
                file = rootfs,
                legacyDataDirectory = alias.toFile(),
            )

        assertEquals(alias.resolve("files/rootfs/jammy").toString(), result)
        parent.toFile().deleteRecursively()
    }

    @Test
    fun `keeps actual path when aliases or ownership do not match`() {
        val parent = Files.createTempDirectory("udroid-data-no-alias")
        val data = parent.resolve("user-data").toFile().apply { mkdirs() }
        val other = parent.resolve("other-data").toFile().apply { mkdirs() }
        val rootfs = data.resolve("files/rootfs/jammy")

        assertEquals(
            rootfs.absolutePath,
            ProotPathContract.preferLegacyDataAlias(
                packageName = "org.randomcoder.udroid",
                dataDirectory = data,
                file = rootfs,
                legacyDataDirectory = other,
            ),
        )
        assertEquals(
            other.absolutePath,
            ProotPathContract.preferLegacyDataAlias(
                packageName = "org.randomcoder.udroid",
                dataDirectory = data,
                file = other,
                legacyDataDirectory = data,
            ),
        )
        parent.toFile().deleteRecursively()
    }
}
