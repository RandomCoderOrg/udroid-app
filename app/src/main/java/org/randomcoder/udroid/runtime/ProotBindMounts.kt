package org.randomcoder.udroid.runtime

import java.io.File

internal val ANDROID_PROOT_BIND_MOUNTS =
    listOf(
        "/system",
        "/apex",
        "/dev",
        "/proc",
        "/sys",
        "/linkerconfig/ld.config.txt",
    )

internal fun MutableList<String>.addAndroidProotBindMounts() {
    ANDROID_PROOT_BIND_MOUNTS.forEach { path ->
        add("-b")
        add(path)
    }
}

/**
 * Exposes one complete X11 display claim to PRoot consumers.
 *
 * The socket alone is insufficient: nested X servers use the matching lock
 * file to choose a free display number. Without it, Xwayland can claim :0 and
 * unlink the outer server's bound X0 socket.
 */
internal fun MutableList<String>.addX11ProotBindMounts(
    socketDirectory: String,
    displayNumber: Int = 0,
) {
    val socketDirectoryFile = File(socketDirectory)
    require(socketDirectoryFile.name == ".X11-unix") {
        "X11 socket directory must end with .X11-unix"
    }
    val runtimeDirectory =
        requireNotNull(socketDirectoryFile.parentFile) {
            "X11 socket directory must have a runtime parent"
        }
    val lockFile = File(runtimeDirectory, ".X$displayNumber-lock")

    add("-b")
    add("$socketDirectory:/tmp/.X11-unix")
    add("-b")
    add("${lockFile.path}:/tmp/.X$displayNumber-lock")
}
