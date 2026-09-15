package org.randomcoder.udroid.gfxstream

import android.content.Context
import java.io.File

data class GfxstreamHostRuntime(
    val executable: File,
    val libraryDirectory: File,
    val version: String,
    val gfxstreamCommit: String = "",
    val mesaProtocolCommit: String = "",
)

/** Installs the optional Android-host Kumquat runtime from signed APK assets. */
object GfxstreamHostRuntimeInstaller {
    internal const val RUNTIME_VERSION = "15-async-present-85eb290-1f2939dc-dirty"
    private val bundle =
        VerifiedRuntimeAssetBundle(
            name = "gfxstream host",
            assetDirectory = "gfxstream-host",
            destinationPrefix = "gfxstream-host",
            version = RUNTIME_VERSION,
            entries = listOf("bin/kumquat", "lib/libc++_shared.so"),
            executables = setOf("bin/kumquat"),
            metadataKeys = setOf("gfxstream_commit", "mesa_protocol_commit"),
        )

    fun install(context: Context): GfxstreamHostRuntime {
        val installation = VerifiedRuntimeAssetInstaller.installWithMetadata(context, bundle)
        return GfxstreamHostRuntime(
            executable = File(installation.directory, "bin/kumquat"),
            libraryDirectory = File(installation.directory, "lib"),
            version = RUNTIME_VERSION,
            gfxstreamCommit = installation.metadata.getValue("gfxstream_commit"),
            mesaProtocolCommit = installation.metadata.getValue("mesa_protocol_commit"),
        )
    }
}
