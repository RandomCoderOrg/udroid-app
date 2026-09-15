package org.randomcoder.udroid.gfxstream

import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class GfxstreamGuestRuntimeTest {
    @Test
    fun `profile binds an immutable runtime and private gpu socket`() {
        val root = Files.createTempDirectory("udroid-gfxstream-profile").toFile()
        val launcher = root.resolve("bin/udroid-gfxstream-run")
        requireNotNull(launcher.parentFile).mkdirs()
        launcher.writeText("#!/bin/sh\n")
        launcher.setExecutable(true)
        val socket = Files.createTempFile("udroid-kumquat", ".sock").toFile()
        val profile =
            GfxstreamProotLaunchProfile(
                runtime = GfxstreamGuestRuntime(root, launcher, "test"),
                gpuSocket = socket,
            )
        val arguments = mutableListOf<String>()

        profile.addBindings(arguments)

        assertEquals(
            listOf(
                "-b",
                "${root.absolutePath}:/opt/udroid/gfxstream",
                "-b",
                "${socket.absolutePath}:/tmp/kumquat-gpu-0",
            ),
            arguments,
        )
        assertEquals(
            listOf("/opt/udroid/gfxstream/bin/udroid-gfxstream-run", "vulkaninfo", "--summary"),
            profile.wrapGuestCommand(listOf("vulkaninfo", "--summary")),
        )
    }

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

        assertTrue(
            VerifiedRuntimeAssetInstaller.isComplete(root, bundle, "arm64-v8a", manifest),
        )
        entry.writeText("altered-runtime")
        assertEquals("trusted-runtime".length, entry.length().toInt())
        assertFalse(
            VerifiedRuntimeAssetInstaller.isComplete(root, bundle, "arm64-v8a", manifest),
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
