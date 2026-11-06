package org.randomcoder.udroid.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.randomcoder.udroid.runtime.TerminalColorProfile
import org.randomcoder.udroid.runtime.TerminalProfileState
import org.randomcoder.udroid.runtime.exportTermuxProfile
import org.randomcoder.udroid.runtime.formatTerminalColor
import org.randomcoder.udroid.runtime.importTerminalProfile
import org.randomcoder.udroid.runtime.parseTerminalColor
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun TerminalSettingsPage(
    modifier: Modifier = Modifier,
    state: TerminalProfileState,
    onBack: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onSaveProfile: (TerminalColorProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editingProfile by remember { mutableStateOf<TerminalColorProfile?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var fileMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val fileName = context.displayName(uri) ?: "Imported profile"
                                importTerminalProfile(fileName, context.readPaletteText(uri)).getOrThrow()
                            }
                        }
                    result.onSuccess { profile ->
                        onSaveProfile(profile.copy(id = UUID.randomUUID().toString()))
                        fileMessage = "Imported ${profile.name}"
                    }.onFailure { error ->
                        fileMessage = "Could not import palette: ${error.message ?: "invalid file"}"
                    }
                }
            }
        }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                scope.launch {
                    val profile = state.selectedProfile
                    val result =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                                    it.write(exportTermuxProfile(profile))
                                } ?: error("Could not open file")
                            }
                        }
                    result.onSuccess {
                        fileMessage = "Exported ${profile.name}"
                    }.onFailure { error ->
                        fileMessage = "Could not export palette: ${error.message ?: "write failed"}"
                    }
                }
            }
        }
    val editing = editingProfile
    BackHandler {
        if (editing != null) editingProfile = null else onBack()
    }
    if (editing != null) {
        TerminalProfileEditor(
            modifier = modifier,
            initial = editing,
            onBack = { editingProfile = null },
            onSave = {
                onSaveProfile(it)
                editingProfile = null
            },
        )
        return
    }
    val profiles = filterTerminalProfiles(state.profiles, searchQuery)

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(UdroidTerminal),
    ) {
        TerminalSettingsHeader(title = "Terminal appearance", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Profiles set the terminal, cursor, ANSI colors, and surrounding controls.",
                    color = UdroidTerminalMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            importLauncher.launch(arrayOf("text/*", "application/xml", "application/octet-stream"))
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Import")
                    }
                    OutlinedButton(
                        onClick = {
                            val name = state.selectedProfile.name.replace(INVALID_FILE_NAME, "-")
                            exportLauncher.launch("$name.properties")
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Export selected")
                    }
                }
                fileMessage?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(message, color = UdroidTerminalMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            item(key = "profile-search") {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search color profiles") },
                    leadingIcon = {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                    },
                    trailingIcon =
                        if (searchQuery.isBlank()) {
                            null
                        } else {
                            {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                                }
                            }
                        },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                )
            }
            if (profiles.isEmpty()) {
                item(key = "no-profile-results") {
                    Text(
                        "No color profiles match “$searchQuery”.",
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = UdroidTerminalMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(profiles, key = TerminalColorProfile::id) { profile ->
                TerminalProfileCard(
                    profile = profile,
                    selected = profile.id == state.selectedProfile.id,
                    builtIn = profile.id.startsWith("builtin:"),
                    onSelect = { onSelectProfile(profile.id) },
                    onEdit = {
                        editingProfile =
                            if (profile.id.startsWith("builtin:")) {
                                profile.copy(
                                    id = UUID.randomUUID().toString(),
                                    name = "${profile.name} custom",
                                )
                            } else {
                                profile
                            }
                    },
                    pendingDelete = pendingDeleteId == profile.id,
                    onDelete = { pendingDeleteId = profile.id },
                    onConfirmDelete = {
                        onDeleteProfile(profile.id)
                        pendingDeleteId = null
                    },
                    onCancelDelete = { pendingDeleteId = null },
                )
            }
            item {
                OutlinedButton(
                    onClick = {
                        editingProfile =
                            state.selectedProfile.copy(
                                id = UUID.randomUUID().toString(),
                                name = "New profile",
                            )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("New profile")
                }
            }
        }
    }
}

@Composable
private fun TerminalSettingsHeader(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(58.dp)
                .background(UdroidTerminalSurface)
                .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
        }
        Text(
            title,
            color = UdroidTerminalText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TerminalProfileCard(
    profile: TerminalColorProfile,
    selected: Boolean,
    builtIn: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    pendingDelete: Boolean,
    onDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
) {
    var menuExpanded by remember(profile.id) { mutableStateOf(false) }
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect),
        color = UdroidTerminalSurface,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (selected) UdroidTerminalGreen else UdroidTerminalLine),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(profile.name, color = UdroidTerminalText, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (builtIn) "Built-in profile" else "Custom profile",
                        color = UdroidTerminalMuted,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (selected) {
                    Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = UdroidTerminalGreen)
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = "Actions for ${profile.name}",
                            tint = UdroidTerminalMuted,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (builtIn) "Customize" else "Edit") },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            },
                        )
                        if (!builtIn) {
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            }
            CompactTerminalProfilePreview(profile)
            if (pendingDelete) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Delete this profile?", modifier = Modifier.weight(1f), color = UdroidTerminalText)
                    TextButton(onClick = onCancelDelete) { Text("Cancel") }
                    TextButton(onClick = onConfirmDelete) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun CompactTerminalProfilePreview(profile: TerminalColorProfile) {
    Surface(
        color = Color(profile.background),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Color(profile.foreground).copy(alpha = 0.22f)),
    ) {
        AnsiPalette(
            colors = profile.ansiColors,
            modifier = Modifier.padding(6.dp),
            swatchHeight = 7.dp,
            spacing = 3.dp,
        )
    }
}

