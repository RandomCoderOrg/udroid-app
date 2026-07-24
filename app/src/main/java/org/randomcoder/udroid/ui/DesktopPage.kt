package org.randomcoder.udroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.randomcoder.udroid.runtime.RuntimePhase
import org.randomcoder.udroid.runtime.RuntimeSnapshot
import org.randomcoder.udroid.runtime.RuntimeSupervisorService
import org.randomcoder.udroid.x11.X11DisplayView

@Composable
fun DesktopPage(
    snapshot: RuntimeSnapshot,
    service: RuntimeSupervisorService?,
    onExit: () -> Unit,
) {
    BackHandler(onBack = onExit)
    var displayView by remember { mutableStateOf<X11DisplayView?>(null) }
    var status by remember { mutableStateOf("Waiting for the supervised X11 renderer") }

    DisposableEffect(service, displayView, snapshot.bootId) {
        val view = displayView
        if (
            snapshot.phase == RuntimePhase.RUNNING &&
            service != null &&
            view != null &&
            !view.isRendererAttached
        ) {
            status = "Connecting Android surface to display :0"
            service.requestX11RendererConnection { descriptor ->
                when {
                    descriptor == null -> status = "X11 renderer connection is not ready"
                    !view.isAttachedToWindow -> {
                        descriptor.close()
                    }
                    else -> {
                        view.attachRenderer(descriptor)
                        status = "Display :0 attached"
                    }
                }
            }
        } else if (snapshot.phase != RuntimePhase.RUNNING) {
            status = "Start the Linux runtime before opening Desktop"
        }

        onDispose {
            view?.detachRenderer()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .background(Color(0xFF12131F)),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                IconButton(onClick = onExit) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Close desktop",
                        tint = Color.White,
                    )
                }
                Text(
                    text = status,
                    color = Color.White,
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(end = UdroidSpacing.content.dp),
                )
                IconButton(onClick = { displayView?.showKeyboard() }) {
                    Icon(
                        imageVector = Icons.Outlined.Keyboard,
                        contentDescription = "Show keyboard",
                        tint = Color.White,
                    )
                }
            }
            AndroidView(
                factory = { context ->
                    X11DisplayView(context).also { displayView = it }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
            )
        }
    }
}
