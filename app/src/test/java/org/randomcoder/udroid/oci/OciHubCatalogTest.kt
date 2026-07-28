package org.randomcoder.udroid.oci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OciHubCatalogTest {
    @Test
    fun `catalogue keeps active official operating systems and rejects deprecated entries`() {
        val page =
            OciHubCatalogParser.repositories(
                """
                {
                  "next": "https://hub.docker.com/v2/namespaces/library/repositories?page=2",
                  "results": [
                    {
                      "name": "ubuntu",
                      "namespace": "library",
                      "repository_type": "image",
                      "status_description": "active",
                      "description": "Ubuntu base system",
                      "pull_count": 99,
                      "star_count": 12,
                      "categories": [{"slug": "operating-systems"}],
                      "content_types": ["image"]
                    },
                    {
                      "name": "ubuntu-upstart",
                      "namespace": "library",
                      "repository_type": "image",
                      "status_description": "active",
                      "description": "DEPRECATED",
                      "pull_count": 1,
                      "star_count": 1,
                      "categories": [{"slug": "operating-systems"}],
                      "content_types": ["image"]
                    },
                    {
                      "name": "private-lookalike",
                      "namespace": "someone",
                      "repository_type": "image",
                      "status_description": "active",
                      "description": "Not official",
                      "categories": [{"slug": "operating-systems"}],
                      "content_types": ["image"]
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertEquals(listOf("ubuntu"), page.repositories.map(OciHubRepository::name))
        assertEquals("registry-1.docker.io/library/ubuntu:latest", page.repositories.single().reference.toString())
        assertTrue(page.next!!.contains("page=2"))
    }

    @Test
    fun `tag inspection selects exact arm64 variant and ignores attestations`() {
        val selected =
            OciHubCatalogParser.tagPlatform(
                json =
                    """
                    {
                      "name": "24.04",
                      "images": [
                        {
                          "architecture": "unknown",
                          "variant": null,
                          "os": "unknown",
                          "status": "active",
                          "size": 42,
                          "digest": "sha256:${"1".repeat(64)}"
                        },
                        {
                          "architecture": "arm64",
                          "variant": "v8",
                          "os": "linux",
                          "status": "active",
                          "size": 28884180,
                          "digest": "sha256:${"7".repeat(64)}"
                        }
                      ]
                    }
                    """.trimIndent(),
                target = OciPlatform("linux", "arm64", "v8"),
            )

        assertEquals("24.04", selected.tag)
        assertEquals(OciPlatform("linux", "arm64", "v8"), selected.platform)
        assertEquals(28_884_180L, selected.compressedBytes)
        assertEquals("sha256:${"7".repeat(64)}", selected.digest)
    }

    @Test
    fun `tag inspection fails before install when phone architecture is absent`() {
        val failure =
            runCatching {
                OciHubCatalogParser.tagPlatform(
                    """
                    {
                      "name": "latest",
                      "images": [{
                        "architecture": "amd64",
                        "os": "linux",
                        "status": "active",
                        "size": 10,
                        "digest": "sha256:${"2".repeat(64)}"
                      }]
                    }
                    """.trimIndent(),
                    OciPlatform("linux", "arm64", "v8"),
                )
            }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("linux/arm64/v8"))
    }

    @Test
    fun `tag pages retain only phone-compatible images`() {
        val page =
            OciHubCatalogParser.tags(
                """
                {
                  "next": null,
                  "results": [
                    {
                      "name": "24.04",
                      "images": [{
                        "architecture": "arm64",
                        "variant": "v8",
                        "os": "linux",
                        "status": "active",
                        "size": 28884180,
                        "digest": "sha256:${"3".repeat(64)}"
                      }]
                    },
                    {
                      "name": "amd64-only",
                      "images": [{
                        "architecture": "amd64",
                        "os": "linux",
                        "status": "active",
                        "size": 10,
                        "digest": "sha256:${"4".repeat(64)}"
                      }]
                    }
                  ]
                }
                """.trimIndent(),
                OciPlatform("linux", "arm64", "v8"),
            )

        assertEquals(listOf("24.04"), page.tags.map(OciHubTagPlatform::tag))
        assertEquals(null, page.next)
    }
}
