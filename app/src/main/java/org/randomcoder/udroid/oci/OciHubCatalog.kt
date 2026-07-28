package org.randomcoder.udroid.oci

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class OciHubRepository(
    val name: String,
    val description: String,
    val pullCount: Long,
    val starCount: Long,
) {
    val reference: OciImageReference =
        OciImageReference.parse("docker.io/library/$name")
}

data class OciHubRepositoryPage(
    val repositories: List<OciHubRepository>,
    val next: String?,
)

data class OciHubTagPlatform(
    val tag: String,
    val platform: OciPlatform,
    val digest: String,
    val compressedBytes: Long,
)

data class OciHubTagPage(
    val tags: List<OciHubTagPlatform>,
    val next: String?,
)

object OciHubCatalogParser {
    fun repositories(json: String): OciHubRepositoryPage {
        val root = Json.parseToJsonElement(json).jsonObject
        val repositories =
            root["results"]
                ?.jsonArray
                ?.mapNotNull { element ->
                    val value = element.jsonObject
                    val categories =
                        value["categories"]
                            ?.jsonArray
                            .orEmpty()
                            .mapNotNull {
                                it.jsonObject["slug"]?.jsonPrimitive?.contentOrNull
                            }
                    val contentTypes =
                        value["content_types"]
                            ?.jsonArray
                            .orEmpty()
                            .mapNotNull { it.jsonPrimitive.contentOrNull }
                    val name = value["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val namespace =
                        value["namespace"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val repositoryType =
                        value["repository_type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val status =
                        value["status_description"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            .orEmpty()
                    val description =
                        value["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (
                        namespace != OFFICIAL_NAMESPACE ||
                        repositoryType != "image" ||
                        !status.equals("active", ignoreCase = true) ||
                        OPERATING_SYSTEMS_CATEGORY !in categories ||
                        "image" !in contentTypes ||
                        !SAFE_REPOSITORY_NAME.matches(name) ||
                        description.contains("deprecated", ignoreCase = true)
                    ) {
                        return@mapNotNull null
                    }
                    OciHubRepository(
                        name = name,
                        description = description,
                        pullCount =
                            value["pull_count"]
                                ?.jsonPrimitive
                                ?.longOrNull
                                ?: 0L,
                        starCount =
                            value["star_count"]
                                ?.jsonPrimitive
                                ?.longOrNull
                                ?: 0L,
                    )
                }.orEmpty()
        return OciHubRepositoryPage(
            repositories = repositories,
            next = root["next"]?.jsonPrimitive?.contentOrNull,
        )
    }

    fun tagPlatform(
        json: String,
        target: OciPlatform,
    ): OciHubTagPlatform {
        val root = Json.parseToJsonElement(json).jsonObject
        return platformFromTag(root, target)
            ?: error(
                "Tag ${root["name"]?.jsonPrimitive?.contentOrNull.orEmpty()} has no " +
                    "${target.os}/${target.architecture}" +
                    target.variant?.let { "/$it" }.orEmpty() +
                    " image",
            )
    }

    fun tags(
        json: String,
        target: OciPlatform,
    ): OciHubTagPage {
        val root = Json.parseToJsonElement(json).jsonObject
        return OciHubTagPage(
            tags =
                root["results"]
                    ?.jsonArray
                    .orEmpty()
                    .mapNotNull { platformFromTag(it.jsonObject, target) },
            next = root["next"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun platformFromTag(
        root: kotlinx.serialization.json.JsonObject,
        target: OciPlatform,
    ): OciHubTagPlatform? {
        val tag = root["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(SAFE_TAG.matches(tag)) { "Docker Hub returned an invalid tag" }
        val candidates =
            root["images"]
                ?.jsonArray
                ?.mapNotNull { element ->
                    val value = element.jsonObject
                    val os = value["os"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val architecture =
                        value["architecture"]?.jsonPrimitive?.contentOrNull
                            ?: return@mapNotNull null
                    val variant = value["variant"]?.jsonPrimitive?.contentOrNull
                    if (
                        os != target.os ||
                        architecture != target.architecture ||
                        !value["status"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            .equals("active", ignoreCase = true)
                    ) {
                        return@mapNotNull null
                    }
                    val digest =
                        value["digest"]?.jsonPrimitive?.contentOrNull
                            ?: error("Docker Hub tag omitted its digest")
                    require(SHA256_DIGEST.matches(digest)) {
                        "Docker Hub returned an unsupported image digest"
                    }
                    val size =
                        value["size"]?.jsonPrimitive?.longOrNull
                            ?: error("Docker Hub tag omitted its size")
                    require(size >= 0L) { "Docker Hub returned a negative image size" }
                    OciHubTagPlatform(
                        tag = tag,
                        platform = OciPlatform(os, architecture, variant),
                        digest = digest,
                        compressedBytes = size,
                    )
                }.orEmpty()
        return candidates.firstOrNull { it.platform.variant == target.variant }
            ?: candidates.firstOrNull { it.platform.variant == null }
    }

    private const val OFFICIAL_NAMESPACE = "library"
    private const val OPERATING_SYSTEMS_CATEGORY = "operating-systems"
    private val SAFE_REPOSITORY_NAME = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")
    private val SAFE_TAG = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")
    private val SHA256_DIGEST = Regex("sha256:[a-f0-9]{64}")
}

class OciHubCatalogClient(
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
) {
    fun officialOperatingSystems(maxPages: Int = MAX_PAGES): List<OciHubRepository> {
        require(maxPages in 1..MAX_PAGES) { "Docker Hub page limit is out of range" }
        val repositories = linkedMapOf<String, OciHubRepository>()
        var next: String? = FIRST_PAGE
        repeat(maxPages) {
            val current = next ?: return@repeat
            val page = OciHubCatalogParser.repositories(get(current))
            page.repositories.forEach { repositories[it.name] = it }
            next = page.next?.also(::validatePageUrl)
        }
        return repositories.values
            .sortedWith(
                compareByDescending<OciHubRepository>(OciHubRepository::pullCount)
                    .thenBy(OciHubRepository::name),
            )
    }

    fun tagPlatform(
        repository: String,
        tag: String,
        target: OciPlatform,
    ): OciHubTagPlatform {
        require(SAFE_REPOSITORY_NAME.matches(repository)) {
            "Invalid Docker Hub repository"
        }
        require(SAFE_TAG.matches(tag)) { "Invalid Docker Hub tag" }
        return OciHubCatalogParser.tagPlatform(
            get("$HUB_API/namespaces/library/repositories/$repository/tags/$tag"),
            target,
        )
    }

    fun tags(
        repository: String,
        target: OciPlatform,
        maxPages: Int = MAX_TAG_PAGES,
    ): List<OciHubTagPlatform> {
        require(SAFE_REPOSITORY_NAME.matches(repository)) {
            "Invalid Docker Hub repository"
        }
        require(maxPages in 1..MAX_TAG_PAGES) { "Docker Hub tag page limit is out of range" }
        val tags = linkedMapOf<String, OciHubTagPlatform>()
        var next: String? =
            "$HUB_API/namespaces/library/repositories/$repository/tags?page_size=100"
        repeat(maxPages) {
            val current = next ?: return@repeat
            val page = OciHubCatalogParser.tags(get(current), target)
            page.tags.forEach { tags[it.tag] = it }
            next = page.next?.also { validateTagPageUrl(it, repository) }
        }
        return tags.values
            .sortedWith(
                compareByDescending<OciHubTagPlatform> { it.tag == "latest" }
                    .thenByDescending(OciHubTagPlatform::tag),
            )
    }

    private fun get(url: String): String {
        validateApiUrl(url)
        val connection =
            connectionFactory(URL(url)).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "uDroid-Android/0.1")
            }
        return try {
            val code = connection.responseCode
            check(code in 200..299) {
                if (code == 429) {
                    "Docker Hub catalogue rate limit reached; try again later"
                } else {
                    "Docker Hub catalogue returned HTTP $code"
                }
            }
            connection.inputStream.use(::readLimitedUtf8)
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimitedUtf8(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > MAX_RESPONSE_BYTES) {
                throw IOException("Docker Hub catalogue response is too large")
            }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun validatePageUrl(url: String) {
        validateApiUrl(url)
        require(URL(url).path == "/v2/namespaces/library/repositories") {
            "Docker Hub catalogue pagination left the official library"
        }
    }

    private fun validateTagPageUrl(
        url: String,
        repository: String,
    ) {
        validateApiUrl(url)
        require(
            URL(url).path ==
                "/v2/namespaces/library/repositories/$repository/tags",
        ) {
            "Docker Hub tag pagination left the selected official repository"
        }
    }

    private fun validateApiUrl(url: String) {
        val parsed = URL(url)
        require(parsed.protocol == "https" && parsed.host == "hub.docker.com") {
            "Docker Hub catalogue attempted to leave its API origin"
        }
    }

    private companion object {
        const val HUB_API = "https://hub.docker.com/v2"
        const val FIRST_PAGE =
            "$HUB_API/namespaces/library/repositories?page_size=100&ordering=-pull_count"
        const val MAX_PAGES = 3
        const val MAX_TAG_PAGES = 3
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        val SAFE_REPOSITORY_NAME = Regex("[a-z0-9]+(?:[._-][a-z0-9]+)*")
        val SAFE_TAG = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}")
    }
}
