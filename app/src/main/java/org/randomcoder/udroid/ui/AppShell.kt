package org.randomcoder.udroid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Feedback
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import org.json.JSONObject
import org.randomcoder.udroid.BuildConfig
import org.randomcoder.udroid.catalog.DistroCatalogState
import org.randomcoder.udroid.catalog.DistroVariant
import org.randomcoder.udroid.catalog.LinuxDistribution
import org.randomcoder.udroid.audio.AudioConfiguration
import org.randomcoder.udroid.install.InstallProgress
import org.randomcoder.udroid.linuxapps.LinuxApplication
import org.randomcoder.udroid.linuxapps.LinuxApplicationsState
import org.randomcoder.udroid.oci.OciHubCatalogueState
import org.randomcoder.udroid.oci.OciHubRepository
import org.randomcoder.udroid.oci.OciHubTagPlatform
import org.randomcoder.udroid.oci.OciHubTagsState
import org.randomcoder.udroid.runtime.CapabilityResult
import org.randomcoder.udroid.runtime.CapabilityStatus
import org.randomcoder.udroid.runtime.DesktopConfiguration
import org.randomcoder.udroid.runtime.DesktopGraphicsProfile
import org.randomcoder.udroid.runtime.DesktopEnvironment
import org.randomcoder.udroid.runtime.DesktopSessionPhase
import org.randomcoder.udroid.runtime.InstalledRootfs
import org.randomcoder.udroid.runtime.ProotMountProfile
import org.randomcoder.udroid.runtime.ProotMountProfileStore
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
    HOME("Home", Icons.Rounded.Home, Icons.Rounded.Home),
    DISTROS("Linux", Icons.Rounded.Storage, Icons.Rounded.Storage),
    INSTALL("Install", Icons.Rounded.Storage, Icons.Rounded.Storage),
    SYSTEM("System", Icons.Rounded.Storage, Icons.Rounded.Storage),
    MOUNTS("Mounts", Icons.Rounded.Tune, Icons.Rounded.Tune),
    MOUNT_EDITOR("Mounts", Icons.Rounded.Tune, Icons.Rounded.Tune),
    TERMINAL("Terminal", Icons.Rounded.Terminal, Icons.Rounded.Terminal),
    APPS("Apps", Icons.Rounded.Apps, Icons.Rounded.Apps),
    DESKTOP("Desktop", Icons.Rounded.DesktopWindows, Icons.Rounded.DesktopWindows),
    DEVICE("Device", Icons.Rounded.Memory, Icons.Rounded.Memory),
    ABOUT("About", Icons.Rounded.Info, Icons.Rounded.Info),
}

internal enum class NavigationMotion {
    FORWARD,
    BACK,
    FADE,
}

internal fun navigationMotion(
    initial: UdroidDestination,
    target: UdroidDestination,
): NavigationMotion {
    val initialDepth = initial.navigationDepth
    val targetDepth = target.navigationDepth
    return when {
        targetDepth > initialDepth -> NavigationMotion.FORWARD
        targetDepth < initialDepth -> NavigationMotion.BACK
        else -> NavigationMotion.FADE
    }
}

private val UdroidDestination.navigationDepth: Int
    get() =
        when (this) {
            UdroidDestination.INSTALL,
            UdroidDestination.SYSTEM,
            -> 1
            UdroidDestination.MOUNTS -> 2
            UdroidDestination.MOUNT_EDITOR -> 3
            else -> 0
        }

