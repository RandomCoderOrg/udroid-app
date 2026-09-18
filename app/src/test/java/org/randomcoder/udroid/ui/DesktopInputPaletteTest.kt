package org.randomcoder.udroid.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopInputPaletteTest {
    @Test
    fun positionIsClampedInsideTheDesktop() {
        assertEquals(0, clampInputPaletteCoordinate(-20, 300))
        assertEquals(180, clampInputPaletteCoordinate(180, 300))
        assertEquals(300, clampInputPaletteCoordinate(420, 300))
        assertEquals(0, clampInputPaletteCoordinate(20, -1))
    }

    @Test
    fun mouseButtonTapHoldsSwitchesAndReleases() {
        assertEquals(1, nextHeldMouseButton(null, 1))
        assertEquals(3, nextHeldMouseButton(1, 3))
        assertEquals(null, nextHeldMouseButton(3, 3))
    }

    @Test
    fun dragAccumulatesEveryMotionDelta() {
        val firstMove = accumulateInputPaletteDrag(100f, 8f, 300)
        val secondMove = accumulateInputPaletteDrag(firstMove, 7f, 300)

        assertEquals(115f, secondMove)
        assertEquals(300f, accumulateInputPaletteDrag(secondMove, 500f, 300))
    }
}
