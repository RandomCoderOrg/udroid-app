package org.randomcoder.udroid.x11

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayKeyboardTest {
    @Test
    fun bindingsRejectUnsupportedOrMissingKeys() {
        val saved = listOf(KeyEvent.KEYCODE_F5, OverlayKeyboard.MOUSE_LEFT, -100)
        val normalized = OverlayKeyboard.normalized(saved)
        assertEquals(OverlayKeyboard.defaults.size, normalized.size)
        assertEquals(KeyEvent.KEYCODE_F5, normalized[0])
        assertEquals(OverlayKeyboard.MOUSE_LEFT, normalized[1])
        assertEquals(OverlayKeyboard.defaults[2], normalized[2])
    }

    @Test
    fun modifierClassificationDoesNotIncludeOrdinaryKeys() {
        assertTrue(OverlayKeyboard.isModifier(KeyEvent.KEYCODE_CTRL_LEFT))
        assertTrue(OverlayKeyboard.isModifier(KeyEvent.KEYCODE_META_RIGHT))
        assertFalse(OverlayKeyboard.isModifier(KeyEvent.KEYCODE_W))
    }

    @Test
    fun duplicateBindingsReleaseOnlyAfterLastOwner() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val keys = OverlayKeyState { key, down -> events += key to down }
        keys.press(1, KeyEvent.KEYCODE_W)
        keys.press(2, KeyEvent.KEYCODE_W)
        keys.release(1)
        assertEquals(listOf(KeyEvent.KEYCODE_W to true), events)
        keys.release(2)
        assertEquals(
            listOf(KeyEvent.KEYCODE_W to true, KeyEvent.KEYCODE_W to false),
            events,
        )
    }

    @Test
    fun tappingModifierCanLatchWithoutAReleaseBetweenOwners() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val keys = OverlayKeyState { key, down -> events += key to down }
        keys.press(0, KeyEvent.KEYCODE_CTRL_LEFT)
        keys.press(1, KeyEvent.KEYCODE_CTRL_LEFT)
        keys.release(0)
        assertEquals(listOf(KeyEvent.KEYCODE_CTRL_LEFT to true), events)
        keys.release(1)
        assertEquals(
            listOf(KeyEvent.KEYCODE_CTRL_LEFT to true, KeyEvent.KEYCODE_CTRL_LEFT to false),
            events,
        )
    }

    @Test
    fun releaseAllEndsHeldChordsExactlyOnce() {
        val events = mutableListOf<Pair<Int, Boolean>>()
        val keys = OverlayKeyState { key, down -> events += key to down }
        keys.press(0, KeyEvent.KEYCODE_CTRL_LEFT)
        keys.press(1, KeyEvent.KEYCODE_W)
        keys.releaseAll()
        keys.releaseAll()
        assertEquals(4, events.size)
        assertEquals(1, events.count { it == (KeyEvent.KEYCODE_CTRL_LEFT to false) })
        assertEquals(1, events.count { it == (KeyEvent.KEYCODE_W to false) })
    }
}
