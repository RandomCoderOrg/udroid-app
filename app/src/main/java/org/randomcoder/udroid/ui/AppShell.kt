package org.randomcoder.udroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Feedback
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.randomcoder.udroid.BuildConfig
import org.randomcoder.udroid.catalog.DistroCatalogState
import org.randomcoder.udroid.catalog.DistroVariant
import org.randomcoder.udroid.install.InstallProgress
import org.randomcoder.udroid.linuxapps.LinuxApplication
import org.randomcoder.udroid.linuxapps.LinuxApplicationsState
import org.randomcoder.udroid.runtime.CapabilityResult
import org.randomcoder.udroid.runtime.CapabilityStatus
import org.randomcoder.udroid.runtime.DesktopConfiguration
import org.randomcoder.udroid.runtime.DesktopEnvironment
import org.randomcoder.udroid.runtime.InstalledRootfs
import org.randomcoder.udroid.runtime.RuntimePhase
import org.randomcoder.udroid.runtime.RuntimeSnapshot
import org.randomcoder.udroid.runtime.RuntimeSupervisorService
import org.randomcoder.udroid.update.AppUpdatePhase
import org.randomcoder.udroid.update.AppUpdateState
import java.time.Instant

enum class UdroidDestination(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    HOME("Home", Icons.Outlined.Home, Icons.Filled.Home),
    DISTROS("Linux", Icons.Outlined.Storage, Icons.Filled.Storage),
    SYSTEM("System", Icons.Outlined.Storage, Icons.Filled.Storage),
    TERMINAL("Terminal", Icons.Outlined.Terminal, Icons.Filled.Terminal),
    APPS("Apps", Icons.Outlined.Apps, Icons.Filled.Apps),
    DESKTOP("Desktop", Icons.Outlined.DesktopWindows, Icons.Filled.DesktopWindows),
    DEVICE("Device", Icons.Outlined.Memory, Icons.Filled.Memory),
    ABOUT("About", Icons.Outlined.Info, Icons.Filled.Info),
}

