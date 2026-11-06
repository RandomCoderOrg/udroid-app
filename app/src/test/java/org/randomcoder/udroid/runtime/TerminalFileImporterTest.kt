package org.randomcoder.udroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

class TerminalFileImporterTest {
    @Test
    fun `imports stay inside the selected distro`() {
        val parent = Files.createTempDirectory("udroid-terminal-upload").toFile()
        try {
            val ubuntu = parent.resolve("ubuntu").apply { resolve("root").mkdirs() }
            val debian = parent.resolve("debian").apply { resolve("root").mkdirs() }

            val imported =
                TerminalFileImporter.import(
                    rootfs = ubuntu,
                    sourceName = "../../server.pem",
                    source = ByteArrayInputStream("private key".toByteArray()),
                )

            assertEquals("~/Uploads/server.pem", imported)
            assertEquals("private key", ubuntu.resolve("root/Uploads/server.pem").readText())
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(ubuntu.resolve("root/Uploads/server.pem").toPath()),
            )
            assertFalse(debian.resolve("root/Uploads/server.pem").exists())
            assertFalse(parent.resolve("server.pem").exists())
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun `failed imports remove partial files`() {
        val rootfs = Files.createTempDirectory("udroid-terminal-upload").toFile()
        rootfs.resolve("root").mkdirs()
        try {
            val failure =
                runCatching {
                    TerminalFileImporter.import(
                        rootfs = rootfs,
                        sourceName = "script.sh",
                        source = FailingInputStream(),
                    )
                }.exceptionOrNull()

            assertTrue(failure is IOException)
            assertTrue(rootfs.resolve("root/Uploads").listFiles().orEmpty().isEmpty())
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `existing upload is never overwritten`() {
        val rootfs = Files.createTempDirectory("udroid-terminal-upload").toFile()
        val existing =
            rootfs.resolve("root/Uploads/config.json").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("original")
            }
        try {
            val failure =
                runCatching {
                    TerminalFileImporter.import(
                        rootfs = rootfs,
                        sourceName = "config.json",
                        source = ByteArrayInputStream("replacement".toByteArray()),
                    )
                }.exceptionOrNull()

            assertTrue(failure is IllegalStateException)
            assertEquals("original", existing.readText())
        } finally {
            rootfs.deleteRecursively()
        }
    }

    private class FailingInputStream : InputStream() {
        private var firstRead = true

        override fun read(): Int = error("Buffered reads are expected")

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (!firstRead) throw IOException("source disconnected")
            firstRead = false
            buffer[offset] = 'x'.code.toByte()
            return 1
        }
    }
}
