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

        val rootHome = root.resolve("root")
        check(!Files.isSymbolicLink(rootHome)) {
            "The Linux home directory cannot be a symbolic link"
        }
        val home = if (Files.isDirectory(rootHome, LinkOption.NOFOLLOW_LINKS)) rootHome else root
        val uploads = home.resolve("Uploads")
        if (Files.exists(uploads, LinkOption.NOFOLLOW_LINKS)) {
            check(Files.isDirectory(uploads, LinkOption.NOFOLLOW_LINKS)) {
                "~/Uploads is not a directory"
            }
        } else {
            Files.createDirectory(uploads)
        }

        val fileName = safeFileName(sourceName)
        val destination = uploads.resolve(fileName)
        check(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            "~/Uploads/$fileName already exists"
        }

        val staging = uploads.resolve(".upload-${UUID.randomUUID()}.part")
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
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging, destination)
            }
        } finally {
            Files.deleteIfExists(staging)
        }
        return "~/Uploads/$fileName"
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
                .trim()
                .take(MAX_FILE_NAME_CHARS)
        return sanitized.takeUnless { it.isBlank() || it == "." || it == ".." } ?: "upload"
    }

    private const val MAX_FILE_NAME_CHARS = 64
}
