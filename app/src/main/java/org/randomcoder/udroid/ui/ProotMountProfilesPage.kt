package org.randomcoder.udroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import org.randomcoder.udroid.catalog.LinuxDistribution
import org.randomcoder.udroid.install.InstallProgress
import org.randomcoder.udroid.runtime.InstalledRootfs
import org.randomcoder.udroid.runtime.PROOT_DEFAULT_MOUNTS
import org.randomcoder.udroid.runtime.ProotCustomMount
import org.randomcoder.udroid.runtime.ProotMountProfile
import org.randomcoder.udroid.runtime.ProotMountProfileStore
import org.randomcoder.udroid.runtime.ProotMountProfileValidator

private data class MountConfigurationItem(
    val systemId: String,
    val profile: ProotMountProfile,
    val installed: Boolean,
    val active: Boolean,
    val setupInProgress: Boolean,
)

@Composable
fun ProotMountConfigurationsPage(
    sourceSystemId: String,
    sourceSystemTitle: String,
    distribution: LinuxDistribution,
    installedRootfses: List<InstalledRootfs>,
    activeRootfsName: String?,
    installProgress: InstallProgress?,
    onBack: () -> Unit,
    onCreateConfiguration: () -> Unit,
    onEditConfiguration: (String) -> Unit,
    onLaunchDistro: (String) -> Unit,
    onDeleteConfiguration: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val store = remember(context) { ProotMountProfileStore(context) }
    val installedIds = installedRootfses.mapTo(mutableSetOf(), InstalledRootfs::name)
    val storedIds = runCatching(store::systemIds).getOrDefault(emptyList())
    val configurationIds =
        linkedSetOf(sourceSystemId).apply {
            storedIds.forEach { systemId ->
                val profile = runCatching { store.load(systemId) }.getOrNull()
                if (profile?.sourceSystemId == sourceSystemId) add(systemId)
            }
            installProgress
                ?.takeIf { progress ->
                    runCatching { store.load(progress.installationName).sourceSystemId }
                        .getOrNull() == sourceSystemId
                }?.installationName
                ?.let(::add)
        }
    val configurations =
        configurationIds
            .map { systemId ->
                val loaded = runCatching { store.load(systemId) }.getOrDefault(ProotMountProfile())
                MountConfigurationItem(
                    systemId = systemId,
                    profile =
                        loaded.copy(
                            sourceSystemId = loaded.sourceSystemId ?: sourceSystemId,
                        ),
                    installed = systemId in installedIds,
                    active = systemId == activeRootfsName,
                    setupInProgress = installProgress?.installationName == systemId,
                )
            }.sortedWith(
                compareByDescending<MountConfigurationItem> { it.systemId == sourceSystemId }
                    .thenByDescending { it.active }
                    .thenBy { it.profile.name.lowercase() },
            )
    var pendingDelete by remember(sourceSystemId) {
        mutableStateOf<MountConfigurationItem?>(null)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "configuration-list-header") {
            Row(
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back to distro",
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
                    Text("Mount configurations", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        sourceSystemTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = UdroidMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        item(key = "configuration-source") {
            Surface(
                color = UdroidRaised,
                border = BorderStroke(1.dp, UdroidLine),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DistroMark(distribution = distribution, size = 44)
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text("Source distro", color = UdroidMuted, style = MaterialTheme.typography.labelMedium)
                        Text(sourceSystemTitle, style = MaterialTheme.typography.titleMedium)
                        Text(
                            sourceSystemId,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = UdroidMuted,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        item(key = "create-configuration") {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onCreateConfiguration,
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Create configuration")
            }
        }

        item(key = "configuration-list-label") {
            UdroidSectionLabel(
                text = "Configurations",
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        items(configurations, key = MountConfigurationItem::systemId) { configuration ->
            MountConfigurationCard(
                configuration = configuration,
                isSource = configuration.systemId == sourceSystemId,
                onLaunch = { onLaunchDistro(configuration.systemId) },
                onEdit = { onEditConfiguration(configuration.systemId) },
                onDelete = { pendingDelete = configuration },
            )
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    pendingDelete?.let { configuration ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${configuration.profile.name}?") },
            text = {
                Text(
                    "This removes the configuration and its attached distro filesystem. " +
                        "This cannot be undone.",
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDeleteConfiguration(configuration.systemId)
                    },
                ) {
                    Text("Delete")
                }
            },
        )
    }
}

@Composable
private fun MountConfigurationCard(
    configuration: MountConfigurationItem,
    isSource: Boolean,
    onLaunch: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val enabledDefaults =
        PROOT_DEFAULT_MOUNTS.count { configuration.profile.isDefaultEnabled(it.id) }
    val enabledCustom = configuration.profile.customMounts.count(ProotCustomMount::enabled)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
        color = UdroidRaised,
        border = BorderStroke(1.dp, UdroidLine),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(configuration.profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        configuration.systemId,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = UdroidMuted,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                UdroidStatusBadge(
                    label =
                        when {
                            configuration.active -> "Active"
                            configuration.installed -> "Ready"
                            configuration.setupInProgress -> "Creating"
                            else -> "Saved"
                        },
                    color = if (configuration.setupInProgress) UdroidWarning else UdroidForest,
                    background =
                        if (configuration.setupInProgress) {
                            UdroidWarningSurface
                        } else {
                            UdroidSoftGreen
                        },
                )
            }
            Text(
                "$enabledDefaults of ${PROOT_DEFAULT_MOUNTS.size} defaults · $enabledCustom custom",
                modifier = Modifier.padding(top = 9.dp),
                color = UdroidMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    enabled = configuration.installed,
                    onClick = onLaunch,
                    shape = RoundedCornerShape(9.dp),
                ) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Open distro")
                }
                OutlinedButton(onClick = onEdit, shape = RoundedCornerShape(9.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Edit")
                }
                if (!isSource && configuration.installed) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Outlined.DeleteOutline,
                            contentDescription = "Delete configuration",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ProotMountConfigurationEditorPage(
    sourceSystemId: String,
    configurationSystemId: String?,
    systemTitle: String,
    distribution: LinuxDistribution,
    active: Boolean,
    editingEnabled: Boolean,
    externalMessage: String?,
    onBack: () -> Unit,
    onCreateDistro: (ProotMountProfile) -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { ProotMountProfileStore(context) }
    val creating = configurationSystemId == null
    val initialProfile =
        remember(sourceSystemId, configurationSystemId) {
            if (configurationSystemId == null) {
                runCatching { store.load(sourceSystemId) }
                    .getOrDefault(ProotMountProfile())
                    .independentCopy()
                    .copy(name = "", sourceSystemId = sourceSystemId)
            } else {
                runCatching { store.load(configurationSystemId) }
                    .getOrDefault(ProotMountProfile())
                    .copy(sourceSystemId = sourceSystemId)
            }
        }
    var persistedProfile by remember(sourceSystemId, configurationSystemId) {
        mutableStateOf(initialProfile)
    }
    var draft by remember(sourceSystemId, configurationSystemId) { mutableStateOf(initialProfile) }
    var message by remember(sourceSystemId, configurationSystemId) { mutableStateOf<String?>(null) }
    var saving by remember(sourceSystemId, configurationSystemId) { mutableStateOf(false) }
    var confirmDiscard by remember(sourceSystemId, configurationSystemId) {
        mutableStateOf(false)
    }
    val scope = rememberCoroutineScope()
    val dirty = draft != persistedProfile

    fun requestBack() {
        if (dirty) confirmDiscard = true else onBack()
    }

    fun submit() {
        if (!editingEnabled || saving) return
        val validated =
            runCatching {
                ProotMountProfileValidator.requireValid(
                    draft.copy(sourceSystemId = sourceSystemId),
                )
            }.onFailure { message = it.message ?: "The configuration is invalid" }
                .getOrNull() ?: return
        saving = true
        if (creating) {
            persistedProfile = validated
            draft = validated
            message = "Configuration ready. Preparing the attached distro…"
            saving = false
            onCreateDistro(validated)
        } else {
            scope.launch {
                val result =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            store.save(configurationSystemId, validated)
                        }
                    }
                saving = false
                result.fold(
                    onSuccess = { saved ->
                        persistedProfile = saved
                        draft = saved
                        message = "Configuration saved. It applies on the next launch."
                    },
                    onFailure = { error ->
                        message = error.message ?: "The configuration could not be saved"
                    },
                )
            }
        }
    }

    BackHandler(onBack = ::requestBack)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "configuration-editor-header") {
            Row(
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = ::requestBack) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back to configurations",
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
                    Text(
                        if (creating) "Create configuration" else "Edit configuration",
                        style = MaterialTheme.typography.headlineSmall,
                    )
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

        item(key = "configuration-attached-distro-label") {
            UdroidSectionLabel(text = if (creating) "Source distro" else "Attached distro")
        }

        item(key = "configuration-attached-distro") {
            Surface(
                color = UdroidRaised,
                border = BorderStroke(1.dp, UdroidLine),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DistroMark(distribution = distribution, size = 44)
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(systemTitle, style = MaterialTheme.typography.titleMedium)
                        Text(
                            configurationSystemId ?: sourceSystemId,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = UdroidMuted,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (!creating) {
                        UdroidStatusBadge(
                            label = if (active) "Active" else "Installed",
                            color = UdroidForest,
                            background = UdroidSoftGreen,
                        )
                    }
                }
            }
        }

        item(key = "configuration-name") {
            OutlinedTextField(
                value = draft.name,
                enabled = editingEnabled,
                onValueChange = {
                    draft = draft.copy(name = it)
                    message = null
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Configuration name") },
                supportingText = {
                    Text("This name identifies both the profile and its attached distro.")
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
            )
        }

        if (!editingEnabled) {
            item(key = "configuration-editor-locked") {
                Surface(color = UdroidWarningSurface, shape = RoundedCornerShape(10.dp)) {
                    Text(
                        if (creating) {
                            "Finish the current Linux setup before creating another configuration."
                        } else {
                            "Stop the attached distro before changing this configuration."
                        },
                        modifier = Modifier.padding(12.dp),
                        color = UdroidWarning,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item(key = "configuration-warning") {
            Surface(color = UdroidWarningSurface, shape = RoundedCornerShape(10.dp)) {
                Text(
                    "Mappings are applied exactly as saved. Removing /sys, /proc, or another " +
                        "system path can intentionally prevent Linux from starting; uDroid keeps " +
                        "the configuration and reports the crash.",
                    modifier = Modifier.padding(12.dp),
                    color = UdroidWarning,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item(key = "configuration-defaults-label") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Mount mappings", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${PROOT_DEFAULT_MOUNTS.count { draft.isDefaultEnabled(it.id) }} of " +
                            "${PROOT_DEFAULT_MOUNTS.size} uDroid defaults enabled",
                        color = UdroidMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(
                    enabled = editingEnabled,
                    onClick = {
                        draft =
                            ProotMountProfile(
                                name = draft.name,
                                sourceSystemId = sourceSystemId,
                            )
                        message = null
                    },
                ) {
                    Icon(Icons.Outlined.RestartAlt, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("Restore")
                }
            }
        }

        item(key = "configuration-defaults") {
            Surface(
                color = Color.Transparent,
                border = BorderStroke(1.dp, UdroidLine),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column {
                    PROOT_DEFAULT_MOUNTS.forEachIndexed { index, mount ->
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    mount.guestTarget,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    mount.label,
                                    color = UdroidMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(
                                checked = draft.isDefaultEnabled(mount.id),
                                enabled = editingEnabled,
                                onCheckedChange = { enabled ->
                                    draft = draft.withDefaultEnabled(mount.id, enabled)
                                    message = null
                                },
                            )
                        }
                        if (index != PROOT_DEFAULT_MOUNTS.lastIndex) {
                            Divider(color = UdroidLine)
                        }
                    }
                }
            }
        }

        item(key = "configuration-custom-label") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Custom mappings", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Absolute host path to absolute guest destination",
                        color = UdroidMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(
                    enabled = editingEnabled,
                    onClick = {
                        draft =
                            draft.copy(
                                customMounts =
                                    draft.customMounts +
                                        ProotCustomMount(hostSource = "", guestTarget = ""),
                            )
                        message = null
                    },
                    shape = RoundedCornerShape(9.dp),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("Add")
                }
            }
        }

        if (draft.customMounts.isEmpty()) {
            item(key = "configuration-custom-empty") {
                Surface(color = UdroidInset, shape = RoundedCornerShape(11.dp)) {
                    Text(
                        "No custom mappings. Add one when this configuration needs another path.",
                        modifier = Modifier.padding(14.dp),
                        color = UdroidMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            items(draft.customMounts, key = ProotCustomMount::id) { mount ->
                CustomMountEditor(
                    mount = mount,
                    enabled = editingEnabled,
                    onChange = { changed ->
                        draft = draft.updateCustomMount(mount.id) { changed }
                        message = null
                    },
                    onDelete = {
                        draft =
                            draft.copy(
                                customMounts =
                                    draft.customMounts.filterNot { it.id == mount.id },
                            )
                        message = null
                    },
                )
            }
        }

        (message ?: externalMessage)?.let { visibleMessage ->
            item(key = "configuration-message") {
                Text(
                    visibleMessage,
                    color =
                        if (visibleMessage.startsWith("Configuration saved")) {
                            UdroidForest
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item(key = "configuration-submit") {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = editingEnabled && !saving && (creating || dirty),
                onClick = ::submit,
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    when {
                        saving -> "Saving…"
                        creating -> "Create distro"
                        else -> "Save configuration"
                    },
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = {
                Text(if (creating) "Discard configuration?" else "Unsaved changes")
            },
            text = {
                Text(
                    if (creating) {
                        "This new mount configuration has not been created yet."
                    } else {
                        "Your changes to this configuration have not been saved."
                    },
                )
            },
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
@OptIn(ExperimentalMaterial3Api::class)
private fun CustomMountEditor(
    mount: ProotCustomMount,
    enabled: Boolean,
    onChange: (ProotCustomMount) -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = UdroidInset, shape = RoundedCornerShape(11.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Mapping",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                )
                Switch(
                    checked = mount.enabled,
                    enabled = enabled,
                    onCheckedChange = { onChange(mount.copy(enabled = it)) },
                )
                IconButton(enabled = enabled, onClick = onDelete) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "Delete mapping",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            OutlinedTextField(
                value = mount.hostSource,
                enabled = enabled,
                onValueChange = { onChange(mount.copy(hostSource = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Host source") },
                placeholder = { Text("/data/local/project") },
                singleLine = true,
                textStyle =
                    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = mount.guestTarget,
                enabled = enabled,
                onValueChange = { onChange(mount.copy(guestTarget = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Guest destination") },
                placeholder = { Text("/workspace") },
                singleLine = true,
                textStyle =
                    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

private fun ProotMountProfile.updateCustomMount(
    id: String,
    update: (ProotCustomMount) -> ProotCustomMount,
): ProotMountProfile =
    copy(customMounts = customMounts.map { if (it.id == id) update(it) else it })
