package org.randomcoder.udroid.oci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OciManifestIndexTest {
    @Test
    fun `android abi order selects an OCI platform`() {
        assertEquals(
            OciPlatform("linux", "arm64", "v8"),
            OciPlatform.fromAndroidAbis(listOf("arm64-v8a", "armeabi-v7a")),
        )
        assertEquals(
            OciPlatform("linux", "amd64"),
            OciPlatform.fromAndroidAbis(listOf("x86_64")),
        )
    }

    @Test
    fun `index selection prefers exact architecture variant`() {
        val selected =
            OciManifestIndex.select(
                index(
                    descriptor("arm64", null, "1"),
                    descriptor("amd64", null, "2"),
                    descriptor("arm64", "v8", "3"),
                ),
                OciPlatform("linux", "arm64", "v8"),
            )

        assertEquals("sha256:" + "3".repeat(64), selected.digest)
        assertEquals(OciPlatform("linux", "arm64", "v8"), selected.platform)
    }

    @Test
    fun `index selection accepts a variantless architecture fallback`() {
        val selected =
            OciManifestIndex.select(
                index(descriptor("arm64", null, "4")),
                OciPlatform("linux", "arm64", "v8"),
            )

        assertEquals("sha256:" + "4".repeat(64), selected.digest)
    }

    @Test
    fun `index selection rejects another arm variant`() {
        assertThrows(IllegalStateException::class.java) {
            OciManifestIndex.select(
                index(descriptor("arm", "v6", "5")),
                OciPlatform("linux", "arm", "v7"),
            )
        }
    }

    @Test
    fun `unsupported android abi fails explicitly`() {
        assertThrows(IllegalStateException::class.java) {
            OciPlatform.fromAndroidAbis(listOf("riscv64"))
        }
    }

    private fun index(vararg manifests: String): String =
        """
        {
          "schemaVersion": 2,
          "mediaType": "application/vnd.oci.image.index.v1+json",
          "manifests": [${manifests.joinToString()}]
        }
        """.trimIndent()

    private fun descriptor(
        architecture: String,
        variant: String?,
        digestCharacter: String,
    ): String {
        val variantField =
            variant?.let {
                """,
            "variant": "$it""""
            }.orEmpty()
        return """
            {
              "mediaType": "application/vnd.oci.image.manifest.v1+json",
              "digest": "sha256:${digestCharacter.repeat(64)}",
              "size": 1024,
              "platform": {
                "architecture": "$architecture",
                "os": "linux"$variantField
              }
            }
            """.trimIndent()
    }
}
