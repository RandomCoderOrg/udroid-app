package org.randomcoder.udroid.virgl

import java.io.File
import org.randomcoder.udroid.runtime.ProotLaunchProfile

internal data class VirglProotLaunchProfile(
    val socket: File,
) : ProotLaunchProfile {
    init {
        require(socket.exists()) { "The VirGL socket is unavailable" }
    }

    override fun addBindings(arguments: MutableList<String>) {
        arguments += "-b"
        arguments += "${socket.absolutePath}:$GUEST_SOCKET"
    }

    override fun wrapGuestCommand(command: List<String>): List<String> =
        listOf(
            "/usr/bin/env",
            "-u",
            "LIBGL_ALWAYS_SOFTWARE",
            "GALLIUM_DRIVER=virpipe",
        ) + command

    companion object {
        const val GUEST_SOCKET = "/tmp/.virgl_test"
    }
}