@Composable
fun UdroidApp(
    destination: UdroidDestination,
    snapshot: RuntimeSnapshot,
    capabilities: List<CapabilityResult>,
    journalLines: List<String>,
    catalogueState: DistroCatalogState,
    installProgress: InstallProgress?,
    updateState: AppUpdateState,
    installedRootfsName: String?,
    installedRootfses: List<InstalledRootfs>,
    selectedSystemRootfsName: String?,
    desktopEnvironments: List<DesktopEnvironment>,
    desktopConfiguration: DesktopConfiguration,
    desktopScanLoading: Boolean,
    desktopScanMessage: String?,
    linuxApplicationsState: LinuxApplicationsState,
    linuxApplicationMessage: String?,
    showInstallTerminal: Boolean,
    runtimeService: RuntimeSupervisorService?,
    onDestinationSelected: (UdroidDestination) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onReloadCatalogue: () -> Unit,
    onPreviewInstall: (DistroVariant) -> Unit,
    onOpenInstalledSystem: (String) -> Unit,
    onOpenRootfsTerminal: (String) -> Unit,
    onOpenRootfsApps: (String) -> Unit,
    onSelectDesktopEnvironment: (String) -> Unit,
    onCompositingChanged: (Boolean) -> Unit,
    onTouchScaleChanged: (Boolean) -> Unit,
    onStartDesktop: () -> Unit,
    onStopDesktop: () -> Unit,
    onRestartDesktop: () -> Unit,
    onStartDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onToggleInstallTerminal: () -> Unit,
    onCloseInstall: () -> Unit,
    onRetryDownload: () -> Unit,
    onRefreshLinuxApplications: () -> Unit,
    onLaunchLinuxApplication: (LinuxApplication) -> Unit,
    onPinLinuxApplication: (LinuxApplication) -> Unit,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onCancelUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenUpdateRelease: () -> Unit,
) {
    val hasInstalledLinux = installedRootfsName != null
    val requestedJourney =
        workspaceJourney(
            requestedDestination = destination,
            hasInstalledLinux = hasInstalledLinux,
            hasInstallation = installProgress != null,
            compactNavigation = false,
        )
    val activeDestination = requestedJourney.destination

    if (activeDestination == UdroidDestination.DESKTOP) {
        DesktopPage(
            snapshot = snapshot,
            service = runtimeService,
            onExit = { onDestinationSelected(UdroidDestination.SYSTEM) },
        )
        return
    }

    if (activeDestination == UdroidDestination.TERMINAL) {
        UdroidTerminalTheme {
            BackHandler {
                onDestinationSelected(UdroidDestination.SYSTEM)
            }
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = UdroidTerminal,
            ) {
                InteractiveTerminalPage(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                    snapshot = snapshot,
                    service = runtimeService,
                    onStart = onStart,
                    onStop = onStop,
                    onExit = { onDestinationSelected(UdroidDestination.SYSTEM) },
                )
            }
        }
        return
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = UdroidCanvas,
    ) {
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            val useRail = maxWidth >= 600.dp
            if (useRail) {
                Row(modifier = Modifier.fillMaxSize()) {
                    WorkspaceNavigationRail(
                        selected = activeDestination,
                        destinations = requestedJourney.destinations,
                        onSelected = onDestinationSelected,
                    )
                    Divider(
                        modifier = Modifier.fillMaxHeight().width(1.dp),
                        color = UdroidLine,
                    )
                    ManagementPane(
                        modifier = Modifier.weight(1f),
                        destination = activeDestination,
                        snapshot = snapshot,
                        capabilities = capabilities,
                        journalLines = journalLines,
                        catalogueState = catalogueState,
                        installProgress = installProgress,
                        updateState = updateState,
                        installedRootfsName = installedRootfsName,
                        installedRootfses = installedRootfses,
                        selectedSystemRootfsName = selectedSystemRootfsName,
                        desktopEnvironments = desktopEnvironments,
                        desktopConfiguration = desktopConfiguration,
                        desktopScanLoading = desktopScanLoading,
                        desktopScanMessage = desktopScanMessage,
                        linuxApplicationsState = linuxApplicationsState,
                        linuxApplicationMessage = linuxApplicationMessage,
                        showInstallTerminal = showInstallTerminal,
                        onDestinationSelected = onDestinationSelected,
                        onStart = onStart,
                        onStop = onStop,
                        onRefresh = onRefresh,
                        onReloadCatalogue = onReloadCatalogue,
                        onPreviewInstall = onPreviewInstall,
                        onOpenInstalledSystem = onOpenInstalledSystem,
                        onOpenRootfsTerminal = onOpenRootfsTerminal,
                        onOpenRootfsApps = onOpenRootfsApps,
                        onSelectDesktopEnvironment = onSelectDesktopEnvironment,
                        onCompositingChanged = onCompositingChanged,
                        onTouchScaleChanged = onTouchScaleChanged,
                        onStartDesktop = onStartDesktop,
                        onStopDesktop = onStopDesktop,
                        onRestartDesktop = onRestartDesktop,
                        onStartDownload = onStartDownload,
                        onPauseDownload = onPauseDownload,
                        onToggleInstallTerminal = onToggleInstallTerminal,
                        onCloseInstall = onCloseInstall,
                        onRetryDownload = onRetryDownload,
                        onRefreshLinuxApplications = onRefreshLinuxApplications,
                        onLaunchLinuxApplication = onLaunchLinuxApplication,
                        onPinLinuxApplication = onPinLinuxApplication,
                        onCheckForUpdates = onCheckForUpdates,
                        onDownloadUpdate = onDownloadUpdate,
                        onCancelUpdate = onCancelUpdate,
                        onInstallUpdate = onInstallUpdate,
                        onOpenUpdateRelease = onOpenUpdateRelease,
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    ManagementPane(
                        modifier = Modifier.weight(1f),
                        destination = activeDestination,
                        snapshot = snapshot,
                        capabilities = capabilities,
                        journalLines = journalLines,
                        catalogueState = catalogueState,
                        installProgress = installProgress,
                        updateState = updateState,
                        installedRootfsName = installedRootfsName,
                        installedRootfses = installedRootfses,
                        selectedSystemRootfsName = selectedSystemRootfsName,
                        desktopEnvironments = desktopEnvironments,
                        desktopConfiguration = desktopConfiguration,
                        desktopScanLoading = desktopScanLoading,
                        desktopScanMessage = desktopScanMessage,
                        linuxApplicationsState = linuxApplicationsState,
                        linuxApplicationMessage = linuxApplicationMessage,
                        showInstallTerminal = showInstallTerminal,
                        onDestinationSelected = onDestinationSelected,
                        onStart = onStart,
                        onStop = onStop,
                        onRefresh = onRefresh,
                        onReloadCatalogue = onReloadCatalogue,
                        onPreviewInstall = onPreviewInstall,
                        onOpenInstalledSystem = onOpenInstalledSystem,
                        onOpenRootfsTerminal = onOpenRootfsTerminal,
                        onOpenRootfsApps = onOpenRootfsApps,
                        onSelectDesktopEnvironment = onSelectDesktopEnvironment,
                        onCompositingChanged = onCompositingChanged,
                        onTouchScaleChanged = onTouchScaleChanged,
                        onStartDesktop = onStartDesktop,
                        onStopDesktop = onStopDesktop,
                        onRestartDesktop = onRestartDesktop,
                        onStartDownload = onStartDownload,
                        onPauseDownload = onPauseDownload,
                        onToggleInstallTerminal = onToggleInstallTerminal,
                        onCloseInstall = onCloseInstall,
                        onRetryDownload = onRetryDownload,
                        onRefreshLinuxApplications = onRefreshLinuxApplications,
                        onLaunchLinuxApplication = onLaunchLinuxApplication,
                        onPinLinuxApplication = onPinLinuxApplication,
                        onCheckForUpdates = onCheckForUpdates,
                        onDownloadUpdate = onDownloadUpdate,
                        onCancelUpdate = onCancelUpdate,
                        onInstallUpdate = onInstallUpdate,
                        onOpenUpdateRelease = onOpenUpdateRelease,
                    )
                    WorkspaceNavigationBar(
                        selected = activeDestination,
                        destinations =
                            workspaceJourney(
                                requestedDestination = activeDestination,
                                hasInstalledLinux = hasInstalledLinux,
                                hasInstallation = installProgress != null,
                                compactNavigation = true,
                            ).destinations,
                        onSelected = onDestinationSelected,
                    )
                }
            }
        }
    }
}

