package org.randomcoder.udroid.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalModifierStateTest {
    @Test
    fun modifierTapAppliesToOnlyTheNextKey() {
        val state = TerminalModifierState()
        val sent = mutableListOf<Pair<Int, Boolean>>()

        state.onKeyEvent(0, KeyEvent.KEYCODE_CTRL_LEFT, true, 0) { key, down -> sent += key to down }
        state.onKeyEvent(0, KeyEvent.KEYCODE_CTRL_LEFT, false, 50) { key, down -> sent += key to down }
        assertTrue(state.isControlActive())

        state.onKeyEvent(1, KeyEvent.KEYCODE_C, true, 60) { key, down -> sent += key to down }
        state.onKeyEvent(1, KeyEvent.KEYCODE_C, false, 70) { key, down -> sent += key to down }

        assertFalse(state.isControlActive())
        assertEquals(listOf(KeyEvent.KEYCODE_C to true, KeyEvent.KEYCODE_C to false), sent)
    }

    @Test
    fun heldModifierSurvivesChordUntilReleased() {
        val state = TerminalModifierState()

        state.onKeyEvent(0, KeyEvent.KEYCODE_ALT_LEFT, true, 0) { _, _ -> }
        state.onKeyEvent(1, KeyEvent.KEYCODE_B, true, 600) { _, _ -> }
        state.onKeyEvent(1, KeyEvent.KEYCODE_B, false, 610) { _, _ -> }
        assertTrue(state.isAltActive())

        state.onKeyEvent(0, KeyEvent.KEYCODE_ALT_LEFT, false, 620) { _, _ -> }
        assertFalse(state.isAltActive())
    }

    @Test
    fun doubleTapLocksAndNextTapUnlocksModifier() {
        val state = TerminalModifierState(doubleTapMillis = 300)

        state.onKeyEvent(0, KeyEvent.KEYCODE_SHIFT_LEFT, true, 0) { _, _ -> }
        state.onKeyEvent(0, KeyEvent.KEYCODE_SHIFT_LEFT, false, 40) { _, _ -> }
        state.onKeyEvent(0, KeyEvent.KEYCODE_SHIFT_LEFT, true, 200) { _, _ -> }
        state.onKeyEvent(0, KeyEvent.KEYCODE_SHIFT_LEFT, false, 240) { _, _ -> }
        assertTrue(state.isShiftActive())

        state.onKeyEvent(0, KeyEvent.KEYCODE_SHIFT_LEFT, true, 400) { _, _ -> }
        state.onKeyEvent(0, KeyEvent.KEYCODE_SHIFT_LEFT, false, 440) { _, _ -> }
        assertFalse(state.isShiftActive())
    }
}
