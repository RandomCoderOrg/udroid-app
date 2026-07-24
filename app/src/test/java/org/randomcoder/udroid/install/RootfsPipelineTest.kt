package org.randomcoder.udroid.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InterruptedIOException
import java.nio.file.Files

class RootfsPipelineTest {
    @Test
    fun `successful install extracts into stable final path and marks it ready`() {
        val fixture = fixture()
        val events = mutableListOf<String>()
        val pipeline =
            RootfsInstallationPipeline(
                extractor =
                    RootfsExtractor { _, destination, progress ->
                        assertEquals(File(fixture.rootfs, "jammy"), destination)
                        File(destination, "usr/bin").mkdirs()
                        File(destination, "usr/bin/env").writeText("fixture")
                        progress(7, 7)
                        events += "extract"
                    },
                configurator =
                    RootfsConfigurator {
                        File(it, "etc").mkdirs()
                        events += "configure"
                    },
                healthCheck = RootfsHealthCheck { events += "health" },
            )

        val result = pipeline.execute(fixture.request)

        assertEquals(listOf("extract", "configure", "health"), events)
        assertTrue(result.rootfs.isDirectory)
        assertTrue(File(result.rootfs, RootfsInstallationPipeline.READY_MARKER).isFile)
        assertFalse(
            File(result.rootfs, RootfsInstallationPipeline.INSTALLING_MARKER).exists(),
        )
        assertFalse(fixture.archive.exists())
    }

    @Test
    fun `interrupted extraction removes final path but retains verified archive`() {
        val fixture = fixture()
        val pipeline =
            RootfsInstallationPipeline(
                extractor =
                    RootfsExtractor { _, destination, _ ->
                        File(destination, "partial").writeText("incomplete")
                        throw InterruptedIOException("pause")
                    },
                configurator = RootfsConfigurator {},
                healthCheck = RootfsHealthCheck {},
            )

        runCatching { pipeline.execute(fixture.request) }

        assertTrue(fixture.archive.isFile)
        assertFalse(File(fixture.rootfs, "jammy").exists())
    }

    @Test
    fun `retry replaces an incomplete installer-owned final path`() {
        val fixture = fixture()
        val active = File(fixture.rootfs, "jammy").apply { mkdirs() }
        File(active, RootfsInstallationPipeline.INSTALLING_MARKER).writeText("format=1\n")
        File(active, "partial").writeText("incomplete")
        val pipeline =
            RootfsInstallationPipeline(
                extractor =
                    RootfsExtractor { _, destination, _ ->
                        assertFalse(File(destination, "partial").exists())
                        File(destination, "complete").writeText("ready")
                    },
                configurator = RootfsConfigurator {},
                healthCheck = RootfsHealthCheck {},
            )

        val result = pipeline.execute(fixture.request)

        assertTrue(File(result.rootfs, "complete").isFile)
        assertTrue(File(result.rootfs, RootfsInstallationPipeline.READY_MARKER).isFile)
        assertFalse(
            File(result.rootfs, RootfsInstallationPipeline.INSTALLING_MARKER).exists(),
        )
    }

    @Test
    fun `ready installation is reused without touching its contents`() {
        val fixture = fixture()
        val active = File(fixture.rootfs, "jammy").apply { mkdirs() }
        File(active, RootfsInstallationPipeline.READY_MARKER).writeText("format=1\n")
        File(active, "keep").writeText("user data")
        val pipeline =
            RootfsInstallationPipeline(
                extractor = RootfsExtractor { _, _, _ -> error("must not extract") },
                configurator = RootfsConfigurator { error("must not configure") },
                healthCheck = RootfsHealthCheck { error("must not check") },
            )

        val result = pipeline.execute(fixture.request)

        assertTrue(result.reusedInstallation)
        assertEquals("user data", File(active, "keep").readText())
        assertFalse(fixture.archive.exists())
    }

    @Test
    fun `existing unmarked directory fails without deleting it`() {
        val fixture = fixture()
        val active = File(fixture.rootfs, "jammy").apply { mkdirs() }
        File(active, "unknown").writeText("preserve")
        val pipeline =
            RootfsInstallationPipeline(
                extractor = RootfsExtractor { _, _, _ -> },
                configurator = RootfsConfigurator {},
                healthCheck = RootfsHealthCheck {},
            )

        val failure = runCatching { pipeline.execute(fixture.request) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("preserve", File(active, "unknown").readText())
        assertTrue(fixture.archive.isFile)
    }

    @Test
    fun `unsafe catalogue name is rejected before filesystem mutation`() {
        val fixture = fixture()
        val request = fixture.request.copy(installationName = "../escape")
        val pipeline =
            RootfsInstallationPipeline(
                extractor = RootfsExtractor { _, _, _ -> },
                configurator = RootfsConfigurator {},
                healthCheck = RootfsHealthCheck {},
            )

        val failure = runCatching { pipeline.execute(request) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertFalse(File(fixture.directory, "escape").exists())
    }

    @Test
    fun `android compatibility configuration creates missing proc files`() {
        val rootfs = Files.createTempDirectory("udroid-configurator-test").toFile()

        AndroidRootfsConfigurator().configure(rootfs)

        assertTrue(File(rootfs, "proc/.version").isFile)
        assertTrue(File(rootfs, "proc/.uptime").isFile)
        assertTrue(File(rootfs, "etc/hosts").readText().contains("localhost"))
        assertTrue(File(rootfs, "etc/profile.d/udroid.sh").canExecute())
    }

    private fun fixture(): Fixture {
        val directory = Files.createTempDirectory("udroid-rootfs-test").toFile()
        val archive = File(directory, "jammy.tar.gz").apply { writeText("archive") }
        val rootfs = File(directory, "rootfs")
        return Fixture(
            directory = directory,
            archive = archive,
            rootfs = rootfs,
            request =
                RootfsInstallRequest(
                    archive = archive,
                    rootfsDirectory = rootfs,
                    installationName = "jammy",
                    operationId = "1234-test",
                ),
        )
    }

    private data class Fixture(
        val directory: File,
        val archive: File,
        val rootfs: File,
        val request: RootfsInstallRequest,
    )
}
