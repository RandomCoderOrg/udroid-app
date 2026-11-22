package org.randomcoder.udroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
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
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import org.randomcoder.udroid.runtime.terminalDistroTitle
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
    rootfsName: String?,
    onExit: () -> Unit,
    onOpenTerminal: () -> Unit,
    onStartLinux: () -> Unit,
) {
    val context = LocalContext.current
    val settingsStore = remember(context) { X11SettingsStore(context) }
    var settings by remember { mutableStateOf(settingsStore.load()) }
    var controlsExpanded by rememberSaveable {
        mutableStateOf(!settings.startControlsCollapsed)
    }
    var inputPaletteVisible by rememberSaveable { mutableStateOf(false) }
    var displayKeysVisible by rememberSaveable { mutableStateOf(false) }
    var arrangingDisplayKeys by rememberSaveable { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var displayView by remember { mutableStateOf<X11DisplayView?>(null) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler {
        if (showSettings) {
            showSettings = false
        } else if (imeVisible) {
            displayView?.hideKeyboard()
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
    var attempt by remember { mutableStateOf(0) }
    var attached by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val updateSettings: (X11Settings) -> Unit = { updated ->
        if (updated.displayKeys.size > settings.displayKeys.size) displayKeysVisible = true
        settings = settingsStore.save(updated)
        displayView?.applySettings(updated)
    }

    LaunchedEffect(arrangingDisplayKeys, showSettings) {
        if (showSettings) displayView?.resetInput()
        if (arrangingDisplayKeys && !showSettings) {
            withFrameNanos { }
            displayView?.hideKeyboard()
        }
    }

    DisposableEffect(service, displayView, snapshot.phase, snapshot.bootId, rootfsName, attempt) {
        val view = displayView
        var current = true
        attached = false
        error = null
        if (snapshot.phase == RuntimePhase.RUNNING && service != null && view != null && rootfsName != null) {
            service.requestX11RendererConnection(rootfsName) { result ->
                result.onSuccess { descriptor ->
                    if (current && view.isAttachedToWindow) {
                        view.attachRenderer(descriptor)
                        attached = true
                        error = null
                    } else {
                        descriptor.close()
                    }
                }.onFailure { if (current) error = it.message ?: "Display :0 could not connect" }
            }
        }
        onDispose {
            current = false
            view?.detachRenderer()
        }
    }

    LaunchedEffect(snapshot.phase, snapshot.bootId, service, attached, attempt) {
        if (snapshot.phase != RuntimePhase.RUNNING) return@LaunchedEffect
        if (attached) {
            while (true) {
                delay(1500)
                if (displayView?.isRendererAttached != true) {
                    attached = false
                    error = "The renderer disconnected."
                    break
                }
            }
        } else {
            delay(12000)
            if (error == null) error = "Timed out connecting to display :0."
        }
    }

    val palettePrimary = MaterialTheme.colorScheme.primaryContainer.toArgb()
    val paletteSurface = UdroidTerminalSurface.toArgb()
    val paletteRaised = UdroidTerminalRaised.toArgb()
    val paletteText = UdroidTerminalText.toArgb()
    val paletteActiveText = UdroidTerminalGreen.toArgb()
    val paletteOutline = UdroidTerminalLine.toArgb()

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
                    onOpenTerminal = onOpenTerminal,
                    attached = attached,
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
                    attached = attached,
                    onOpenTerminal = onOpenTerminal,
                    onExpand = { controlsExpanded = true },
                )
            }
            if (attached && controlsExpanded) {
                val desktop = snapshot.desktop
                val owner = desktop.rootfsName?.takeIf { desktop.phase.isDisplayClaimed() }
                val detail = if (arrangingDisplayKeys) "Drag keys to move them" else owner?.let {
                    "Display :0 · ${desktop.environmentName ?: "Desktop"} · ${terminalDistroTitle(it)}"
                }
                if (detail != null) {
                    Text(
                        text = detail,
                        color = UdroidTerminalText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
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
                        it.setPaletteVisible(attached && inputPaletteVisible)
                        it.setKeyboardBindings(settings.overlayKeyRows)
                        it.setKeyboardStripVisible(attached && settings.showKeyboardStrip)
                        it.setDisplayKeys(settings.displayKeys)
                        it.setDisplayKeysVisible(attached && displayKeysVisible)
                        it.setDisplayKeysArranging(arrangingDisplayKeys)
                        it.setPaletteColors(
                            primaryContainer = palettePrimary,
                            surfaceContainer = paletteSurface,
                            raisedContainer = paletteRaised,
                            onSurface = paletteText,
                            activeText = paletteActiveText,
                            outline = paletteOutline,
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (!attached) {
                    Column(
                        modifier = Modifier.align(Alignment.Center).background(Color.Black).padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val stopped = snapshot.phase == RuntimePhase.STOPPED || snapshot.phase == RuntimePhase.CRASHED
                        val message = when (snapshot.phase) {
                            RuntimePhase.STOPPED -> "Start Linux to use display :0"
                            RuntimePhase.CRASHED -> snapshot.message
                            RuntimePhase.STARTING -> "Starting Linux…"
                            RuntimePhase.STOPPING -> "Stopping Linux…"
                            RuntimePhase.RUNNING -> if (error?.startsWith("XKB data is missing") == true) {
                                "Display :0 needs XKB keyboard data."
                            } else {
                                error ?: "Connecting to display :0…"
                            }
                        }
                        Text(message, color = UdroidTerminalText, style = MaterialTheme.typography.bodyMedium)
                        if (error?.contains("XKB", ignoreCase = true) == true) {
                            Text("Install XKB keyboard data in this distro, then retry.", color = UdroidTerminalMuted)
                        }
                        if (stopped) {
                            Button(onClick = onStartLinux) { Text("Start Linux") }
                        } else if (error != null && snapshot.phase == RuntimePhase.RUNNING) {
                            Button(onClick = { attempt++ }) { Text("Retry display") }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        X11SettingsPage(
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
    onOpenTerminal: () -> Unit,
    attached: Boolean,
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
                contentDescription = "Leave display",
                tint = UdroidTerminalText,
            )
        }
        TextButton(onClick = onOpenTerminal) { Text("Terminal", color = UdroidTerminalGreen) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onKeyboard, enabled = attached) {
            Icon(
                imageVector = Icons.Rounded.Keyboard,
                contentDescription = "Show keyboard",
                tint = UdroidTerminalText,
            )
        }
        IconButton(onClick = onInputPalette, enabled = attached) {
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
            enabled = attached,
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
                contentDescription = "Display settings",
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
    onOpenTerminal: () -> Unit,
    onExpand: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(48.dp)
                .background(Color.Black),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (attached) "●  :0" else "○  :0",
            color = if (attached) UdroidTerminalGreen else UdroidTerminalMuted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = UdroidSpacing.content.dp),
        )
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onOpenTerminal) { Text("Terminal", color = UdroidTerminalGreen) }
        IconButton(
            onClick = onExpand,
            modifier = Modifier.height(48.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = "Show desktop controls",
                tint = UdroidTerminalMuted,
            )
        }
    }
}
