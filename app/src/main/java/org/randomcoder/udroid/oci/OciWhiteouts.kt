package org.randomcoder.udroid.oci

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

data class OciWhiteoutPlan(
    val deletions: Set<String>,
    val opaqueDirectories: Set<String>,
) {
    companion object {
        fun fromArchiveEntries(entries: List<String>): OciWhiteoutPlan {
            val deletions = linkedSetOf<String>()
            val opaqueDirectories = linkedSetOf<String>()
            entries.forEach { rawEntry ->
                val entry = normalizeArchivePath(rawEntry) ?: return@forEach
                val name = entry.substringAfterLast('/')
                val parent = entry.substringBeforeLast('/', missingDelimiterValue = "")
                when {
                    name == OPAQUE_WHITEOUT -> opaqueDirectories += parent
                    name.startsWith(WHITEOUT_PREFIX) -> {
                        val targetName = name.removePrefix(WHITEOUT_PREFIX)
                        require(targetName.isNotEmpty()) {
                            "OCI layer contains an empty whiteout target"
                        }
                        deletions +=
                            if (parent.isEmpty()) {
                                targetName
                            } else {
                                "$parent/$targetName"
                            }
                    }
                }
            }
            return OciWhiteoutPlan(
                deletions = deletions,
                opaqueDirectories = opaqueDirectories,
            )
        }

        private fun normalizeArchivePath(value: String): String? {
            val normalized = value.removePrefix("./").trimEnd('/')
            if (normalized.isEmpty()) return null
            require(!normalized.startsWith('/')) {
                "OCI layer contains an absolute archive path"
            }
            require('\u0000' !in normalized) {
                "OCI layer contains a NUL in an archive path"
            }
            require(normalized.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
                "OCI layer contains an unsafe archive path"
            }
            return normalized
        }

        private const val WHITEOUT_PREFIX = ".wh."
        private const val OPAQUE_WHITEOUT = ".wh..wh..opq"
    }
}

class OciWhiteoutApplier {
    fun apply(
        rootfs: File,
        plan: OciWhiteoutPlan,
    ) {
        require(rootfs.isDirectory) { "OCI rootfs destination does not exist" }
        val root = rootfs.toPath().toAbsolutePath().normalize()

        plan.opaqueDirectories
            .sortedBy(String::length)
            .forEach { relative ->
                val directory = resolveInside(root, relative)
                requireNoSymlinkParents(root, directory)
                if (Files.isSymbolicLink(directory)) {
                    Files.delete(directory)
                } else if (Files.isDirectory(directory)) {
                    Files.newDirectoryStream(directory).use { children ->
                        children.forEach(::deleteTreeWithoutFollowingLinks)
                    }
                }
            }
        plan.deletions
            .sortedByDescending(String::length)
            .forEach { relative ->
                val target = resolveInside(root, relative)
                requireNoSymlinkParents(root, target)
                if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    deleteTreeWithoutFollowingLinks(target)
                }
            }
    }

    private fun resolveInside(
        root: Path,
        relative: String,
    ): Path {
        val target = if (relative.isEmpty()) root else root.resolve(relative).normalize()
        require(target.startsWith(root)) { "OCI whiteout escaped the rootfs" }
        return target
    }

    private fun requireNoSymlinkParents(
        root: Path,
        target: Path,
    ) {
        var current = target.parent
        while (current != null && current != root) {
            require(!Files.isSymbolicLink(current)) {
                "OCI whiteout crosses a rootfs symlink: ${root.relativize(current)}"
            }
            current = current.parent
        }
    }

    private fun deleteTreeWithoutFollowingLinks(path: Path) {
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    error: java.io.IOException?,
                ): FileVisitResult {
                    error?.let { throw it }
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
}
