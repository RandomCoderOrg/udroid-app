package org.randomcoder.udroid.oci

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

data class OciHubTagSnapshot(
    val repository: String,
    val platform: OciPlatform,
    val tags: List<OciHubTagPlatform>,
    val source: OciHubCatalogSource,
    val fetchedAtEpochMs: Long,
)

class OciHubTagRepository(
    context: Context,
    private val networkLoader: (String, OciPlatform) -> List<OciHubTagPlatform> =
        { repository, platform ->
            OciHubCatalogClient().tags(repository, platform)
        },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val cacheDirectory = File(context.filesDir, "catalog/oci-hub-tags")
    private val memoryCache = mutableMapOf<String, OciHubTagSnapshot>()

    @Synchronized
    fun load(
        repository: String,
        platform: OciPlatform,
        forceRefresh: Boolean = false,
    ): OciHubTagSnapshot {
        validate(repository, platform)
        val cacheFile = cacheFile(repository, platform)
        val cacheKey = cacheKey(repository, platform)
        val currentTime = now()
        val cached =
            memoryCache[cacheKey]
                ?: readCache(cacheFile, repository, platform)
                    ?.also { memoryCache[cacheKey] = it }
        if (
            !forceRefresh &&
            cached != null &&
            currentTime >= cached.fetchedAtEpochMs &&
            currentTime - cached.fetchedAtEpochMs <= FRESH_FOR_MS
        ) {
            return cached.copy(source = OciHubCatalogSource.CACHE)
        }

        val network =
            runCatching {
                val tags = networkLoader(repository, platform)
                check(tags.isNotEmpty()) {
                    "Docker Hub returned no compatible tags for $repository"
                }
                OciHubTagSnapshot(
                    repository = repository,
                    platform = platform,
                    tags = tags,
                    source = OciHubCatalogSource.NETWORK,
                    fetchedAtEpochMs = currentTime,
                ).also {
                    memoryCache[cacheKey] = it
                    writeCache(cacheFile, it)
                }
            }
        if (network.isSuccess) return network.getOrThrow()
        if (cached != null) {
            return cached.copy(source = OciHubCatalogSource.STALE_CACHE)
        }
        throw network.exceptionOrNull()!!
    }

    @Synchronized
    internal fun clearCache(
        repository: String,
        platform: OciPlatform,
    ) {
        validate(repository, platform)
        memoryCache.remove(cacheKey(repository, platform))
        val file = cacheFile(repository, platform)
        if (file.exists()) check(file.delete()) {
            "Could not clear the Docker Hub tag cache"
        }
    }

    private fun readCache(
        file: File,
        repository: String,
        platform: OciPlatform,
    ): OciHubTagSnapshot? =
        runCatching {
            if (!file.isFile || file.length() > MAX_CACHE_BYTES) return null
            OciHubTagCacheCodec.decode(file.readText())
                .takeIf {
                    it.repository == repository &&
                        it.platform == platform
                }
        }.getOrNull()

    private fun writeCache(
        file: File,
        snapshot: OciHubTagSnapshot,
    ) {
        file.parentFile?.mkdirs()
        val staging = File(file.parentFile, "${file.name}.staging")
        staging.writeText(OciHubTagCacheCodec.encode(snapshot))
        if (file.exists()) check(file.delete()) {
            "Could not replace the Docker Hub tag cache"
        }
        check(staging.renameTo(file)) {
            "Could not activate the Docker Hub tag cache"
        }
    }

    private fun cacheFile(
        repository: String,
        platform: OciPlatform,
    ): File =
        File(
            cacheDirectory,
            cacheKey(repository, platform) + ".json",
        )

    private fun cacheKey(
        repository: String,
        platform: OciPlatform,
    ): String =
        listOfNotNull(
            repository,
            platform.os,
            platform.architecture,
            platform.variant,
        ).joinToString("-")

    private fun validate(
        repository: String,
        platform: OciPlatform,
    ) {
        require(SAFE_REPOSITORY_NAME.matches(repository)) {
            "Invalid Docker Hub repository"
        }
        require(SAFE_PLATFORM_PART.matches(platform.os)) { "Invalid OCI platform OS" }
        require(SAFE_PLATFORM_PART.matches(platform.architecture)) {
            "Invalid OCI platform architecture"
        }
        require(platform.variant == null || SAFE_PLATFORM_PART.matches(platform.variant)) {
            "Invalid OCI platform variant"
        }
    }

    private companion object {
        const val FRESH_FOR_MS = 2L * 60L * 60L * 1_000L
        const val MAX_CACHE_BYTES = 2L * 1024L * 1024L
        val SAFE_REPOSITORY_NAME = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")
        val SAFE_PLATFORM_PART = Regex("[a-z0-9_][a-z0-9_.-]{0,31}")
    }
}

