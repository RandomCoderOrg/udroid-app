package org.randomcoder.udroid.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.randomcoder.udroid.catalog.DistroCatalogState
import org.randomcoder.udroid.catalog.DistroVariant
import org.randomcoder.udroid.install.InstallProgress
import org.randomcoder.udroid.install.InstallStage
import org.randomcoder.udroid.runtime.InstalledRootfs

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun DistroCataloguePage(
    state: DistroCatalogState,
    installedRootfses: List<InstalledRootfs>,
    activeRootfsName: String?,
    onRetry: () -> Unit,
    onPreviewInstall: (DistroVariant) -> Unit,
    onOpenInstalledSystem: (String) -> Unit,
) {
    when (state) {
        DistroCatalogState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Finding Linux images")
                    Text(
                        "Checking uDroid and proot-distro sources",
                        color = UdroidMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        is DistroCatalogState.Failed -> {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = UdroidSurface,
                    border = BorderStroke(1.dp, UdroidLine),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            "Couldn’t load Linux images",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(state.message, color = UdroidMuted)
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = onRetry,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Try again")
                        }
                    }
                }
            }
        }

        is DistroCatalogState.Ready -> {
            val catalogue = state.catalog
            val installedNames =
                remember(installedRootfses) {
                    installedRootfses.mapTo(mutableSetOf(), InstalledRootfs::name)
                }
            val orderedVariants =
                remember(catalogue.variants, installedNames, activeRootfsName) {
                    catalogue.variants.sortedWith(
                        compareBy<DistroVariant> { distro ->
                            when {
                                distro.internalName == activeRootfsName -> 0
                                distro.internalName in installedNames -> 1
                                distro.recommended -> 2
                                else -> 3
                            }
                        }.thenBy { it.releaseName.lowercase() },
                    )
                }
            var searchQuery by remember(catalogue.architecture) { mutableStateOf("") }
            val visibleVariants by
                remember(orderedVariants, searchQuery) {
                    derivedStateOf {
                        val terms =
                            searchQuery
                                .trim()
                                .lowercase()
                                .split(Regex("\\s+"))
                                .filter(String::isNotBlank)
                        if (terms.isEmpty()) {
                            orderedVariants
                        } else {
                            orderedVariants.filter { distro ->
                                terms.all(distro.searchableText::contains)
                            }
                        }
                    }
                }

            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                item {
                    UdroidPageHeader(
                        title = "Linux systems",
                        subtitle =
                            if (installedRootfses.isEmpty()) {
                                "${catalogue.variants.size} compatible systems"
                            } else {
                                "${installedRootfses.size} installed · " +
                                    "${catalogue.variants.size} compatible"
                            },
                        modifier = Modifier.padding(top = 18.dp, bottom = 7.dp),
                    )
                }

                item(key = "distro-search") {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search Ubuntu, Debian, Arch…") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Outlined.Search,
                                contentDescription = null,
                            )
                        },
                        trailingIcon =
                            if (searchQuery.isBlank()) {
                                null
                            } else {
                                {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Close,
                                            contentDescription = "Clear search",
                                        )
                                    }
                                }
                            },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                }

                item(key = "catalogue-count") {
                    UdroidSectionLabel(
                        text =
                            when {
                                searchQuery.isBlank() -> "All systems"
                                visibleVariants.size == 1 -> "1 matching system"
                                else -> "${visibleVariants.size} matching systems"
                            },
                        modifier = Modifier.padding(top = 4.dp, bottom = 1.dp),
                    )
                }

                if (visibleVariants.isEmpty()) {
                    item(key = "empty-search") {
                        Surface(
                            color = UdroidRaised,
                            border = BorderStroke(1.dp, UdroidLine),
                            shape = RoundedCornerShape(11.dp),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "No matching Linux system",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Try a distro, release, desktop, or architecture.",
                                    color = UdroidMuted,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                } else {
                    items(
                        items = visibleVariants,
                        key = { it.id },
                        contentType = { "distro-card" },
                    ) { distro ->
                        val installed = distro.internalName in installedNames
                        DistroCard(
                            distro = distro,
                            installed = installed,
                            active = distro.internalName == activeRootfsName,
                            onSelect = {
                                if (installed) {
                                    onOpenInstalledSystem(distro.internalName)
                                } else {
                                    onPreviewInstall(distro)
                                }
                            },
                        )
                    }
                }

                item { Spacer(Modifier.height(28.dp)) }
            }
        }
    }
}

