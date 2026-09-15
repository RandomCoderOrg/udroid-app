package org.randomcoder.udroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import org.randomcoder.udroid.runtime.PROOT_DEFAULT_MOUNTS
import org.randomcoder.udroid.runtime.ProotMountProfile
import org.randomcoder.udroid.runtime.ProotMountProfileStore
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                    Text("Loading Linux systems")
                    Text(
                        "From uDroid and proot-distro",
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
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            "Couldn’t load Linux systems",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(state.message, color = UdroidMuted)
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = onRetry,
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
                        placeholder = { Text("Search Linux systems") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Search,
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
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Clear search",
                                        )
                                    }
                                }
                            },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
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
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    "No systems found",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Search by distribution, release, desktop, or architecture",
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
                            text = "More official images",
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                when (ociState) {
                    OciHubCatalogueState.Loading -> {
                        item(key = "oci-loading") {
                            InlineCatalogueStatus(
                                loading = true,
                                title = "Finding more Linux systems",
                                detail = "Systems listed above are still available",
                            )
                        }
                    }

                    is OciHubCatalogueState.Failed -> {
                        item(key = "oci-failed") {
                            InlineCatalogueStatus(
                                loading = false,
                                title = "Can’t load more Linux systems",
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
    OutlinedCard(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.outlinedCardColors(
                containerColor =
                    if (active) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        border =
            when {
                active -> BorderStroke(1.dp, UdroidForest.copy(alpha = 0.45f))
                distro.recommended && !installed -> BorderStroke(1.dp, UdroidUbuntu.copy(alpha = 0.55f))
                else -> CardDefaults.outlinedCardBorder()
            },
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { DistroMark(distribution = distro.distribution, size = 42) },
            headlineContent = {
                Text(
                    distro.releaseName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            supportingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val status =
                        when {
                            installed && active -> "Active"
                            installed -> "Installed"
                            distro.recommended -> "Recommended"
                            else -> null
                        }
                    status?.let {
                        Text(
                            "$it · ",
                            color = if (installed) UdroidForest else UdroidUbuntu,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    Text(
                        "${distro.experienceName} · ${distro.architecture} · ${distro.sourceName}",
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
            trailingContent = {
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
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription =
                            if (installed) {
                                "Open ${distro.releaseName}"
                            } else {
                                "Review ${distro.releaseName}"
                            },
                        tint = if (installed) UdroidForest else UdroidFaint,
                    )
                }
            },
        )
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
    OutlinedCard(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { OciRepositoryMark(repository) },
            headlineContent = {
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
            },
            supportingContent = {
                Text(
                    "Official image · $architecture · Choose version",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = "Choose a $title version",
                    tint = UdroidFaint,
                )
            },
        )
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
            shape = MaterialTheme.shapes.medium,
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
        shape = MaterialTheme.shapes.medium,
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
                        .clip(MaterialTheme.shapes.small)
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
                        "Choose a version that works on this device",
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
                        title = "Finding compatible versions",
                        detail = "Checking which versions work on this device",
                    )
                }
            }

            is OciHubTagsState.Failed -> {
                item(key = "failed") {
                    InlineCatalogueStatus(
                        loading = false,
                        title = "Couldn’t load versions",
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
                                imageVector = Icons.Rounded.Search,
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
                                            imageVector = Icons.Rounded.Close,
                                            contentDescription = "Clear version search",
                                        )
                                    }
                                }
                            },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
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
                            detail = "Search by release number or name",
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
    OutlinedCard(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = {
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
            },
            supportingContent = {
                Text(
                    "${formatCompactBytes(tag.compressedBytes)} compressed · " +
                        tag.platform.displayLabel(),
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            trailingContent = {
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
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = if (installed) "Open ${tag.tag}" else "Review ${tag.tag}",
                        tint = if (installed) UdroidForest else UdroidFaint,
                    )
                }
            },
        )
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
@OptIn(ExperimentalMaterial3Api::class)
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
    val context = LocalContext.current
    val mountProfileStore = remember(context) { ProotMountProfileStore(context) }
    val scope = rememberCoroutineScope()
    var showMountProfile by remember(progress.installationName) { mutableStateOf(false) }
    var mountProfile by remember(progress.installationName) {
        mutableStateOf(
            runCatching { mountProfileStore.load(progress.installationName) }
                .getOrDefault(ProotMountProfile()),
        )
    }
    var mountProfileMessage by remember(progress.installationName) {
        mutableStateOf<String?>(null)
    }
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
                    TextButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = null,
                        )
                        Text(
                            if (progress.stage == InstallStage.COMPLETE) {
                                "Workspace"
                            } else if (progress.work is InstallerWorkRequest.Oci) {
                                "Versions"
                            } else {
                                "Linux systems"
                            },
                        )
                    }
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
                        shape = MaterialTheme.shapes.medium,
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
                        shape = MaterialTheme.shapes.medium,
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
                                "Preview",
                                color = UdroidUbuntu,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                "  Nothing will be downloaded",
                                color = UdroidInk,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            item {
                val enabledDefaults =
                    PROOT_DEFAULT_MOUNTS.count { mountProfile.isDefaultEnabled(it.id) }
                val enabledCustom = mountProfile.customMounts.count { it.enabled }
                OutlinedCard(
                    onClick = { showMountProfile = true },
                    enabled = !progress.cancellable,
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.outlinedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                ) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            Surface(
                                modifier = Modifier.size(40.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.medium,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Rounded.Settings,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        },
                        headlineContent = {
                            Text("File access", style = MaterialTheme.typography.titleMedium)
                        },
                        supportingContent = {
                            Text(
                                mountProfileMessage
                                    ?: "$enabledDefaults default mounts · $enabledCustom custom",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (progress.cancellable) "Locked" else "Configure",
                                    color = MaterialTheme.colorScheme.secondary,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                if (!progress.cancellable) {
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Rounded.ChevronRight,
                                        contentDescription = null,
                                    )
                                }
                            }
                        },
                    )
                }
            }

            item {
                val containerColor =
                    when (progress.stage) {
                        InstallStage.COMPLETE -> MaterialTheme.colorScheme.secondaryContainer
                        InstallStage.FAILED -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                    }
                val contentColor =
                    when (progress.stage) {
                        InstallStage.COMPLETE -> MaterialTheme.colorScheme.onSecondaryContainer
                        InstallStage.FAILED -> MaterialTheme.colorScheme.onErrorContainer
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = containerColor,
                            contentColor = contentColor,
                        ),
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    progress.stage.stepLabel,
                                    color = contentColor.copy(alpha = 0.72f),
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    progress.stage.normalTitle,
                                    style = MaterialTheme.typography.headlineSmall,
                                )
                            }
                            if (progress.stage == InstallStage.COMPLETE) {
                                Spacer(Modifier.size(16.dp))
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = "Installation complete",
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        InstallStageTimeline(progress)
                        Spacer(Modifier.height(18.dp))
                        Text(
                            progress.stage.normalSubtitle,
                            color = contentColor.copy(alpha = 0.78f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (progress.stage != InstallStage.COMPLETE) {
                            Spacer(Modifier.height(5.dp))
                            Text(
                                progress.currentDetail,
                                style = MaterialTheme.typography.bodyMedium,
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
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Download image")
                        }
                    }

                    progress.cancellable -> {
                        OutlinedButton(
                            onClick = onPauseDownload,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Pause installation")
                        }
                    }

                    progress.stage == InstallStage.PAUSED -> {
                        Button(
                            onClick = onRetryDownload,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Resume installation")
                        }
                    }

                    progress.stage == InstallStage.FAILED -> {
                        Button(
                            onClick = onRetryDownload,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Try again")
                        }
                    }

                    progress.stage == InstallStage.ARCHIVE_READY -> {
                        Button(
                            onClick = onRetryDownload,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Install verified image")
                        }
                    }

                    progress.stage == InstallStage.COMPLETE -> {
                        Button(
                            onClick = onOpenTerminal,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Open terminal")
                        }
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = onToggleTerminal,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Terminal,
                        contentDescription = null,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("View install log")
                }
            }

            if (
                progress.cancellable ||
                progress.stage == InstallStage.PAUSED ||
                progress.stage == InstallStage.ARCHIVE_READY
            ) item {
                Text(
                    when {
                        progress.cancellable ->
                            "You can leave this screen while installation continues"
                        progress.stage == InstallStage.PAUSED ->
                            "Your download is saved and ready to resume"
                        progress.stage == InstallStage.ARCHIVE_READY ->
                            "The verified image is saved while installation continues"
                        else -> ""
                    },
                    color = UdroidMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(24.dp))
            }
        }

        if (showTerminal) {
            val logListState = rememberLazyListState()
            LaunchedEffect(progress.terminalLines.size) {
                logListState.scrollToItem(progress.terminalLines.size)
            }
            ModalBottomSheet(
                onDismissRequest = onToggleTerminal,
                containerColor = UdroidTerminal,
                contentColor = UdroidTerminalText,
                dragHandle = {
                    BottomSheetDefaults.DragHandle(color = UdroidTerminalMuted)
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.72f),
            ) {
                Column {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 20.dp, end = 8.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            modifier = Modifier.size(40.dp),
                            color = UdroidTerminalRaised,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Terminal,
                                    contentDescription = null,
                                    tint = UdroidTerminalGreen,
                                )
                            }
                        }
                        Column(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                        ) {
                            Text(
                                "Install log",
                                color = UdroidTerminalText,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                "${progress.stage.normalTitle}  •  ${progress.sourceIdentity}",
                                color = UdroidTerminalMuted,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = onToggleTerminal) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Close install log",
                                tint = UdroidTerminalMuted,
                            )
                        }
                    }
                    HorizontalDivider(color = UdroidTerminalLine)
                    SelectionContainer(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            state = logListState,
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            items(progress.terminalLines) { line ->
                                val isSuccess =
                                    line.startsWith("[ok]") ||
                                        line.startsWith("[complete]") ||
                                        line.startsWith("[ready]")
                                val isFailure =
                                    line.startsWith("[error]") || line.startsWith("[failed]")
                                Text(
                                    line,
                                    color =
                                        when {
                                            isSuccess -> UdroidTerminalGreen
                                            isFailure -> Color(0xFFFFB4AB)
                                            else -> UdroidTerminalText
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

        if (showMountProfile) {
            ProotMountProfileDialog(
                systemName = progress.displayName,
                initialProfile = mountProfile,
                onDismiss = { showMountProfile = false },
                onSave = { profile ->
                    scope.launch {
                        val saved =
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    mountProfileStore.save(progress.installationName, profile)
                                }
                            }
                        saved.fold(
                            onSuccess = {
                                mountProfile = it
                                mountProfileMessage = "Profile saved for this distro."
                                showMountProfile = false
                            },
                            onFailure = {
                                mountProfileMessage =
                                    it.message ?: "The mount profile could not be saved"
                            },
                        )
                    }
                },
            )
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
            InstallStage.READY -> "Ready"
            InstallStage.ARCHIVE_READY -> "Step 3 of 5"
            InstallStage.COMPLETE -> "5 steps complete"
            InstallStage.PAUSED -> "Paused"
            InstallStage.FAILED -> "Needs attention"
            else -> "Step $index of 5"
        }
    }

@Composable
private fun InstallStageTimeline(progress: InstallProgress) {
    val stages =
        listOf(
            InstallStage.CHECKING to "Check device",
            InstallStage.DOWNLOADING to "Download image",
            InstallStage.VERIFYING to "Verify download",
            InstallStage.EXTRACTING to "Install files",
            InstallStage.CONFIGURING to "Set up Linux",
        )
    val currentIndex =
        when (progress.stage) {
            InstallStage.COMPLETE -> stages.lastIndex
            InstallStage.ARCHIVE_READY -> stages.indexOfFirst { it.first == InstallStage.VERIFYING }
            InstallStage.READY -> -1
            InstallStage.PAUSED, InstallStage.FAILED ->
                when {
                    progress.overallProgress >= InstallStage.CONFIGURING.startFraction ->
                        stages.indexOfFirst { it.first == InstallStage.CONFIGURING }
                    progress.overallProgress >= InstallStage.EXTRACTING.startFraction ->
                        stages.indexOfFirst { it.first == InstallStage.EXTRACTING }
                    progress.overallProgress >= InstallStage.VERIFYING.startFraction ->
                        stages.indexOfFirst { it.first == InstallStage.VERIFYING }
                    progress.overallProgress >= InstallStage.DOWNLOADING.startFraction ->
                        stages.indexOfFirst { it.first == InstallStage.DOWNLOADING }
                    else -> stages.indexOfFirst { it.first == InstallStage.CHECKING }
                }
            else -> stages.indexOfFirst { it.first == progress.stage }
        }

    Column {
        stages.forEachIndexed { index, (stage, label) ->
            val segmentProgress =
                when {
                    progress.stage == InstallStage.COMPLETE || index < currentIndex -> 1f
                    index > currentIndex || currentIndex < 0 -> 0f
                    progress.stage == InstallStage.ARCHIVE_READY -> 1f
                    progress.stage == InstallStage.PAUSED || progress.stage == InstallStage.FAILED -> {
                        ((progress.overallProgress - stage.startFraction) / stage.weight)
                            .coerceIn(0f, 1f)
                    }
                    else -> progress.stageProgress.coerceIn(0f, 1f)
                }
            val completed = progress.stage == InstallStage.COMPLETE || index < currentIndex
            val current = index == currentIndex && progress.stage != InstallStage.COMPLETE
            val determinate =
                when {
                    progress.stage == InstallStage.PAUSED || progress.stage == InstallStage.FAILED -> true
                    stage == InstallStage.CHECKING -> false
                    stage == InstallStage.CONFIGURING || stage == InstallStage.ARCHIVE_READY -> true
                    else -> progress.totalBytes > 0L
                }
            Row(
                verticalAlignment = Alignment.Top,
            ) {
                Surface(
                    modifier = Modifier.size(24.dp),
                    color =
                        if (completed || current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor =
                        if (completed || current) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    border =
                        if (completed || current) null
                        else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = CircleShape,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (completed) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "$label complete",
                                modifier = Modifier.size(15.dp),
                            )
                        } else {
                            Text(
                                "${index + 1}",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label,
                            color =
                                if (current) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (current && determinate) {
                            Text(
                                "${(segmentProgress * 100f).toInt()}%",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    if (current) {
                        Spacer(Modifier.height(7.dp))
                        if (determinate) {
                            LinearProgressIndicator(
                                progress = { segmentProgress },
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.outlineVariant,
                                drawStopIndicator = {},
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(6.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
            }
            if (index < stages.lastIndex) {
                Box(
                    modifier =
                        Modifier
                            .width(24.dp)
                            .height(18.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(
                                    if (index < currentIndex) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                                ),
                    )
                }
            }
        }
    }
}
