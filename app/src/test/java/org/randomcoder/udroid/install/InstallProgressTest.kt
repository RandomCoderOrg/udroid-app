package org.randomcoder.udroid.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.randomcoder.udroid.catalog.DistroVariant

class InstallProgressTest {
    private val distro =
        DistroVariant(
            suite = "jammy",
            variant = "raw",
            internalName = "udroid-jammy-raw",
            friendlyName = "Ubuntu 22.04 LTS - Jammy (raw)",
            architecture = "aarch64",
            downloadUrl = "https://example.test/jammy.tar.gz",
            sha256 = "abc",
        )

    @Test
    fun `download progress is mapped into its weighted overall segment`() {
        val progress =
            InstallProgress(
                distro = distro,
                stage = InstallStage.DOWNLOADING,
                stageProgress = 0.5f,
                currentDetail = "Downloading",
                terminalLines = emptyList(),
                previewOnly = true,
            )

        assertEquals(0.25f, progress.overallProgress, 0.0001f)
        assertEquals(25, progress.percentage)
    }

    @Test
    fun `preview reaches complete with a terminal event for every transition`() {
        val steps = InstallationUxPreview.steps(distro)

        assertEquals(InstallStage.COMPLETE, steps.last().stage)
        assertTrue(steps.all { it.terminalLine.isNotBlank() })
        assertTrue(steps.zipWithNext().all { (left, right) ->
            val leftOverall =
                InstallProgress(distro, left.stage, left.stageProgress, "", emptyList(), true)
                    .overallProgress
            val rightOverall =
                InstallProgress(distro, right.stage, right.stageProgress, "", emptyList(), true)
                    .overallProgress
            rightOverall >= leftOverall
        })
    }
}
