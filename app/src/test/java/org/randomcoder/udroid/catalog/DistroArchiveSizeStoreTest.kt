package org.randomcoder.udroid.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DistroArchiveSizeStoreTest {
    @Test
    fun `size headers prefer the complete ranged total`() {
        assertEquals(172L, totalBytesFromHeaders(206, 1L, "bytes 0-0/172"))
        assertEquals(172L, totalBytesFromHeaders(200, 172L, null))
        assertNull(totalBytesFromHeaders(404, 172L, null))
    }
}
