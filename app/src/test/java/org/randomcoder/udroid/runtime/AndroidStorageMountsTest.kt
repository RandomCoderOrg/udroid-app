package org.randomcoder.udroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidStorageMountsTest {
    @Test
    fun `storage targets are stable and distinguish removable volumes`() {
        assertEquals("/mnt/shared", AndroidStorageMounts.guestTarget(true, "/storage/emulated/0"))
        assertEquals(
            "/mnt/storage/1234-abcd",
            AndroidStorageMounts.guestTarget(false, "/storage/1234-ABCD"),
        )
    }
}
