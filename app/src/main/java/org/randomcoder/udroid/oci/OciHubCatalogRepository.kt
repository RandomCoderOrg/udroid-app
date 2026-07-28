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

enum class OciHubCatalogSource {
    NETWORK,
    CACHE,
    STALE_CACHE,
}

data class OciHubCatalogSnapshot(
    val repositories: List<OciHubRepository>,
    val source: OciHubCatalogSource,
    val fetchedAtEpochMs: Long,
)

class OciHubCatalogRepository(
    context: Context,
    private val networkLoader: () -> List<OciHubRepository> = {
        OciHubCatalogClient().officialOperatingSystems()
    },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val cacheFile = File(context.filesDir, "catalog/oci-hub-operating-systems.json")
    private var memoryCache: OciHubCatalogSnapshot? = null

    @Synchronized
    fun load(forceRefresh: Boolean = false): OciHubCatalogSnapshot {
        val currentTime = now()
        val cached = memoryCache ?: readCache()?.also { memoryCache = it }
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
                val repositories = networkLoader()
                check(repositories.isNotEmpty()) {
                    "Docker Hub returned no compatible operating-system images"
                }
                OciHubCatalogSnapshot(
                    repositories = repositories,
                    source = OciHubCatalogSource.NETWORK,
                    fetchedAtEpochMs = currentTime,
                ).also {
                    memoryCache = it
                    writeCache(it)
                }
            }
        if (network.isSuccess) return network.getOrThrow()
        if (cached != null) {
            return cached.copy(source = OciHubCatalogSource.STALE_CACHE)
        }
        throw network.exceptionOrNull()!!
    }

    @Synchronized
    internal fun clearCache() {
        memoryCache = null
        if (cacheFile.exists()) check(cacheFile.delete()) {
            "Could not clear the OCI catalogue cache"
        }
    }

    private fun readCache(): OciHubCatalogSnapshot? =
        runCatching {
            if (!cacheFile.isFile || cacheFile.length() > MAX_CACHE_BYTES) return null
            OciHubCatalogCacheCodec.decode(cacheFile.readText())
        }.getOrNull()

    private fun writeCache(snapshot: OciHubCatalogSnapshot) {
        cacheFile.parentFile?.mkdirs()
        val staging = File(cacheFile.parentFile, "${cacheFile.name}.staging")
        staging.writeText(OciHubCatalogCacheCodec.encode(snapshot))
        if (cacheFile.exists()) check(cacheFile.delete()) {
            "Could not replace the OCI catalogue cache"
        }
        check(staging.renameTo(cacheFile)) {
            "Could not activate the OCI catalogue cache"
        }
    }

    private companion object {
        const val FRESH_FOR_MS = 6L * 60L * 60L * 1_000L
        const val MAX_CACHE_BYTES = 512L * 1024L
    }
}

internal object OciHubCatalogCacheCodec {
    fun encode(snapshot: OciHubCatalogSnapshot): String =
        buildJsonObject {
            put("format", FORMAT)
            put("fetched_at_epoch_ms", snapshot.fetchedAtEpochMs)
            put(
                "repositories",
                JsonArray(
                    snapshot.repositories.map { repository ->
                        buildJsonObject {
                            put("name", repository.name)
                            put("description", repository.description)
                            put("pull_count", repository.pullCount)
                            put("star_count", repository.starCount)
                        }
                    },
                ),
            )
        }.toString()

    fun decode(encoded: String): OciHubCatalogSnapshot {
        val root = Json.parseToJsonElement(encoded).jsonObject
        require(root["format"]?.jsonPrimitive?.contentOrNull == FORMAT) {
            "Unsupported OCI catalogue cache format"
        }
        val fetchedAt = root.getValue("fetched_at_epoch_ms").jsonPrimitive.long
        require(fetchedAt >= 0L) { "Invalid OCI catalogue cache timestamp" }
        val repositories =
            root.getValue("repositories")
                .jsonArray
                .map { element ->
                    val value = element.jsonObject
                    val name = value.getValue("name").jsonPrimitive.content
                    val description = value.getValue("description").jsonPrimitive.content
                    require(SAFE_REPOSITORY_NAME.matches(name)) {
                        "Invalid OCI catalogue cache repository"
                    }
                    require(description.length <= MAX_DESCRIPTION_LENGTH) {
                        "OCI catalogue cache description is too long"
                    }
                    OciHubRepository(
                        name = name,
                        description = description,
                        pullCount =
                            value["pull_count"]?.jsonPrimitive?.longOrNull
                                ?: 0L,
                        starCount =
                            value["star_count"]?.jsonPrimitive?.longOrNull
                                ?: 0L,
                    )
                }
        require(repositories.isNotEmpty() && repositories.size <= MAX_REPOSITORIES) {
            "Invalid OCI catalogue cache repository count"
        }
        return OciHubCatalogSnapshot(
            repositories = repositories,
            source = OciHubCatalogSource.CACHE,
            fetchedAtEpochMs = fetchedAt,
        )
    }

    private const val FORMAT = "1"
    private const val MAX_REPOSITORIES = 500
    private const val MAX_DESCRIPTION_LENGTH = 1_000
    private val SAFE_REPOSITORY_NAME = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")
}
