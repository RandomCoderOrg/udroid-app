package org.randomcoder.udroid.catalog

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class DistroArchiveSizeStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("distro-archive-sizes", Context.MODE_PRIVATE)

    fun cached(distro: DistroVariant): Long? =
        preferences.getLong(distro.cacheKey(), -1L).takeIf { it > 0L }

    fun resolve(distro: DistroVariant): Long {
        cached(distro)?.let { return it }
        val size = probe(distro.downloadUrl)
        check(preferences.edit().putLong(distro.cacheKey(), size).commit()) {
            "Could not cache the Linux image size"
        }
        return size
    }

    private fun probe(url: String): Long {
        open(url, "HEAD").useConnection { connection ->
            val size = totalBytesFromHeaders(
                connection.responseCode,
                connection.contentLengthLong,
                connection.getHeaderField("Content-Range"),
            )
            if (size != null) return size
        }
        open(url, "GET").apply {
            setRequestProperty("Range", "bytes=0-0")
        }.useConnection { connection ->
            return totalBytesFromHeaders(
                connection.responseCode,
                connection.contentLengthLong,
                connection.getHeaderField("Content-Range"),
            ) ?: throw IOException("The image server did not provide a download size")
        }
    }

    private fun open(
        url: String,
        method: String,
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            requestMethod = method
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "uDroid-Android/0.1")
        }

    private inline fun <T> HttpURLConnection.useConnection(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }

    private fun DistroVariant.cacheKey(): String =
        "${sha256.ifBlank { downloadUrl }}:${architecture}"
}

internal fun totalBytesFromHeaders(
    responseCode: Int,
    contentLength: Long,
    contentRange: String?,
): Long? {
    if (responseCode !in 200..299) return null
    val rangedTotal = contentRange?.substringAfterLast('/')?.toLongOrNull()
    return rangedTotal?.takeIf { it > 0L } ?: contentLength.takeIf { it > 0L }
}
