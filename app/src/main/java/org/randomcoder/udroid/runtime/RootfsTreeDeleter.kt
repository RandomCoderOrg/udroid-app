package org.randomcoder.udroid.runtime

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

internal object RootfsTreeDeleter {
    private val safeName = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")

    fun delete(
        rootfsDirectory: Path,
        installationName: String,
    ) {
        require(safeName.matches(installationName)) {
            "Unsafe rootfs name: $installationName"
        }
        val parent = rootfsDirectory.toAbsolutePath().normalize()
        val target = parent.resolve(installationName).normalize()
        require(target.parent == parent) {
            "Rootfs must be a direct child of rootfs storage"
        }
        require(Files.isDirectory(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            "Linux system $installationName is not an installed directory"
        }

        Files.walkFileTree(
            target,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    directory.toFile().setWritable(true, true)
                    directory.toFile().setExecutable(true, true)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    directory: Path,
                    error: IOException?,
                ): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
