package org.randomcoder.udroid.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import org.randomcoder.udroid.runtime.RuntimePhase
import org.randomcoder.udroid.runtime.RuntimeSnapshot
import org.randomcoder.udroid.runtime.RuntimeSupervisorService
import org.randomcoder.udroid.runtime.TerminalTabSnapshot
import kotlin.math.roundToInt

@Composable
fun InteractiveTerminalPage(
    modifier: Modifier = Modifier,
    snapshot: RuntimeSnapshot,
    service: RuntimeSupervisorService?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onExit: () -> Unit,
) {
    val tabs = service?.terminalTabSnapshots().orEmpty()
    val activeTab = tabs.firstOrNull(TerminalTabSnapshot::active)
    val session = service?.currentTerminalSession()
    var stopRequested by remember(session) { mutableStateOf(false) }
    var tabError by remember { mutableStateOf<String?>(null) }
    var renamingTab by remember { mutableStateOf<TerminalTabSnapshot?>(null) }
    var creatingTab by remember { mutableStateOf(false) }
    val stopping = stopRequested || snapshot.phase == RuntimePhase.STOPPING
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
            creatingTab = creatingTab,
            onExit = onExit,
            onCreateTab = {
                val connectedService = service
                if (connectedService == null) {
                    tabError = "Linux service is reconnecting"
                } else {
                    creatingTab = true
                    tabError = null
                    connectedService.createTerminalTab { result ->
                        creatingTab = false
                        tabError = result.exceptionOrNull()?.message
                    }
                }
            },
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
                onClose = { id -> service?.closeTerminalTab(id) },
            )
        }

        tabError?.let { message ->
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
}

@Composable
private fun TerminalSessionBar(
    snapshot: RuntimeSnapshot,
    session: TerminalSession?,
    activeTab: TerminalTabSnapshot?,
    stopping: Boolean,
    creatingTab: Boolean,
    onExit: () -> Unit,
    onCreateTab: () -> Unit,
    onStop: () -> Unit,
) {
    val running = session?.isRunning == true && !stopping
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
                    .height(46.dp),
            color = UdroidTerminalRaised,
            shape = RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp),
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
                        activeTab?.title ?: terminalSessionTitle(session?.mSessionName),
                        color = UdroidTerminalText,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    Text(
                        when {
                            stopping -> "Stopping terminal…"
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
            }
        }
        if (running) {
            if (creatingTab) {
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = UdroidTerminalGreen,
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                IconButton(onClick = onCreateTab) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "New terminal tab",
                        tint = UdroidTerminalGreen,
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
    }
}

@Composable
private fun TerminalTabsRow(
    tabs: List<TerminalTabSnapshot>,
    onSelect: (String) -> Unit,
    onRename: (TerminalTabSnapshot) -> Unit,
    onClose: (String) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(UdroidTerminalSurface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            TerminalTabChip(
                tab = tab,
                onSelect = { onSelect(tab.id) },
                onRename = { onRename(tab) },
                onClose = { onClose(tab.id) },
            )
        }
    }
}

