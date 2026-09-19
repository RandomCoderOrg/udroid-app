package org.randomcoder.udroid.virgl

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class VirglHostLaunchTest {
    @Test
    fun `starts one foreground multi-client server on the private socket`() {
        assertEquals(
            listOf(
                "--no-fork",
                "--multi-clients",
                "--socket-path",
                "/private/graphics/virgl-gles.sock",
            ),
            VirglHostLaunch.arguments(
                File("/private/graphics/virgl-gles.sock"),
                VirglHostBackend.NATIVE_GLES,
            ),
        )
    }

    @Test
    fun `starts the ANGLE Vulkan backend on a separate socket`() {
        assertEquals(
            listOf(
                "--no-fork",
                "--multi-clients",
                "--angle-vulkan",
                "--socket-path",
                "/private/graphics/virgl-angle-vulkan.sock",
            ),
            VirglHostLaunch.arguments(
                File("/private/graphics/virgl-angle-vulkan.sock"),
                VirglHostBackend.ANGLE_VULKAN,
            ),
        )
    }

    @Test
    fun `loads the packaged host libraries`() {
        val environment =
            VirglHostLaunch.environment(
                home = File("/private/files"),
                libraryDirectory = File("/private/lib"),
                temporaryDirectory = File("/private/cache"),
            )

        assertEquals("/private/lib", environment["LD_LIBRARY_PATH"])
        assertEquals("/private/cache", environment["TMPDIR"])
        assertEquals("/system/bin", environment["PATH"])
        assertEquals(null, environment["VTEST_ANGLE_LIBRARY_PATH"])
    }

    @Test
    fun `points the ANGLE backend at only its Vulkan libraries`() {
        val environment =
            VirglHostLaunch.environment(
                home = File("/private/files"),
                libraryDirectory = File("/private/virgl-lib"),
                temporaryDirectory = File("/private/cache"),
                angleLibraryDirectory = File("/private/angle-vulkan-lib"),
            )

        assertEquals("/private/virgl-lib", environment["LD_LIBRARY_PATH"])
        assertEquals(
            "/private/angle-vulkan-lib",
            environment["VTEST_ANGLE_LIBRARY_PATH"],
        )
    }

    @Test
    fun `maps only VirGL profiles to their host backend`() {
        assertEquals(
            VirglHostBackend.NATIVE_GLES,
            VirglHostBackend.from(
                org.randomcoder.udroid.runtime.DesktopGraphicsProfile.VIRGL,
            ),
        )
        assertEquals(
            VirglHostBackend.ANGLE_VULKAN,
            VirglHostBackend.from(
                org.randomcoder.udroid.runtime.DesktopGraphicsProfile.VIRGL_ANGLE,
            ),
        )
        assertEquals(
            null,
            VirglHostBackend.from(
                org.randomcoder.udroid.runtime.DesktopGraphicsProfile.ZINK,
            ),
        )
    }

    @Test
    fun `binds the server socket and selects virpipe for the guest command`() {
        val socket = Files.createTempFile("udroid-virgl", ".sock").toFile()
        val profile = VirglProotLaunchProfile(socket)
        val bindings = mutableListOf<String>()

        profile.addBindings(bindings)

        assertEquals(
            listOf("-b", "${socket.absolutePath}:/tmp/.virgl_test"),
            bindings,
        )
        assertEquals(
            listOf(
                "/usr/bin/env",
                "-u",
                "LIBGL_ALWAYS_SOFTWARE",
                "GALLIUM_DRIVER=virpipe",
                "/usr/bin/glxinfo",
                "-B",
            ),
            profile.wrapGuestCommand(listOf("/usr/bin/glxinfo", "-B")),
        )
    }
}
