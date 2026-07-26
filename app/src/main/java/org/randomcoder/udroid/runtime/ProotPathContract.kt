package org.randomcoder.udroid.runtime

import android.content.Context
import java.io.File
import java.nio.file.Files

/**
 * Keeps PRoot's root path spelling stable for --link2symlink.
 *
 * Android exposes the primary app data directory through both /data/user/0
 * and /data/data. PRoot records absolute host paths when translating archive
 * hard links, so later launches must use the same alias or those links appear
 * to be missing inside the guest.
 */
object ProotPathContract {
    fun rootfsPath(
        context: Context,
        rootfs: File,
    ): String =
        preferLegacyDataAlias(
            packageName = context.packageName,
            dataDirectory = File(context.applicationInfo.dataDir),
            file = rootfs,
        )

    internal fun preferLegacyDataAlias(
        packageName: String,
        dataDirectory: File,
        file: File,
        legacyDataDirectory: File = File("/data/data/$packageName"),
    ): String {
        val dataPath = dataDirectory.absoluteFile.toPath().normalize()
        val filePath = file.absoluteFile.toPath().normalize()
        if (filePath != dataPath && !filePath.startsWith(dataPath)) {
            return file.absolutePath
        }
        val aliasesSameDirectory =
            runCatching {
                Files.isSameFile(dataPath, legacyDataDirectory.toPath())
            }.getOrDefault(false)
        if (!aliasesSameDirectory) {
            return file.absolutePath
        }
        val relative = dataPath.relativize(filePath)
        return File(legacyDataDirectory, relative.toString()).absolutePath
    }
}
