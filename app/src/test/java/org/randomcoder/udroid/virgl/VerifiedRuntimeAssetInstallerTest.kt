package org.randomcoder.udroid.virgl

import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifiedRuntimeAssetInstallerTest {
    @Test
    fun `verified runtime rejects a missing digest`() {
        val bundle =
            VerifiedRuntimeAssetBundle(
                name = "test runtime",
                assetDirectory = "test",
                destinationPrefix = "test",
                version = "1",
                entries = listOf("bin/probe"),
            )
        val manifest =
            Properties().apply {
                setProperty("format", "1")
                setProperty("runtime", "1")
                setProperty("abi", "arm64-v8a")
            }

        assertThrows(IllegalStateException::class.java) {
            VerifiedRuntimeAssetInstaller.validateManifest(manifest, bundle, "arm64-v8a")
        }
    }

    @Test
    fun `verified runtime rejects same-size tampering with an unchanged manifest`() {
        val root = Files.createTempDirectory("udroid-verified-runtime").toFile()
        val entry = root.resolve("lib/runtime.so")
        requireNotNull(entry.parentFile).mkdirs()
        entry.writeText("trusted-runtime")
        val bundle =
            VerifiedRuntimeAssetBundle(
                name = "test runtime",
                assetDirectory = "test",
                destinationPrefix = "test",
                version = "1",
                entries = listOf("lib/runtime.so"),
            )
        val manifest =
            Properties().apply {
                setProperty("format", "1")
                setProperty("runtime", "1")
                setProperty("abi", "arm64-v8a")
                setProperty("lib/runtime.so.sha256", sha256(entry.readBytes()))
            }
        root.resolve("MANIFEST.properties").outputStream().use { output ->
            manifest.store(output, null)
        }

        assertTrue(VerifiedRuntimeAssetInstaller.isComplete(root, bundle, "arm64-v8a", manifest))
        entry.writeText("altered-runtime")
        assertEquals("trusted-runtime".length, entry.length().toInt())
        assertFalse(VerifiedRuntimeAssetInstaller.isComplete(root, bundle, "arm64-v8a", manifest))
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
