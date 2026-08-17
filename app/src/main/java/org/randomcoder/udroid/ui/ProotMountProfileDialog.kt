package org.randomcoder.udroid.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.randomcoder.udroid.runtime.PROOT_DEFAULT_MOUNTS
import org.randomcoder.udroid.runtime.ProotCustomMount
import org.randomcoder.udroid.runtime.ProotMountProfile
import org.randomcoder.udroid.runtime.ProotMountProfileValidator

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ProotMountProfileDialog(
    systemName: String,
    initialProfile: ProotMountProfile,
    onDismiss: () -> Unit,
    onSave: (ProotMountProfile) -> Unit,
) {
    var draft by remember(systemName, initialProfile) { mutableStateOf(initialProfile) }
    var validationMessage by remember(systemName) { mutableStateOf<String?>(null) }
    val disabledDefaults = PROOT_DEFAULT_MOUNTS.count { !draft.isDefaultEnabled(it.id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Mount mappings")
                Text(
                    systemName,
                    color = UdroidMuted,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
            }
        },
        text = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = UdroidWarningSurface,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        "This profile is applied exactly as saved. Disabling system paths " +
                            "may prevent Linux from starting; uDroid will keep the profile " +
                            "and report the crash.",
                        modifier = Modifier.padding(12.dp),
                        color = UdroidWarning,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }

                Column {
                    Text(
                        "uDroid defaults",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "${PROOT_DEFAULT_MOUNTS.size - disabledDefaults} of " +
                            "${PROOT_DEFAULT_MOUNTS.size} enabled",
                        color = UdroidMuted,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }

                Surface(
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    border = BorderStroke(1.dp, UdroidLine),
                    shape = RoundedCornerShape(11.dp),
                ) {
                    Column {
                        PROOT_DEFAULT_MOUNTS.forEachIndexed { index, mount ->
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        mount.guestTarget,
                                        fontFamily = FontFamily.Monospace,
                                        style =
                                            androidx.compose.material3.MaterialTheme.typography
                                                .bodyMedium,
                                    )
                                    Text(
                                        mount.label,
                                        color = UdroidMuted,
                                        style =
                                            androidx.compose.material3.MaterialTheme.typography
                                                .bodySmall,
                                    )
                                }
                                Switch(
                                    checked = draft.isDefaultEnabled(mount.id),
                                    onCheckedChange = { enabled ->
                                        draft = draft.withDefaultEnabled(mount.id, enabled)
                                        validationMessage = null
                                    },
                                )
                            }
                            if (index != PROOT_DEFAULT_MOUNTS.lastIndex) {
                                Divider(color = UdroidLine)
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Custom mappings",
                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Absolute host path to absolute guest path",
                            color = UdroidMuted,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(
                        onClick = {
                            draft =
                                draft.copy(
                                    customMounts =
                                        draft.customMounts +
                                            ProotCustomMount(
                                                hostSource = "",
                                                guestTarget = "",
                                            ),
                                )
                            validationMessage = null
                        },
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Text("Add")
                    }
                }

                draft.customMounts.forEach { mount ->
                    Surface(
                        color = UdroidInset,
                        shape = RoundedCornerShape(11.dp),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Mapping",
                                    modifier = Modifier.weight(1f),
                                    style =
                                        androidx.compose.material3.MaterialTheme.typography
                                            .labelLarge,
                                )
                                Switch(
                                    checked = mount.enabled,
                                    onCheckedChange = { enabled ->
                                        draft =
                                            draft.copy(
                                                customMounts =
                                                    draft.customMounts.map {
                                                        if (it.id == mount.id) {
                                                            it.copy(enabled = enabled)
                                                        } else {
                                                            it
                                                        }
                                                    },
                                            )
                                        validationMessage = null
                                    },
                                )
                                IconButton(
                                    onClick = {
                                        draft =
                                            draft.copy(
                                                customMounts =
                                                    draft.customMounts.filterNot {
                                                        it.id == mount.id
                                                    },
                                            )
                                        validationMessage = null
                                    },
                                ) {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = "Delete mapping",
                                        tint = androidx.compose.material3.MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            OutlinedTextField(
                                value = mount.hostSource,
                                onValueChange = { value ->
                                    draft = draft.updateMount(mount.id) { it.copy(hostSource = value) }
                                    validationMessage = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Host source") },
                                placeholder = { Text("/storage/emulated/0/Projects") },
                                singleLine = true,
                                textStyle =
                                    androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = mount.guestTarget,
                                onValueChange = { value ->
                                    draft = draft.updateMount(mount.id) { it.copy(guestTarget = value) }
                                    validationMessage = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Guest destination") },
                                placeholder = { Text("/workspace") },
                                singleLine = true,
                                textStyle =
                                    androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                    ),
                            )
                        }
                    }
                }

                TextButton(
                    onClick = {
                        draft = ProotMountProfile()
                        validationMessage = null
                    },
                ) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                    Text("Restore uDroid defaults")
                }

                validationMessage?.let {
                    Text(
                        it,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    runCatching { ProotMountProfileValidator.requireValid(draft) }
                        .onSuccess(onSave)
                        .onFailure {
                            validationMessage = it.message ?: "The mount profile is invalid"
                        }
                },
            ) {
                Text("Save profile")
            }
        },
    )
}

private fun ProotMountProfile.updateMount(
    id: String,
    update: (ProotCustomMount) -> ProotCustomMount,
): ProotMountProfile =
    copy(
        customMounts = customMounts.map { if (it.id == id) update(it) else it },
    )
