package org.randomcoder.udroid.virgl

import android.content.Context
import java.io.File
import org.randomcoder.udroid.gfxstream.VerifiedRuntimeAssetBundle
import org.randomcoder.udroid.gfxstream.VerifiedRuntimeAssetInstaller

internal data class VirglHostRuntime(
    val executable: File,
    val libraryDirectory: File,
)

internal data class VirglAngleRuntime(
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

internal object VirglAngleRuntimeInstaller {
    const val VERSION = "2.1.24923-f09a19ce-2"
    private val bundle =
        VerifiedRuntimeAssetBundle(
            name = "VirGL ANGLE Vulkan backend",
            assetDirectory = "virgl-angle-vulkan",
            destinationPrefix = "virgl-angle-vulkan",
            version = VERSION,
            entries =
                listOf(
                    "lib/libEGL_angle.so",
                    "lib/libGLESv1_CM_angle.so",
                    "lib/libGLESv2_angle.so",
                    "lib/libfeature_support_angle.so",
                    "share/doc/copyright",
                ),
            metadataKeys = setOf("termux_packages_commit"),
        )

    fun install(context: Context): VirglAngleRuntime =
        VirglAngleRuntime(
            libraryDirectory =
                File(VerifiedRuntimeAssetInstaller.install(context, bundle), "lib"),
        )
}