internal object OciHubTagCacheCodec {
    fun encode(snapshot: OciHubTagSnapshot): String =
        buildJsonObject {
            put("format", FORMAT)
            put("repository", snapshot.repository)
            put("platform_os", snapshot.platform.os)
            put("platform_architecture", snapshot.platform.architecture)
            snapshot.platform.variant?.let { put("platform_variant", it) }
            put("fetched_at_epoch_ms", snapshot.fetchedAtEpochMs)
            put(
                "tags",
                JsonArray(
                    snapshot.tags.map { tag ->
                        buildJsonObject {
                            put("tag", tag.tag)
                            put("variant", tag.platform.variant)
                            put("digest", tag.digest)
                            put("compressed_bytes", tag.compressedBytes)
                        }
                    },
                ),
            )
        }.toString()

    fun decode(encoded: String): OciHubTagSnapshot {
        val root = Json.parseToJsonElement(encoded).jsonObject
        require(root["format"]?.jsonPrimitive?.contentOrNull == FORMAT) {
            "Unsupported Docker Hub tag cache format"
        }
        val repository = root.getValue("repository").jsonPrimitive.content
        require(SAFE_REPOSITORY_NAME.matches(repository)) {
            "Invalid Docker Hub tag cache repository"
        }
        val platform =
            OciPlatform(
                os = root.getValue("platform_os").jsonPrimitive.content,
                architecture =
                    root.getValue("platform_architecture").jsonPrimitive.content,
                variant = root["platform_variant"]?.jsonPrimitive?.contentOrNull,
            )
        val fetchedAt = root.getValue("fetched_at_epoch_ms").jsonPrimitive.long
        require(fetchedAt >= 0L) { "Invalid Docker Hub tag cache timestamp" }
        val tags =
            root.getValue("tags")
                .jsonArray
                .map { element ->
                    val value = element.jsonObject
                    val tag = value.getValue("tag").jsonPrimitive.content
                    val digest = value.getValue("digest").jsonPrimitive.content
                    val compressedBytes =
                        value["compressed_bytes"]?.jsonPrimitive?.longOrNull
                            ?: error("Docker Hub tag cache omitted image size")
                    require(SAFE_TAG.matches(tag)) { "Invalid Docker Hub cached tag" }
                    require(SHA256_DIGEST.matches(digest)) {
                        "Invalid Docker Hub cached digest"
                    }
                    require(compressedBytes >= 0L) {
                        "Invalid Docker Hub cached image size"
                    }
                    OciHubTagPlatform(
                        tag = tag,
                        platform =
                            platform.copy(
                                variant = value["variant"]?.jsonPrimitive?.contentOrNull,
                            ),
                        digest = digest,
                        compressedBytes = compressedBytes,
                    )
                }
        require(tags.isNotEmpty() && tags.size <= MAX_TAGS) {
            "Invalid Docker Hub tag cache count"
        }
        return OciHubTagSnapshot(
            repository = repository,
            platform = platform,
            tags = tags,
            source = OciHubCatalogSource.CACHE,
            fetchedAtEpochMs = fetchedAt,
        )
    }

    private const val FORMAT = "1"
    private const val MAX_TAGS = 500
    private val SAFE_REPOSITORY_NAME = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")
    private val SAFE_TAG = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")
    private val SHA256_DIGEST = Regex("sha256:[a-f0-9]{64}")
}