@Composable
private fun ManagementPane(
    destination: UdroidDestination,
    snapshot: RuntimeSnapshot,
    capabilities: List<CapabilityResult>,
    journalLines: List<String>,
    catalogueState: DistroCatalogState,
    installProgress: InstallProgress?,
    updateState: AppUpdateState,
    installedRootfsName: String?,
    installedRootfses: List<InstalledRootfs>,
    selectedSystemRootfsName: String?,
    desktopEnvironments: List<DesktopEnvironment>,
    desktopConfiguration: DesktopConfiguration,
    desktopScanLoading: Boolean,
    desktopScanMessage: String?,
    linuxApplicationsState: LinuxApplicationsState,
    linuxApplicationMessage: String?,
    showInstallTerminal: Boolean,
    onDestinationSelected: (UdroidDestination) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onReloadCatalogue: () -> Unit,
    onPreviewInstall: (DistroVariant) -> Unit,
    onOpenInstalledSystem: (String) -> Unit,
    onOpenRootfsTerminal: (String) -> Unit,
    onOpenRootfsApps: (String) -> Unit,
    onSelectDesktopEnvironment: (String) -> Unit,
    onCompositingChanged: (Boolean) -> Unit,
    onTouchScaleChanged: (Boolean) -> Unit,
    onStartDesktop: () -> Unit,
    onStopDesktop: () -> Unit,
    onRestartDesktop: () -> Unit,
    onStartDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onToggleInstallTerminal: () -> Unit,
    onCloseInstall: () -> Unit,
    onRetryDownload: () -> Unit,
    onRefreshLinuxApplications: () -> Unit,
    onLaunchLinuxApplication: (LinuxApplication) -> Unit,
    onPinLinuxApplication: (LinuxApplication) -> Unit,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onCancelUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenUpdateRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxHeight()) {
        WorkspaceTopBar(
            snapshot = snapshot,
            onOpenTerminal = { onDestinationSelected(UdroidDestination.TERMINAL) },
        )
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .widthIn(max = 900.dp),
            ) {
                when (destination) {
                    UdroidDestination.HOME -> {
                        val installedDistro =
                            (catalogueState as? DistroCatalogState.Ready)
                                ?.catalog
                                ?.variants
                                ?.firstOrNull { it.internalName == installedRootfsName }
                        WorkspacePage(
                            snapshot = snapshot,
                            distro = installedDistro,
                            rootfsName = installedRootfsName,
                            installedCount = installedRootfses.size,
                            capabilities = capabilities,
                            onRefresh = onRefresh,
                            onOpenTerminal = {
                                onDestinationSelected(UdroidDestination.TERMINAL)
                            },
                            onOpenLinux = {
                                installedRootfsName?.let(onOpenInstalledSystem)
                                    ?: onDestinationSelected(UdroidDestination.DISTROS)
                            },
                            onOpenDevice = {
                                onDestinationSelected(UdroidDestination.DEVICE)
                            },
                            onOpenApps = {
                                onDestinationSelected(UdroidDestination.APPS)
                            },
                            onOpenDesktop = {
                                onDestinationSelected(UdroidDestination.DESKTOP)
                            },
                            onOpenAbout = {
                                onDestinationSelected(UdroidDestination.ABOUT)
                            },
                        )
                    }
                    UdroidDestination.DISTROS ->
                        installProgress?.let {
                            InstallExperiencePage(
                                progress = it,
                                showTerminal = showInstallTerminal,
                                onToggleTerminal = onToggleInstallTerminal,
                                onBack = onCloseInstall,
                                onOpenTerminal = {
                                    onOpenRootfsTerminal(it.distro.internalName)
                                },
                                onStartDownload = onStartDownload,
                                onPauseDownload = onPauseDownload,
                                onRetryDownload = onRetryDownload,
                            )
                        } ?: DistroCataloguePage(
                            state = catalogueState,
                            installedRootfses = installedRootfses,
                            activeRootfsName = installedRootfsName,
                            onRetry = onReloadCatalogue,
                            onPreviewInstall = onPreviewInstall,
                            onOpenInstalledSystem = onOpenInstalledSystem,
                        )
                    UdroidDestination.SYSTEM -> {
                        val rootfsName = selectedSystemRootfsName ?: installedRootfsName
                        val selectedRootfs =
                            installedRootfses.firstOrNull { it.name == rootfsName }
                        val selectedDistro =
                            (catalogueState as? DistroCatalogState.Ready)
                                ?.catalog
                                ?.variants
                                ?.firstOrNull { it.internalName == rootfsName }
                        if (selectedRootfs == null) {
                            onDestinationSelected(UdroidDestination.DISTROS)
                        } else {
                            LinuxSystemPage(
                                rootfs = selectedRootfs,
                                distro = selectedDistro,
                                active = rootfsName == installedRootfsName,
                                snapshot = snapshot,
                                environments = desktopEnvironments,
                                configuration = desktopConfiguration,
                                scanLoading = desktopScanLoading,
                                scanMessage = desktopScanMessage,
                                onBack = {
                                    onDestinationSelected(UdroidDestination.DISTROS)
                                },
                                onOpenTerminal = {
                                    onOpenRootfsTerminal(selectedRootfs.name)
                                },
                                onOpenApps = {
                                    onOpenRootfsApps(selectedRootfs.name)
                                },
                                onOpenDisplay = {
                                    onDestinationSelected(UdroidDestination.DESKTOP)
                                },
                                onSelectEnvironment = onSelectDesktopEnvironment,
                                onCompositingChanged = onCompositingChanged,
                                onTouchScaleChanged = onTouchScaleChanged,
                                onStartDesktop = onStartDesktop,
                                onStopDesktop = onStopDesktop,
                                onRestartDesktop = onRestartDesktop,
                            )
                        }
                    }
                    UdroidDestination.DEVICE ->
                        DevicePage(
                            capabilities = capabilities,
                            onRefresh = onRefresh,
                        )
                    UdroidDestination.ABOUT ->
                        AboutPage(
                            journalLines = journalLines,
                            updateState = updateState,
                            onRefresh = onRefresh,
                            onCheckForUpdates = onCheckForUpdates,
                            onDownloadUpdate = onDownloadUpdate,
                            onCancelUpdate = onCancelUpdate,
                            onInstallUpdate = onInstallUpdate,
                            onOpenUpdateRelease = onOpenUpdateRelease,
                        )
                    UdroidDestination.APPS ->
                        LinuxAppsPage(
                            state = linuxApplicationsState,
                            launchMessage = linuxApplicationMessage,
                            onRefresh = onRefreshLinuxApplications,
                            onLaunch = onLaunchLinuxApplication,
                            onPin = onPinLinuxApplication,
                            onOpenDesktop = {
                                onDestinationSelected(UdroidDestination.DESKTOP)
                            },
                        )
                    UdroidDestination.TERMINAL -> Unit
                    UdroidDestination.DESKTOP -> Unit
                }
            }
        }
    }
}

