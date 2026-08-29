package org.randomcoder.udroid.gfxstream

import android.content.Context
import java.io.File

data class GfxstreamHostRuntime(
    val executable: File,
    val libraryDirectory: File,
    val version: String,
)

/** Installs the optional Android-host Kumquat runtime from signed APK assets. */
object GfxstreamHostRuntimeInstaller {
    internal const val RUNTIME_VERSION = "7-5605f4c-36967251d"
    private val bundle =
        VerifiedRuntimeAssetBundle(
            name = "gfxstream host",
            assetDirectory = "gfxstream-host",
            destinationPrefix = "gfxstream-host",
            version = RUNTIME_VERSION,
            entries = listOf("bin/kumquat", "lib/libc++_shared.so"),
            executables = setOf("bin/kumquat"),
        )

    fun install(context: Context): GfxstreamHostRuntime {
        val directory = VerifiedRuntimeAssetInstaller.install(context, bundle)
        return GfxstreamHostRuntime(
            executable = File(directory, "bin/kumquat"),
            libraryDirectory = File(directory, "lib"),
            version = RUNTIME_VERSION,
        )
    }
}
