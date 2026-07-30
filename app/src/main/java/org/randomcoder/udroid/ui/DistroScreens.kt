package org.randomcoder.udroid.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.randomcoder.udroid.catalog.DistroCatalogState
import org.randomcoder.udroid.catalog.DistroVariant
import org.randomcoder.udroid.catalog.LinuxDistribution
import org.randomcoder.udroid.install.InstallProgress
import org.randomcoder.udroid.install.InstallStage
import org.randomcoder.udroid.install.InstallerWorkRequest
import org.randomcoder.udroid.install.OciInstallationSelection
import org.randomcoder.udroid.oci.OciHubCatalogueState
import org.randomcoder.udroid.oci.OciHubRepository
import org.randomcoder.udroid.oci.OciHubTagPlatform
import org.randomcoder.udroid.oci.OciHubTagsState
import org.randomcoder.udroid.oci.OciPlatform
import org.randomcoder.udroid.runtime.InstalledRootfs
import java.util.Locale

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun DistroCataloguePage(
    state: DistroCatalogState,
    ociState: OciHubCatalogueState,
    installedRootfses: List<InstalledRootfs>,
    activeRootfsName: String?,
    onRetry: () -> Unit,
    onPreviewInstall: (DistroVariant) -> Unit,
    onSelectOciRepository: (OciHubRepository) -> Unit,
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
            val ociReady = ociState as? OciHubCatalogueState.Ready
            val ociRepositories = ociReady?.snapshot?.repositories.orEmpty()
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
            val visibleOciRepositories by
                remember(ociRepositories, searchQuery) {
                    derivedStateOf {
                        val terms = searchTerms(searchQuery)
                        if (terms.isEmpty()) {
                            ociRepositories
                        } else {
                            ociRepositories.filter { repository ->
                                terms.all(repository.searchableText()::contains)
                            }
                        }
                    }
                }
            val visibleCount = visibleVariants.size + visibleOciRepositories.size
            val totalCount = catalogue.variants.size + ociRepositories.size

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
                                "$totalCount compatible systems"
                            } else {
                                "${installedRootfses.size} installed · " +
                                    "$totalCount compatible"
                            },
                        modifier = Modifier.padding(top = 18.dp, bottom = 7.dp),
                    )
                }

                item(key = "distro-search") {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search Ubuntu, Debian, Fedora…") },
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
                                visibleCount == 1 -> "1 matching system"
                                else -> "$visibleCount matching systems"
                            },
                        modifier = Modifier.padding(top = 4.dp, bottom = 1.dp),
                    )
                }

                if (
                    visibleCount == 0 &&
                    ociState !is OciHubCatalogueState.Loading
                ) {
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
                }

                if (visibleVariants.isNotEmpty()) {
                    item(key = "archive-sources") {
                        UdroidSectionLabel(
                            text = "uDroid and proot-distro",
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
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

                if (
                    searchQuery.isBlank() ||
                    visibleOciRepositories.isNotEmpty() ||
                    ociState !is OciHubCatalogueState.Ready
                ) {
                    item(key = "oci-source") {
                        UdroidSectionLabel(
                            text = "Official container images",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                when (ociState) {
                    OciHubCatalogueState.Loading -> {
                        item(key = "oci-loading") {
                            InlineCatalogueStatus(
                                loading = true,
                                title = "Finding compatible official images",
                                detail = "This does not block the standard Linux images above.",
                            )
                        }
                    }

                    is OciHubCatalogueState.Failed -> {
                        item(key = "oci-failed") {
                            InlineCatalogueStatus(
                                loading = false,
                                title = "Official images are unavailable",
                                detail = ociState.message,
                                actionLabel = "Retry",
                                onAction = onRetry,
                            )
                        }
                    }

                    is OciHubCatalogueState.Ready -> {
                        items(
                            items = visibleOciRepositories,
                            key = { "oci:${it.name}" },
                            contentType = { "oci-repository-card" },
                        ) { repository ->
                            val installed =
                                installedNames.any {
                                    it.startsWith("oci-${repository.name}-")
                                }
                            OciRepositoryCard(
                                repository = repository,
                                architecture = ociState.platform.displayArchitecture(),
                                installed = installed,
                                onSelect = { onSelectOciRepository(repository) },
                            )
                        }
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
private fun OciRepositoryCard(
    repository: OciHubRepository,
    architecture: String,
    installed: Boolean,
    onSelect: () -> Unit,
) {
    val title = OciInstallationSelection.displayName(repository)
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect),
        color = UdroidRaised,
        border = BorderStroke(1.dp, UdroidLine),
        shape = RoundedCornerShape(11.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OciRepositoryMark(repository)
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (installed) {
                        Spacer(Modifier.size(8.dp))
                        UdroidStatusBadge(
                            label = "Installed",
                            color = UdroidForest,
                            background = UdroidSoftGreen,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "Official image · $architecture · Choose version",
                    color = UdroidMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = "Choose a $title version",
                tint = UdroidFaint,
            )
        }
    }
}

@Composable
private fun OciRepositoryMark(repository: OciHubRepository) {
    val distribution =
        when (repository.name) {
            "ubuntu" -> LinuxDistribution.UBUNTU
            "debian" -> LinuxDistribution.DEBIAN
            "alpine" -> LinuxDistribution.ALPINE
            "archlinux" -> LinuxDistribution.ARCH
            else -> null
        }
    if (distribution != null) {
        DistroMark(distribution = distribution, size = 42)
    } else {
        Surface(
            modifier = Modifier.size(42.dp),
            color = UdroidWarm,
            shape = RoundedCornerShape(11.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    repository.name.take(2).uppercase(Locale.US),
                    color = UdroidForest,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun InlineCatalogueStatus(
    loading: Boolean,
    title: String,
    detail: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Surface(
        color = UdroidRaised,
        border = BorderStroke(1.dp, UdroidLine),
        shape = RoundedCornerShape(11.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.size(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text(
                    detail,
                    color = UdroidMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            actionLabel?.let {
                Spacer(Modifier.size(8.dp))
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(9.dp),
                ) {
                    Text(it)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun OciTagCataloguePage(
    repository: OciHubRepository,
    state: OciHubTagsState,
    installedRootfses: List<InstalledRootfs>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSelectTag: (OciHubTagPlatform) -> Unit,
) {
    BackHandler(onBack = onBack)
    val title = OciInstallationSelection.displayName(repository)
    val installedNames =
        remember(installedRootfses) {
            installedRootfses.mapTo(mutableSetOf(), InstalledRootfs::name)
        }
    var searchQuery by remember(repository.name) { mutableStateOf("") }
    val readyTags = (state as? OciHubTagsState.Ready)?.snapshot?.tags.orEmpty()
    val visibleTags by
        remember(readyTags, searchQuery) {
            derivedStateOf {
                val terms = searchTerms(searchQuery)
                if (terms.isEmpty()) {
                    readyTags
                } else {
                    readyTags.filter { tag ->
                        val searchable =
                            listOf(
                                tag.tag,
                                tag.platform.os,
                                tag.platform.architecture,
                                tag.platform.variant.orEmpty(),
                            ).joinToString(" ").lowercase(Locale.US)
                        terms.all(searchable::contains)
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
        item(key = "back") {
            Text(
                "‹  Linux systems",
                modifier =
                    Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onBack)
                        .padding(vertical = 8.dp, horizontal = 2.dp),
                color = UdroidForest,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OciRepositoryMark(repository)
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Official image · choose a compatible version",
                        color = UdroidMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        when (state) {
            OciHubTagsState.Idle,
            OciHubTagsState.Loading
            -> {
                item(key = "loading") {
                    InlineCatalogueStatus(
                        loading = true,
                        title = "Loading versions",
                        detail = "Checking this phone’s architecture against available tags.",
                    )
                }
            }

            is OciHubTagsState.Failed -> {
                item(key = "failed") {
                    InlineCatalogueStatus(
                        loading = false,
                        title = "Versions are unavailable",
                        detail = state.message,
                        actionLabel = "Retry",
                        onAction = onRetry,
                    )
                }
            }

            is OciHubTagsState.Ready -> {
                item(key = "tag-search") {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search versions") },
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
                                            contentDescription = "Clear version search",
                                        )
                                    }
                                }
                            },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                    )
                }
                item(key = "tag-count") {
                    UdroidSectionLabel(
                        text =
                            if (visibleTags.size == 1) {
                                "1 compatible version"
                            } else {
                                "${visibleTags.size} compatible versions"
                            },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (visibleTags.isEmpty()) {
                    item(key = "empty-tags") {
                        InlineCatalogueStatus(
                            loading = false,
                            title = "No matching version",
                            detail = "Try a release number, codename, or latest.",
                        )
                    }
                } else {
                    items(
                        items = visibleTags,
                        key = { it.tag },
                        contentType = { "oci-tag-card" },
                    ) { tag ->
                        val installationName =
                            OciInstallationSelection.installationName(
                                repository.name,
                                tag.tag,
                            )
                        OciTagCard(
                            tag = tag,
                            installed = installationName in installedNames,
                            onSelect = { onSelectTag(tag) },
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun OciTagCard(
    tag: OciHubTagPlatform,
    installed: Boolean,
    onSelect: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect),
        color = UdroidRaised,
        border = BorderStroke(1.dp, UdroidLine),
        shape = RoundedCornerShape(11.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tag.tag, style = MaterialTheme.typography.titleMedium)
                    if (installed) {
                        Spacer(Modifier.size(8.dp))
                        UdroidStatusBadge(
                            label = "Installed",
                            color = UdroidForest,
                            background = UdroidSoftGreen,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "${formatCompactBytes(tag.compressedBytes)} compressed · " +
                        tag.platform.displayLabel(),
                    color = UdroidMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
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
                contentDescription = if (installed) "Open ${tag.tag}" else "Review ${tag.tag}",
                tint = if (installed) UdroidForest else UdroidFaint,
            )
        }
    }
}

private fun searchTerms(query: String): List<String> =
    query
        .trim()
        .lowercase(Locale.US)
        .split(Regex("\\s+"))
        .filter(String::isNotBlank)

private fun OciHubRepository.searchableText(): String =
    listOf(
        name,
        OciInstallationSelection.displayName(this),
        description,
        "official container image docker hub OCI",
    ).joinToString(" ").lowercase(Locale.US)

private fun OciPlatform.displayArchitecture(): String =
    when (architecture) {
        "arm64" -> "aarch64"
        "arm" -> "armhf"
        else -> architecture
    }

private fun OciPlatform.displayLabel(): String =
    listOfNotNull(os, displayArchitecture(), variant).joinToString("/")

private fun formatCompactBytes(bytes: Long): String =
    when {
        bytes >= 1024L * 1024L * 1024L ->
            String.format(Locale.US, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L ->
            String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> String.format(Locale.US, "%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
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
    BackHandler(onBack = onBack)
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
                        } else if (progress.work is InstallerWorkRequest.Oci) {
                            "‹  Versions"
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
                    progress.distribution?.let { distribution ->
                        DistroMark(distribution = distribution, size = 48)
                    } ?: Surface(
                        modifier = Modifier.size(48.dp),
                        color = UdroidWarm,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "OCI",
                                color = UdroidForest,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            progress.displayName,
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            "${progress.experienceName} · ${progress.architecture}",
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
                            "Partial downloads and verified data remain available for retry."
                        progress.stage == InstallStage.ARCHIVE_READY ->
                            "The archive is cached safely while rootfs setup begins."
                        progress.stage == InstallStage.COMPLETE ->
                            "Downloaded image data was removed after the rootfs passed its checks."
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
                                progress.sourceIdentity,
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
            InstallStage.PAUSED -> "INSTALLATION PAUSED"
            InstallStage.FAILED -> "INSTALLATION STOPPED"
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
