package org.randomcoder.udroid.virgl

import android.content.Context
import java.io.File
import org.randomcoder.udroid.gfxstream.VerifiedRuntimeAssetBundle
import org.randomcoder.udroid.gfxstream.VerifiedRuntimeAssetInstaller

internal data class VirglHostRuntime(
    val executable: File,
    val libraryDirectory: File,
)

internal object VirglHostRuntimeInstaller {
    const val VERSION = "1.3.0-1"
    private val bundle =
        VerifiedRuntimeAssetBundle(
            name = "VirGL host",
            assetDirectory = "virgl-host",
            destinationPrefix = "virgl-host",
            version = VERSION,
            entries =
                listOf(
                    "bin/virgl_test_server_android",
                    "lib/libepoxy.so",
                    "lib/libvirglrenderer.so",
                    "share/doc/COPYING-gl4es",
                    "share/doc/COPYING-libepoxy",
                    "share/doc/COPYING-virglrenderer",
                ),
            executables = setOf("bin/virgl_test_server_android"),
            metadataKeys = setOf("termux_packages_commit"),
        )

    fun install(context: Context): VirglHostRuntime {
        val directory = VerifiedRuntimeAssetInstaller.install(context, bundle)
        return VirglHostRuntime(
            executable = File(directory, "bin/virgl_test_server_android"),
            libraryDirectory = File(directory, "lib"),
        )
    }
}
