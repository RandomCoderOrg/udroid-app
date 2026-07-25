package org.randomcoder.udroid.update

import android.content.Context
import org.json.JSONObject
import java.io.File

enum class AppUpdatePhase {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    READY,
    FAILED,
}

data class AppRelease(
    val tag: String,
    val version: String,
    val title: String,
    val notes: String,
    val publishedAt: String,
    val releaseUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val apkSha256: String?,
    val checksumsUrl: String,
)

data class AppUpdateState(
    val phase: AppUpdatePhase = AppUpdatePhase.IDLE,
    val release: AppRelease? = null,
    val checkedAtMillis: Long = 0L,
    val completedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val downloadedApkPath: String? = null,
    val message: String? = null,
    val etag: String? = null,
    val notifiedTag: String? = null,
) {
    val percentage: Int
        get() =
            if (totalBytes > 0L) {
                ((completedBytes.coerceIn(0L, totalBytes) * 100L) / totalBytes).toInt()
            } else {
                0
            }
}

class AppUpdateStateStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("app-update-state", Context.MODE_PRIVATE)

    fun current(): AppUpdateState =
        synchronized(LOCK) {
            preferences.getString(KEY_STATE, null)
                ?.let { raw -> runCatching { decode(raw) }.getOrNull() }
                ?: AppUpdateState()
        }

    fun save(state: AppUpdateState): AppUpdateState =
        synchronized(LOCK) {
            check(preferences.edit().putString(KEY_STATE, encode(state)).commit()) {
                "Could not persist app update state"
            }
            state
        }

    fun update(transform: (AppUpdateState) -> AppUpdateState): AppUpdateState =
        synchronized(LOCK) {
            save(transform(current()))
        }

    fun recoverAfterProcessRestart(): AppUpdateState =
        synchronized(LOCK) {
            val current = current()
            val recovered =
                when {
                    current.phase == AppUpdatePhase.DOWNLOADING ->
                        current.copy(
                            phase = AppUpdatePhase.AVAILABLE,
                            message = "The interrupted update download can resume",
                        )
                    current.phase == AppUpdatePhase.READY &&
                        current.downloadedApkPath?.let(::File)?.isFile != true ->
                        current.copy(
                            phase = AppUpdatePhase.AVAILABLE,
                            downloadedApkPath = null,
                            message = "The downloaded update is no longer available",
                        )
                    else -> current
                }
            if (recovered == current) current else save(recovered)
        }

    fun reconcileInstalledVersion(currentVersion: String): AppUpdateState =
        synchronized(LOCK) {
            val current = recoverAfterProcessRestart()
            val release = current.release
            if (
                release == null ||
                SemanticVersion.compare(release.version, currentVersion) > 0
            ) {
                return@synchronized current
            }
            current.downloadedApkPath?.let(::File)?.delete()
            save(
                AppUpdateState(
                    phase = AppUpdatePhase.UP_TO_DATE,
                    checkedAtMillis = current.checkedAtMillis,
                    message = "uDroid $currentVersion is current",
                    etag = current.etag,
                    notifiedTag = current.notifiedTag,
                ),
            )
        }

    private fun encode(state: AppUpdateState): String =
        JSONObject()
            .put("phase", state.phase.name)
            .put("checked_at", state.checkedAtMillis)
            .put("completed_bytes", state.completedBytes)
            .put("total_bytes", state.totalBytes)
            .put("downloaded_apk", state.downloadedApkPath)
            .put("message", state.message)
            .put("etag", state.etag)
            .put("notified_tag", state.notifiedTag)
            .put("release", state.release?.toJson())
            .toString()

    private fun decode(raw: String): AppUpdateState {
        val json = JSONObject(raw)
        return AppUpdateState(
            phase =
                json.optString("phase")
                    .let { value ->
                        AppUpdatePhase.entries.firstOrNull { it.name == value }
                    } ?: AppUpdatePhase.IDLE,
            release =
                json.optJSONObject("release")
                    ?.let(::releaseFromJson),
            checkedAtMillis = json.optLong("checked_at"),
            completedBytes = json.optLong("completed_bytes"),
            totalBytes = json.optLong("total_bytes", -1L),
            downloadedApkPath = json.optStringOrNull("downloaded_apk"),
            message = json.optStringOrNull("message"),
            etag = json.optStringOrNull("etag"),
            notifiedTag = json.optStringOrNull("notified_tag"),
        )
    }

    private fun AppRelease.toJson(): JSONObject =
        JSONObject()
            .put("tag", tag)
            .put("version", version)
            .put("title", title)
            .put("notes", notes)
            .put("published_at", publishedAt)
            .put("release_url", releaseUrl)
            .put("apk_name", apkName)
            .put("apk_url", apkUrl)
            .put("apk_size", apkSize)
            .put("apk_sha256", apkSha256)
            .put("checksums_url", checksumsUrl)

    private fun releaseFromJson(json: JSONObject): AppRelease =
        AppRelease(
            tag = json.getString("tag"),
            version = json.getString("version"),
            title = json.getString("title"),
            notes = json.optString("notes"),
            publishedAt = json.optString("published_at"),
            releaseUrl = json.getString("release_url"),
            apkName = json.getString("apk_name"),
            apkUrl = json.getString("apk_url"),
            apkSize = json.getLong("apk_size"),
            apkSha256 = json.optStringOrNull("apk_sha256"),
            checksumsUrl = json.getString("checksums_url"),
        )

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

    private companion object {
        const val KEY_STATE = "state"
        val LOCK = Any()
    }
}
