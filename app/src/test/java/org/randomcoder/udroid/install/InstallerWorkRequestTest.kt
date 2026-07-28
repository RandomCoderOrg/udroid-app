package org.randomcoder.udroid.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.randomcoder.udroid.catalog.DistroProvider
import org.randomcoder.udroid.catalog.DistroVariant
import org.randomcoder.udroid.catalog.LinuxDistribution
import org.randomcoder.udroid.oci.OciImageReference
import org.randomcoder.udroid.oci.OciPlatform

class InstallerWorkRequestTest {
    @Test
    fun `archive work survives service intent serialization`() {
        val expected =
            InstallerWorkRequest.Archive(
                operationId = "archive-1234",
                distro =
                    DistroVariant(
                        suite = "trixie",
                        variant = "base",
                        internalName = "debian-trixie",
                        friendlyName = "Debian 13",
                        architecture = "aarch64",
                        downloadUrl = "https://example.test/debian.tar.xz",
                        sha256 = "a".repeat(64),
                        distribution = LinuxDistribution.DEBIAN,
                        provider = DistroProvider.PROOT_DISTRO,
                        releaseLabel = "Debian 13 (Trixie)",
                        archiveStripComponents = 1,
                    ),
            )

        val actual =
            InstallerWorkRequestCodec.decode(
                InstallerWorkRequestCodec.encode(expected),
            )

        assertEquals(expected, actual)
    }

    @Test
    fun `oci work survives service intent serialization without fake archive metadata`() {
        val expected =
            InstallerWorkRequest.Oci(
                reference = OciImageReference.parse("ubuntu:24.04"),
                platform = OciPlatform("linux", "arm64", "v8"),
                installationName = "ubuntu-24.04-oci",
                displayName = "Ubuntu 24.04",
                architecture = "aarch64",
                operationId = "oci-1234",
            )

        val actual =
            InstallerWorkRequestCodec.decode(
                InstallerWorkRequestCodec.encode(expected),
            )

        assertEquals(expected, actual)
        assertTrue(InstallerWorkRequestCodec.encode(expected).contains("\"source\":\"oci\""))
    }

    @Test
    fun `unsafe installation names are rejected before service dispatch`() {
        val encoded =
            InstallerWorkRequestCodec.encode(
                InstallerWorkRequest.Oci(
                    reference = OciImageReference.parse("ubuntu:24.04"),
                    platform = OciPlatform("linux", "arm64", "v8"),
                    installationName = "../escape",
                    displayName = "Ubuntu 24.04",
                    architecture = "aarch64",
                    operationId = "oci-1234",
                ),
            )

        val failure = runCatching { InstallerWorkRequestCodec.decode(encoded) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}
