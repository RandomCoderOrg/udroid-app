package org.randomcoder.udroid.gfxstream

import java.io.File
import org.randomcoder.udroid.runtime.ProotLaunchProfile

/** Opt-in PRoot bindings for the matched gfxstream guest. It never mutates a rootfs. */
data class GfxstreamProotLaunchProfile(
    val runtime: GfxstreamGuestRuntime,
    val gpuSocket: File,
) : ProotLaunchProfile {
    init {
        require(runtime.directory.isDirectory) { "The gfxstream guest runtime is unavailable" }
        require(runtime.launcher.canExecute()) { "The gfxstream guest launcher is unavailable" }
        require(gpuSocket.exists()) { "The Kumquat guest socket is unavailable" }
    }

    override fun addBindings(arguments: MutableList<String>) {
        arguments += "-b"
        arguments += "${runtime.directory.absolutePath}:${GfxstreamGuestRuntime.GUEST_DIRECTORY}"
        arguments += "-b"
        arguments += "${gpuSocket.absolutePath}:${GfxstreamGuestRuntime.GUEST_GPU_SOCKET}"
    }

    override fun wrapGuestCommand(command: List<String>): List<String> {
        require(command.isNotEmpty()) { "A guest command is required" }
        return listOf("${GfxstreamGuestRuntime.GUEST_DIRECTORY}/bin/udroid-gfxstream-run") + command
    }
}
