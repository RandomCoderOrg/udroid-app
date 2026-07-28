package org.randomcoder.udroid.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.randomcoder.udroid.oci.OciHubRepository
import org.randomcoder.udroid.oci.OciHubTagPlatform
import org.randomcoder.udroid.oci.OciPlatform

class OciInstallationSelectionTest {
    private val repository =
        OciHubRepository(
            name = "ubuntu",
            description = "Ubuntu base system",
            pullCount = 1,
            starCount = 1,
        )
    private val tag =
        OciHubTagPlatform(
            tag = "24.04",
            platform = OciPlatform("linux", "arm64", "v8"),
            digest = "sha256:${"7".repeat(64)}",
            compressedBytes = 28_884_180L,
        )

    @Test
    fun `tag selection creates a digest pinned review state`() {
        val progress =
            OciInstallationSelection.initial(
                repository = repository,
                tag = tag,
                displayArchitecture = "aarch64",
            )
        val work = progress.work as InstallerWorkRequest.Oci

        assertEquals(InstallStage.READY, progress.stage)
        assertEquals("oci-ubuntu-24.04", work.installationName)
        assertEquals("Ubuntu 24.04", work.displayName)
        assertEquals(tag.digest, work.reference.digest)
        assertEquals(tag.tag, work.reference.tag)
        assertEquals(tag.platform, work.platform)
        assertTrue(progress.currentDetail.contains("27.5 MiB"))
    }

    @Test
    fun `long tags produce safe stable and collision resistant rootfs names`() {
        val prefix = "release-" + "x".repeat(119)
        val first = OciInstallationSelection.installationName("ubuntu", "${prefix}a")
        val second = OciInstallationSelection.installationName("ubuntu", "${prefix}b")

        assertEquals(first, OciInstallationSelection.installationName("ubuntu", "${prefix}a"))
        assertNotEquals(first, second)
        assertTrue(first.length <= 96)
        assertTrue(Regex("[A-Za-z0-9][A-Za-z0-9._-]+").matches(first))
    }

    @Test
    fun `known repository names preserve their product spelling`() {
        assertEquals(
            "AlmaLinux",
            OciInstallationSelection.displayName(
                OciHubRepository(
                    name = "almalinux",
                    description = "AlmaLinux base system",
                    pullCount = 1,
                    starCount = 1,
                ),
            ),
        )
    }
}
