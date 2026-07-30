package org.randomcoder.udroid.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class InstalledRootfsSourceRecoveryTest {
    @Test
    fun `recovers an OCI reset source from an existing ready marker`() {
        val marker = Files.createTempFile("udroid-ready", ".marker").toFile()
        try {
            marker.writeText(
                """
                format=1
                source=oci
                name=oci-alpine-3.22
                operation=old-operation
                reference=registry-1.docker.io/library/alpine:3.22@sha256:${"a".repeat(64)}
                manifest=sha256:${"a".repeat(64)}
                platform=linux/arm64/v8
                """.trimIndent(),
            )

            val work =
                InstalledRootfsSourceRecovery.fromReadyMarker(
                    installationName = "oci-alpine-3.22",
                    marker = marker,
                )

            requireNotNull(work)
            assertEquals("Alpine Linux 3.22", work.displayName)
            assertEquals("aarch64", work.architecture)
            assertEquals("v8", work.platform.variant)
        } finally {
            marker.delete()
        }
    }

    @Test
    fun `does not recover a marker for another rootfs`() {
        val marker = Files.createTempFile("udroid-ready", ".marker").toFile()
        try {
            marker.writeText(
                """
                format=1
                source=oci
                name=oci-debian-trixie
                reference=registry-1.docker.io/library/debian:trixie
                platform=linux/arm64
                """.trimIndent(),
            )

            assertNull(
                InstalledRootfsSourceRecovery.fromReadyMarker(
                    installationName = "oci-alpine-3.22",
                    marker = marker,
                ),
            )
        } finally {
            marker.delete()
        }
    }
}
