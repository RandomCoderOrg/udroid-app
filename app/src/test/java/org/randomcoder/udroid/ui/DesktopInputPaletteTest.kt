package org.randomcoder.udroid.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopInputPaletteTest {
    @Test
    fun mouseButtonTapHoldsSwitchesAndReleases() {
        assertEquals(1, nextLatchedMouseButton(0, 1))
        assertEquals(3, nextLatchedMouseButton(1, 3))
        assertEquals(0, nextLatchedMouseButton(3, 3))
    }
}
