package org.randomcoder.udroid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import org.randomcoder.udroid.catalog.LinuxDistribution
import org.randomcoder.udroid.runtime.InstalledRootfs
import org.randomcoder.udroid.runtime.RuntimePhase
import org.randomcoder.udroid.runtime.RuntimeSnapshot
import org.randomcoder.udroid.runtime.RuntimeSupervisorService
import org.randomcoder.udroid.runtime.TerminalColorProfile
import org.randomcoder.udroid.runtime.TerminalExtraKeys
import org.randomcoder.udroid.runtime.TerminalExtraKeysState
import org.randomcoder.udroid.runtime.TerminalExtraKeysStore
import org.randomcoder.udroid.input.ExtraKeyBinding
import org.randomcoder.udroid.runtime.TerminalProfileState
import org.randomcoder.udroid.runtime.TerminalTypographyState
import org.randomcoder.udroid.runtime.TerminalTypographyStore
import org.randomcoder.udroid.runtime.TerminalTabCloseState
import org.randomcoder.udroid.runtime.TerminalTabSnapshot
import org.randomcoder.udroid.runtime.MAX_TERMINAL_TITLE_CHARS
import org.randomcoder.udroid.runtime.TERMINAL_TEXT_SIZE_MAX_SP
import org.randomcoder.udroid.runtime.TERMINAL_TEXT_SIZE_MIN_SP
import org.randomcoder.udroid.runtime.terminalDistroTitle
import kotlin.math.roundToInt

