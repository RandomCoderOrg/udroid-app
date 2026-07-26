package org.randomcoder.udroid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
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
                shape = RoundedCornerShape(24.dp),
                color = UdroidTerminalSurface,
                tonalElevation = 8.dp,
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
                                imageVector = Icons.Outlined.Close,
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
                        SettingsSectionTitle("OUTPUT")
                        SettingsLabel(
                            title = "Resolution",
                            subtitle = "Native follows the Android surface; scaled changes Linux UI size.",
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
                            subtitle = "Ignore aspect ratio and use the complete surface.",
                            checked = settings.stretchDisplay,
                            onCheckedChange = {
                                onSettingsChanged(settings.copy(stretchDisplay = it))
                            },
                        )

                        SettingsDivider()
                        SettingsSectionTitle("POINTER")
                        SettingsLabel(
                            title = "Touch input",
                            subtitle =
                                "Direct controls the pointer. Trackpad moves relatively. " +
                                    "Native sends every contact to Linux.",
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
                        SettingsSectionTitle("KEYBOARD")
                        SettingsSwitch(
                            title = "Prefer hardware scancodes",
                            subtitle = "Let the Linux desktop handle the physical keyboard layout.",
                            checked = settings.preferScancodes,
                            onCheckedChange = {
                                onSettingsChanged(settings.copy(preferScancodes = it))
                            },
                        )

                        SettingsDivider()
                        SettingsSectionTitle("SESSION")
                        SettingsSwitch(
                            title = "Keep screen on",
                            subtitle = "Prevent display sleep while the desktop surface is open.",
                            checked = settings.keepScreenOn,
                            onCheckedChange = {
                                onSettingsChanged(settings.copy(keepScreenOn = it))
                            },
                        )
                        SettingsSwitch(
                            title = "Start with controls collapsed",
                            subtitle = "Use the compact handle when opening Desktop.",
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
private fun <T> ChoiceGroup(
    selected: T,
    options: List<Pair<T, String>>,
    onSelected: (T) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Surface(
                modifier =
                    Modifier
                        .weight(1f)
                        .clickable { onSelected(value) },
                shape = RoundedCornerShape(12.dp),
                color =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        UdroidTerminalRaised
                    },
                contentColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        UdroidTerminalText
                    },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
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
    Divider(
        modifier = Modifier.padding(vertical = 18.dp),
        color = UdroidTerminalLine,
    )
}
