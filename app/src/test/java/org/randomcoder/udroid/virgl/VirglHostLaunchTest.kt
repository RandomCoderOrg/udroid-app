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
                "/private/graphics/virgl.sock",
            ),
            VirglHostLaunch.arguments(File("/private/graphics/virgl.sock")),
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
