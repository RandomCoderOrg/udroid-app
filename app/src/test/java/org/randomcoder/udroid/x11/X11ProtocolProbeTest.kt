package org.randomcoder.udroid.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class X11ProtocolProbeTest {
    @Test
    fun `parses a successful X11 setup header`() {
        val result =
            X11ProtocolProbe.parseSetupHeader(
                byteArrayOf(1, 0, 11, 0, 0, 0, 30, 0),
            )

        assertEquals(X11ProtocolProbe.Result.Ready(11, 0), result)
    }

    @Test
    fun `reports authentication instead of treating socket accept as ready`() {
        val result =
            X11ProtocolProbe.parseSetupHeader(
                byteArrayOf(2, 0, 11, 0, 0, 0, 0, 0),
            )

        assertTrue(result is X11ProtocolProbe.Result.NotReady)
        assertEquals(
            "X11 server requested authentication",
            (result as X11ProtocolProbe.Result.NotReady).reason,
        )
    }
}
