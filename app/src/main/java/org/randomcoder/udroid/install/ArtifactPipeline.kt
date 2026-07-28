package org.randomcoder.udroid.install

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.max

data class ArtifactRequest(
    val url: String,
    val expectedSha256: String,
    val stagingFile: File,
    val finalFile: File,
    val requestHeaders: Map<String, String> = emptyMap(),
)

data class ByteProgress(
    val completedBytes: Long,
    val totalBytes: Long,
    val resumed: Boolean,
)

data class ArtifactResult(
    val file: File,
    val byteCount: Long,
    val resumed: Boolean,
    val reusedVerifiedFile: Boolean,
)

class ChecksumMismatchException(
    val expected: String,
    val actual: String,
) : IOException("SHA-256 mismatch: got $actual, expected $expected")

class ResumableArtifactPipeline(
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
    private val minimumFreeHeadroomBytes: Long = DEFAULT_FREE_HEADROOM_BYTES,
) {
    fun execute(
        request: ArtifactRequest,
        onDownloadProgress: (ByteProgress) -> Unit = {},
        onVerifyProgress: (ByteProgress) -> Unit = {},
    ): ArtifactResult {
        validateRequest(request)
        request.finalFile.parentFile?.mkdirs()
        request.stagingFile.parentFile?.mkdirs()

        if (request.finalFile.isFile) {
            val actual =
                sha256(
                    request.finalFile,
                    resumed = false,
                    onProgress = onVerifyProgress,
                )
            if (actual.equals(request.expectedSha256, ignoreCase = true)) {
                return ArtifactResult(
                    file = request.finalFile,
                    byteCount = request.finalFile.length(),
                    resumed = false,
                    reusedVerifiedFile = true,
                )
            }
            check(request.finalFile.delete()) {
                "Could not discard corrupt cached artifact ${request.finalFile}"
            }
        }

        val resumed = download(request, onDownloadProgress, allowRangeRetry = true)
        val actual =
            sha256(
                request.stagingFile,
                resumed = resumed,
                onProgress = onVerifyProgress,
            )
        if (!actual.equals(request.expectedSha256, ignoreCase = true)) {
            request.stagingFile.delete()
            throw ChecksumMismatchException(request.expectedSha256.lowercase(), actual)
        }

        if (request.finalFile.exists() && !request.finalFile.delete()) {
            throw IOException("Could not replace ${request.finalFile}")
        }
        if (!request.stagingFile.renameTo(request.finalFile)) {
            throw IOException(
                "Could not atomically promote ${request.stagingFile} to ${request.finalFile}",
            )
        }
        return ArtifactResult(
            file = request.finalFile,
            byteCount = request.finalFile.length(),
            resumed = resumed,
            reusedVerifiedFile = false,
        )
    }

    private fun download(
        request: ArtifactRequest,
        onProgress: (ByteProgress) -> Unit,
        allowRangeRetry: Boolean,
    ): Boolean {
        checkInterrupted()
        val existingBytes = request.stagingFile.length().coerceAtLeast(0L)
        val connection =
            openFollowingRedirects(
                initialUrl = request.url,
                existingBytes = existingBytes,
                requestHeaders = request.requestHeaders,
            )

        return try {
            val responseCode = connection.responseCode
            if (responseCode == HTTP_RANGE_NOT_SATISFIABLE) {
                val serverTotal = parseUnsatisfiedTotal(connection.getHeaderField("Content-Range"))
                if (serverTotal != null && serverTotal == existingBytes) {
                    onProgress(ByteProgress(existingBytes, serverTotal, resumed = true))
                    return true
                }
                if (allowRangeRetry && request.stagingFile.delete()) {
                    return download(request, onProgress, allowRangeRetry = false)
                }
                throw IOException("Server rejected resume at byte $existingBytes")
            }

            val response =
                when (responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> {
                        val range =
                            parseContentRange(connection.getHeaderField("Content-Range"))
                                ?: throw IOException("206 response omitted a valid Content-Range")
                        if (range.start != existingBytes) {
                            throw IOException(
                                "Resume response starts at ${range.start}, expected $existingBytes",
                            )
                        }
                        ResponsePlan(
                            append = true,
                            resumed = existingBytes > 0L,
                            startingBytes = existingBytes,
                            totalBytes = range.total,
                        )
                    }

                    HttpURLConnection.HTTP_OK ->
                        ResponsePlan(
                            append = false,
                            resumed = false,
                            startingBytes = 0L,
                            totalBytes = connection.contentLengthLong,
                        )

                    else ->
                        throw IOException(
                            "Artifact server returned HTTP $responseCode ${connection.responseMessage}",
                        )
                }

            preflightStorage(
                directory = request.stagingFile.parentFile ?: request.stagingFile.absoluteFile.parentFile,
                remainingBytes =
                    if (response.totalBytes > 0L) {
                        max(0L, response.totalBytes - response.startingBytes)
                    } else {
                        -1L
                    },
            )

            var completedBytes = response.startingBytes
            onProgress(
                ByteProgress(
                    completedBytes = completedBytes,
                    totalBytes = response.totalBytes,
                    resumed = response.resumed,
                ),
            )
            connection.inputStream.use { input ->
                FileOutputStream(request.stagingFile, response.append).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        checkInterrupted()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        completedBytes += read
                        onProgress(
                            ByteProgress(
                                completedBytes = completedBytes,
                                totalBytes = response.totalBytes,
                                resumed = response.resumed,
                            ),
                        )
                    }
                    output.fd.sync()
                }
            }
            if (response.totalBytes > 0L && completedBytes != response.totalBytes) {
                throw IOException(
                    "Download ended at $completedBytes bytes; expected ${response.totalBytes}",
                )
            }
            response.resumed
        } finally {
            connection.disconnect()
        }
    }

    private fun openFollowingRedirects(
        initialUrl: String,
        existingBytes: Long,
        requestHeaders: Map<String, String>,
    ): HttpURLConnection {
        val initial = URL(initialUrl)
        val initialProtocol = initial.protocol
        var currentUrl = initial
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection =
                connectionFactory(currentUrl).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("User-Agent", "uDroid-Android/0.1")
                    if (existingBytes > 0L) {
                        setRequestProperty("Range", "bytes=$existingBytes-")
                    }
                    if (sameOrigin(initial, currentUrl)) {
                        requestHeaders.forEach(::setRequestProperty)
                    }
                }
            val responseCode = connection.responseCode
            if (responseCode !in REDIRECT_CODES) return connection
            if (redirectCount == MAX_REDIRECTS) {
                connection.disconnect()
                throw IOException("Artifact URL exceeded $MAX_REDIRECTS redirects")
            }
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank()) {
                throw IOException("Artifact redirect omitted Location")
            }
            val nextUrl = URL(currentUrl, location)
            if (initialProtocol == "https" && nextUrl.protocol != "https") {
                throw IOException("Artifact redirect attempted to leave HTTPS")
            }
            currentUrl = nextUrl
        }
        throw IOException("Artifact redirect resolution failed")
    }

    private fun sameOrigin(
        left: URL,
        right: URL,
    ): Boolean =
        left.protocol.equals(right.protocol, ignoreCase = true) &&
            left.host.equals(right.host, ignoreCase = true) &&
            effectivePort(left) == effectivePort(right)

    private fun effectivePort(url: URL): Int =
        if (url.port >= 0) url.port else url.defaultPort

    /*
     * Download planning stays below this boundary so callers see only byte
     * progress. Redirects, Range validation and safe restart remain transport
     * details rather than leaking into the service or UI.
     */

    private fun sha256(
        file: File,
        resumed: Boolean,
        onProgress: (ByteProgress) -> Unit,
    ): String {
        val totalBytes = file.length()
        var completedBytes = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        onProgress(ByteProgress(0L, totalBytes, resumed))
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                checkInterrupted()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                completedBytes += read
                onProgress(ByteProgress(completedBytes, totalBytes, resumed))
            }
        }
        return digest.digest().joinToString(separator = "") { "%02x".format(it) }
    }

    private fun validateRequest(request: ArtifactRequest) {
        require(request.url.startsWith("https://") || request.url.startsWith("http://")) {
            "Artifact URL must use HTTP or HTTPS"
        }
        require(SHA256.matches(request.expectedSha256)) {
            "A 64-character SHA-256 is required"
        }
        require(request.stagingFile.absolutePath != request.finalFile.absolutePath) {
            "Staging and final artifact paths must differ"
        }
        request.requestHeaders.forEach { (name, value) ->
            require(SAFE_REQUEST_HEADER.matches(name)) {
                "Invalid artifact request header name"
            }
            require(name.lowercase() !in RESERVED_REQUEST_HEADERS) {
                "Artifact request header $name is controlled by uDroid"
            }
            require('\r' !in value && '\n' !in value) {
                "Invalid artifact request header value"
            }
        }
    }

    private fun preflightStorage(
        directory: File,
        remainingBytes: Long,
    ) {
        if (remainingBytes < 0L) return
        val required = remainingBytes + minimumFreeHeadroomBytes
        if (directory.usableSpace < required) {
            throw IOException(
                "Not enough storage: need ${formatBytes(required)}, " +
                    "available ${formatBytes(directory.usableSpace)}",
            )
        }
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("Artifact operation cancelled")
        }
    }

    private data class ResponsePlan(
        val append: Boolean,
        val resumed: Boolean,
        val startingBytes: Long,
        val totalBytes: Long,
    )

    private data class ContentRange(
        val start: Long,
        val end: Long,
        val total: Long,
    )

    private fun parseContentRange(value: String?): ContentRange? {
        val match = CONTENT_RANGE.matchEntire(value.orEmpty()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (end < start || total <= end) return null
        return ContentRange(start = start, end = end, total = total)
    }

    private fun parseUnsatisfiedTotal(value: String?): Long? =
        UNSATISFIED_RANGE.matchEntire(value.orEmpty())
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()

    private fun formatBytes(bytes: Long): String =
        when {
            bytes >= 1024L * 1024L * 1024L -> "%.1f GiB".format(bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> "%.1f MiB".format(bytes / (1024.0 * 1024.0))
            else -> "$bytes bytes"
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val BUFFER_BYTES = 64 * 1024
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val MAX_REDIRECTS = 5
        const val DEFAULT_FREE_HEADROOM_BYTES = 64L * 1024L * 1024L
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val SHA256 = Regex("^[0-9a-fA-F]{64}$")
        val CONTENT_RANGE = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$")
        val UNSATISFIED_RANGE = Regex("^bytes \\*/(\\d+)$")
        val SAFE_REQUEST_HEADER = Regex("[A-Za-z0-9-]+")
        val RESERVED_REQUEST_HEADERS =
            setOf("host", "range", "accept-encoding", "content-length", "user-agent")
    }
}
