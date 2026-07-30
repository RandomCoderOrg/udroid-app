package org.randomcoder.udroid.runtime

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque

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

        val pending = ArrayDeque<TraversalEntry>()
        pending.addLast(TraversalEntry(target, expanded = false))
        try {
            while (pending.isNotEmpty()) {
                val entry = pending.removeLast()
                val attributes =
                    Files.readAttributes(
                        entry.path,
                        BasicFileAttributes::class.java,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS,
                    )
                if (!attributes.isDirectory) {
                    Files.delete(entry.path)
                } else if (entry.expanded) {
                    Files.delete(entry.path)
                } else {
                    makeTraversable(entry.path)
                    pending.addLast(entry.copy(expanded = true))
                    Files.newDirectoryStream(entry.path).use { children ->
                        children.forEach { child ->
                            pending.addLast(TraversalEntry(child, expanded = false))
                        }
                    }
                }
            }
        } catch (error: IOException) {
            val failedPath =
                (error as? java.nio.file.FileSystemException)
                    ?.file
                    ?.let { java.io.File(it).toPath() }
                    ?.let { path ->
                        runCatching { parent.relativize(path.toAbsolutePath().normalize()) }
                            .getOrNull()
                            ?.toString()
                    }?.takeIf(String::isNotBlank)
                    ?: installationName
            throw IOException(
                "Could not remove $failedPath" +
                    (error.message
                        ?.takeUnless { it == (error as? java.nio.file.FileSystemException)?.file }
                        ?.let { ": $it" }
                        ?: ""),
                error,
            )
        }
    }

    private fun makeTraversable(directory: Path) {
        val file = directory.toFile()
        file.setReadable(true, true)
        file.setWritable(true, true)
        file.setExecutable(true, true)
    }

    private data class TraversalEntry(
        val path: Path,
        val expanded: Boolean,
    )
}
