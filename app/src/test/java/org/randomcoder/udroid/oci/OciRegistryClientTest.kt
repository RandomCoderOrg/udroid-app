package org.randomcoder.udroid.oci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class OciRegistryClientTest {
    @Test
    fun `authenticated index resolves to a verified device manifest`() {
        val leaf = manifest()
        val leafDigest = digest(leaf)
        val index = index(leafDigest, leaf.toByteArray().size.toLong())
        val indexDigest = digest(index)
        val requests = mutableListOf<RecordedRequest>()
        val client =
            OciRegistryClient { url ->
                FakeConnection(url) { connection ->
                    requests +=
                        RecordedRequest(
                            url = url.toString(),
                            authorization = connection.getRequestProperty("Authorization"),
                            accept = connection.getRequestProperty("Accept"),
                        )
                    when {
                        url.host == "auth.example.test" ->
                            Response(200, """{"token":"registry-token"}""")
                        connection.getRequestProperty("Authorization") == null ->
                            Response(
                                401,
                                "",
                                mapOf(
                                    "WWW-Authenticate" to
                                        "Bearer realm=\"https://auth.example.test/token\"," +
                                        "service=\"registry.example.test\"," +
                                        "scope=\"repository:system/base:pull\"",
                                ),
                            )
                        url.path.endsWith("/manifests/24.04") ->
                            Response(
                                200,
                                index,
                                mapOf("Docker-Content-Digest" to indexDigest),
                            )
                        url.path.endsWith("/manifests/$leafDigest") ->
                            Response(
                                200,
                                leaf,
                                mapOf("Docker-Content-Digest" to leafDigest),
                            )
                        else -> error("Unexpected request to $url")
                    }
                }
            }

        val resolved =
            client.resolve(
                OciImageReference.parse("registry.example.test/system/base:24.04"),
                OciPlatform("linux", "arm64", "v8"),
            )

        assertEquals(leafDigest, resolved.manifestDigest)
        assertEquals("sha256:" + "c".repeat(64), resolved.config.digest)
        assertEquals(
            listOf("sha256:" + "1".repeat(64), "sha256:" + "2".repeat(64)),
            resolved.layers.map(OciBlobDescriptor::digest),
        )
        assertEquals(listOf(12L, 34L), resolved.layers.map(OciBlobDescriptor::size))
        val firstLayer = resolved.blobDownload(resolved.layers.first())
        assertEquals(
            "https://registry.example.test/v2/system/base/blobs/sha256:${"1".repeat(64)}",
            firstLayer.url,
        )
        assertEquals(
            "Bearer registry-token",
            firstLayer.requestHeaders["Authorization"],
        )
        assertTrue(requests.first().accept.orEmpty().contains("oci.image.index"))
        assertEquals("Bearer registry-token", requests.last().authorization)
        assertTrue(
            requests.single { it.url.startsWith("https://auth.example.test") }
                .url
                .contains("scope=repository%3Asystem%2Fbase%3Apull"),
        )
    }

    @Test
    fun `digest mismatch is rejected before manifest parsing`() {
        val manifest = manifest()
        val client =
            OciRegistryClient { url ->
                FakeConnection(url) {
                    Response(
                        200,
                        manifest,
                        mapOf("Docker-Content-Digest" to "sha256:" + "0".repeat(64)),
                    )
                }
            }

        val error =
            runCatching {
                client.resolve(
                    OciImageReference.parse("registry.example.test/system/base:latest"),
                    OciPlatform("linux", "arm64", "v8"),
                )
            }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("digest mismatch"))
    }

    private fun index(
        manifestDigest: String,
        manifestSize: Long,
    ): String =
        """
        {
          "schemaVersion": 2,
          "mediaType": "application/vnd.oci.image.index.v1+json",
          "manifests": [
            {
              "mediaType": "application/vnd.oci.image.manifest.v1+json",
              "digest": "$manifestDigest",
              "size": $manifestSize,
              "platform": {
                "architecture": "arm64",
                "os": "linux",
                "variant": "v8"
              }
            }
          ]
        }
        """.trimIndent()

    private fun manifest(): String =
        """
        {
          "schemaVersion": 2,
          "mediaType": "application/vnd.oci.image.manifest.v1+json",
          "config": {
            "mediaType": "application/vnd.oci.image.config.v1+json",
            "digest": "sha256:${"c".repeat(64)}",
            "size": 99
          },
          "layers": [
            {
              "mediaType": "application/vnd.oci.image.layer.v1.tar+gzip",
              "digest": "sha256:${"1".repeat(64)}",
              "size": 12
            },
            {
              "mediaType": "application/vnd.oci.image.layer.v1.tar+gzip",
              "digest": "sha256:${"2".repeat(64)}",
              "size": 34
            }
          ]
        }
        """.trimIndent()

    private fun digest(value: String): String =
        "sha256:" +
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray())
                .joinToString(separator = "") { "%02x".format(it) }

    private data class RecordedRequest(
        val url: String,
        val authorization: String?,
        val accept: String?,
    )

    private data class Response(
        val status: Int,
        val body: String,
        val headers: Map<String, String> = emptyMap(),
    )

    private class FakeConnection(
        url: URL,
        private val responder: (FakeConnection) -> Response,
    ) : HttpURLConnection(url) {
        private val response: Response by lazy { responder(this) }

        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = response.status

        override fun getInputStream(): InputStream =
            ByteArrayInputStream(response.body.toByteArray())

        override fun getContentLengthLong(): Long = response.body.toByteArray().size.toLong()

        override fun getHeaderField(name: String?): String? =
            response.headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.value
    }
}
