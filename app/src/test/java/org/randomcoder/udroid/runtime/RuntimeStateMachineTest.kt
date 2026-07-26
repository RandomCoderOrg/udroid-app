package org.randomcoder.udroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStateMachineTest {
    @Test
    fun `interrupted stop is reconciled when the app process returns`() {
        val recovered =
            RuntimeStateMachine.recoverAfterApplicationCreate(
                RuntimeSnapshot(
                    phase = RuntimePhase.STOPPING,
                    desiredRunning = false,
                    childPid = 42,
                    heartbeatSequence = 9,
                ),
            )

        assertEquals(RuntimePhase.STOPPED, recovered.phase)
        assertFalse(recovered.desiredRunning)
        assertNull(recovered.childPid)
        assertNull(recovered.heartbeatSequence)
    }

    @Test
    fun `desired running state survives app recreation for sticky recovery`() {
        val running =
            RuntimeSnapshot(
                phase = RuntimePhase.RUNNING,
                desiredRunning = true,
                childPid = 42,
                desktop =
                    DesktopSessionSnapshot(
                        phase = DesktopSessionPhase.RUNNING,
                        desiredRunning = true,
                        rootfsName = "ubuntu",
                        environmentId = "xfce",
                    ),
            )

        val recovered = RuntimeStateMachine.recoverAfterApplicationCreate(running)

        assertEquals(RuntimePhase.RUNNING, recovered.phase)
        assertTrue(recovered.desiredRunning)
        assertEquals(DesktopSessionPhase.STOPPED, recovered.desktop.phase)
        assertFalse(recovered.desktop.desiredRunning)
        assertEquals(
            "Previous desktop session ended with the app process",
            recovered.desktop.message,
        )
    }

    @Test
    fun `unexpected child exit is visible as a crash`() {
        val crashed =
            RuntimeStateMachine.afterProcessExit(
                RuntimeSnapshot(
                    phase = RuntimePhase.RUNNING,
                    desiredRunning = true,
                    childPid = 42,
                ),
                exitCode = 7,
            )

        assertEquals(RuntimePhase.CRASHED, crashed.phase)
        assertTrue(crashed.desiredRunning)
        assertEquals("Runtime exited unexpectedly with code 7", crashed.message)
        assertNull(crashed.childPid)
    }

    @Test
    fun `requested stop becomes clean stopped state`() {
        val stopped =
            RuntimeStateMachine.afterProcessExit(
                RuntimeSnapshot(
                    phase = RuntimePhase.STOPPING,
                    desiredRunning = false,
                    childPid = 42,
                    heartbeatSequence = 12,
                ),
                exitCode = 0,
            )

        assertEquals(RuntimePhase.STOPPED, stopped.phase)
        assertFalse(stopped.desiredRunning)
        assertEquals("uDroid runtime stopped cleanly", stopped.message)
        assertNull(stopped.childPid)
        assertNull(stopped.heartbeatSequence)
        assertEquals(DesktopSessionPhase.STOPPED, stopped.desktop.phase)
        assertEquals("Desktop stopped with the Linux runtime", stopped.desktop.message)
    }
}
