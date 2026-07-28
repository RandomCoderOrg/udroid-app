package org.randomcoder.udroid.oci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.randomcoder.udroid.install.ByteProgress

class OciImagePullerProgressTest {
    @Test
    fun `per blob counters become monotonic image counters`() {
        val config =
            ByteProgress(10, 10, resumed = false)
                .toAggregateOciProgress(
                    completedBeforeBlob = 0,
                    aggregateTotalBytes = 110,
                    descriptorSize = 10,
                )
        val layerStart =
            ByteProgress(5, 100, resumed = true)
                .toAggregateOciProgress(
                    completedBeforeBlob = 10,
                    aggregateTotalBytes = 110,
                    descriptorSize = 100,
                )
        val layerEnd =
            ByteProgress(100, 100, resumed = true)
                .toAggregateOciProgress(
                    completedBeforeBlob = 10,
                    aggregateTotalBytes = 110,
                    descriptorSize = 100,
                )

        assertEquals(
            listOf(10L, 15L, 110L),
            listOf(config.completedBytes, layerStart.completedBytes, layerEnd.completedBytes),
        )
        assertTrue(listOf(config, layerStart, layerEnd).all { it.totalBytes == 110L })
        assertTrue(layerStart.resumed)
    }
}