@Composable
private fun TerminalTabChip(
    tab: TerminalTabSnapshot,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Surface(
        modifier =
            Modifier
                .widthIn(min = 128.dp, max = 196.dp)
                .height(40.dp)
                .clickable(onClick = onSelect),
        color = if (tab.active) UdroidTerminalRaised else UdroidTerminal,
        shape = RoundedCornerShape(9.dp),
        border =
            BorderStroke(
                1.dp,
                if (tab.active) UdroidTerminalGreen else UdroidTerminalLine,
            ),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Terminal,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (tab.active) UdroidTerminalGreen else UdroidTerminalMuted,
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = tab.title,
                modifier = Modifier.weight(1f),
                color = if (tab.active) UdroidTerminalText else UdroidTerminalMuted,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
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
                    DropdownMenuItem(
                        text = { Text("Close terminal") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Close, contentDescription = null)
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
                onValueChange = { title = it.take(40) },
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

private fun terminalSessionTitle(rawName: String?): String =
    when {
        rawName.isNullOrBlank() -> "Linux terminal"
        rawName.contains("jammy", ignoreCase = true) -> "Ubuntu Jammy"
        rawName.contains("noble", ignoreCase = true) -> "Ubuntu Noble"
        rawName.contains("resolute", ignoreCase = true) -> "Ubuntu Resolute"
        else ->
            rawName
                .removePrefix("udroid-")
                .removeSuffix("-raw")
                .split('-')
                .joinToString(" ") { word -> word.replaceFirstChar(Char::titlecase) }
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
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val modifiers = remember { TerminalModifierState() }
    val initialTextSize = remember(density) {
        with(density) { 15.sp.toPx().roundToInt() }
    }
    val client =
        remember {
            UdroidTerminalViewClient(
                context = context,
                modifiers = modifiers,
                initialTextSize = initialTextSize,
            )
        }
    val terminalView =
        remember {
            TerminalView(context, null).apply {
                setBackgroundColor(android.graphics.Color.rgb(17, 19, 31))
                setTextSize(initialTextSize)
                setTypeface(Typeface.MONOSPACE)
                isFocusable = true
                isFocusableInTouchMode = true
                setTerminalViewClient(client)
                client.attach(this)
            }
        }

    DisposableEffect(service, terminalView) {
        service.attachTerminalView(terminalView)
        onDispose {
            service.detachTerminalView(terminalView)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = { terminalView },
            update = { view ->
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
        ExtraKeysRow(
            modifiers = modifiers,
            write = service::writeToTerminal,
        )
    }
}

@Composable
private fun ExtraKeysRow(
    modifiers: TerminalModifierState,
    write: (String) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                .background(UdroidTerminalSurface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 7.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalKey(
            label = "Ctrl",
            active = modifiers.control,
            onClick = { modifiers.control = !modifiers.control },
        )
        TerminalKey(
            label = "Alt",
            active = modifiers.alt,
            onClick = { modifiers.alt = !modifiers.alt },
        )
        TerminalKey("Esc") { write("\u001B") }
        TerminalKey("Tab") { write("\t") }
        TerminalKey("←") { write("\u001B[D") }
        TerminalKey("↑") { write("\u001B[A") }
        TerminalKey("↓") { write("\u001B[B") }
        TerminalKey("→") { write("\u001B[C") }
        TerminalKey("Home") { write("\u001B[H") }
        TerminalKey("PgUp") { write("\u001B[5~") }
        TerminalKey("PgDn") { write("\u001B[6~") }
        TerminalKey("End") { write("\u001B[F") }
    }
}

@Composable
private fun TerminalKey(
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .width(if (label.length > 2) 56.dp else 48.dp)
                .height(48.dp)
                .background(
                    color =
                        if (active) {
                            Color(0xFF164A39)
                        } else {
                            UdroidTerminalRaised
                        },
                    shape = RoundedCornerShape(8.dp),
                )
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) UdroidTerminalGreen else UdroidTerminalText,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private class TerminalModifierState {
    var control by mutableStateOf(false)
    var alt by mutableStateOf(false)
}

private class UdroidTerminalViewClient(
    private val context: Context,
    private val modifiers: TerminalModifierState,
    initialTextSize: Int,
) : TerminalViewClient {
    private var terminalView: TerminalView? = null
    private var textSize = initialTextSize.toFloat()

    fun attach(view: TerminalView) {
        terminalView = view
    }

    override fun onScale(scale: Float): Float {
        textSize = (textSize * scale).coerceIn(MIN_TEXT_SIZE_PX, MAX_TEXT_SIZE_PX)
        terminalView?.setTextSize(textSize.roundToInt())
        return 1f
    }

    override fun onSingleTapUp(event: MotionEvent) {
        terminalView?.let { view ->
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
    ): Boolean = false

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = modifiers.control

    override fun readAltKey(): Boolean = modifiers.alt

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(
        codePoint: Int,
        ctrlDown: Boolean,
        session: TerminalSession,
    ): Boolean {
        terminalView?.post {
            modifiers.control = false
            modifiers.alt = false
        }
        return false
    }

    override fun onEmulatorSet() = Unit

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

    private companion object {
        const val MIN_TEXT_SIZE_PX = 20f
        const val MAX_TEXT_SIZE_PX = 64f
    }
}
