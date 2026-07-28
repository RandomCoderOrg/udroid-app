package org.randomcoder.udroid.oci

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OciWhiteoutsTest {
    private val temporaryDirectories = mutableListOf<File>()

    @After
    fun cleanUp() {
        temporaryDirectories.forEach(File::deleteRecursively)
    }

    @Test
    fun `planner translates file and opaque directory markers`() {
        val plan =
            OciWhiteoutPlan.fromArchiveEntries(
                listOf(
                    "etc/.wh.legacy.conf",
                    "./usr/share/cache/.wh..wh..opq",
                    "usr/bin/current",
                ),
            )

        assertEquals(setOf("etc/legacy.conf"), plan.deletions)
        assertEquals(setOf("usr/share/cache"), plan.opaqueDirectories)
    }

    @Test
    fun `applier removes lower files without following final symlinks`() {
        val rootfs = temporaryDirectory()
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/legacy.conf").writeText("old")
        File(rootfs, "usr/share/cache").mkdirs()
        File(rootfs, "usr/share/cache/old-index").writeText("old")
        File(rootfs, "usr/share/cache/old-link")
            .toPath()
            .let { Files.createSymbolicLink(it, File(rootfs, "etc").toPath()) }

        OciWhiteoutApplier().apply(
            rootfs,
            OciWhiteoutPlan(
                deletions = setOf("etc/legacy.conf"),
                opaqueDirectories = setOf("usr/share/cache"),
            ),
        )

        assertFalse(File(rootfs, "etc/legacy.conf").exists())
        assertTrue(File(rootfs, "etc").isDirectory)
        assertTrue(File(rootfs, "usr/share/cache").isDirectory)
        assertEquals(emptyList<String>(), File(rootfs, "usr/share/cache").list()?.toList())
    }

    @Test
    fun `unsafe archive paths are rejected before filesystem work`() {
        assertThrows(IllegalArgumentException::class.java) {
            OciWhiteoutPlan.fromArchiveEntries(listOf("../../outside/.wh.file"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OciWhiteoutPlan.fromArchiveEntries(listOf("/etc/.wh.shadow"))
        }
    }

    @Test
    fun `whiteout cannot cross a symlinked parent`() {
        val rootfs = temporaryDirectory()
        val outside = temporaryDirectory()
        File(outside, "valuable").writeText("keep")
        Files.createSymbolicLink(File(rootfs, "escape").toPath(), outside.toPath())

        assertThrows(IllegalArgumentException::class.java) {
            OciWhiteoutApplier().apply(
                rootfs,
                OciWhiteoutPlan(
                    deletions = setOf("escape/valuable"),
                    opaqueDirectories = emptySet(),
                ),
            )
        }
        assertEquals("keep", File(outside, "valuable").readText())
    }

    private fun temporaryDirectory(): File =
        Files.createTempDirectory("udroid-oci-whiteout-test")
            .toFile()
            .also(temporaryDirectories::add)
}
