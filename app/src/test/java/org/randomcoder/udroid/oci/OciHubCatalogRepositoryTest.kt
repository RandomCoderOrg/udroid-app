package org.randomcoder.udroid.oci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OciHubCatalogRepositoryTest {
    private val repository =
        OciHubRepository(
            name = "ubuntu",
            description = "Ubuntu base system",
            pullCount = 99,
            starCount = 12,
        )

    @Test
    fun `compact cache round trip retains stable catalogue metadata`() {
        val expected =
            OciHubCatalogSnapshot(
                repositories = listOf(repository),
                source = OciHubCatalogSource.NETWORK,
                fetchedAtEpochMs = 123_456L,
            )

        val actual =
            OciHubCatalogCacheCodec.decode(
                OciHubCatalogCacheCodec.encode(expected),
            )

        assertEquals(expected.repositories, actual.repositories)
        assertEquals(expected.fetchedAtEpochMs, actual.fetchedAtEpochMs)
        assertEquals(OciHubCatalogSource.CACHE, actual.source)
    }

    @Test
    fun `cache rejects path-shaped repository names`() {
        val encoded =
            """
            {
              "format": "1",
              "fetched_at_epoch_ms": 1,
              "repositories": [{
                "name": "../ubuntu",
                "description": "unsafe",
                "pull_count": 1,
                "star_count": 1
              }]
            }
            """.trimIndent()

        val failure =
            runCatching { OciHubCatalogCacheCodec.decode(encoded) }
                .exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}
