package org.randomcoder.udroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt
import com.termux.x11.input.InputStub
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
    var inputPaletteLocked by rememberSaveable { mutableStateOf(false) }
    var heldMouseButton by remember { mutableStateOf<Int?>(null) }
    var inputPaletteX by rememberSaveable { mutableIntStateOf(UNSET_PALETTE_POSITION) }
    var inputPaletteY by rememberSaveable { mutableIntStateOf(UNSET_PALETTE_POSITION) }
    var showSettings by remember { mutableStateOf(false) }
    var displayView by remember { mutableStateOf<X11DisplayView?>(null) }
    val releaseHeldMouseButton = {
        heldMouseButton?.let { displayView?.setMouseButton(it, false) }
        heldMouseButton = null
    }
    BackHandler {
        if (showSettings) {
            showSettings = false
        } else if (inputPaletteVisible) {
            releaseHeldMouseButton()
            inputPaletteVisible = false
        } else {
            onExit()
        }
    }
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
                    inputPaletteVisible = inputPaletteVisible,
                    onExit = {
                        releaseHeldMouseButton()
                        onExit()
                    },
                    onKeyboard = { displayView?.showKeyboard() },
                    onInputPalette = {
                        if (inputPaletteVisible) releaseHeldMouseButton()
                        inputPaletteVisible = !inputPaletteVisible
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
                        X11DisplayView(context).also {
                            it.applySettings(settings)
                            displayView = it
                        }
                    },
                    update = { it.applySettings(settings) },
                    modifier = Modifier.fillMaxSize(),
                )
                if (inputPaletteVisible) {
                    DesktopInputPalette(
                        locked = inputPaletteLocked,
                        heldMouseButton = heldMouseButton,
                        positionX = inputPaletteX,
                        positionY = inputPaletteY,
                        onLockedChange = { inputPaletteLocked = it },
                        onPositionChange = { x, y ->
                            inputPaletteX = x
                            inputPaletteY = y
                        },
                        onMouseButton = { button ->
                            val next = nextHeldMouseButton(heldMouseButton, button)
                            heldMouseButton?.let { displayView?.setMouseButton(it, false) }
                            next?.let { displayView?.setMouseButton(it, true) }
                            heldMouseButton = next
                        },
                        onScroll = { displayView?.scrollMouse(it) },
                    )
                }
            }
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
    inputPaletteVisible: Boolean,
    onExit: () -> Unit,
    onKeyboard: () -> Unit,
    onInputPalette: () -> Unit,
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
                text = status.removePrefix("Display :0 "),
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
private fun DesktopInputPalette(
    locked: Boolean,
    heldMouseButton: Int?,
    positionX: Int,
    positionY: Int,
    onLockedChange: (Boolean) -> Unit,
    onPositionChange: (Int, Int) -> Unit,
    onMouseButton: (Int) -> Unit,
    onScroll: (Float) -> Unit,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var paletteSize by remember { mutableStateOf(IntSize.Zero) }
    val margin = 12.dp
    val marginPixels = with(androidx.compose.ui.platform.LocalDensity.current) { margin.roundToPx() }
    val latestPositionX = rememberUpdatedState(positionX)
    val latestPositionY = rememberUpdatedState(positionY)

    LaunchedEffect(containerSize, paletteSize) {
        if (containerSize == IntSize.Zero || paletteSize == IntSize.Zero) return@LaunchedEffect
        val maxX = (containerSize.width - paletteSize.width).coerceAtLeast(0)
        val maxY = (containerSize.height - paletteSize.height).coerceAtLeast(0)
        val clampedX =
            if (positionX == UNSET_PALETTE_POSITION) {
                (maxX - marginPixels).coerceAtLeast(0)
            } else {
                clampInputPaletteCoordinate(positionX, maxX)
            }
        val clampedY =
            if (positionY == UNSET_PALETTE_POSITION) {
                marginPixels.coerceAtMost(maxY)
            } else {
                clampInputPaletteCoordinate(positionY, maxY)
            }
        if (clampedX != positionX || clampedY != positionY) {
            onPositionChange(clampedX, clampedY)
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .onSizeChanged { containerSize = it },
    ) {
        Surface(
            modifier =
                Modifier
                    .offset {
                        IntOffset(
                            positionX.coerceAtLeast(0),
                            positionY.coerceAtLeast(0),
                        )
                    }.onSizeChanged { paletteSize = it },
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 3.dp,
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Row(
                    modifier =
                        Modifier
                            .width(184.dp)
                            .height(36.dp)
                            .then(
                                if (locked) {
                                    Modifier
                                } else {
                                    Modifier.pointerInput(containerSize, paletteSize) {
                                        var dragX = 0f
                                        var dragY = 0f
                                        detectDragGestures(
                                            onDragStart = {
                                                dragX = latestPositionX.value.toFloat()
                                                dragY = latestPositionY.value.toFloat()
                                            },
                                        ) { change, dragAmount ->
                                            change.consume()
                                            val maxX =
                                                (containerSize.width - paletteSize.width)
                                                    .coerceAtLeast(0)
                                            val maxY =
                                                (containerSize.height - paletteSize.height)
                                                    .coerceAtLeast(0)
                                            dragX =
                                                accumulateInputPaletteDrag(dragX, dragAmount.x, maxX)
                                            dragY =
                                                accumulateInputPaletteDrag(dragY, dragAmount.y, maxY)
                                            onPositionChange(dragX.roundToInt(), dragY.roundToInt())
                                        }
                                    }
                                },
                            ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DragHandle,
                        contentDescription = if (locked) null else "Move mouse controls",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Mouse",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = { onLockedChange(!locked) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector =
                                if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                            contentDescription =
                                if (locked) {
                                    "Unlock mouse controls"
                                } else {
                                    "Lock mouse controls"
                                },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MouseButton(
                        label = "L",
                        description = "Hold left mouse button",
                        selected = heldMouseButton == InputStub.BUTTON_LEFT,
                        onClick = { onMouseButton(InputStub.BUTTON_LEFT) },
                    )
                    MouseButton(
                        label = "M",
                        description = "Hold middle mouse button",
                        selected = heldMouseButton == InputStub.BUTTON_MIDDLE,
                        onClick = { onMouseButton(InputStub.BUTTON_MIDDLE) },
                    )
                    MouseButton(
                        label = "R",
                        description = "Hold right mouse button",
                        selected = heldMouseButton == InputStub.BUTTON_RIGHT,
                        onClick = { onMouseButton(InputStub.BUTTON_RIGHT) },
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    FilledTonalIconButton(
                        onClick = { onScroll(MOUSE_SCROLL_STEP) },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowUp,
                            contentDescription = "Scroll up",
                        )
                    }
                    FilledTonalIconButton(
                        onClick = { onScroll(-MOUSE_SCROLL_STEP) },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.KeyboardArrowDown,
                            contentDescription = "Scroll down",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MouseButton(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .size(40.dp)
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (selected) "Release" else "Hold",
                    onClick = onClick,
                ).semantics {
                    role = Role.Button
                    contentDescription = description
                },
        shape = RoundedCornerShape(20.dp),
        color =
            if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                Color.Transparent
            },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

internal fun clampInputPaletteCoordinate(
    value: Int,
    maximum: Int,
): Int = value.coerceIn(0, maximum.coerceAtLeast(0))

internal fun nextHeldMouseButton(
    current: Int?,
    tapped: Int,
): Int? = if (current == tapped) null else tapped

internal fun accumulateInputPaletteDrag(
    current: Float,
    delta: Float,
    maximum: Int,
): Float = (current + delta).coerceIn(0f, maximum.coerceAtLeast(0).toFloat())

private const val UNSET_PALETTE_POSITION = Int.MIN_VALUE
private const val MOUSE_SCROLL_STEP = 120f

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
