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
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.randomcoder.udroid.catalog.DistroCatalogState
import org.randomcoder.udroid.catalog.DistroVariant
import org.randomcoder.udroid.install.InstallProgress
import org.randomcoder.udroid.linuxapps.LinuxApplication
import org.randomcoder.udroid.linuxapps.LinuxApplicationsState
import org.randomcoder.udroid.runtime.CapabilityResult
import org.randomcoder.udroid.runtime.CapabilityStatus
import org.randomcoder.udroid.runtime.RuntimePhase
import org.randomcoder.udroid.runtime.RuntimeSnapshot
import org.randomcoder.udroid.runtime.RuntimeSupervisorService
import java.time.Instant

enum class UdroidDestination(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    HOME("Home", Icons.Outlined.Home, Icons.Filled.Home),
    DISTROS("Linux", Icons.Outlined.Storage, Icons.Filled.Storage),
    TERMINAL("Terminal", Icons.Outlined.Terminal, Icons.Filled.Terminal),
    APPS("Apps", Icons.Outlined.Apps, Icons.Filled.Apps),
    DESKTOP("Desktop", Icons.Outlined.DesktopWindows, Icons.Filled.DesktopWindows),
    DEVICE("Device", Icons.Outlined.Memory, Icons.Filled.Memory),
    LOGS("Logs", Icons.AutoMirrored.Outlined.Article, Icons.AutoMirrored.Filled.Article),
}

