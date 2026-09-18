package org.randomcoder.udroid.runtime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

data class AndroidStorageVolume(
    val label: String,
    val hostPath: String,
    val state: String,
    val primary: Boolean,
    val removable: Boolean,
) {
    val mounted: Boolean
        get() = state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY

    val guestTarget: String
        get() = AndroidStorageMounts.guestTarget(primary, hostPath)
}

object AndroidStorageMounts {
    fun discover(context: Context): List<AndroidStorageVolume> {
        val manager = context.getSystemService(StorageManager::class.java)
        val volumes = manager.storageVolumes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return volumes.filter { it.isPrimary }.mapNotNull { volume ->
                volume.directory?.let { directory ->
                    AndroidStorageVolume(
                        label = volume.getDescription(context),
                        hostPath = directory.absolutePath,
                        state = volume.state,
                        primary = volume.isPrimary,
                        removable = volume.isRemovable,
                    )
                }
            }
        }

        val marker = "/Android/data/${context.packageName}/files"
        return context.getExternalFilesDirs(null).mapIndexedNotNull { index, appDirectory ->
            val path = appDirectory?.absolutePath ?: return@mapIndexedNotNull null
            val root = path.substringBefore(marker).takeIf { it != path } ?: return@mapIndexedNotNull null
            val volume =
                volumes.firstOrNull { candidate ->
                    if (index == 0) {
                        candidate.isPrimary
                    } else {
                        !candidate.isPrimary &&
                            candidate.uuid?.equals(File(root).name, ignoreCase = true) == true
                    }
                }
            AndroidStorageVolume(
                label = volume?.getDescription(context) ?: if (index == 0) "Internal shared storage" else "External storage",
                hostPath = root,
                state = Environment.getExternalStorageState(appDirectory),
                primary = index == 0,
                removable = volume?.isRemovable ?: index != 0,
            )
        }.filter(AndroidStorageVolume::primary)
            .distinctBy(AndroidStorageVolume::hostPath)
    }

    fun hasFullAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    fun accessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appSettings = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
            appSettings.takeIf { it.resolveActivity(context.packageManager) != null }
                ?: Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        } else {
            Intent()
        }

    fun legacyPermissions(): Array<String> =
        arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )

    internal fun guestTarget(
        primary: Boolean,
        hostPath: String,
    ): String {
        if (primary) return "/mnt/shared"
        val volumeId = File(hostPath).name.lowercase().ifBlank { "external" }
        return "/mnt/storage/$volumeId"
    }
}
