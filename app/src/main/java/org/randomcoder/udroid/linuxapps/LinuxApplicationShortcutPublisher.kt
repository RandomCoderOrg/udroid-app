package org.randomcoder.udroid.linuxapps

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.BitmapFactory
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import org.randomcoder.udroid.MainActivity
import org.randomcoder.udroid.R

data class LinuxApplicationShortcutResult(
    val dynamicPublished: Boolean,
)

class LinuxApplicationShortcutPublisher(
    private val context: Context,
) {
    fun publishAndRequestPin(
        application: LinuxApplication,
        rootfsName: String,
    ): Result<LinuxApplicationShortcutResult> =
        runCatching {
            check(ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                "This launcher does not support adding app shortcuts to the home screen"
            }

            val shortcut = buildShortcut(application, rootfsName)
            val dynamicPublished =
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            check(ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)) {
                "The launcher did not accept the shortcut request"
            }
            LinuxApplicationShortcutResult(
                dynamicPublished = dynamicPublished,
            )
        }

    fun disableForRootfs(rootfsName: String) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val shortcutIds =
            (manager.dynamicShortcuts + manager.pinnedShortcuts)
                .asSequence()
                .map { it.id }
                .filter { LinuxApplicationShortcutContract.belongsToRootfs(it, rootfsName) }
                .distinct()
                .toList()
        if (shortcutIds.isEmpty()) return
        manager.disableShortcuts(
            shortcutIds,
            "The Linux system for this shortcut was removed",
        )
        manager.removeDynamicShortcuts(shortcutIds)
    }

    private fun buildShortcut(
        application: LinuxApplication,
        rootfsName: String,
    ): ShortcutInfoCompat =
        ShortcutInfoCompat
            .Builder(
                context,
                LinuxApplicationShortcutContract.shortcutId(rootfsName, application.id),
            ).setShortLabel(application.name)
            .setLongLabel("Open ${application.name} in uDroid")
            .setDisabledMessage(
                "${application.name} is not installed in the current Linux system",
            ).setIcon(application.shortcutIcon())
            .setIntent(
                Intent(context, MainActivity::class.java)
                    .setAction(LinuxApplicationShortcutContract.ACTION_LAUNCH)
                    .putExtra(
                        LinuxApplicationShortcutContract.EXTRA_APPLICATION_ID,
                        application.id,
                    ).putExtra(
                        LinuxApplicationShortcutContract.EXTRA_ROOTFS_NAME,
                        rootfsName,
                    ).addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
            ).build()

    private fun LinuxApplication.shortcutIcon(): IconCompat {
        val bitmap = iconPath?.let(BitmapFactory::decodeFile)
        return if (bitmap != null) {
            IconCompat.createWithBitmap(bitmap)
        } else {
            IconCompat.createWithResource(context, R.drawable.udroid_logo)
        }
    }
}

object LinuxApplicationShortcutContract {
    const val ACTION_LAUNCH =
        "org.randomcoder.udroid.action.LAUNCH_LINUX_APPLICATION"
    const val EXTRA_APPLICATION_ID =
        "org.randomcoder.udroid.extra.LINUX_APPLICATION_ID"
    const val EXTRA_ROOTFS_NAME =
        "org.randomcoder.udroid.extra.ROOTFS_NAME"

    private const val SHORTCUT_ID_PREFIX = "linux-application:"

    fun belongsToRootfs(
        shortcutId: String,
        rootfsName: String,
    ): Boolean = shortcutId.startsWith("$SHORTCUT_ID_PREFIX$rootfsName:")

    fun shortcutId(
        rootfsName: String,
        applicationId: String,
    ): String {
        require(rootfsName.isNotBlank()) { "Linux system name must not be blank" }
        require(applicationId.isNotBlank()) { "Linux application ID must not be blank" }
        return "$SHORTCUT_ID_PREFIX$rootfsName:$applicationId"
    }
}
