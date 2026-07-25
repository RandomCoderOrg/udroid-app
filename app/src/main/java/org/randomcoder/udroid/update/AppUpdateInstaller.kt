package org.randomcoder.udroid.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

sealed interface UpdateInstallResult {
    data object Submitted : UpdateInstallResult

    data object PermissionRequested : UpdateInstallResult

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
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return UpdateInstallResult.PermissionRequested
        }
        return runCatching {
            val installer = context.packageManager.packageInstaller
            val parameters =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply {
                        setAppPackageName(context.packageName)
                        setSize(apk.length())
                        if (Build.VERSION.SDK_INT >= 31) {
                            setRequireUserAction(
                                PackageInstaller.SessionParams.USER_ACTION_REQUIRED,
                            )
                        }
                    }
            val sessionId = installer.createSession(parameters)
            installer.openSession(sessionId).use { session ->
                FileInputStream(apk).use { input ->
                    session.openWrite("udroid-update.apk", 0L, apk.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }
                val callback =
                    PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        Intent(context, UpdateInstallReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                session.commit(callback.intentSender)
            }
            UpdateInstallResult.Submitted
        }.getOrElse { error ->
            UpdateInstallResult.Failed(
                error.message ?: "Android could not prepare the update installer",
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
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (
            intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE,
            )
        ) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation =
                    if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirmation != null) context.startActivity(confirmation)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                val store = AppUpdateStateStore(context)
                store.current().downloadedApkPath?.let(::File)?.delete()
                store.save(
                    AppUpdateState(
                        phase = AppUpdatePhase.UP_TO_DATE,
                        checkedAtMillis = System.currentTimeMillis(),
                        message = "uDroid update installed",
                    ),
                )
                broadcastState(context)
            }
            else -> {
                val message =
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                        ?: "Android did not install the update"
                AppUpdateStateStore(context).update {
                    it.copy(
                        phase = AppUpdatePhase.READY,
                        message = message,
                    )
                }
                broadcastState(context)
            }
        }
    }
}
