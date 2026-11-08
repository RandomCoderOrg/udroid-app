package org.randomcoder.udroid.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalLinkTest {
    @Test
    fun onlyWebAndMailLinksCanLeaveTheTerminal() {
        assertTrue(terminalLinkCanOpen("https"))
        assertTrue(terminalLinkCanOpen("HTTP"))
        assertTrue(terminalLinkCanOpen("mailto"))
        assertFalse(terminalLinkCanOpen("intent"))
        assertFalse(terminalLinkCanOpen("javascript"))
        assertFalse(terminalLinkCanOpen(null))
    }
}
