package org.randomcoder.udroid.x11

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.randomcoder.udroid.input.ExtraKeyBinding

@RunWith(AndroidJUnit4::class)
class X11SettingsTest {
    @Test
    fun savedFlatKeyboardLayoutMigratesToSixKeyRows() {
        val raw = """["CTRL","ALT","A","B","C","D","LEFT"]"""

        assertEquals(listOf(6, 1), parseOverlayKeyRows(raw).map(List<*>::size))
    }

    @Test
    fun savedMatrixKeepsMacrosAndCustomLabels() {
        val raw = """[[{"macro":"CTRL f d","display":"tmux"}],["ESC"]]"""

        assertEquals(
            listOf(
                listOf(ExtraKeyBinding("CTRL f d", macro = true, display = "tmux")),
                listOf(ExtraKeyBinding("ESC")),
            ),
            parseOverlayKeyRows(raw),
        )
    }
}
