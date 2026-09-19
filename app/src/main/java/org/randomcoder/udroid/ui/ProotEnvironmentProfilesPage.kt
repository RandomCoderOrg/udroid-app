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
import org.randomcoder.udroid.runtime.PROOT_DEFAULT_ENVIRONMENT_VARIABLES
import org.randomcoder.udroid.runtime.PROOT_LOCKED_ENVIRONMENT_VARIABLE_NAMES
import org.randomcoder.udroid.runtime.ProotCustomEnvironmentVariable
import org.randomcoder.udroid.runtime.ProotEnvironmentProfile
import org.randomcoder.udroid.runtime.ProotEnvironmentProfileStore
import org.randomcoder.udroid.runtime.ProotEnvironmentProfileValidator

@Composable
fun ProotEnvironmentProfilePage(
    systemId: String,
    systemTitle: String,
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
            Column {
                Text("Managed by uDroid", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Identity, display, audio, desktop, and graphics values follow app settings.",
                    color = UdroidMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
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
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = {
                                    Text(name, fontFamily = FontFamily.Monospace)
                                },
                                supportingContent = { Text(managedVariableOwner(name)) },
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

private fun managedVariableOwner(name: String): String =
    when (name) {
        "HOME", "USER", "LOGNAME", "SHELL" -> "Linux identity"
        "DISPLAY" -> "Display session"
        "PULSE_SERVER", "PULSE_COOKIE" -> "Audio"
        "XDG_SESSION_TYPE", "XDG_CURRENT_DESKTOP", "DESKTOP_SESSION" -> "Desktop session"
        else -> "Graphics"
    }
