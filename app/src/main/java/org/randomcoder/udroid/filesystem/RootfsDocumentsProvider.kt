package org.randomcoder.udroid.filesystem

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import org.randomcoder.udroid.R
import org.randomcoder.udroid.install.InstalledRootfsSourceStore
import org.randomcoder.udroid.runtime.InstalledRootfs
import org.randomcoder.udroid.runtime.InstalledRootfsRegistry
import org.randomcoder.udroid.runtime.terminalDistroTitle
import java.io.FileNotFoundException
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Base64

class RootfsDocumentsProvider : DocumentsProvider() {
    private val registry by lazy { InstalledRootfsRegistry(requireNotNull(context)) }
    private val installSources by lazy { InstalledRootfsSourceStore(requireNotNull(context)) }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        registry.all().forEach { rootfs ->
            cursor.newRow()
                .add(DocumentsContract.Root.COLUMN_ROOT_ID, rootfs.name)
                .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, RootfsDocumentId.encode(rootfs.name, ""))
                .add(DocumentsContract.Root.COLUMN_TITLE, displayName(rootfs))
                .add(DocumentsContract.Root.COLUMN_SUMMARY, "uDroid Linux file system")
                .add(DocumentsContract.Root.COLUMN_ICON, rootfsIconResource(rootfs.name))
                .add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_LOCAL_ONLY or DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
                .add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        }
        return cursor
    }

    override fun queryDocument(
        documentId: String,
        projection: Array<out String>?,
    ): Cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS).also { includeDocument(it, resolve(documentId)) }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val parent = resolve(parentDocumentId)
        if (!Files.isDirectory(parent.path)) throw FileNotFoundException("Not a directory")
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        Files.newDirectoryStream(parent.path).use { children ->
            children
                .sortedBy { it.fileName.toString().lowercase() }
                .forEach { child ->
                    val relative = listOf(parent.relative, child.fileName.toString()).filter(String::isNotEmpty).joinToString("/")
                    runCatching { resolve(RootfsDocumentId.encode(parent.rootfs.name, relative)) }
                        .getOrNull()
                        ?.takeIf { Files.isDirectory(it.path) || Files.isRegularFile(it.path) }
                        ?.let { includeDocument(cursor, it) }
                }
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Linux filesystems are read-only in Android Files")
        val document = resolve(documentId)
        if (!Files.isRegularFile(document.path)) throw FileNotFoundException("Not a regular file")
        return ParcelFileDescriptor.open(document.path.toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun isChildDocument(
        parentDocumentId: String,
        documentId: String,
    ): Boolean {
        val parent = runCatching { resolve(parentDocumentId) }.getOrNull() ?: return false
        val child = runCatching { resolve(documentId) }.getOrNull() ?: return false
        return parent.rootfs.name == child.rootfs.name &&
            (parent.relative.isEmpty() || child.relative.startsWith(parent.relative + "/")) &&
            child.relative != parent.relative
    }

    private fun includeDocument(
        cursor: MatrixCursor,
        document: ResolvedDocument,
    ) {
        val attributes = Files.readAttributes(document.path, BasicFileAttributes::class.java)
        val directory = attributes.isDirectory
        cursor.newRow()
            .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, RootfsDocumentId.encode(document.rootfs.name, document.relative))
            .add(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                if (document.relative.isEmpty()) displayName(document.rootfs) else document.relative.substringAfterLast('/'),
            )
            .add(DocumentsContract.Document.COLUMN_MIME_TYPE, if (directory) DocumentsContract.Document.MIME_TYPE_DIR else mimeType(document.path))
            .add(DocumentsContract.Document.COLUMN_FLAGS, 0)
            .add(DocumentsContract.Document.COLUMN_SIZE, if (directory) null else attributes.size())
            .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, attributes.lastModifiedTime().toMillis())
    }

    private fun resolve(documentId: String): ResolvedDocument {
        val id = runCatching { RootfsDocumentId.decode(documentId) }
            .getOrElse { throw FileNotFoundException("Invalid document id") }
        val rootfs = registry.all().firstOrNull { it.name == id.rootfs }
            ?: throw FileNotFoundException("Linux system is not installed")
        val path = runCatching { RootfsPathResolver.resolve(rootfs.directory.toPath(), id.relative) }
            .getOrElse { throw FileNotFoundException("File is outside the Linux filesystem") }
        if (!Files.exists(path)) throw FileNotFoundException("File does not exist")
        return ResolvedDocument(rootfs, id.relative, path)
    }

    private fun mimeType(path: Path): String =
        URLConnection.guessContentTypeFromName(path.fileName.toString()) ?: "application/octet-stream"

    private fun displayName(rootfs: InstalledRootfs): String =
        rootfsDisplayName(
            rootfs.name,
            runCatching { installSources.load(rootfs.name)?.displayName }.getOrNull(),
        )

    private data class ResolvedDocument(
        val rootfs: InstalledRootfs,
        val relative: String,
        val path: Path,
    )

    private companion object {
        val ROOT_COLUMNS = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
        )
        val DOCUMENT_COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

