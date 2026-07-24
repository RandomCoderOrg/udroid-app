package org.randomcoder.udroid.runtime

import android.os.Build
import java.io.File

object AndroidExecutableCommand {
    fun create(
        executable: File,
        vararg arguments: String,
    ): List<String> {
        require(executable.isAbsolute) { "Executable path must be absolute" }
        return if (Build.VERSION.SDK_INT >= 29) {
            listOf(systemLinker(), executable.absolutePath) + arguments
        } else {
            listOf(executable.absolutePath) + arguments
        }
    }

    private fun systemLinker(): String =
        if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) {
            "/system/bin/linker64"
        } else {
            "/system/bin/linker"
        }
}
