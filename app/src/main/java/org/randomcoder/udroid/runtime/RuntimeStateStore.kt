package org.randomcoder.udroid.runtime

import android.content.Context

class RuntimeStateStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("runtime-supervisor-state", Context.MODE_PRIVATE)

    @Synchronized
    fun current(): RuntimeSnapshot {
        val phase =
            runCatching {
                RuntimePhase.valueOf(
                    preferences.getString(KEY_PHASE, RuntimePhase.STOPPED.name)
                        ?: RuntimePhase.STOPPED.name,
                )
            }.getOrDefault(RuntimePhase.STOPPED)

        return RuntimeSnapshot(
            phase = phase,
            desiredRunning = preferences.getBoolean(KEY_DESIRED_RUNNING, false),
            bootId = preferences.getString(KEY_BOOT_ID, null),
            message =
                preferences.getString(KEY_MESSAGE, "Runtime has not been started")
                    ?: "Runtime has not been started",
            childPid = preferences.getLongOrNull(KEY_CHILD_PID),
            heartbeatSequence = preferences.getLongOrNull(KEY_HEARTBEAT_SEQUENCE),
            updatedAtEpochMs =
                preferences.getLong(KEY_UPDATED_AT, System.currentTimeMillis()),
        )
    }

    @Synchronized
    fun update(transform: (RuntimeSnapshot) -> RuntimeSnapshot): RuntimeSnapshot {
        val next =
            transform(current()).copy(
                updatedAtEpochMs = System.currentTimeMillis(),
            )

        check(
            preferences.edit()
                .putString(KEY_PHASE, next.phase.name)
                .putBoolean(KEY_DESIRED_RUNNING, next.desiredRunning)
                .putNullableString(KEY_BOOT_ID, next.bootId)
                .putString(KEY_MESSAGE, next.message)
                .putNullableLong(KEY_CHILD_PID, next.childPid)
                .putNullableLong(KEY_HEARTBEAT_SEQUENCE, next.heartbeatSequence)
                .putLong(KEY_UPDATED_AT, next.updatedAtEpochMs)
                .commit(),
        ) {
            "Failed to persist runtime supervisor state"
        }
        return next
    }

    private fun android.content.SharedPreferences.getLongOrNull(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null

    private fun android.content.SharedPreferences.Editor.putNullableString(
        key: String,
        value: String?,
    ): android.content.SharedPreferences.Editor =
        if (value == null) remove(key) else putString(key, value)

    private fun android.content.SharedPreferences.Editor.putNullableLong(
        key: String,
        value: Long?,
    ): android.content.SharedPreferences.Editor =
        if (value == null) remove(key) else putLong(key, value)

    private companion object {
        const val KEY_PHASE = "phase"
        const val KEY_DESIRED_RUNNING = "desired-running"
        const val KEY_BOOT_ID = "boot-id"
        const val KEY_MESSAGE = "message"
        const val KEY_CHILD_PID = "child-pid"
        const val KEY_HEARTBEAT_SEQUENCE = "heartbeat-sequence"
        const val KEY_UPDATED_AT = "updated-at"
    }
}
