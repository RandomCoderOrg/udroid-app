package org.randomcoder.udroid.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DistroCatalogParserTest {
    @Test
    fun `parser preserves upstream typo and resolves architecture keys`() {
        val catalog =
            DistroCatalogParser.parse(
                """
                {
                  "suites": ["jammy"],
                  "jammy": {
                    "varients": ["raw"],
                    "raw": {
                      "Name": "udroid-jammy-raw",
                      "FirendlyName": "Ubuntu Jammy raw",
                      "aarch64url": "https://example.test/jammy.tar.gz",
                      "aarch64sha": "abc123"
                    }
                  }
                }
                """.trimIndent(),
                architecture = "aarch64",
            )

        assertEquals(1, catalog.variants.size)
        assertEquals("ubuntu:jammy:raw", catalog.variants.single().id)
        assertEquals("abc123", catalog.variants.single().sha256)
        assertTrue(catalog.variants.single().recommended)
    }

    @Test
    fun `pinned proot distro catalogue exposes core distributions for arm64`() {
        val variants = ProotDistroArchiveCatalog.forArchitecture("aarch64")

        assertEquals(
            setOf(
                LinuxDistribution.DEBIAN,
                LinuxDistribution.ARCH,
                LinuxDistribution.ALPINE,
                LinuxDistribution.VOID,
            ),
            variants.map(DistroVariant::distribution).toSet(),
        )
        assertTrue(variants.all { it.provider == DistroProvider.PROOT_DISTRO })
        assertTrue(variants.all { it.downloadUrl.endsWith(".tar.xz") })
        assertTrue(variants.all { it.sha256.matches(Regex("[a-f0-9]{64}")) })
        assertTrue(variants.all { it.archiveStripComponents == 1 })
    }

    @Test
    fun `distro search metadata includes provider release and architecture`() {
        val debian =
            ProotDistroArchiveCatalog.forArchitecture("aarch64")
                .first { it.distribution == LinuxDistribution.DEBIAN }

        assertTrue("debian 13" in debian.searchableText)
        assertTrue("trixie" in debian.searchableText)
        assertTrue("aarch64" in debian.searchableText)
        assertTrue("proot-distro" in debian.searchableText)
    }
}
