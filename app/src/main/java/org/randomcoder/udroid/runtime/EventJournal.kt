package org.randomcoder.udroid.runtime

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant

class EventJournal(context: Context) {
    private val logsDirectory = File(context.filesDir, "logs").apply { mkdirs() }
    private val activeFile = File(logsDirectory, "supervisor.jsonl")
    private val previousFile = File(logsDirectory, "supervisor.previous.jsonl")

    @Synchronized
    fun append(
        component: String,
        severity: String,
        event: String,
        message: String,
        bootId: String?,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        rotateIfNeeded()
        val payload =
            JSONObject()
                .put("timestamp", Instant.now().toString())
                .put("boot_id", bootId ?: JSONObject.NULL)
                .put("component", component)
                .put("pid", android.os.Process.myPid())
                .put("severity", severity)
                .put("event", event)
                .put("message", message)

        val jsonFields = JSONObject()
        fields.forEach { (key, value) ->
            jsonFields.put(key, value ?: JSONObject.NULL)
        }
        payload.put("fields", jsonFields)
        activeFile.appendText(payload.toString() + "\n")
    }

    @Synchronized
    fun tail(limit: Int = 80): List<String> {
        if (!activeFile.exists()) return emptyList()
        return activeFile.readLines().takeLast(limit).asReversed()
    }

    private fun rotateIfNeeded() {
        if (!activeFile.exists() || activeFile.length() < MAX_BYTES) return
        if (previousFile.exists()) previousFile.delete()
        activeFile.renameTo(previousFile)
    }

    private companion object {
        const val MAX_BYTES = 1024L * 1024L
    }
}
