package org.randomcoder.udroid.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test
    fun `semantic version comparison handles release and prerelease ordering`() {
        assertTrue(SemanticVersion.compare("0.0.3", "0.0.2") > 0)
        assertTrue(SemanticVersion.compare("v1.0.0", "0.99.99") > 0)
        assertTrue(SemanticVersion.compare("1.0.0", "1.0.0-rc.2") > 0)
        assertTrue(SemanticVersion.compare("1.0.0-rc.10", "1.0.0-rc.2") > 0)
        assertEquals("0.0.3-rc.1", SemanticVersion.normalize("v0.0.3-rc.1"))
        assertNull(SemanticVersion.normalize("nightly"))
    }

    @Test
    fun `release list includes prereleases and selects the newest installable version`() {
        val result =
            GitHubReleaseClient("https://example.invalid")
                .parse(
                    json =
                        """
                        [
                          ${releaseJson("v0.0.2", "old.apk")},
                          ${releaseJson("v0.0.4", "udroid-v0.0.4-prerelease.apk")},
                          ${releaseJson("v0.0.3", "udroid-v0.0.3-prerelease.apk")}
                        ]
                        """.trimIndent(),
                    currentVersion = "0.0.2",
                    etag = "\"fixture\"",
                )

        val available = result as GitHubReleaseCheck.Available
        assertEquals("0.0.4", available.release.version)
        assertEquals("udroid-v0.0.4-prerelease.apk", available.release.apkName)
        assertEquals("a".repeat(64), available.release.apkSha256)
    }

    @Test
    fun `release without checksum manifest is not installable`() {
        val json = releaseJson("v0.0.3", "udroid.apk", includeChecksums = false)
        val result =
            GitHubReleaseClient("https://example.invalid")
                .parse("[$json]", "0.0.2", null)

        assertTrue(result is GitHubReleaseCheck.UpToDate)
    }

    @Test
    fun `release without GitHub asset digest is not installable`() {
        val json =
            releaseJson("v0.0.3", "udroid.apk")
                .replace("\"digest\": \"sha256:${"a".repeat(64)}\",", "")
        val result =
            GitHubReleaseClient("https://example.invalid")
                .parse("[$json]", "0.0.2", null)

        assertTrue(result is GitHubReleaseCheck.UpToDate)
    }

    @Test
    fun `checksum manifest selects the exact APK name`() {
        val wanted = "b".repeat(64)
        val manifest =
            """
            ${"a".repeat(64)}  another.apk
            $wanted *udroid-v0.0.3-prerelease.apk
            """.trimIndent()

        assertEquals(
            wanted,
            Sha256Sums.digestFor(manifest, "udroid-v0.0.3-prerelease.apk"),
        )
        assertNull(Sha256Sums.digestFor(manifest, "udroid.apk"))
    }

    private fun releaseJson(
        tag: String,
        apkName: String,
        includeChecksums: Boolean = true,
    ): String =
        """
        {
          "tag_name": "$tag",
          "name": "uDroid $tag",
          "body": "Development release",
          "draft": false,
          "prerelease": true,
          "published_at": "2026-07-25T00:00:00Z",
          "html_url": "https://github.com/RandomCoderOrg/udroid-app/releases/tag/$tag",
          "assets": [
            {
              "name": "$apkName",
              "size": 12345,
              "digest": "sha256:${"a".repeat(64)}",
              "browser_download_url": "https://github.com/RandomCoderOrg/udroid-app/releases/download/$tag/$apkName"
            }
            ${if (includeChecksums) ",${checksumAsset(tag)}" else ""}
          ]
        }
        """.trimIndent()

    private fun checksumAsset(tag: String): String =
        """
        {
          "name": "SHA256SUMS",
          "size": 90,
          "browser_download_url": "https://github.com/RandomCoderOrg/udroid-app/releases/download/$tag/SHA256SUMS"
        }
        """.trimIndent()
}
