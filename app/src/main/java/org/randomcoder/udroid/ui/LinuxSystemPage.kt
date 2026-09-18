package org.randomcoder.udroid.ui

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.randomcoder.udroid.audio.AudioConfiguration
import org.randomcoder.udroid.catalog.DistroVariant
import org.randomcoder.udroid.catalog.LinuxDistribution
import org.randomcoder.udroid.runtime.DesktopCompositorSupport
import org.randomcoder.udroid.runtime.DesktopConfiguration
import org.randomcoder.udroid.runtime.DesktopEnvironment
import org.randomcoder.udroid.runtime.DesktopGraphicsProfile
import org.randomcoder.udroid.runtime.DesktopSessionPhase
import org.randomcoder.udroid.runtime.GFXSTREAM_PROFILE_ENABLED
import org.randomcoder.udroid.runtime.InstalledRootfs
import org.randomcoder.udroid.runtime.PROOT_DEFAULT_MOUNTS
import org.randomcoder.udroid.runtime.ProotMountProfile
import org.randomcoder.udroid.runtime.ProotMountProfileStore
import org.randomcoder.udroid.runtime.RuntimePhase
import org.randomcoder.udroid.runtime.RuntimeSnapshot
import java.text.DateFormat
import java.util.Date

@Composable
fun LinuxSystemPage(
    rootfs: InstalledRootfs,
    distro: DistroVariant?,
    active: Boolean,
    snapshot: RuntimeSnapshot,
    environments: List<DesktopEnvironment>,
    configuration: DesktopConfiguration,
    scanLoading: Boolean,
    scanMessage: String?,
    audioConfiguration: AudioConfiguration,
    audioConfigurationMessage: String?,
    resetAvailable: Boolean,
    maintenanceInProgress: Boolean,
    maintenanceMessage: String?,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenDisplay: () -> Unit,
    onSelectEnvironment: (String) -> Unit,
    onCompositingChanged: (Boolean) -> Unit,
    onTouchScaleChanged: (Boolean) -> Unit,
    onGraphicsProfileChanged: (DesktopGraphicsProfile) -> Unit,
    onAudioOutputChanged: (Boolean) -> Unit,
    onMicrophoneChanged: (Boolean) -> Unit,
    onStartDesktop: () -> Unit,
    onStopTerminal: () -> Unit,
    onStopDesktop: () -> Unit,
    onRestartDesktop: () -> Unit,
    onConfigureMounts: () -> Unit,
    onResetFilesystem: () -> Unit,
    onDeleteFilesystem: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = androidx.compose.ui.platform.LocalContext.current
    val mountProfileStore = remember(context) { ProotMountProfileStore(context) }
    var confirmation by remember(rootfs.name) {
        mutableStateOf<FilesystemConfirmation?>(null)
    }
    val mountProfile = remember(rootfs.name) {
        runCatching { mountProfileStore.load(rootfs.name) }
            .getOrDefault(ProotMountProfile())
    }
    val selectedEnvironment =
        environments.firstOrNull { it.id == configuration.environmentId }
            ?: environments.firstOrNull()
    val desktop = snapshot.desktop
    val runtimeOwnsSystem =
        snapshot.rootfsName == rootfs.name &&
            snapshot.phase != RuntimePhase.STOPPED
    val desktopOwnsSystem =
        desktop.rootfsName == rootfs.name &&
            desktop.phase.isDisplayClaimed()
    val desktopBusy =
        desktop.phase == DesktopSessionPhase.STARTING ||
            desktop.phase == DesktopSessionPhase.STOPPING
    val desktopRunning = desktopOwnsSystem && desktop.phase == DesktopSessionPhase.RUNNING
    val runtimeBlocksMaintenance =
        snapshot.rootfsName == rootfs.name &&
            snapshot.phase in
            setOf(
                RuntimePhase.STARTING,
                RuntimePhase.RUNNING,
                RuntimePhase.STOPPING,
            )
    val runtimeStopping =
        snapshot.rootfsName == rootfs.name && snapshot.phase == RuntimePhase.STOPPING
    val desktopBlocksMaintenance =
        desktop.rootfsName == rootfs.name &&
            desktop.phase in
            setOf(
                DesktopSessionPhase.STARTING,
                DesktopSessionPhase.RUNNING,
                DesktopSessionPhase.STOPPING,
            )
    val maintenanceEnabled =
        !maintenanceInProgress &&
            !runtimeBlocksMaintenance &&
            !desktopBlocksMaintenance

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "system-header") {
            Row(
                modifier = Modifier.padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to Linux systems",
                    )
                }
                DistroMark(
                    distribution = distro?.distribution ?: distributionFrom(rootfs.name),
                    size = 46,
                )
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                ) {
                    Text(
                        distro?.releaseName ?: systemTitle(rootfs.name),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        distro?.let { "${it.experienceName} · ${it.architecture}" }
                            ?: rootfs.name,
                        color = UdroidMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                UdroidStatusBadge(
                    label = if (active) "Active" else "Installed",
                    color = UdroidForest,
                    background = UdroidSoftGreen,
                )
            }
        }

        item(key = "state") {
            OperationalStatePanel(
                snapshot = snapshot,
                runtimeOwnsSystem = runtimeOwnsSystem,
                desktopOwnsSystem = desktopOwnsSystem,
            )
        }

        item(key = "actions-label") {
            UdroidSectionLabel("Open")
        }
        item(key = "actions") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SystemAction(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Terminal,
                    label = "Terminal",
                    onClick = onOpenTerminal,
                )
                SystemAction(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Apps,
                    label = "Apps",
                    onClick = onOpenApps,
                )
                SystemAction(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.DesktopWindows,
                    label = "Display",
                    enabled = desktopRunning,
                    onClick = onOpenDisplay,
                )
            }
        }

        item(key = "audio-label") {
            UdroidSectionLabel(
                text = "Audio",
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        item(key = "audio-settings") {
            AudioSettingsPanel(
                configuration = audioConfiguration,
                runtimeRunning = runtimeOwnsSystem && snapshot.phase == RuntimePhase.RUNNING,
                message = audioConfigurationMessage,
                onOutputChanged = onAudioOutputChanged,
                onMicrophoneChanged = onMicrophoneChanged,
            )
        }

        item(key = "desktop-label") {
            UdroidSectionLabel(
                text = "Desktop session",
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        item(key = "display-owner") {
            DisplayOwnerRow(snapshot = snapshot)
        }

        when {
            scanLoading -> {
                item(key = "desktop-loading") {
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(
                            "Looking for installed desktops…",
                            modifier = Modifier.padding(start = 12.dp),
                            color = UdroidMuted,
                        )
                    }
                }
            }
            environments.isEmpty() -> {
                item(key = "desktop-empty") {
                    Surface(
                        color = UdroidRaised,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "No desktop found",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                scanMessage
                                    ?: "Install XFCE, Plasma, or MATE, then scan again",
                                color = UdroidMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            else -> {
                items(
                    items = environments,
                    key = DesktopEnvironment::id,
                ) { environment ->
                    DesktopEnvironmentRow(
                        environment = environment,
                        selected = environment.id == selectedEnvironment?.id,
                        running = desktopOwnsSystem && environment.id == desktop.environmentId,
                        onSelect = { onSelectEnvironment(environment.id) },
                    )
                }
            }
        }

        if (selectedEnvironment != null) {
            item(key = "desktop-settings") {
                DesktopSettingsPanel(
                    environment = selectedEnvironment,
                    configuration = configuration,
                    desktopRunning = desktopRunning,
                    onCompositingChanged = onCompositingChanged,
                    onTouchScaleChanged = onTouchScaleChanged,
                    onGraphicsProfileChanged = onGraphicsProfileChanged,
                )
            }
            item(key = "desktop-controls") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when {
                        desktopRunning -> {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                onClick = onStopDesktop,
                                enabled = !desktopBusy,
                            ) {
                                Icon(Icons.Rounded.Stop, contentDescription = null)
                                Text("Stop", modifier = Modifier.padding(start = 6.dp))
                            }
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = onRestartDesktop,
                                enabled = !desktopBusy,
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null)
                                Text("Restart", modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                        else -> {
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onStartDesktop,
                                enabled = !desktopBusy,
                            ) {
                                Icon(Icons.Rounded.DesktopWindows, contentDescription = null)
                                Text(
                                    when (desktop.phase) {
                                        DesktopSessionPhase.STARTING -> "Starting desktop…"
                                        DesktopSessionPhase.STOPPING -> "Stopping desktop…"
                                        else -> "Start ${selectedEnvironment.name}"
                                    },
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        item(key = "system-label") {
            UdroidSectionLabel(
                text = "System",
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        item(key = "system-facts") {
            Surface(
                color = Color.Transparent,
                border = BorderStroke(1.dp, UdroidLine),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column {
                    FactRow("Storage", rootfs.directory.name)
                    HorizontalDivider(color = UdroidLine)
                    FactRow(
                        "Installed",
                        DateFormat.getDateTimeInstance(
                            DateFormat.MEDIUM,
                            DateFormat.SHORT,
                        ).format(Date(rootfs.readyAtEpochMs)),
                    )
                    distro?.let {
                        HorizontalDivider(color = UdroidLine)
                        FactRow("Architecture", it.architecture)
                    }
                }
            }
        }

        item(key = "mounts-label") {
            UdroidSectionLabel(
                text = "File access",
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        item(key = "mounts-settings") {
            MountProfilePanel(
                profile = mountProfile,
                enabled = maintenanceEnabled,
                crashed =
                    snapshot.rootfsName == rootfs.name &&
                        snapshot.phase == RuntimePhase.CRASHED,
                message = null,
                onConfigure = onConfigureMounts,
                onRetry = onOpenTerminal,
            )
        }

        item(key = "filesystem-label") {
            UdroidSectionLabel(
                text = "Storage",
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        item(key = "filesystem-actions") {
            Surface(
                color = Color.Transparent,
                border = BorderStroke(1.dp, UdroidLine),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "Manage this Linux system",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        when {
                            maintenanceInProgress ->
                                maintenanceMessage ?: "Changing the Linux system…"
                            runtimeBlocksMaintenance || desktopBlocksMaintenance ->
                                "Stop the terminal and desktop before resetting or deleting this system"
                            !resetAvailable ->
                                "Reset isn’t available because the original image source is missing. " +
                                    "You can still delete this system."
                            else ->
                                "Reset installs a fresh copy. Delete removes this Linux system."
                        },
                        modifier = Modifier.padding(top = 4.dp),
                        color = UdroidMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (maintenanceInProgress) {
                        CircularProgressIndicator(
                            modifier =
                                Modifier
                                    .padding(top = 14.dp)
                                    .size(22.dp),
                            strokeWidth = 2.dp,
                        )
                    } else if (runtimeBlocksMaintenance || desktopBlocksMaintenance) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (runtimeBlocksMaintenance) {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    enabled = !runtimeStopping,
                                    onClick = onStopTerminal,
                                ) {
                                    if (runtimeStopping) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(Icons.Rounded.Stop, contentDescription = null)
                                    }
                                    Text(
                                        if (runtimeStopping) "Stopping…" else "Stop terminal",
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                            }
                            if (desktopBlocksMaintenance) {
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = onStopDesktop,
                                ) {
                                    Icon(Icons.Rounded.Stop, contentDescription = null)
                                    Text(
                                        "Stop desktop",
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                modifier = Modifier.weight(1f),
                                enabled = maintenanceEnabled && resetAvailable,
                                onClick = {
                                    confirmation = FilesystemConfirmation.RESET
                                },
                            ) {
                                Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                                Text("Reset", modifier = Modifier.padding(start = 6.dp))
                            }
                            TextButton(
                                modifier = Modifier.weight(1f),
                                enabled = maintenanceEnabled,
                                colors =
                                    ButtonDefaults.textButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                onClick = {
                                    confirmation = FilesystemConfirmation.DELETE
                                },
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                                Text("Delete", modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                    maintenanceMessage
                        ?.takeIf { !maintenanceInProgress }
                        ?.let { message ->
                            Text(
                                message,
                                modifier = Modifier.padding(top = 8.dp),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                }
            }
        }

        item { Spacer(Modifier.height(28.dp)) }
    }

    confirmation?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = {
                Text(
                    if (action == FilesystemConfirmation.RESET) {
                        "Reset this Linux system?"
                    } else {
                        "Delete this Linux system?"
                    },
                )
            },
            text = {
                Text(
                    if (action == FilesystemConfirmation.RESET) {
                        "Reset permanently removes the packages, settings, and files you added. " +
                            "uDroid then installs a fresh copy of the original image."
                    } else {
                        "Delete permanently removes ${rootfs.name}, including its packages, " +
                            "settings, and files. You can’t undo this."
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                TextButton(
                    colors =
                        ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    onClick = {
                        confirmation = null
                        if (action == FilesystemConfirmation.RESET) {
                            onResetFilesystem()
                        } else {
                            onDeleteFilesystem()
                        }
                    },
                ) {
                    Text(
                        if (action == FilesystemConfirmation.RESET) {
                            "Erase and reinstall"
                        } else {
                            "Delete permanently"
                        },
                    )
                }
            },
        )
    }

}

private enum class FilesystemConfirmation {
    RESET,
    DELETE,
}

@Composable
private fun MountProfilePanel(
    profile: ProotMountProfile,
    enabled: Boolean,
    crashed: Boolean,
    message: String?,
    onConfigure: () -> Unit,
    onRetry: () -> Unit,
) {
    val enabledDefaults = PROOT_DEFAULT_MOUNTS.count { profile.isDefaultEnabled(it.id) }
    val enabledCustom = profile.customMounts.count { it.enabled }
    Surface(
        modifier = Modifier.clickable(enabled = enabled, onClick = onConfigure),
        color = Color.Transparent,
        border = BorderStroke(1.dp, UdroidLine),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text("Android and system files") },
                supportingContent = {
                    Column {
                        Text("$enabledDefaults defaults · session mounts as needed · $enabledCustom custom")
                        if (!enabled) Text("Stop Linux to make changes")
                        if (crashed) {
                            Text(
                                "Last launch stopped unexpectedly",
                                color = UdroidWarning,
                            )
                        }
                        message?.let { Text(it) }
                    }
                },
                leadingContent = {
                    Icon(Icons.Rounded.Settings, contentDescription = null)
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (enabled) "Configure" else "Locked")
                        if (enabled) {
                            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                        }
                    }
                },
            )
            if (crashed) {
                HorizontalDivider(color = UdroidLine)
                TextButton(
                    modifier = Modifier.align(Alignment.End).padding(horizontal = 8.dp),
                    onClick = onRetry,
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Text("Retry", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun AudioSettingsPanel(
    configuration: AudioConfiguration,
    runtimeRunning: Boolean,
    message: String?,
    onOutputChanged: (Boolean) -> Unit,
    onMicrophoneChanged: (Boolean) -> Unit,
) {
    Surface(
        color = Color.Transparent,
        border = BorderStroke(1.dp, UdroidLine),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column {
            SettingRow(
                title = "Device speaker",
                detail =
                    if (runtimeRunning) {
                        "Play Linux audio through Android. Changes apply to this session."
                    } else {
                        "Play Linux audio through Android when this system starts."
                    },
                checked = configuration.outputEnabled,
                enabled = true,
                onCheckedChange = onOutputChanged,
            )
            HorizontalDivider(color = UdroidLine)
            SettingRow(
                title = "Device microphone",
                detail =
                    "Off by default. Android asks before Linux can receive microphone input " +
                        "and shows its privacy indicator while active.",
                checked = configuration.microphoneEnabled,
                enabled = true,
                onCheckedChange = onMicrophoneChanged,
            )
            message?.let {
                HorizontalDivider(color = UdroidLine)
                Text(
                    text = it,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = UdroidMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun OperationalStatePanel(
    snapshot: RuntimeSnapshot,
    runtimeOwnsSystem: Boolean,
    desktopOwnsSystem: Boolean,
) {
    Surface(
        color = UdroidRaised,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            StateDatum(
                modifier = Modifier.weight(1f),
                label = "Linux",
                value =
                    if (runtimeOwnsSystem) {
                        snapshot.phase.name.lowercase().replaceFirstChar(Char::titlecase)
                    } else {
                        "Stopped"
                    },
                live = runtimeOwnsSystem && snapshot.phase == RuntimePhase.RUNNING,
            )
            StateDatum(
                modifier = Modifier.weight(1f),
                label = "Desktop",
                value =
                    if (desktopOwnsSystem) {
                        snapshot.desktop.phase.name.lowercase().replaceFirstChar(Char::titlecase)
                    } else {
                        "Stopped"
                    },
                live =
                    desktopOwnsSystem &&
                        snapshot.desktop.phase == DesktopSessionPhase.RUNNING,
            )
            StateDatum(
                modifier = Modifier.weight(1f),
                label = "Display",
                value =
                    when {
                        desktopOwnsSystem -> ":0"
                        else -> "Unclaimed"
                    },
                live = desktopOwnsSystem,
            )
        }
    }
}

@Composable
private fun StateDatum(
    label: String,
    value: String,
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            label,
            color = UdroidFaint,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(7.dp),
                color = if (live) UdroidForest else UdroidStrongLine,
                shape = CircleShape,
            ) {}
            Text(
                value,
                modifier = Modifier.padding(start = 7.dp),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SystemAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Surface(
        modifier =
            modifier.clickable(
                enabled = enabled,
                onClick = onClick,
            ),
        color = if (enabled) UdroidRaised else UdroidInset,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) UdroidForest else UdroidFaint,
            )
            Text(
                label,
                modifier = Modifier.padding(top = 5.dp),
                color = if (enabled) UdroidInk else UdroidFaint,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun DisplayOwnerRow(snapshot: RuntimeSnapshot) {
    val desktop = snapshot.desktop
    val claimed = desktop.phase.isDisplayClaimed() && desktop.rootfsName != null
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(8.dp),
            color = if (claimed) UdroidForest else UdroidStrongLine,
            shape = CircleShape,
        ) {}
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
        ) {
            Text(
                if (claimed) {
                    "Display :${desktop.displayNumber ?: 0} · ${desktop.environmentName}"
                } else {
                    "Display :0 · available"
                },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                if (claimed) desktop.message else "Ready to start a desktop",
                color = UdroidMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DesktopEnvironmentRow(
    environment: DesktopEnvironment,
    selected: Boolean,
    running: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect),
        color = if (selected) UdroidSoftGreen.copy(alpha = 0.42f) else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) UdroidForest.copy(alpha = 0.35f) else UdroidLine),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(environment.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${environment.kind.desktopName} · ${compositorSummary(environment)}",
                    color = UdroidMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (running) {
                UdroidStatusBadge(
                    label = "Running",
                    color = UdroidForest,
                    background = UdroidSoftGreen,
                )
            }
        }
    }
}

@Composable
private fun DesktopSettingsPanel(
    environment: DesktopEnvironment,
    configuration: DesktopConfiguration,
    desktopRunning: Boolean,
    onCompositingChanged: (Boolean) -> Unit,
    onTouchScaleChanged: (Boolean) -> Unit,
    onGraphicsProfileChanged: (DesktopGraphicsProfile) -> Unit,
) {
    val compositorSupport = environment.kind.compositorSupport
    val compositorConfigurable =
        compositorSupport == DesktopCompositorSupport.CONFIGURABLE
    val compositorChecked =
        when (compositorSupport) {
            DesktopCompositorSupport.REQUIRED -> true
            DesktopCompositorSupport.EXTERNAL_OR_NONE -> false
            DesktopCompositorSupport.UNKNOWN -> configuration.compositingEnabled
            DesktopCompositorSupport.CONFIGURABLE -> configuration.compositingEnabled
        }
    Surface(
        color = Color.Transparent,
        border = BorderStroke(1.dp, UdroidLine),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column {
            SettingRow(
                title = "Desktop compositing",
                detail =
                    when (compositorSupport) {
                        DesktopCompositorSupport.CONFIGURABLE ->
                            "Turn off for lower latency, or turn on for effects and transparency." +
                                if (desktopRunning) " Restart the desktop to apply." else ""
                        DesktopCompositorSupport.REQUIRED ->
                            "${environment.kind.desktopName} requires compositing"
                        DesktopCompositorSupport.EXTERNAL_OR_NONE ->
                            "${environment.kind.desktopName} has no standard compositing switch"
                        DesktopCompositorSupport.UNKNOWN ->
                            "Compositing can’t be changed for this desktop"
                    },
                checked = compositorChecked,
                enabled = compositorConfigurable,
                onCheckedChange = onCompositingChanged,
            )
            HorizontalDivider(color = UdroidLine)
            SettingRow(
                title = "Touch-sized interface",
                detail =
                    "Make desktop controls and the cursor easier to use on a phone." +
                        if (desktopRunning) " Restart the desktop to apply." else "",
                checked = configuration.touchScaleEnabled,
                enabled = true,
                onCheckedChange = onTouchScaleChanged,
            )
            if (GFXSTREAM_PROFILE_ENABLED) {
                HorizontalDivider(color = UdroidLine)
                GraphicsProfileSelector(
                    selected = configuration.graphicsProfile,
                    desktopRunning = desktopRunning,
                    onSelected = onGraphicsProfileChanged,
                )
            }
        }
    }
}

@Composable
private fun GraphicsProfileSelector(
    selected: DesktopGraphicsProfile,
    desktopRunning: Boolean,
    onSelected: (DesktopGraphicsProfile) -> Unit,
) {
    val gfxstreamSupported = "arm64-v8a" in Build.SUPPORTED_ABIS
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            "Graphics driver",
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            color = UdroidInk,
            style = MaterialTheme.typography.titleMedium,
        )
        GraphicsProfileRow(
            title = "Standard",
            detail = "Use the distribution’s default graphics driver",
            selected = selected == DesktopGraphicsProfile.STANDARD,
            enabled = true,
            onClick = { onSelected(DesktopGraphicsProfile.STANDARD) },
        )
        GraphicsProfileRow(
            title = "gfxstream (experimental)",
            detail =
                if (gfxstreamSupported) {
                    "Use Android’s Vulkan driver." +
                        if (desktopRunning) " Restart the desktop to apply." else ""
                } else {
                    "Available only on arm64 devices"
                },
            selected = selected == DesktopGraphicsProfile.GFXSTREAM_EXPERIMENTAL,
            enabled = gfxstreamSupported,
            onClick = { onSelected(DesktopGraphicsProfile.GFXSTREAM_EXPERIMENTAL) },
        )
    }
}

@Composable
private fun GraphicsProfileRow(
    title: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            RadioButton(
                selected = selected,
                enabled = enabled,
                onClick = null,
            )
        },
        headlineContent = {
            Text(
                title,
                color = if (enabled) UdroidInk else UdroidMuted,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            Text(
                detail,
                color = UdroidMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        },
    )
}

@Composable
private fun SettingRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = {
            Text(
                title,
                color = if (enabled) UdroidInk else UdroidMuted,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            Text(
                detail,
                color = UdroidMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange,
            )
        },
    )
}

@Composable
private fun FactRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = UdroidMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.weight(1f))
        Text(
            value,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun compositorSummary(environment: DesktopEnvironment): String =
    when (environment.kind.compositorSupport) {
        DesktopCompositorSupport.CONFIGURABLE -> "compositor can be managed"
        DesktopCompositorSupport.REQUIRED -> "compositor required"
        DesktopCompositorSupport.EXTERNAL_OR_NONE -> "external or no compositor"
        DesktopCompositorSupport.UNKNOWN -> "compositor unknown"
    }

private fun distributionFrom(rootfsName: String): LinuxDistribution {
    val normalized = rootfsName.lowercase()
    return when {
        "debian" in normalized -> LinuxDistribution.DEBIAN
        "arch" in normalized -> LinuxDistribution.ARCH
        "alpine" in normalized -> LinuxDistribution.ALPINE
        "void" in normalized -> LinuxDistribution.VOID
        else -> LinuxDistribution.UBUNTU
    }
}

private fun DesktopSessionPhase.isDisplayClaimed(): Boolean =
    this == DesktopSessionPhase.STARTING ||
        this == DesktopSessionPhase.RUNNING ||
        this == DesktopSessionPhase.STOPPING

private fun systemTitle(rootfsName: String): String =
    rootfsName
        .replace('-', ' ')
        .replace('_', ' ')
        .split(' ')
        .filter(String::isNotBlank)
        .joinToString(" ") { token -> token.replaceFirstChar(Char::titlecase) }