@Composable
private fun DistroCard(
    distro: DistroVariant,
    installed: Boolean,
    active: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect),
        color = UdroidRaised,
        border =
            BorderStroke(
                1.dp,
                when {
                    active -> UdroidForest.copy(alpha = 0.45f)
                    distro.recommended && !installed -> Color(0xFFF5C8B3)
                    else -> UdroidLine
                },
            ),
        shape = RoundedCornerShape(11.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DistroMark(distribution = distro.distribution, size = 42)
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        distro.releaseName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (installed) {
                        Spacer(Modifier.size(8.dp))
                        UdroidStatusBadge(
                            label = if (active) "Active" else "Installed",
                            color = UdroidForest,
                            background = UdroidSoftGreen,
                        )
                    } else if (distro.recommended) {
                        Spacer(Modifier.size(8.dp))
                        UdroidStatusBadge(
                            label = "Recommended",
                            color = UdroidUbuntu,
                            background = UdroidWarm,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${distro.experienceName} · ${distro.architecture} · ${distro.sourceName}",
                    color = UdroidMuted,
                    maxLines = 1,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (installed) {
                    Text(
                        "Open",
                        color = UdroidForest,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.size(2.dp))
                }
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription =
                        if (installed) {
                            "Open ${distro.releaseName}"
                        } else {
                            "Review ${distro.releaseName}"
                        },
                    tint = if (installed) UdroidForest else UdroidFaint,
                )
            }
        }
    }
}

