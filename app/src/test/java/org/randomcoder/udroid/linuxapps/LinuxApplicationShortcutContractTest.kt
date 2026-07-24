package org.randomcoder.udroid.linuxapps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LinuxApplicationShortcutContractTest {
    @Test
    fun derivesStableShortcutIdFromDesktopEntryId() {
        assertEquals(
            "linux-application:org.blender.Blender",
            LinuxApplicationShortcutContract.shortcutId("org.blender.Blender"),
        )
    }

    @Test
    fun rejectsBlankApplicationId() {
        assertThrows(IllegalArgumentException::class.java) {
            LinuxApplicationShortcutContract.shortcutId(" ")
        }
    }
}
