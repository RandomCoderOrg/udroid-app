package org.randomcoder.udroid.runtime

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.randomcoder.udroid.input.ExtraKeyBinding

class TerminalExtraKeysTest {
    @Test
    fun termuxNamesRoundTripSupportedKeys() {
        val keys =
            listOf(
                KeyEvent.KEYCODE_CTRL_LEFT,
                KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_A,
                KeyEvent.KEYCODE_7,
                KeyEvent.KEYCODE_F12,
                KeyEvent.KEYCODE_SLASH,
            )

        keys.forEach { keyCode ->
            assertEquals(keyCode, TerminalExtraKeys.keyCode(TerminalExtraKeys.termuxName(keyCode)))
        }
    }

    @Test
    fun invalidRowsFallBackWithoutKeepingUnsupportedButtons() {
        assertEquals(TerminalExtraKeys.defaults, TerminalExtraKeys.normalized(emptyList()))
        assertEquals(
            listOf(listOf(TerminalExtraKeys.binding(KeyEvent.KEYCODE_TAB))),
            TerminalExtraKeys.normalized(listOf(listOf(ExtraKeyBinding(""), TerminalExtraKeys.binding(KeyEvent.KEYCODE_TAB)))),
        )
    }

    @Test
    fun layoutShapeIsNotCapped() {
        val rows = List(6) { List(14) { TerminalExtraKeys.binding(KeyEvent.KEYCODE_TAB) } }

        assertEquals(rows, TerminalExtraKeys.normalized(rows))
    }
}
