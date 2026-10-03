package org.randomcoder.udroid.virgl

import android.content.Context
import java.io.File
import org.randomcoder.udroid.gfxstream.VerifiedRuntimeAssetBundle
import org.randomcoder.udroid.gfxstream.VerifiedRuntimeAssetInstaller

internal data class VirglHostRuntime(
    val executable: File,
    val renderServerExecutable: File,
    val libraryDirectory: File,
)

internal data class VirglAngleRuntime(
    val libraryDirectory: File,
)

internal data class VenusGuestRuntime(
    val directory: File,
)

internal object VirglHostRuntimeInstaller {
    const val VERSION = "1.3.0-6"
    private val bundle =
        VerifiedRuntimeAssetBundle(
            name = "VirGL host",
            assetDirectory = "virgl-host",
            destinationPrefix = "virgl-host",
            version = VERSION,
            entries =
                listOf(
                    "bin/virgl_test_server_android",
                    "libexec/virgl_render_server",
                    "lib/libepoxy.so",
                    "lib/libvirglrenderer.so",
                    "share/doc/COPYING-gl4es",
                    "share/doc/COPYING-libepoxy",
                    "share/doc/COPYING-virglrenderer",
                ),
            executables =
                setOf(
                    "bin/virgl_test_server_android",
                    "libexec/virgl_render_server",
                ),
            metadataKeys = setOf("termux_packages_commit"),
        )

    fun install(context: Context): VirglHostRuntime {
        val directory = VerifiedRuntimeAssetInstaller.install(context, bundle)
        return VirglHostRuntime(
            executable = File(directory, "bin/virgl_test_server_android"),
            renderServerExecutable = File(directory, "libexec/virgl_render_server"),
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

internal object VenusGuestRuntimeInstaller {
    const val GUEST_DIRECTORY = "/opt/udroid/venus-mesa"
    const val VERSION = "26.1.5-jammy"
    private val bundle =
        VerifiedRuntimeAssetBundle(
            name = "Venus Mesa runtime for Ubuntu 22.04 and 24.04",
            assetDirectory = "venus-mesa",
            destinationPrefix = "venus-mesa",
            version = VERSION,
            entries =
                listOf(
                    "bin/zink_check_requirements",
                    "lib/dri/zink_dri.so",
                    "lib/libEGL_mesa.so.0",
                    "lib/libGLX_mesa.so.0",
                    "lib/libgallium-26.1.5.so",
                    "lib/libvulkan_virtio.so",
                    "share/doc/license.rst",
                    "share/drirc.d/00-mesa-defaults.conf",
                    "share/glvnd/egl_vendor.d/50_mesa.json",
                    "share/vulkan/icd.d/virtio_icd.aarch64.json",
                ),
            executables = setOf("bin/zink_check_requirements"),
            metadataKeys = setOf("mesa_commit"),
        )

    fun install(
        context: Context,
        rootfs: File,
    ): VenusGuestRuntime? {
        if (!requiresBundledMesa(rootfs)) return null
        val directory = VerifiedRuntimeAssetInstaller.install(context, bundle)
        return VenusGuestRuntime(directory)
    }

    internal fun requiresBundledMesa(rootfs: File): Boolean {
        val values =
            runCatching {
                File(rootfs, "etc/os-release").useLines { lines ->
                    lines
                        .mapNotNull { line ->
                            line.split('=', limit = 2).takeIf { it.size == 2 }
                        }.associate { (key, value) ->
                            key to value.removeSurrounding("\"").removeSurrounding("'")
                        }
                }
            }.getOrNull() ?: return false
        return values["ID"] == "ubuntu" && values["VERSION_ID"] in setOf("22.04", "24.04")
    }
}

internal data class ZinkRequirementResult(
    val profile: String,
    val supported: Boolean,
) {
    val label: String =
        profile
            .removePrefix("VP_ZINK_")
            .replace(Regex("^gl(\\d)(\\d)_"), "OpenGL $1.$2 ")
            .replace('_', ' ')
}

internal fun parseZinkRequirements(output: String): List<ZinkRequirementResult> {
    var profile: String? = null
    return buildList {
        output.lineSequence().forEach { line ->
            when {
                line.startsWith("Checking profile ") ->
                    profile = line.removePrefix("Checking profile ").trim()
                line == "Supported" -> {
                    profile?.let { add(ZinkRequirementResult(it, true)) }
                    profile = null
                }
                line.startsWith("UNSUPPORTED") -> {
                    profile?.let { add(ZinkRequirementResult(it, false)) }
                    profile = null
                }
            }
        }
    }
}
