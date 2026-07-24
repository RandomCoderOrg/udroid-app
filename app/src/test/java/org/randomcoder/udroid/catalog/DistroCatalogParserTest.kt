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
        assertEquals("jammy:raw", catalog.variants.single().id)
        assertEquals("abc123", catalog.variants.single().sha256)
        assertTrue(catalog.variants.single().recommended)
    }
}
