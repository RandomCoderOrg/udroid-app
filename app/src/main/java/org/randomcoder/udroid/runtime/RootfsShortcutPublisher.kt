package org.randomcoder.udroid.runtime

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import org.randomcoder.udroid.MainActivity
import org.randomcoder.udroid.R

enum class RootfsShortcutTarget {
    TERMINAL,
    DISPLAY,
}

class RootfsShortcutPublisher(
    private val context: Context,
) {
    fun publishAndRequestPin(
        rootfsName: String,
        label: String,
        target: RootfsShortcutTarget,
        terminalTabId: String? = null,
    ): Result<Unit> =
        runCatching {
            require(label.isNotBlank()) { "Shortcut name must not be blank" }
            check(ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
                "This launcher does not support adding shortcuts to the home screen"
            }
            val shortcutIntent =
                Intent(context, MainActivity::class.java)
                    .setAction(RootfsShortcutContract.ACTION_OPEN)
                    .putExtra(RootfsShortcutContract.EXTRA_ROOTFS_NAME, rootfsName)
                    .putExtra(RootfsShortcutContract.EXTRA_TARGET, target.name)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ).apply {
                        terminalTabId?.let {
                            putExtra(RootfsShortcutContract.EXTRA_TERMINAL_TAB_ID, it)
                        }
                    }
            val shortcut =
                ShortcutInfoCompat
                    .Builder(
                        context,
                        RootfsShortcutContract.shortcutId(rootfsName, target, terminalTabId),
                    ).setShortLabel(label.trim())
                    .setLongLabel("Open ${label.trim()} in uDroid")
                    .setIcon(IconCompat.createWithResource(context, R.drawable.udroid_logo))
                    .setIntent(shortcutIntent)
                    .build()
            check(ShortcutManagerCompat.requestPinShortcut(context, shortcut, null)) {
                "The launcher did not accept the shortcut request"
            }
        }

    fun disableForRootfs(rootfsName: String) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val shortcutIds =
            manager.pinnedShortcuts
                .asSequence()
                .map { it.id }
                .filter { RootfsShortcutContract.belongsToRootfs(it, rootfsName) }
                .toList()
        if (shortcutIds.isEmpty()) return
        manager.disableShortcuts(
            shortcutIds,
            "The Linux system for this shortcut was removed",
        )
    }
}

object RootfsShortcutContract {
    const val ACTION_OPEN = "org.randomcoder.udroid.action.OPEN_ROOTFS_SHORTCUT"
    const val EXTRA_ROOTFS_NAME = "org.randomcoder.udroid.extra.ROOTFS_NAME"
    const val EXTRA_TARGET = "org.randomcoder.udroid.extra.ROOTFS_SHORTCUT_TARGET"
    const val EXTRA_TERMINAL_TAB_ID = "org.randomcoder.udroid.extra.TERMINAL_TAB_ID"

    private const val SHORTCUT_ID_PREFIX = "rootfs:"

    fun belongsToRootfs(
        shortcutId: String,
        rootfsName: String,
    ): Boolean = shortcutId.startsWith("$SHORTCUT_ID_PREFIX$rootfsName:")

    fun shortcutId(
        rootfsName: String,
        target: RootfsShortcutTarget,
        terminalTabId: String? = null,
    ): String {
        require(rootfsName.isNotBlank()) { "Linux system name must not be blank" }
        require(terminalTabId == null || terminalTabId.isNotBlank()) {
            "Terminal tab ID must not be blank"
        }
        require(target == RootfsShortcutTarget.TERMINAL || terminalTabId == null) {
            "Only terminal shortcuts can target a terminal tab"
        }
        return listOfNotNull(SHORTCUT_ID_PREFIX + rootfsName, target.name.lowercase(), terminalTabId)
            .joinToString(":")
    }
}
