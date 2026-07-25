package org.randomcoder.udroid.install

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.randomcoder.udroid.catalog.DistroProvider
import org.randomcoder.udroid.catalog.DistroVariant
import org.randomcoder.udroid.catalog.LinuxDistribution

class InstallStateStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("installer-state", Context.MODE_PRIVATE)

    @Synchronized
    fun current(): InstallProgress? {
        val raw = preferences.getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching { decode(raw) }.getOrNull()
    }

    @Synchronized
    fun save(progress: InstallProgress): InstallProgress {
        check(
            preferences.edit()
                .putString(KEY_SNAPSHOT, encode(progress))
                .commit(),
        ) {
            "Failed to persist installer state"
        }
        return progress
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().remove(KEY_SNAPSHOT).commit()) {
            "Failed to clear installer state"
        }
    }

    private fun encode(progress: InstallProgress): String =
        buildJsonObject {
            put("suite", progress.distro.suite)
            put("variant", progress.distro.variant)
            put("internal_name", progress.distro.internalName)
            put("friendly_name", progress.distro.friendlyName)
            put("architecture", progress.distro.architecture)
            put("download_url", progress.distro.downloadUrl)
            put("sha256", progress.distro.sha256)
            put("distribution", progress.distro.distribution.id)
            put("provider", progress.distro.provider.name)
            progress.distro.releaseLabel?.let { put("release_label", it) }
            put("archive_strip_components", progress.distro.archiveStripComponents)
            put("stage", progress.stage.name)
            put("stage_progress", progress.stageProgress)
            put("current_detail", progress.currentDetail)
            put(
                "terminal_lines",
                JsonArray(progress.terminalLines.takeLast(MAX_TERMINAL_LINES).map(::JsonPrimitive)),
            )
            put("preview_only", progress.previewOnly)
            progress.operationId?.let { put("operation_id", it) }
            put("completed_bytes", progress.completedBytes)
            put("total_bytes", progress.totalBytes)
            put("bytes_per_second", progress.bytesPerSecond)
            put("cancellable", progress.cancellable)
        }.toString()

    private fun decode(raw: String): InstallProgress {
        val value = Json.parseToJsonElement(raw).jsonObject
        val distro =
            DistroVariant(
                suite = value.requiredString("suite"),
                variant = value.requiredString("variant"),
                internalName = value.requiredString("internal_name"),
                friendlyName = value.requiredString("friendly_name"),
                architecture = value.requiredString("architecture"),
                downloadUrl = value.requiredString("download_url"),
                sha256 = value.requiredString("sha256"),
                distribution =
                    value["distribution"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.let { id -> LinuxDistribution.entries.firstOrNull { it.id == id } }
                        ?: LinuxDistribution.UBUNTU,
                provider =
                    value["provider"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.let { name ->
                            DistroProvider.entries.firstOrNull { it.name == name }
                        }
                        ?: DistroProvider.UDROID,
                releaseLabel = value["release_label"]?.jsonPrimitive?.contentOrNull,
                archiveStripComponents =
                    value["archive_strip_components"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.toIntOrNull()
                        ?: 0,
            )
        return InstallProgress(
            distro = distro,
            stage = InstallStage.valueOf(value.requiredString("stage")),
            stageProgress = value.getValue("stage_progress").jsonPrimitive.float,
            currentDetail = value.requiredString("current_detail"),
            terminalLines =
                value.getValue("terminal_lines")
                    .jsonArray
                    .mapNotNull { it.jsonPrimitive.contentOrNull },
            previewOnly = value.getValue("preview_only").jsonPrimitive.boolean,
            operationId = value["operation_id"]?.jsonPrimitive?.contentOrNull,
            completedBytes = value.getValue("completed_bytes").jsonPrimitive.long,
            totalBytes = value.getValue("total_bytes").jsonPrimitive.long,
            bytesPerSecond = value.getValue("bytes_per_second").jsonPrimitive.long,
            cancellable = value.getValue("cancellable").jsonPrimitive.boolean,
        )
    }

    private fun JsonObject.requiredString(key: String): String =
        getValue(key).jsonPrimitive.content

    private companion object {
        const val KEY_SNAPSHOT = "snapshot"
        const val MAX_TERMINAL_LINES = 160
    }
}
