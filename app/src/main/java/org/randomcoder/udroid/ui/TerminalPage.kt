package org.randomcoder.udroid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.doOnLayout
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
import org.randomcoder.udroid.runtime.DesktopSessionPhase
import org.randomcoder.udroid.runtime.DesktopSessionSnapshot
import org.randomcoder.udroid.runtime.MAX_TERMINAL_TITLE_CHARS
import org.randomcoder.udroid.runtime.TERMINAL_TEXT_SIZE_MAX_SP
import org.randomcoder.udroid.runtime.TERMINAL_TEXT_SIZE_MIN_SP
import org.randomcoder.udroid.runtime.terminalDistroTitle
import kotlin.math.roundToInt

@Composable
internal fun StopSessionsDialog(
    tabs: List<TerminalTabSnapshot>,
    desktop: DesktopSessionSnapshot,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val systems = tabs.groupBy { it.rootfsName }.map { (name, sessions) ->
        "${terminalDistroTitle(name)} (${sessions.size})"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop all sessions?") },
        text = {
            Text(buildString {
                append("All Linux terminals, jobs and apps will stop.")
                if (systems.isNotEmpty()) append("\n\nOpen tabs: ${systems.joinToString()}")
                if (desktop.phase in setOf(DesktopSessionPhase.STARTING, DesktopSessionPhase.RUNNING, DesktopSessionPhase.STOPPING)) {
                    append("\nDesktop: ${desktop.environmentName} in ${desktop.rootfsName}")
                }
            })
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Stop all sessions") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

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
    onOpenDisplay: () -> Unit,
    scrollbackRows: MutableMap<TerminalSession, Int>,
) {
    val context = LocalContext.current
    val tabs = service?.terminalTabSnapshots().orEmpty()
    val activeTab = tabs.firstOrNull(TerminalTabSnapshot::active)
    val session = service?.currentTerminalSession()
    var tabError by remember { mutableStateOf<String?>(null) }
    var renamingTab by remember { mutableStateOf<TerminalTabSnapshot?>(null) }
    var pendingCloseTabId by remember { mutableStateOf<String?>(null) }
    var creatingTab by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val extraKeysStore = remember(context) { TerminalExtraKeysStore(context) }
    var extraKeysState by remember(extraKeysStore) { mutableStateOf(extraKeysStore.load()) }
    val stopping = snapshot.phase == RuntimePhase.STOPPING

    fun createTab(rootfsName: String?) {
        val connectedService = service
        when {
            connectedService == null -> tabError = "Linux service is reconnecting"
            rootfsName.isNullOrBlank() -> tabError = "Select a running terminal first"
            else -> {
                creatingTab = true
                tabError = null
                connectedService.openTerminal(rootfsName, newTab = true) { result ->
                    creatingTab = false
                    tabError = result.exceptionOrNull()?.message
                }
            }
        }
    }

    fun closeTab(id: String, stopIfLast: Boolean = false) {
        if (service?.closeTerminalTab(id, stopIfLast) != true) {
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
        TerminalWorkspaceBar(
            tabs = tabs,
            activeTab = activeTab,
            installedRootfses = installedRootfses,
            creatingTab = creatingTab,
            canCreate = service != null && installedRootfses.isNotEmpty() && !creatingTab &&
                !stopping && snapshot.phase != RuntimePhase.STARTING,
            onNew = { createTab(activeTab?.rootfsName ?: installedRootfses.firstOrNull()?.name) },
            onNewInDistro = ::createTab,
            onSelect = { service?.selectTerminalTab(it.id) },
            onRename = { renamingTab = it },
            onFollowTitle = { service?.followTerminalShellTitle(it.id) },
            onClose = { tab ->
                if (tabs.size == 1 || tab.commandRunning) pendingCloseTabId = tab.id else closeTab(tab.id)
            },
            onExit = onExit,
            onOpenDisplay = onOpenDisplay,
            onShowSettings = { showSettings = true },
            onStop = onStop,
            stopping = stopping,
        )

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

        if (stopping || activeTab?.closeState?.isClosing == true) {
            val title = when {
                stopping -> "Stopping Linux…"
                activeTab?.closeState == TerminalTabCloseState.FORCE_CLOSING -> "Force closing tab…"
                else -> "Closing tab…"
            }
            ClosingTerminalState(
                modifier = Modifier.weight(1f),
                title = title,
                detail = if (stopping) snapshot.message else activeTab?.shortTitle.orEmpty(),
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
                scrollbackRows = scrollbackRows,
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
                        "Closing the last tab stops Linux, its jobs, apps and any running desktop."
                    } else {
                        "A command is still running in ${tab.title}. Closing the tab will end it."
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingCloseTabId = null
                        closeTab(tab.id, stopIfLast = stopsLinux)
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
private fun ClosingTerminalState(
    modifier: Modifier,
    title: String,
    detail: String,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .background(UdroidTerminal)
                .focusRequester(focus)
                .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LinearProgressIndicator(
                modifier = Modifier.width(128.dp),
                color = UdroidTerminalGreen,
                trackColor = UdroidTerminalRaised,
            )
            Spacer(Modifier.height(18.dp))
            Text(
                title,
                color = UdroidTerminalText,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                detail,
                color = UdroidTerminalMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

internal fun terminalDistribution(rootfsName: String): LinuxDistribution? {
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
    scrollbackRows: MutableMap<TerminalSession, Int>,
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
        terminalView.mTermSession?.let { attached ->
            scrollbackRows.remove(attached)?.let { row ->
                terminalView.doOnLayout {
                    if (terminalView.mTermSession === attached) {
                        val history = attached.emulator?.screen?.activeTranscriptRows ?: 0
                        terminalView.topRow = row.coerceIn(-history, 0)
                        terminalView.invalidate()
                    }
                }
            }
        }
        onDispose {
            terminalView.mTermSession?.let { scrollbackRows[it] = terminalView.topRow }
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
                    view.onScreenUpdated()
                }
            },
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds(),
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
