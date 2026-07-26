package org.randomcoder.udroid.runtime

object RuntimeStateMachine {
    fun recoverAfterApplicationCreate(snapshot: RuntimeSnapshot): RuntimeSnapshot {
        val runtime =
            if (snapshot.desiredRunning || snapshot.phase == RuntimePhase.STOPPED) {
                snapshot
            } else {
                snapshot.copy(
                    phase = RuntimePhase.STOPPED,
                    message = "Recovered a previously interrupted stop",
                    childPid = null,
                    heartbeatSequence = null,
                )
            }
        val desktop =
            if (runtime.desktop.phase == DesktopSessionPhase.STOPPED) {
                runtime.desktop
            } else {
                DesktopSessionSnapshot(
                    message = "Previous desktop session ended with the app process",
                )
            }
        return runtime.copy(desktop = desktop)
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
            rootfsName = if (expected) null else snapshot.rootfsName,
            desktop =
                DesktopSessionSnapshot(
                    message =
                        if (expected) {
                            "Desktop stopped with the Linux runtime"
                        } else {
                            "Desktop stopped because the Linux runtime crashed"
                        },
                ),
        )
    }
}
