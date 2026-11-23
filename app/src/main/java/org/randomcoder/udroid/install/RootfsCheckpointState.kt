package org.randomcoder.udroid.install

import android.content.Context

enum class RootfsCheckpointOperation {
    BACKUP,
    RESTORE,
}

enum class RootfsCheckpointStage {
    PREPARING,
    COPYING,
    ARCHIVING,
    EXTRACTING,
    CONFIGURING,
    SWAPPING,
    COMPLETE,
    FAILED,
    ;

    val running: Boolean
        get() = this != COMPLETE && this != FAILED
}

data class RootfsCheckpointState(
    val operationId: String,
    val rootfsName: String,
    val operation: RootfsCheckpointOperation,
    val stage: RootfsCheckpointStage,
    val detail: String,
    val progress: Int? = null,
    val documentUri: String? = null,
)

class RootfsCheckpointStateStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun current(): RootfsCheckpointState? {
        val operationId = preferences.getString(KEY_OPERATION_ID, null) ?: return null
        return runCatching {
            RootfsCheckpointState(
                operationId = operationId,
                rootfsName = requireNotNull(preferences.getString(KEY_ROOTFS_NAME, null)),
                operation =
                    RootfsCheckpointOperation.valueOf(
                        requireNotNull(preferences.getString(KEY_OPERATION, null)),
                    ),
                stage =
                    RootfsCheckpointStage.valueOf(
                        requireNotNull(preferences.getString(KEY_STAGE, null)),
                    ),
                detail = requireNotNull(preferences.getString(KEY_DETAIL, null)),
                progress = preferences.getInt(KEY_PROGRESS, NO_PROGRESS).takeUnless { it == NO_PROGRESS },
                documentUri = preferences.getString(KEY_DOCUMENT_URI, null),
            )
        }.getOrNull()
    }

    @Synchronized
    fun save(state: RootfsCheckpointState): RootfsCheckpointState {
        check(
            preferences
                .edit()
                .putString(KEY_OPERATION_ID, state.operationId)
                .putString(KEY_ROOTFS_NAME, state.rootfsName)
                .putString(KEY_OPERATION, state.operation.name)
                .putString(KEY_STAGE, state.stage.name)
                .putString(KEY_DETAIL, state.detail)
                .putInt(KEY_PROGRESS, state.progress ?: NO_PROGRESS)
                .putString(KEY_DOCUMENT_URI, state.documentUri)
                .commit(),
        ) {
            "Failed to persist checkpoint state"
        }
        return state
    }

    private companion object {
        const val PREFERENCES_NAME = "rootfs-checkpoint-state"
        const val KEY_OPERATION_ID = "operation-id"
        const val KEY_ROOTFS_NAME = "rootfs-name"
        const val KEY_OPERATION = "operation"
        const val KEY_STAGE = "stage"
        const val KEY_DETAIL = "detail"
        const val KEY_PROGRESS = "progress"
        const val KEY_DOCUMENT_URI = "document-uri"
        const val NO_PROGRESS = -1
    }
}