@Composable
private fun WorkspaceTopBar(
    snapshot: RuntimeSnapshot,
    onOpenTerminal: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(62.dp)
                .background(UdroidSurface)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UdroidBrand(compact = true)
        Spacer(Modifier.weight(1f))
        if (snapshot.phase == RuntimePhase.RUNNING) {
            Surface(
                modifier = Modifier.clickable(onClick = onOpenTerminal),
                color = UdroidSoftGreen,
                shape = RoundedCornerShape(9.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        modifier = Modifier.size(7.dp),
                        color = UdroidForest,
                        shape = CircleShape,
                    ) {}
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "SESSION LIVE",
                        color = UdroidForest,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Text(
            "v${BuildConfig.VERSION_NAME}",
            color = UdroidFaint,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelMedium,
        )
    }
    Divider(color = UdroidLine)
}

@Composable
private fun WorkspaceNavigationBar(
    selected: UdroidDestination,
    destinations: List<UdroidDestination>,
    onSelected: (UdroidDestination) -> Unit,
) {
    NavigationBar(
        containerColor = UdroidSurface,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        destinations.forEach { destination ->
            val active = selected == destination
            NavigationBarItem(
                selected = active,
                onClick = { onSelected(destination) },
                icon = {
                    Icon(
                        imageVector =
                            if (active) destination.selectedIcon else destination.icon,
                        contentDescription = destination.label,
                    )
                },
                label = {
                    Text(
                        destination.label,
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

@Composable
private fun WorkspaceNavigationRail(
    selected: UdroidDestination,
    destinations: List<UdroidDestination>,
    onSelected: (UdroidDestination) -> Unit,
) {
    NavigationRail(
        containerColor = UdroidSurface,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Spacer(Modifier.height(12.dp))
        destinations.forEach { destination ->
            val active = selected == destination
            NavigationRailItem(
                selected = active,
                onClick = { onSelected(destination) },
                icon = {
                    Icon(
                        imageVector =
                            if (active) destination.selectedIcon else destination.icon,
                        contentDescription = destination.label,
                    )
                },
                label = { Text(destination.label) },
            )
        }
    }
}

@Composable
private fun WorkspacePage(
    snapshot: RuntimeSnapshot,
    distro: DistroVariant?,
    rootfsName: String?,
    installedCount: Int,
    capabilities: List<CapabilityResult>,
    onRefresh: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenLinux: () -> Unit,
    onOpenDevice: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenDesktop: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val hasInstalledLinux = rootfsName != null
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            UdroidPageHeader(
                title = "Home",
                subtitle = "Everything in one place",
                modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
                trailing = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Refresh workspace",
                            tint = UdroidMuted,
                        )
                    }
                },
            )
        }
        item {
            UdroidSectionLabel(
                text = "Everything",
                modifier = Modifier.padding(top = 4.dp, bottom = 1.dp),
            )
        }
        item {
            UdroidToolRow(
                icon = Icons.Outlined.Storage,
                title = "Linux systems",
                subtitle =
                    distro?.releaseName
                        ?: rootfsName?.let(::installedSystemTitle)
                        ?: "Choose and install a distribution",
                trailingText =
                    when {
                        installedCount > 1 -> "$installedCount installed"
                        hasInstalledLinux -> "Installed"
                        else -> "Set up"
                    },
                onClick = onOpenLinux,
            )
        }
        item {
            UdroidToolRow(
                icon = Icons.Outlined.Terminal,
                title = "Terminal",
                subtitle =
                    if (hasInstalledLinux) {
                        "Open a shell in the installed Linux system"
                    } else {
                        "Install Linux to open a terminal"
                    },
                trailingText =
                    when {
                        !hasInstalledLinux -> "Needs Linux"
                        snapshot.phase == RuntimePhase.RUNNING -> "Live"
                        else -> null
                    },
                onClick = onOpenTerminal,
            )
        }
        item {
            UdroidToolRow(
                icon = Icons.Outlined.Apps,
                title = "Linux apps",
                subtitle =
                    if (hasInstalledLinux) {
                        "Find and launch installed applications"
                    } else {
                        "Install Linux to discover applications"
                    },
                trailingText = if (hasInstalledLinux) null else "Needs Linux",
                onClick = onOpenApps,
            )
        }
        item {
            UdroidToolRow(
                icon = Icons.Outlined.DesktopWindows,
                title = "Desktop",
                subtitle =
                    if (hasInstalledLinux) {
                        "Open the graphical Linux desktop"
                    } else {
                        "Install Linux to use a graphical desktop"
                    },
                trailingText = if (hasInstalledLinux) null else "Needs Linux",
                onClick = onOpenDesktop,
            )
        }
        item {
            val passed = capabilities.count { it.status == CapabilityStatus.PASS }
            UdroidToolRow(
                icon = Icons.Outlined.Memory,
                title = "Device compatibility",
                subtitle = "Runtime, architecture, and optional capabilities",
                trailingText =
                    if (capabilities.isEmpty()) {
                        null
                    } else {
                        "$passed/${capabilities.size}"
                    },
                onClick = onOpenDevice,
            )
        }
        item {
            UdroidToolRow(
                icon = Icons.Outlined.Info,
                title = "About uDroid",
                subtitle = "App updates, supervisor journal, and project details",
                onClick = onOpenAbout,
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun AppUpdatePanel(
    state: AppUpdateState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onOpenRelease: () -> Unit,
) {
    val release = state.release ?: return
    Surface(
        color = UdroidRaised,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, UdroidLine),
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    color = UdroidSoftGreen,
                    shape = RoundedCornerShape(9.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.SystemUpdateAlt,
                            contentDescription = null,
                            tint = UdroidForest,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "uDroid ${release.version}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        state.message ?: "Verified GitHub release",
                        color = UdroidMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                UdroidStatusBadge(
                    label =
                        when (state.phase) {
                            AppUpdatePhase.DOWNLOADING -> "${state.percentage}%"
                            AppUpdatePhase.READY -> "Ready"
                            else -> "Available"
                        },
                    color = UdroidForest,
                    background = UdroidSoftGreen,
                )
            }
            if (state.phase == AppUpdatePhase.DOWNLOADING) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = state.percentage / 100f,
                    modifier = Modifier.fillMaxWidth(),
                    color = UdroidForest,
                )
            }
            release.notes
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.takeIf(String::isNotBlank)
                ?.let { summary ->
                    Spacer(Modifier.height(10.dp))
                    Text(
                        summary,
                        color = UdroidMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (state.phase) {
                    AppUpdatePhase.DOWNLOADING ->
                        OutlinedButton(
                            onClick = onCancel,
                            shape = RoundedCornerShape(9.dp),
                        ) {
                            Text("Pause")
                        }
                    AppUpdatePhase.READY ->
                        Button(
                            onClick = onInstall,
                            shape = RoundedCornerShape(9.dp),
                        ) {
                            Text("Install update")
                        }
                    else ->
                        Button(
                            onClick = onDownload,
                            shape = RoundedCornerShape(9.dp),
                        ) {
                            Text("Download update")
                        }
                }
                TextButton(onClick = onOpenRelease) {
                    Text("Release notes")
                }
            }
        }
    }
}

private fun updateStatusText(state: AppUpdateState): String =
    when (state.phase) {
        AppUpdatePhase.IDLE -> "Current version ${BuildConfig.VERSION_NAME} · Tap to check"
        AppUpdatePhase.CHECKING -> "Checking GitHub releases…"
        AppUpdatePhase.UP_TO_DATE -> "Version ${BuildConfig.VERSION_NAME} is current"
        AppUpdatePhase.AVAILABLE -> state.message ?: "A verified release is available"
        AppUpdatePhase.DOWNLOADING -> "Downloading · ${state.percentage}%"
        AppUpdatePhase.READY -> "Verified and ready to install"
        AppUpdatePhase.FAILED -> state.message ?: "Update check needs attention"
    }

@Composable
private fun DevicePage(
    capabilities: List<CapabilityResult>,
    onRefresh: () -> Unit,
) {
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            UdroidPageHeader(
                title = "Device",
                subtitle = "Runtime compatibility and optional hardware",
                modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
                trailing = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "Run probes again",
                            tint = UdroidMuted,
                        )
                    }
                },
            )
        }
        items(capabilities) { capability ->
            CapabilityRow(capability)
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun CapabilityRow(capability: CapabilityResult) {
    val (icon, tint, label) =
        when (capability.status) {
            CapabilityStatus.PASS ->
                Triple(Icons.Outlined.CheckCircle, UdroidForest, "Available")
            CapabilityStatus.FAIL ->
                Triple(
                    Icons.Outlined.ErrorOutline,
                    if (capability.required) MaterialTheme.colorScheme.error else UdroidWarning,
                    if (capability.required) "Required" else "Unavailable",
                )
            CapabilityStatus.INFO ->
                Triple(Icons.Outlined.Info, Color(0xFF35658A), "Detected")
        }
    Surface(
        color = UdroidRaised,
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, UdroidLine),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    capability.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    capability.detail,
                    color = UdroidMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                color = tint,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun AboutPage(
    journalLines: List<String>,
    updateState: AppUpdateState,
    onRefresh: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onCancelUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenUpdateRelease: () -> Unit,
) {
    val visibleJournalLines = journalLines.takeLast(25).asReversed()
    val uriHandler = LocalUriHandler.current
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            UdroidPageHeader(
                title = "About uDroid",
                subtitle = "App details, updates, and diagnostics",
                modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
            )
        }
        item {
            Surface(
                color = UdroidRaised,
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, UdroidLine),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        UdroidBrand()
                        Spacer(Modifier.weight(1f))
                        UdroidStatusBadge(
                            label = "v${BuildConfig.VERSION_NAME}",
                            color = UdroidForest,
                            background = UdroidSoftGreen,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "A Linux experience shaped for Android",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "The goal is a self-contained way to install and use Linux systems, " +
                            "while keeping the terminal close when it is useful.",
                        color = UdroidMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        item {
            UdroidSectionLabel(
                text = "Project",
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item {
            UdroidToolRow(
                icon = Icons.Outlined.Code,
                title = "GitHub repository",
                subtitle = "Source code, releases, and project history",
                onClick = { uriHandler.openUri(GITHUB_REPOSITORY_URL) },
            )
        }
        item {
            SupportProjectPanel(
                onStar = { uriHandler.openUri(GITHUB_REPOSITORY_URL) },
                onSponsor = { uriHandler.openUri(GITHUB_SPONSOR_URL) },
            )
        }
        item {
            UdroidToolRow(
                icon = Icons.Outlined.Feedback,
                title = "Request a feature or report an issue",
                subtitle = "Open a new issue on GitHub",
                onClick = { uriHandler.openUri(GITHUB_ISSUES_URL) },
            )
        }
        item {
            UdroidSectionLabel(
                text = "App updates",
                modifier =
                    Modifier
                        .padding(top = 8.dp),
            )
        }
        if (updateState.release == null) {
            item {
                AppUpdateStatusPanel(
                    state = updateState,
                    onCheckForUpdates = onCheckForUpdates,
                )
            }
        } else {
            item {
                AppUpdatePanel(
                    state = updateState,
                    onDownload = onDownloadUpdate,
                    onCancel = onCancelUpdate,
                    onInstall = onInstallUpdate,
                    onOpenRelease = onOpenUpdateRelease,
                )
            }
        }
        item {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    UdroidSectionLabel(text = "Supervisor journal")
                    Text(
                        if (journalLines.size > visibleJournalLines.size) {
                            "Latest ${visibleJournalLines.size} events"
                        } else {
                            "Lifecycle events and technical diagnostics"
                        },
                        color = UdroidMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "Refresh journal",
                        tint = UdroidMuted,
                    )
                }
            }
        }
        if (visibleJournalLines.isEmpty()) {
            item {
                Surface(
                    color = UdroidRaised,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, UdroidLine),
                ) {
                    Text(
                        "No lifecycle events yet.",
                        modifier = Modifier.padding(16.dp),
                        color = UdroidMuted,
                    )
                }
            }
        } else {
            items(visibleJournalLines) { line ->
                JournalRow(line)
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SupportProjectPanel(
    onStar: () -> Unit,
    onSponsor: () -> Unit,
) {
    Surface(
        color = UdroidRaised,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, UdroidLine),
    ) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    color = UdroidSoftGreen,
                    shape = RoundedCornerShape(9.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.FavoriteBorder,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = UdroidForest,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Support uDroid",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Star the repository to help others find it, or sponsor ongoing work.",
                        color = UdroidMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onStar) {
                    Text("Star on GitHub")
                }
                TextButton(onClick = onSponsor) {
                    Text("Sponsor")
                }
            }
        }
    }
}

@Composable
private fun AppUpdateStatusPanel(
    state: AppUpdateState,
    onCheckForUpdates: () -> Unit,
) {
    Surface(
        color = UdroidRaised,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, UdroidLine),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 15.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    color = UdroidInset,
                    shape = RoundedCornerShape(9.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.SystemUpdateAlt,
                            contentDescription = null,
                            tint = UdroidForest,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        updateStatusText(state),
                        color = UdroidMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(
                    onClick = onCheckForUpdates,
                    enabled = state.phase != AppUpdatePhase.CHECKING,
                ) {
                    Text(if (state.phase == AppUpdatePhase.CHECKING) "Checking" else "Check")
                }
            }
            if (state.phase == AppUpdatePhase.CHECKING) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = UdroidForest,
                )
            }
        }
    }
}

