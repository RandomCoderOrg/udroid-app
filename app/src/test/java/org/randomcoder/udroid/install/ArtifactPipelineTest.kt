package org.randomcoder.udroid.install

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ArtifactPipelineTest {
    private val servers = mutableListOf<TinyHttpServer>()
    private val temporaryDirectories = mutableListOf<File>()

    @After
    fun cleanUp() {
        servers.forEach(TinyHttpServer::close)
        temporaryDirectories.forEach(File::deleteRecursively)
    }

    @Test
    fun `fresh artifact is verified before atomic promotion`() {
        val bytes = payload()
        val fixture = startServer {
            TinyResponse(status = 200, body = bytes)
        }
        val directory = tempDirectory()
        val staging = File(directory, "image.part")
        val final = File(directory, "image.tar.gz")

        val result =
            pipeline().execute(
                ArtifactRequest(fixture, sha256(bytes), staging, final),
            )

        assertTrue(final.isFile)
        assertFalse(staging.exists())
        assertArrayEquals(bytes, final.readBytes())
        assertFalse(result.resumed)
        assertFalse(result.reusedVerifiedFile)
        assertEquals(null, servers.last().lastHeaders["range"])
    }

    @Test
    fun `partial artifact resumes with validated content range`() {
        val bytes = payload()
        val resumeAt = 91_337
        val fixture = startServer {
            TinyResponse(
                status = 206,
                headers =
                    mapOf(
                        "Content-Range" to "bytes $resumeAt-${bytes.lastIndex}/${bytes.size}",
                    ),
                body = bytes.copyOfRange(resumeAt, bytes.size),
            )
        }
        val directory = tempDirectory()
        val staging = File(directory, "image.part")
        staging.writeBytes(bytes.copyOfRange(0, resumeAt))
        val final = File(directory, "image.tar.gz")

        val result =
            pipeline().execute(
                ArtifactRequest(fixture, sha256(bytes), staging, final),
            )

        assertTrue(result.resumed)
        assertArrayEquals(bytes, final.readBytes())
        assertEquals("bytes=$resumeAt-", servers.last().lastHeaders["range"])
    }

    @Test
    fun `server ignoring range safely restarts instead of appending`() {
        val bytes = payload()
        val fixture = startServer {
            TinyResponse(status = 200, body = bytes)
        }
        val directory = tempDirectory()
        val staging = File(directory, "image.part")
        staging.writeBytes(byteArrayOf(1, 2, 3, 4))
        val final = File(directory, "image.tar.gz")

        val result =
            pipeline().execute(
                ArtifactRequest(fixture, sha256(bytes), staging, final),
            )

        assertFalse(result.resumed)
        assertArrayEquals(bytes, final.readBytes())
        assertTrue(servers.last().lastHeaders.getValue("range").startsWith("bytes="))
    }

    @Test
    fun `checksum mismatch deletes staging artifact and never promotes it`() {
        val bytes = payload()
        val fixture = startServer { TinyResponse(status = 200, body = bytes) }
        val directory = tempDirectory()
        val staging = File(directory, "image.part")
        val final = File(directory, "image.tar.gz")

        val error =
            runCatching {
                pipeline().execute(
                    ArtifactRequest(fixture, "0".repeat(64), staging, final),
                )
            }.exceptionOrNull()

        assertTrue(error is ChecksumMismatchException)
        assertFalse(staging.exists())
        assertFalse(final.exists())
    }

    @Test
    fun `verified cached artifact is reused without network access`() {
        val bytes = payload()
        val directory = tempDirectory()
        val staging = File(directory, "image.part")
        val final = File(directory, "image.tar.gz").apply { writeBytes(bytes) }
        val pipeline =
            ResumableArtifactPipeline(
                connectionFactory = { error("network should not be opened") },
                minimumFreeHeadroomBytes = 0L,
            )

        val result =
            pipeline.execute(
                ArtifactRequest("https://example.test/image", sha256(bytes), staging, final),
            )

        assertTrue(result.reusedVerifiedFile)
        assertArrayEquals(bytes, final.readBytes())
    }

    @Test
    fun `resume range is preserved across release asset redirect`() {
        val bytes = payload()
        val resumeAt = 64_000
        val target =
            startServer {
                TinyResponse(
                    status = 206,
                    headers =
                        mapOf(
                            "Content-Range" to
                                "bytes $resumeAt-${bytes.lastIndex}/${bytes.size}",
                        ),
                    body = bytes.copyOfRange(resumeAt, bytes.size),
                )
            }
        val redirect =
            startServer {
                TinyResponse(
                    status = 302,
                    headers = mapOf("Location" to target),
                    body = byteArrayOf(),
                )
            }
        val directory = tempDirectory()
        val staging =
            File(directory, "image.part").apply {
                writeBytes(bytes.copyOfRange(0, resumeAt))
            }
        val final = File(directory, "image.tar.gz")

        val result =
            pipeline().execute(
                ArtifactRequest(redirect, sha256(bytes), staging, final),
            )

        assertTrue(result.resumed)
        assertEquals("bytes=$resumeAt-", servers[0].lastHeaders["range"])
        assertEquals("bytes=$resumeAt-", servers[1].lastHeaders["range"])
        assertArrayEquals(bytes, final.readBytes())
    }

    @Test
    fun `sensitive request headers never follow a cross-origin redirect`() {
        val bytes = payload()
        val target =
            startServer { headers ->
                assertEquals(null, headers["authorization"])
                TinyResponse(status = 200, body = bytes)
            }
        val redirect =
            startServer { headers ->
                assertEquals("Bearer registry-token", headers["authorization"])
                TinyResponse(
                    status = 302,
                    headers = mapOf("Location" to target),
                    body = byteArrayOf(),
                )
            }
        val directory = tempDirectory()
        val staging = File(directory, "layer.part")
        val final = File(directory, "layer.tar.gz")

        pipeline().execute(
            ArtifactRequest(
                url = redirect,
                expectedSha256 = sha256(bytes),
                stagingFile = staging,
                finalFile = final,
                requestHeaders = mapOf("Authorization" to "Bearer registry-token"),
            ),
        )

        assertArrayEquals(bytes, final.readBytes())
    }

    private fun pipeline() =
        ResumableArtifactPipeline(minimumFreeHeadroomBytes = 0L)

    private fun payload(): ByteArray =
        ByteArray(384 * 1024) { index -> ((index * 31) xor (index ushr 3)).toByte() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { "%02x".format(it) }

    private fun tempDirectory(): File =
        Files.createTempDirectory("udroid-artifact-test")
            .toFile()
            .also(temporaryDirectories::add)

    private fun startServer(handler: (Map<String, String>) -> TinyResponse): String {
        val server = TinyHttpServer(handler)
        servers += server
        return "http://127.0.0.1:${server.port}/artifact"
    }

    private data class TinyResponse(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray,
    )

    private class TinyHttpServer(
        private val handler: (Map<String, String>) -> TinyResponse,
    ) : AutoCloseable {
        private val socket =
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            }
        private val failure = AtomicReference<Throwable?>(null)
        private val worker =
            thread(name = "udroid-test-http", isDaemon = true) {
                try {
                    socket.accept().use { client ->
                        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                        reader.readLine()
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val separator = line.indexOf(':')
                            if (separator > 0) {
                                headers[line.substring(0, separator).trim().lowercase()] =
                                    line.substring(separator + 1).trim()
                            }
                        }
                        lastHeaders = headers
                        val response = handler(headers)
                        val reason =
                            when (response.status) {
                                200 -> "OK"
                                206 -> "Partial Content"
                                416 -> "Range Not Satisfiable"
                                else -> "Test Response"
                            }
                        val output = client.getOutputStream()
                        output.write("HTTP/1.1 ${response.status} $reason\r\n".toByteArray())
                        response.headers.forEach { (name, value) ->
                            output.write("$name: $value\r\n".toByteArray())
                        }
                        output.write("Content-Length: ${response.body.size}\r\n".toByteArray())
                        output.write("Connection: close\r\n\r\n".toByteArray())
                        output.write(response.body)
                        output.flush()
                    }
                } catch (error: Throwable) {
                    if (!socket.isClosed) failure.set(error)
                }
            }

        val port: Int
            get() = socket.localPort

        @Volatile
        var lastHeaders: Map<String, String> = emptyMap()
            private set

        override fun close() {
            socket.close()
            worker.join(2_000)
            failure.get()?.let { throw AssertionError("Fixture server failed", it) }
        }
    }
}
