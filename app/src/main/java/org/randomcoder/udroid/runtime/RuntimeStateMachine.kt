package org.randomcoder.udroid.runtime

object RuntimeStateMachine {
    fun recoverAfterApplicationCreate(snapshot: RuntimeSnapshot): RuntimeSnapshot {
        if (snapshot.desiredRunning || snapshot.phase == RuntimePhase.STOPPED) {
            return snapshot
        }
        return snapshot.copy(
            phase = RuntimePhase.STOPPED,
            message = "Recovered a previously interrupted stop",
            childPid = null,
            heartbeatSequence = null,
        )
    }

    fun afterProcessExit(
        snapshot: RuntimeSnapshot,
        exitCode: Int,
    ): RuntimeSnapshot {
        val expected = !snapshot.desiredRunning || snapshot.phase == RuntimePhase.STOPPING
        return snapshot.copy(
            phase = if (expected) RuntimePhase.STOPPED else RuntimePhase.CRASHED,
            desiredRunning = if (expected) false else snapshot.desiredRunning,
            message =
                if (expected) {
                    "uDroid runtime stopped cleanly"
                } else {
                    "Runtime exited unexpectedly with code $exitCode"
                },
            childPid = null,
            heartbeatSequence = null,
        )
    }
}
