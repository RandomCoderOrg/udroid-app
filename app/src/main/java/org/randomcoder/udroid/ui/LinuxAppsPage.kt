package org.randomcoder.udroid.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.randomcoder.udroid.linuxapps.LinuxApplication
import org.randomcoder.udroid.linuxapps.LinuxApplicationsState

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun LinuxAppsPage(
    state: LinuxApplicationsState,
    launchMessage: String?,
    onRefresh: () -> Unit,
    onLaunch: (LinuxApplication) -> Unit,
    onOpenDesktop: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Linux apps",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text =
                        when (state) {
                            LinuxApplicationsState.Loading ->
                                "Reading installed desktop entries"
                            is LinuxApplicationsState.Ready -> {
                                val count = state.result.applications.size
                                "$count ${if (count == 1) "app" else "apps"} · " +
                                    "${state.rootfsName} · ${state.result.elapsedMillis} ms"
                            }
                            is LinuxApplicationsState.Failed -> "Application catalogue unavailable"
                        },
                    color = UdroidMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            IconButton(onClick = onOpenDesktop) {
                Icon(
                    imageVector = Icons.Outlined.DesktopWindows,
                    contentDescription = "Open desktop",
                    tint = UdroidForest,
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Refresh Linux apps",
                    tint = UdroidForest,
                )
            }
        }

        if (!launchMessage.isNullOrBlank()) {
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                shape = RoundedCornerShape(12.dp),
                color = UdroidSoftGreen,
            ) {
                Text(
                    text = launchMessage,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = UdroidForest,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        when (state) {
            LinuxApplicationsState.Loading ->
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = UdroidForest)
                }
            is LinuxApplicationsState.Failed ->
                LinuxAppsEmptyState(
                    title = "Could not read Linux apps",
                    body = state.message,
                )
            is LinuxApplicationsState.Ready -> {
                val applications =
                    remember(state, query) {
                        state.result.applications.filter { application ->
                            query.isBlank() ||
                                application.name.contains(query, ignoreCase = true) ||
                                application.genericName
                                    ?.contains(query, ignoreCase = true) == true ||
                                application.categories.any {
                                    it.contains(query, ignoreCase = true)
                                }
                        }
                    }
                if (state.result.applications.isNotEmpty()) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                        singleLine = true,
                        label = { Text("Search installed apps") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = null,
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                    )
                }
                if (applications.isEmpty()) {
                    LinuxAppsEmptyState(
                        title =
                            if (query.isBlank()) {
                                "No launchable apps yet"
                            } else {
                                "No matching apps"
                            },
                        body =
                            if (query.isBlank()) {
                                "Install a Linux GUI package with a .desktop entry, then refresh."
                            } else {
                                "Try a different app name or category."
                            },
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = applications,
                            key = LinuxApplication::id,
                        ) { application ->
                            LinuxApplicationCard(
                                application = application,
                                onLaunch = { onLaunch(application) },
                            )
                        }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LinuxApplicationCard(
    application: LinuxApplication,
    onLaunch: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = UdroidSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, UdroidLine),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LinuxApplicationIcon(application)
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = application.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        application.genericName
                            ?: application.comment
                            ?: application.executable,
                    color = UdroidMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.padding(top = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector =
                            if (application.terminal) {
                                Icons.Outlined.Terminal
                            } else {
                                Icons.Outlined.DesktopWindows
                            },
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = UdroidForest,
                    )
                    Text(
                        text = if (application.terminal) " TERMINAL" else " DISPLAY :0",
                        color = UdroidForest,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(Modifier.size(10.dp))
            Button(onClick = onLaunch) {
                Text(if (application.terminal) "Open" else "Launch")
            }
        }
    }
}

@Composable
private fun LinuxApplicationIcon(application: LinuxApplication) {
    val bitmap by
        produceState<ImageBitmap?>(
            initialValue = null,
            key1 = application.iconPath,
        ) {
            value =
                withContext(Dispatchers.IO) {
                    application.iconPath
                        ?.let(BitmapFactory::decodeFile)
                        ?.asImageBitmap()
                }
        }
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = Modifier.size(58.dp),
        shape = shape,
        color = UdroidSoftGreen,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = checkNotNull(bitmap),
                contentDescription = null,
                modifier = Modifier.padding(8.dp),
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(UdroidSoftGreen),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = categoryIcon(application.categories),
                    contentDescription = null,
                    tint = UdroidForest,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

private fun categoryIcon(categories: List<String>): ImageVector =
    when {
        categories.any { it in setOf("Development", "IDE") } -> Icons.Outlined.Code
        categories.any { it in setOf("Network", "WebBrowser") } -> Icons.Outlined.Public
        categories.any { it in setOf("Graphics", "Photography") } -> Icons.Outlined.Palette
        categories.any { it in setOf("Settings", "System") } -> Icons.Outlined.Settings
        else -> Icons.Outlined.Apps
    }

@Composable
private fun LinuxAppsEmptyState(
    title: String,
    body: String,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(28.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Apps,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = UdroidMuted,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                color = UdroidMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}
