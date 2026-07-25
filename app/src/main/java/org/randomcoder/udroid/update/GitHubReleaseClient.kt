package org.randomcoder.udroid.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

sealed interface GitHubReleaseCheck {
    data class Available(
        val release: AppRelease,
        val etag: String?,
    ) : GitHubReleaseCheck

    data class UpToDate(
        val newestVersion: String?,
        val etag: String?,
    ) : GitHubReleaseCheck

    data object NotModified : GitHubReleaseCheck
}

class GitHubReleaseClient(
    private val releasesApi: String,
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
) {
    fun check(
        currentVersion: String,
        etag: String?,
    ): GitHubReleaseCheck {
        val connection =
            connectionFactory(URL(releasesApi)).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
                setRequestProperty("User-Agent", "uDroid-Android/$currentVersion")
                etag?.takeIf(String::isNotBlank)?.let {
                    setRequestProperty("If-None-Match", it)
                }
            }
        return try {
            when (connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> GitHubReleaseCheck.NotModified
                HttpURLConnection.HTTP_OK -> {
                    val contentLength = connection.contentLengthLong
                    if (contentLength > MAX_RESPONSE_BYTES) {
                        throw IOException("GitHub release response is unexpectedly large")
                    }
                    parse(
                        json = readLimitedText(connection),
                        currentVersion = currentVersion,
                        etag = connection.getHeaderField("ETag"),
                    )
                }
                else ->
                    throw IOException(
                        "GitHub releases returned HTTP ${connection.responseCode}",
                    )
            }
        } finally {
            connection.disconnect()
        }
    }

    internal fun parse(
        json: String,
        currentVersion: String,
        etag: String?,
    ): GitHubReleaseCheck {
        val releases = Json.parseToJsonElement(json).jsonArray
        val compatible =
            buildList {
                releases.forEach { release ->
                    parseRelease(release.jsonObject)?.let(::add)
                }
            }.sortedWith { left, right ->
                SemanticVersion.compare(right.version, left.version)
            }
        val newest = compatible.firstOrNull()
        return if (
            newest != null &&
            SemanticVersion.compare(newest.version, currentVersion) > 0
        ) {
            GitHubReleaseCheck.Available(newest, etag)
        } else {
            GitHubReleaseCheck.UpToDate(newest?.version, etag)
        }
    }

    private fun parseRelease(json: JsonObject): AppRelease? {
        if (json.boolean("draft")) return null
        val tag = json.string("tag_name")
        val version = SemanticVersion.normalize(tag) ?: return null
        val assets = json["assets"]?.jsonArray ?: return null
        val apk =
            assets
                .map { it.jsonObject }
                .filter { it.string("name").endsWith(".apk", ignoreCase = true) }
                .maxByOrNull { asset ->
                    val name = asset.string("name").lowercase()
                    when {
                        "universal" in name -> 3
                        "prerelease" in name -> 2
                        else -> 1
                    }
                } ?: return null
        val checksums =
            assets
                .map { it.jsonObject }
                .firstOrNull { it.string("name").equals("SHA256SUMS", ignoreCase = true) }
                ?: return null
        val releaseUrl = json.string("html_url")
        val apkUrl = apk.string("browser_download_url")
        val checksumsUrl = checksums.string("browser_download_url")
        if (
            !isRepositoryReleasePage(releaseUrl, tag) ||
            !isRepositoryReleaseAsset(apkUrl, tag, apk.string("name")) ||
            !isRepositoryReleaseAsset(checksumsUrl, tag, checksums.string("name"))
        ) {
            return null
        }
        val digest =
            apk.string("digest")
                .removePrefix("sha256:")
                .lowercase()
                .takeIf { SHA256.matches(it) }
                ?: return null
        return AppRelease(
            tag = tag,
            version = version,
            title = json.string("name").takeIf(String::isNotBlank) ?: "uDroid $tag",
            notes = json.string("body").take(MAX_NOTES_CHARS),
            publishedAt = json.string("published_at"),
            releaseUrl = releaseUrl,
            apkName = apk.string("name"),
            apkUrl = apkUrl,
            apkSize = apk["size"]?.jsonPrimitive?.longOrNull ?: -1L,
            apkSha256 = digest,
            checksumsUrl = checksumsUrl,
        ).takeIf {
            it.apkName.isNotBlank() &&
                it.apkSize > 0L
        }
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.boolean(key: String): Boolean =
        this[key]?.jsonPrimitive?.booleanOrNull ?: false

    private fun isRepositoryReleasePage(
        value: String,
        tag: String,
    ): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme == "https" &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.path == "/RandomCoderOrg/udroid-app/releases/tag/$tag"
    }

    private fun isRepositoryReleaseAsset(
        value: String,
        tag: String,
        name: String,
    ): Boolean {
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme == "https" &&
            uri.host.equals("github.com", ignoreCase = true) &&
            uri.path == "/RandomCoderOrg/udroid-app/releases/download/$tag/$name"
    }

    private fun readLimitedText(connection: HttpURLConnection): String =
        connection.inputStream.bufferedReader().use { reader ->
            val output = StringBuilder()
            val buffer = CharArray(8 * 1024)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                if (output.length + count > MAX_RESPONSE_BYTES) {
                    throw IOException("GitHub release response exceeded the size limit")
                }
                output.append(buffer, 0, count)
            }
            output.toString()
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val MAX_NOTES_CHARS = 4_000
        val SHA256 = Regex("[a-f0-9]{64}")
    }
}
