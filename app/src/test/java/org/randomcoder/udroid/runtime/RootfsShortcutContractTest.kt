package org.randomcoder.udroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsShortcutContractTest {
    @Test
    fun derivesStableShortcutIds() {
        assertEquals(
            "rootfs:udroid-jammy:terminal:tab-2",
            RootfsShortcutContract.shortcutId(
                "udroid-jammy",
                RootfsShortcutTarget.TERMINAL,
                "tab-2",
            ),
        )
        assertEquals(
            "rootfs:udroid-jammy:display",
            RootfsShortcutContract.shortcutId(
                "udroid-jammy",
                RootfsShortcutTarget.DISPLAY,
            ),
        )
    }

    @Test
    fun validatesShortcutOwnershipAndTargets() {
        val shortcut =
            RootfsShortcutContract.shortcutId(
                "oci-alpine-3.22",
                RootfsShortcutTarget.DISPLAY,
            )

        assertTrue(RootfsShortcutContract.belongsToRootfs(shortcut, "oci-alpine-3.22"))
        assertFalse(RootfsShortcutContract.belongsToRootfs(shortcut, "oci-alpine"))
        assertThrows(IllegalArgumentException::class.java) {
            RootfsShortcutContract.shortcutId(" ", RootfsShortcutTarget.TERMINAL)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootfsShortcutContract.shortcutId(
                "udroid-jammy",
                RootfsShortcutTarget.TERMINAL,
                " ",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootfsShortcutContract.shortcutId(
                "udroid-jammy",
                RootfsShortcutTarget.DISPLAY,
                "tab-2",
            )
        }
    }
}
