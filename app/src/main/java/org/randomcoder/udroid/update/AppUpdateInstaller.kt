package org.randomcoder.udroid.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

sealed interface UpdateInstallResult {
    data object Submitted : UpdateInstallResult

    data class Failed(val message: String) : UpdateInstallResult
}

object AppUpdateInstaller {
    fun install(
        context: Context,
        state: AppUpdateState,
    ): UpdateInstallResult {
        val release = state.release
            ?: return UpdateInstallResult.Failed("No update release is selected")
        val apk =
            state.downloadedApkPath
                ?.let(::File)
                ?.takeIf(File::isFile)
                ?: return UpdateInstallResult.Failed("The verified update APK is missing")
        validateApk(context, apk, release)?.let {
            return UpdateInstallResult.Failed(it)
        }
        return runCatching {
            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.update-files",
                    apk,
                )
            context.startActivity(
                Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setDataAndType(uri, APK_MIME_TYPE)
                    .apply {
                        clipData = ClipData.newRawUri("uDroid update", uri)
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_ACTIVITY_NEW_TASK,
                        )
                    },
            )
            UpdateInstallResult.Submitted
        }.getOrElse { error ->
            UpdateInstallResult.Failed(
                error.message ?: "Android could not open the update installer",
            )
        }
    }

    private fun validateApk(
        context: Context,
        apk: File,
        release: AppRelease,
    ): String? {
        val archive =
            packageInfo(context.packageManager, apk.absolutePath)
                ?: return "Android could not inspect the downloaded APK"
        if (archive.packageName != context.packageName) {
            return "Downloaded APK belongs to ${archive.packageName}, not ${context.packageName}"
        }
        val installed =
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.PackageInfoFlags.of(
                            PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
                        ),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.GET_SIGNING_CERTIFICATES,
                    )
                }
            }.getOrNull() ?: return "Android could not inspect the installed uDroid package"
        if (versionCode(archive) <= versionCode(installed)) {
            return "uDroid ${release.version} does not have a newer Android version code"
        }
        val installedSigners = signerDigests(installed)
        val archiveSigners = signerDigests(archive)
        if (
            installedSigners.isEmpty() ||
            archiveSigners.isEmpty() ||
            installedSigners != archiveSigners
        ) {
            return "This build uses a different signing key. Reinstall once from the " +
                "stable-signed release before in-app updates can continue."
        }
        return null
    }

    private fun packageInfo(
        packageManager: PackageManager,
        path: String,
    ): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageArchiveInfo(
                path,
                PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures =
            if (Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.apkContentsSigners.orEmpty()
            } else {
                @Suppress("DEPRECATION")
                info.signatures.orEmpty()
            }
        return signatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
}
