package org.randomcoder.udroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.randomcoder.udroid.catalog.DistroCatalogRepository
import org.randomcoder.udroid.catalog.DistroCatalogState
import org.randomcoder.udroid.catalog.DistroVariant
import org.randomcoder.udroid.install.InstallProgress
import org.randomcoder.udroid.install.InstallationUxPreview
import org.randomcoder.udroid.runtime.CapabilityProbe
import org.randomcoder.udroid.runtime.CapabilityResult
import org.randomcoder.udroid.runtime.CapabilityStatus
import org.randomcoder.udroid.runtime.RuntimePhase
import org.randomcoder.udroid.runtime.RuntimeSnapshot
import org.randomcoder.udroid.runtime.RuntimeSupervisorService
import org.randomcoder.udroid.ui.DistroCataloguePage
import org.randomcoder.udroid.ui.InstallExperiencePage
import java.time.Instant

class MainActivity : ComponentActivity() {
    private val app: UdroidApplication
        get() = application as UdroidApplication

    private var snapshot by mutableStateOf(RuntimeSnapshot())
    private var capabilities by mutableStateOf<List<CapabilityResult>>(emptyList())
    private var journalLines by mutableStateOf<List<String>>(emptyList())
    private var selectedPage by mutableStateOf(Page.HOME)
    private var catalogueState by mutableStateOf<DistroCatalogState>(DistroCatalogState.Loading)
    private var installProgress by mutableStateOf<InstallProgress?>(null)
    private var showInstallTerminal by mutableStateOf(false)
    private var installPreviewJob: Job? = null

    private val stateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                refreshFromDisk()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            UdroidTheme {
                UdroidApp(
                    page = selectedPage,
                    snapshot = snapshot,
                    capabilities = capabilities,
                    journalLines = journalLines,
                    catalogueState = catalogueState,
                    installProgress = installProgress,
                    showInstallTerminal = showInstallTerminal,
                    onPageSelected = { selectedPage = it },
                    onStart = { RuntimeSupervisorService.start(this) },
                    onStop = { RuntimeSupervisorService.stop(this) },
                    onRefresh = { refreshAll() },
                    onReloadCatalogue = { loadCatalogue() },
                    onPreviewInstall = { startInstallationPreview(it) },
                    onToggleInstallTerminal = {
                        showInstallTerminal = !showInstallTerminal
                    },
                    onCloseInstall = {
                        installPreviewJob?.cancel()
                        installProgress = null
                        showInstallTerminal = false
                    },
                    onRestartInstallPreview = {
                        installProgress?.distro?.let { startInstallationPreview(it) }
                    },
                )
            }
        }
        refreshAll()
        loadCatalogue()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter(RuntimeSupervisorService.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refreshFromDisk()
    }

    override fun onStop() {
        unregisterReceiver(stateReceiver)
        super.onStop()
    }

    private fun refreshAll() {
        refreshFromDisk()
        lifecycleScope.launch {
            capabilities = withContext(Dispatchers.IO) { CapabilityProbe.run(this@MainActivity) }
        }
    }

    private fun refreshFromDisk() {
        snapshot = app.runtimeState.current()
        journalLines = app.journal.tail()
    }

    private fun loadCatalogue() {
        catalogueState = DistroCatalogState.Loading
        lifecycleScope.launch {
            catalogueState =
                runCatching {
                    withContext(Dispatchers.IO) {
                        DistroCatalogRepository(this@MainActivity).load()
                    }
                }.fold(
                    onSuccess = { DistroCatalogState.Ready(it) },
                    onFailure = {
                        DistroCatalogState.Failed(
                            it.message ?: "The distro catalogue could not be read",
                        )
                    },
                )
        }
    }

    private fun startInstallationPreview(distro: DistroVariant) {
        installPreviewJob?.cancel()
        selectedPage = Page.DISTROS
        showInstallTerminal = false
        installProgress = InstallationUxPreview.initial(distro)
        installPreviewJob =
            lifecycleScope.launch {
                val terminalLines = installProgress?.terminalLines.orEmpty().toMutableList()
                InstallationUxPreview.steps(distro).forEach { step ->
                    delay(step.delayMs)
                    terminalLines += step.terminalLine
                    installProgress =
                        InstallProgress(
                            distro = distro,
                            stage = step.stage,
                            stageProgress = step.stageProgress,
                            currentDetail = step.detail,
                            terminalLines = terminalLines.toList(),
                            previewOnly = true,
                        )
                }
            }
    }
}

private enum class Page(val label: String) {
    HOME("Home"),
    DISTROS("Linux"),
    DEVICE("Device"),
    LOGS("Logs"),
}

@Composable
private fun UdroidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme =
            androidx.compose.material3.lightColorScheme(
                primary = Color(0xFF315A47),
                onPrimary = Color.White,
                primaryContainer = Color(0xFFD4E9DC),
                surface = Color(0xFFF7F7F2),
                background = Color(0xFFF0F2EC),
                error = Color(0xFFB3261E),
            ),
        content = content,
    )
}

