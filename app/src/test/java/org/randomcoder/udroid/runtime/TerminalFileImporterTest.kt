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
    fun `valid file names are preserved`() {
        val sourceName = "  ${"a".repeat(65)}.txt  "

        assertEquals(sourceName, TerminalFileImporter.safeFileName(sourceName))
    }

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

            assertEquals("/tmp/server.pem", imported)
            assertEquals("private key", ubuntu.resolve("tmp/server.pem").readText())
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(ubuntu.resolve("tmp/server.pem").toPath()),
            )
            assertFalse(debian.resolve("tmp/server.pem").exists())
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
            assertTrue(rootfs.resolve("tmp").listFiles().orEmpty().isEmpty())
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `existing upload is replaced`() {
        val rootfs = Files.createTempDirectory("udroid-terminal-upload").toFile()
        val existing =
            rootfs.resolve("tmp/config.json").apply {
                requireNotNull(parentFile).mkdirs()
                writeText("original")
            }
        try {
            val imported =
                TerminalFileImporter.import(
                    rootfs = rootfs,
                    sourceName = "config.json",
                    source = ByteArrayInputStream("replacement".toByteArray()),
                )

            assertEquals("/tmp/config.json", imported)
            assertEquals("replacement", existing.readText())
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `upload created while copying is replaced`() {
        val rootfs = Files.createTempDirectory("udroid-terminal-upload").toFile()
        val destination = rootfs.resolve("tmp/config.json")
        destination.parentFile?.mkdirs()
        try {
            val source =
                object : ByteArrayInputStream("replacement".toByteArray()) {
                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        destination.writeText("original")
                        return super.read(buffer, offset, length)
                    }
                }

            val imported =
                TerminalFileImporter.import(
                    rootfs = rootfs,
                    sourceName = "config.json",
                    source = source,
                )

            assertEquals("/tmp/config.json", imported)
            assertEquals("replacement", destination.readText())
            assertTrue(destination.parentFile?.listFiles().orEmpty().size == 1)
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
