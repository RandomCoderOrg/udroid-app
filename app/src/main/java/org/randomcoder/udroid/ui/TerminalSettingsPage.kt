package org.randomcoder.udroid.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import org.randomcoder.udroid.runtime.TerminalColorProfile
import org.randomcoder.udroid.runtime.TerminalCursorStyle
import org.randomcoder.udroid.runtime.TerminalFonts
import org.randomcoder.udroid.runtime.TerminalFontProfile
import org.randomcoder.udroid.runtime.TerminalExtraKeys
import org.randomcoder.udroid.runtime.TerminalExtraKeysState
import org.randomcoder.udroid.runtime.TerminalProfileState
import org.randomcoder.udroid.runtime.TerminalTypographyState
import org.randomcoder.udroid.runtime.TerminalTypographyStore
import org.randomcoder.udroid.runtime.TERMINAL_CURSOR_BLINK_RATE_DEFAULT_MS
import org.randomcoder.udroid.runtime.TERMINAL_CURSOR_BLINK_RATE_MAX_MS
import org.randomcoder.udroid.runtime.TERMINAL_CURSOR_BLINK_RATE_MIN_MS
import org.randomcoder.udroid.runtime.TERMINAL_LINE_SPACING_MAX
import org.randomcoder.udroid.runtime.TERMINAL_LINE_SPACING_MIN
import org.randomcoder.udroid.runtime.TERMINAL_TEXT_SIZE_MAX_SP
import org.randomcoder.udroid.runtime.TERMINAL_TEXT_SIZE_MIN_SP
import org.randomcoder.udroid.runtime.exportTermuxProfile
import org.randomcoder.udroid.runtime.formatTerminalColor
import org.randomcoder.udroid.runtime.importTerminalProfile
import org.randomcoder.udroid.runtime.parseTerminalColor
import org.randomcoder.udroid.input.ExtraKeyBinding
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.abs

private enum class TerminalSettingsDestination {
    HOME,
    COLORS,
    TYPOGRAPHY,
    FONTS,
    EXTRA_KEYS,
    KEY_PICKER,
}

private sealed interface TerminalExtraKeyTarget {
    data class Replace(val row: Int, val column: Int) : TerminalExtraKeyTarget

    data class Append(val row: Int) : TerminalExtraKeyTarget

    data object NewRow : TerminalExtraKeyTarget
}

private data class TerminalExtraKeyPosition(val row: Int, val column: Int)

private data class TerminalExtraKeyDrop(val target: TerminalExtraKeyPosition, val after: Boolean)

