package org.randomcoder.udroid.gfxstream

import android.content.Context
import java.io.File

data class GfxstreamGuestRuntime(
    val directory: File,
    val launcher: File,
    val version: String,
    val protocolHostCommit: String = "",
    val mesaCommit: String = "",
) {
    companion object {
        const val GUEST_DIRECTORY = "/opt/udroid/gfxstream"
        const val GUEST_GPU_SOCKET = "/tmp/kumquat-gpu-0"
    }
}

/** Installs the matched glibc gfxstream Vulkan ICD used by opt-in PRoot launches. */
object GfxstreamGuestRuntimeInstaller {
    internal const val RUNTIME_VERSION = "15-sync-fd-lifecycle-fb349a2d3b-dirty"
    private val bundle =
        VerifiedRuntimeAssetBundle(
            name = "gfxstream guest",
            assetDirectory = "gfxstream-guest",
            destinationPrefix = "gfxstream-guest",
            version = RUNTIME_VERSION,
            entries =
                listOf(
                    "bin/udroid-gfxstream-run",
                    "lib/libvulkan_gfxstream.so",
                    "lib/libdrm.so.2",
                    "lib/libexpat.so.1",
                    "share/vulkan/icd.d/gfxstream_icd.json",
                ),
            executables = setOf("bin/udroid-gfxstream-run"),
            metadataKeys = setOf("protocol_host_commit", "mesa_commit"),
        )

    fun install(context: Context): GfxstreamGuestRuntime {
        val installation = VerifiedRuntimeAssetInstaller.installWithMetadata(context, bundle)
        return GfxstreamGuestRuntime(
            directory = installation.directory,
            launcher = File(installation.directory, "bin/udroid-gfxstream-run"),
            version = RUNTIME_VERSION,
            protocolHostCommit = installation.metadata.getValue("protocol_host_commit"),
            mesaCommit = installation.metadata.getValue("mesa_commit"),
        )
    }
}
