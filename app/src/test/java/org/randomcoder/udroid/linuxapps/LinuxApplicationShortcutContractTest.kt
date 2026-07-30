package org.randomcoder.udroid.linuxapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxApplicationShortcutContractTest {
    @Test
    fun derivesStableShortcutIdFromDesktopEntryId() {
        assertEquals(
            "linux-application:udroid-jammy:org.blender.Blender",
            LinuxApplicationShortcutContract.shortcutId(
                "udroid-jammy",
                "org.blender.Blender",
            ),
        )
    }

    @Test
    fun rejectsBlankApplicationId() {
        assertThrows(IllegalArgumentException::class.java) {
            LinuxApplicationShortcutContract.shortcutId("udroid-jammy", " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LinuxApplicationShortcutContract.shortcutId(" ", "org.blender.Blender")
        }
    }

    @Test
    fun matchesOnlyShortcutsOwnedByTheSelectedRootfs() {
        val shortcut =
            LinuxApplicationShortcutContract.shortcutId(
                "oci-alpine-3.22",
                "org.example.Editor",
            )

        assertTrue(
            LinuxApplicationShortcutContract.belongsToRootfs(
                shortcut,
                "oci-alpine-3.22",
            ),
        )
        assertFalse(
            LinuxApplicationShortcutContract.belongsToRootfs(
                shortcut,
                "oci-alpine",
            ),
        )
    }
}
