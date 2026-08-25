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
}
