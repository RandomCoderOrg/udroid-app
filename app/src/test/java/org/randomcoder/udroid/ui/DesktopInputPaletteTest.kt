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
    fun onlyAnUnchordedModifierTapCanEnterDoubleTapSequence() {
        assertTrue(isQuickModifierTap(true, true, false, 50, 500))
        assertFalse(isQuickModifierTap(true, true, true, 50, 500))
        assertFalse(isQuickModifierTap(false, true, false, 50, 500))
        assertFalse(isQuickModifierTap(true, false, false, 50, 500))
        assertFalse(isQuickModifierTap(true, true, false, 1_000, 500))
    }

    @Test
    fun modifierDoubleTapLocksOnlyConsecutiveQuickTapsOnSameButton() {
        val taps = ModifierDoubleTap(300)
        assertFalse(taps.tapped(0, 1_000))
        assertTrue(taps.tapped(0, 1_250))
        assertFalse(taps.tapped(0, 1_350))
        taps.cancelUnless(1)
        assertFalse(taps.tapped(0, 1_500))
        assertFalse(taps.tapped(0, 2_000))
        assertTrue(taps.tapped(0, 2_300))
        taps.clear()
        assertFalse(taps.tapped(0, 2_400))
    }
}
