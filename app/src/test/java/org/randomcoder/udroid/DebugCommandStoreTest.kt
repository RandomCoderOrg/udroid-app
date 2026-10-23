package org.randomcoder.udroid

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugCommandStoreTest {
    @Test
    fun acceptsOnlyFixedSupervisorCommands() {
        assertTrue(DebugCommandStore.isSupported("start-desktop"))
        assertTrue(DebugCommandStore.isSupported("stop-terminal"))
        assertFalse(DebugCommandStore.isSupported("run-shell"))
        assertFalse(DebugCommandStore.isSupported(""))
    }
}