internal fun rootfsDisplayName(
    rootfsName: String,
    installedDisplayName: String?,
): String = installedDisplayName?.takeIf(String::isNotBlank) ?: terminalDistroTitle(rootfsName)

internal fun notifyRootfsDocumentsChanged(context: Context) {
    runCatching {
        context.contentResolver.notifyChange(
            DocumentsContract.buildRootsUri("${context.packageName}.linux-files"),
            null,
        )
    }.onFailure { error ->
        Log.w("uDroidFiles", "Could not notify Android Files about Linux systems", error)
    }
}

internal fun rootfsIconResource(name: String): Int {
    val normalized = name.lowercase()
    return when {
        listOf("focal", "jammy", "noble", "oracular", "plucky", "questing", "resolute", "ubuntu")
            .any(normalized::contains) -> R.drawable.distro_ubuntu
        "debian" in normalized || "trixie" in normalized || "sid" in normalized -> R.drawable.distro_debian
        "arch" in normalized -> R.drawable.distro_arch
        "alpine" in normalized -> R.drawable.distro_alpine
        "void" in normalized -> R.drawable.distro_void
        else -> R.drawable.distro_unknown
    }
}

internal data class RootfsDocumentId(
    val rootfs: String,
    val relative: String,
) {
    companion object {
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()

        fun encode(rootfs: String, relative: String): String =
            "${encoder.encodeToString(rootfs.toByteArray(StandardCharsets.UTF_8))}:${encoder.encodeToString(relative.toByteArray(StandardCharsets.UTF_8))}"

        fun decode(value: String): RootfsDocumentId {
            val separator = value.indexOf(':')
            require(separator > 0)
            val rootfs = String(decoder.decode(value.substring(0, separator)), StandardCharsets.UTF_8)
            val relative = String(decoder.decode(value.substring(separator + 1)), StandardCharsets.UTF_8)
            require(rootfs.isNotBlank() && '\u0000' !in rootfs)
            require(relative.isEmpty() || relative.split('/').none { it.isEmpty() || it == "." || it == ".." })
            return RootfsDocumentId(rootfs, relative)
        }
    }
}

internal object RootfsPathResolver {
    fun resolve(root: Path, relative: String): Path {
        val absoluteRoot = root.toAbsolutePath().normalize()
        val pending = ArrayDeque(relative.split('/').filter(String::isNotEmpty))
        val resolved = ArrayDeque<String>()
        var symlinks = 0
        while (pending.isNotEmpty()) {
            when (val part = pending.removeFirst()) {
                "." -> Unit
                ".." -> {
                    require(resolved.isNotEmpty())
                    resolved.removeLast()
                }
                else -> {
                    val candidate = resolved.fold(absoluteRoot) { path, component -> path.resolve(component) }.resolve(part)
                    if (Files.isSymbolicLink(candidate)) {
                        require(++symlinks <= 40)
                        val target = Files.readSymbolicLink(candidate)
                        if (target.isAbsolute) resolved.clear()
                        target.map(Path::toString).asReversed().forEach(pending::addFirst)
                    } else {
                        resolved.addLast(part)
                    }
                }
            }
        }
        return resolved.fold(absoluteRoot) { path, component -> path.resolve(component) }
            .normalize()
            .also { require(it.startsWith(absoluteRoot)) }
    }
}
