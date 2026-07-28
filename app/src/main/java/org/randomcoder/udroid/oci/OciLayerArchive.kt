package org.randomcoder.udroid.oci

import android.content.Context
import org.randomcoder.udroid.install.ProotRuntime
import org.randomcoder.udroid.install.ProotTarExtractor
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

data class VerifiedOciLayer(
    val descriptor: OciBlobDescriptor,
    val file: File,
)

object OciLayerMedia {
    fun fileSuffix(mediaType: String): String =
        when (mediaType) {
            OCI_GZIP_LAYER, DOCKER_GZIP_LAYER -> ".layer.tar.gz"
            OCI_TAR_LAYER, DOCKER_TAR_LAYER -> ".layer.tar"
            else -> error("Unsupported OCI layer media type: $mediaType")
        }

    fun open(
        layer: VerifiedOciLayer,
    ): InputStream {
        val input = layer.file.inputStream().buffered()
        return when (layer.descriptor.mediaType) {
            OCI_GZIP_LAYER, DOCKER_GZIP_LAYER -> GZIPInputStream(input)
            OCI_TAR_LAYER, DOCKER_TAR_LAYER -> input
            else -> {
                input.close()
                error("Unsupported OCI layer media type: ${layer.descriptor.mediaType}")
            }
        }
    }

    private const val OCI_GZIP_LAYER = "application/vnd.oci.image.layer.v1.tar+gzip"
    private const val OCI_TAR_LAYER = "application/vnd.oci.image.layer.v1.tar"
    private const val DOCKER_GZIP_LAYER =
        "application/vnd.docker.image.rootfs.diff.tar.gzip"
    private const val DOCKER_TAR_LAYER =
        "application/vnd.docker.image.rootfs.diff.tar"
}

/**
 * Reads tar entry names without materializing layer contents.
 *
 * The scanner understands ustar prefixes, PAX path records, and GNU long-name
 * records because OCI producers may use any of them for whiteout paths.
 */
class TarEntryNameScanner {
    fun scan(input: InputStream): List<String> {
        val names = mutableListOf<String>()
        var pendingPaxPath: String? = null
        var pendingLongName: String? = null
        var zeroBlocks = 0

        while (true) {
            val header = readBlockOrNull(input) ?: break
            if (header.all { it == 0.toByte() }) {
                zeroBlocks++
                if (zeroBlocks == 2) break
                continue
            }
            zeroBlocks = 0
            requireValidChecksum(header)
            val size = parseTarNumber(header, SIZE_OFFSET, SIZE_LENGTH)
            val type = header[TYPE_OFFSET].toInt().toChar()
            val headerName = readHeaderName(header)

            when (type) {
                PAX_EXTENDED_HEADER -> {
                    pendingPaxPath = parsePaxPath(readEntryData(input, size))
                }
                PAX_GLOBAL_HEADER -> {
                    readEntryData(input, size)
                }
                GNU_LONG_NAME -> {
                    pendingLongName =
                        decodePath(readEntryData(input, size))
                            .trimEnd('\u0000', '\n')
                }
                else -> {
                    val name = pendingPaxPath ?: pendingLongName ?: headerName
                    require(name.isNotEmpty()) { "Tar entry has no path" }
                    names += name
                    pendingPaxPath = null
                    pendingLongName = null
                    skipEntryData(input, size)
                }
            }
        }
        require(pendingPaxPath == null && pendingLongName == null) {
            "Tar archive ended before extended path metadata was used"
        }
        return names
    }

