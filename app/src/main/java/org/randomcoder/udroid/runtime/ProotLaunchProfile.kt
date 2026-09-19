package org.randomcoder.udroid.runtime

/** Optional, command-scoped additions to a PRoot launch. */
interface ProotLaunchProfile {
    fun addBindings(arguments: MutableList<String>)

    fun wrapGuestCommand(command: List<String>): List<String>
}

internal class EnvironmentProotLaunchProfile private constructor(
    private val environment: List<String>,
) : ProotLaunchProfile {
    override fun addBindings(arguments: MutableList<String>) = Unit

    override fun wrapGuestCommand(command: List<String>): List<String> =
        listOf("/usr/bin/env") + environment + command

    companion object {
        fun from(profile: DesktopGraphicsProfile): ProotLaunchProfile? =
            when (profile) {
                DesktopGraphicsProfile.STANDARD -> null
                DesktopGraphicsProfile.SOFTWARE ->
                    EnvironmentProotLaunchProfile(
                        listOf("LIBGL_ALWAYS_SOFTWARE=1", "GALLIUM_DRIVER=llvmpipe"),
                    )
                DesktopGraphicsProfile.ZINK ->
                    EnvironmentProotLaunchProfile(
                        listOf(
                            "MESA_LOADER_DRIVER_OVERRIDE=zink",
                            "GALLIUM_DRIVER=zink",
                            "LIBGL_KOPPER_DRI2=true",
                        ),
                    )
                DesktopGraphicsProfile.VIRGL -> null
                DesktopGraphicsProfile.VIRGL_ANGLE -> null
                DesktopGraphicsProfile.GFXSTREAM_EXPERIMENTAL -> null
            }
    }
}
