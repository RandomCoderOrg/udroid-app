package org.randomcoder.udroid.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SphalGraphicsSupportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `guest runtime requires the launcher and patched Mesa`() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        val requiredFiles =
            listOf(
                SphalGraphicsSupport.GUEST_RUNNER,
                "${SphalGraphicsSupport.GUEST_MESA_LIBRARY_DIRECTORY}/libGL.so.1",
                "${SphalGraphicsSupport.GUEST_MESA_DRI_DIRECTORY}/zink_dri.so",
            ).map { path -> File(rootfs, path.removePrefix("/")) }

        assertFalse(SphalGraphicsSupport.isGuestRuntimeAvailable(rootfs))
        requiredFiles.forEach { file ->
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        assertTrue(SphalGraphicsSupport.isGuestRuntimeAvailable(rootfs))

        requiredFiles.last().delete()
        assertFalse(SphalGraphicsSupport.isGuestRuntimeAvailable(rootfs))
    }
}