@Composable
internal fun TerminalSettingsPage(
    modifier: Modifier = Modifier,
    state: TerminalProfileState,
    typographyState: TerminalTypographyState,
    extraKeysState: TerminalExtraKeysState,
    onBack: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onSaveProfile: (TerminalColorProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onTypographyChanged: (TerminalTypographyState) -> Unit,
    onExtraKeysChanged: (TerminalExtraKeysState) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val typographyStore = remember(context) { TerminalTypographyStore(context) }
    var editingProfile by remember { mutableStateOf<TerminalColorProfile?>(null) }
    var page by remember { mutableStateOf(TerminalSettingsDestination.HOME) }
    var extraKeyTarget by remember { mutableStateOf<TerminalExtraKeyTarget?>(null) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var fileMessage by remember { mutableStateOf<String?>(null) }
    var extraKeysMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    val importLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val fileName = context.displayName(uri) ?: "Imported profile"
                                importTerminalProfile(
                                    fileName,
                                    context.readLimitedText(uri, "Palette file"),
                                ).getOrThrow()
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
    val importExtraKeysLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                TerminalExtraKeys.parseTermuxJson(
                                    context.readLimitedText(uri, "Extra-keys file"),
                                )
                            }
                        }
                    result.onSuccess { imported ->
                        onExtraKeysChanged(extraKeysState.copy(rows = imported.rows))
                        extraKeysMessage =
                            if (imported.ignoredPopups == 0) {
                                "Imported ${imported.rows.sumOf { it.size }} keys"
                            } else {
                                "Imported primary keys; ${imported.ignoredPopups} popup actions were not imported"
                            }
                    }.onFailure { error ->
                        extraKeysMessage = "Could not import keys: ${error.message ?: "invalid JSON"}"
                    }
                }
            }
        }
    val exportExtraKeysLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) {
                scope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                                    it.write(TerminalExtraKeys.toTermuxJson(extraKeysState.rows))
                                } ?: error("Could not open file")
                            }
                        }
                    result.onSuccess { extraKeysMessage = "Exported Termux extra keys" }
                        .onFailure { error ->
                            extraKeysMessage = "Could not export keys: ${error.message ?: "write failed"}"
                        }
                }
            }
        }
    val editing = editingProfile
    BackHandler {
        when {
            editing != null -> editingProfile = null
            page == TerminalSettingsDestination.FONTS -> page = TerminalSettingsDestination.TYPOGRAPHY
            page == TerminalSettingsDestination.KEY_PICKER -> {
                extraKeyTarget = null
                page = TerminalSettingsDestination.EXTRA_KEYS
            }
            page != TerminalSettingsDestination.HOME -> page = TerminalSettingsDestination.HOME
            else -> onBack()
        }
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
    if (page == TerminalSettingsDestination.TYPOGRAPHY) {
        TerminalTypographySettingsPage(
            modifier = modifier,
            state = typographyState,
            colorProfile = state.selectedProfile,
            store = typographyStore,
            onBack = { page = TerminalSettingsDestination.HOME },
            onChooseFont = { page = TerminalSettingsDestination.FONTS },
            onChanged = onTypographyChanged,
        )
        return
    }
    if (page == TerminalSettingsDestination.FONTS) {
        TerminalFontPickerPage(
            modifier = modifier,
            state = typographyState,
            store = typographyStore,
            onBack = { page = TerminalSettingsDestination.TYPOGRAPHY },
            onChanged = onTypographyChanged,
        )
        return
    }
    if (page == TerminalSettingsDestination.EXTRA_KEYS) {
        TerminalExtraKeysSettingsPage(
            modifier = modifier,
            state = extraKeysState,
            message = extraKeysMessage,
            onBack = { page = TerminalSettingsDestination.HOME },
            onChanged = onExtraKeysChanged,
            onEdit = { row, column ->
                extraKeyTarget = TerminalExtraKeyTarget.Replace(row, column)
                searchQuery = ""
                page = TerminalSettingsDestination.KEY_PICKER
            },
            onAddKey = { row ->
                extraKeyTarget = TerminalExtraKeyTarget.Append(row)
                searchQuery = ""
                page = TerminalSettingsDestination.KEY_PICKER
            },
            onAddRow = {
                extraKeyTarget = TerminalExtraKeyTarget.NewRow
                searchQuery = ""
                page = TerminalSettingsDestination.KEY_PICKER
            },
            onRemoveKey = { row, column ->
                onExtraKeysChanged(extraKeysState.copy(rows = removeTerminalExtraKey(extraKeysState.rows, row, column)))
            },
            onRemoveRow = { row ->
                onExtraKeysChanged(extraKeysState.copy(rows = removeTerminalExtraKeyRow(extraKeysState.rows, row)))
            },
            onImport = { importExtraKeysLauncher.launch(arrayOf("application/json", "text/*")) },
            onExport = { exportExtraKeysLauncher.launch("udroid-extra-keys.json") },
        )
        return
    }
    if (page == TerminalSettingsDestination.KEY_PICKER) {
        val initialBinding =
            (extraKeyTarget as? TerminalExtraKeyTarget.Replace)?.let { target ->
                extraKeysState.rows.getOrNull(target.row)?.getOrNull(target.column)
            }
        TerminalExtraKeyPickerPage(
            modifier = modifier,
            query = searchQuery,
            initial = initialBinding,
            onQueryChanged = { searchQuery = it },
            onBack = {
                extraKeyTarget = null
                page = TerminalSettingsDestination.EXTRA_KEYS
            },
            onSelected = { binding ->
                val rows = extraKeysState.rows.map { it.toMutableList() }.toMutableList()
                when (val target = extraKeyTarget) {
                    is TerminalExtraKeyTarget.Replace -> rows[target.row][target.column] = binding
                    is TerminalExtraKeyTarget.Append -> rows[target.row].add(binding)
                    TerminalExtraKeyTarget.NewRow -> rows.add(mutableListOf(binding))
                    null -> Unit
                }
                onExtraKeysChanged(extraKeysState.copy(rows = rows))
                extraKeyTarget = null
                page = TerminalSettingsDestination.EXTRA_KEYS
            },
        )
        return
    }
    if (page == TerminalSettingsDestination.HOME) {
        TerminalSettingsHome(
            modifier = modifier,
            colorProfile = state.selectedProfile,
            typography = typographyState,
            onBack = onBack,
            extraKeys = extraKeysState,
            onOpenColors = { page = TerminalSettingsDestination.COLORS },
            onOpenTypography = { page = TerminalSettingsDestination.TYPOGRAPHY },
            onOpenExtraKeys = { page = TerminalSettingsDestination.EXTRA_KEYS },
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
        TerminalSettingsHeader(title = "Colors", onBack = { page = TerminalSettingsDestination.HOME })
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
private fun TerminalSettingsHome(
    modifier: Modifier,
    colorProfile: TerminalColorProfile,
    typography: TerminalTypographyState,
    extraKeys: TerminalExtraKeysState,
    onBack: () -> Unit,
    onOpenColors: () -> Unit,
    onOpenTypography: () -> Unit,
    onOpenExtraKeys: () -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(UdroidTerminal),
    ) {
        TerminalSettingsHeader(title = "Terminal settings", onBack = onBack)
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Choose what you want to customize.",
                color = UdroidTerminalMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            TerminalAppearanceEntry(
                icon = { Icon(Icons.Outlined.Palette, contentDescription = null, tint = UdroidTerminalGreen) },
                title = "Colors",
                detail = colorProfile.name,
                onClick = onOpenColors,
            )
            TerminalAppearanceEntry(
                icon = { Icon(Icons.Outlined.TextFields, contentDescription = null, tint = UdroidTerminalGreen) },
                title = "Font & text",
                detail = "${typography.selectedFont.name} · ${typography.textSizeSp.formatValue()} sp",
                onClick = onOpenTypography,
            )
            TerminalAppearanceEntry(
                icon = { Icon(Icons.Outlined.Keyboard, contentDescription = null, tint = UdroidTerminalGreen) },
                title = "Extra keys",
                detail =
                    if (extraKeys.visible) {
                        "${extraKeys.rows.size} rows · ${extraKeys.rows.sumOf { it.size }} keys"
                    } else {
                        "Hidden"
                    },
                onClick = onOpenExtraKeys,
            )
        }
    }
}

@Composable
internal fun TerminalExtraKeysSettingsPage(
    modifier: Modifier,
    state: TerminalExtraKeysState,
    message: String?,
    title: String = "Extra keys",
    visibilityTitle: String = "Show extra keys",
    visibilityDetail: String = "Keep the key rail below the terminal",
    resetLabel: String = "Reset extra keys",
    bindingLabel: (ExtraKeyBinding) -> String = TerminalExtraKeys::label,
    onBack: () -> Unit,
    onChanged: (TerminalExtraKeysState) -> Unit,
    onEdit: (Int, Int) -> Unit,
    onAddKey: (Int) -> Unit,
    onAddRow: () -> Unit,
    onRemoveKey: (Int, Int) -> Unit,
    onRemoveRow: (Int) -> Unit,
    onReset: () -> Unit = { onChanged(TerminalExtraKeysState(visible = state.visible)) },
    onImport: () -> Unit,
    onExport: () -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val keyBounds = remember { mutableStateMapOf<TerminalExtraKeyPosition, Rect>() }
    val rowBounds = remember { mutableStateMapOf<Int, Rect>() }
    val rowScrollStates = remember { mutableStateMapOf<Int, androidx.compose.foundation.ScrollState>() }
    var pageBounds by remember { mutableStateOf(Rect.Zero) }
    var listBounds by remember { mutableStateOf(Rect.Zero) }
    var dragSource by remember { mutableStateOf<TerminalExtraKeyPosition?>(null) }
    var dragLabel by remember { mutableStateOf("") }
    var dragCenter by remember { mutableStateOf(Offset.Zero) }
    var dragSize by remember { mutableStateOf(IntSize.Zero) }
    var drop by remember { mutableStateOf<TerminalExtraKeyDrop?>(null) }
    val edgeSize = with(density) { 48.dp.toPx() }
    val scrollStep = with(density) { 20.dp.toPx() }

    fun relocate(
        source: TerminalExtraKeyPosition,
        target: TerminalExtraKeyPosition,
        after: Boolean,
    ): Boolean {
        val rows = moveTerminalExtraKey(state.rows, source.row, source.column, target.row, target.column, after)
        if (rows == state.rows) return false
        onChanged(state.copy(rows = rows))
        return true
    }

    fun clearDrag() {
        dragSource = null
        dragLabel = ""
        dragCenter = Offset.Zero
        dragSize = IntSize.Zero
        drop = null
    }

    fun targetAt(point: Offset, source: TerminalExtraKeyPosition): TerminalExtraKeyDrop? {
        val row =
            rowBounds.entries.minByOrNull { (_, bounds) ->
                when {
                    point.y < bounds.top -> bounds.top - point.y
                    point.y > bounds.bottom -> point.y - bounds.bottom
                    else -> 0f
                }
            } ?: return null
        if (point.y < row.value.top - edgeSize || point.y > row.value.bottom + edgeSize) return null
        val target =
            keyBounds.entries
                .filter { it.key.row == row.key && it.key != source }
                .minByOrNull { abs(point.x - it.value.center.x) }
                ?: return null
        return TerminalExtraKeyDrop(target.key, point.x >= target.value.center.x)
    }

    fun autoScroll(point: Offset) {
        rowBounds.entries.firstOrNull { point.y in it.value.top..it.value.bottom }?.let { (row, bounds) ->
            val delta =
                when {
                    point.x < bounds.left + edgeSize -> -scrollStep
                    point.x > bounds.right - edgeSize -> scrollStep
                    else -> 0f
                }
            if (delta != 0f) rowScrollStates[row]?.dispatchRawDelta(delta)
        }
        val delta =
            when {
                point.y < listBounds.top + edgeSize -> -scrollStep
                point.y > listBounds.bottom - edgeSize -> scrollStep
                else -> 0f
            }
        if (delta != 0f) listState.dispatchRawDelta(delta)
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(UdroidTerminal)
                .onGloballyPositioned { pageBounds = it.boundsInRoot() },
    ) {
        Column(Modifier.fillMaxSize()) {
            TerminalSettingsHeader(title = title, onBack = onBack)
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { listBounds = it.boundsInRoot() },
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(visibilityTitle, color = UdroidTerminalText, fontWeight = FontWeight.SemiBold)
                            Text(
                                visibilityDetail,
                                color = UdroidTerminalMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = state.visible,
                            onCheckedChange = { onChanged(state.copy(visible = it)) },
                        )
                    }
                }
                item {
                    Text(
                        "Tap a slot to change it, or hold its handle and drag it to move it. Tap a modifier once for the next key, hold it for a chord, or double-tap to lock it.",
                        color = UdroidTerminalMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                itemsIndexed(state.rows) { rowIndex, row ->
                    val rowScrollState = rememberScrollState()
                    rowScrollStates[rowIndex] = rowScrollState
                    Surface(
                        modifier = Modifier.onGloballyPositioned { rowBounds[rowIndex] = it.boundsInRoot() },
                        color = UdroidTerminalSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, UdroidTerminalLine),
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Row ${rowIndex + 1}",
                                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                                    color = UdroidTerminalText,
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                TextButton(onClick = { onAddKey(rowIndex) }) {
                                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Add key")
                                }
                                IconButton(
                                    onClick = { onRemoveRow(rowIndex) },
                                    enabled = state.rows.size > 1,
                                ) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = "Remove row ${rowIndex + 1}",
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rowScrollState),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                row.forEachIndexed { columnIndex, binding ->
                                    val position = TerminalExtraKeyPosition(rowIndex, columnIndex)
                                    val targeted = drop?.target == position
                                    Surface(
                                        modifier =
                                            Modifier
                                                .onGloballyPositioned { keyBounds[position] = it.boundsInRoot() }
                                                .graphicsLayer { alpha = if (dragSource == position) 0.3f else 1f }
                                                .semantics {
                                                    customActions =
                                                        buildList {
                                                            if (columnIndex > 0) {
                                                                add(
                                                                    CustomAccessibilityAction("Move left") {
                                                                        relocate(position, position.copy(column = columnIndex - 1), false)
                                                                    },
                                                                )
                                                            }
                                                            if (columnIndex < row.lastIndex) {
                                                                add(
                                                                    CustomAccessibilityAction("Move right") {
                                                                        relocate(position, position.copy(column = columnIndex + 1), true)
                                                                    },
                                                                )
                                                            }
                                                            if (rowIndex > 0) {
                                                                add(
                                                                    CustomAccessibilityAction("Move to previous row") {
                                                                        relocate(
                                                                            position,
                                                                            TerminalExtraKeyPosition(
                                                                                rowIndex - 1,
                                                                                state.rows[rowIndex - 1].lastIndex,
                                                                            ),
                                                                            true,
                                                                        )
                                                                    },
                                                                )
                                                            }
                                                            if (rowIndex < state.rows.lastIndex) {
                                                                add(
                                                                    CustomAccessibilityAction("Move to next row") {
                                                                        relocate(position, TerminalExtraKeyPosition(rowIndex + 1, 0), false)
                                                                    },
                                                                )
                                                            }
                                                        }
                                                },
                                        color = UdroidTerminalRaised,
                                        shape = RoundedCornerShape(8.dp),
                                        border =
                                            BorderStroke(
                                                if (targeted) 2.dp else 1.dp,
                                                if (targeted) MaterialTheme.colorScheme.primary else UdroidTerminalLine,
                                            ),
                                    ) {
                                        Box(Modifier.height(38.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxSize(),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Icon(
                                                    Icons.Rounded.DragHandle,
                                                    contentDescription = "Drag ${bindingLabel(binding)} to move",
                                                    modifier =
                                                        Modifier
                                                            .size(32.dp)
                                                            .pointerInput(position, binding) {
                                                                detectDragGesturesAfterLongPress(
                                                                    onDragStart = {
                                                                        keyBounds[position]?.let { bounds ->
                                                                            dragSource = position
                                                                            dragLabel = bindingLabel(binding)
                                                                            dragCenter = bounds.center
                                                                            dragSize =
                                                                                IntSize(
                                                                                    bounds.width.roundToInt(),
                                                                                    bounds.height.roundToInt(),
                                                                                )
                                                                            drop = null
                                                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                        }
                                                                    },
                                                                    onDrag = { change, amount ->
                                                                        if (dragSource == position) {
                                                                            change.consume()
                                                                            dragCenter += amount
                                                                            autoScroll(dragCenter)
                                                                            drop = targetAt(dragCenter, position)
                                                                        }
                                                                    },
                                                                    onDragEnd = {
                                                                        val source = dragSource
                                                                        val destination = drop
                                                                        clearDrag()
                                                                        if (source != null && destination != null) {
                                                                            if (relocate(source, destination.target, destination.after)) {
                                                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                            }
                                                                        }
                                                                    },
                                                                    onDragCancel = ::clearDrag,
                                                                )
                                                            }.padding(6.dp),
                                                    tint = UdroidTerminalMuted,
                                                )
                                                Text(
                                                    bindingLabel(binding),
                                                    modifier =
                                                        Modifier
                                                            .clickable { onEdit(rowIndex, columnIndex) }
                                                            .widthIn(max = 180.dp)
                                                            .padding(start = 10.dp, end = 6.dp),
                                                    color = UdroidTerminalText,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    style = MaterialTheme.typography.labelMedium,
                                                )
                                                IconButton(
                                                    onClick = { onRemoveKey(rowIndex, columnIndex) },
                                                    modifier = Modifier.size(32.dp),
                                                    enabled = state.rows.size > 1 || row.size > 1,
                                                ) {
                                                    Icon(
                                                        Icons.Rounded.Close,
                                                        contentDescription =
                                                            "Remove ${bindingLabel(binding)} from row ${rowIndex + 1}",
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                            }
                                            if (targeted) {
                                                Box(
                                                    Modifier
                                                        .align(
                                                            if (drop?.after == true) {
                                                                Alignment.CenterEnd
                                                            } else {
                                                                Alignment.CenterStart
                                                            },
                                                        ).width(3.dp)
                                                        .fillMaxHeight()
                                                        .background(MaterialTheme.colorScheme.primary),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(
                        onClick = onAddRow,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(9.dp),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add row")
                    }
                }
                item {
                    TextButton(onClick = onReset) {
                        Text(resetLabel)
                    }
                }
                item {
                    HorizontalDivider(color = UdroidTerminalLine)
                    Spacer(Modifier.height(14.dp))
                    Text("Termux layout", color = UdroidTerminalText, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Import or export the Termux extra-keys JSON matrix. Keys, macros, and custom display labels are supported; popup actions are not.",
                        color = UdroidTerminalMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                            Text("Import JSON")
                        }
                        OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                            Text("Export JSON")
                        }
                    }
                    message?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = UdroidTerminalMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (dragSource != null && dragSize != IntSize.Zero) {
            Surface(
                modifier =
                    Modifier
                        .offset {
                            IntOffset(
                                (dragCenter.x - pageBounds.left - dragSize.width / 2f).roundToInt(),
                                (dragCenter.y - pageBounds.top - dragSize.height / 2f).roundToInt(),
                            )
                        }.width(with(density) { dragSize.width.toDp() })
                        .height(with(density) { dragSize.height.toDp() })
                        .graphicsLayer {
                            scaleX = 1.05f
                            scaleY = 1.05f
                            alpha = 0.95f
                        },
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                shadowElevation = 8.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.DragHandle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        dragLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
internal fun TerminalExtraKeyPickerPage(
    modifier: Modifier,
    query: String,
    initial: ExtraKeyBinding?,
    title: String = if (initial == null) "Add key" else "Edit key",
    keys: List<Int> = TerminalExtraKeys.keys,
    keyLabel: (Int) -> String = TerminalExtraKeys::label,
    keyBinding: (Int) -> ExtraKeyBinding = TerminalExtraKeys::binding,
    onQueryChanged: (String) -> Unit,
    onBack: () -> Unit,
    onSelected: (ExtraKeyBinding) -> Unit,
) {
    var display by remember(initial) { mutableStateOf(initial?.display.orEmpty()) }
    var macro by remember(initial) { mutableStateOf(initial?.takeIf(ExtraKeyBinding::macro)?.action.orEmpty()) }
    val matches =
        remember(query) {
            keys.filter {
                keyLabel(it).contains(query.trim(), ignoreCase = true)
            }
        }
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(UdroidTerminal),
    ) {
        TerminalSettingsHeader(title = title, onBack = onBack)
        OutlinedTextField(
            value = display,
            onValueChange = { display = it.replace("\n", "") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            label = { Text("Display label (optional)") },
            placeholder = { Text("Text, symbol, or emoji") },
            singleLine = true,
        )
        OutlinedTextField(
            value = macro,
            onValueChange = { macro = it.replace("\n", " ") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            label = { Text("Macro (optional)") },
            supportingText = { Text("Termux syntax, for example: CTRL f d") },
            singleLine = true,
        )
        if (macro.isNotBlank()) {
            Button(
                onClick = { onSelected(ExtraKeyBinding(macro, macro = true, display = display.ifBlank { null })) },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text("Use macro")
            }
        } else if (initial != null) {
            Button(
                onClick = { onSelected(initial.copy(display = display.ifBlank { null })) },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text("Save label")
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            placeholder = { Text("Search keys") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon =
                if (query.isBlank()) {
                    null
                } else {
                    {
                        IconButton(onClick = { onQueryChanged("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                    }
                },
            singleLine = true,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        ) {
            items(matches, key = { it }) { keyCode ->
                Text(
                    text = keyLabel(keyCode),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelected(
                                    keyBinding(keyCode).copy(display = display.ifBlank { null }),
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                    color = UdroidTerminalText,
                    style = MaterialTheme.typography.bodyLarge,
                )
                HorizontalDivider(color = UdroidTerminalLine)
            }
        }
    }
}

@Composable
private fun TerminalAppearanceEntry(
    icon: @Composable () -> Unit,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        color = UdroidTerminalSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, UdroidTerminalLine),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(42.dp)
                        .background(UdroidTerminalRaised, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                icon()
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = UdroidTerminalText, fontWeight = FontWeight.SemiBold)
                Text(detail, color = UdroidTerminalMuted, style = MaterialTheme.typography.bodySmall)
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = "Open $title settings",
                tint = UdroidTerminalMuted,
            )
        }
    }
}

@Composable
internal fun TerminalSettingsHeader(
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
private fun TerminalTypographySettingsPage(
    modifier: Modifier,
    state: TerminalTypographyState,
    colorProfile: TerminalColorProfile,
    store: TerminalTypographyStore,
    onBack: () -> Unit,
    onChooseFont: () -> Unit,
    onChanged: (TerminalTypographyState) -> Unit,
) {
    val typeface = remember(state.selectedFont) { store.loadTypeface(state.selectedFont) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(UdroidTerminal),
    ) {
        TerminalSettingsHeader(title = "Font & text", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                TerminalTypographyPreview(
                    state = state,
                    profile = colorProfile,
                    typeface = typeface,
                )
            }
            item {
                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onChooseFont),
                    color = UdroidTerminalSurface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, UdroidTerminalLine),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Font", color = UdroidTerminalText, fontWeight = FontWeight.SemiBold)
                            Text(
                                state.selectedFont.name,
                                color = UdroidTerminalMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = "Choose terminal font",
                            tint = UdroidTerminalMuted,
                        )
                    }
                }
            }
            item {
                Surface(
                    color = UdroidTerminalSurface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, UdroidTerminalLine),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        TerminalSettingSlider(
                            label = "Text size",
                            valueLabel = "${state.textSizeSp.formatValue()} sp",
                            value = state.textSizeSp,
                            range = TERMINAL_TEXT_SIZE_MIN_SP..TERMINAL_TEXT_SIZE_MAX_SP,
                            steps = (TERMINAL_TEXT_SIZE_MAX_SP - TERMINAL_TEXT_SIZE_MIN_SP).roundToInt() - 1,
                            onValueChange = { onChanged(state.copy(textSizeSp = it)) },
                        )
                        HorizontalDivider(color = UdroidTerminalLine)
                        TerminalSettingSlider(
                            label = "Line height",
                            valueLabel = "${(state.lineSpacing * 100).roundToInt()}%",
                            value = state.lineSpacing,
                            range = TERMINAL_LINE_SPACING_MIN..TERMINAL_LINE_SPACING_MAX,
                            steps = 15,
                            onValueChange = { onChanged(state.copy(lineSpacing = it)) },
                        )
                    }
                }
            }
            item {
                Surface(
                    color = UdroidTerminalSurface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, UdroidTerminalLine),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Cursor", color = UdroidTerminalText, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TerminalCursorStyle.entries.forEach { style ->
                                FilterChip(
                                    selected = state.cursorStyle == style,
                                    onClick = { onChanged(state.copy(cursorStyle = style)) },
                                    label = { Text(style.displayName()) },
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Blink cursor", color = UdroidTerminalText)
                                Text(
                                    if (state.cursorBlinkRateMs == 0) "Off" else "Lower is faster",
                                    color = UdroidTerminalMuted,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Switch(
                                checked = state.cursorBlinkRateMs > 0,
                                onCheckedChange = { enabled ->
                                    onChanged(
                                        state.copy(
                                            cursorBlinkRateMs =
                                                if (enabled) TERMINAL_CURSOR_BLINK_RATE_DEFAULT_MS else 0,
                                        ),
                                    )
                                },
                            )
                        }
                        if (state.cursorBlinkRateMs > 0) {
                            TerminalSettingSlider(
                                label = "Blink interval",
                                valueLabel = "${state.cursorBlinkRateMs} ms",
                                value = state.cursorBlinkRateMs.toFloat(),
                                range =
                                    TERMINAL_CURSOR_BLINK_RATE_MIN_MS.toFloat()..
                                        TERMINAL_CURSOR_BLINK_RATE_MAX_MS.toFloat(),
                                steps = 18,
                                onValueChange = {
                                    onChanged(state.copy(cursorBlinkRateMs = (it / 100).roundToInt() * 100))
                                },
                            )
                        }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        onChanged(
                            state.copy(
                                selectedFontId = TerminalFonts.system.id,
                                textSizeSp = 15f,
                                lineSpacing = 1f,
                                cursorStyle = TerminalCursorStyle.BLOCK,
                                cursorBlinkRateMs = 0,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reset font & text")
                }
            }
        }
    }
}

@Composable
private fun TerminalFontPickerPage(
    modifier: Modifier,
    state: TerminalTypographyState,
    store: TerminalTypographyStore,
    onBack: () -> Unit,
    onChanged: (TerminalTypographyState) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val fontLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                scope.launch {
                    val results =
                        withContext(Dispatchers.IO) {
                            uris.map { uri ->
                                val name = context.displayName(uri) ?: "Imported font.ttf"
                                store.importFont(uri, name)
                            }
                        }
                    val imported = results.mapNotNull(Result<TerminalFontProfile>::getOrNull)
                    if (imported.isNotEmpty()) {
                        onChanged(
                            state.copy(
                                selectedFontId = imported.last().id,
                                importedFonts = state.importedFonts + imported,
                            ),
                        )
                    }
                    val failed = results.size - imported.size
                    message =
                        when {
                            imported.size == 1 && failed == 0 -> "Imported ${imported.single().name}"
                            imported.isNotEmpty() && failed == 0 -> "Imported ${imported.size} fonts"
                            imported.isNotEmpty() -> "Imported ${imported.size} fonts; $failed failed"
                            else -> {
                                val reason =
                                    results.firstNotNullOfOrNull { it.exceptionOrNull()?.message }
                                        ?: "invalid files"
                                "Could not import fonts: $reason"
                            }
                        }
                }
            }
        }
    val fonts = state.fonts.filter { it.name.contains(searchQuery, ignoreCase = true) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(UdroidTerminal),
    ) {
        TerminalSettingsHeader(title = "Fonts", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Use a monospaced font to keep terminal columns aligned.",
                        modifier = Modifier.weight(1f),
                        color = UdroidTerminalMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.size(12.dp))
                    OutlinedButton(
                        onClick = {
                            fontLauncher.launch(
                                arrayOf(
                                    "font/ttf",
                                    "font/otf",
                                    "application/x-font-ttf",
                                    "application/x-font-opentype",
                                    "application/octet-stream",
                                ),
                            )
                        },
                    ) {
                        Text("Import")
                    }
                }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = UdroidTerminalMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search fonts") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
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
                )
            }
            if (fonts.isEmpty()) {
                item {
                    Text(
                        "No fonts match “$searchQuery”.",
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = UdroidTerminalMuted,
                    )
                }
            }
            items(fonts, key = TerminalFontProfile::id) { font ->
                val typeface = remember(font) { store.loadTypeface(font) }
                TerminalFontRow(
                    font = font,
                    typeface = typeface,
                    selected = font.id == state.selectedFontId,
                    onSelect = { onChanged(state.copy(selectedFontId = font.id)) },
                    onDelete =
                        if (font.fileName == null) {
                            null
                        } else {
                            {
                                store.delete(font)
                                onChanged(
                                    state.copy(
                                        selectedFontId =
                                            if (state.selectedFontId == font.id) {
                                                TerminalFonts.system.id
                                            } else {
                                                state.selectedFontId
                                            },
                                        importedFonts = state.importedFonts.filterNot { it.id == font.id },
                                    ),
                                )
                            }
                        },
                )
            }
        }
    }
}

@Composable
@SuppressLint("SetTextI18n")
private fun TerminalTypographyPreview(
    state: TerminalTypographyState,
    profile: TerminalColorProfile,
    typeface: Typeface,
) {
    Surface(
        color = Color(profile.background),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Color(profile.foreground).copy(alpha = 0.22f)),
    ) {
        val cursor =
            when (state.cursorStyle) {
                TerminalCursorStyle.BLOCK -> "█"
                TerminalCursorStyle.BAR -> "│"
                TerminalCursorStyle.UNDERLINE -> "_"
            }
        AndroidView(
            factory = { context ->
                TextView(context).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                    val padding = (16 * resources.displayMetrics.density).roundToInt()
                    setPadding(padding, padding, padding, padding)
                }
            },
            update = { view ->
                view.text = "root@linux:~$ printf 'Hello'\nHello\n$cursor"
                view.setTextColor(profile.foreground)
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, state.textSizeSp)
                view.typeface = typeface
                view.setLineSpacing(0f, state.lineSpacing)
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 116.dp),
        )
    }
}

@Composable
@SuppressLint("SetTextI18n")
private fun TerminalFontRow(
    font: TerminalFontProfile,
    typeface: Typeface,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val sampleTextColor = UdroidTerminalText.toArgb()
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect),
        color = UdroidTerminalSurface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (selected) UdroidTerminalGreen else UdroidTerminalLine),
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onSelect)
            Column(modifier = Modifier.weight(1f)) {
                Text(font.name, color = UdroidTerminalText, fontWeight = FontWeight.Medium)
                Text(
                    if (font.fileName == null) "Included" else "Imported",
                    color = UdroidTerminalMuted,
                    style = MaterialTheme.typography.labelSmall,
                )
                AndroidView(
                    factory = { context ->
                        TextView(context).apply {
                            includeFontPadding = false
                            text = "Aa Bb Cc 0123  ~/  {}[]"
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                            setTextColor(sampleTextColor)
                        }
                    },
                    update = { it.typeface = typeface },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = "Delete ${font.name}",
                        tint = UdroidTerminalMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalSettingSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, modifier = Modifier.weight(1f), color = UdroidTerminalText)
            Text(valueLabel, color = UdroidTerminalMuted, style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
        )
    }
}

