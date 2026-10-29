package org.randomcoder.udroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    var inputPaletteVisible by rememberSaveable { mutableStateOf(false) }
    var displayKeysVisible by rememberSaveable { mutableStateOf(false) }
    var arrangingDisplayKeys by rememberSaveable { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var displayView by remember { mutableStateOf<X11DisplayView?>(null) }
    BackHandler {
        if (showSettings) {
            showSettings = false
        } else if (inputPaletteVisible) {
            inputPaletteVisible = false
        } else if (arrangingDisplayKeys) {
            arrangingDisplayKeys = false
        } else if (displayKeysVisible) {
            displayKeysVisible = false
        } else {
            onExit()
        }
    }
    var status by remember { mutableStateOf("Waiting for the supervised X11 renderer") }
    val updateSettings: (X11Settings) -> Unit = { updated ->
        if (updated.displayKeys.size > settings.displayKeys.size) displayKeysVisible = true
        settings = settingsStore.save(updated)
        displayView?.applySettings(updated)
    }

    LaunchedEffect(arrangingDisplayKeys, showSettings) {
        if (arrangingDisplayKeys && !showSettings) {
            withFrameNanos { }
            displayView?.hideKeyboard()
        }
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

    val palettePrimary = MaterialTheme.colorScheme.primaryContainer.toArgb()
    val paletteSurface = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb()
    val paletteText = MaterialTheme.colorScheme.onSurface.toArgb()
    val paletteOutline = MaterialTheme.colorScheme.outline.toArgb()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black,
    ) {
        Column(
            modifier =
                Modifier.fillMaxSize().navigationBarsPadding().then(
                    if (settings.avoidSoftKeyboard) Modifier.imePadding() else Modifier,
                ),
        ) {
            if (controlsExpanded) {
                DesktopControlBar(
                    status = status,
                    inputPaletteVisible = inputPaletteVisible,
                    displayKeysVisible = displayKeysVisible,
                    arrangingDisplayKeys = arrangingDisplayKeys,
                    onExit = onExit,
                    onKeyboard = { displayView?.showKeyboard() },
                    onInputPalette = { inputPaletteVisible = !inputPaletteVisible },
                    onKeyboardPalette = {
                        if (arrangingDisplayKeys) arrangingDisplayKeys = false
                        else if (settings.displayKeys.isEmpty()) showSettings = true
                        else displayKeysVisible = !displayKeysVisible
                    },
                    onSettings = { showSettings = true },
                    onCollapse = { controlsExpanded = false },
                )
            } else {
                CollapsedDesktopControlBar(
                    attached = displayView?.isRendererAttached == true,
                    onExpand = { controlsExpanded = true },
                )
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            ) {
                AndroidView(
                    factory = { context ->
                        X11DesktopHostView(context).also {
                            it.displayView.applySettings(settings)
                            displayView = it.displayView
                        }
                    },
                    update = {
                        it.displayView.applySettings(settings)
                        it.onDisplayKeysMoved = { moved ->
                            updateSettings(settings.copy(displayKeys = moved))
                        }
                        it.setPaletteVisible(inputPaletteVisible)
                        it.setKeyboardBindings(settings.overlayKeys)
                        it.setKeyboardStripVisible(settings.showKeyboardStrip)
                        it.setDisplayKeys(settings.displayKeys)
                        it.setDisplayKeysVisible(displayKeysVisible)
                        it.setDisplayKeysArranging(arrangingDisplayKeys)
                        it.setPaletteColors(
                            primaryContainer = palettePrimary,
                            surfaceContainer = paletteSurface,
                            onSurface = paletteText,
                            outline = paletteOutline,
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (showSettings) {
        X11SettingsDialog(
            settings = settings,
            onSettingsChanged = updateSettings,
            onArrangeDisplayKeys = {
                displayKeysVisible = true
                arrangingDisplayKeys = true
                controlsExpanded = true
                showSettings = false
            },
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun DesktopControlBar(
    status: String,
    inputPaletteVisible: Boolean,
    displayKeysVisible: Boolean,
    arrangingDisplayKeys: Boolean,
    onExit: () -> Unit,
    onKeyboard: () -> Unit,
    onInputPalette: () -> Unit,
    onKeyboardPalette: () -> Unit,
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
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
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
                text = if (arrangingDisplayKeys) "Drag buttons to move" else status.removePrefix("Display :0 "),
                color = UdroidTerminalText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
        IconButton(onClick = onKeyboard) {
            Icon(
                imageVector = Icons.Rounded.Keyboard,
                contentDescription = "Show keyboard",
                tint = UdroidTerminalText,
            )
        }
        IconButton(onClick = onInputPalette) {
            Icon(
                imageVector = Icons.Rounded.Mouse,
                contentDescription =
                    if (inputPaletteVisible) {
                        "Hide mouse controls"
                    } else {
                        "Show mouse controls"
                    },
                tint =
                    if (inputPaletteVisible) {
                        UdroidTerminalGreen
                    } else {
                        UdroidTerminalText
                    },
            )
        }
        IconButton(
            onClick = onKeyboardPalette,
            modifier = Modifier.semantics {
                contentDescription =
                    if (arrangingDisplayKeys) "Done arranging display keys"
                    else if (displayKeysVisible) "Hide display keys" else "Show display keys"
            },
        ) {
            Text(
                text = if (arrangingDisplayKeys) "Done" else "Keys",
                color = if (displayKeysVisible) UdroidTerminalGreen else UdroidTerminalText,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        IconButton(onClick = onSettings) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = "Desktop settings",
                tint = UdroidTerminalText,
            )
        }
        IconButton(onClick = onCollapse) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowUp,
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
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "Show desktop controls",
                tint = UdroidTerminalMuted,
            )
        }
    }
}
