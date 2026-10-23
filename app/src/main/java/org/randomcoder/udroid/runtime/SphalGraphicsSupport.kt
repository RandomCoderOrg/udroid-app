package org.randomcoder.udroid.runtime

import android.content.Context
import java.io.File

object SphalGraphicsSupport {
    private const val PREFERENCES_NAME = "experimental-graphics"
    private const val KEY_ENABLED = "sphal-enabled"
    const val GUEST_RUNNER = "/opt/proot-sphal/bin/proot-vulkan-x11-run"
    const val GUEST_MESA_LIBRARY_DIRECTORY = "/opt/udroid/graphics/lib/mesa"
    const val GUEST_MESA_DRI_DIRECTORY = "$GUEST_MESA_LIBRARY_DIRECTORY/dri"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        check(
            context.applicationContext
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .commit(),
        ) {
            "Could not save the SPHAL graphics setting"
        }
    }

    fun findVulkanHal(): String? =
        listOf("/vendor/lib64/hw", "/odm/lib64/hw")
            .asSequence()
            .flatMap { directory ->
                File(directory).listFiles().orEmpty().asSequence()
            }.filter { file ->
                file.isFile &&
                    file.name.startsWith("vulkan.") &&
                    file.name.endsWith(".so") &&
                    file.name != "vulkan.default.so"
            }.map(File::getAbsolutePath)
            .sorted()
            .singleOrNull()

    fun isGuestRuntimeAvailable(rootfs: File): Boolean =
        listOf(
            GUEST_RUNNER,
            "$GUEST_MESA_LIBRARY_DIRECTORY/libGL.so.1",
            "$GUEST_MESA_DRI_DIRECTORY/zink_dri.so",
        ).all { path -> File(rootfs, path.removePrefix("/")).isFile }
}

internal class SphalProotLaunchProfile(
    private val vulkanHal: String,
) : ProotLaunchProfile {
    override fun addBindings(arguments: MutableList<String>) {
        listOf("/system_ext", "/product", "/vendor", "/odm")
            .filter { File(it).exists() }
            .forEach { path ->
                if (arguments.windowed(2).none { it == listOf("-b", path) }) {
                    arguments += listOf("-b", path)
                }
            }
    }

    override fun wrapGuestCommand(command: List<String>): List<String> =
        listOf(
            "/usr/bin/env",
            "MESA_LOADER_DRIVER_OVERRIDE=zink",
            "GALLIUM_DRIVER=zink",
            "LIBGL_KOPPER_DRI2=true",
            "ZINK_FORCE_XCB_SURFACE=1",
            "LD_LIBRARY_PATH=${SphalGraphicsSupport.GUEST_MESA_LIBRARY_DIRECTORY}:" +
                "/usr/lib/aarch64-linux-gnu:/lib/aarch64-linux-gnu",
            "LIBGL_DRIVERS_PATH=${SphalGraphicsSupport.GUEST_MESA_DRI_DIRECTORY}",
            SphalGraphicsSupport.GUEST_RUNNER,
            vulkanHal,
        ) + command
}
