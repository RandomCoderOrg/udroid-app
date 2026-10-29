package org.randomcoder.udroid.virgl

import java.io.File
import org.randomcoder.udroid.runtime.ProotLaunchProfile

internal data class VirglProotLaunchProfile(
    val socket: File,
    val backend: VirglHostBackend = VirglHostBackend.NATIVE_GLES,
    val venusGuestRuntime: VenusGuestRuntime? = null,
) : ProotLaunchProfile {
    init {
        require(socket.exists()) { "The VirGL socket is unavailable" }
        require(venusGuestRuntime == null || backend == VirglHostBackend.VENUS) {
            "The Venus guest runtime requires the Venus backend"
        }
        require(venusGuestRuntime == null || venusGuestRuntime.directory.isDirectory) {
            "The Venus guest runtime is unavailable"
        }
    }

    override fun addBindings(arguments: MutableList<String>) {
        arguments += "-b"
        arguments += "${socket.absolutePath}:$GUEST_SOCKET"
        venusGuestRuntime?.let {
            arguments += "-b"
            arguments += "${it.directory.absolutePath}:${VenusGuestRuntimeInstaller.GUEST_DIRECTORY}"
        }
    }

    override fun wrapGuestCommand(command: List<String>): List<String> {
        require(command.isNotEmpty()) { "A guest command is required" }
        return listOf(
            "/usr/bin/env",
            "-u",
            "LIBGL_ALWAYS_SOFTWARE",
        ) +
            if (backend == VirglHostBackend.VENUS) {
                listOf(
                    "-u",
                    "GALLIUM_DRIVER",
                    "-u",
                    "MESA_LOADER_DRIVER_OVERRIDE",
                    "-u",
                    "MESA_VK_WSI_DEBUG",
                    "VN_DEBUG=vtest",
                ) +
                    if (venusGuestRuntime != null) {
                        listOf(
                            "LD_LIBRARY_PATH=${VenusGuestRuntimeInstaller.GUEST_DIRECTORY}/lib:" +
                                "/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu",
                            "LIBGL_DRIVERS_PATH=${VenusGuestRuntimeInstaller.GUEST_DIRECTORY}/lib/dri",
                            "VK_ICD_FILENAMES=${VenusGuestRuntimeInstaller.GUEST_DIRECTORY}/share/" +
                                "vulkan/icd.d/virtio_icd.aarch64.json",
                            "__EGL_VENDOR_LIBRARY_FILENAMES=${VenusGuestRuntimeInstaller.GUEST_DIRECTORY}/" +
                                "share/glvnd/egl_vendor.d/50_mesa.json",
                        )
                    } else {
                        emptyList()
                    } + command
            } else {
                listOf("GALLIUM_DRIVER=virpipe") + command
            }
    }

    companion object {
        const val GUEST_SOCKET = "/tmp/.virgl_test"
    }
}