@Composable
fun InteractiveTerminalPage(
    modifier: Modifier = Modifier,
    snapshot: RuntimeSnapshot,
    service: RuntimeSupervisorService?,
    installedRootfses: List<InstalledRootfs>,
    profileState: TerminalProfileState,
    typographyState: TerminalTypographyState,
    onSelectProfile: (String) -> Unit,
    onSaveProfile: (TerminalColorProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onTypographyChanged: (TerminalTypographyState) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val tabs = service?.terminalTabSnapshots().orEmpty()
    val activeTab = tabs.firstOrNull(TerminalTabSnapshot::active)
    val session = service?.currentTerminalSession()
    var stopRequested by remember(session) { mutableStateOf(false) }
    var tabError by remember { mutableStateOf<String?>(null) }
    var renamingTab by remember { mutableStateOf<TerminalTabSnapshot?>(null) }
    var pendingCloseTabId by remember { mutableStateOf<String?>(null) }
    var creatingTab by remember { mutableStateOf(false) }
    var showDistroPicker by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val extraKeysStore = remember(context) { TerminalExtraKeysStore(context) }
    var extraKeysState by remember(extraKeysStore) { mutableStateOf(extraKeysStore.load()) }
    val stopping = stopRequested || snapshot.phase == RuntimePhase.STOPPING

    fun createTab(rootfsName: String?) {
        val connectedService = service
        when {
            connectedService == null -> tabError = "Linux service is reconnecting"
            rootfsName.isNullOrBlank() -> tabError = "Select a running terminal first"
            else -> {
                creatingTab = true
                tabError = null
                connectedService.createTerminalTab(rootfsName) { result ->
                    creatingTab = false
                    tabError = result.exceptionOrNull()?.message
                }
            }
        }
    }

    fun closeTab(id: String) {
        if (service?.closeTerminalTab(id) != true) {
            tabError = "Could not close terminal"
        }
    }

    if (showSettings) {
        TerminalSettingsPage(
            modifier = modifier,
            state = profileState,
            typographyState = typographyState,
            extraKeysState = extraKeysState,
            onBack = { showSettings = false },
            onSelectProfile = onSelectProfile,
            onSaveProfile = onSaveProfile,
            onDeleteProfile = onDeleteProfile,
            onTypographyChanged = onTypographyChanged,
            onExtraKeysChanged = {
                extraKeysState = extraKeysStore.save(it)
            },
        )
        return
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(UdroidTerminal),
    ) {
        TerminalSessionBar(
            snapshot = snapshot,
            session = session,
            activeTab = activeTab,
            stopping = stopping,
            onExit = onExit,
            onChooseDistro = { showDistroPicker = true },
            onShowSettings = { showSettings = true },
            onStop = {
                stopRequested = true
                onStop()
            },
        )

        if (tabs.isNotEmpty()) {
            TerminalTabsRow(
                tabs = tabs,
                onSelect = { id -> service?.selectTerminalTab(id) },
                onRename = { tab -> renamingTab = tab },
                onFollowTitle = { id -> service?.followTerminalShellTitle(id) },
                onClose = { id ->
                    if (tabs.firstOrNull { it.id == id }?.commandRunning == true) {
                        pendingCloseTabId = id
                    } else {
                        closeTab(id)
                    }
                },
                creatingTab = creatingTab,
                onClone = { createTab(activeTab?.rootfsName) },
            )
        }

        val closeError = tabs.firstOrNull { it.closeState == TerminalTabCloseState.FAILED }?.closeError
        (tabError ?: closeError)?.let { message ->
            Text(
                text = message,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(UdroidTerminalSurface)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
            )
        }

        if (stopping) {
            StoppingTerminalState(
                modifier = Modifier.weight(1f),
                snapshot = snapshot,
            )
        } else if (session == null || !session.isRunning) {
            EmptyTerminalState(
                modifier = Modifier.weight(1f),
                snapshot = snapshot,
                onStart = onStart,
            )
        } else {
            LiveTerminal(
                modifier = Modifier.weight(1f),
                service = service,
                session = session,
                typography = typographyState,
                extraKeys = extraKeysState,
                onTypographyChanged = onTypographyChanged,
            )
        }
    }

    renamingTab?.let { tab ->
        RenameTerminalDialog(
            tab = tab,
            onDismiss = { renamingTab = null },
            onRename = { title ->
                if (service?.renameTerminalTab(tab.id, title) == true) {
                    renamingTab = null
                    tabError = null
                } else {
                    tabError = "Enter a terminal name"
                }
            },
        )
    }

    tabs.firstOrNull { it.id == pendingCloseTabId }?.let { tab ->
        val stopsLinux = tabs.size == 1
        AlertDialog(
            onDismissRequest = { pendingCloseTabId = null },
            title = { Text(if (stopsLinux) "Stop Linux?" else "Close tab?") },
            text = {
                Text(
                    if (stopsLinux) {
                        "A command is still running. Stopping Linux will end it."
                    } else {
                        "A command is still running in ${tab.title}. Closing the tab will end it."
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingCloseTabId = null
                        closeTab(tab.id)
                    },
                ) {
                    Text(if (stopsLinux) "Stop Linux" else "Close tab")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCloseTabId = null }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showDistroPicker) {
        TerminalDistroPicker(
            installedRootfses = installedRootfses,
            activeRootfsName = activeTab?.rootfsName,
            onDismiss = { showDistroPicker = false },
            onSelect = { rootfs ->
                showDistroPicker = false
                createTab(rootfs.name)
            },
        )
    }
}
@Composable
private fun TerminalSessionBar(
    snapshot: RuntimeSnapshot,
    session: TerminalSession?,
    activeTab: TerminalTabSnapshot?,
    stopping: Boolean,
    onExit: () -> Unit,
    onChooseDistro: () -> Unit,
    onShowSettings: () -> Unit,
    onStop: () -> Unit,
) {
    val tabClosing =
        activeTab?.closeState == TerminalTabCloseState.CLOSING ||
            activeTab?.closeState == TerminalTabCloseState.FORCE_CLOSING
    val running = session?.isRunning == true && !stopping && !tabClosing
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                .background(UdroidTerminalSurface)
                .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onExit) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "Leave terminal",
                tint = UdroidTerminalMuted,
            )
        }
        Surface(
            modifier =
                Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clickable(enabled = running, onClick = onChooseDistro),
            color = UdroidTerminalRaised,
            shape = RoundedCornerShape(9.dp),
            border = BorderStroke(1.dp, UdroidTerminalLine),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = if (running) UdroidTerminalGreen else UdroidTerminalMuted,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        terminalDistroTitle(activeTab?.rootfsName ?: session?.mSessionName),
                        color = UdroidTerminalText,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    Text(
                        when {
                            stopping -> "Stopping terminal…"
                            activeTab?.closeState == TerminalTabCloseState.CLOSING ->
                                "Closing terminal…"
                            activeTab?.closeState == TerminalTabCloseState.FORCE_CLOSING ->
                                "Force closing terminal…"
                            activeTab?.closeState == TerminalTabCloseState.FAILED ->
                                activeTab.closeError ?: "Could not close terminal"
                            running ->
                                "root  •  ${Build.SUPPORTED_ABIS.firstOrNull().orEmpty()}" +
                                    "  •  PID ${session?.pid}"
                            else -> snapshot.message
                        },
                        color = UdroidTerminalMuted,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                    )
                }
                if (running) {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "Choose installed Linux system",
                        modifier = Modifier.size(20.dp),
                        tint = UdroidTerminalMuted,
                    )
                }
            }
        }
        if (stopping) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = UdroidTerminalGreen,
                    strokeWidth = 2.dp,
                )
            }
        } else if (running) {
            IconButton(onClick = onStop) {
                Icon(
                    imageVector = Icons.Filled.StopCircle,
                    contentDescription = "Stop Linux session",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            Spacer(Modifier.width(48.dp))
        }
        IconButton(onClick = onShowSettings) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = "Terminal settings",
                tint = UdroidTerminalMuted,
            )
        }
    }
}
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TerminalTabsRow(
    tabs: List<TerminalTabSnapshot>,
    onSelect: (String) -> Unit,
    onRename: (TerminalTabSnapshot) -> Unit,
    onFollowTitle: (String) -> Unit,
    onClose: (String) -> Unit,
    creatingTab: Boolean,
    onClone: () -> Unit,
) {
    val activeTabId = tabs.firstOrNull(TerminalTabSnapshot::active)?.id
    val activeTabRequester = remember(activeTabId) { BringIntoViewRequester() }
    val tabsScrollState = rememberScrollState()
    LaunchedEffect(activeTabId, tabs.size) {
        if (activeTabId != null) {
            withFrameNanos { }
            activeTabRequester.bringIntoView()
        }
    }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(UdroidTerminalSurface),
        verticalAlignment = Alignment.Bottom,
    ) {
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .horizontalScroll(tabsScrollState)
                    .padding(start = 8.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            tabs.forEach { tab ->
                TerminalTabChip(
                    modifier =
                        if (tab.id == activeTabId) {
                            Modifier.bringIntoViewRequester(activeTabRequester)
                        } else {
                            Modifier
                        },
                    tab = tab,
                    onSelect = { onSelect(tab.id) },
                    onRename = { onRename(tab) },
                    onFollowTitle = { onFollowTitle(tab.id) },
                    onClose = { onClose(tab.id) },
                    stopsLinux = tabs.size == 1,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Surface(
            modifier =
                Modifier
                    .width(54.dp)
                    .height(46.dp)
                    .clickable(enabled = !creatingTab, onClick = onClone),
            color = UdroidTerminalRaised,
            shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (creatingTab) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = UdroidTerminalGreen,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "Clone current Linux terminal",
                        modifier = Modifier.size(28.dp),
                        tint = UdroidTerminalText,
                    )
                }
            }
        }
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun TerminalTabChip(
    modifier: Modifier = Modifier,
    tab: TerminalTabSnapshot,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onFollowTitle: () -> Unit,
    onClose: () -> Unit,
    stopsLinux: Boolean,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val closing =
        tab.closeState == TerminalTabCloseState.CLOSING ||
            tab.closeState == TerminalTabCloseState.FORCE_CLOSING
    Surface(
        modifier =
            modifier
                .width(144.dp)
                .height(46.dp)
                .clickable(enabled = !closing, onClick = onSelect),
        color = if (tab.active) UdroidTerminal else UdroidTerminalRaised,
        shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
        border =
            if (tab.active) {
                null
            } else {
                BorderStroke(1.dp, UdroidTerminalLine)
            },
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (closing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = UdroidTerminalGreen,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint =
                        if (tab.closeState == TerminalTabCloseState.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else if (tab.active) {
                            UdroidTerminalGreen
                        } else {
                            UdroidTerminalMuted
                        },
                )
            }
            Spacer(Modifier.width(7.dp))
            Text(
                text =
                    when (tab.closeState) {
                        TerminalTabCloseState.CLOSING -> "${tab.title} · closing"
                        TerminalTabCloseState.FORCE_CLOSING -> "${tab.title} · force closing"
                        TerminalTabCloseState.FAILED -> "${tab.title} · close failed"
                        TerminalTabCloseState.OPEN -> tab.title
                    },
                modifier = Modifier.weight(1f),
                color = if (tab.active) UdroidTerminalText else UdroidTerminalMuted,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!closing) {
                Box {
                    IconButton(
                        modifier = Modifier.size(36.dp),
                        onClick = { menuExpanded = true },
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MoreVert,
                            contentDescription = "${tab.title} settings",
                            modifier = Modifier.size(18.dp),
                            tint = UdroidTerminalMuted,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            },
                        )
                        if (tab.hasCustomTitle) {
                            DropdownMenuItem(
                                text = { Text("Follow shell title") },
                                onClick = {
                                    menuExpanded = false
                                    onFollowTitle()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    when {
                                        tab.closeState == TerminalTabCloseState.FAILED -> "Retry close"
                                        stopsLinux -> "Stop Linux"
                                        else -> "Close tab"
                                    },
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (stopsLinux) Icons.Filled.StopCircle else Icons.Outlined.Close,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onClose()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TerminalDistroPicker(
    installedRootfses: List<InstalledRootfs>,
    activeRootfsName: String?,
    onDismiss: () -> Unit,
    onSelect: (InstalledRootfs) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open a Linux terminal") },
        text = {
            if (installedRootfses.isEmpty()) {
                Text("Install a Linux system before opening another terminal.")
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(
                        items = installedRootfses,
                        key = InstalledRootfs::name,
                    ) { rootfs ->
                        val distribution = terminalDistribution(rootfs.name)
                        Surface(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(rootfs) },
                            color = UdroidTerminalRaised,
                            shape = RoundedCornerShape(11.dp),
                            border = BorderStroke(1.dp, UdroidTerminalLine),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (distribution != null) {
                                    DistroMark(
                                        distribution = distribution,
                                        size = 38,
                                    )
                                } else {
                                    Surface(
                                        modifier = Modifier.size(38.dp),
                                        color = UdroidTerminal,
                                        shape = RoundedCornerShape(9.dp),
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Outlined.Terminal,
                                                contentDescription = null,
                                                modifier = Modifier.size(19.dp),
                                                tint = UdroidTerminalGreen,
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = terminalDistroTitle(rootfs.name),
                                        color = UdroidTerminalText,
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text =
                                            if (rootfs.name == activeRootfsName) {
                                                "Current distro · ${rootfs.name}"
                                            } else {
                                                rootfs.name
                                            },
                                        color = UdroidTerminalMuted,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RenameTerminalDialog(
    tab: TerminalTabSnapshot,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var title by remember(tab.id) { mutableStateOf(tab.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename terminal") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(MAX_TERMINAL_TITLE_CHARS) },
                label = { Text("Terminal name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(title) },
                enabled = title.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun StoppingTerminalState(
    modifier: Modifier = Modifier,
    snapshot: RuntimeSnapshot,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(UdroidTerminal),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(38.dp),
                color = UdroidTerminalGreen,
                strokeWidth = 3.dp,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "Stopping terminal…",
                color = UdroidTerminalText,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                snapshot.message.takeIf { snapshot.phase == RuntimePhase.STOPPING }
                    ?: "Waiting for the supervised Linux process to exit.",
                color = UdroidTerminalMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun terminalDistribution(rootfsName: String): LinuxDistribution? {
    val normalized = rootfsName.lowercase()
    return when {
        listOf("focal", "jammy", "noble", "oracular", "plucky", "questing", "resolute", "ubuntu")
            .any(normalized::contains) -> LinuxDistribution.UBUNTU
        "debian" in normalized || "trixie" in normalized -> LinuxDistribution.DEBIAN
        "arch" in normalized -> LinuxDistribution.ARCH
        "alpine" in normalized -> LinuxDistribution.ALPINE
        "void" in normalized -> LinuxDistribution.VOID
        else -> null
    }
}

@Composable
private fun EmptyTerminalState(
    modifier: Modifier = Modifier,
    snapshot: RuntimeSnapshot,
    onStart: () -> Unit,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(UdroidTerminal),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                color = UdroidTerminalRaised,
                shape = RoundedCornerShape(13.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Terminal,
                        contentDescription = null,
                        tint = UdroidTerminalGreen,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                if (snapshot.phase == RuntimePhase.CRASHED) {
                    "Session ended unexpectedly"
                } else {
                    "Start a Linux session"
                },
                color = UdroidTerminalText,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                if (snapshot.phase == RuntimePhase.CRASHED) {
                    snapshot.message
                } else {
                    "The supervised PTY keeps running when you move around uDroid."
                },
                color = UdroidTerminalMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = onStart,
                enabled = snapshot.phase != RuntimePhase.STARTING,
                shape = RoundedCornerShape(9.dp),
            ) {
                Text(
                    if (snapshot.phase == RuntimePhase.CRASHED) {
                        "Try again"
                    } else {
                        "Start terminal"
                    },
                )
            }
        }
    }
}

@Composable
private fun LiveTerminal(
    modifier: Modifier = Modifier,
    service: RuntimeSupervisorService,
    session: TerminalSession,
    typography: TerminalTypographyState,
    extraKeys: TerminalExtraKeysState,
    onTypographyChanged: (TerminalTypographyState) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val terminalBackground = UdroidTerminal.toArgb()
    val modifiers = remember { TerminalModifierState() }
    val typographyStore = remember(context) { TerminalTypographyStore(context) }
    val typeface = remember(typography.selectedFont) { typographyStore.loadTypeface(typography.selectedFont) }
    val textSizePx = with(density) { typography.textSizeSp.sp.toPx().roundToInt() }
    val minimumTextSizePx = with(density) { TERMINAL_TEXT_SIZE_MIN_SP.sp.toPx() }
    val maximumTextSizePx = with(density) { TERMINAL_TEXT_SIZE_MAX_SP.sp.toPx() }
    val currentTypography by rememberUpdatedState(typography)
    val currentOnTypographyChanged by rememberUpdatedState(onTypographyChanged)
    var pendingLink by remember { mutableStateOf<String?>(null) }
    val client =
        remember(context, density) {
            UdroidTerminalViewClient(
                context = context,
                modifiers = modifiers,
                initialTextSize = textSizePx,
                minimumTextSize = minimumTextSizePx,
                maximumTextSize = maximumTextSizePx,
                onLinkTapped = { pendingLink = it },
                onTextSizeChanged = {
                    currentOnTypographyChanged(
                        currentTypography.copy(
                            textSizeSp = with(density) { it.toSp().value },
                        ),
                    )
                },
            )
        }
    val terminalView =
        remember(context) {
            TerminalView(context, null).apply {
                setBackgroundColor(terminalBackground)
                setTextAppearance(textSizePx, typeface, typography.lineSpacing)
                isFocusable = true
                isFocusableInTouchMode = true
                setTerminalViewClient(client)
                client.attach(this)
            }
        }

    LaunchedEffect(terminalView, typeface, textSizePx, typography.lineSpacing, typography.cursorBlinkRateMs) {
        client.updateTextSize(textSizePx)
        terminalView.setTextAppearance(textSizePx, typeface, typography.lineSpacing)
        client.updateCursorBlinkRate(typography.cursorBlinkRateMs)
    }

    DisposableEffect(service, terminalView) {
        service.attachTerminalView(terminalView)
        onDispose {
            modifiers.reset()
            client.stopCursorBlinking()
            service.detachTerminalView(terminalView)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { terminalView },
            update = { view ->
                view.setBackgroundColor(terminalBackground)
                if (view.mTermSession !== session) {
                    view.attachSession(session)
                }
                view.onScreenUpdated()
            },
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
        )
        if (extraKeys.visible) {
            TerminalExtraKeysRail(
                rows = extraKeys.rows,
                modifiers = modifiers,
                terminalView = terminalView,
            )
        }
    }
    pendingLink?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingLink = null },
            title = { Text("Open link?") },
            text = { Text(uri) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingLink = null
                        if (!openTerminalLink(context, uri)) {
                            Toast.makeText(context, "This link type cannot be opened", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text("Open") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Terminal link", uri))
                        pendingLink = null
                        Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                    },
                ) { Text("Copy") }
            },
        )
    }
}

internal fun openTerminalLink(
    context: Context,
    value: String,
): Boolean {
    val uri = Uri.parse(value)
    if (!terminalLinkCanOpen(uri.scheme)) return false
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }.isSuccess
}

internal fun terminalLinkCanOpen(scheme: String?): Boolean =
    scheme.equals("http", true) || scheme.equals("https", true) || scheme.equals("mailto", true)

@Composable
private fun TerminalExtraKeysRail(
    rows: List<List<ExtraKeyBinding>>,
    modifiers: TerminalModifierState,
    terminalView: TerminalView,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(UdroidTerminalSurface)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        var slot = 0
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                row.forEach { binding ->
                    val keySlot = slot++
                    val keyCode = binding.takeUnless(ExtraKeyBinding::macro)?.let { TerminalExtraKeys.keyCode(it.action) }
                    TerminalExtraKey(
                        modifier = Modifier.weight(1f),
                        slot = keySlot,
                        binding = binding,
                        active = keyCode?.let(modifiers::isActive) == true,
                        onPressed = { pressed ->
                            if (keyCode == null) return@TerminalExtraKey
                            val now = SystemClock.uptimeMillis()
                            modifiers.onKeyEvent(keySlot, keyCode, pressed, now) { code, down ->
                                val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
                                val event = KeyEvent(now, now, action, code, 0)
                                if (down) terminalView.onKeyDown(code, event) else terminalView.onKeyUp(code, event)
                            }
                        },
                        onMacro = { sendTerminalMacro(binding, terminalView) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalExtraKey(
    modifier: Modifier,
    slot: Int,
    binding: ExtraKeyBinding,
    active: Boolean,
    onPressed: (Boolean) -> Unit,
    onMacro: () -> Unit,
) {
    val label = TerminalExtraKeys.label(binding)
    Box(
        modifier =
            modifier
                .height(34.dp)
                .background(
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else UdroidTerminalRaised,
                    shape = RoundedCornerShape(8.dp),
                ).semantics {
                    role = Role.Button
                    contentDescription =
                        if (binding.display == null) "$label extra key" else "$label, ${binding.action} extra key"
                    onClick {
                        if (binding.macro || TerminalExtraKeys.keyCode(binding.action) == null) {
                            onMacro()
                        } else {
                            onPressed(true)
                            onPressed(false)
                        }
                        true
                    }
                }.pointerInput(slot, binding) {
                    if (binding.macro || TerminalExtraKeys.keyCode(binding.action) == null) {
                        detectTapGestures(onTap = { onMacro() })
                    } else {
                        detectTapGestures(
                            onPress = {
                                onPressed(true)
                                tryAwaitRelease()
                                onPressed(false)
                            },
                        )
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) UdroidTerminalGreen else UdroidTerminalText,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun sendTerminalMacro(
    binding: ExtraKeyBinding,
    terminalView: TerminalView,
) {
    val tokens = binding.tokens()
    val modifiers = tokens.mapNotNull(TerminalExtraKeys::keyCode).filter(TerminalExtraKeys::isModifier).toSet()
    val metaState =
        (if (KeyEvent.KEYCODE_CTRL_LEFT in modifiers) KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CTRL_ON else 0) or
            (if (KeyEvent.KEYCODE_ALT_LEFT in modifiers) KeyEvent.META_ALT_LEFT_ON or KeyEvent.META_ALT_ON else 0) or
            (if (KeyEvent.KEYCODE_SHIFT_LEFT in modifiers) KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_ON else 0) or
            (if (KeyEvent.KEYCODE_FUNCTION in modifiers) KeyEvent.META_FUNCTION_ON else 0)
    tokens.forEach { token ->
        val keyCode = TerminalExtraKeys.keyCode(token)
        if (keyCode?.let(TerminalExtraKeys::isModifier) == true) return@forEach
        if (keyCode != null) {
            val now = SystemClock.uptimeMillis()
            terminalView.onKeyDown(keyCode, KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
            terminalView.onKeyUp(keyCode, KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState))
        } else {
            token.codePoints().forEach { terminalView.inputCodePoint(it, metaState and KeyEvent.META_CTRL_ON != 0, metaState and KeyEvent.META_ALT_ON != 0) }
        }
    }
}

internal class TerminalModifierState(
    private val doubleTapMillis: Long = 300,
) {
    private enum class Modifier { CONTROL, ALT, SHIFT, FUNCTION }

    private data class Held(
        val modifier: Modifier,
        val pressedAt: Long,
        var usedInChord: Boolean = false,
    )

    private val held = mutableMapOf<Int, Held>()
    private val armed = mutableSetOf<Modifier>()
    private val locked = mutableSetOf<Modifier>()
    private val ignoredReleases = mutableSetOf<Int>()
    private var lastTapSlot = -1
    private var lastTapAt = 0L
    private var revision by mutableStateOf(0)

    fun onKeyEvent(
        slot: Int,
        keyCode: Int,
        pressed: Boolean,
        atMillis: Long,
        send: (Int, Boolean) -> Unit,
    ) {
        val modifier = modifierFor(keyCode)
        if (pressed) {
            if (modifier == null) {
                held.values.forEach { it.usedInChord = true }
                send(keyCode, true)
            } else {
                when {
                    modifier in locked -> {
                        locked -= modifier
                        ignoredReleases += slot
                    }
                    modifier in armed && slot == lastTapSlot && atMillis - lastTapAt in 0..doubleTapMillis -> {
                        armed -= modifier
                        locked += modifier
                        ignoredReleases += slot
                        lastTapSlot = -1
                    }
                    else -> held[slot] = Held(modifier, atMillis)
                }
                changed()
            }
        } else if (modifier == null) {
            send(keyCode, false)
            consumeArmed()
        } else if (!ignoredReleases.remove(slot)) {
            held.remove(slot)?.let { key ->
                if (!key.usedInChord && atMillis - key.pressedAt <= 500) {
                    armed += key.modifier
                    lastTapSlot = slot
                    lastTapAt = atMillis
                }
                changed()
            }
        }
    }

    fun isActive(keyCode: Int): Boolean {
        revision
        val modifier = modifierFor(keyCode) ?: return false
        return modifier in armed || modifier in locked || held.values.any { it.modifier == modifier }
    }

    fun isControlActive(): Boolean = isActive(KeyEvent.KEYCODE_CTRL_LEFT)

    fun isAltActive(): Boolean = isActive(KeyEvent.KEYCODE_ALT_LEFT)

    fun isShiftActive(): Boolean = isActive(KeyEvent.KEYCODE_SHIFT_LEFT)

    fun isFunctionActive(): Boolean = isActive(KeyEvent.KEYCODE_FUNCTION)

    fun markChordUsed() {
        held.values.forEach { it.usedInChord = true }
    }

    fun consumeArmed() {
        if (armed.isEmpty()) return
        armed.clear()
        lastTapSlot = -1
        changed()
    }

    fun reset() {
        held.clear()
        armed.clear()
        locked.clear()
        ignoredReleases.clear()
        lastTapSlot = -1
        changed()
    }

    private fun changed() {
        revision += 1
    }

    private fun modifierFor(keyCode: Int): Modifier? =
        when (keyCode) {
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> Modifier.CONTROL
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> Modifier.ALT
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> Modifier.SHIFT
            KeyEvent.KEYCODE_FUNCTION -> Modifier.FUNCTION
            else -> null
        }
}

private class UdroidTerminalViewClient(
    private val context: Context,
    private val modifiers: TerminalModifierState,
    initialTextSize: Int,
    private val minimumTextSize: Float,
    private val maximumTextSize: Float,
    private val onLinkTapped: (String) -> Unit,
    private val onTextSizeChanged: (Int) -> Unit,
) : TerminalViewClient {
    private var terminalView: TerminalView? = null
    private var textSize = initialTextSize.toFloat()
    private var cursorBlinkRate = 0

    fun attach(view: TerminalView) {
        terminalView = view
    }

    fun updateTextSize(textSize: Int) {
        this.textSize = textSize.toFloat()
    }

    fun updateCursorBlinkRate(rate: Int) {
        cursorBlinkRate = rate
        applyCursorBlinking()
    }

    fun stopCursorBlinking() {
        terminalView?.setTerminalCursorBlinkerState(false, true)
    }

    private fun applyCursorBlinking() {
        terminalView?.let { view ->
            view.setTerminalCursorBlinkerRate(cursorBlinkRate)
            view.setTerminalCursorBlinkerState(cursorBlinkRate > 0, true)
        }
    }

    override fun onScale(scale: Float): Float {
        textSize = (textSize * scale).coerceIn(minimumTextSize, maximumTextSize)
        val roundedTextSize = textSize.roundToInt()
        terminalView?.setTextSize(roundedTextSize)
        onTextSizeChanged(roundedTextSize)
        return 1f
    }

    override fun onSingleTapUp(event: MotionEvent) {
        terminalView?.let { view ->
            val (column, row) = view.getColumnAndRow(event, true)
            view.currentSession?.emulator?.getHyperlinkUriAt(row, column)?.let {
                onLinkTapped(it)
                return
            }
            view.requestFocus()
            context.getSystemService(InputMethodManager::class.java)
                .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = terminalView?.hasFocus() == true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
        session: TerminalSession,
    ): Boolean {
        if (!TerminalExtraKeys.isModifier(keyCode)) {
            modifiers.markChordUsed()
            terminalView?.post(modifiers::consumeArmed)
        }
        return false
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = modifiers.isControlActive()

    override fun readAltKey(): Boolean = modifiers.isAltActive()

    override fun readShiftKey(): Boolean = modifiers.isShiftActive()

    override fun readFnKey(): Boolean = modifiers.isFunctionActive()

    override fun onCodePoint(
        codePoint: Int,
        ctrlDown: Boolean,
        session: TerminalSession,
    ): Boolean {
        terminalView?.post(modifiers::consumeArmed)
        return false
    }

    override fun onEmulatorSet() {
        applyCursorBlinking()
    }

    override fun logError(
        tag: String,
        message: String,
    ) {
        Log.e(tag, message)
    }

    override fun logWarn(
        tag: String,
        message: String,
    ) {
        Log.w(tag, message)
    }

    override fun logInfo(
        tag: String,
        message: String,
    ) {
        Log.i(tag, message)
    }

    override fun logDebug(
        tag: String,
        message: String,
    ) {
        Log.d(tag, message)
    }

    override fun logVerbose(
        tag: String,
        message: String,
    ) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(
        tag: String,
        message: String,
        error: Exception,
    ) {
        Log.e(tag, message, error)
    }

    override fun logStackTrace(
        tag: String,
        error: Exception,
    ) {
        Log.e(tag, error.message, error)
    }
}
