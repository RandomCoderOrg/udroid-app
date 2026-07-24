package org.randomcoder.udroid.linuxapps

import android.content.Context
import android.content.Intent
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
    ): Result<LinuxApplicationShortcutResult> =
        runCatching {
            check(ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                "This launcher does not support adding app shortcuts to the home screen"
            }

            val shortcut = buildShortcut(application)
            val dynamicPublished =
                ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
            check(ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)) {
                "The launcher did not accept the shortcut request"
            }
            LinuxApplicationShortcutResult(
                dynamicPublished = dynamicPublished,
            )
        }

    private fun buildShortcut(
        application: LinuxApplication,
    ): ShortcutInfoCompat =
        ShortcutInfoCompat
            .Builder(
                context,
                LinuxApplicationShortcutContract.shortcutId(application.id),
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
            IconCompat.createWithResource(context, R.drawable.ic_launcher)
        }
    }
}

object LinuxApplicationShortcutContract {
    const val ACTION_LAUNCH =
        "org.randomcoder.udroid.action.LAUNCH_LINUX_APPLICATION"
    const val EXTRA_APPLICATION_ID =
        "org.randomcoder.udroid.extra.LINUX_APPLICATION_ID"

    private const val SHORTCUT_ID_PREFIX = "linux-application:"

    fun shortcutId(applicationId: String): String {
        require(applicationId.isNotBlank()) { "Linux application ID must not be blank" }
        return "$SHORTCUT_ID_PREFIX$applicationId"
    }
}
