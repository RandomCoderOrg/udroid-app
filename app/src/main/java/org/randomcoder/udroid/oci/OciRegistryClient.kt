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
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class OciBlobDescriptor(
    val digest: String,
    val mediaType: String,
    val size: Long,
)

data class OciResolvedImage(
    val reference: OciImageReference,
    val platform: OciPlatform,
    val manifestDigest: String,
    val config: OciBlobDescriptor,
    val layers: List<OciBlobDescriptor>,
    internal val bearerToken: String?,
) {
    fun blobDownload(descriptor: OciBlobDescriptor): OciBlobDownload {
        require(descriptor == config || descriptor in layers) {
            "Blob does not belong to this resolved image"
        }
        return OciBlobDownload(
            descriptor = descriptor,
            url =
                "https://${reference.registry}/v2/${reference.repository}/blobs/" +
                    descriptor.digest,
            requestHeaders =
                bearerToken?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty(),
        )
    }
}

data class OciBlobDownload(
    val descriptor: OciBlobDescriptor,
    val url: String,
    val requestHeaders: Map<String, String>,
)

class OciRegistryClient(
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
) {
    fun resolve(
        reference: OciImageReference,
        platform: OciPlatform,
    ): OciResolvedImage {
        var token: String? = null
        val top =
            fetchManifest(
                reference = reference,
                manifestReference = reference.manifestReference,
                expectedDigest = reference.digest,
                expectedSize = null,
                bearerToken = token,
            )
        token = top.bearerToken

        val root = Json.parseToJsonElement(top.body.decodeToString()).jsonObject
        val leaf =
            if ("manifests" in root) {
                val descriptor = OciManifestIndex.select(top.body.decodeToString(), platform)
                fetchManifest(
                    reference = reference,
                    manifestReference = descriptor.digest,
                    expectedDigest = descriptor.digest,
                    expectedSize = descriptor.size,
                    bearerToken = token,
                )
            } else {
                top
            }
        val manifest = Json.parseToJsonElement(leaf.body.decodeToString()).jsonObject
        require("layers" in manifest && "config" in manifest) {
            "Registry response is not an OCI image manifest"
        }

        return OciResolvedImage(
            reference = reference,
            platform = platform,
            manifestDigest = leaf.digest,
            config = parseDescriptor(manifest.getValue("config").jsonObject),
            layers = manifest.getValue("layers").jsonArray.map { parseDescriptor(it.jsonObject) },
            bearerToken = leaf.bearerToken,
        )
    }

    private fun fetchManifest(
        reference: OciImageReference,
        manifestReference: String,
        expectedDigest: String?,
        expectedSize: Long?,
        bearerToken: String?,
    ): ManifestResponse {
        val endpoint =
            URL(
                "https://${reference.registry}/v2/${reference.repository}/manifests/" +
                    manifestReference,
            )
        var token = bearerToken
        var connection = openManifestConnection(endpoint, token)
        if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
            val challenge = connection.getHeaderField("WWW-Authenticate")
            connection.disconnect()
            token = requestBearerToken(parseBearerChallenge(challenge))
            connection = openManifestConnection(endpoint, token)
        }

        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException(
                    "OCI registry returned HTTP ${connection.responseCode} for a manifest",
                )
            }
            val body = readLimited(connection, MAX_MANIFEST_BYTES)
            expectedSize?.let {
                require(body.size.toLong() == it) {
                    "OCI manifest size mismatch: got ${body.size}, expected $it"
                }
            }
            val actualDigest = sha256Digest(body)
            val headerDigest = connection.getHeaderField("Docker-Content-Digest")
            expectedDigest?.let {
                requireDigestMatch(it, actualDigest, "requested manifest")
            }
            headerDigest?.takeIf(String::isNotBlank)?.let {
                requireDigestMatch(it, actualDigest, "registry manifest")
            }
            ManifestResponse(
                body = body,
                digest = actualDigest,
                bearerToken = token,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun openManifestConnection(
        endpoint: URL,
        bearerToken: String?,
    ): HttpURLConnection =
        connectionFactory(endpoint).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", MANIFEST_ACCEPT)
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", USER_AGENT)
            bearerToken?.let {
                setRequestProperty("Authorization", "Bearer $it")
            }
        }

    private fun requestBearerToken(challenge: BearerChallenge): String {
        val separator = if ('?' in challenge.realm) '&' else '?'
        val url =
            URL(
                challenge.realm +
                    separator +
                    listOfNotNull(
                        challenge.service?.let { "service=${encode(it)}" },
                        challenge.scope?.let { "scope=${encode(it)}" },
                    ).joinToString("&"),
            )
        require(url.protocol == "https") {
            "OCI registry requested credentials over a non-HTTPS endpoint"
        }
        val connection =
            connectionFactory(url).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException(
                    "OCI registry token service returned HTTP ${connection.responseCode}",
                )
            }
            val json = Json.parseToJsonElement(readLimited(connection, MAX_TOKEN_BYTES).decodeToString())
                .jsonObject
            json["token"]?.jsonPrimitive?.contentOrNull
                ?: json["access_token"]?.jsonPrimitive?.contentOrNull
                ?: error("OCI registry token response omitted its token")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseBearerChallenge(value: String?): BearerChallenge {
        val header = value?.trim().orEmpty()
        require(header.startsWith("Bearer ", ignoreCase = true)) {
            "OCI registry did not provide a Bearer authentication challenge"
        }
        val parameters =
            CHALLENGE_PARAMETER.findAll(header.substringAfter(' '))
                .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        val realm = parameters["realm"] ?: error("OCI registry challenge omitted its realm")
        val realmUri = URI(realm)
        require(realmUri.scheme == "https" && !realmUri.host.isNullOrBlank()) {
            "OCI registry challenge has an unsafe token endpoint"
        }
        return BearerChallenge(
            realm = realm,
            service = parameters["service"],
            scope = parameters["scope"],
        )
    }

    private fun parseDescriptor(json: kotlinx.serialization.json.JsonObject): OciBlobDescriptor {
        val digest =
            json["digest"]?.jsonPrimitive?.contentOrNull
                ?: error("OCI blob descriptor omitted its digest")
        require(SHA256_DIGEST.matches(digest)) {
            "OCI blob descriptor uses an unsupported digest"
        }
        val size =
            json["size"]?.jsonPrimitive?.longOrNull
                ?: error("OCI blob descriptor omitted its size")
        require(size >= 0L) { "OCI blob descriptor has a negative size" }
        return OciBlobDescriptor(
            digest = digest,
            mediaType =
                json["mediaType"]?.jsonPrimitive?.contentOrNull
                    ?: error("OCI blob descriptor omitted its media type"),
            size = size,
        )
    }

    private fun readLimited(
        connection: HttpURLConnection,
        limit: Int,
    ): ByteArray {
        if (connection.contentLengthLong > limit) {
            throw IOException("OCI registry response is unexpectedly large")
        }
        val output = ByteArrayOutputStream()
        connection.inputStream.use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > limit) {
                    throw IOException("OCI registry response exceeded its size limit")
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun requireDigestMatch(
        expected: String,
        actual: String,
        subject: String,
    ) {
        require(SHA256_DIGEST.matches(expected) && expected == actual) {
            "$subject digest mismatch: got $actual, expected $expected"
        }
    }

    private fun sha256Digest(value: ByteArray): String =
        "sha256:" +
            MessageDigest.getInstance("SHA-256")
                .digest(value)
                .joinToString(separator = "") { "%02x".format(it) }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private data class ManifestResponse(
        val body: ByteArray,
        val digest: String,
        val bearerToken: String?,
    )

    private data class BearerChallenge(
        val realm: String,
        val service: String?,
        val scope: String?,
    )

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 20_000
        const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
        const val MAX_TOKEN_BYTES = 256 * 1024
        const val USER_AGENT = "uDroid-Android/0.1"
        const val MANIFEST_ACCEPT =
            "application/vnd.oci.image.index.v1+json, " +
                "application/vnd.docker.distribution.manifest.list.v2+json, " +
                "application/vnd.oci.image.manifest.v1+json, " +
                "application/vnd.docker.distribution.manifest.v2+json"
        val CHALLENGE_PARAMETER = Regex("""([A-Za-z][A-Za-z0-9_-]*)="([^"]*)"""")
        val SHA256_DIGEST = Regex("sha256:[a-f0-9]{64}")
    }
}
