package org.randomcoder.udroid.install

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.randomcoder.udroid.runtime.ANDROID_PROOT_BIND_MOUNTS

class ProotTarExtractorTest {
    @Test
    fun `extractor uses GNU tar with delayed directory restoration`() {
        val arguments =
            ProotTarExtractor.buildArguments(
                rootfsPath = "/data/data/org.randomcoder.udroid/files/rootfs/arch",
                tarExecutablePath = "/data/user/0/org.randomcoder.udroid/files/runtime/tar",
                tarMountPath = "/.udroid-extract/tar",
                stripComponents = 1,
            )

        assertTrue("--delay-directory-restore" in arguments)
        assertTrue("--preserve-permissions" in arguments)
        assertTrue("--strip-components=1" in arguments)
        val guestCommandIndex = arguments.indexOf("/.udroid-extract/tar")
        assertTrue(guestCommandIndex > 0)
        ANDROID_PROOT_BIND_MOUNTS.forEach { path ->
            val bindIndex = arguments.windowed(2).indexOf(listOf("-b", path))
            assertTrue("Missing extraction bind for $path", bindIndex >= 0)
            assertTrue("Extraction bind $path must precede the guest command", bindIndex < guestCommandIndex)
            assertTrue("--exclude=${path.removePrefix("/")}" in arguments)
        }
        assertTrue(
            arguments.windowed(2).contains(
                listOf(
                    "-b",
                    "/data/user/0/org.randomcoder.udroid/files/runtime/tar:/.udroid-extract/tar",
                ),
            ),
        )
        assertFalse("/system/bin/tar" in arguments)
    }

    @Test
    fun `failure summary keeps the actionable tar error instead of its footer`() {
        val summary =
            ProotTarExtractor.diagnosticSummary(
                exitCode = 1,
                stderrLines =
                    listOf(
                        "tar: etc/ca-certificates/extracted/cadir/link: Permission denied",
                        "tar: had errors",
                    ),
            )

        assertEquals(
            "PRoot tar failed (1): " +
                "tar: etc/ca-certificates/extracted/cadir/link: Permission denied",
            summary,
        )
    }
}