@Composable
private fun JournalRow(line: String) {
    val payload = runCatching { JSONObject(line) }.getOrNull()
    val event = payload?.optString("event").orEmpty().ifBlank { "event" }
    val message = payload?.optString("message").orEmpty().ifBlank { line }
    val timestamp = payload?.optString("timestamp").orEmpty()
    Surface(
        color = UdroidRaised,
        shape = RoundedCornerShape(11.dp),
        border = BorderStroke(1.dp, UdroidLine),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                modifier = Modifier.size(8.dp).padding(top = 2.dp),
                color = UdroidForest,
                shape = CircleShape,
            ) {}
            Spacer(Modifier.width(11.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        event,
                        modifier = Modifier.weight(1f),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        timestamp.substringAfter("T").take(8),
                        color = UdroidFaint,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    message,
                    color = UdroidMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun installedSystemTitle(rootfsName: String): String =
    when {
        rootfsName.contains("jammy", ignoreCase = true) -> "Ubuntu 22.04 LTS"
        rootfsName.contains("noble", ignoreCase = true) -> "Ubuntu 24.04 LTS"
        rootfsName.contains("resolute", ignoreCase = true) -> "Ubuntu Resolute"
        rootfsName.contains("focal", ignoreCase = true) -> "Ubuntu 20.04 LTS"
        else -> rootfsName
    }

private const val GITHUB_REPOSITORY_URL = "https://github.com/RandomCoderOrg/udroid-app"
private const val GITHUB_SPONSOR_URL = "https://github.com/sponsors/RandomCoderOrg"
private const val GITHUB_ISSUES_URL =
    "https://github.com/RandomCoderOrg/udroid-app/issues/new/choose"