@Composable
fun InstallExperiencePage(
    progress: InstallProgress,
    showTerminal: Boolean,
    onToggleTerminal: () -> Unit,
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onStartDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onRetryDownload: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!progress.cancellable) {
                item {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (progress.stage == InstallStage.COMPLETE) {
                            "‹  Workspace"
                        } else {
                            "‹  Linux images"
                        },
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onBack)
                                .padding(vertical = 8.dp, horizontal = 2.dp),
                        color = UdroidForest,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            } else {
                item { Spacer(Modifier.height(10.dp)) }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DistroMark(distribution = progress.distro.distribution, size = 48)
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            progress.distro.releaseName,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            "${progress.distro.experienceName} · ${progress.distro.architecture}",
                            color = UdroidMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (progress.previewOnly) {
                item {
                    Surface(
                        color = UdroidWarm,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Row(
                            modifier =
                                Modifier.padding(
                                    horizontal = 12.dp,
                                    vertical = 9.dp,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "PREVIEW",
                                color = UdroidUbuntu,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                "  No rootfs will be downloaded",
                                color = UdroidInk,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            item {
                when {
                    progress.stage == InstallStage.READY -> {
                        Button(
                            onClick = onStartDownload,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Download image")
                        }
                    }

                    progress.cancellable -> {
                        Text(
                            "Pause installation",
                            modifier =
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(onClick = onPauseDownload)
                                    .padding(vertical = 9.dp, horizontal = 2.dp),
                            color = UdroidForest,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }

                    progress.stage == InstallStage.PAUSED -> {
                        Button(
                            onClick = onRetryDownload,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Resume installation")
                        }
                    }

                    progress.stage == InstallStage.FAILED -> {
                        Button(
                            onClick = onRetryDownload,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Try again")
                        }
                    }

                    progress.stage == InstallStage.ARCHIVE_READY -> {
                        Button(
                            onClick = onRetryDownload,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Install verified image")
                        }
                    }

                    progress.stage == InstallStage.COMPLETE -> {
                        Button(
                            onClick = onOpenTerminal,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("Open terminal")
                        }
                    }
                }
            }

            item {
                Surface(
                    color = UdroidSurface,
                    border = BorderStroke(1.dp, UdroidLine),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                progress.stage.stepLabel,
                                color = UdroidMuted,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                "${progress.percentage}%",
                                color = UdroidForest,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Spacer(Modifier.height(9.dp))
                        Text(
                            progress.stage.normalTitle,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(12.dp))
                        InstallStageRail(progress)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = progress.overallProgress,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                            color =
                                if (progress.stage == InstallStage.COMPLETE) {
                                    UdroidForest
                                } else {
                                    UdroidForest
                                },
                            trackColor = UdroidLine,
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            progress.stage.normalSubtitle,
                            color = UdroidMuted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "•  ${progress.currentDetail}",
                            color = UdroidInk,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            item {
                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onToggleTerminal),
                    color = if (showTerminal) UdroidSoftGreen else Color.Transparent,
                    border = BorderStroke(1.dp, UdroidLine),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                ">_",
                                color = UdroidForest,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                if (showTerminal) "  Hide terminal" else "  Show terminal",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Text(
                            if (showTerminal) "↓" else "↑",
                            color = UdroidMuted,
                        )
                    }
                }
            }

            item {
                Text(
                    when {
                        progress.stage == InstallStage.READY ->
                            "Nothing downloads until you press Download image."
                        progress.cancellable ->
                            "You can leave this screen. The foreground service keeps installing."
                        progress.stage == InstallStage.PAUSED ->
                            "Partial downloads or the verified archive remain available for retry."
                        progress.stage == InstallStage.ARCHIVE_READY ->
                            "The archive is cached safely while rootfs setup begins."
                        progress.stage == InstallStage.COMPLETE ->
                            "The verified archive was removed after the rootfs passed its checks."
                        else ->
                            "Open the terminal for exact operation details."
                    },
                    color = UdroidMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(24.dp))
            }
        }

        if (showTerminal) {
            Surface(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.50f),
                color = UdroidTerminal,
                shadowElevation = 18.dp,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
            ) {
                Column {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onToggleTerminal)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                "TERMINAL · INSTALL",
                                color = UdroidTerminalGreen,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                progress.distro.id,
                                color = Color(0xFF8EA99A),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(
                            "Close  ↓",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color(0xFF24342B)),
                    )
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        items(progress.terminalLines) { line ->
                            Text(
                                line,
                                color =
                                    if (line.startsWith("[ok]") || line.startsWith("[complete]")) {
                                        UdroidTerminalGreen
                                    } else {
                                        Color(0xFFD8E2DC)
                                    },
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        item {
                            Text(
                                "▌",
                                color = UdroidTerminalGreen,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val InstallStage.stepLabel: String
    get() {
        val index =
            when (this) {
                InstallStage.READY -> 0
                InstallStage.CHECKING -> 1
                InstallStage.DOWNLOADING -> 2
                InstallStage.VERIFYING -> 3
                InstallStage.ARCHIVE_READY -> 3
                InstallStage.EXTRACTING -> 4
                InstallStage.CONFIGURING, InstallStage.COMPLETE -> 5
                InstallStage.FAILED -> 0
                InstallStage.PAUSED -> 0
            }
        return when (this) {
            InstallStage.READY -> "DOWNLOAD PLAN"
            InstallStage.ARCHIVE_READY -> "STEP 3 OF 5"
            InstallStage.PAUSED -> "DOWNLOAD PAUSED"
            InstallStage.FAILED -> "DOWNLOAD STOPPED"
            else -> "STEP $index OF 5"
        }
    }

@Composable
private fun InstallStageRail(progress: InstallProgress) {
    val stages =
        listOf(
            InstallStage.CHECKING,
            InstallStage.DOWNLOADING,
            InstallStage.VERIFYING,
            InstallStage.EXTRACTING,
            InstallStage.CONFIGURING,
        )
    val currentIndex =
        when (progress.stage) {
            InstallStage.COMPLETE -> stages.lastIndex
            InstallStage.ARCHIVE_READY -> stages.indexOf(InstallStage.VERIFYING)
            InstallStage.READY, InstallStage.FAILED -> -1
            InstallStage.PAUSED ->
                when {
                    progress.overallProgress >= InstallStage.VERIFYING.startFraction ->
                        stages.indexOf(InstallStage.VERIFYING)
                    progress.overallProgress >= InstallStage.DOWNLOADING.startFraction ->
                        stages.indexOf(InstallStage.DOWNLOADING)
                    else -> stages.indexOf(InstallStage.CHECKING)
                }
            else -> stages.indexOf(progress.stage)
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        stages.forEachIndexed { index, _ ->
            val active = currentIndex >= index
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .height(5.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (active) UdroidForest else UdroidLine),
            )
        }
    }
}
