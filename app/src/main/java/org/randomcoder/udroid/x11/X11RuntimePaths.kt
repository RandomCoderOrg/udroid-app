package org.randomcoder.udroid.x11

import android.content.Context
import java.io.File

object X11RuntimePaths {
    fun runtimeDirectory(context: Context): File =
        File(context.filesDir, "runtime/x11")

    fun socketDirectory(context: Context): File =
        File(runtimeDirectory(context), ".X11-unix")

    fun displaySocket(context: Context): File =
        File(socketDirectory(context), "X0")
}
