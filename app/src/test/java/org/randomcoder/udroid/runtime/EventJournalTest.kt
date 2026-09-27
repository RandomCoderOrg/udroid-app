package org.randomcoder.udroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class EventJournalTest {
    @Test
    fun commandFormattingPreservesEveryArgumentAndIsShellSafe() {
        assertEquals(
            "'proot' '/bin/sh' '-lc' 'echo '\"'\"'hello world'\"'\"'' 'API_TOKEN=value echo ready' '--password' 'value'",
            formatCommandForLog(
                listOf(
                    "proot",
                    "/bin/sh",
                    "-lc",
                    "echo 'hello world'",
                    "API_TOKEN=value echo ready",
                    "--password",
                    "value",
                ),
            ),
        )
    }
}
