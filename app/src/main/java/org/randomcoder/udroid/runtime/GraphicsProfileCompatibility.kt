package org.randomcoder.udroid.runtime

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.HardwareBuffer
import android.os.Build
import org.randomcoder.udroid.virgl.VenusGuestRuntimeInstaller
import java.io.File

data class GraphicsProfileSupport(
    val available: Boolean,
    val reason: String? = null,
)

internal data class GraphicsDeviceSupport(
    val arm64: Boolean,
    val openGlEsVersion: Int,
    val vulkanVersion: Int,
    val hardwareBuffer: Boolean,
)

internal data class GraphicsGuestSupport(
    val software: Boolean,
    val zink: Boolean,
    val vulkanIcd: Boolean,
    val virgl: Boolean,
    val venus: Boolean,
)

object GraphicsProfileCompatibilityProbe {
    fun run(
        context: Context,
        rootfs: File,
    ): Map<DesktopGraphicsProfile, GraphicsProfileSupport> =
        classify(
            device = deviceSupport(context),
            guest = guestSupport(rootfs),
        )

    internal fun classify(
        device: GraphicsDeviceSupport,
        guest: GraphicsGuestSupport,
    ): Map<DesktopGraphicsProfile, GraphicsProfileSupport> =
        mapOf(
            DesktopGraphicsProfile.STANDARD to supported(),
            DesktopGraphicsProfile.SOFTWARE to
                require(guest.software, "Mesa software rendering is not installed in Linux"),
            DesktopGraphicsProfile.ZINK to
                require(
                    guest.zink && guest.vulkanIcd,
                    if (guest.zink) {
                        "No Vulkan driver is installed in Linux"
                    } else {
                        "Mesa Zink is not installed in Linux"
                    },
                ),
            DesktopGraphicsProfile.VIRGL to
                firstFailure(
                    device.arm64 to "VirGL is packaged only for arm64 devices",
                    isOpenGlEs3(device.openGlEsVersion) to "VirGL requires Android OpenGL ES 3.0",
                    guest.virgl to "Mesa VirGL support is not installed in Linux",
                ),
            DesktopGraphicsProfile.VIRGL_ANGLE to
                firstFailure(
                    device.arm64 to "VirGL + ANGLE is packaged only for arm64 devices",
                    isVulkan11(device.vulkanVersion) to
                        "VirGL + ANGLE requires Android Vulkan 1.1",
                    guest.virgl to "Mesa VirGL support is not installed in Linux",
                ),
            DesktopGraphicsProfile.VENUS_EXPERIMENTAL to
                firstFailure(
                    device.arm64 to "Venus is packaged only for arm64 devices",
                    isVulkan11(device.vulkanVersion) to "Venus requires Android Vulkan 1.1",
                    device.hardwareBuffer to "This device cannot allocate GPU hardware buffers",
                    guest.venus to "A compatible Mesa Venus driver is not available in Linux",
                ),
            DesktopGraphicsProfile.GFXSTREAM_EXPERIMENTAL to
                require(GFXSTREAM_PROFILE_ENABLED, "gfxstream is not enabled in this build"),
        )

    private fun deviceSupport(context: Context): GraphicsDeviceSupport {
        val packageManager = context.packageManager
        val vulkanVersion =
            packageManager.systemAvailableFeatures
                .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }
                ?.version ?: 0
        val openGlEsVersion =
            context.getSystemService(ActivityManager::class.java)
                ?.deviceConfigurationInfo
                ?.reqGlEsVersion ?: 0
        val hardwareBuffer =
            runCatching {
                HardwareBuffer.create(
                    4,
                    4,
                    HardwareBuffer.RGBA_8888,
                    1,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
                ).close()
            }.isSuccess
        return GraphicsDeviceSupport(
            arm64 = "arm64-v8a" in Build.SUPPORTED_ABIS,
            openGlEsVersion = openGlEsVersion,
            vulkanVersion = vulkanVersion,
            hardwareBuffer = hardwareBuffer,
        )
    }

    private fun guestSupport(rootfs: File): GraphicsGuestSupport {
        val driDirectories =
            listOf(
                "usr/lib/aarch64-linux-gnu/dri",
                "usr/lib64/dri",
                "usr/lib/dri",
            ).map(rootfs::resolve)
        val icdDirectories =
            listOf(
                "usr/share/vulkan/icd.d",
                "etc/vulkan/icd.d",
            ).map(rootfs::resolve)
        val libraryDirectories =
            listOf(
                "usr/lib/aarch64-linux-gnu",
                "usr/lib64",
                "usr/lib",
            ).map(rootfs::resolve)
        val hasDriDriver = { name: String -> driDirectories.any { File(it, name).isFile } }
        val icds =
            icdDirectories.flatMap { directory ->
                directory.listFiles()
                    ?.filter { file -> file.isFile && file.extension == "json" }
                    .orEmpty()
            }
        val systemVenus =
            icds.any { it.name.contains("virtio", ignoreCase = true) } &&
                libraryDirectories.any { directory ->
                    directory.listFiles()
                        ?.any { file ->
                            file.isFile && file.name.startsWith("libvulkan_virtio.so")
                        } == true
                }
        return GraphicsGuestSupport(
            software = hasDriDriver("swrast_dri.so"),
            zink = hasDriDriver("zink_dri.so"),
            vulkanIcd = icds.isNotEmpty(),
            virgl = hasDriDriver("virtio_gpu_dri.so"),
            venus = VenusGuestRuntimeInstaller.requiresBundledMesa(rootfs) || systemVenus,
        )
    }

    private fun firstFailure(vararg checks: Pair<Boolean, String>): GraphicsProfileSupport =
        checks.firstOrNull { !it.first }
            ?.let { GraphicsProfileSupport(false, it.second) }
            ?: supported()

    private fun require(
        condition: Boolean,
        reason: String,
    ): GraphicsProfileSupport =
        if (condition) supported() else GraphicsProfileSupport(false, reason)

    private fun supported() = GraphicsProfileSupport(true)

    private fun isOpenGlEs3(version: Int): Boolean = version >= 0x00030000

    private fun isVulkan11(version: Int): Boolean =
        (version ushr 22) > 1 ||
            (version ushr 22) == 1 && ((version ushr 12) and 0x3ff) >= 1
}
