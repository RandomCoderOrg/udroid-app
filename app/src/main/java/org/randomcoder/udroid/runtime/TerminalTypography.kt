package org.randomcoder.udroid.runtime

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.Base64
import java.util.UUID

const val TERMINAL_TEXT_SIZE_MIN_SP = 10f
const val TERMINAL_TEXT_SIZE_MAX_SP = 30f
const val TERMINAL_LINE_SPACING_MIN = 0.8f
const val TERMINAL_LINE_SPACING_MAX = 1.6f
const val TERMINAL_CURSOR_BLINK_RATE_DEFAULT_MS = 600
const val TERMINAL_CURSOR_BLINK_RATE_MIN_MS = 100
const val TERMINAL_CURSOR_BLINK_RATE_MAX_MS = 2000
private val SAFE_FONT_FILE = Regex("[0-9a-f-]+\\.(ttf|otf)")

enum class TerminalCursorStyle {
    BLOCK,
    BAR,
    UNDERLINE,
}

data class TerminalFontProfile(
    val id: String,
    val name: String,
    val fileName: String?,
    val assetPath: String? = null,
)

data class TerminalTypographyState(
    val selectedFontId: String = TerminalFonts.system.id,
    val importedFonts: List<TerminalFontProfile> = emptyList(),
    val textSizeSp: Float = 15f,
    val lineSpacing: Float = 1f,
    val cursorStyle: TerminalCursorStyle = TerminalCursorStyle.BLOCK,
    val cursorBlinkRateMs: Int = 0,
) {
    val fonts: List<TerminalFontProfile>
        get() = TerminalFonts.builtIns + importedFonts

    val selectedFont: TerminalFontProfile
        get() = fonts.firstOrNull { it.id == selectedFontId } ?: TerminalFonts.system

    fun normalized(): TerminalTypographyState =
        copy(
            selectedFontId = selectedFont.id,
            importedFonts = importedFonts.distinctBy(TerminalFontProfile::id).sortedBy(TerminalFontProfile::name),
            textSizeSp = textSizeSp.coerceIn(TERMINAL_TEXT_SIZE_MIN_SP, TERMINAL_TEXT_SIZE_MAX_SP),
            lineSpacing = lineSpacing.coerceIn(TERMINAL_LINE_SPACING_MIN, TERMINAL_LINE_SPACING_MAX),
            cursorBlinkRateMs =
                if (cursorBlinkRateMs == 0) {
                    0
                } else {
                    cursorBlinkRateMs.coerceIn(
                        TERMINAL_CURSOR_BLINK_RATE_MIN_MS,
                        TERMINAL_CURSOR_BLINK_RATE_MAX_MS,
                    )
                },
        )
}

object TerminalFonts {
    val system =
        TerminalFontProfile(
            id = "builtin:monospace",
            name = "System monospace",
            fileName = null,
        )
    val jetBrainsMono =
        TerminalFontProfile(
            id = "builtin:jetbrains-mono",
            name = "JetBrains Mono",
            fileName = null,
            assetPath = "terminal-fonts/JetBrainsMono-Regular.ttf",
        )
    val firaCode =
        TerminalFontProfile(
            id = "builtin:fira-code",
            name = "Fira Code",
            fileName = null,
            assetPath = "terminal-fonts/FiraCode-Regular.ttf",
        )
    val sourceCodePro =
        TerminalFontProfile(
            id = "builtin:source-code-pro",
            name = "Source Code Pro",
            fileName = null,
            assetPath = "terminal-fonts/SourceCodePro-Regular.otf",
        )
    val builtIns = listOf(system, jetBrainsMono, firaCode, sourceCodePro)
}

class TerminalTypographyStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val fontsDirectory = File(appContext.filesDir, "terminal-fonts")

    fun load(): TerminalTypographyState {
        var importedFonts =
            preferences
                .getStringSet(KEY_IMPORTED_FONTS, emptySet())
                .orEmpty()
                .mapNotNull(::decodeTerminalFont)
                .filter { font -> font.fileName?.let(::fontFile)?.isFile == true }
        if (!preferences.getBoolean(KEY_EMBEDDED_NAMES_MIGRATED, false)) {
            importedFonts =
                importedFonts.map { font ->
                    val embeddedName = font.fileName?.let(::fontFile)?.let(::readOpenTypeFontName)
                    if (embeddedName == null) font else font.copy(name = embeddedName)
                }
            preferences
                .edit()
                .putStringSet(KEY_IMPORTED_FONTS, importedFonts.map(::encodeTerminalFont).toSet())
                .putBoolean(KEY_EMBEDDED_NAMES_MIGRATED, true)
                .apply()
        }
        val cursorStyle =
            runCatching {
                TerminalCursorStyle.valueOf(
                    preferences.getString(KEY_CURSOR_STYLE, null) ?: TerminalCursorStyle.BLOCK.name,
                )
            }.getOrDefault(TerminalCursorStyle.BLOCK)
        return TerminalTypographyState(
            selectedFontId = preferences.getString(KEY_SELECTED_FONT, null) ?: TerminalFonts.system.id,
            importedFonts = importedFonts,
            textSizeSp =
                if (preferences.contains(KEY_TEXT_SIZE_SP)) {
                    preferences.getFloat(KEY_TEXT_SIZE_SP, DEFAULT_TEXT_SIZE_SP)
                } else {
                    val legacyTextSizePx =
                        appContext
                            .getSharedPreferences(LEGACY_PREFERENCES_NAME, Context.MODE_PRIVATE)
                            .getInt(LEGACY_TEXT_SIZE_PX, -1)
                    if (legacyTextSizePx > 0) {
                        legacyTextSizePx.toSp()
                    } else {
                        DEFAULT_TEXT_SIZE_SP
                    }
                },
            lineSpacing = preferences.getFloat(KEY_LINE_SPACING, 1f),
            cursorStyle = cursorStyle,
            cursorBlinkRateMs = preferences.getInt(KEY_CURSOR_BLINK_RATE, 0),
        ).normalized()
    }

    fun save(state: TerminalTypographyState) {
        val normalized = state.normalized()
        preferences
            .edit()
            .putString(KEY_SELECTED_FONT, normalized.selectedFontId)
            .putStringSet(KEY_IMPORTED_FONTS, normalized.importedFonts.map(::encodeTerminalFont).toSet())
            .putFloat(KEY_TEXT_SIZE_SP, normalized.textSizeSp)
            .putFloat(KEY_LINE_SPACING, normalized.lineSpacing)
            .putString(KEY_CURSOR_STYLE, normalized.cursorStyle.name)
            .putInt(KEY_CURSOR_BLINK_RATE, normalized.cursorBlinkRateMs)
            .apply()
    }

    fun importFont(
        uri: Uri,
        sourceName: String,
    ): Result<TerminalFontProfile> =
        runCatching {
            val extension = sourceName.substringAfterLast('.', "").lowercase()
            require(extension == "ttf" || extension == "otf") { "Choose a .ttf or .otf font" }
            fontsDirectory.mkdirs()
            val id = UUID.randomUUID().toString()
            val destination = File(fontsDirectory, "$id.$extension")
            val temporary = File(fontsDirectory, ".$id.tmp")
            try {
                val input = appContext.contentResolver.openInputStream(uri) ?: error("Could not open font")
                input.buffered().use { source ->
                    temporary.outputStream().buffered().use { target ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_FONT_FILE_BYTES) { "Font is larger than 16 MiB" }
                            target.write(buffer, 0, count)
                        }
                    }
                }
                requireNotNull(Typeface.Builder(temporary).build()) { "Android could not read this font" }
                val embeddedName = readOpenTypeFontName(temporary)
                check(temporary.renameTo(destination)) { "Could not store font" }
                TerminalFontProfile(
                    id = id,
                    name =
                        embeddedName
                            ?: sourceName.substringBeforeLast('.').ifBlank { "Imported font" }.take(80),
                    fileName = destination.name,
                )
            } finally {
                temporary.delete()
            }
        }

    fun loadTypeface(font: TerminalFontProfile): Typeface =
        font.assetPath
            ?.let { runCatching { Typeface.Builder(appContext.assets, it).build() }.getOrNull() }
            ?: font.fileName
            ?.let(::fontFile)
            ?.takeIf(File::isFile)
            ?.let { runCatching { Typeface.Builder(it).build() }.getOrNull() }
            ?: Typeface.MONOSPACE

    fun delete(font: TerminalFontProfile) {
        font.fileName?.let(::fontFile)?.delete()
    }

    private fun fontFile(fileName: String): File {
        require(SAFE_FONT_FILE.matches(fileName)) { "Invalid font file" }
        return File(fontsDirectory, fileName)
    }

    private fun Int.toSp(): Float {
        val metrics = appContext.resources.displayMetrics
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, toFloat(), metrics)
        } else {
            @Suppress("DEPRECATION")
            val scaledDensity = metrics.scaledDensity
            this / scaledDensity
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "terminal-typography"
        const val KEY_SELECTED_FONT = "selected-font"
        const val KEY_IMPORTED_FONTS = "imported-fonts"
        const val KEY_TEXT_SIZE_SP = "text-size-sp"
        const val KEY_LINE_SPACING = "line-spacing"
        const val KEY_CURSOR_STYLE = "cursor-style"
        const val KEY_CURSOR_BLINK_RATE = "cursor-blink-rate"
        const val KEY_EMBEDDED_NAMES_MIGRATED = "embedded-names-migrated-v1"
        const val LEGACY_PREFERENCES_NAME = "terminal-view"
        const val LEGACY_TEXT_SIZE_PX = "text-size-px"
        const val DEFAULT_TEXT_SIZE_SP = 15f
        const val MAX_FONT_FILE_BYTES = 16L * 1024 * 1024
    }
}

