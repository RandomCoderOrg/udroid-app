package org.randomcoder.udroid.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.randomcoder.udroid.audio.AudioEndpoint
import org.randomcoder.udroid.catalog.LinuxDistribution
import org.randomcoder.udroid.gfxstream.GfxstreamGuestRuntime
import org.randomcoder.udroid.install.InstallProgress
import org.randomcoder.udroid.runtime.GFXSTREAM_PROFILE_ENABLED
import org.randomcoder.udroid.runtime.AndroidStorageMounts
import org.randomcoder.udroid.runtime.AndroidStorageVolume
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

private data class AutomaticMountInfo(
    val guestTarget: String,
    val purpose: String,
    val owner: String,
)

private val AUTOMATIC_SESSION_MOUNTS =
    buildList {
        add(AutomaticMountInfo("/tmp/.X11-unix", "X11 socket", "Display"))
        add(AutomaticMountInfo("/tmp/.X0-lock", "X11 display lock", "Display"))
        add(AutomaticMountInfo(AudioEndpoint.GUEST_AUTH_DIRECTORY, "PulseAudio authentication", "Audio"))
        if (GFXSTREAM_PROFILE_ENABLED) {
            add(AutomaticMountInfo(GfxstreamGuestRuntime.GUEST_DIRECTORY, "Graphics runtime", "Acceleration"))
            add(AutomaticMountInfo(GfxstreamGuestRuntime.GUEST_GPU_SOCKET, "Graphics socket", "Acceleration"))
        }
    }

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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "configuration-list-header") {
            Row(
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to distro",
                    )
                }
                DistroMark(distribution = distribution, size = 38)
                Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
                    Text("File access", style = MaterialTheme.typography.headlineSmall)
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

        item(key = "create-configuration") {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onCreateConfiguration,
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("New file setup")
            }
        }

        item(key = "configuration-list-label") {
            UdroidSectionLabel(
                text = "Linux systems",
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
        shape = MaterialTheme.shapes.large,
    ) {
        Column {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = {
                    Text(configuration.profile.name.ifBlank { "Default" })
                },
                supportingContent = {
                    Text("$enabledDefaults system paths · $enabledCustom added folders")
                },
                leadingContent = {
                    Icon(Icons.Rounded.Folder, contentDescription = null)
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                                if (configuration.setupInProgress) UdroidWarningSurface else UdroidSoftGreen,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null)
                    }
                },
            )
            HorizontalDivider(color = UdroidLine)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    enabled = configuration.installed,
                    onClick = onLaunch,
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Open")
                }
                if (!isSource && configuration.installed) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = "Delete file setup",
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
    onOpenSessionFeatures: () -> Unit,
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
    var openSessionFeaturesAfterDiscard by remember(sourceSystemId, configurationSystemId) {
        mutableStateOf(false)
    }
    val scope = rememberCoroutineScope()
    val dirty = draft != persistedProfile

    fun requestBack() {
        openSessionFeaturesAfterDiscard = false
        if (dirty) confirmDiscard = true else onBack()
    }

    fun requestSessionFeatures() {
        openSessionFeaturesAfterDiscard = true
        if (dirty) confirmDiscard = true else onOpenSessionFeatures()
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
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to configurations",
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
                    Text(
                        if (creating) "New file setup" else "File access",
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
            UdroidSectionLabel(text = "Linux system")
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
                        val systemId = configurationSystemId ?: sourceSystemId
                        if (systemTitle != systemId) {
                            Text(
                                systemId,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = UdroidMuted,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
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
                label = { Text("Setup name") },
                supportingText = {
                    Text("Used to identify this Linux system.")
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
                        "Finish the current Linux setup before creating another one."
                        } else {
                            "Stop this Linux system before changing its file access."
                        },
                        modifier = Modifier.padding(12.dp),
                        color = UdroidWarning,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        item(key = "configuration-warning") {
            Surface(color = UdroidInset, shape = MaterialTheme.shapes.medium) {
                Text(
                    "System paths are needed for Linux to start. Change them only when you know " +
                        "the app you are running needs different access.",
                    modifier = Modifier.padding(12.dp),
                    color = UdroidMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        item(key = "configuration-defaults-label") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("System access", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${PROOT_DEFAULT_MOUNTS.count { draft.isDefaultEnabled(it.id) }} of " +
                            "${PROOT_DEFAULT_MOUNTS.size} recommended paths enabled",
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
                    Icon(Icons.Rounded.RestartAlt, contentDescription = null)
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
                            HorizontalDivider(color = UdroidLine)
                        }
                    }
                }
            }
        }

        item(key = "configuration-android-storage") {
            AndroidStorageMountsCard(
                enabled = editingEnabled,
                mounts = draft.customMounts,
                onAdd = { mount ->
                    draft = draft.copy(customMounts = draft.customMounts + mount)
                    message = "${mount.hostSource} will be available at ${mount.guestTarget}"
                },
                onMessage = { message = it },
            )
        }

        item(key = "configuration-custom-label") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Custom mounts", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Map any Android path into Linux",
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
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.width(5.dp))
                    Text("Add")
                }
            }
        }

        if (draft.customMounts.isEmpty()) {
            item(key = "configuration-custom-empty") {
                Surface(color = UdroidInset, shape = RoundedCornerShape(11.dp)) {
                    Text(
                        "No extra folders shared with this Linux system.",
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

        item(key = "configuration-session-label") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Session mounts", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Added only while the owning feature is active",
                        color = UdroidMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                TextButton(onClick = ::requestSessionFeatures) {
                    Text("Manage features")
                }
            }
        }

        item(key = "configuration-session-mounts") {
            AutomaticSessionMounts()
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
                        creating -> "Create Linux system"
                        else -> "Save changes"
                    },
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = {
                confirmDiscard = false
                openSessionFeaturesAfterDiscard = false
            },
            title = {
                Text(if (creating) "Discard file setup?" else "Unsaved changes")
            },
            text = {
                Text(
                    if (creating) {
                        "This new file setup has not been created yet."
                    } else {
                        "Your file access changes have not been saved."
                    },
                )
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        openSessionFeaturesAfterDiscard = false
                    },
                ) { Text("Keep editing") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (openSessionFeaturesAfterDiscard) onOpenSessionFeatures() else onBack()
                    },
                ) { Text("Discard") }
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
    Surface(color = UdroidInset, shape = MaterialTheme.shapes.medium) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Shared folder",
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
                        Icons.Rounded.DeleteOutline,
                        contentDescription = "Remove shared folder",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            OutlinedTextField(
                value = mount.hostSource,
                enabled = enabled,
                onValueChange = { onChange(mount.copy(hostSource = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Android path") },
                placeholder = { Text("/storage/emulated/0/Projects") },
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
                label = { Text("Path inside Linux") },
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

@Composable
internal fun AndroidStorageMountsCard(
    enabled: Boolean,
    mounts: List<ProotCustomMount>,
    onAdd: (ProotCustomMount) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }
    val hasFullAccess = remember(refreshKey) { AndroidStorageMounts.hasFullAccess(context) }
    val volumes = remember(refreshKey) { AndroidStorageMounts.discover(context) }
    DisposableEffect(context) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    ignoredContext: Context,
                    ignoredIntent: Intent,
                ) {
                    refreshKey++
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addAction(Intent.ACTION_MEDIA_EJECT)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addDataScheme("file")
            }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val settingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshKey++
            onMessage(
                if (AndroidStorageMounts.hasFullAccess(context)) {
                    "Android storage access granted"
                } else {
                    "Android storage access was not granted"
                },
            )
        }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshKey++
            onMessage(
                if (AndroidStorageMounts.hasFullAccess(context)) {
                    "Android storage access granted"
                } else {
                    "Android storage access was not granted"
                },
            )
        }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text("Android storage", style = MaterialTheme.typography.titleMedium)
            Text(
                if (hasFullAccess) {
                    "Choose internal shared storage"
                } else {
                    "Full file access is required before Linux can use shared storage"
                },
                color = UdroidMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            if (!hasFullAccess) {
                TextButton(
                    modifier = Modifier.align(Alignment.End),
                    enabled = enabled,
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            runCatching {
                                settingsLauncher.launch(AndroidStorageMounts.accessIntent(context))
                            }.onFailure {
                                onMessage("Open Android Settings and allow all files access for uDroid")
                            }
                        } else {
                            permissionLauncher.launch(AndroidStorageMounts.legacyPermissions())
                        }
                    },
                ) {
                    Text("Allow access")
                }
            }
        }

        if (!hasFullAccess) {
            Surface(color = UdroidWarningSurface, shape = MaterialTheme.shapes.medium) {
                Text(
                    "This permission lets uDroid read and write shared files, but Linux only " +
                        "receives volumes you add below.",
                    modifier = Modifier.padding(12.dp),
                    color = UdroidWarning,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Surface(
            color = Color.Transparent,
            border = BorderStroke(1.dp, UdroidLine),
            shape = MaterialTheme.shapes.medium,
        ) {
            if (volumes.isEmpty()) {
                Text(
                    "Internal shared storage was not detected.",
                    modifier = Modifier.padding(14.dp),
                    color = UdroidMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Column {
                    volumes.forEachIndexed { index, volume ->
                        AndroidStorageVolumeRow(
                            volume = volume,
                            enabled = enabled,
                            hasFullAccess = hasFullAccess,
                            mounts = mounts,
                            onAdd = onAdd,
                        )
                        if (index != volumes.lastIndex) HorizontalDivider(color = UdroidLine)
                    }
                }
            }
        }
    }
}

@Composable
private fun AndroidStorageVolumeRow(
    volume: AndroidStorageVolume,
    enabled: Boolean,
    hasFullAccess: Boolean,
    mounts: List<ProotCustomMount>,
    onAdd: (ProotCustomMount) -> Unit,
) {
    val alreadyAdded = mounts.any { it.hostSource == volume.hostPath }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(volume.label) },
        supportingContent = {
            Column {
                Text(
                    volume.hostPath,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    when {
                        !volume.mounted -> "Not mounted"
                        alreadyAdded -> "Already configured below; add another mapping if needed"
                        !hasFullAccess -> "Access required"
                        volume.state == android.os.Environment.MEDIA_MOUNTED_READ_ONLY -> "Read only"
                        else -> "Mount inside Linux at ${volume.guestTarget}"
                    },
                    color = UdroidMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        trailingContent = {
            TextButton(
                enabled = enabled && hasFullAccess && volume.mounted,
                onClick = {
                    onAdd(
                        ProotCustomMount(
                            hostSource = volume.hostPath,
                            guestTarget = volume.guestTarget,
                        ),
                    )
                },
            ) {
                Text(if (alreadyAdded) "Add another" else "Add")
            }
        },
    )
}

@Composable
internal fun AutomaticSessionMounts() {
    Surface(
        color = Color.Transparent,
        border = BorderStroke(1.dp, UdroidLine),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column {
            AUTOMATIC_SESSION_MOUNTS.forEachIndexed { index, mount ->
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = {
                        Text(
                            mount.guestTarget,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    supportingContent = { Text(mount.purpose) },
                    trailingContent = {
                        Text(
                            mount.owner,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                )
                if (index != AUTOMATIC_SESSION_MOUNTS.lastIndex) {
                    HorizontalDivider(color = UdroidLine)
                }
            }
        }
    }
}