    private fun readHeaderName(header: ByteArray): String {
        val name = decodeNullTerminated(header, NAME_OFFSET, NAME_LENGTH)
        val prefix = decodeNullTerminated(header, PREFIX_OFFSET, PREFIX_LENGTH)
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun parsePaxPath(data: ByteArray): String? {
        var offset = 0
        var path: String? = null
        while (offset < data.size) {
            val space =
                (offset until data.size)
                    .firstOrNull { data[it] == ' '.code.toByte() }
                    ?: -1
            require(space > offset) { "Malformed PAX record length" }
            val recordLength =
                data.copyOfRange(offset, space)
                    .decodeToString()
                    .toIntOrNull()
                    ?: error("Malformed PAX record length")
            require(recordLength > space - offset && offset + recordLength <= data.size) {
                "PAX record exceeds its tar entry"
            }
            val recordEnd = offset + recordLength
            require(data[recordEnd - 1] == '\n'.code.toByte()) {
                "PAX record is not newline terminated"
            }
            val payload = data.copyOfRange(space + 1, recordEnd - 1)
            val separator = payload.indexOf('='.code.toByte())
            require(separator > 0) { "Malformed PAX record" }
            val key = decodePath(payload.copyOfRange(0, separator))
            if (key == "path") {
                path = decodePath(payload.copyOfRange(separator + 1, payload.size))
            }
            offset = recordEnd
        }
        return path
    }

    private fun requireValidChecksum(header: ByteArray) {
        val expected = parseTarNumber(header, CHECKSUM_OFFSET, CHECKSUM_LENGTH)
        var actual = 0L
        header.forEachIndexed { index, byte ->
            actual +=
                if (index in CHECKSUM_OFFSET until CHECKSUM_OFFSET + CHECKSUM_LENGTH) {
                    ' '.code
                } else {
                    byte.toInt() and 0xff
                }
        }
        require(expected == actual) {
            "Tar header checksum mismatch: got $actual, expected $expected"
        }
    }

    private fun parseTarNumber(
        value: ByteArray,
        offset: Int,
        length: Int,
    ): Long {
        val bytes = value.copyOfRange(offset, offset + length)
        require(bytes.isNotEmpty()) { "Empty tar number" }
        if (bytes[0].toInt() and 0x80 != 0) {
            require(bytes[0].toInt() and 0x40 == 0) {
                "Negative base-256 tar numbers are not supported"
            }
            bytes[0] = (bytes[0].toInt() and 0x7f).toByte()
            var result = 0L
            bytes.forEach { byte ->
                require(result <= Long.MAX_VALUE ushr 8) { "Tar number overflows Long" }
                result = (result shl 8) or (byte.toLong() and 0xff)
            }
            return result
        }
        val text =
            bytes.decodeToString()
                .trim('\u0000', ' ')
        if (text.isEmpty()) return 0L
        return text.toLongOrNull(8) ?: error("Malformed octal tar number")
    }

    private fun readEntryData(
        input: InputStream,
        size: Long,
    ): ByteArray {
        require(size <= MAX_METADATA_BYTES) { "Tar metadata entry is unexpectedly large" }
        val data = ByteArray(size.toInt())
        readFully(input, data)
        skipPadding(input, size)
        return data
    }

    private fun skipEntryData(
        input: InputStream,
        size: Long,
    ) {
        skipFully(input, size)
        skipPadding(input, size)
    }

    private fun skipPadding(
        input: InputStream,
        size: Long,
    ) {
        val padding = (BLOCK_SIZE - size % BLOCK_SIZE) % BLOCK_SIZE
        skipFully(input, padding)
    }

    private fun readBlockOrNull(input: InputStream): ByteArray? {
        val block = ByteArray(BLOCK_SIZE)
        val first = input.read()
        if (first < 0) return null
        block[0] = first.toByte()
        readFully(input, block, 1, block.size - 1)
        return block
    }

    private fun readFully(
        input: InputStream,
        target: ByteArray,
        offset: Int = 0,
        length: Int = target.size,
    ) {
        var completed = 0
        while (completed < length) {
            val count = input.read(target, offset + completed, length - completed)
            if (count < 0) throw EOFException("Tar archive ended unexpectedly")
            completed += count
        }
    }

    private fun skipFully(
        input: InputStream,
        byteCount: Long,
    ) {
        var remaining = byteCount
        val buffer = ByteArray(SKIP_BUFFER_BYTES)
        while (remaining > 0L) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw EOFException("Tar archive ended unexpectedly")
            remaining -= count
        }
    }

    private fun decodeNullTerminated(
        value: ByteArray,
        offset: Int,
        length: Int,
    ): String {
        val end =
            (offset until offset + length)
                .firstOrNull { value[it] == 0.toByte() }
                ?: offset + length
        return decodePath(value.copyOfRange(offset, end))
    }

    private fun decodePath(value: ByteArray): String =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(value))
            .toString()

    private companion object {
        const val BLOCK_SIZE = 512
        const val NAME_OFFSET = 0
        const val NAME_LENGTH = 100
        const val SIZE_OFFSET = 124
        const val SIZE_LENGTH = 12
        const val CHECKSUM_OFFSET = 148
        const val CHECKSUM_LENGTH = 8
        const val TYPE_OFFSET = 156
        const val PREFIX_OFFSET = 345
        const val PREFIX_LENGTH = 155
        const val PAX_EXTENDED_HEADER = 'x'
        const val PAX_GLOBAL_HEADER = 'g'
        const val GNU_LONG_NAME = 'L'
        const val MAX_METADATA_BYTES = 1024L * 1024L
        const val SKIP_BUFFER_BYTES = 32 * 1024
    }
}

class OciLayerRootfsAssembler(
    private val context: Context,
    private val runtime: ProotRuntime,
    private val scanner: TarEntryNameScanner = TarEntryNameScanner(),
    private val whiteoutApplier: OciWhiteoutApplier = OciWhiteoutApplier(),
) {
    fun assemble(
        layers: List<VerifiedOciLayer>,
        destination: File,
        onLayerProgress: (
            layerIndex: Int,
            layerCount: Int,
            completedBytes: Long,
            totalBytes: Long,
        ) -> Unit = { _, _, _, _ -> },
    ) {
        require(layers.isNotEmpty()) { "OCI image manifest has no filesystem layers" }
        check(destination.mkdirs() || destination.isDirectory) {
            "Could not create OCI rootfs destination"
        }
        layers.forEachIndexed { index, layer ->
            require(layer.file.isFile) { "Verified OCI layer is missing: ${layer.file.name}" }
            val entries = OciLayerMedia.open(layer).use(scanner::scan)
            val whiteouts = OciWhiteoutPlan.fromArchiveEntries(entries)
            whiteoutApplier.apply(destination, whiteouts)
            ProotTarExtractor(
                context = context,
                runtime = runtime,
                excludeOciWhiteouts = true,
            ).extract(layer.file, destination) { completed, total ->
                onLayerProgress(index, layers.size, completed, total)
            }
        }
    }
}
