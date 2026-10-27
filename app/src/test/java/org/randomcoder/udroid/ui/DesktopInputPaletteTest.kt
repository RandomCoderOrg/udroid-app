package org.randomcoder.udroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopInputPaletteTest {
    @Test
    fun mouseButtonTapHoldsSwitchesAndReleases() {
        assertEquals(1, nextLatchedMouseButton(0, 1))
        assertEquals(3, nextLatchedMouseButton(1, 3))
        assertEquals(0, nextLatchedMouseButton(3, 3))
    }

    @Test
    fun modifierTapLatchesButChordHoldAndCancelDoNot() {
        assertTrue(shouldLatchModifier(true, true, false, 50, 500))
        assertFalse(shouldLatchModifier(true, true, true, 50, 500))
        assertFalse(shouldLatchModifier(false, true, false, 50, 500))
        assertFalse(shouldLatchModifier(true, false, false, 50, 500))
        assertFalse(shouldLatchModifier(true, true, false, 1_000, 500))
    }
}
