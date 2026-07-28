package org.randomcoder.udroid.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.randomcoder.udroid.oci.OciInstallEvent
import org.randomcoder.udroid.oci.OciInstallStage

class OciInstallProgressMappingTest {
    @Test
    fun `foreground progress reserves distinct ranges for expensive phases`() {
        assertEquals(
            5,
            OciInstallProgressMapping.percentage(
                OciInstallEvent(OciInstallStage.DOWNLOADING, "blob", 0, 100),
            ),
        )
        assertEquals(
            45,
            OciInstallProgressMapping.percentage(
                OciInstallEvent(OciInstallStage.DOWNLOADING, "blob", 100, 100),
            ),
        )
        assertEquals(
            55,
            OciInstallProgressMapping.percentage(
                OciInstallEvent(OciInstallStage.ASSEMBLING, "layer", 0, 100),
            ),
        )
        assertEquals(
            85,
            OciInstallProgressMapping.percentage(
                OciInstallEvent(OciInstallStage.ASSEMBLING, "layer", 100, 100),
            ),
        )
        assertEquals(
            100,
            OciInstallProgressMapping.percentage(
                OciInstallEvent(OciInstallStage.READY, "ready"),
            ),
        )
    }

    @Test
    fun `per blob verification remains in the monotonic acquisition segment`() {
        val download =
            OciInstallProgressMapping.map(
                OciInstallEvent(OciInstallStage.DOWNLOADING, "layer", 55, 100),
            )
        val verify =
            OciInstallProgressMapping.map(
                OciInstallEvent(OciInstallStage.VERIFYING, "layer", 55, 100),
            )

        assertEquals(InstallStage.DOWNLOADING, download.stage)
        assertEquals(download, verify)
    }

    @Test
    fun `invalid byte counters cannot escape notification bounds`() {
        val values =
            listOf(
                OciInstallProgressMapping.percentage(
                    OciInstallEvent(OciInstallStage.DOWNLOADING, "blob", -1, 100),
                ),
                OciInstallProgressMapping.percentage(
                    OciInstallEvent(OciInstallStage.VERIFYING, "blob", 200, 100),
                ),
                OciInstallProgressMapping.percentage(
                    OciInstallEvent(OciInstallStage.ASSEMBLING, "layer", 1, -1),
                ),
            )

        assertTrue(values.all { it in 0..100 })
    }
}
