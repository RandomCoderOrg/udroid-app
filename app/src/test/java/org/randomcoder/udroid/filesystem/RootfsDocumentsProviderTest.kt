package org.randomcoder.udroid.filesystem

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.randomcoder.udroid.R

class RootfsDocumentsProviderTest {
    @Test
    fun rootIconsFollowTheInstalledDistribution() {
        assertEquals(R.drawable.distro_ubuntu, rootfsIconResource("udroid-jammy-raw"))
        assertEquals(R.drawable.distro_debian, rootfsIconResource("debian-trixie"))
        assertEquals(R.drawable.distro_unknown, rootfsIconResource("custom-linux"))
    }

    @Test
    fun documentIdsRoundTripAndRejectTraversal() {
        val id = RootfsDocumentId.decode(RootfsDocumentId.encode("ubuntu:noble", "usr/share/a b"))
        assertEquals("ubuntu:noble", id.rootfs)
        assertEquals("usr/share/a b", id.relative)
        assertThrows(IllegalArgumentException::class.java) {
            RootfsDocumentId.decode(RootfsDocumentId.encode("ubuntu", "../outside"))
        }
    }

    @Test
    fun resolverKeepsRelativeAndAbsoluteSymlinksInsideGuestRoot() {
        val root = Files.createTempDirectory("rootfs-provider")
        Files.createDirectories(root.resolve("usr/bin"))
        Files.write(root.resolve("usr/bin/tool"), "ok".toByteArray())
        Files.createSymbolicLink(root.resolve("bin"), root.fileSystem.getPath("usr/bin"))
        Files.createSymbolicLink(root.resolve("sbin"), root.fileSystem.getPath("/usr/bin"))

        assertEquals(root.resolve("usr/bin/tool"), RootfsPathResolver.resolve(root, "bin/tool"))
        assertEquals(root.resolve("usr/bin/tool"), RootfsPathResolver.resolve(root, "sbin/tool"))
        assertThrows(IllegalArgumentException::class.java) { RootfsPathResolver.resolve(root, "../outside") }
    }
}