@Composable
private fun TerminalProfilePreview(profile: TerminalColorProfile) {
    Surface(
        color = Color(profile.background),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Color(profile.foreground).copy(alpha = 0.22f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "user@linux:~$ printf 'Hello'",
                color = Color(profile.foreground),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            AnsiPalette(colors = profile.ansiColors)
        }
    }
}

@Composable
private fun AnsiPalette(
    colors: List<Int>,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
    onSelect: ((Int) -> Unit)? = null,
    swatchHeight: Dp = if (onSelect == null) 14.dp else 32.dp,
    spacing: Dp = 5.dp,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing)) {
        repeat(2) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                repeat(8) { column ->
                    val index = row * 8 + column
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(swatchHeight)
                                .background(Color(colors[index]), RoundedCornerShape(5.dp))
                                .then(
                                    if (onSelect == null) Modifier else Modifier.clickable { onSelect(index) },
                                ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selectedIndex == index) {
                            Text("•", color = contrastingColor(colors[index]), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

internal fun filterTerminalProfiles(
    profiles: List<TerminalColorProfile>,
    query: String,
): List<TerminalColorProfile> =
    if (query.isBlank()) profiles else profiles.filter { it.name.contains(query.trim(), ignoreCase = true) }

@Composable
private fun TerminalProfileEditor(
    modifier: Modifier,
    initial: TerminalColorProfile,
    onBack: () -> Unit,
    onSave: (TerminalColorProfile) -> Unit,
) {
    var profile by remember(initial.id) { mutableStateOf(initial) }
    var selectedColor by remember { mutableIntStateOf(BACKGROUND_COLOR) }
    val color = profile.colorAt(selectedColor)
    var hexText by remember(selectedColor, color) { mutableStateOf(formatTerminalColor(color)) }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(UdroidTerminal),
    ) {
        TerminalSettingsHeader(title = "Edit profile", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                OutlinedTextField(
                    value = profile.name,
                    onValueChange = { profile = profile.copy(name = it.take(40)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Profile name") },
                    singleLine = true,
                )
            }
            item { TerminalProfilePreview(profile) }
            item {
                Text("Terminal colors", color = UdroidTerminalText, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProfileColorButton("Background", profile.background, selectedColor == BACKGROUND_COLOR) {
                        selectedColor = BACKGROUND_COLOR
                    }
                    ProfileColorButton("Text", profile.foreground, selectedColor == FOREGROUND_COLOR) {
                        selectedColor = FOREGROUND_COLOR
                    }
                    ProfileColorButton("Cursor", profile.cursor, selectedColor == CURSOR_COLOR) {
                        selectedColor = CURSOR_COLOR
                    }
                }
            }
            item {
                Text("ANSI palette", color = UdroidTerminalText, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                AnsiPalette(
                    colors = profile.ansiColors,
                    selectedIndex = selectedColor.takeIf { it >= 0 },
                    onSelect = { selectedColor = it },
                )
            }
            item {
                HorizontalDivider(color = UdroidTerminalLine)
                Spacer(Modifier.height(12.dp))
                Text(
                    colorName(selectedColor),
                    color = UdroidTerminalText,
                    fontWeight = FontWeight.SemiBold,
                )
                ColorSlider("Red", color.redChannel()) {
                    profile = profile.withColor(selectedColor, color.withChannel(red = it))
                }
                ColorSlider("Green", color.greenChannel()) {
                    profile = profile.withColor(selectedColor, color.withChannel(green = it))
                }
                ColorSlider("Blue", color.blueChannel()) {
                    profile = profile.withColor(selectedColor, color.withChannel(blue = it))
                }
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { value ->
                        hexText = value.take(7)
                        parseTerminalColor(hexText)?.let { profile = profile.withColor(selectedColor, it) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Hex value") },
                    singleLine = true,
                    isError = parseTerminalColor(hexText) == null,
                )
            }
            item {
                Button(
                    onClick = { onSave(profile) },
                    enabled = profile.name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save profile")
                }
            }
        }
    }
}

@Composable
private fun RowScope.ProfileColorButton(
    label: String,
    color: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 8.dp),
        border = BorderStroke(2.dp, if (selected) UdroidTerminalGreen else UdroidTerminalLine),
    ) {
        Box(Modifier.size(14.dp).background(Color(color), RoundedCornerShape(3.dp)))
        Spacer(Modifier.size(6.dp))
        Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ColorSlider(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(0.28f), color = UdroidTerminalMuted)
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            modifier = Modifier.weight(0.72f),
            valueRange = 0f..255f,
        )
        Text(value.toString(), modifier = Modifier.padding(start = 8.dp), color = UdroidTerminalText)
    }
}

private fun TerminalColorProfile.colorAt(index: Int): Int =
    when (index) {
        BACKGROUND_COLOR -> background
        FOREGROUND_COLOR -> foreground
        CURSOR_COLOR -> cursor
        else -> ansiColors[index]
    }

private fun TerminalColorProfile.withColor(
    index: Int,
    color: Int,
): TerminalColorProfile =
    when (index) {
        BACKGROUND_COLOR -> copy(background = color)
        FOREGROUND_COLOR -> copy(foreground = color)
        CURSOR_COLOR -> copy(cursor = color)
        else -> copy(ansiColors = ansiColors.toMutableList().also { it[index] = color })
    }

private fun colorName(index: Int): String =
    when (index) {
        BACKGROUND_COLOR -> "Background"
        FOREGROUND_COLOR -> "Text"
        CURSOR_COLOR -> "Cursor"
        in 0..7 -> "ANSI $index"
        else -> "Bright ANSI $index"
    }

private fun Int.redChannel(): Int = (this shr 16) and 0xFF

private fun Int.greenChannel(): Int = (this shr 8) and 0xFF

private fun Int.blueChannel(): Int = this and 0xFF

private fun Int.withChannel(
    red: Int = redChannel(),
    green: Int = greenChannel(),
    blue: Int = blueChannel(),
): Int = 0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue

private fun contrastingColor(color: Int): Color {
    val luminance = (color.redChannel() * 299 + color.greenChannel() * 587 + color.blueChannel() * 114) / 1000
    return if (luminance >= 128) Color.Black else Color.White
}

private const val BACKGROUND_COLOR = -3
private const val FOREGROUND_COLOR = -2
private const val CURSOR_COLOR = -1
private const val MAX_PALETTE_FILE_CHARS = 1_048_576
private val INVALID_FILE_NAME = Regex("[^A-Za-z0-9._ -]")

private fun Context.displayName(uri: Uri): String? =
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

private fun Context.readPaletteText(uri: Uri): String =
    contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
        buildString {
            val buffer = CharArray(8192)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                append(buffer, 0, count)
                require(length <= MAX_PALETTE_FILE_CHARS) { "Palette file is larger than 1 MiB" }
            }
        }
    } ?: error("Could not open file")
