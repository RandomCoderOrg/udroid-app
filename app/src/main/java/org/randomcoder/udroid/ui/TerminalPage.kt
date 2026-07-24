package org.randomcoder.udroid.ui

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import kotlin.math.roundToInt

@Composable
fun InteractiveTerminalPage(
    snapshot: RuntimeSnapshot,
    service: RuntimeSupervisorService?,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val session = service?.currentTerminalSession()
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(UdroidCanvas)
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Terminal",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    snapshot.message,
                    color = UdroidMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            if (session?.isRunning == true) {
                OutlinedButton(onClick = onStop) {
                    Text("Stop")
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        if (session == null || !session.isRunning) {
            EmptyTerminalState(
                snapshot = snapshot,
                onStart = onStart,
            )
        } else {
            LiveTerminal(
                service = service,
                session = session,
            )
        }
    }
}

@Composable
private fun EmptyTerminalState(
    snapshot: RuntimeSnapshot,
    onStart: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = UdroidSurface,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                if (snapshot.phase == RuntimePhase.CRASHED) {
                    "The terminal needs attention"
                } else {
                    "Open your installed Linux system"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (snapshot.phase == RuntimePhase.CRASHED) {
                    snapshot.message
                } else {
                    "A real PTY session will continue in the foreground service when you leave this screen."
                },
                color = UdroidMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onStart,
                enabled = snapshot.phase != RuntimePhase.STARTING,
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
        remember(session) {
            UdroidTerminalViewClient(
                context = context,
                modifiers = modifiers,
                initialTextSize = initialTextSize,
            )
        }
    val terminalView =
        remember(session) {
            TerminalView(context, null).apply {
                setBackgroundColor(android.graphics.Color.rgb(11, 20, 16))
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

    Column(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { terminalView },
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
                .height(48.dp)
                .background(Color(0xFF101B16))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalKey(
            label = "CTRL",
            active = modifiers.control,
            onClick = { modifiers.control = !modifiers.control },
        )
        TerminalKey(
            label = "ALT",
            active = modifiers.alt,
            onClick = { modifiers.alt = !modifiers.alt },
        )
        TerminalKey("ESC") { write("\u001B") }
        TerminalKey("TAB") { write("\t") }
        TerminalKey("←") { write("\u001B[D") }
        TerminalKey("↑") { write("\u001B[A") }
        TerminalKey("↓") { write("\u001B[B") }
        TerminalKey("→") { write("\u001B[C") }
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
                .width(if (label.length > 2) 58.dp else 44.dp)
                .height(36.dp)
                .background(
                    color = if (active) UdroidForest else Color(0xFF223029),
                    shape = RoundedCornerShape(7.dp),
                )
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color(0xFFE9F3ED),
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
