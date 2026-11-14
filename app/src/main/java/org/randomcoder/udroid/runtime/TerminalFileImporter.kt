package org.randomcoder.udroid.runtime

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

internal object TerminalFileImporter {
    fun import(
        rootfs: File,
        sourceName: String?,
        source: InputStream,
    ): String {
        val root = rootfs.toPath().toAbsolutePath().normalize()
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            "The selected Linux system is unavailable"
        }

        val temporaryDirectory = root.resolve("tmp")
        if (Files.exists(temporaryDirectory, LinkOption.NOFOLLOW_LINKS)) {
            check(Files.isDirectory(temporaryDirectory, LinkOption.NOFOLLOW_LINKS)) {
                "/tmp is not a directory"
            }
        } else {
            Files.createDirectory(temporaryDirectory)
        }

        val fileName = safeFileName(sourceName)
        val guestDestination = "/tmp/$fileName"
        val destination = temporaryDirectory.resolve(fileName)

        val staging = temporaryDirectory.resolve(".upload-${UUID.randomUUID()}.part")
        try {
            Files.createFile(
                staging,
                PosixFilePermissions.asFileAttribute(
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                    ),
                ),
            )
            FileOutputStream(staging.toFile()).use { output ->
                source.copyTo(output)
                output.fd.sync()
            }
            try {
                Files.move(
                    staging,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(staging)
        }
        return guestDestination
    }

    internal fun safeFileName(sourceName: String?): String {
        val sanitized =
            sourceName
                .orEmpty()
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .map { character ->
                    if (character.code < 32 || character.code == 127) '_' else character
                }.joinToString("")
        return sanitized.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "upload"
    }
}
