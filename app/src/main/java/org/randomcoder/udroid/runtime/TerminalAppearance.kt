package org.randomcoder.udroid.runtime

import android.content.Context
import androidx.annotation.ColorInt
import java.util.Base64
import java.util.Locale

const val TERMINAL_ANSI_COLOR_COUNT = 16

data class TerminalColorProfile(
    val id: String,
    val name: String,
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val ansiColors: List<Int>,
) {
    init {
        require(name.isNotBlank()) { "Profile name cannot be blank" }
        require(ansiColors.size == TERMINAL_ANSI_COLOR_COUNT) {
            "A terminal profile requires 16 ANSI colors"
        }
    }
}

data class TerminalProfileState(
    val selectedProfileId: String,
    val userProfiles: List<TerminalColorProfile>,
) {
    val profiles: List<TerminalColorProfile>
        get() = TerminalProfiles.builtIns + userProfiles

    val selectedProfile: TerminalColorProfile
        get() = profiles.firstOrNull { it.id == selectedProfileId } ?: TerminalProfiles.udroid
}

object TerminalProfiles {
    val udroid =
        TerminalColorProfile(
            id = "builtin:udroid",
            name = "uDroid",
            background = rgb("11131F"),
            foreground = rgb("E3E7EF"),
            cursor = rgb("43D292"),
            ansiColors =
                palette(
                    "000000", "CD0000", "43D292", "E8B86D", "65A7D8", "CD00CD", "00CDCD", "E5E5E5",
                    "7F7F7F", "FF0000", "00FF00", "FFFF00", "5C5CFF", "FF00FF", "00FFFF", "FFFFFF",
                ),
        )

    private val solarizedAnsi =
        palette(
            "073642", "DC322F", "859900", "B58900", "268BD2", "D33682", "2AA198", "EEE8D5",
            "002B36", "CB4B16", "586E75", "657B83", "839496", "6C71C4", "93A1A1", "FDF6E3",
        )

    val builtIns =
        listOf(
            udroid,
            TerminalColorProfile(
                id = "builtin:breeze",
                name = "Breeze",
                background = rgb("232627"),
                foreground = rgb("FCFCFC"),
                cursor = rgb("1D99F3"),
                ansiColors =
                    palette(
                        "232627", "ED1515", "11D116", "F67400", "1D99F3", "9B59B6", "1ABC9C", "FCFCFC",
                        "7F8C8D", "C0392B", "1CDC9A", "FDBC4B", "3DAEE9", "8E44AD", "16A085", "FFFFFF",
                    ),
            ),
            TerminalColorProfile(
                id = "builtin:solarized-dark",
                name = "Solarized Dark",
                background = rgb("002B36"),
                foreground = rgb("839496"),
                cursor = rgb("268BD2"),
                ansiColors = solarizedAnsi,
            ),
            TerminalColorProfile(
                id = "builtin:solarized-light",
                name = "Solarized Light",
                background = rgb("FDF6E3"),
                foreground = rgb("657B83"),
                cursor = rgb("268BD2"),
                ansiColors = solarizedAnsi,
            ),
            TerminalColorProfile(
                id = "builtin:dracula",
                name = "Dracula",
                background = rgb("282A36"),
                foreground = rgb("F8F8F2"),
                cursor = rgb("BD93F9"),
                ansiColors =
                    palette(
                        "21222C", "FF5555", "50FA7B", "F1FA8C", "BD93F9", "FF79C6", "8BE9FD", "F8F8F2",
                        "6272A4", "FF6E6E", "69FF94", "FFFFA5", "D6ACFF", "FF92DF", "A4FFFF", "FFFFFF",
                    ),
            ),
            TerminalColorProfile(
                id = "builtin:catppuccin-mocha",
                name = "Catppuccin Mocha",
                background = rgb("1E1E2E"),
                foreground = rgb("CDD6F4"),
                cursor = rgb("F5E0DC"),
                ansiColors =
                    palette(
                        "45475A", "F38BA8", "A6E3A1", "F9E2AF", "89B4FA", "F5C2E7", "94E2D5", "BAC2DE",
                        "585B70", "F38BA8", "A6E3A1", "F9E2AF", "89B4FA", "F5C2E7", "94E2D5", "A6ADC8",
                    ),
            ),
            TerminalColorProfile(
                id = "builtin:gruvbox-dark",
                name = "Gruvbox Dark",
                background = rgb("1D2021"),
                foreground = rgb("EBDBB2"),
                cursor = rgb("EBDBB2"),
                ansiColors =
                    palette(
                        "282828", "CC241D", "98971A", "D79921", "458588", "B16286", "689D6A", "A89984",
                        "928374", "FB4934", "B8BB26", "FABD2F", "83A598", "D3869B", "8EC07C", "EBDBB2",
                    ),
            ),
            TerminalColorProfile(
                id = "builtin:nord",
                name = "Nord",
                background = rgb("2E3440"),
                foreground = rgb("D8DEE9"),
                cursor = rgb("D8DEE9"),
                ansiColors =
                    palette(
                        "3B4252", "BF616A", "A3BE8C", "EBCB8B", "81A1C1", "B48EAD", "88C0D0", "E5E8F0",
                        "4C566A", "BF616A", "A3BE8C", "EBCB8B", "81A1C1", "B48EAD", "8FBCBB", "ECEFF4",
                    ),
            ),
            TerminalColorProfile(
                id = "builtin:tokyo-night",
                name = "Tokyo Night",
                background = rgb("1A1B26"),
                foreground = rgb("C0CAF5"),
                cursor = rgb("C0CAF5"),
                ansiColors =
                    palette(
                        "15161E", "F7768E", "9ECE6A", "E0AF68", "7AA2F7", "BB9AF7", "7DCFFF", "A9B1D6",
                        "414868", "F7768E", "9ECE6A", "E0AF68", "7AA2F7", "BB9AF7", "7DCFFF", "C0CAF5",
                    ),
            ),
            TerminalColorProfile(
                id = "builtin:rose-pine",
                name = "Rosé Pine",
                background = rgb("191724"),
                foreground = rgb("E0DEF4"),
                cursor = rgb("6E6A86"),
                ansiColors =
                    palette(
                        "26233A", "EB6F92", "31748F", "F6C177", "9CCFD8", "C4A7E7", "EBBCBA", "E0DEF4",
                        "6E6A86", "EB6F92", "31748F", "F6C177", "9CCFD8", "C4A7E7", "EBBCBA", "E0DEF4",
                    ),
            ),
            TerminalColorProfile(
                id = "builtin:ubuntu",
                name = "Ubuntu",
                background = rgb("300A24"),
                foreground = rgb("FFFFFF"),
                cursor = rgb("FFFFFF"),
                ansiColors =
                    palette(
                        "2E3436", "CC0000", "4E9A06", "C4A000", "3465A4", "75507B", "06989A", "D3D7CF",
                        "555753", "EF2929", "8AE234", "FCE94F", "729FCF", "AD7FA8", "34E2E2", "EEEEEC",
                    ),
            ),
        )
}

class TerminalProfileStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): TerminalProfileState =
        TerminalProfileState(
            selectedProfileId = preferences.getString(KEY_SELECTED, null) ?: TerminalProfiles.udroid.id,
            userProfiles =
                preferences
                    .getStringSet(KEY_USER_PROFILES, emptySet())
                    .orEmpty()
                    .mapNotNull(::decodeTerminalProfile)
                    .sortedBy(TerminalColorProfile::name),
        )

    fun save(state: TerminalProfileState) {
        preferences
            .edit()
            .putString(KEY_SELECTED, state.selectedProfile.id)
            .putStringSet(KEY_USER_PROFILES, state.userProfiles.map(::encodeTerminalProfile).toSet())
            .apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "terminal-profiles"
        const val KEY_SELECTED = "selected-profile"
        const val KEY_USER_PROFILES = "user-profiles"
    }
}

internal fun parseTerminalColor(value: String): Int? {
    val hex = value.trim().removePrefix("#")
    if (hex.length != 6 || hex.any { it.digitToIntOrNull(16) == null }) return null
    return (0xFF000000L or hex.toLong(16)).toInt()
}

internal fun formatTerminalColor(
    @ColorInt color: Int,
): String = String.format(Locale.US, "#%06X", color and 0xFFFFFF)

internal fun encodeTerminalProfile(profile: TerminalColorProfile): String =
    listOf(
        profile.id,
        Base64.getUrlEncoder().withoutPadding().encodeToString(profile.name.toByteArray()),
        formatTerminalColor(profile.background),
        formatTerminalColor(profile.foreground),
        formatTerminalColor(profile.cursor),
        profile.ansiColors.joinToString(",", transform = ::formatTerminalColor),
    ).joinToString("|")

internal fun decodeTerminalProfile(value: String): TerminalColorProfile? {
    val fields = value.split('|')
    if (fields.size != 6) return null
    return runCatching {
        TerminalColorProfile(
            id = fields[0].takeIf(String::isNotBlank) ?: error("Missing id"),
            name = String(Base64.getUrlDecoder().decode(fields[1])),
            background = parseTerminalColor(fields[2]) ?: error("Invalid background"),
            foreground = parseTerminalColor(fields[3]) ?: error("Invalid foreground"),
            cursor = parseTerminalColor(fields[4]) ?: error("Invalid cursor"),
            ansiColors = fields[5].split(',').map { parseTerminalColor(it) ?: error("Invalid ANSI color") },
        )
    }.getOrNull()
}

private fun rgb(value: String): Int = checkNotNull(parseTerminalColor(value))

private fun palette(vararg values: String): List<Int> = values.map(::rgb)
