package org.randomcoder.udroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.randomcoder.udroid.x11.X11DisplayFilter
import org.randomcoder.udroid.x11.X11ResolutionMode
import org.randomcoder.udroid.x11.X11Settings
import org.randomcoder.udroid.x11.X11TouchMode
import kotlin.math.roundToInt

@Composable
fun X11SettingsDialog(
    settings: X11Settings,
    onSettingsChanged: (X11Settings) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        UdroidTerminalTheme {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 32.dp)
                        .widthIn(max = 560.dp)
                        .heightIn(max = 760.dp),
                shape = MaterialTheme.shapes.extraLarge,
                color = UdroidTerminalSurface,
                shadowElevation = 6.dp,
            ) {
                Column {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, top = 12.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Desktop settings",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = "Changes apply live to display :0",
                                color = UdroidTerminalMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close desktop settings",
                            )
                        }
                    }
                    Column(
                        modifier =
                            Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        SettingsSectionTitle("Output")
                        SettingsLabel(
                            title = "Resolution",
                            subtitle = "Native matches your screen. Scaled changes the size of Linux controls.",
                        )
                        ChoiceGroup(
                            selected = settings.resolutionMode,
                            options =
                                listOf(
                                    X11ResolutionMode.NATIVE to "Native",
                                    X11ResolutionMode.SCALED to "Scaled",
                                    X11ResolutionMode.EXACT to "Fixed",
                                ),
                            onSelected = {
                                onSettingsChanged(settings.copy(resolutionMode = it))
                            },
                        )
                        if (settings.resolutionMode == X11ResolutionMode.SCALED) {
                            SettingsSlider(
                                title = "Display scale",
                                value = settings.displayScalePercent,
                                range = 50..200,
                                suffix = "%",
                                onChanged = {
                                    onSettingsChanged(
                                        settings.copy(displayScalePercent = it),
                                    )
                                },
                            )
                        }
                        if (settings.resolutionMode == X11ResolutionMode.EXACT) {
                            SettingsLabel(
                                title = "Fixed size",
                                subtitle = "${settings.exactWidth} × ${settings.exactHeight}",
                            )
                            ChoiceGroup(
                                selected = settings.exactWidth to settings.exactHeight,
                                options =
                                    listOf(
                                        (1280 to 720) to "720p",
                                        (1600 to 900) to "900p",
                                        (1920 to 1080) to "1080p",
                                    ),
                                onSelected = {
                                    onSettingsChanged(
                                        settings.copy(
                                            exactWidth = it.first,
                                            exactHeight = it.second,
                                        ),
                                    )
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
                            onSelected = {
                                onSettingsChanged(settings.copy(displayFilter = it))
                            },
                        )
                        SettingsSwitch(
                            title = "Stretch to fill",
                            subtitle = "Fill the screen even if the picture changes shape",
                            checked = settings.stretchDisplay,
                            onCheckedChange = {
                                onSettingsChanged(settings.copy(stretchDisplay = it))
                            },
                        )

                        SettingsDivider()
                        SettingsSectionTitle("Pointer")
                        SettingsLabel(
                            title = "Touch input",
                            subtitle =
                                "Direct follows your finger. Trackpad moves the pointer relative to your touch. " +
                                    "Native sends all touch points to Linux.",
                        )
                        ChoiceGroup(
                            selected = settings.touchMode,
                            options =
                                listOf(
                                    X11TouchMode.DIRECT to "Direct",
                                    X11TouchMode.TRACKPAD to "Trackpad",
                                    X11TouchMode.NATIVE to "Native",
                                ),
                            onSelected = {
                                onSettingsChanged(settings.copy(touchMode = it))
                            },
                        )
                        if (settings.touchMode == X11TouchMode.TRACKPAD) {
                            SettingsSlider(
                                title = "Trackpad speed",
                                value = settings.trackpadSpeedPercent,
                                range = 25..300,
                                suffix = "%",
                                onChanged = {
                                    onSettingsChanged(
                                        settings.copy(trackpadSpeedPercent = it),
                                    )
                                },
                            )
                        }

                        SettingsDivider()
                        SettingsSectionTitle("Keyboard")
                        SettingsSwitch(
                            title = "Use hardware keyboard layout",
                            subtitle = "Let Linux manage the physical keyboard layout",
                            checked = settings.preferScancodes,
                            onCheckedChange = {
                                onSettingsChanged(settings.copy(preferScancodes = it))
                            },
                        )

                        SettingsDivider()
                        SettingsSectionTitle("Session")
                        SettingsSwitch(
                            title = "Keep screen on",
                            subtitle = "Prevent the screen from sleeping while the desktop is open",
                            checked = settings.keepScreenOn,
                            onCheckedChange = {
                                onSettingsChanged(settings.copy(keepScreenOn = it))
                            },
                        )
                        SettingsSwitch(
                            title = "Start with controls collapsed",
                            subtitle = "Show only the compact handle when the desktop opens",
                            checked = settings.startControlsCollapsed,
                            onCheckedChange = {
                                onSettingsChanged(
                                    settings.copy(startControlsCollapsed = it),
                                )
                            },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text = text,
        color = UdroidTerminalGreen,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

@Composable
private fun SettingsLabel(
    title: String,
    subtitle: String,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text = subtitle,
        color = UdroidTerminalMuted,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun <T> ChoiceGroup(
    selected: T,
    options: List<Pair<T, String>>,
    onSelected: (T) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            val isSelected = value == selected
            SegmentedButton(
                selected = isSelected,
                onClick = { onSelected(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(text = label)
            }
        }
    }
    Spacer(modifier = Modifier.height(14.dp))
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$value$suffix",
            fontFamily = FontFamily.Monospace,
            color = UdroidTerminalGreen,
            style = MaterialTheme.typography.labelMedium,
        )
    }
    Slider(
        value = value.toFloat(),
        onValueChange = {
            val quantized = (it / step).roundToInt() * step
            onChanged(quantized.coerceIn(range))
        },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = ((range.last - range.first) / step - 1).coerceAtLeast(0),
    )
}

@Composable
private fun SettingsSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!checked) }
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = subtitle,
                color = UdroidTerminalMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 18.dp),
        color = UdroidTerminalLine,
    )
}
