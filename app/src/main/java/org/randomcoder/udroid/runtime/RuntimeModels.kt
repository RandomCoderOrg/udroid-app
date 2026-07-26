package org.randomcoder.udroid.runtime

enum class RuntimePhase {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    CRASHED,
}

data class RuntimeSnapshot(
    val phase: RuntimePhase = RuntimePhase.STOPPED,
    val desiredRunning: Boolean = false,
    val bootId: String? = null,
    val message: String = "Runtime has not been started",
    val childPid: Long? = null,
    val heartbeatSequence: Long? = null,
    val rootfsName: String? = null,
    val desktop: DesktopSessionSnapshot = DesktopSessionSnapshot(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

enum class CapabilityStatus {
    PASS,
    FAIL,
    INFO,
}

data class CapabilityResult(
    val name: String,
    val status: CapabilityStatus,
    val detail: String,
    val required: Boolean,
)
