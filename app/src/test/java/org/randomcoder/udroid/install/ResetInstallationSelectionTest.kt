package org.randomcoder.udroid.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.randomcoder.udroid.catalog.DistroVariant
import org.randomcoder.udroid.oci.OciImageReference
import org.randomcoder.udroid.oci.OciPlatform

class ResetInstallationSelectionTest {
    @Test
    fun `archive reset preserves source and replaces operation id`() {
        val distro =
            DistroVariant(
                suite = "trixie",
                variant = "base",
                internalName = "debian-trixie",
                friendlyName = "Debian",
                architecture = "aarch64",
                downloadUrl = "https://example.invalid/debian.tar.xz",
                sha256 = "a".repeat(64),
            )
        val progress =
            ResetInstallationSelection.initial(
                InstallerWorkRequest.Archive(distro, "old-operation"),
                operationId = "new-operation",
            )

        assertEquals(InstallStage.READY, progress.stage)
        assertFalse(progress.previewOnly)
        assertEquals("new-operation", progress.operationId)
        assertEquals(distro, (progress.work as InstallerWorkRequest.Archive).distro)
    }

    @Test
    fun `oci reset preserves immutable reference and platform`() {
        val previous =
            InstallerWorkRequest.Oci(
                reference =
                    OciImageReference.parse(
                        "docker.io/library/alpine:3.22@sha256:${"b".repeat(64)}",
                    ),
                platform = OciPlatform("linux", "arm64"),
                installationName = "oci-alpine-3.22",
                displayName = "Alpine Linux 3.22",
                architecture = "aarch64",
                operationId = "old-operation",
            )

        val progress =
            ResetInstallationSelection.initial(previous, operationId = "new-operation")
        val reset = progress.work as InstallerWorkRequest.Oci

        assertEquals(previous.reference, reset.reference)
        assertEquals(previous.platform, reset.platform)
        assertEquals("new-operation", reset.operationId)
        assertTrue(progress.terminalLines.any { it.contains(previous.reference.toString()) })
    }
}
