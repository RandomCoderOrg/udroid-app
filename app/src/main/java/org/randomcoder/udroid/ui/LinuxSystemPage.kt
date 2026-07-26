package org.randomcoder.udroid.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import org.randomcoder.udroid.catalog.DistroVariant
import org.randomcoder.udroid.catalog.LinuxDistribution
import org.randomcoder.udroid.runtime.DesktopCompositorSupport
import org.randomcoder.udroid.runtime.DesktopConfiguration
import org.randomcoder.udroid.runtime.DesktopEnvironment
import org.randomcoder.udroid.runtime.DesktopSessionPhase
import org.randomcoder.udroid.runtime.InstalledRootfs
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
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenDisplay: () -> Unit,
    onSelectEnvironment: (String) -> Unit,
    onCompositingChanged: (Boolean) -> Unit,
    onTouchScaleChanged: (Boolean) -> Unit,
    onStartDesktop: () -> Unit,
    onStopDesktop: () -> Unit,
    onRestartDesktop: () -> Unit,
) {
    BackHandler(onBack = onBack)
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
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
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
                        rootfs.name,
                        color = UdroidMuted,
                        fontFamily = FontFamily.Monospace,
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
                    icon = Icons.Outlined.Terminal,
                    label = "Terminal",
                    onClick = onOpenTerminal,
                )
                SystemAction(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Apps,
                    label = "Apps",
                    onClick = onOpenApps,
                )
                SystemAction(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.DesktopWindows,
                    label = "Display",
                    enabled = desktopRunning,
                    onClick = onOpenDisplay,
                )
            }
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
                            "Detecting installed X11 desktops…",
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
                        border = BorderStroke(1.dp, UdroidLine),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "No desktop environment detected",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                scanMessage
                                    ?: "Install an X11 session such as XFCE, Plasma, or MATE.",
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
                                Icon(Icons.Outlined.Stop, contentDescription = null)
                                Text("Stop", modifier = Modifier.padding(start = 6.dp))
                            }
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = onRestartDesktop,
                                enabled = !desktopBusy,
                            ) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null)
                                Text("Restart", modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                        else -> {
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onStartDesktop,
                                enabled = !desktopBusy,
                            ) {
                                Icon(Icons.Outlined.DesktopWindows, contentDescription = null)
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
                shape = RoundedCornerShape(12.dp),
            ) {
                Column {
                    FactRow("Rootfs", rootfs.directory.name)
                    Divider(color = UdroidLine)
                    FactRow(
                        "Installed",
                        DateFormat.getDateTimeInstance(
                            DateFormat.MEDIUM,
                            DateFormat.SHORT,
                        ).format(Date(rootfs.readyAtEpochMs)),
                    )
                    distro?.let {
                        Divider(color = UdroidLine)
                        FactRow("Architecture", it.architecture)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(28.dp)) }
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
        border = BorderStroke(1.dp, UdroidLine),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            StateDatum(
                modifier = Modifier.weight(1f),
                label = "LINUX",
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
                label = "DESKTOP",
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
                label = "OWNER",
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
        border = BorderStroke(1.dp, UdroidLine),
        shape = RoundedCornerShape(11.dp),
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
                    "DISPLAY :${desktop.displayNumber ?: 0} · ${desktop.environmentName}"
                } else {
                    "DISPLAY :0 · unclaimed"
                },
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                if (claimed) desktop.message else "Ready for one supervised desktop",
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
        shape = RoundedCornerShape(11.dp),
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
        shape = RoundedCornerShape(12.dp),
    ) {
        Column {
            SettingRow(
                title = "Desktop compositing",
                detail =
                    when (compositorSupport) {
                        DesktopCompositorSupport.CONFIGURABLE ->
                            "Disable for lower latency; enable for effects and transparency." +
                                if (desktopRunning) " Applies after restart." else ""
                        DesktopCompositorSupport.REQUIRED ->
                            "${environment.kind.desktopName} requires its compositor."
                        DesktopCompositorSupport.EXTERNAL_OR_NONE ->
                            "${environment.kind.desktopName} does not expose one standard switch."
                        DesktopCompositorSupport.UNKNOWN ->
                            "No safe compositor adapter is available for this session."
                    },
                checked = compositorChecked,
                enabled = compositorConfigurable,
                onCheckedChange = onCompositingChanged,
            )
            Divider(color = UdroidLine)
            SettingRow(
                title = "Touch-sized interface",
                detail =
                    "Scale desktop controls and cursor for a phone display." +
                        if (desktopRunning) " Applies after restart." else "",
                checked = configuration.touchScaleEnabled,
                enabled = true,
                onCheckedChange = onTouchScaleChanged,
            )
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = if (enabled) UdroidInk else UdroidMuted,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                detail,
                color = UdroidMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
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
