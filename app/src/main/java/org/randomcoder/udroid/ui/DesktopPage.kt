package org.randomcoder.udroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.randomcoder.udroid.runtime.RuntimePhase
import org.randomcoder.udroid.runtime.RuntimeSnapshot
import org.randomcoder.udroid.runtime.RuntimeSupervisorService
import org.randomcoder.udroid.x11.X11DisplayView
import org.randomcoder.udroid.x11.X11Settings
import org.randomcoder.udroid.x11.X11SettingsStore

@Composable
fun DesktopPage(
    snapshot: RuntimeSnapshot,
    service: RuntimeSupervisorService?,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val settingsStore = remember(context) { X11SettingsStore(context) }
    var settings by remember { mutableStateOf(settingsStore.load()) }
    var controlsExpanded by remember {
        mutableStateOf(!settings.startControlsCollapsed)
    }
    var showSettings by remember { mutableStateOf(false) }
    BackHandler {
        if (showSettings) {
            showSettings = false
        } else {
            onExit()
        }
    }
    var displayView by remember { mutableStateOf<X11DisplayView?>(null) }
    var status by remember { mutableStateOf("Waiting for the supervised X11 renderer") }
    val updateSettings: (X11Settings) -> Unit = { updated ->
        settings = settingsStore.save(updated)
        displayView?.applySettings(updated)
    }

    DisposableEffect(service, displayView, snapshot.bootId) {
        val view = displayView
        if (
            snapshot.phase == RuntimePhase.RUNNING &&
            service != null &&
            view != null &&
            !view.isRendererAttached
        ) {
            status = "Connecting Android surface to display :0"
            service.requestX11RendererConnection { descriptor ->
                when {
                    descriptor == null -> status = "X11 renderer connection is not ready"
                    !view.isAttachedToWindow -> {
                        descriptor.close()
                    }
                    else -> {
                        view.attachRenderer(descriptor)
                        status = "Display :0 attached"
                    }
                }
            }
        } else if (snapshot.phase != RuntimePhase.RUNNING) {
            status = "Start the Linux runtime before opening Desktop"
        }

        onDispose {
            view?.detachRenderer()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (controlsExpanded) {
                DesktopControlBar(
                    status = status,
                    onExit = onExit,
                    onKeyboard = { displayView?.showKeyboard() },
                    onSettings = { showSettings = true },
                    onCollapse = { controlsExpanded = false },
                )
            } else {
                CollapsedDesktopControlBar(
                    attached = displayView?.isRendererAttached == true,
                    onExpand = { controlsExpanded = true },
                )
            }
            AndroidView(
                factory = { context ->
                    X11DisplayView(context).also {
                        it.applySettings(settings)
                        displayView = it
                    }
                },
                update = { it.applySettings(settings) },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            )
        }
    }

    if (showSettings) {
        X11SettingsDialog(
            settings = settings,
            onSettingsChanged = updateSettings,
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun DesktopControlBar(
    status: String,
    onExit: () -> Unit,
    onKeyboard: () -> Unit,
    onSettings: () -> Unit,
    onCollapse: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(UdroidTerminal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onExit) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Close desktop",
                tint = UdroidTerminalText,
            )
        }
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = UdroidSpacing.compact.dp),
        ) {
            Text(
                text = "DISPLAY :0",
                color = UdroidTerminalGreen,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = status.removePrefix("Display :0 "),
                color = UdroidTerminalText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
        IconButton(onClick = onKeyboard) {
            Icon(
                imageVector = Icons.Outlined.Keyboard,
                contentDescription = "Show keyboard",
                tint = UdroidTerminalText,
            )
        }
        IconButton(onClick = onSettings) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "Desktop settings",
                tint = UdroidTerminalText,
            )
        }
        IconButton(onClick = onCollapse) {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowUp,
                contentDescription = "Collapse desktop controls",
                tint = UdroidTerminalMuted,
            )
        }
    }
}

@Composable
private fun CollapsedDesktopControlBar(
    attached: Boolean,
    onExpand: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(32.dp)
                .background(Color.Black),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (attached) "●  :0" else "○  :0",
            color = if (attached) UdroidTerminalGreen else UdroidTerminalMuted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = UdroidSpacing.content.dp),
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
        IconButton(
            onClick = onExpand,
            modifier = Modifier.height(32.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = "Show desktop controls",
                tint = UdroidTerminalMuted,
            )
        }
    }
}
