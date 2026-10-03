package org.randomcoder.udroid.virgl

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.randomcoder.udroid.runtime.VirglServerMode

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
                VirglServerMode.MULTI_CLIENT,
            ),
        )
    }

    @Test
    fun `starts the ANGLE Vulkan backend on a separate socket`() {
        assertEquals(
            listOf(
                "--no-fork",
                "--angle-vulkan",
                "--socket-path",
                "/private/graphics/virgl-angle-vulkan.sock",
            ),
            VirglHostLaunch.arguments(
                File("/private/graphics/virgl-angle-vulkan.sock"),
                VirglHostBackend.ANGLE_VULKAN,
                VirglServerMode.COMPATIBILITY,
            ),
        )
    }

    @Test
    fun `starts the Venus backend on its own socket`() {
        assertEquals(
            listOf(
                "--no-fork",
                "--multi-clients",
                "--venus",
                "--socket-path",
                "/private/graphics/venus.sock",
            ),
            VirglHostLaunch.arguments(
                File("/private/graphics/venus.sock"),
                VirglHostBackend.VENUS,
                VirglServerMode.COMPATIBILITY,
            ),
        )
    }

    @Test
    fun `Venus always supports concurrent Vulkan clients`() {
        assertEquals(
            VirglServerMode.MULTI_CLIENT,
            VirglHostBackend.VENUS.resolveServerMode(VirglServerMode.AUTOMATIC),
        )
        assertEquals(
            VirglServerMode.MULTI_CLIENT,
            VirglHostBackend.VENUS.resolveServerMode(VirglServerMode.COMPATIBILITY),
        )
    }

    @Test
    fun `loads the packaged host libraries`() {
        val environment =
            VirglHostLaunch.environment(
                home = File("/private/files"),
                libraryDirectory = File("/private/lib"),
                renderServerExecutable = File("/private/libexec/virgl_render_server"),
                temporaryDirectory = File("/private/cache"),
            )

        assertEquals("/private/lib", environment["LD_LIBRARY_PATH"])
        assertEquals("/private/cache", environment["TMPDIR"])
        assertEquals("/system/bin", environment["PATH"])
        assertEquals("info", environment["VIRGL_LOG_LEVEL"])
        assertEquals(
            "/private/libexec/virgl_render_server",
            environment["RENDER_SERVER_EXEC_PATH"],
        )
        assertEquals(null, environment["VTEST_ANGLE_LIBRARY_PATH"])
    }

    @Test
    fun `points the ANGLE backend at only its Vulkan libraries`() {
        val environment =
            VirglHostLaunch.environment(
                home = File("/private/files"),
                libraryDirectory = File("/private/virgl-lib"),
                renderServerExecutable = File("/private/libexec/virgl_render_server"),
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
            VirglHostBackend.VENUS,
            VirglHostBackend.from(
                org.randomcoder.udroid.runtime.DesktopGraphicsProfile.VENUS_EXPERIMENTAL,
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

    @Test
    fun `selects Venus vtest with the X11 copy presentation path`() {
        val socket = Files.createTempFile("udroid-venus", ".sock").toFile()
        val runtime = Files.createTempDirectory("udroid-venus-runtime").toFile()
        val profile =
            VirglProotLaunchProfile(
                socket,
                VirglHostBackend.VENUS,
                VenusGuestRuntime(runtime),
            )
        val bindings = mutableListOf<String>()

        profile.addBindings(bindings)

        assertEquals(
            listOf(
                "-b",
                "${socket.absolutePath}:/tmp/.virgl_test",
                "-b",
                "${runtime.absolutePath}:/opt/udroid/venus-mesa",
            ),
            bindings,
        )
        assertEquals(
            listOf(
                "/usr/bin/env",
                "-u",
                "LIBGL_ALWAYS_SOFTWARE",
                "-u",
                "GALLIUM_DRIVER",
                "-u",
                "MESA_LOADER_DRIVER_OVERRIDE",
                "VN_DEBUG=vtest",
                "MESA_VK_WSI_DEBUG=sw",
                "LD_LIBRARY_PATH=/opt/udroid/venus-mesa/lib:" +
                    "/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu",
                "LIBGL_DRIVERS_PATH=/opt/udroid/venus-mesa/lib/dri",
                "VK_ICD_FILENAMES=/opt/udroid/venus-mesa/share/vulkan/icd.d/" +
                    "virtio_icd.aarch64.json",
                "__EGL_VENDOR_LIBRARY_FILENAMES=/opt/udroid/venus-mesa/share/glvnd/" +
                    "egl_vendor.d/50_mesa.json",
                "/usr/bin/vkcube",
            ),
            profile.wrapGuestCommand(listOf("/usr/bin/vkcube")),
        )
    }

    @Test
    fun `uses bundled Venus Mesa for supported Ubuntu releases`() {
        val rootfs = Files.createTempDirectory("udroid-venus-rootfs").toFile()
        val osRelease = rootfs.resolve("etc/os-release")
        requireNotNull(osRelease.parentFile).mkdirs()
        osRelease.writeText("ID=ubuntu\nVERSION_ID=\"22.04\"\n")

        assertTrue(VenusGuestRuntimeInstaller.requiresBundledMesa(rootfs))

        osRelease.writeText("ID=ubuntu\nVERSION_ID=\"24.04\"\n")
        assertTrue(VenusGuestRuntimeInstaller.requiresBundledMesa(rootfs))

        osRelease.writeText("ID=ubuntu\nVERSION_ID=\"20.04\"\n")
        assertFalse(VenusGuestRuntimeInstaller.requiresBundledMesa(rootfs))
    }

    @Test
    fun `parses verbose Zink profile results`() {
        val results =
            parseZinkRequirements(
                """
                Checking profile VP_ZINK_gl21_baseline
                Checking device Virtio-GPU Venus
                Supported

                Checking profile VP_ZINK_gl46_baseline
                Checking device Virtio-GPU Venus
                UNSUPPORTED physical device
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                ZinkRequirementResult("VP_ZINK_gl21_baseline", true),
                ZinkRequirementResult("VP_ZINK_gl46_baseline", false),
            ),
            results,
        )
        assertEquals("OpenGL 2.1 baseline", results.first().label)
    }
}
