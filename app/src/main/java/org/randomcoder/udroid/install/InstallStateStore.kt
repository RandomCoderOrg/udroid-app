package org.randomcoder.udroid.install

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.randomcoder.udroid.catalog.DistroProvider
import org.randomcoder.udroid.catalog.DistroVariant
import org.randomcoder.udroid.catalog.LinuxDistribution
import java.util.UUID

class InstallStateStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("installer-state", Context.MODE_PRIVATE)

    @Synchronized
    fun current(): InstallProgress? {
        val raw = preferences.getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching { InstallProgressCodec.decode(raw) }.getOrNull()
    }

    @Synchronized
    fun save(progress: InstallProgress): InstallProgress {
        check(
            preferences.edit()
                .putString(KEY_SNAPSHOT, InstallProgressCodec.encode(progress))
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

    private companion object {
        const val KEY_SNAPSHOT = "snapshot"
    }
}

internal object InstallProgressCodec {
    fun encode(progress: InstallProgress): String =
        buildJsonObject {
            put("format", FORMAT)
            put(
                "work_request",
                Json.parseToJsonElement(InstallerWorkRequestCodec.encode(progress.work)),
            )
            put("stage", progress.stage.name)
            put("stage_progress", progress.stageProgress)
            put("current_detail", progress.currentDetail)
            put(
                "terminal_lines",
                JsonArray(progress.terminalLines.takeLast(MAX_TERMINAL_LINES).map(::JsonPrimitive)),
            )
            put("preview_only", progress.previewOnly)
            put("completed_bytes", progress.completedBytes)
            put("total_bytes", progress.totalBytes)
            put("bytes_per_second", progress.bytesPerSecond)
            put("cancellable", progress.cancellable)
        }.toString()

    fun decode(raw: String): InstallProgress {
        val value = Json.parseToJsonElement(raw).jsonObject
        value["format"]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.let { format ->
                require(format == FORMAT) { "Unsupported installer progress format" }
            }
        val work =
            value["work_request"]
                ?.let { InstallerWorkRequestCodec.decode(it.toString()) }
                ?: decodeLegacyArchive(value)
        return InstallProgress(
            work = work,
            stage = InstallStage.valueOf(value.requiredString("stage")),
            stageProgress = value.floatOrDefault("stage_progress", 0f),
            currentDetail = value.requiredString("current_detail"),
            terminalLines =
                value["terminal_lines"]
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty(),
            previewOnly = value.booleanOrDefault("preview_only", false),
            completedBytes = value.longOrDefault("completed_bytes", 0L),
            totalBytes = value.longOrDefault("total_bytes", -1L),
            bytesPerSecond = value.longOrDefault("bytes_per_second", 0L),
            cancellable = value.booleanOrDefault("cancellable", false),
        )
    }

    private fun decodeLegacyArchive(value: JsonObject): InstallerWorkRequest.Archive {
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
        return InstallerWorkRequest.Archive(
            distro = distro,
            operationId =
                value["operation_id"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: UUID.randomUUID().toString(),
        )
    }

    private fun JsonObject.requiredString(key: String): String =
        getValue(key).jsonPrimitive.content

    private fun JsonObject.floatOrDefault(
        key: String,
        default: Float,
    ): Float = get(key)?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: default

    private fun JsonObject.longOrDefault(
        key: String,
        default: Long,
    ): Long = get(key)?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: default

    private fun JsonObject.booleanOrDefault(
        key: String,
        default: Boolean,
    ): Boolean = get(key)?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: default

    private const val FORMAT = "2"
    private const val MAX_TERMINAL_LINES = 160
}
