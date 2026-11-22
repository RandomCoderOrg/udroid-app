package org.randomcoder.udroid.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.randomcoder.udroid.catalog.LinuxDistribution
import org.randomcoder.udroid.runtime.InstalledRootfs
import org.randomcoder.udroid.runtime.TerminalTabCloseState
import org.randomcoder.udroid.runtime.TerminalTabSnapshot
import org.randomcoder.udroid.runtime.terminalDistroTitle

private typealias TabMenu = @Composable (tab: TerminalTabSnapshot, expanded: Boolean, dismiss: () -> Unit) -> Unit

@Composable
internal fun TerminalWorkspaceBar(
    tabs: List<TerminalTabSnapshot>,
    activeTab: TerminalTabSnapshot?,
    installedRootfses: List<InstalledRootfs>,
    canCreate: Boolean,
    creatingTab: Boolean,
    stopping: Boolean,
    onNew: () -> Unit,
    onNewInDistro: (String) -> Unit,
    onSelect: (TerminalTabSnapshot) -> Unit,
    onRename: (TerminalTabSnapshot) -> Unit,
    onFollowTitle: (TerminalTabSnapshot) -> Unit,
    onClose: (TerminalTabSnapshot) -> Unit,
    onExit: () -> Unit,
    onOpenDisplay: () -> Unit,
    onShowSettings: () -> Unit,
    onStop: () -> Unit,
) {
    var showTabs by remember(tabs.isEmpty()) { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showDetails by remember(activeTab?.id) { mutableStateOf(false) }
    val distro = activeTab?.let { terminalDistroTitle(it.rootfsName) } ?: "Terminal"
    val shellTitle = activeTab?.workspaceTitle()
    val count = "${tabs.size} ${if (tabs.size == 1) "tab" else "tabs"}"

    val tabMenu: TabMenu = { tab, expanded, dismiss ->
        DropdownMenu(
            expanded = expanded && !tab.closeState.isClosing,
            onDismissRequest = dismiss,
            shape = RoundedCornerShape(8.dp),
            containerColor = UdroidTerminalRaised,
        ) {
            fun handle(action: (TerminalTabSnapshot) -> Unit) {
                dismiss()
                showTabs = false
                action(tab)
            }
            DropdownMenuItem(text = { Text("Rename") }, onClick = { handle(onRename) })
            if (tab.hasCustomTitle) {
                DropdownMenuItem(text = { Text("Follow shell title") }, onClick = { handle(onFollowTitle) })
            }
            val closeLabel = when {
                tab.closeState == TerminalTabCloseState.FAILED -> "Retry close"
                tabs.size == 1 -> "Stop Linux"
                else -> "Close tab"
            }
            DropdownMenuItem(text = { Text(closeLabel) }, onClick = { handle(onClose) })
        }
    }

    fun menuAction(action: () -> Unit) {
        showMenu = false
        action()
    }

    BoxWithConstraints(Modifier.fillMaxWidth().background(UdroidTerminalSurface)) {
        val multipleTabs = tabs.size > 1
        val inlineTabs = multipleTabs && maxWidth >= 600.dp && LocalDensity.current.fontScale <= 1.2f
        Column {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val onlyTab = tabs.singleOrNull()
                var showSingleActions by remember(onlyTab?.id) { mutableStateOf(false) }
                Box(if (inlineTabs) Modifier else Modifier.weight(1f)) {
                    Row(
                        modifier = (if (inlineTabs) Modifier else Modifier.fillMaxWidth())
                            .heightIn(min = 48.dp)
                            .combinedClickable(
                                enabled = tabs.isNotEmpty(),
                                role = Role.Button,
                                onLongClickLabel = "Tab actions",
                                onLongClick = if (onlyTab != null && !onlyTab.closeState.isClosing) ({ showSingleActions = true }) else null,
                                onClick = { showTabs = true },
                            )
                            .semantics { contentDescription = "All tabs, $count, $distro, ${shellTitle.orEmpty()}" }
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onlyTab != null && (stopping || onlyTab.closeState.isClosing)) {
                            TabCloseProgress()
                        } else if (tabs.isNotEmpty()) {
                            Box(
                                Modifier.widthIn(min = 24.dp)
                                    .border(1.dp, UdroidTerminalMuted, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 3.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(tabs.size.toString(), color = UdroidTerminalText, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (!inlineTabs) {
                            Column {
                                Text(
                                    distro,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = UdroidTerminalText,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                if (!multipleTabs && shellTitle != null) {
                                    Text(
                                        shellTitle,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = UdroidTerminalMuted,
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    }
                    if (onlyTab != null) tabMenu(onlyTab, showSingleActions) { showSingleActions = false }
                }
                if (inlineTabs) WorkspaceTabs(Modifier.weight(1f), tabs, onSelect, tabMenu, showDistro = true)
                IconButton(
                    onClick = onNew,
                    enabled = canCreate,
                    modifier = Modifier.size(48.dp),
                ) {
                    if (creatingTab) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).semantics { contentDescription = "Opening terminal" },
                            color = UdroidTerminalGreen,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Outlined.Add, "New terminal in $distro")
                    }
                }
                if (installedRootfses.size > 1) {
                    DistroLaunchMenu(installedRootfses, activeTab?.rootfsName, canCreate, onNewInDistro)
                }
                IconButton(onClick = onOpenDisplay, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.DesktopWindows, "Open display", tint = UdroidTerminalGreen)
                }
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.MoreVert, "Terminal options", tint = UdroidTerminalMuted)
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        if (activeTab != null) {
                            DropdownMenuItem(text = { Text("Session details") }, onClick = { menuAction { showDetails = true } })
                        }
                        DropdownMenuItem(text = { Text("Terminal settings") }, onClick = { menuAction(onShowSettings) })
                        DropdownMenuItem(text = { Text("Leave terminal") }, onClick = { menuAction(onExit) })
                        if (tabs.isNotEmpty()) {
                            DropdownMenuItem(
                                text = { Text("Stop all sessions", color = MaterialTheme.colorScheme.error) },
                                enabled = !stopping,
                                onClick = { menuAction(onStop) },
                            )
                        }
                    }
                }
            }
            if (multipleTabs && !inlineTabs) WorkspaceTabs(Modifier.fillMaxWidth(), tabs, onSelect, tabMenu)
        }
    }

    if (showTabs) {
        AlertDialog(
            onDismissRequest = { showTabs = false },
            title = { Text("All tabs (${tabs.size})") },
            text = {
                OpenTerminalList(tabs, onSelect, tabMenu, onDismiss = { showTabs = false })
            },
            confirmButton = { TextButton(onClick = { showTabs = false }) { Text("Done") } },
        )
    }
    if (showDetails) {
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("Session details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(distro)
                    activeTab?.let { tab ->
                        Text(tab.workspaceTitle())
                        tab.pid?.let { Text("PID $it") }
                    }
                    Build.SUPPORTED_ABIS.firstOrNull()?.let { Text("Android ABI · $it") }
                }
            },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Done") } },
        )
    }
}