@Composable
fun UdroidApp(
    destination: UdroidDestination,
    snapshot: RuntimeSnapshot,
    capabilities: List<CapabilityResult>,
    journalLines: List<String>,
    catalogueState: DistroCatalogState,
    installProgress: InstallProgress?,
    installedRootfsName: String?,
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
    onStartDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onToggleInstallTerminal: () -> Unit,
    onCloseInstall: () -> Unit,
    onRetryDownload: () -> Unit,
    onRefreshLinuxApplications: () -> Unit,
    onLaunchLinuxApplication: (LinuxApplication) -> Unit,
) {
    if (destination == UdroidDestination.DESKTOP) {
        DesktopPage(
            snapshot = snapshot,
            service = runtimeService,
            onExit = { onDestinationSelected(UdroidDestination.APPS) },
        )
        return
    }

    if (destination == UdroidDestination.TERMINAL) {
        UdroidTerminalTheme {
            BackHandler {
                onDestinationSelected(UdroidDestination.HOME)
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
                    onExit = { onDestinationSelected(UdroidDestination.HOME) },
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
                        selected = destination,
                        onSelected = onDestinationSelected,
                    )
                    Divider(
                        modifier = Modifier.fillMaxHeight().width(1.dp),
                        color = UdroidLine,
                    )
                    ManagementPane(
                        modifier = Modifier.weight(1f),
                        destination = destination,
                        snapshot = snapshot,
                        capabilities = capabilities,
                        journalLines = journalLines,
                        catalogueState = catalogueState,
                        installProgress = installProgress,
                        installedRootfsName = installedRootfsName,
                        linuxApplicationsState = linuxApplicationsState,
                        linuxApplicationMessage = linuxApplicationMessage,
                        showInstallTerminal = showInstallTerminal,
                        onDestinationSelected = onDestinationSelected,
                        onStart = onStart,
                        onStop = onStop,
                        onRefresh = onRefresh,
                        onReloadCatalogue = onReloadCatalogue,
                        onPreviewInstall = onPreviewInstall,
                        onStartDownload = onStartDownload,
                        onPauseDownload = onPauseDownload,
                        onToggleInstallTerminal = onToggleInstallTerminal,
                        onCloseInstall = onCloseInstall,
                        onRetryDownload = onRetryDownload,
                        onRefreshLinuxApplications = onRefreshLinuxApplications,
                        onLaunchLinuxApplication = onLaunchLinuxApplication,
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    ManagementPane(
                        modifier = Modifier.weight(1f),
                        destination = destination,
                        snapshot = snapshot,
                        capabilities = capabilities,
                        journalLines = journalLines,
                        catalogueState = catalogueState,
                        installProgress = installProgress,
                        installedRootfsName = installedRootfsName,
                        linuxApplicationsState = linuxApplicationsState,
                        linuxApplicationMessage = linuxApplicationMessage,
                        showInstallTerminal = showInstallTerminal,
                        onDestinationSelected = onDestinationSelected,
                        onStart = onStart,
                        onStop = onStop,
                        onRefresh = onRefresh,
                        onReloadCatalogue = onReloadCatalogue,
                        onPreviewInstall = onPreviewInstall,
                        onStartDownload = onStartDownload,
                        onPauseDownload = onPauseDownload,
                        onToggleInstallTerminal = onToggleInstallTerminal,
                        onCloseInstall = onCloseInstall,
                        onRetryDownload = onRetryDownload,
                        onRefreshLinuxApplications = onRefreshLinuxApplications,
                        onLaunchLinuxApplication = onLaunchLinuxApplication,
                    )
                    WorkspaceNavigationBar(
                        selected = destination,
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
    installedRootfsName: String?,
    linuxApplicationsState: LinuxApplicationsState,
    linuxApplicationMessage: String?,
    showInstallTerminal: Boolean,
    onDestinationSelected: (UdroidDestination) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onReloadCatalogue: () -> Unit,
    onPreviewInstall: (DistroVariant) -> Unit,
    onStartDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onToggleInstallTerminal: () -> Unit,
    onCloseInstall: () -> Unit,
    onRetryDownload: () -> Unit,
    onRefreshLinuxApplications: () -> Unit,
    onLaunchLinuxApplication: (LinuxApplication) -> Unit,
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
                            capabilities = capabilities,
                            onStart = onStart,
                            onStop = onStop,
                            onRefresh = onRefresh,
                            onOpenTerminal = {
                                onDestinationSelected(UdroidDestination.TERMINAL)
                            },
                            onOpenLinux = {
                                onDestinationSelected(UdroidDestination.DISTROS)
                            },
                            onOpenDevice = {
                                onDestinationSelected(UdroidDestination.DEVICE)
                            },
                            onOpenLogs = {
                                onDestinationSelected(UdroidDestination.LOGS)
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
                                onStartDownload = onStartDownload,
                                onPauseDownload = onPauseDownload,
                                onRetryDownload = onRetryDownload,
                            )
                        } ?: DistroCataloguePage(
                            state = catalogueState,
                            onRetry = onReloadCatalogue,
                            onPreviewInstall = onPreviewInstall,
                        )
                    UdroidDestination.DEVICE ->
                        DevicePage(
                            capabilities = capabilities,
                            onRefresh = onRefresh,
                        )
                    UdroidDestination.LOGS ->
                        LogsPage(
                            journalLines = journalLines,
                            onRefresh = onRefresh,
                        )
                    UdroidDestination.APPS ->
                        LinuxAppsPage(
                            state = linuxApplicationsState,
                            launchMessage = linuxApplicationMessage,
                            onRefresh = onRefreshLinuxApplications,
                            onLaunch = onLaunchLinuxApplication,
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
            Spacer(Modifier.width(4.dp))
        }
    }
    Divider(color = UdroidLine)
}

@Composable
private fun WorkspaceNavigationBar(
    selected: UdroidDestination,
    onSelected: (UdroidDestination) -> Unit,
) {
    NavigationBar(
        containerColor = UdroidSurface,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        UdroidDestination.entries
            .filterNot {
                it == UdroidDestination.DEVICE ||
                    it == UdroidDestination.DESKTOP
            }
            .forEach { destination ->
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
    onSelected: (UdroidDestination) -> Unit,
) {
    NavigationRail(
        containerColor = UdroidSurface,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Spacer(Modifier.height(12.dp))
        UdroidDestination.entries.forEach { destination ->
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
    capabilities: List<CapabilityResult>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenLinux: () -> Unit,
    onOpenDevice: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            UdroidPageHeader(
                title = "Workspace",
                subtitle = "Linux systems on this device",
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
            InstalledSystemPanel(
                snapshot = snapshot,
                distro = distro,
                rootfsName = rootfsName,
                onStart = onStart,
                onStop = onStop,
                onOpenTerminal = onOpenTerminal,
            )
        }
        item {
            UdroidSectionLabel(
                text = "Tools",
                modifier = Modifier.padding(top = 10.dp, bottom = 1.dp),
            )
        }
        item {
            UdroidToolRow(
                icon = Icons.Outlined.Storage,
                title = "Linux images",
                subtitle =
                    distro?.releaseName
                        ?: rootfsName?.let(::installedSystemTitle)
                        ?: "Install or inspect a distribution",
                trailingText = if (rootfsName == null) null else "Installed",
                onClick = onOpenLinux,
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
                icon = Icons.AutoMirrored.Outlined.Article,
                title = "Supervisor journal",
                subtitle = "Lifecycle events and technical diagnostics",
                onClick = onOpenLogs,
            )
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun InstalledSystemPanel(
    snapshot: RuntimeSnapshot,
    distro: DistroVariant?,
    rootfsName: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val running = snapshot.phase == RuntimePhase.RUNNING
    val inTransition =
        snapshot.phase == RuntimePhase.STARTING || snapshot.phase == RuntimePhase.STOPPING
    val (badgeColor, badgeBackground) =
        when (snapshot.phase) {
            RuntimePhase.RUNNING -> UdroidForest to UdroidSoftGreen
            RuntimePhase.CRASHED -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.errorContainer
            RuntimePhase.STARTING, RuntimePhase.STOPPING ->
                UdroidWarning to UdroidWarningSurface
            RuntimePhase.STOPPED -> UdroidMuted to UdroidInset
        }
    Surface(
        color = UdroidRaised,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, UdroidLine),
    ) {
        Column {
            Row(
                modifier = Modifier.padding(15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                UbuntuMark()
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        distro?.releaseName
                            ?: rootfsName?.let(::installedSystemTitle)
                            ?: sessionTitle(snapshot),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(Modifier.height(2.dp))
                    UdroidMetadataRow(
                        items =
                            listOfNotNull(
                                distro?.suite?.replaceFirstChar { it.titlecase() },
                                rootfsName
                                    ?.substringAfter("udroid-", "")
                                    ?.substringBefore('-')
                                    ?.replaceFirstChar { it.titlecase() }
                                    ?.takeIf { distro == null && it.isNotBlank() },
                                distro?.architecture ?: "aarch64",
                                snapshot.childPid?.let { "PID $it" },
                            ),
                    )
                }
                UdroidStatusBadge(
                    label = snapshot.phase.name.lowercase().replaceFirstChar { it.titlecase() },
                    color = badgeColor,
                    background = badgeBackground,
                )
            }
            Divider(color = UdroidLine)
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 15.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Button(
                    onClick = if (running) onOpenTerminal else onStart,
                    modifier = Modifier.weight(1f),
                    enabled = !inTransition,
                    shape = RoundedCornerShape(9.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Terminal,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (running) "Open terminal" else "Start terminal")
                }
                if (running || inTransition) {
                    OutlinedButton(
                        onClick = onStop,
                        enabled = snapshot.phase != RuntimePhase.STOPPING,
                        shape = RoundedCornerShape(9.dp),
                        colors =
                            ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                    ) {
                        Text("Stop")
                    }
                }
            }
        }
    }
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
private fun LogsPage(
    journalLines: List<String>,
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
                title = "Journal",
                subtitle = "Supervisor lifecycle and diagnostics",
                modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
                trailing = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "Refresh journal",
                            tint = UdroidMuted,
                        )
                    }
                },
            )
        }
        if (journalLines.isEmpty()) {
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
            items(journalLines) { line ->
                JournalRow(line)
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
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

private fun sessionTitle(snapshot: RuntimeSnapshot): String =
    if (snapshot.message.contains("jammy", ignoreCase = true)) {
        "Ubuntu 22.04 LTS"
    } else {
        "Installed Linux"
    }

private fun installedSystemTitle(rootfsName: String): String =
    when {
        rootfsName.contains("jammy", ignoreCase = true) -> "Ubuntu 22.04 LTS"
        rootfsName.contains("noble", ignoreCase = true) -> "Ubuntu 24.04 LTS"
        rootfsName.contains("resolute", ignoreCase = true) -> "Ubuntu Resolute"
        rootfsName.contains("focal", ignoreCase = true) -> "Ubuntu 20.04 LTS"
        else -> rootfsName
    }
