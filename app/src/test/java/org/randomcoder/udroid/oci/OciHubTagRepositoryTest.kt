package org.randomcoder.udroid.oci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OciHubTagRepositoryTest {
    @Test
    fun `tag cache round trip retains platform digest and size`() {
        val platform = OciPlatform("linux", "arm64", "v8")
        val expected =
            OciHubTagSnapshot(
                repository = "ubuntu",
                platform = platform,
                tags =
                    listOf(
                        OciHubTagPlatform(
                            tag = "24.04",
                            platform = platform,
                            digest = "sha256:${"5".repeat(64)}",
                            compressedBytes = 28_884_180L,
                        ),
                    ),
                source = OciHubCatalogSource.NETWORK,
                fetchedAtEpochMs = 42L,
            )

        val actual =
            OciHubTagCacheCodec.decode(
                OciHubTagCacheCodec.encode(expected),
            )

        assertEquals(expected.repository, actual.repository)
        assertEquals(expected.platform, actual.platform)
        assertEquals(expected.tags, actual.tags)
        assertEquals(OciHubCatalogSource.CACHE, actual.source)
    }

    @Test
    fun `tag cache rejects untrusted digests`() {
        val encoded =
            """
            {
              "format": "1",
              "repository": "ubuntu",
              "platform_os": "linux",
              "platform_architecture": "arm64",
              "platform_variant": "v8",
              "fetched_at_epoch_ms": 1,
              "tags": [{
                "tag": "latest",
                "variant": "v8",
                "digest": "not-a-digest",
                "compressed_bytes": 10
              }]
            }
            """.trimIndent()

        val failure = runCatching { OciHubTagCacheCodec.decode(encoded) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}