@Composable
private fun DistroLaunchMenu(
    installedRootfses: List<InstalledRootfs>,
    activeRootfs: String?,
    canCreate: Boolean,
    onNewInDistro: (String) -> Unit,
) {
    var expanded by remember(canCreate) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = canCreate, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.KeyboardArrowDown, "Choose distro for new terminal")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.widthIn(min = 216.dp, max = 280.dp),
            shape = RoundedCornerShape(8.dp),
            containerColor = UdroidTerminalRaised,
        ) {
            Text(
                "New terminal",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).semantics { heading() },
                color = UdroidTerminalMuted,
                style = MaterialTheme.typography.labelMedium,
            )
            val titleCounts = installedRootfses.groupingBy { terminalDistroTitle(it.name) }.eachCount()
            installedRootfses.forEach { rootfs ->
                val title = terminalDistroTitle(rootfs.name)
                DropdownMenuItem(
                    onClick = {
                        expanded = false
                        onNewInDistro(rootfs.name)
                    },
                    leadingIcon = {
                        DistroLogo(terminalDistribution(rootfs.name) ?: LinuxDistribution.UNKNOWN, Modifier.size(20.dp))
                    },
                    text = {
                        Column {
                            Text(title, color = UdroidTerminalText, style = MaterialTheme.typography.bodyMedium)
                            if (titleCounts.getValue(title) > 1) {
                                Text(rootfs.name, color = UdroidTerminalMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                    trailingIcon = if (rootfs.name == activeRootfs) {
                        { Text("Current", color = UdroidTerminalGreen, style = MaterialTheme.typography.labelSmall) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun WorkspaceTabs(
    modifier: Modifier,
    tabs: List<TerminalTabSnapshot>,
    onSelect: (TerminalTabSnapshot) -> Unit,
    tabMenu: TabMenu,
    showDistro: Boolean = false,
) {
    val accent = UdroidTerminalGreen
    Row(
        modifier.horizontalScroll(rememberScrollState()).selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(4.dp))
        tabs.forEach { tab ->
            key(tab.id) {
                val requester = remember { BringIntoViewRequester() }
                LaunchedEffect(tab.active) {
                    if (tab.active) requester.bringIntoView()
                }
                var showActions by remember { mutableStateOf(false) }
                val title = tab.workspaceTitle()
                val distro = terminalDistroTitle(tab.rootfsName)
                val distribution = terminalDistribution(tab.rootfsName)
                Box {
                    Row(
                        Modifier.bringIntoViewRequester(requester)
                            .widthIn(min = 48.dp, max = 192.dp)
                            .heightIn(min = 48.dp)
                            .combinedClickable(
                                enabled = !tab.closeState.isClosing,
                                role = Role.Tab,
                                onLongClickLabel = "Tab actions",
                                onLongClick = { showActions = true },
                                onClick = { onSelect(tab) },
                            )
                            .semantics {
                                selected = tab.active
                                contentDescription = "$distro, $title"
                            }
                            .drawBehind {
                                if (tab.active) {
                                    val inset = 8.dp.toPx()
                                    val y = size.height - 1.dp.toPx()
                                    drawLine(accent, Offset(inset, y), Offset(size.width - inset, y), 2.dp.toPx())
                                }
                            }
                            .padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (tab.closeState.isClosing) {
                            TabCloseProgress()
                        } else if (distribution != null) {
                            DistroLogo(distribution, Modifier.size(16.dp))
                        }
                        Text(
                            text = if (showDistro || distribution == null) "$title · $distro" else title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontFamily = FontFamily.Monospace,
                            color = if (tab.active) UdroidTerminalText else UdroidTerminalMuted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    tabMenu(tab, showActions) { showActions = false }
                }
            }
        }
    }
}

@Composable
private fun OpenTerminalList(
    tabs: List<TerminalTabSnapshot>,
    onSelect: (TerminalTabSnapshot) -> Unit,
    tabMenu: TabMenu,
    onDismiss: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
        items(tabs, key = { it.id }) { tab ->
            var actions by remember { mutableStateOf(false) }
            val title = tab.workspaceTitle()
            val system = terminalDistroTitle(tab.rootfsName)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).heightIn(min = 56.dp)
                        .selectable(tab.active, enabled = !tab.closeState.isClosing, role = Role.Tab) {
                            onSelect(tab)
                            onDismiss()
                        }.padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    terminalDistribution(tab.rootfsName)?.let {
                        DistroMark(distribution = it, size = 32)
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleSmall)
                        Text(system, style = MaterialTheme.typography.bodySmall, color = UdroidTerminalMuted)
                    }
                    if (tab.closeState.isClosing) TabCloseProgress()
                    else if (tab.active) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = UdroidTerminalGreen)
                }
                Box {
                    IconButton(onClick = { actions = true }, enabled = !tab.closeState.isClosing, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.MoreVert, "Options for $system, $title")
                    }
                    tabMenu(tab, actions) { actions = false }
                }
            }
        }
    }
}

@Composable
private fun TabCloseProgress() {
    CircularProgressIndicator(
        modifier = Modifier.size(16.dp),
        color = UdroidTerminalGreen,
        strokeWidth = 2.dp,
    )
}

private fun TerminalTabSnapshot.workspaceTitle(): String =
    when (closeState) {
        TerminalTabCloseState.OPEN -> shortTitle
        TerminalTabCloseState.CLOSING -> "$shortTitle · closing"
        TerminalTabCloseState.FORCE_CLOSING -> "$shortTitle · force closing"
        TerminalTabCloseState.FAILED -> "$shortTitle · close failed"
    }
