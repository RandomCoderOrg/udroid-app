package org.randomcoder.udroid.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.randomcoder.udroid.oci.OciImageReference
import org.randomcoder.udroid.oci.OciPlatform

class InstallProgressCodecTest {
    @Test
    fun `OCI progress survives a persisted snapshot round trip`() {
        val work =
            InstallerWorkRequest.Oci(
                reference = OciImageReference.parse("docker.io/library/ubuntu:24.04"),
                platform = OciPlatform("linux", "arm64", "v8"),
                installationName = "oci-ubuntu-24.04",
                displayName = "Ubuntu 24.04",
                architecture = "arm64",
                operationId = "oci-operation-1",
            )
        val expected =
            InstallProgress(
                work = work,
                stage = InstallStage.DOWNLOADING,
                stageProgress = 0.42f,
                currentDetail = "Downloading layer",
                terminalLines = listOf("[resolve] manifest", "[download] 42 MiB"),
                previewOnly = false,
                completedBytes = 42,
                totalBytes = 100,
                bytesPerSecond = 7,
                cancellable = true,
            )

        val actual = InstallProgressCodec.decode(InstallProgressCodec.encode(expected))

        assertEquals(expected, actual)
        assertEquals("registry-1.docker.io/library/ubuntu:24.04", actual.sourceIdentity)
        assertEquals("oci-ubuntu-24.04", actual.installationName)
    }

    @Test
    fun `legacy archive snapshot migrates without requiring new fields`() {
        val legacy =
            """
            {
              "suite": "jammy",
              "variant": "base",
              "internal_name": "ubuntu-jammy",
              "friendly_name": "Ubuntu 22.04",
              "architecture": "aarch64",
              "download_url": "https://example.test/jammy.tar.xz",
              "sha256": "abc123",
              "distribution": "ubuntu",
              "provider": "UDROID",
              "stage": "DOWNLOADING",
              "stage_progress": 0.25,
              "current_detail": "Downloading",
              "terminal_lines": ["[download] partial"],
              "preview_only": false,
              "operation_id": "legacy-operation"
            }
            """.trimIndent()

        val progress = InstallProgressCodec.decode(legacy)
        val work = progress.work

        assertTrue(work is InstallerWorkRequest.Archive)
        work as InstallerWorkRequest.Archive
        assertEquals("ubuntu-jammy", work.distro.internalName)
        assertEquals("legacy-operation", work.operationId)
        assertEquals(0L, progress.completedBytes)
        assertEquals(-1L, progress.totalBytes)
        assertFalse(progress.cancellable)
    }
}
