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
            rootfsName = preferences.getString(KEY_ROOTFS_NAME, null),
            desktop =
                DesktopSessionSnapshot(
                    phase =
                        runCatching {
                            DesktopSessionPhase.valueOf(
                                preferences.getString(
                                    KEY_DESKTOP_PHASE,
                                    DesktopSessionPhase.STOPPED.name,
                                ) ?: DesktopSessionPhase.STOPPED.name,
                            )
                        }.getOrDefault(DesktopSessionPhase.STOPPED),
                    desiredRunning =
                        preferences.getBoolean(KEY_DESKTOP_DESIRED_RUNNING, false),
                    rootfsName = preferences.getString(KEY_DESKTOP_ROOTFS_NAME, null),
                    environmentId =
                        preferences.getString(KEY_DESKTOP_ENVIRONMENT_ID, null),
                    environmentName =
                        preferences.getString(KEY_DESKTOP_ENVIRONMENT_NAME, null),
                    displayNumber =
                        preferences.getIntOrNull(KEY_DESKTOP_DISPLAY_NUMBER),
                    message =
                        preferences.getString(
                            KEY_DESKTOP_MESSAGE,
                            "No desktop session is running",
                        ) ?: "No desktop session is running",
                ),
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
                .putNullableString(KEY_ROOTFS_NAME, next.rootfsName)
                .putString(KEY_DESKTOP_PHASE, next.desktop.phase.name)
                .putBoolean(KEY_DESKTOP_DESIRED_RUNNING, next.desktop.desiredRunning)
                .putNullableString(KEY_DESKTOP_ROOTFS_NAME, next.desktop.rootfsName)
                .putNullableString(
                    KEY_DESKTOP_ENVIRONMENT_ID,
                    next.desktop.environmentId,
                ).putNullableString(
                    KEY_DESKTOP_ENVIRONMENT_NAME,
                    next.desktop.environmentName,
                ).putNullableInt(
                    KEY_DESKTOP_DISPLAY_NUMBER,
                    next.desktop.displayNumber,
                ).putString(KEY_DESKTOP_MESSAGE, next.desktop.message)
                .putLong(KEY_UPDATED_AT, next.updatedAtEpochMs)
                .commit(),
        ) {
            "Failed to persist runtime supervisor state"
        }
        return next
    }

    private fun android.content.SharedPreferences.getLongOrNull(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null

    private fun android.content.SharedPreferences.getIntOrNull(key: String): Int? =
        if (contains(key)) getInt(key, 0) else null

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

    private fun android.content.SharedPreferences.Editor.putNullableInt(
        key: String,
        value: Int?,
    ): android.content.SharedPreferences.Editor =
        if (value == null) remove(key) else putInt(key, value)

    private companion object {
        const val KEY_PHASE = "phase"
        const val KEY_DESIRED_RUNNING = "desired-running"
        const val KEY_BOOT_ID = "boot-id"
        const val KEY_MESSAGE = "message"
        const val KEY_CHILD_PID = "child-pid"
        const val KEY_HEARTBEAT_SEQUENCE = "heartbeat-sequence"
        const val KEY_ROOTFS_NAME = "rootfs-name"
        const val KEY_DESKTOP_PHASE = "desktop-phase"
        const val KEY_DESKTOP_DESIRED_RUNNING = "desktop-desired-running"
        const val KEY_DESKTOP_ROOTFS_NAME = "desktop-rootfs-name"
        const val KEY_DESKTOP_ENVIRONMENT_ID = "desktop-environment-id"
        const val KEY_DESKTOP_ENVIRONMENT_NAME = "desktop-environment-name"
        const val KEY_DESKTOP_DISPLAY_NUMBER = "desktop-display-number"
        const val KEY_DESKTOP_MESSAGE = "desktop-message"
        const val KEY_UPDATED_AT = "updated-at"
    }
}