@Composable
private fun UdroidApp(
    page: Page,
    snapshot: RuntimeSnapshot,
    capabilities: List<CapabilityResult>,
    journalLines: List<String>,
    catalogueState: DistroCatalogState,
    installProgress: InstallProgress?,
    showInstallTerminal: Boolean,
    onPageSelected: (Page) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
    onReloadCatalogue: () -> Unit,
    onPreviewInstall: (DistroVariant) -> Unit,
    onToggleInstallTerminal: () -> Unit,
    onCloseInstall: () -> Unit,
    onRestartInstallPreview: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                Text(
                    text = "uDroid",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Linux on Android, without living in a terminal",
                    color = Color(0xFF56635C),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                Page.entries.forEach { item ->
                    if (page == item) {
                        Button(
                            onClick = { onPageSelected(item) },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .padding(horizontal = 3.dp),
                        ) {
                            Text(item.label)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onPageSelected(item) },
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .padding(horizontal = 3.dp),
                        ) {
                            Text(item.label)
                        }
                    }
                }
            }

            when (page) {
                Page.HOME ->
                    HomePage(
                        snapshot = snapshot,
                        onStart = onStart,
                        onStop = onStop,
                        onRefresh = onRefresh,
                    )
                Page.DISTROS ->
                    installProgress?.let {
                        InstallExperiencePage(
                            progress = it,
                            showTerminal = showInstallTerminal,
                            onToggleTerminal = onToggleInstallTerminal,
                            onBack = onCloseInstall,
                            onRunAgain = onRestartInstallPreview,
                        )
                    } ?: DistroCataloguePage(
                        state = catalogueState,
                        onRetry = onReloadCatalogue,
                        onPreviewInstall = onPreviewInstall,
                    )
                Page.DEVICE -> DevicePage(capabilities, onRefresh)
                Page.LOGS -> LogsPage(journalLines, onRefresh)
            }
        }
    }
}

@Composable
private fun HomePage(
    snapshot: RuntimeSnapshot,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
) {
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            StatusCard(snapshot)
        }
        item {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        "Phase 1 development runtime",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This button starts a tiny packaged Linux process under the same " +
                            "supervisor that will later own PRoot, your distro, and its session.",
                        color = Color(0xFF56635C),
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = onStart,
                            enabled =
                                snapshot.phase != RuntimePhase.RUNNING &&
                                    snapshot.phase != RuntimePhase.STARTING,
                        ) {
                            Text("Start test runtime")
                        }
                        OutlinedButton(
                            onClick = onStop,
                            enabled =
                                snapshot.phase == RuntimePhase.RUNNING ||
                                    snapshot.phase == RuntimePhase.STARTING,
                        ) {
                            Text("Stop")
                        }
                    }
                }
            }
        }
        item {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = Color(0xFFFFF7D6),
                    ),
                shape = RoundedCornerShape(18.dp),
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        "What comes next",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Distro catalogue → resumable install → boot profiles → graphical " +
                            "desktop. Hardware acceleration stays an optional device profile.",
                    )
                }
            }
        }
        item {
            OutlinedButton(onClick = onRefresh) {
                Text("Refresh status")
            }
        }
    }
}

@Composable
private fun StatusCard(snapshot: RuntimeSnapshot) {
    val accent =
        when (snapshot.phase) {
            RuntimePhase.RUNNING -> Color(0xFF2E7D52)
            RuntimePhase.CRASHED -> MaterialTheme.colorScheme.error
            RuntimePhase.STARTING, RuntimePhase.STOPPING -> Color(0xFF9A6700)
            RuntimePhase.STOPPED -> Color(0xFF5D6460)
        }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                snapshot.phase.name.lowercase().replaceFirstChar { it.titlecase() },
                color = accent,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(snapshot.message)
            snapshot.childPid?.let {
                Spacer(Modifier.height(10.dp))
                Text("Process $it", fontFamily = FontFamily.Monospace)
            }
            snapshot.heartbeatSequence?.let {
                Text("Heartbeat $it", fontFamily = FontFamily.Monospace)
            }
            snapshot.bootId?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Boot ${it.take(8)}",
                    color = Color(0xFF66716B),
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text(
                Instant.ofEpochMilli(snapshot.updatedAtEpochMs).toString(),
                color = Color(0xFF7A827E),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DevicePage(
    capabilities: List<CapabilityResult>,
    onRefresh: () -> Unit,
) {
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Compatibility",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Core uDroid requirements are separate from optional display and " +
                    "acceleration features.",
                color = Color(0xFF56635C),
            )
        }
        items(capabilities) { capability ->
            CapabilityCard(capability)
        }
        item {
            OutlinedButton(onClick = onRefresh) {
                Text("Run probes again")
            }
        }
    }
}

@Composable
private fun CapabilityCard(capability: CapabilityResult) {
    val label =
        when (capability.status) {
            CapabilityStatus.PASS -> "Available"
            CapabilityStatus.FAIL -> if (capability.required) "Required" else "Unavailable"
            CapabilityStatus.INFO -> "Detected"
        }
    val color =
        when (capability.status) {
            CapabilityStatus.PASS -> Color(0xFF2E7D52)
            CapabilityStatus.FAIL ->
                if (capability.required) MaterialTheme.colorScheme.error else Color(0xFF8A6A17)
            CapabilityStatus.INFO -> Color(0xFF435E78)
        }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(capability.name, fontWeight = FontWeight.SemiBold)
                Text(label, color = color, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                capability.detail,
                color = Color(0xFF66716B),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LogsPage(
    journalLines: List<String>,
    onRefresh: () -> Unit,
) {
    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Supervisor journal",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                OutlinedButton(onClick = onRefresh) {
                    Text("Refresh")
                }
            }
        }
        if (journalLines.isEmpty()) {
            item { Text("No events yet.") }
        } else {
            items(journalLines) { line ->
                val payload = runCatching { JSONObject(line) }.getOrNull()
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            payload?.optString("event").orEmpty().ifBlank { "event" },
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            payload?.optString("message").orEmpty().ifBlank { line },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            payload?.optString("timestamp").orEmpty(),
                            color = Color(0xFF7A827E),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
