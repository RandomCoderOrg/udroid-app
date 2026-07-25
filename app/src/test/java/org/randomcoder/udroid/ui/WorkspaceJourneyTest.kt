package org.randomcoder.udroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceJourneyTest {
    @Test
    fun `fresh install opens Linux images and hides unusable destinations`() {
        val journey =
            workspaceJourney(
                requestedDestination = UdroidDestination.HOME,
                hasInstalledLinux = false,
                hasInstallation = false,
                compactNavigation = true,
            )

        assertEquals(WorkspaceStage.NEEDS_LINUX, journey.stage)
        assertEquals(UdroidDestination.DISTROS, journey.destination)
        assertEquals(
            listOf(UdroidDestination.DISTROS, UdroidDestination.LOGS),
            journey.destinations,
        )
        assertFalse(journey.destinations.contains(UdroidDestination.TERMINAL))
        assertFalse(journey.destinations.contains(UdroidDestination.APPS))
    }

    @Test
    fun `installed Linux restores the complete workspace`() {
        val journey =
            workspaceJourney(
                requestedDestination = UdroidDestination.HOME,
                hasInstalledLinux = true,
                hasInstallation = false,
                compactNavigation = true,
            )

        assertEquals(WorkspaceStage.READY, journey.stage)
        assertEquals(UdroidDestination.HOME, journey.destination)
        assertTrue(journey.destinations.contains(UdroidDestination.TERMINAL))
        assertTrue(journey.destinations.contains(UdroidDestination.APPS))
        assertFalse(journey.destinations.contains(UdroidDestination.DESKTOP))
    }

    @Test
    fun `shortcut cannot enter Apps before Linux is installed`() {
        val journey =
            workspaceJourney(
                requestedDestination = UdroidDestination.APPS,
                hasInstalledLinux = false,
                hasInstallation = false,
                compactNavigation = false,
            )

        assertEquals(UdroidDestination.DISTROS, journey.destination)
    }

    @Test
    fun `paused or active setup remains in the installation journey`() {
        val journey =
            workspaceJourney(
                requestedDestination = UdroidDestination.HOME,
                hasInstalledLinux = false,
                hasInstallation = true,
                compactNavigation = true,
            )

        assertEquals(WorkspaceStage.SETTING_UP, journey.stage)
        assertEquals(UdroidDestination.DISTROS, journey.destination)
    }
}
