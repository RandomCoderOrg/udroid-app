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
        when {
            !hasInstalledLinux && requestedDestination.requiresInstalledLinux ->
                UdroidDestination.DISTROS
            requestedDestination == UdroidDestination.SYSTEM &&
                !hasInstalledLinux &&
                !hasInstallation ->
                UdroidDestination.DISTROS
            else -> requestedDestination
        }
    val destinations =
        if (hasInstalledLinux) {
            UdroidDestination.entries.filterNot {
                it == UdroidDestination.SYSTEM ||
                    it == UdroidDestination.MOUNTS ||
                    it == UdroidDestination.MOUNT_EDITOR ||
                    it == UdroidDestination.ENVIRONMENT ||
                    it == UdroidDestination.DESKTOP ||
                    (compactNavigation && it == UdroidDestination.DEVICE)
            }
        } else {
            buildList {
                add(UdroidDestination.HOME)
                add(UdroidDestination.DISTROS)
                if (!compactNavigation) add(UdroidDestination.DEVICE)
                add(UdroidDestination.ABOUT)
            }
        }
    return WorkspaceJourney(
        stage = stage,
        destination = destination,
        destinations = destinations,
    )
}

internal fun workspaceHistoryAfter(
    current: UdroidDestination,
    next: UdroidDestination,
    history: List<UdroidDestination>,
    goingBack: Boolean = false,
): List<UdroidDestination> = when {
    !next.isWorkspaceView -> emptyList()
    current == next -> history
    goingBack -> history.dropLast(1)
    !current.isWorkspaceView -> listOf(current)
    // Keep the entry screen and the last view, even after repeated toolbar switches.
    else -> history.take(1) + current
}

private val UdroidDestination.isWorkspaceView: Boolean
    get() = this == UdroidDestination.TERMINAL || this == UdroidDestination.DESKTOP

val UdroidDestination.requiresInstalledLinux: Boolean
    get() =
        isWorkspaceView ||
            this == UdroidDestination.MOUNTS ||
            this == UdroidDestination.MOUNT_EDITOR ||
            this == UdroidDestination.ENVIRONMENT ||
            this == UdroidDestination.APPS
