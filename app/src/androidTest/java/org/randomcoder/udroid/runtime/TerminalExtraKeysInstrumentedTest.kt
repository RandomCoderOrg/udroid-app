package org.randomcoder.udroid.runtime

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.randomcoder.udroid.input.ExtraKeyBinding

@RunWith(AndroidJUnit4::class)
class TerminalExtraKeysInstrumentedTest {
    @Test
    fun importsTermuxJsonSyntaxAndReportsDroppedPopups() {
        val imported =
            TerminalExtraKeys.parseTermuxJson(
                "[['ESC',{key:'-',popup:'|'}],['CTRL','LEFT']]",
            )

        assertEquals(
            listOf(
                listOf(TerminalExtraKeys.binding(KeyEvent.KEYCODE_ESCAPE), TerminalExtraKeys.binding(KeyEvent.KEYCODE_MINUS)),
                listOf(TerminalExtraKeys.binding(KeyEvent.KEYCODE_CTRL_LEFT), TerminalExtraKeys.binding(KeyEvent.KEYCODE_DPAD_LEFT)),
            ),
            imported.rows,
        )
        assertEquals(1, imported.ignoredPopups)
    }

    @Test
    fun importsAndExportsMacrosAndDisplayLabels() {
        val imported =
            TerminalExtraKeys.parseTermuxJson(
                "[[{macro:'CTRL f d',display:'tmux 🚪'},{key:'ESC',display:'🚪'}]]",
            )

        assertEquals(
            listOf(
                listOf(
                    ExtraKeyBinding("CTRL f d", macro = true, display = "tmux 🚪"),
                    ExtraKeyBinding("ESC", display = "🚪"),
                ),
            ),
            imported.rows,
        )
        assertEquals(imported.rows, TerminalExtraKeys.parseTermuxJson(TerminalExtraKeys.toTermuxJson(imported.rows)).rows)
    }
}