private fun TerminalCursorStyle.displayName(): String =
    when (this) {
        TerminalCursorStyle.BLOCK -> "Block"
        TerminalCursorStyle.BAR -> "Bar"
        TerminalCursorStyle.UNDERLINE -> "Underline"
    }

private fun Float.formatValue(): String =
    if (this == roundToInt().toFloat()) roundToInt().toString() else String.format(Locale.US, "%.1f", this)

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

internal fun <T> removeTerminalExtraKey(
    rows: List<List<T>>,
    rowIndex: Int,
    columnIndex: Int,
): List<List<T>> {
    if (rows.sumOf { it.size } <= 1) return rows
    val updated = rows.map { it.toMutableList() }.toMutableList()
    val row = updated.getOrNull(rowIndex) ?: return rows
    if (columnIndex !in row.indices) return rows
    row.removeAt(columnIndex)
    if (row.isEmpty()) updated.removeAt(rowIndex)
    return updated
}

internal fun <T> removeTerminalExtraKeyRow(
    rows: List<List<T>>,
    rowIndex: Int,
): List<List<T>> =
    if (rows.size <= 1 || rowIndex !in rows.indices) rows else rows.filterIndexed { index, _ -> index != rowIndex }

internal fun <T> moveTerminalExtraKey(
    rows: List<List<T>>,
    sourceRow: Int,
    sourceColumn: Int,
    targetRow: Int,
    targetColumn: Int,
    after: Boolean,
): List<List<T>> {
    val source = rows.getOrNull(sourceRow) ?: return rows
    val target = rows.getOrNull(targetRow) ?: return rows
    if (sourceColumn !in source.indices || targetColumn !in target.indices) return rows
    if (sourceRow == targetRow && sourceColumn == targetColumn) return rows

    val updated = rows.map { it.toMutableList() }.toMutableList()
    val key = updated[sourceRow].removeAt(sourceColumn)
    var destination = targetColumn + if (after) 1 else 0
    if (sourceRow == targetRow && sourceColumn < destination) destination -= 1
    updated[targetRow].add(destination.coerceIn(0, updated[targetRow].size), key)
    if (updated[sourceRow].isEmpty()) updated.removeAt(sourceRow)
    return updated
}

@Composable
private fun TerminalProfileEditor(
    modifier: Modifier,
    initial: TerminalColorProfile,
    onBack: () -> Unit,
    onSave: (TerminalColorProfile) -> Unit,
) {
    var profile by remember(initial.id) { mutableStateOf(initial) }
    var profileName by remember(initial.id) { mutableStateOf(initial.name) }
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
                    value = profileName,
                    onValueChange = { profileName = it.take(40) },
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
                    onClick = { onSave(profile.copy(name = profileName.trim())) },
                    enabled = profileName.isNotBlank(),
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
private const val MAX_TERMINAL_SETTINGS_FILE_CHARS = 1_048_576
private val INVALID_FILE_NAME = Regex("[^A-Za-z0-9._ -]")

private fun Context.displayName(uri: Uri): String? =
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }

internal fun Context.readLimitedText(
    uri: Uri,
    label: String,
): String =
    contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
        buildString {
            val buffer = CharArray(8192)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                append(buffer, 0, count)
                require(length <= MAX_TERMINAL_SETTINGS_FILE_CHARS) { "$label is larger than 1 MiB" }
            }
        }
    } ?: error("Could not open file")
