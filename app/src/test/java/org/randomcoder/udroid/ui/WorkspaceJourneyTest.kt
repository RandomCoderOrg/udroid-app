package org.randomcoder.udroid.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceJourneyTest {
    @Test
    fun `fresh install opens Home and keeps setup destinations available`() {
        val journey =
            workspaceJourney(
                requestedDestination = UdroidDestination.HOME,
                hasInstalledLinux = false,
                hasInstallation = false,
                compactNavigation = true,
            )

        assertEquals(WorkspaceStage.NEEDS_LINUX, journey.stage)
        assertEquals(UdroidDestination.HOME, journey.destination)
        assertEquals(
            listOf(
                UdroidDestination.HOME,
                UdroidDestination.DISTROS,
                UdroidDestination.ABOUT,
            ),
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
        assertFalse(journey.destinations.contains(UdroidDestination.SYSTEM))
        assertFalse(journey.destinations.contains(UdroidDestination.INSTALL))
        assertFalse(journey.destinations.contains(UdroidDestination.DESKTOP))
    }

    @Test
    fun `installed system detail is reachable but never becomes a navigation tab`() {
        val journey =
            workspaceJourney(
                requestedDestination = UdroidDestination.SYSTEM,
                hasInstalledLinux = true,
                hasInstallation = false,
                compactNavigation = false,
            )

        assertEquals(UdroidDestination.SYSTEM, journey.destination)
        assertFalse(journey.destinations.contains(UdroidDestination.SYSTEM))
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
    fun `about and maintenance remain available before Linux is installed`() {
        val journey =
            workspaceJourney(
                requestedDestination = UdroidDestination.ABOUT,
                hasInstalledLinux = false,
                hasInstallation = false,
                compactNavigation = true,
            )

        assertEquals(UdroidDestination.ABOUT, journey.destination)
        assertTrue(journey.destinations.contains(UdroidDestination.ABOUT))
    }

    @Test
    fun `paused or active setup keeps Home available`() {
        val journey =
            workspaceJourney(
                requestedDestination = UdroidDestination.HOME,
                hasInstalledLinux = false,
                hasInstallation = true,
                compactNavigation = true,
            )

        assertEquals(WorkspaceStage.SETTING_UP, journey.stage)
        assertEquals(UdroidDestination.HOME, journey.destination)
    }

    @Test
    fun `installation is a nested route and never a navigation tab`() {
        val journey =
            workspaceJourney(
                requestedDestination = UdroidDestination.INSTALL,
                hasInstalledLinux = true,
                hasInstallation = true,
                compactNavigation = true,
            )

        assertEquals(UdroidDestination.INSTALL, journey.destination)
        assertFalse(journey.destinations.contains(UdroidDestination.INSTALL))
        assertTrue(journey.destinations.contains(UdroidDestination.DISTROS))
    }

    @Test
    fun `stale installation route returns to the Linux catalogue`() {
        val journey =
            workspaceJourney(
                requestedDestination = UdroidDestination.INSTALL,
                hasInstalledLinux = true,
                hasInstallation = false,
                compactNavigation = true,
            )

        assertEquals(UdroidDestination.DISTROS, journey.destination)
    }

    @Test
    fun `nested Linux pages use directional motion`() {
        assertEquals(
            NavigationMotion.FORWARD,
            navigationMotion(UdroidDestination.DISTROS, UdroidDestination.SYSTEM),
        )
        assertEquals(
            NavigationMotion.FORWARD,
            navigationMotion(UdroidDestination.DISTROS, UdroidDestination.INSTALL),
        )
        assertEquals(
            NavigationMotion.BACK,
            navigationMotion(UdroidDestination.SYSTEM, UdroidDestination.DISTROS),
        )
        assertEquals(
            NavigationMotion.FORWARD,
            navigationMotion(UdroidDestination.SYSTEM, UdroidDestination.MOUNTS),
        )
        assertEquals(
            NavigationMotion.BACK,
            navigationMotion(UdroidDestination.MOUNTS, UdroidDestination.SYSTEM),
        )
        assertEquals(
            NavigationMotion.FORWARD,
            navigationMotion(UdroidDestination.MOUNTS, UdroidDestination.MOUNT_EDITOR),
        )
        assertEquals(
            NavigationMotion.BACK,
            navigationMotion(UdroidDestination.MOUNT_EDITOR, UdroidDestination.MOUNTS),
        )
    }

    @Test
    fun `mount pages stay available without becoming navigation tabs`() {
        val journey =
            workspaceJourney(
                requestedDestination = UdroidDestination.MOUNTS,
                hasInstalledLinux = false,
                hasInstallation = false,
                compactNavigation = true,
            )

        assertEquals(UdroidDestination.DISTROS, journey.destination)
        assertFalse(journey.destinations.contains(UdroidDestination.MOUNTS))
        assertFalse(journey.destinations.contains(UdroidDestination.MOUNT_EDITOR))
    }

    @Test
    fun `top level destinations use a compact fade`() {
        assertEquals(
            NavigationMotion.FADE,
            navigationMotion(UdroidDestination.HOME, UdroidDestination.DISTROS),
        )
    }
}
