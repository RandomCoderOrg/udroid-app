package org.randomcoder.udroid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
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
import org.randomcoder.udroid.ui.UdroidCanvas
import org.randomcoder.udroid.ui.UdroidForest
import org.randomcoder.udroid.ui.UdroidInk
import org.randomcoder.udroid.ui.UdroidLine
import org.randomcoder.udroid.ui.UdroidMuted
import org.randomcoder.udroid.ui.UdroidSoftGreen
import org.randomcoder.udroid.ui.UdroidSurface
import org.randomcoder.udroid.ui.UdroidTheme
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
        color = UdroidCanvas,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .background(UdroidSurface)
                        .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(24.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(UdroidForest),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "u",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Text(
                    text = "uDroid",
                    modifier = Modifier.padding(start = 9.dp),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "LOCAL",
                    color = UdroidMuted,
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
            ) {
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

            BottomNavigation(
                selected = page,
                onSelected = onPageSelected,
            )
        }
    }
}

@Composable
private fun BottomNavigation(
    selected: Page,
    onSelected: (Page) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(UdroidSurface),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(UdroidLine),
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(64.dp),
        ) {
            Page.entries.forEach { item ->
                val active = selected == item
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { onSelected(item) }
                            .padding(top = 9.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(width = 22.dp, height = 3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (active) UdroidForest else Color.Transparent),
                    )
                    Text(
                        item.label,
                        color = if (active) UdroidForest else UdroidMuted,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    )
                }
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
                .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 18.dp, bottom = 2.dp)) {
                Text(
                    "Your Linux workspace",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Install a system, start it, and open a shell or desktop.",
                    color = UdroidMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            StatusCard(snapshot)
        }
        item {
            Surface(
                color = UdroidSurface,
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Runtime probe",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Test the supervisor with a small packaged process before a distro is installed.",
                        color = UdroidMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = onStart,
                            enabled =
                                snapshot.phase != RuntimePhase.RUNNING &&
                                    snapshot.phase != RuntimePhase.STARTING,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Start probe")
                        }
                        OutlinedButton(
                            onClick = onStop,
                            enabled =
                                snapshot.phase == RuntimePhase.RUNNING ||
                                    snapshot.phase == RuntimePhase.STARTING,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Stop")
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Refresh device status  ↻",
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onRefresh)
                        .padding(vertical = 10.dp, horizontal = 2.dp),
                color = UdroidForest,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(18.dp))
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
    Surface(
        color = UdroidSurface,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(accent),
                )
                Text(
                    snapshot.phase.name.lowercase().replaceFirstChar { it.titlecase() },
                    modifier = Modifier.padding(start = 9.dp),
                    color = UdroidInk,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                snapshot.message,
                color = UdroidMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            snapshot.childPid?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    "pid $it",
                    color = UdroidForest,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            snapshot.heartbeatSequence?.let {
                Text(
                    "heartbeat $it",
                    color = UdroidForest,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            snapshot.bootId?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    "boot ${it.take(8)}  ·  ${Instant.ofEpochMilli(snapshot.updatedAtEpochMs)}",
                    color = UdroidMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
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
                .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            Text(
                "Compatibility",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "Core requirements and optional device features.",
                color = UdroidMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(capabilities) { capability ->
            CapabilityCard(capability)
        }
        item {
            Text(
                "Run probes again  ↻",
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onRefresh)
                        .padding(vertical = 10.dp, horizontal = 2.dp),
                color = UdroidForest,
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(18.dp))
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
    Surface(
        color = UdroidSurface,
        shape = RoundedCornerShape(12.dp),
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
                color = UdroidMuted,
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
                .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Supervisor journal",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    "Refresh  ↻",
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onRefresh)
                            .padding(8.dp),
                    color = UdroidForest,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
        if (journalLines.isEmpty()) {
            item { Text("No events yet.") }
        } else {
            items(journalLines) { line ->
                val payload = runCatching { JSONObject(line) }.getOrNull()
                Surface(
                    color = UdroidSurface,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp)) {
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
                            color = UdroidMuted,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}
