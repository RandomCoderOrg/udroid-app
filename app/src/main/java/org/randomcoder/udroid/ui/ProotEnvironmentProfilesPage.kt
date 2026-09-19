package org.randomcoder.udroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.randomcoder.udroid.audio.AudioEndpoint
import org.randomcoder.udroid.runtime.PROOT_DEFAULT_ENVIRONMENT_VARIABLES
import org.randomcoder.udroid.runtime.PROOT_LOCKED_ENVIRONMENT_VARIABLE_NAMES
import org.randomcoder.udroid.runtime.DesktopConfiguration
import org.randomcoder.udroid.runtime.DesktopEnvironment
import org.randomcoder.udroid.runtime.DesktopGraphicsProfile
import org.randomcoder.udroid.runtime.ProotCustomEnvironmentVariable
import org.randomcoder.udroid.runtime.ProotEnvironmentProfile
import org.randomcoder.udroid.runtime.ProotEnvironmentProfileStore
import org.randomcoder.udroid.runtime.ProotEnvironmentProfileValidator
import org.randomcoder.udroid.runtime.ProotTerminalLaunchBuilder
import java.io.File

@Composable
fun ProotEnvironmentProfilePage(
    systemId: String,
    systemTitle: String,
    rootfsDirectory: File,
    desktopEnvironment: DesktopEnvironment?,
    desktopConfiguration: DesktopConfiguration,
    runtimeRunning: Boolean,
    desktopRunning: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { ProotEnvironmentProfileStore(context) }
    val initialProfile =
        remember(systemId) {
            runCatching { store.load(systemId) }.getOrDefault(ProotEnvironmentProfile())
        }
    var persistedProfile by remember(systemId) { mutableStateOf(initialProfile) }
    var draft by remember(systemId) { mutableStateOf(initialProfile) }
    var message by remember(systemId) { mutableStateOf<String?>(null) }
    var saving by remember(systemId) { mutableStateOf(false) }
    var confirmDiscard by remember(systemId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dirty = draft != persistedProfile
    val managedValues =
        remember(rootfsDirectory, desktopEnvironment, desktopConfiguration) {
            automaticManagedEnvironmentValues(
                rootfsDirectory = rootfsDirectory,
                desktopEnvironment = desktopEnvironment,
                desktopConfiguration = desktopConfiguration,
            )
        }

    fun requestBack() {
        if (dirty) confirmDiscard = true else onBack()
    }

    fun save() {
        if (saving || !dirty) return
        val validated =
            runCatching { ProotEnvironmentProfileValidator.requireValid(draft) }
                .onFailure { message = it.message ?: "The environment profile is invalid" }
                .getOrNull() ?: return
        saving = true
        scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { store.save(systemId, validated) } }
            saving = false
            result.fold(
                onSuccess = {
                    persistedProfile = it
                    draft = it
                    message = "Environment saved. New launches use these values."
                },
                onFailure = {
                    message = it.message ?: "The environment could not be saved"
                },
            )
        }
    }

    BackHandler(onBack = ::requestBack)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "environment-header") {
            Row(
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = ::requestBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to Linux system",
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
                    Text("Environment variables", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        systemTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = UdroidMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        item(key = "environment-apply-note") {
            Surface(color = UdroidInset, shape = MaterialTheme.shapes.medium) {
                Text(
                    launchEffectText(runtimeRunning, desktopRunning),
                    modifier = Modifier.padding(12.dp),
                    color = UdroidMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item(key = "environment-secret-warning") {
            Text(
                "Do not store passwords, tokens, or other secrets here. Values are saved as " +
                    "plain app data and passed directly to launched processes.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        item(key = "environment-defaults-label") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Launch defaults", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${draft.defaultOverrides.size} changed",
                        color = UdroidMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(
                    enabled = draft.defaultOverrides.isNotEmpty(),
                    onClick = {
                        draft = draft.copy(defaultOverrides = emptyMap())
                        message = null
                    },
                ) {
                    Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("Restore defaults")
                }
            }
        }

        items(
            items = PROOT_DEFAULT_ENVIRONMENT_VARIABLES,
            key = { "default-${it.name}" },
        ) { variable ->
            val value = draft.defaultOverrides[variable.name] ?: variable.defaultValue
            OutlinedTextField(
                value = value,
                onValueChange = { changed ->
                    val overrides = draft.defaultOverrides.toMutableMap()
                    if (changed == variable.defaultValue) {
                        overrides.remove(variable.name)
                    } else {
                        overrides[variable.name] = changed
                    }
                    draft = draft.copy(defaultOverrides = overrides)
                    message = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(variable.name) },
                supportingText = {
                    if (variable.name in draft.defaultOverrides) {
                        TextButton(
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                            onClick = {
                                draft =
                                    draft.copy(
                                        defaultOverrides = draft.defaultOverrides - variable.name,
                                    )
                                message = null
                            },
                        ) {
                            Text("Restore ${variable.defaultValue}")
                        }
                    } else {
                        Text("Default")
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
            )
        }

        item(key = "environment-custom-label") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Custom variables", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Added to every new launch",
                        color = UdroidMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(
                    onClick = {
                        draft =
                            draft.copy(
                                customVariables =
                                    draft.customVariables +
                                        ProotCustomEnvironmentVariable(name = "", value = ""),
                            )
                        message = null
                    },
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("Add")
                }
            }
        }

        if (draft.customVariables.isEmpty()) {
            item(key = "environment-custom-empty") {
                Surface(color = UdroidInset, shape = MaterialTheme.shapes.medium) {
                    Text(
                        "No custom variables added.",
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        color = UdroidMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        items(draft.customVariables, key = ProotCustomEnvironmentVariable::id) { variable ->
            CustomEnvironmentVariableEditor(
                variable = variable,
                onChange = { changed ->
                    draft =
                        draft.copy(
                            customVariables =
                                draft.customVariables.map {
                                    if (it.id == variable.id) changed else it
                                },
                        )
                    message = null
                },
                onDelete = {
                    draft =
                        draft.copy(
                            customVariables =
                                draft.customVariables.filterNot { it.id == variable.id },
                        )
                    message = null
                },
            )
        }

        item(key = "environment-managed-label") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Managed by uDroid", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${draft.managedOverrides.size} overridden · Overrides apply last",
                        color = UdroidMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(
                    enabled = draft.managedOverrides.isNotEmpty(),
                    onClick = {
                        draft = draft.copy(managedOverrides = emptyMap())
                        message = null
                    },
                ) {
                    Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("Reset all")
                }
            }
        }

        item(key = "environment-managed-list") {
            Surface(
                color = Color.Transparent,
                border = BorderStroke(1.dp, UdroidLine),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column {
                    PROOT_LOCKED_ENVIRONMENT_VARIABLE_NAMES
                        .sorted()
                        .forEachIndexed { index, name ->
                            ManagedEnvironmentVariableEditor(
                                name = name,
                                automaticValue = managedValues.getValue(name),
                                overrideValue = draft.managedOverrides[name],
                                overridden = name in draft.managedOverrides,
                                onOverride = {
                                    draft =
                                        draft.copy(
                                            managedOverrides =
                                                draft.managedOverrides +
                                                    (name to managedValues.getValue(name).suggestedOverride),
                                        )
                                    message = null
                                },
                                onChange = { value ->
                                    draft =
                                        draft.copy(
                                            managedOverrides = draft.managedOverrides + (name to value),
                                        )
                                    message = null
                                },
                                onReset = {
                                    draft =
                                        draft.copy(
                                            managedOverrides = draft.managedOverrides - name,
                                        )
                                    message = null
                                },
                            )
                            if (index != PROOT_LOCKED_ENVIRONMENT_VARIABLE_NAMES.size - 1) {
                                HorizontalDivider(color = UdroidLine)
                            }
                        }
                }
            }
        }

        message?.let { visibleMessage ->
            item(key = "environment-message") {
                Text(
                    visibleMessage,
                    color =
                        if (visibleMessage.startsWith("Environment saved")) {
                            UdroidForest
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item(key = "environment-save") {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = dirty && !saving,
                onClick = ::save,
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(if (saving) "Saving…" else "Save changes")
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Unsaved changes") },
            text = { Text("Your environment variable changes have not been saved.") },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") }
            },
            confirmButton = {
                TextButton(onClick = onBack) { Text("Discard") }
            },
        )
    }
}

@Composable
private fun CustomEnvironmentVariableEditor(
    variable: ProotCustomEnvironmentVariable,
    onChange: (ProotCustomEnvironmentVariable) -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = Color.Transparent,
        border = BorderStroke(1.dp, UdroidLine),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Variable", modifier = Modifier.weight(1f))
                TextButton(onClick = onDelete) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Delete")
                }
            }
            OutlinedTextField(
                value = variable.name,
                onValueChange = { onChange(variable.copy(name = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Name") },
                singleLine = true,
            )
            OutlinedTextField(
                value = variable.value,
                onValueChange = { onChange(variable.copy(value = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Value") },
                singleLine = true,
            )
        }
    }
}

@Composable
private fun ManagedEnvironmentVariableEditor(
    name: String,
    automaticValue: ManagedEnvironmentValue,
    overrideValue: String?,
    overridden: Boolean,
    onOverride: () -> Unit,
    onChange: (String) -> Unit,
    onReset: () -> Unit,
) {
    if (!overridden) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(name, fontFamily = FontFamily.Monospace) },
            supportingContent = {
                Text(automaticValue.display, fontFamily = FontFamily.Monospace)
            },
            trailingContent = { TextButton(onClick = onOverride) { Text("Override") } },
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace)
            TextButton(onClick = onReset) {
                Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Reset")
            }
        }
        OutlinedTextField(
            value = overrideValue.orEmpty(),
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Override value") },
            supportingText = {
                Text("uDroid: ${automaticValue.display}", fontFamily = FontFamily.Monospace)
            },
            singleLine = true,
        )
    }
}

private fun launchEffectText(
    runtimeRunning: Boolean,
    desktopRunning: Boolean,
): String =
    when {
        runtimeRunning && desktopRunning ->
            "New app and desktop launches use saved changes. Restart Linux for the current " +
                "terminal. Existing processes are unchanged."
        runtimeRunning ->
            "New app and desktop launches use saved changes. Restart Linux for the current " +
                "terminal. Existing processes are unchanged."
        desktopRunning ->
            "New launches use saved changes. Restart the desktop to update its current session. " +
                "Existing processes are unchanged."
        else -> "New terminal, app, and desktop launches use saved changes."
    }

private data class ManagedEnvironmentValue(
    val display: String,
    val suggestedOverride: String,
)

private fun automaticManagedEnvironmentValues(
    rootfsDirectory: File,
    desktopEnvironment: DesktopEnvironment?,
    desktopConfiguration: DesktopConfiguration,
): Map<String, ManagedEnvironmentValue> {
    val guestHome = if (File(rootfsDirectory, "root").isDirectory) "/root" else "/"
    val guestShell = ProotTerminalLaunchBuilder.findGuestShell(rootfsDirectory) ?: "/bin/sh"
    val desktopName = desktopEnvironment?.kind?.desktopName
    val desktopId = desktopEnvironment?.id
    val values =
        PROOT_LOCKED_ENVIRONMENT_VARIABLE_NAMES.associateWith {
            ManagedEnvironmentValue("<unset>", "")
        }.toMutableMap()

    fun set(
        name: String,
        value: String,
        display: String = value,
    ) {
        values[name] = ManagedEnvironmentValue(display, value)
    }

    set("HOME", guestHome)
    set("USER", "root")
    set("LOGNAME", "root")
    set(
        "SHELL",
        guestShell,
        if (guestShell == "/bin/sh") "/bin/sh" else "terminal=$guestShell · apps/desktops=/bin/sh",
    )
    set("DISPLAY", ":0")
    set("PULSE_SERVER", AudioEndpoint.GUEST_SERVER)
    set(
        "PULSE_COOKIE",
        "${AudioEndpoint.GUEST_AUTH_DIRECTORY}/${AudioEndpoint.COOKIE_NAME}",
    )
    set("XDG_SESSION_TYPE", "x11", "desktop=x11")
    set(
        "XDG_CURRENT_DESKTOP",
        desktopName ?: "UDROID",
        "apps=UDROID · desktop=${desktopName ?: "<unset>"}",
    )
    if (desktopId != null) set("DESKTOP_SESSION", desktopId, "desktop=$desktopId")
    set("GDK_BACKEND", "x11", "apps/desktops=x11")
    set("QT_QPA_PLATFORM", "xcb", "apps/desktops=xcb")
    if (desktopConfiguration.touchScaleEnabled) {
        set("GDK_SCALE", "2", "desktop=2")
        set("QT_SCALE_FACTOR", "2", "desktop=2")
        set("XCURSOR_SIZE", "48", "desktop=48")
    }

    when (desktopConfiguration.graphicsProfile) {
        DesktopGraphicsProfile.STANDARD -> Unit
        DesktopGraphicsProfile.SOFTWARE -> {
            set("LIBGL_ALWAYS_SOFTWARE", "1")
            set("GALLIUM_DRIVER", "llvmpipe")
        }
        DesktopGraphicsProfile.ZINK -> {
            set("MESA_LOADER_DRIVER_OVERRIDE", "zink")
            set("GALLIUM_DRIVER", "zink")
            set("LIBGL_KOPPER_DRI2", "true")
        }
        DesktopGraphicsProfile.VIRGL,
        DesktopGraphicsProfile.VIRGL_ANGLE,
        -> set("GALLIUM_DRIVER", "virpipe")
        DesktopGraphicsProfile.GFXSTREAM_EXPERIMENTAL -> Unit
    }
    return values
}
