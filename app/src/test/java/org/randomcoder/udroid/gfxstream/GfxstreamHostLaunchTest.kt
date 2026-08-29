package org.randomcoder.udroid.gfxstream

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class GfxstreamHostLaunchTest {
    @Test
    fun `passes private guest and presenter sockets explicitly`() {
        assertEquals(
            listOf(
                "--capset-names=gfxstream-vulkan",
                "--gpu-socket-path=/private/graphics/kumquat-gpu.sock",
                "--presenter-socket-path=/private/graphics/ahb-presenter.sock",
            ),
            GfxstreamHostLaunch.arguments(
                File("/private/graphics/kumquat-gpu.sock"),
                File("/private/graphics/ahb-presenter.sock"),
            ),
        )
    }

    @Test
    fun `omits the private presenter for standard X11 WSI`() {
        assertEquals(
            listOf(
                "--capset-names=gfxstream-vulkan",
                "--gpu-socket-path=/private/graphics/kumquat-gpu.sock",
            ),
            GfxstreamHostLaunch.arguments(
                File("/private/graphics/kumquat-gpu.sock"),
            ),
        )
    }

    @Test
    fun `selects the Android Vulkan loader explicitly`() {
        val environment =
            GfxstreamHostLaunch.environment(
                home = File("/private/files"),
                libraryDirectory = File("/private/lib"),
                temporaryDirectory = File("/private/cache"),
                is64Bit = true,
            )

        assertEquals("/system/lib64/libvulkan.so", environment["ANDROID_EMU_VK_LOADER_PATH"])
        assertEquals("1", environment["ANDROID_EMUGL_VERBOSE"])
        assertEquals(null, environment["UDROID_WINSYS_TRACE"])
    }

    @Test
    fun `enables producer tracing only for a contract probe`() {
        val environment =
            GfxstreamHostLaunch.environment(
                home = File("/private/files"),
                libraryDirectory = File("/private/lib"),
                temporaryDirectory = File("/private/cache"),
                is64Bit = true,
                contractTrace = true,
            )

        assertEquals("1", environment["UDROID_WINSYS_TRACE"])
    }
}
