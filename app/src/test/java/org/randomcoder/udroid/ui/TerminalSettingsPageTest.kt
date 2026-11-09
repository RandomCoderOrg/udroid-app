package org.randomcoder.udroid.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.randomcoder.udroid.runtime.TerminalProfiles

class TerminalSettingsPageTest {
    @Test
    fun profileSearchMatchesNamesCaseInsensitively() {
        assertEquals(
            listOf("Catppuccin Mocha"),
            filterTerminalProfiles(TerminalProfiles.builtIns, "  CATPPUCCIN ").map { it.name },
        )
    }

    @Test
    fun blankProfileSearchKeepsEveryProfile() {
        assertEquals(
            TerminalProfiles.builtIns,
            filterTerminalProfiles(TerminalProfiles.builtIns, "  "),
        )
    }

    @Test
    fun removingOnlyKeyInRowRemovesThatRow() {
        assertEquals(
            listOf(listOf(KeyEvent.KEYCODE_TAB)),
            removeTerminalExtraKey(
                listOf(listOf(KeyEvent.KEYCODE_ESCAPE), listOf(KeyEvent.KEYCODE_TAB)),
                rowIndex = 0,
                columnIndex = 0,
            ),
        )
    }

    @Test
    fun finalKeyAndFinalRowCannotBeRemoved() {
        val rows = listOf(listOf(KeyEvent.KEYCODE_ESCAPE))

        assertEquals(rows, removeTerminalExtraKey(rows, rowIndex = 0, columnIndex = 0))
        assertEquals(rows, removeTerminalExtraKeyRow(rows, rowIndex = 0))
    }

    @Test
    fun movingKeyReordersWithinRow() {
        assertEquals(
            listOf(listOf("B", "C", "A")),
            moveTerminalExtraKey(
                rows = listOf(listOf("A", "B", "C")),
                sourceRow = 0,
                sourceColumn = 0,
                targetRow = 0,
                targetColumn = 2,
                after = true,
            ),
        )
    }

    @Test
    fun movingKeyBeforeTargetReordersWithinRow() {
        assertEquals(
            listOf(listOf("C", "A", "B")),
            moveTerminalExtraKey(
                rows = listOf(listOf("A", "B", "C")),
                sourceRow = 0,
                sourceColumn = 2,
                targetRow = 0,
                targetColumn = 0,
                after = false,
            ),
        )
    }

    @Test
    fun movingOnlyKeyToAnotherRowRemovesEmptyRow() {
        assertEquals(
            listOf(listOf("B", "A", "C")),
            moveTerminalExtraKey(
                rows = listOf(listOf("A"), listOf("B", "C")),
                sourceRow = 0,
                sourceColumn = 0,
                targetRow = 1,
                targetColumn = 0,
                after = true,
            ),
        )
    }
}
