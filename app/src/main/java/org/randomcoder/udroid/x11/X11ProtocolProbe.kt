package org.randomcoder.udroid.x11

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.EOFException
import java.io.File

internal object X11ProtocolProbe {
    fun query(socketFile: File): Result {
        if (!socketFile.exists()) return Result.NotReady("Display socket does not exist")
        return runCatching {
            LocalSocket().use { socket ->
                socket.connect(
                    LocalSocketAddress(
                        socketFile.absolutePath,
                        LocalSocketAddress.Namespace.FILESYSTEM,
                    ),
                )
                socket.soTimeout = READ_TIMEOUT_MILLIS
                socket.outputStream.write(CONNECTION_SETUP)
                socket.outputStream.flush()
                parseSetupHeader(socket.inputStream.readExactly(SETUP_HEADER_BYTES))
            }
        }.getOrElse { error ->
            Result.NotReady(error.message ?: error.javaClass.simpleName)
        }
    }

    internal fun parseSetupHeader(header: ByteArray): Result {
        require(header.size == SETUP_HEADER_BYTES)
        val major = littleEndianUnsignedShort(header, 2)
        val minor = littleEndianUnsignedShort(header, 4)
        return when (header[0].toInt() and 0xff) {
            1 -> Result.Ready(major, minor)
            0 -> Result.NotReady("X11 server rejected the connection")
            2 -> Result.NotReady("X11 server requested authentication")
            else -> Result.NotReady("Invalid X11 setup status ${header[0].toInt() and 0xff}")
        }
    }

    private fun littleEndianUnsignedShort(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun java.io.InputStream.readExactly(count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(result, offset, count - offset)
            if (read < 0) throw EOFException("X11 server closed during setup")
            offset += read
        }
        return result
    }

    sealed interface Result {
        data class Ready(
            val protocolMajor: Int,
            val protocolMinor: Int,
        ) : Result

        data class NotReady(
            val reason: String,
        ) : Result
    }

    private const val SETUP_HEADER_BYTES = 8
    private const val READ_TIMEOUT_MILLIS = 500
    private val CONNECTION_SETUP =
        byteArrayOf(
            0x6c,
            0x00,
            0x0b,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
        )
}
