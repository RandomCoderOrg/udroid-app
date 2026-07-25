package org.randomcoder.udroid.ui

enum class WorkspaceStage {
    NEEDS_LINUX,
    SETTING_UP,
    READY,
}

data class WorkspaceJourney(
    val stage: WorkspaceStage,
    val destination: UdroidDestination,
    val destinations: List<UdroidDestination>,
)

fun workspaceJourney(
    requestedDestination: UdroidDestination,
    hasInstalledLinux: Boolean,
    hasInstallation: Boolean,
    compactNavigation: Boolean,
): WorkspaceJourney {
    val stage =
        when {
            hasInstalledLinux -> WorkspaceStage.READY
            hasInstallation -> WorkspaceStage.SETTING_UP
            else -> WorkspaceStage.NEEDS_LINUX
        }
    val destination =
        if (!hasInstalledLinux && requestedDestination.requiresInstalledLinux) {
            UdroidDestination.DISTROS
        } else {
            requestedDestination
        }
    val destinations =
        if (hasInstalledLinux) {
            UdroidDestination.entries.filterNot {
                it == UdroidDestination.DESKTOP ||
                    (compactNavigation && it == UdroidDestination.DEVICE)
            }
        } else {
            buildList {
                add(UdroidDestination.DISTROS)
                if (!compactNavigation) add(UdroidDestination.DEVICE)
                add(UdroidDestination.LOGS)
            }
        }
    return WorkspaceJourney(
        stage = stage,
        destination = destination,
        destinations = destinations,
    )
}

val UdroidDestination.requiresInstalledLinux: Boolean
    get() =
        this == UdroidDestination.HOME ||
            this == UdroidDestination.TERMINAL ||
            this == UdroidDestination.APPS ||
            this == UdroidDestination.DESKTOP
