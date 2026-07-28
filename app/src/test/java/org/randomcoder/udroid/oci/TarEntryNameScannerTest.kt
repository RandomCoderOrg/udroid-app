package org.randomcoder.udroid.oci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class TarEntryNameScannerTest {
    @Test
    fun `scanner reads ustar prefixes and normal paths`() {
        val archive =
            tar(
                entry("etc/hosts"),
                entry(
                    name = ".wh.legacy",
                    prefix = "usr/share/example",
                ),
            )

        assertEquals(
            listOf("etc/hosts", "usr/share/example/.wh.legacy"),
            TarEntryNameScanner().scan(ByteArrayInputStream(archive)),
        )
    }

    @Test
    fun `scanner applies PAX path to the following entry`() {
        val path = "usr/share/a-very-long-directory/.wh..wh..opq"
        val paxData = paxRecord("path", path)
        val archive =
            tar(
                entry("PaxHeader", paxData, type = 'x'),
                entry("placeholder"),
            )

        assertEquals(
            listOf(path),
            TarEntryNameScanner().scan(ByteArrayInputStream(archive)),
        )
    }

    @Test
    fun `scanner ignores binary PAX extended attributes while reading paths`() {
        val path = "usr/bin/.wh.legacy"
        val paxData =
            paxRecord(
                "SCHILY.xattr.security.capability",
                byteArrayOf(0x01, 0x00, 0x00, 0x02, 0x80.toByte(), 0x00),
            ) + paxRecord("path", path)
        val archive =
            tar(
                entry("PaxHeader", paxData, type = 'x'),
                entry("placeholder"),
            )

        assertEquals(
            listOf(path),
            TarEntryNameScanner().scan(ByteArrayInputStream(archive)),
        )
    }

    @Test
    fun `scanner applies GNU long name to the following entry`() {
        val path = "opt/" + "nested/".repeat(20) + ".wh.old"
        val archive =
            tar(
                entry("././@LongLink", (path + '\u0000').toByteArray(), type = 'L'),
                entry("placeholder"),
            )

        assertEquals(
            listOf(path),
            TarEntryNameScanner().scan(ByteArrayInputStream(archive)),
        )
    }

    @Test
    fun `scanner rejects a corrupt tar header`() {
        val archive = tar(entry("etc/hosts")).also { it[0] = 'X'.code.toByte() }

        assertThrows(IllegalArgumentException::class.java) {
            TarEntryNameScanner().scan(ByteArrayInputStream(archive))
        }
    }

    private fun tar(vararg entries: ByteArray): ByteArray =
        ByteArrayOutputStream().use { output ->
            entries.forEach(output::write)
            output.write(ByteArray(BLOCK_SIZE * 2))
            output.toByteArray()
        }

    private fun entry(
        name: String,
        data: ByteArray = byteArrayOf(),
        type: Char = '0',
        prefix: String = "",
    ): ByteArray {
        val header = ByteArray(BLOCK_SIZE)
        writeText(header, 0, 100, name)
        writeOctal(header, 100, 8, 0b111101101)
        writeOctal(header, 108, 8, 0)
        writeOctal(header, 116, 8, 0)
        writeOctal(header, 124, 12, data.size.toLong())
        writeOctal(header, 136, 12, 0)
        repeat(8) { header[148 + it] = ' '.code.toByte() }
        header[156] = type.code.toByte()
        writeText(header, 257, 6, "ustar")
        writeText(header, 263, 2, "00")
        writeText(header, 345, 155, prefix)
        val checksum = header.sumOf { it.toInt() and 0xff }.toLong()
        val checksumText = checksum.toString(8).padStart(6, '0')
        writeText(header, 148, 6, checksumText)
        header[154] = 0
        header[155] = ' '.code.toByte()

        return ByteArrayOutputStream().use { output ->
            output.write(header)
            output.write(data)
            val padding = (BLOCK_SIZE - data.size % BLOCK_SIZE) % BLOCK_SIZE
            output.write(ByteArray(padding))
            output.toByteArray()
        }
    }

    private fun paxRecord(
        key: String,
        value: String,
    ): ByteArray = paxRecord(key, value.toByteArray(StandardCharsets.UTF_8))

    private fun paxRecord(
        key: String,
        value: ByteArray,
    ): ByteArray {
        val payload =
            key.toByteArray(StandardCharsets.UTF_8) +
                byteArrayOf('='.code.toByte()) +
                value +
                byteArrayOf('\n'.code.toByte())
        var length = payload.size + 2
        while (true) {
            val record =
                "$length ".toByteArray(StandardCharsets.UTF_8) +
                    payload
            if (record.size == length) return record
            length = record.size
        }
    }

    private fun writeText(
        target: ByteArray,
        offset: Int,
        length: Int,
        value: String,
    ) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= length)
        bytes.copyInto(target, offset)
    }

    private fun writeOctal(
        target: ByteArray,
        offset: Int,
        length: Int,
        value: Long,
    ) {
        val text = value.toString(8).padStart(length - 1, '0')
        writeText(target, offset, length - 1, text)
        target[offset + length - 1] = 0
    }

    private companion object {
        const val BLOCK_SIZE = 512
    }
}
