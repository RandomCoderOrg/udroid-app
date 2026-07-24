package org.randomcoder.udroid.ui

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import org.randomcoder.udroid.catalog.CatalogSource
import org.randomcoder.udroid.catalog.DistroCatalogState
import org.randomcoder.udroid.catalog.DistroVariant
import org.randomcoder.udroid.install.InstallProgress
import org.randomcoder.udroid.install.InstallStage

private val UbuntuOrange = Color(0xFFE95420)
private val TerminalBlack = Color(0xFF101713)
private val TerminalGreen = Color(0xFFA7F3D0)
private val QuietText = Color(0xFF56635C)

@Composable
fun DistroCataloguePage(
    state: DistroCatalogState,
    onRetry: () -> Unit,
    onPreviewInstall: (DistroVariant) -> Unit,
) {
    when (state) {
        DistroCatalogState.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Finding Linux images")
                    Text(
                        "Checking the uDroid catalogue",
                        color = QuietText,
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
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            "Couldn’t load Linux images",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(state.message, color = QuietText)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onRetry) { Text("Try again") }
                    }
                }
            }
        }

        is DistroCatalogState.Ready -> {
            val catalogue = state.catalog
            val recommended =
                catalogue.variants.firstOrNull { it.recommended }
                    ?: catalogue.variants.first()
            val remaining = catalogue.variants.filterNot { it.id == recommended.id }
            var showAll by remember(catalogue.architecture) { mutableStateOf(false) }

            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Choose your Linux",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Start with the tested choice, or open the full image catalogue.",
                        color = QuietText,
                    )
                }

                item {
                    Text(
                        "RECOMMENDED",
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    DistroCard(
                        distro = recommended,
                        emphasized = true,
                        onSelect = { onPreviewInstall(recommended) },
                    )
                }

                item {
                    val sourceText =
                        when (catalogue.source) {
                            CatalogSource.NETWORK -> "Live uDroid catalogue"
                            CatalogSource.CACHE -> "Saved catalogue · offline"
                            CatalogSource.BUILT_IN -> "Built-in recovery image"
                        }
                    Card(
                        colors =
                            CardDefaults.cardColors(
                                containerColor = Color(0xFFE8EEE9),
                            ),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { showAll = !showAll }
                                    .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (showAll) "Hide advanced images" else "Show all images",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "${remaining.size} compatible variants · $sourceText",
                                    color = QuietText,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Text(
                                if (showAll) "−" else "+",
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                    }
                }

                if (showAll) {
                    items(remaining, key = { it.id }) { distro ->
                        DistroCard(
                            distro = distro,
                            emphasized = false,
                            onSelect = { onPreviewInstall(distro) },
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
    emphasized: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (emphasized) {
                        Color(0xFFFFF4EE)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(UbuntuOrange),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "U",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 14.dp),
            ) {
                Text(
                    distro.releaseName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(distro.experienceName, color = QuietText)
                Text(
                    "${distro.id} · ${distro.architecture}",
                    color = Color(0xFF7A827E),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text("›", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Composable
fun InstallExperiencePage(
    progress: InstallProgress,
    showTerminal: Boolean,
    onToggleTerminal: () -> Unit,
    onBack: () -> Unit,
    onRunAgain: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Spacer(Modifier.height(4.dp))
                Text(
                    "‹  Back to images",
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onBack)
                            .padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier =
                            Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(UbuntuOrange),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "U",
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Column(modifier = Modifier.padding(start = 14.dp)) {
                        Text(
                            progress.distro.releaseName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(progress.distro.experienceName, color = QuietText)
                    }
                }
            }

            if (progress.previewOnly) {
                item {
                    Surface(
                        color = Color(0xFFFFE6A6),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            "UX preview · no rootfs archive is downloaded",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            item {
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    shape = RoundedCornerShape(22.dp),
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            progress.stage.normalTitle,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${progress.percentage}% complete",
                            modifier = Modifier.align(Alignment.End),
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = progress.overallProgress,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                            color =
                                if (progress.stage == InstallStage.COMPLETE) {
                                    Color(0xFF2E7D52)
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(progress.stage.normalSubtitle, color = QuietText)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            progress.currentDetail,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onToggleTerminal) {
                        Text(if (showTerminal) "Hide terminal" else "Show terminal")
                    }
                    if (progress.stage == InstallStage.COMPLETE) {
                        OutlinedButton(onClick = onRunAgain) {
                            Text("Run preview again")
                        }
                    }
                }
            }

            item {
                Text(
                    "The friendly progress and terminal are two views of the same " +
                        "installer events. Closing this screen will not own or cancel " +
                        "the eventual installer service.",
                    color = QuietText,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(40.dp))
            }
        }

        if (showTerminal) {
            Surface(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .fillMaxHeight(0.52f),
                color = TerminalBlack,
                shadowElevation = 24.dp,
                shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            ) {
                Column {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onToggleTerminal)
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                "INSTALL TERMINAL",
                                color = TerminalGreen,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                progress.distro.id,
                                color = Color(0xFF8EA99A),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text("Close  ↓", color = Color.White)
                    }
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color(0xFF29372F)),
                    )
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        items(progress.terminalLines) { line ->
                            Text(
                                line,
                                color =
                                    if (line.startsWith("[ok]") || line.startsWith("[complete]")) {
                                        TerminalGreen
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
                                color = TerminalGreen,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}