internal fun encodeTerminalFont(font: TerminalFontProfile): String =
    listOf(
        font.id,
        Base64.getUrlEncoder().withoutPadding().encodeToString(font.name.toByteArray()),
        font.fileName.orEmpty(),
    ).joinToString("|")

internal fun decodeTerminalFont(value: String): TerminalFontProfile? {
    val fields = value.split('|')
    if (fields.size != 3 || fields[0].isBlank() || fields[2].isBlank()) return null
    return runCatching {
        TerminalFontProfile(
            id = fields[0],
            name = String(Base64.getUrlDecoder().decode(fields[1])).takeIf(String::isNotBlank) ?: error("Missing name"),
            fileName = fields[2].takeIf(SAFE_FONT_FILE::matches)
                ?: error("Invalid file"),
        )
    }.getOrNull()
}

internal fun readOpenTypeFontName(file: File): String? =
    runCatching {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (bytes.size < 12) return null
        val tableCount = buffer.unsignedShort(4)
        if (12L + tableCount * 16L > bytes.size) return null

        var nameOffset = -1
        var nameLength = 0
        repeat(tableCount) { index ->
            val record = 12 + index * 16
            if (
                buffer.get(record) == 'n'.code.toByte() &&
                buffer.get(record + 1) == 'a'.code.toByte() &&
                buffer.get(record + 2) == 'm'.code.toByte() &&
                buffer.get(record + 3) == 'e'.code.toByte()
            ) {
                val offset = buffer.unsignedInt(record + 8)
                val length = buffer.unsignedInt(record + 12)
                if (offset + length <= bytes.size && offset <= Int.MAX_VALUE && length <= Int.MAX_VALUE) {
                    nameOffset = offset.toInt()
                    nameLength = length.toInt()
                }
            }
        }
        if (nameOffset < 0 || nameLength < 6) return null

        val tableEnd = nameOffset.toLong() + nameLength
        val recordCount = buffer.unsignedShort(nameOffset + 2)
        val stringStorage = nameOffset + buffer.unsignedShort(nameOffset + 4)
        if (nameOffset + 6L + recordCount * 12L > tableEnd || stringStorage !in nameOffset..tableEnd.toInt()) {
            return null
        }

        var bestName: String? = null
        var bestRank = Int.MAX_VALUE
        repeat(recordCount) { index ->
            val record = nameOffset + 6 + index * 12
            val platformId = buffer.unsignedShort(record)
            val languageId = buffer.unsignedShort(record + 4)
            val nameId = buffer.unsignedShort(record + 6)
            val nameRank =
                when (nameId) {
                    4 -> 0
                    16 -> 10
                    1 -> 20
                    else -> return@repeat
                }
            val platformRank =
                when {
                    platformId == 3 && languageId == 0x0409 -> 0
                    platformId == 0 -> 1
                    platformId == 3 -> 2
                    platformId == 1 && languageId == 0 -> 3
                    else -> return@repeat
                }
            val length = buffer.unsignedShort(record + 8)
            val offset = stringStorage.toLong() + buffer.unsignedShort(record + 10)
            if (offset + length > tableEnd || offset > Int.MAX_VALUE) return@repeat
            val encoded = bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
            val decoded =
                when (platformId) {
                    0, 3 -> String(encoded, Charsets.UTF_16BE)
                    1 -> String(encoded, MAC_ROMAN)
                    else -> return@repeat
                }.replace(Regex("\\s+"), " ").trim().take(80)
            val rank = nameRank + platformRank
            if (decoded.isNotEmpty() && rank < bestRank) {
                bestName = decoded
                bestRank = rank
            }
        }
        bestName
    }.getOrNull()

private fun ByteBuffer.unsignedShort(offset: Int): Int = getShort(offset).toInt() and 0xffff

private fun ByteBuffer.unsignedInt(offset: Int): Long = getInt(offset).toLong() and 0xffffffffL

private val MAC_ROMAN: Charset = runCatching { Charset.forName("x-MacRoman") }.getOrDefault(Charsets.ISO_8859_1)
