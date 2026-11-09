package org.randomcoder.udroid.x11

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.randomcoder.udroid.input.ExtraKeyBinding

class OverlayKeyboardTest {
    @Test
    fun bindingsRejectUnsupportedOrMissingKeys() {
        val saved = listOf(OverlayKeyboard.binding(KeyEvent.KEYCODE_F5), OverlayKeyboard.binding(OverlayKeyboard.MOUSE_LEFT), ExtraKeyBinding(""))
        val normalized = OverlayKeyboard.normalized(saved)
        assertEquals(OverlayKeyboard.defaults.size, normalized.size)
        assertEquals(OverlayKeyboard.binding(KeyEvent.KEYCODE_F5), normalized[0])
        assertEquals(OverlayKeyboard.binding(OverlayKeyboard.MOUSE_LEFT), normalized[1])
        assertEquals(OverlayKeyboard.defaults[2], normalized[2])
    }

    @Test
    fun displayButtonsKeepValidKeysAndClampTheirPositions() {
        val buttons = OverlayKeyboard.normalizedDisplayKeys(
            listOf(
                DisplayKey(OverlayKeyboard.binding(KeyEvent.KEYCODE_W), -1f, 2f),
                DisplayKey(ExtraKeyBinding(""), 0.5f, 0.5f),
                DisplayKey(OverlayKeyboard.binding(KeyEvent.KEYCODE_A), Float.NaN, 0.5f),
            ),
        )
        assertEquals(listOf(DisplayKey(OverlayKeyboard.binding(KeyEvent.KEYCODE_W), 0f, 1f)), buttons)
    }

    @Test
    fun customDisplayDoesNotChangeTheBoundAction() {
        val binding = ExtraKeyBinding("CTRL f d", macro = true, display = "tmux 🚪")

        assertEquals("tmux 🚪", OverlayKeyboard.label(binding))
        assertEquals(listOf("CTRL", "f", "d"), binding.tokens())
    }

    @Test
    fun keyboardRowsKeepCustomLayoutAndDropBlankBindings() {
        assertEquals(
            listOf(
                listOf(ExtraKeyBinding("CTRL"), ExtraKeyBinding("A")),
                listOf(ExtraKeyBinding("ALT")),
            ),
            OverlayKeyboard.normalizedRows(
                listOf(
                    listOf(ExtraKeyBinding("CTRL"), ExtraKeyBinding(""), ExtraKeyBinding("A")),
                    listOf(ExtraKeyBinding("ALT")),
                ),
            ),
        )
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
