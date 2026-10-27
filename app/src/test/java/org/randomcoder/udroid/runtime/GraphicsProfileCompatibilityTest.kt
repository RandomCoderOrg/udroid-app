package org.randomcoder.udroid.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicsProfileCompatibilityTest {
    @Test
    fun `only exposes profiles whose device and guest requirements are present`() {
        val compatibleDevice =
            GraphicsDeviceSupport(
                arm64 = true,
                openGlEsVersion = 0x00030000,
                vulkanVersion = (1 shl 22) or (1 shl 12),
                hardwareBuffer = true,
            )
        val compatibleGuest =
            GraphicsGuestSupport(
                software = true,
                zink = true,
                vulkanIcd = true,
                virgl = true,
                venus = true,
            )

        val supported = GraphicsProfileCompatibilityProbe.classify(compatibleDevice, compatibleGuest)
        assertTrue(supported.getValue(DesktopGraphicsProfile.VIRGL).available)
        assertTrue(supported.getValue(DesktopGraphicsProfile.VIRGL_ANGLE).available)
        assertTrue(supported.getValue(DesktopGraphicsProfile.VENUS_EXPERIMENTAL).available)

        val noAndroidVulkan =
            GraphicsProfileCompatibilityProbe.classify(
                compatibleDevice.copy(vulkanVersion = 0, hardwareBuffer = false),
                compatibleGuest,
            )
        assertTrue(noAndroidVulkan.getValue(DesktopGraphicsProfile.VIRGL).available)
        assertTrue(noAndroidVulkan.getValue(DesktopGraphicsProfile.ZINK).available)
        assertFalse(noAndroidVulkan.getValue(DesktopGraphicsProfile.VIRGL_ANGLE).available)
        assertFalse(noAndroidVulkan.getValue(DesktopGraphicsProfile.VENUS_EXPERIMENTAL).available)

        val minimalGuest =
            GraphicsProfileCompatibilityProbe.classify(
                compatibleDevice,
                compatibleGuest.copy(
                    software = false,
                    zink = false,
                    vulkanIcd = false,
                    virgl = false,
                    venus = false,
                ),
            )
        assertTrue(minimalGuest.getValue(DesktopGraphicsProfile.STANDARD).available)
        assertFalse(minimalGuest.getValue(DesktopGraphicsProfile.SOFTWARE).available)
        assertFalse(minimalGuest.getValue(DesktopGraphicsProfile.ZINK).available)
        assertFalse(minimalGuest.getValue(DesktopGraphicsProfile.VIRGL).available)
        assertFalse(minimalGuest.getValue(DesktopGraphicsProfile.VENUS_EXPERIMENTAL).available)
    }
}
