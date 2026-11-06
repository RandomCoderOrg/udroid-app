package org.randomcoder.udroid.ui

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
}
