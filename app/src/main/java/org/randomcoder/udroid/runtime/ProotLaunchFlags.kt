package org.randomcoder.udroid.runtime

import android.os.Environment
import java.io.File

object ProotLaunchFlags {
    const val FILE_NAME = "udroid-launch-flags.txt"

    @Suppress("DEPRECATION")
    fun load(): List<String> = load(File(Environment.getExternalStorageDirectory(), FILE_NAME))

    internal fun load(file: File): List<String> {
        if (!file.exists()) return emptyList()
        require(file.isFile) { "${file.absolutePath} is not a file" }
        require(file.length() <= MAX_FILE_SIZE) {
            "$FILE_NAME is larger than ${MAX_FILE_SIZE / 1024} KiB"
        }

        return file.bufferedReader().useLines { lines ->
            lines
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith('#') }
                .toList()
        }
    }
    private const val MAX_FILE_SIZE = 64L * 1024
}
