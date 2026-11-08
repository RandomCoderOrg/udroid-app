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
    fun `android compatibility configuration creates guest configuration`() {
        val rootfs = Files.createTempDirectory("udroid-configurator-test").toFile()

        AndroidRootfsConfigurator().configure(rootfs)

        assertTrue(File(rootfs, "etc/hosts").readText().contains("localhost"))
        val profile = File(rootfs, "etc/profile.d/udroid.sh")
        val profileContents = profile.readText()
        assertTrue(profile.canExecute())
        assertTrue(profileContents.contains("udroid.bash"))
        val bashIntegration = File(rootfs, "etc/udroid/shell-integration/udroid.bash").readText()
        assertTrue(bashIntegration.contains("133;A"))
        assertTrue(bashIntegration.contains("133;B"))
        assertTrue(bashIntegration.contains("133;C"))
        assertTrue(bashIntegration.contains("133;D"))
        assertTrue(bashIntegration.contains("kitty-shell-cwd://localhost"))
        assertTrue(bashIntegration.contains("//%/%25"))
    }

    @Test
    fun `available shells receive native command lifecycle hooks`() {
        val rootfs = Files.createTempDirectory("udroid-shell-configurator-test").toFile()
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/zsh").createNewFile()
        File(rootfs, "usr/bin/fish").createNewFile()

        val configurator = AndroidRootfsConfigurator()
        configurator.configure(rootfs)
        configurator.configureShellProfile(rootfs)

        val zshIntegration = File(rootfs, "etc/udroid/shell-integration/udroid.zsh").readText()
        assertTrue(zshIntegration.contains("add-zsh-hook precmd"))
        assertTrue(zshIntegration.contains("add-zsh-hook preexec"))
        assertTrue(zshIntegration.contains("add-zsh-hook chpwd"))
        assertTrue(zshIntegration.contains("kitty-shell-cwd://localhost"))
        assertTrue(zshIntegration.contains("//%/%25"))
        assertEquals(
            1,
            File(rootfs, "etc/zsh/zshrc")
                .readLines()
                .count { it.contains("udroid.zsh") },
        )
        val fishIntegration = File(rootfs, "etc/fish/conf.d/udroid.fish").readText()
        assertTrue(fishIntegration.contains("fish_prompt"))
        assertTrue(fishIntegration.contains("fish_preexec"))
        assertTrue(fishIntegration.contains("fish_postexec"))
        assertTrue(fishIntegration.contains("--on-variable PWD"))
        assertTrue(fishIntegration.contains("file://localhost"))
    }

    @Test
    fun `android compatibility configuration never follows guest symlinks`() {
        val rootfs = Files.createTempDirectory("udroid-configurator-symlink").toFile()
        val outside = Files.createTempDirectory("udroid-configurator-outside").toFile()
        val profileDirectory = File(rootfs, "etc/profile.d")
        try {
            File(rootfs, "etc").mkdirs()
            val outsideHosts = File(outside, "hosts").apply { writeText("keep me") }
            Files.createSymbolicLink(File(rootfs, "etc/hosts").toPath(), outsideHosts.toPath())

            AndroidRootfsConfigurator().configure(rootfs)

            assertEquals("keep me", outsideHosts.readText())
            assertFalse(Files.isSymbolicLink(File(rootfs, "etc/hosts").toPath()))

            profileDirectory.deleteRecursively()
            Files.createSymbolicLink(profileDirectory.toPath(), outside.toPath())
            val failure = runCatching { AndroidRootfsConfigurator().configure(rootfs) }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertFalse(File(outside, "udroid.sh").exists())
        } finally {
            if (Files.isSymbolicLink(profileDirectory.toPath())) Files.delete(profileDirectory.toPath())
            rootfs.deleteRecursively()
            outside.deleteRecursively()
        }
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
