package org.randomcoder.udroid.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.randomcoder.udroid.runtime.TerminalExtraKeys
import org.randomcoder.udroid.runtime.TerminalExtraKeysState
import org.randomcoder.udroid.x11.DisplayKey
import org.randomcoder.udroid.x11.OverlayKeyboard
import org.randomcoder.udroid.x11.X11DisplayFilter
import org.randomcoder.udroid.x11.X11ResolutionMode
import org.randomcoder.udroid.x11.X11Settings
import org.randomcoder.udroid.x11.X11TouchMode
import kotlin.math.roundToInt

private enum class X11SettingsDestination {
    HOME,
    KEYBOARD_STRIP,
    KEY_PICKER,
    DISPLAY_BUTTONS,
    DISPLAY_BUTTON_PICKER,
}

private sealed interface X11KeyTarget {
    data class Replace(val row: Int, val column: Int) : X11KeyTarget

    data class Append(val row: Int) : X11KeyTarget

    data object NewRow : X11KeyTarget
}

@Composable
fun X11SettingsPage(
    settings: X11Settings,
    onSettingsChanged: (X11Settings) -> Unit,
    onArrangeDisplayKeys: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf(X11SettingsDestination.HOME) }
    var keyTarget by remember { mutableStateOf<X11KeyTarget?>(null) }
    var displayButtonIndex by remember { mutableIntStateOf(-1) }
    var query by remember { mutableStateOf("") }
    var importMessage by remember { mutableStateOf<String?>(null) }
    val importKeys =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                TerminalExtraKeys.parseTermuxJson(context.readLimitedText(uri, "Extra-keys file"))
                            }
                        }
                    result.onSuccess { imported ->
                        onSettingsChanged(
                            settings.copy(overlayKeyRows = OverlayKeyboard.normalizedRows(imported.rows)),
                        )
                        importMessage =
                            if (imported.ignoredPopups == 0) {
                                "Imported ${imported.rows.sumOf { it.size }} keys"
                            } else {
                                "Imported primary keys; ${imported.ignoredPopups} popup actions were ignored"
                            }
                    }.onFailure { error ->
                        importMessage = "Could not import keys: ${error.message ?: "invalid JSON"}"
                    }
                }
            }
        }
    val exportKeys =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                                    it.write(TerminalExtraKeys.toTermuxJson(settings.overlayKeyRows))
                                } ?: error("Could not open file")
                            }
                        }
                    result.onSuccess { importMessage = "Exported display keyboard layout" }
                        .onFailure { error ->
                            importMessage = "Could not export keys: ${error.message ?: "write failed"}"
                        }
                }
            }
        }

    fun goBack() {
        destination =
            when (destination) {
                X11SettingsDestination.KEY_PICKER -> X11SettingsDestination.KEYBOARD_STRIP
                X11SettingsDestination.DISPLAY_BUTTON_PICKER -> X11SettingsDestination.DISPLAY_BUTTONS
                X11SettingsDestination.HOME -> {
                    onDismiss()
                    X11SettingsDestination.HOME
                }
                else -> X11SettingsDestination.HOME
            }
    }

    BackHandler(onBack = ::goBack)

    UdroidTerminalTheme {
        Surface(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            color = UdroidTerminal,
        ) {
            when (destination) {
                X11SettingsDestination.HOME ->
                    X11SettingsHomePage(
                        settings = settings,
                        onSettingsChanged = onSettingsChanged,
                        onKeyboardStrip = { destination = X11SettingsDestination.KEYBOARD_STRIP },
                        onDisplayButtons = { destination = X11SettingsDestination.DISPLAY_BUTTONS },
                        onBack = onDismiss,
                    )

                X11SettingsDestination.KEYBOARD_STRIP ->
                    TerminalExtraKeysSettingsPage(
                        modifier = Modifier.fillMaxSize(),
                        state = TerminalExtraKeysState(settings.showKeyboardStrip, settings.overlayKeyRows),
                        message = importMessage,
                        title = "Keyboard strip",
                        visibilityTitle = "Show keyboard strip",
                        visibilityDetail = "Keep these rows below the Linux desktop",
                        resetLabel = "Reset keyboard strip",
                        bindingLabel = OverlayKeyboard::label,
                        onBack = ::goBack,
                        onChanged = {
                            onSettingsChanged(
                                settings.copy(
                                    showKeyboardStrip = it.visible,
                                    overlayKeyRows = OverlayKeyboard.normalizedRows(it.rows),
                                ),
                            )
                        },
                        onEdit = { row, column ->
                            keyTarget = X11KeyTarget.Replace(row, column)
                            query = ""
                            destination = X11SettingsDestination.KEY_PICKER
                        },
                        onAddKey = { row ->
                            keyTarget = X11KeyTarget.Append(row)
                            query = ""
                            destination = X11SettingsDestination.KEY_PICKER
                        },
                        onAddRow = {
                            keyTarget = X11KeyTarget.NewRow
                            query = ""
                            destination = X11SettingsDestination.KEY_PICKER
                        },
                        onRemoveKey = { row, column ->
                            onSettingsChanged(
                                settings.copy(
                                    overlayKeyRows = removeTerminalExtraKey(settings.overlayKeyRows, row, column),
                                ),
                            )
                        },
                        onRemoveRow = { row ->
                            onSettingsChanged(
                                settings.copy(overlayKeyRows = removeTerminalExtraKeyRow(settings.overlayKeyRows, row)),
                            )
                        },
                        onReset = { onSettingsChanged(settings.copy(overlayKeyRows = OverlayKeyboard.defaultRows)) },
                        onImport = { importKeys.launch(arrayOf("application/json", "text/*")) },
                        onExport = { exportKeys.launch("udroid-display-keys.json") },
                    )

                X11SettingsDestination.KEY_PICKER -> {
                    val initial =
                        (keyTarget as? X11KeyTarget.Replace)?.let {
                            settings.overlayKeyRows.getOrNull(it.row)?.getOrNull(it.column)
                        }
                    TerminalExtraKeyPickerPage(
                        modifier = Modifier.fillMaxSize(),
                        query = query,
                        initial = initial,
                        keys = OverlayKeyboard.keys,
                        keyLabel = OverlayKeyboard::label,
                        keyBinding = OverlayKeyboard::binding,
                        onQueryChanged = { query = it },
                        onBack = ::goBack,
                        onSelected = { binding ->
                            val rows = settings.overlayKeyRows.map { it.toMutableList() }.toMutableList()
                            when (val target = keyTarget) {
                                is X11KeyTarget.Replace -> rows[target.row][target.column] = binding
                                is X11KeyTarget.Append -> rows[target.row].add(binding)
                                X11KeyTarget.NewRow -> rows.add(mutableListOf(binding))
                                null -> Unit
                            }
                            onSettingsChanged(settings.copy(overlayKeyRows = OverlayKeyboard.normalizedRows(rows)))
                            keyTarget = null
                            destination = X11SettingsDestination.KEYBOARD_STRIP
                        },
                    )
                }

                X11SettingsDestination.DISPLAY_BUTTONS ->
                    DisplayButtonsSettingsPage(
                        settings = settings,
                        onBack = ::goBack,
                        onAdd = {
                            displayButtonIndex = -1
                            query = ""
                            destination = X11SettingsDestination.DISPLAY_BUTTON_PICKER
                        },
                        onEdit = {
                            displayButtonIndex = it
                            query = ""
                            destination = X11SettingsDestination.DISPLAY_BUTTON_PICKER
                        },
                        onRemove = { index ->
                            onSettingsChanged(
                                settings.copy(
                                    displayKeys = settings.displayKeys.filterIndexed { i, _ -> i != index },
                                ),
                            )
                        },
                        onArrange = onArrangeDisplayKeys,
                    )

                X11SettingsDestination.DISPLAY_BUTTON_PICKER -> {
                    val initial = settings.displayKeys.getOrNull(displayButtonIndex)?.binding
                    TerminalExtraKeyPickerPage(
                        modifier = Modifier.fillMaxSize(),
                        query = query,
                        initial = initial,
                        title = if (displayButtonIndex < 0) "Add display button" else "Edit display button",
                        keys = OverlayKeyboard.keys,
                        keyLabel = OverlayKeyboard::label,
                        keyBinding = OverlayKeyboard::binding,
                        onQueryChanged = { query = it },
                        onBack = ::goBack,
                        onSelected = { binding ->
                            val buttons = settings.displayKeys.toMutableList()
                            if (displayButtonIndex in buttons.indices) {
                                buttons[displayButtonIndex] = buttons[displayButtonIndex].copy(binding = binding)
                            } else {
                                val index = buttons.size
                                buttons += DisplayKey(binding, 0.12f + (index % 4) * 0.2f, 0.4f + (index / 4) * 0.08f)
                            }
                            onSettingsChanged(settings.copy(displayKeys = buttons))
                            destination = X11SettingsDestination.DISPLAY_BUTTONS
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun X11SettingsHomePage(
    settings: X11Settings,
    onSettingsChanged: (X11Settings) -> Unit,
    onKeyboardStrip: () -> Unit,
    onDisplayButtons: () -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TerminalSettingsHeader("Desktop settings", onBack)
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
            item {
                Text(
                    "Changes apply live to display :0",
                    color = UdroidTerminalMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(18.dp))
                SettingsSectionTitle("Output")
                SettingsLabel("Resolution", "Native matches your screen. Scaled changes the size of Linux controls.")
                ChoiceGroup(
                    selected = settings.resolutionMode,
                    options =
                        listOf(
                            X11ResolutionMode.NATIVE to "Native",
                            X11ResolutionMode.SCALED to "Scaled",
                            X11ResolutionMode.EXACT to "Fixed",
                        ),
                    onSelected = { onSettingsChanged(settings.copy(resolutionMode = it)) },
                )
                if (settings.resolutionMode == X11ResolutionMode.SCALED) {
                    SettingsSlider("Display scale", settings.displayScalePercent, 50..200, "%") {
                        onSettingsChanged(settings.copy(displayScalePercent = it))
                    }
                }
                if (settings.resolutionMode == X11ResolutionMode.EXACT) {
                    SettingsLabel("Fixed size", "${settings.exactWidth} × ${settings.exactHeight}")
                    ChoiceGroup(
                        selected = settings.exactWidth to settings.exactHeight,
                        options =
                            listOf(
                                (1280 to 720) to "720p",
                                (1600 to 900) to "900p",
                                (1920 to 1080) to "1080p",
                            ),
                        onSelected = {
                            onSettingsChanged(settings.copy(exactWidth = it.first, exactHeight = it.second))
                        },
                    )
                }
                ChoiceGroup(
                    selected = settings.displayFilter,
                    options =
                        listOf(
                            X11DisplayFilter.NEAREST to "Sharp",
                            X11DisplayFilter.BILINEAR to "Smooth",
                        ),
                    onSelected = { onSettingsChanged(settings.copy(displayFilter = it)) },
                )
                SettingsSwitch("Stretch to fill", "Fill the screen even if the picture changes shape", settings.stretchDisplay) {
                    onSettingsChanged(settings.copy(stretchDisplay = it))
                }

                SettingsDivider()
                SettingsSectionTitle("Pointer")
                SettingsLabel(
                    "Touch input",
                    "Direct follows your finger. Trackpad moves the pointer relative to your touch. Native sends all touch points to Linux.",
                )
                ChoiceGroup(
                    selected = settings.touchMode,
                    options =
                        listOf(
                            X11TouchMode.DIRECT to "Direct",
                            X11TouchMode.TRACKPAD to "Trackpad",
                            X11TouchMode.NATIVE to "Native",
                        ),
                    onSelected = { onSettingsChanged(settings.copy(touchMode = it)) },
                )
                if (settings.touchMode == X11TouchMode.TRACKPAD) {
                    SettingsSlider("Trackpad speed", settings.trackpadSpeedPercent, 25..300, "%") {
                        onSettingsChanged(settings.copy(trackpadSpeedPercent = it))
                    }
                }

                SettingsDivider()
                SettingsSectionTitle("Keyboard & controls")
                SettingsSwitch("Use hardware keyboard layout", "Let Linux manage the physical keyboard layout", settings.preferScancodes) {
                    onSettingsChanged(settings.copy(preferScancodes = it))
                }
                SettingsSwitch("Keep desktop above keyboard", "Resize Linux when the Android keyboard opens", settings.avoidSoftKeyboard) {
                    onSettingsChanged(settings.copy(avoidSoftKeyboard = it))
                }
                SettingsNavigationRow(
                    "Keyboard strip",
                    if (settings.showKeyboardStrip) {
                        "${settings.overlayKeyRows.size} rows · ${settings.overlayKeyRows.sumOf { it.size }} keys"
                    } else {
                        "Hidden"
                    },
                    onKeyboardStrip,
                )
                Spacer(Modifier.height(8.dp))
                SettingsNavigationRow(
                    "Display buttons",
                    if (settings.displayKeys.isEmpty()) "No floating buttons" else "${settings.displayKeys.size} buttons · position over Linux",
                    onDisplayButtons,
                )

                SettingsDivider()
                SettingsSectionTitle("Session")
                SettingsSwitch("Keep screen on", "Prevent sleep while the desktop is open", settings.keepScreenOn) {
                    onSettingsChanged(settings.copy(keepScreenOn = it))
                }
                SettingsSwitch("Start with controls collapsed", "Show only the compact handle when the desktop opens", settings.startControlsCollapsed) {
                    onSettingsChanged(settings.copy(startControlsCollapsed = it))
                }
            }
        }
    }
}

@Composable
private fun DisplayButtonsSettingsPage(
    settings: X11Settings,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onArrange: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TerminalSettingsHeader("Display buttons", onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Place shortcuts or game controls directly over Linux. Labels and macros use the same format as the keyboard strip.",
                    color = UdroidTerminalMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            itemsIndexed(settings.displayKeys) { index, key ->
                Surface(
                    color = UdroidTerminalSurface,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, UdroidTerminalLine),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(OverlayKeyboard.label(key.binding), fontWeight = FontWeight.SemiBold)
                            Text(if (key.binding.macro) "Macro" else "Key", color = UdroidTerminalMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onEdit(index) }) { Text("Edit") }
                        IconButton(onClick = { onRemove(index) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Remove ${OverlayKeyboard.label(key.binding)}")
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth(), enabled = settings.displayKeys.size < 32) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.padding(4.dp))
                    Text("Add display button")
                }
            }
            if (settings.displayKeys.isNotEmpty()) {
                item {
                    TextButton(onClick = onArrange, modifier = Modifier.fillMaxWidth()) { Text("Arrange on display") }
                }
            }
        }
    }
}

@Composable
private fun SettingsNavigationRow(
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = UdroidTerminalSurface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, UdroidTerminalLine),
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, color = UdroidTerminalMuted, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Open $title", tint = UdroidTerminalMuted)
        }
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(text, color = UdroidTerminalGreen, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun SettingsLabel(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Text(subtitle, color = UdroidTerminalMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun <T> ChoiceGroup(selected: T, options: List<Pair<T, String>>, onSelected: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelected(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label) }
        }
    }
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun SettingsSlider(
    title: String,
    value: Int,
    range: IntRange,
    suffix: String,
    step: Int = 5,
    onChanged: (Int) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Text("$value$suffix", fontFamily = FontFamily.Monospace, color = UdroidTerminalGreen, style = MaterialTheme.typography.labelMedium)
    }
    Slider(
        value = value.toFloat(),
        onValueChange = { onChanged(((it / step).roundToInt() * step).coerceIn(range)) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
    )
}

@Composable
private fun SettingsSwitch(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = UdroidTerminalMuted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 18.dp), color = UdroidTerminalLine)
}