@Composable
fun UdroidApp(
    destination: UdroidDestination,
    snapshot: RuntimeSnapshot,
    capabilities: List<CapabilityResult>,
    journalLines: List<String>,
    catalogueState: DistroCatalogState,
    ociCatalogueState: OciHubCatalogueState,
    selectedOciRepository: OciHubRepository?,
    ociTagsState: OciHubTagsState,
    installProgress: InstallProgress?,
    updateState: AppUpdateState,
    installedRootfsName: String?,
    installedRootfses: List<InstalledRootfs>,
    resettableRootfsNames: Set<String>,
    selectedSystemRootfsName: String?,
    rootfsMaintenanceName: String?,
    rootfsMaintenanceMessage: String?,
    desktopEnvironments: List<DesktopEnvironment>,
    desktopConfiguration: DesktopConfiguration,
    desktopScanLoading: Boolean,
    desktopScanMessage: String?,
    audioConfiguration: AudioConfiguration,
    audioConfigurationMessage: String?,
    linuxApplicationsState: LinuxApplicationsState,
    linuxApplicationMessage: String?,
    showInstallTerminal: Boolean,
    runtimeService: RuntimeSupervisorService?,
    onDestinationSelected: (UdroidDestination) -> Unit,
    onPrimaryDestinationSelected: (UdroidDestination) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onReloadCatalogue: () -> Unit,
    onPreviewInstall: (DistroVariant) -> Unit,
    onSelectOciRepository: (OciHubRepository) -> Unit,
    onRetryOciTags: () -> Unit,
    onBackFromOciRepository: () -> Unit,
    onSelectOciTag: (OciHubRepository, OciHubTagPlatform) -> Unit,
    onOpenInstalledSystem: (String) -> Unit,
    onOpenRootfsTerminal: (String) -> Unit,
    onOpenRootfsApps: (String) -> Unit,
    onResetRootfs: (String, DistroVariant?) -> Unit,
    onCreateRootfsVariation: (String, DistroVariant?, ProotMountProfile) -> Unit,
    onDeleteRootfs: (String) -> Unit,
    onSelectDesktopEnvironment: (String) -> Unit,
    onCompositingChanged: (Boolean) -> Unit,
    onTouchScaleChanged: (Boolean) -> Unit,
    onGraphicsProfileChanged: (DesktopGraphicsProfile) -> Unit,
    onAudioOutputChanged: (Boolean) -> Unit,
    onMicrophoneChanged: (Boolean) -> Unit,
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
    var mountConfigurationSourceSystemId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedMountProfileSystemId by rememberSaveable { mutableStateOf<String?>(null) }
    val hasInstalledLinux = installedRootfsName != null
    val requestedJourney =
        workspaceJourney(
            requestedDestination = destination,
            hasInstalledLinux = hasInstalledLinux,
            hasInstallation = installProgress != null,
            compactNavigation = false,
        )
    val activeDestination = requestedJourney.destination
    val navigationDestination =
        when (activeDestination) {
            UdroidDestination.INSTALL,
            UdroidDestination.SYSTEM,
            -> UdroidDestination.DISTROS
            UdroidDestination.MOUNTS,
            UdroidDestination.MOUNT_EDITOR,
            -> UdroidDestination.DISTROS
            else -> activeDestination
        }

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
                    installedRootfses = installedRootfses,
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
        color = MaterialTheme.colorScheme.background,
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
                        selected = navigationDestination,
                        destinations = requestedJourney.destinations,
                        onSelected = onPrimaryDestinationSelected,
                    )
                    HorizontalDivider(
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
                        ociCatalogueState = ociCatalogueState,
                        selectedOciRepository = selectedOciRepository,
                        ociTagsState = ociTagsState,
                        installProgress = installProgress,
                        updateState = updateState,
                        installedRootfsName = installedRootfsName,
                        installedRootfses = installedRootfses,
                        resettableRootfsNames = resettableRootfsNames,
                        selectedSystemRootfsName = selectedSystemRootfsName,
                        rootfsMaintenanceName = rootfsMaintenanceName,
                        rootfsMaintenanceMessage = rootfsMaintenanceMessage,
                        desktopEnvironments = desktopEnvironments,
                        desktopConfiguration = desktopConfiguration,
                        desktopScanLoading = desktopScanLoading,
                        desktopScanMessage = desktopScanMessage,
                        audioConfiguration = audioConfiguration,
                        audioConfigurationMessage = audioConfigurationMessage,
                        linuxApplicationsState = linuxApplicationsState,
                        linuxApplicationMessage = linuxApplicationMessage,
                        showInstallTerminal = showInstallTerminal,
                        mountConfigurationSourceSystemId = mountConfigurationSourceSystemId,
                        selectedMountProfileSystemId = selectedMountProfileSystemId,
                        onDestinationSelected = onDestinationSelected,
                        onPrimaryDestinationSelected = onPrimaryDestinationSelected,
                        onStart = onStart,
                        onStop = onStop,
                        onRefresh = onRefresh,
                        onReloadCatalogue = onReloadCatalogue,
                        onPreviewInstall = onPreviewInstall,
                        onSelectOciRepository = onSelectOciRepository,
                        onRetryOciTags = onRetryOciTags,
                        onBackFromOciRepository = onBackFromOciRepository,
                        onSelectOciTag = onSelectOciTag,
                        onOpenInstalledSystem = onOpenInstalledSystem,
                        onOpenRootfsTerminal = onOpenRootfsTerminal,
                        onOpenRootfsApps = onOpenRootfsApps,
                        onSelectMountProfile = { systemId ->
                            mountConfigurationSourceSystemId = systemId
                            selectedMountProfileSystemId = null
                            onDestinationSelected(UdroidDestination.MOUNTS)
                        },
                        onCreateMountProfile = {
                            selectedMountProfileSystemId = null
                            onDestinationSelected(UdroidDestination.MOUNT_EDITOR)
                        },
                        onEditMountProfile = { systemId ->
                            selectedMountProfileSystemId = systemId
                            onDestinationSelected(UdroidDestination.MOUNT_EDITOR)
                        },
                        onResetRootfs = onResetRootfs,
                        onCreateRootfsVariation = onCreateRootfsVariation,
                        onDeleteRootfs = onDeleteRootfs,
                        onSelectDesktopEnvironment = onSelectDesktopEnvironment,
                        onCompositingChanged = onCompositingChanged,
                        onTouchScaleChanged = onTouchScaleChanged,
                        onGraphicsProfileChanged = onGraphicsProfileChanged,
                        onAudioOutputChanged = onAudioOutputChanged,
                        onMicrophoneChanged = onMicrophoneChanged,
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
                        ociCatalogueState = ociCatalogueState,
                        selectedOciRepository = selectedOciRepository,
                        ociTagsState = ociTagsState,
                        installProgress = installProgress,
                        updateState = updateState,
                        installedRootfsName = installedRootfsName,
                        installedRootfses = installedRootfses,
                        resettableRootfsNames = resettableRootfsNames,
                        selectedSystemRootfsName = selectedSystemRootfsName,
                        rootfsMaintenanceName = rootfsMaintenanceName,
                        rootfsMaintenanceMessage = rootfsMaintenanceMessage,
                        desktopEnvironments = desktopEnvironments,
                        desktopConfiguration = desktopConfiguration,
                        desktopScanLoading = desktopScanLoading,
                        desktopScanMessage = desktopScanMessage,
                        audioConfiguration = audioConfiguration,
                        audioConfigurationMessage = audioConfigurationMessage,
                        linuxApplicationsState = linuxApplicationsState,
                        linuxApplicationMessage = linuxApplicationMessage,
                        showInstallTerminal = showInstallTerminal,
                        mountConfigurationSourceSystemId = mountConfigurationSourceSystemId,
                        selectedMountProfileSystemId = selectedMountProfileSystemId,
                        onDestinationSelected = onDestinationSelected,
                        onPrimaryDestinationSelected = onPrimaryDestinationSelected,
                        onStart = onStart,
                        onStop = onStop,
                        onRefresh = onRefresh,
                        onReloadCatalogue = onReloadCatalogue,
                        onPreviewInstall = onPreviewInstall,
                        onSelectOciRepository = onSelectOciRepository,
                        onRetryOciTags = onRetryOciTags,
                        onBackFromOciRepository = onBackFromOciRepository,
                        onSelectOciTag = onSelectOciTag,
                        onOpenInstalledSystem = onOpenInstalledSystem,
                        onOpenRootfsTerminal = onOpenRootfsTerminal,
                        onOpenRootfsApps = onOpenRootfsApps,
                        onSelectMountProfile = { systemId ->
                            mountConfigurationSourceSystemId = systemId
                            selectedMountProfileSystemId = null
                            onDestinationSelected(UdroidDestination.MOUNTS)
                        },
                        onCreateMountProfile = {
                            selectedMountProfileSystemId = null
                            onDestinationSelected(UdroidDestination.MOUNT_EDITOR)
                        },
                        onEditMountProfile = { systemId ->
                            selectedMountProfileSystemId = systemId
                            onDestinationSelected(UdroidDestination.MOUNT_EDITOR)
                        },
                        onResetRootfs = onResetRootfs,
                        onCreateRootfsVariation = onCreateRootfsVariation,
                        onDeleteRootfs = onDeleteRootfs,
                        onSelectDesktopEnvironment = onSelectDesktopEnvironment,
                        onCompositingChanged = onCompositingChanged,
                        onTouchScaleChanged = onTouchScaleChanged,
                        onGraphicsProfileChanged = onGraphicsProfileChanged,
                        onAudioOutputChanged = onAudioOutputChanged,
                        onMicrophoneChanged = onMicrophoneChanged,
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
                        selected = navigationDestination,
                        destinations =
                            workspaceJourney(
                                requestedDestination = activeDestination,
                                hasInstalledLinux = hasInstalledLinux,
                                hasInstallation = installProgress != null,
                                compactNavigation = true,
                            ).destinations,
                        onSelected = onPrimaryDestinationSelected,
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
    ociCatalogueState: OciHubCatalogueState,
    selectedOciRepository: OciHubRepository?,
    ociTagsState: OciHubTagsState,
    installProgress: InstallProgress?,
    updateState: AppUpdateState,
    installedRootfsName: String?,
    installedRootfses: List<InstalledRootfs>,
    resettableRootfsNames: Set<String>,
    selectedSystemRootfsName: String?,
    rootfsMaintenanceName: String?,
    rootfsMaintenanceMessage: String?,
    desktopEnvironments: List<DesktopEnvironment>,
    desktopConfiguration: DesktopConfiguration,
    desktopScanLoading: Boolean,
    desktopScanMessage: String?,
    audioConfiguration: AudioConfiguration,
    audioConfigurationMessage: String?,
    linuxApplicationsState: LinuxApplicationsState,
    linuxApplicationMessage: String?,
    showInstallTerminal: Boolean,
    mountConfigurationSourceSystemId: String?,
    selectedMountProfileSystemId: String?,
    onDestinationSelected: (UdroidDestination) -> Unit,
    onPrimaryDestinationSelected: (UdroidDestination) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onReloadCatalogue: () -> Unit,
    onPreviewInstall: (DistroVariant) -> Unit,
    onSelectOciRepository: (OciHubRepository) -> Unit,
    onRetryOciTags: () -> Unit,
    onBackFromOciRepository: () -> Unit,
    onSelectOciTag: (OciHubRepository, OciHubTagPlatform) -> Unit,
    onOpenInstalledSystem: (String) -> Unit,
    onOpenRootfsTerminal: (String) -> Unit,
    onOpenRootfsApps: (String) -> Unit,
    onSelectMountProfile: (String) -> Unit,
    onCreateMountProfile: () -> Unit,
    onEditMountProfile: (String) -> Unit,
    onResetRootfs: (String, DistroVariant?) -> Unit,
    onCreateRootfsVariation: (String, DistroVariant?, ProotMountProfile) -> Unit,
    onDeleteRootfs: (String) -> Unit,
    onSelectDesktopEnvironment: (String) -> Unit,
    onCompositingChanged: (Boolean) -> Unit,
    onTouchScaleChanged: (Boolean) -> Unit,
    onGraphicsProfileChanged: (DesktopGraphicsProfile) -> Unit,
    onAudioOutputChanged: (Boolean) -> Unit,
    onMicrophoneChanged: (Boolean) -> Unit,
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
    val spatialMotion = UdroidMotion.defaultSpatial<IntOffset>()
    val effectsMotion = UdroidMotion.defaultEffects<Float>()
    val fastEffectsMotion = UdroidMotion.fastEffects<Float>()
    val slowEffectsMotion = UdroidMotion.slowEffects<Float>()

    Column(modifier = modifier.fillMaxHeight()) {
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .widthIn(max = 900.dp),
            ) {
                AnimatedContent(
                    targetState = destination,
                    transitionSpec = {
                        when (navigationMotion(initialState, targetState)) {
                            NavigationMotion.FORWARD ->
                                (
                                    slideInHorizontally(
                                        animationSpec = spatialMotion,
                                        initialOffsetX = { width -> width / 10 },
                                    ) +
                                        fadeIn(animationSpec = effectsMotion)
                                ).togetherWith(
                                    slideOutHorizontally(
                                        animationSpec = spatialMotion,
                                        targetOffsetX = { width -> -width / 24 },
                                    ) +
                                        fadeOut(animationSpec = fastEffectsMotion),
                                )
                            NavigationMotion.BACK ->
                                (
                                    slideInHorizontally(
                                        animationSpec = spatialMotion,
                                        initialOffsetX = { width -> -width / 10 },
                                    ) +
                                        fadeIn(animationSpec = effectsMotion)
                                ).togetherWith(
                                    slideOutHorizontally(
                                        animationSpec = spatialMotion,
                                        targetOffsetX = { width -> width / 24 },
                                    ) +
                                        fadeOut(animationSpec = fastEffectsMotion),
                                )
                            NavigationMotion.FADE ->
                                fadeIn(animationSpec = slowEffectsMotion).togetherWith(
                                    fadeOut(animationSpec = fastEffectsMotion),
                                )
                        }
                    },
                    label = "workspace-page",
                ) { animatedDestination ->
                    when (animatedDestination) {
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
                                onPrimaryDestinationSelected(UdroidDestination.DISTROS)
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
                    UdroidDestination.MOUNTS -> {
                        val sourceSystemId = mountConfigurationSourceSystemId
                        if (sourceSystemId == null) {
                            onDestinationSelected(UdroidDestination.DISTROS)
                        } else {
                            val sourceRootfs =
                                installedRootfses.firstOrNull { it.name == sourceSystemId }
                            val sourceDistro =
                                (catalogueState as? DistroCatalogState.Ready)
                                    ?.catalog
                                    ?.variants
                                    ?.firstOrNull { it.internalName == sourceSystemId }
                            if (sourceRootfs == null) {
                                onDestinationSelected(UdroidDestination.DISTROS)
                            } else {
                                ProotMountConfigurationsPage(
                                    sourceSystemId = sourceSystemId,
                                    sourceSystemTitle =
                                        sourceDistro?.releaseName
                                            ?: installedSystemTitle(sourceSystemId),
                                    distribution =
                                        sourceDistro?.distribution
                                            ?: distributionFromSystemId(sourceSystemId),
                                    installedRootfses = installedRootfses,
                                    activeRootfsName = installedRootfsName,
                                    installProgress = installProgress,
                                    onBack = {
                                        onDestinationSelected(UdroidDestination.SYSTEM)
                                    },
                                    onCreateConfiguration = {
                                        onCreateMountProfile()
                                    },
                                    onEditConfiguration = { configurationSystemId ->
                                        onEditMountProfile(configurationSystemId)
                                    },
                                    onLaunchDistro = onOpenInstalledSystem,
                                    onDeleteConfiguration = onDeleteRootfs,
                                )
                            }
                        }
                    }
                    UdroidDestination.MOUNT_EDITOR -> {
                        val sourceSystemId = mountConfigurationSourceSystemId
                        if (sourceSystemId == null) {
                            onDestinationSelected(UdroidDestination.DISTROS)
                        } else {
                            val configurationSystemId = selectedMountProfileSystemId
                            val targetSystemId = configurationSystemId ?: sourceSystemId
                            val sourceDistro =
                                (catalogueState as? DistroCatalogState.Ready)
                                    ?.catalog
                                    ?.variants
                                    ?.firstOrNull { it.internalName == sourceSystemId }
                            val runtimeBusy =
                                snapshot.rootfsName == targetSystemId &&
                                    snapshot.phase in
                                    setOf(
                                        RuntimePhase.STARTING,
                                        RuntimePhase.RUNNING,
                                        RuntimePhase.STOPPING,
                                    )
                            val desktopBusy =
                                snapshot.desktop.rootfsName == targetSystemId &&
                                    snapshot.desktop.phase in
                                    setOf(
                                        DesktopSessionPhase.STARTING,
                                        DesktopSessionPhase.RUNNING,
                                        DesktopSessionPhase.STOPPING,
                                    )
                            ProotMountConfigurationEditorPage(
                                sourceSystemId = sourceSystemId,
                                configurationSystemId = configurationSystemId,
                                systemTitle =
                                    if (configurationSystemId == null) {
                                        sourceDistro?.releaseName
                                            ?: installedSystemTitle(sourceSystemId)
                                    } else {
                                        installProgress
                                            ?.takeIf {
                                                it.installationName == configurationSystemId
                                            }?.displayName
                                            ?: installedSystemTitle(configurationSystemId)
                                    },
                                distribution =
                                    sourceDistro?.distribution
                                        ?: distributionFromSystemId(sourceSystemId),
                                active = configurationSystemId == installedRootfsName,
                                editingEnabled =
                                    if (configurationSystemId == null) {
                                        installProgress == null
                                    } else {
                                        !runtimeBusy &&
                                            !desktopBusy &&
                                            rootfsMaintenanceName != targetSystemId
                                    },
                                externalMessage = rootfsMaintenanceMessage,
                                onBack = {
                                    onDestinationSelected(UdroidDestination.MOUNTS)
                                },
                                onOpenSessionFeatures = {
                                    onDestinationSelected(UdroidDestination.SYSTEM)
                                },
                                onCreateDistro = { profile ->
                                    onCreateRootfsVariation(
                                        sourceSystemId,
                                        sourceDistro,
                                        profile,
                                    )
                                },
                            )
                        }
                    }
                    UdroidDestination.DISTROS ->
                        selectedOciRepository?.let { repository ->
                            OciTagCataloguePage(
                                repository = repository,
                                state = ociTagsState,
                                installedRootfses = installedRootfses,
                                onBack = onBackFromOciRepository,
                                onRetry = onRetryOciTags,
                                onSelectTag = { tag -> onSelectOciTag(repository, tag) },
                            )
                        } ?: DistroCataloguePage(
                            state = catalogueState,
                            ociState = ociCatalogueState,
                            installedRootfses = installedRootfses,
                            activeRootfsName = installedRootfsName,
                            onRetry = onReloadCatalogue,
                            onPreviewInstall = onPreviewInstall,
                            onSelectOciRepository = onSelectOciRepository,
                            onOpenInstalledSystem = onOpenInstalledSystem,
                        )
                    UdroidDestination.INSTALL ->
                        installProgress?.let {
                            InstallExperiencePage(
                                progress = it,
                                showTerminal = showInstallTerminal,
                                onToggleTerminal = onToggleInstallTerminal,
                                onBack = onCloseInstall,
                                onOpenTerminal = {
                                    onOpenRootfsTerminal(it.installationName)
                                },
                                onStartDownload = onStartDownload,
                                onPauseDownload = onPauseDownload,
                                onRetryDownload = onRetryDownload,
                            )
                        }
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
                            val context = LocalContext.current
                            val mountProfileStore = remember(context) {
                                ProotMountProfileStore(context)
                            }
                            val mountConfigurationSourceId =
                                remember(selectedRootfs.name) {
                                    runCatching {
                                        mountProfileStore.load(selectedRootfs.name).sourceSystemId
                                    }.getOrNull() ?: selectedRootfs.name
                                }
                            LinuxSystemPage(
                                rootfs = selectedRootfs,
                                distro = selectedDistro,
                                active = rootfsName == installedRootfsName,
                                snapshot = snapshot,
                                environments = desktopEnvironments,
                                configuration = desktopConfiguration,
                                scanLoading = desktopScanLoading,
                                scanMessage = desktopScanMessage,
                                audioConfiguration = audioConfiguration,
                                audioConfigurationMessage = audioConfigurationMessage,
                                resetAvailable =
                                    selectedRootfs.name in resettableRootfsNames ||
                                        selectedDistro != null,
                                maintenanceInProgress =
                                    rootfsMaintenanceName == selectedRootfs.name,
                                maintenanceMessage =
                                    rootfsMaintenanceMessage
                                        ?.takeIf {
                                            rootfsMaintenanceName == null ||
                                                rootfsMaintenanceName == selectedRootfs.name
                                        },
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
                                onGraphicsProfileChanged = onGraphicsProfileChanged,
                                onAudioOutputChanged = onAudioOutputChanged,
                                onMicrophoneChanged = onMicrophoneChanged,
                                onStartDesktop = onStartDesktop,
                                onStopTerminal = onStop,
                                onStopDesktop = onStopDesktop,
                                onRestartDesktop = onRestartDesktop,
                                onConfigureMounts = {
                                    onSelectMountProfile(mountConfigurationSourceId)
                                },
                                onResetFilesystem = {
                                    onResetRootfs(selectedRootfs.name, selectedDistro)
                                },
                                onDeleteFilesystem = {
                                    onDeleteRootfs(selectedRootfs.name)
                                },
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
}

@Composable
private fun WorkspaceNavigationBar(
    selected: UdroidDestination,
    destinations: List<UdroidDestination>,
    onSelected: (UdroidDestination) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
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
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
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
                .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            UdroidPageHeader(
                title = "Your Linux workspace",
                subtitle =
                    if (hasInstalledLinux) {
                        "Open Linux terminals, apps, and desktops"
                    } else {
                        "Install Linux to get started"
                    },
                modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
                trailing = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh workspace",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        }
        item {
            UdroidSectionLabel(
                text = if (hasInstalledLinux) "Workspace" else "Get started",
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
            )
        }
        item {
            UdroidToolRow(
                icon = Icons.Rounded.Storage,
                title = "Linux systems",
                subtitle =
                    distro?.releaseName
                        ?: rootfsName?.let(::installedSystemTitle)
                        ?: "Choose a Linux distribution",
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
                icon = Icons.Rounded.Terminal,
                title = "Terminal",
                subtitle =
                    if (hasInstalledLinux) {
                        "Open a shell in the installed Linux system"
                    } else {
                        "Install Linux to use the terminal"
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
                icon = Icons.Rounded.Apps,
                title = "Linux apps",
                subtitle =
                    if (hasInstalledLinux) {
                        "Find and launch installed applications"
                    } else {
                        "Install Linux to discover apps"
                    },
                onClick = onOpenApps,
            )
        }
        item {
            UdroidToolRow(
                icon = Icons.Rounded.DesktopWindows,
                title = "Desktop",
                subtitle =
                    if (hasInstalledLinux) {
                        "Open the graphical Linux desktop"
                    } else {
                        "Install Linux to use a desktop"
                    },
                onClick = onOpenDesktop,
            )
        }
        item {
            val passed = capabilities.count { it.status == CapabilityStatus.PASS }
            UdroidToolRow(
                icon = Icons.Rounded.Memory,
                title = "Device compatibility",
                subtitle = "Check which features work on this device",
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
                icon = Icons.Rounded.Info,
                title = "About uDroid",
                subtitle = "Updates, support, and app details",
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
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    color = UdroidSoftGreen,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.SystemUpdateAlt,
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
                    progress = { state.percentage / 100f },
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
                        ) {
                            Text("Pause")
                        }
                    AppUpdatePhase.READY ->
                        Button(
                            onClick = onInstall,
                        ) {
                            Text("Install update")
                        }
                    else ->
                        Button(
                            onClick = onDownload,
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
        AppUpdatePhase.IDLE -> "Version ${BuildConfig.VERSION_NAME} is installed"
        AppUpdatePhase.CHECKING -> "Checking for updates…"
        AppUpdatePhase.UP_TO_DATE -> "Version ${BuildConfig.VERSION_NAME} is current"
        AppUpdatePhase.AVAILABLE -> state.message ?: "A verified release is available"
        AppUpdatePhase.DOWNLOADING -> "Downloading update · ${state.percentage}%"
        AppUpdatePhase.READY -> "Verified and ready to install"
        AppUpdatePhase.FAILED -> state.message ?: "Couldn’t check for updates"
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
                subtitle = "Features available on this device",
                modifier = Modifier.padding(top = 18.dp, bottom = 8.dp),
                trailing = {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Rounded.Refresh,
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
                Triple(Icons.Rounded.CheckCircle, UdroidForest, "Available")
            CapabilityStatus.FAIL ->
                Triple(
                    Icons.Rounded.ErrorOutline,
                    if (capability.required) MaterialTheme.colorScheme.error else UdroidWarning,
                    if (capability.required) "Required" else "Unavailable",
                )
            CapabilityStatus.INFO ->
                Triple(Icons.Rounded.Info, MaterialTheme.colorScheme.tertiary, "Detected")
        }
    ListItem(
        modifier = Modifier.clip(MaterialTheme.shapes.medium),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
            )
        },
        headlineContent = {
            Text(capability.name, style = MaterialTheme.typography.titleMedium)
        },
        supportingContent = {
            Text(capability.detail, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = {
            Text(
                label,
                color = tint,
                style = MaterialTheme.typography.labelLarge,
            )
        },
    )
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
    val visibleJournalLines = newestSupervisorEvents(journalLines)
    val context = LocalContext.current
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
                subtitle = "Updates, support, and app details",
                modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
            )
        }
        item {
            Surface(
                color = UdroidRaised,
                shape = MaterialTheme.shapes.large,
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
                        "Run Linux on Android",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "Install and use Linux without leaving the app. " +
                            "Open the terminal whenever you need more control.",
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
                icon = Icons.Rounded.Code,
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
                icon = Icons.Rounded.Feedback,
                title = "Request a feature",
                subtitle = "Suggest an idea or report a problem on GitHub",
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
                    UdroidSectionLabel(text = "Diagnostic log")
                    Text(
                        if (journalLines.size > visibleJournalLines.size) {
                            "Latest ${visibleJournalLines.size} events"
                        } else {
                            "Recent app and Linux session events"
                        },
                        color = UdroidMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(
                    onClick = {
                        val report =
                            buildSupervisorReport(
                                appVersion = BuildConfig.VERSION_NAME,
                                androidVersion =
                                    "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                                capturedAt = Instant.now().toString(),
                                newestFirstJournalLines = journalLines,
                            )
                        context
                            .getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("uDroid diagnostics", report))
                        Toast
                            .makeText(context, "Report copied", Toast.LENGTH_SHORT)
                            .show()
                    },
                ) {
                    Icon(
                        Icons.Rounded.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("Copy diagnostic report")
                }
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = "Refresh diagnostic log",
                        tint = UdroidMuted,
                    )
                }
            }
        }
        if (visibleJournalLines.isEmpty()) {
            item {
                Surface(
                    color = UdroidRaised,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        "No diagnostic events",
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
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    color = UdroidSoftGreen,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.FavoriteBorder,
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
                        "Star the repository to help others find it, or sponsor its development",
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
        shape = MaterialTheme.shapes.large,
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 15.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    color = UdroidInset,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.SystemUpdateAlt,
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
                    Text(if (state.phase == AppUpdatePhase.CHECKING) "Checking" else "Check for updates")
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
        shape = MaterialTheme.shapes.medium,
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

private fun distributionFromSystemId(systemId: String): LinuxDistribution {
    val normalized = systemId.lowercase()
    return when {
        "debian" in normalized -> LinuxDistribution.DEBIAN
        "arch" in normalized -> LinuxDistribution.ARCH
        "alpine" in normalized -> LinuxDistribution.ALPINE
        "void" in normalized -> LinuxDistribution.VOID
        else -> LinuxDistribution.UBUNTU
    }
}

private const val GITHUB_REPOSITORY_URL = "https://github.com/RandomCoderOrg/udroid-app"
private const val GITHUB_SPONSOR_URL = "https://github.com/sponsors/RandomCoderOrg"
private const val GITHUB_ISSUES_URL =
    "https://github.com/RandomCoderOrg/udroid-app/issues/new/choose"
