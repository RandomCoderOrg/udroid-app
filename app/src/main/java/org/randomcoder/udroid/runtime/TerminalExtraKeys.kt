package org.randomcoder.udroid.runtime

import android.content.Context
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import org.randomcoder.udroid.input.ExtraKeyBinding
import org.randomcoder.udroid.x11.OverlayKeyboard

data class TerminalExtraKeysState(
    val visible: Boolean = true,
    val rows: List<List<ExtraKeyBinding>> = TerminalExtraKeys.defaults,
) {
    fun normalized(): TerminalExtraKeysState = copy(rows = TerminalExtraKeys.normalized(rows))
}

data class TerminalExtraKeysImport(
    val rows: List<List<ExtraKeyBinding>>,
    val ignoredPopups: Int,
)

object TerminalExtraKeys {
    val defaults: List<List<ExtraKeyBinding>> =
        listOf(
            listOf(
                ExtraKeyBinding("CTRL"),
                ExtraKeyBinding("ALT"),
                ExtraKeyBinding("ESC"),
                ExtraKeyBinding("TAB"),
                ExtraKeyBinding("HOME"),
                ExtraKeyBinding("END"),
            ),
            listOf(
                ExtraKeyBinding("SHIFT"),
                ExtraKeyBinding("ENTER"),
                ExtraKeyBinding("LEFT"),
                ExtraKeyBinding("UP"),
                ExtraKeyBinding("DOWN"),
                ExtraKeyBinding("RIGHT"),
            ),
        )

    val keys: List<Int> =
        OverlayKeyboard.keys.filterNot {
            it < 0 || it == KeyEvent.KEYCODE_META_LEFT || it == KeyEvent.KEYCODE_META_RIGHT
        } + KeyEvent.KEYCODE_FUNCTION

    fun normalized(rows: List<List<ExtraKeyBinding>>): List<List<ExtraKeyBinding>> {
        val valid =
            rows.mapNotNull { row ->
                row.mapNotNull { candidate ->
                    candidate.normalized().takeIf { it.action.isNotEmpty() }
                }.takeIf(List<ExtraKeyBinding>::isNotEmpty)
            }
        return valid.takeIf(List<List<ExtraKeyBinding>>::isNotEmpty) ?: defaults
    }

    fun binding(keyCode: Int): ExtraKeyBinding = ExtraKeyBinding(termuxName(keyCode))

    fun label(binding: ExtraKeyBinding): String {
        if (binding.display != null) return binding.display
        if (binding.macro) return binding.action
        return keyCode(binding.action)?.let(::label) ?: binding.action
    }

    fun label(keyCode: Int): String =
        if (keyCode == KeyEvent.KEYCODE_FUNCTION) "Fn" else OverlayKeyboard.label(keyCode)

    fun isModifier(keyCode: Int): Boolean =
        OverlayKeyboard.isModifier(keyCode) || keyCode == KeyEvent.KEYCODE_FUNCTION

    fun parseTermuxJson(text: String): TerminalExtraKeysImport {
        val root = JSONArray(text.trim())
        require(root.length() > 0) { "Add at least one key row" }
        var ignoredPopups = 0
        val rows =
            (0 until root.length()).map { rowIndex ->
                val row = root.optJSONArray(rowIndex)
                    ?: error("Row ${rowIndex + 1} must be a JSON array")
                require(row.length() > 0) {
                    "Row ${rowIndex + 1} must contain at least one key"
                }
                (0 until row.length()).map { columnIndex ->
                    val value = row.get(columnIndex)
                    val binding =
                        when (value) {
                            is String -> ExtraKeyBinding(value)
                            is JSONObject -> {
                                if (value.has("popup")) ignoredPopups += 1
                                val key = value.optString("key").takeIf(String::isNotBlank)
                                val macro = value.optString("macro").takeIf(String::isNotBlank)
                                require((key == null) != (macro == null)) {
                                    "Set either key or macro at row ${rowIndex + 1}, key ${columnIndex + 1}"
                                }
                                ExtraKeyBinding(
                                    action = key ?: macro.orEmpty(),
                                    macro = macro != null,
                                    display = value.optString("display").takeIf(String::isNotBlank),
                                )
                            }
                            else -> error("Invalid key at row ${rowIndex + 1}, key ${columnIndex + 1}")
                        }
                    require(binding.tokens().all { keyCode(it) != null || it.isNotEmpty() }) {
                        "Invalid action at row ${rowIndex + 1}, key ${columnIndex + 1}"
                    }
                    binding
                }
            }
        return TerminalExtraKeysImport(normalized(rows), ignoredPopups)
    }

    fun toTermuxJson(rows: List<List<ExtraKeyBinding>>): String =
        JSONArray().apply {
            normalized(rows).forEach { row ->
                put(
                    JSONArray().apply {
                        row.forEach { binding ->
                            if (!binding.macro && binding.display == null) {
                                put(binding.action)
                            } else {
                                put(
                                    JSONObject().apply {
                                        put(if (binding.macro) "macro" else "key", binding.action)
                                        binding.display?.let { put("display", it) }
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }.toString(2)

    fun keyCode(rawName: String): Int? {
        val name = rawName.trim()
        val upper = name.uppercase()
        return when {
            upper.length == 1 && upper[0] in 'A'..'Z' ->
                KeyEvent.KEYCODE_A + (upper[0] - 'A')
            upper.length == 1 && upper[0] in '0'..'9' ->
                KeyEvent.KEYCODE_0 + (upper[0] - '0')
            upper.startsWith("F") && upper.drop(1).toIntOrNull() in 1..12 ->
                KeyEvent.KEYCODE_F1 + (upper.drop(1).toInt() - 1)
            else -> TERMUX_TO_KEY_CODE[upper]
        }
    }

    fun termuxName(keyCode: Int): String =
        when {
            keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                ('A' + (keyCode - KeyEvent.KEYCODE_A)).toString()
            keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                ('0' + (keyCode - KeyEvent.KEYCODE_0)).toString()
            keyCode in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
                "F${keyCode - KeyEvent.KEYCODE_F1 + 1}"
            else -> KEY_CODE_TO_TERMUX[keyCode] ?: error("Unsupported key code $keyCode")
        }

    private val TERMUX_TO_KEY_CODE =
        mapOf(
            "CTRL" to KeyEvent.KEYCODE_CTRL_LEFT,
            "CONTROL" to KeyEvent.KEYCODE_CTRL_LEFT,
            "ALT" to KeyEvent.KEYCODE_ALT_LEFT,
            "SHIFT" to KeyEvent.KEYCODE_SHIFT_LEFT,
            "SHFT" to KeyEvent.KEYCODE_SHIFT_LEFT,
            "FN" to KeyEvent.KEYCODE_FUNCTION,
            "FUNCTION" to KeyEvent.KEYCODE_FUNCTION,
            "ESC" to KeyEvent.KEYCODE_ESCAPE,
            "ESCAPE" to KeyEvent.KEYCODE_ESCAPE,
            "TAB" to KeyEvent.KEYCODE_TAB,
            "SPACE" to KeyEvent.KEYCODE_SPACE,
            "ENTER" to KeyEvent.KEYCODE_ENTER,
            "RETURN" to KeyEvent.KEYCODE_ENTER,
            "BKSP" to KeyEvent.KEYCODE_DEL,
            "BACKSPACE" to KeyEvent.KEYCODE_DEL,
            "DEL" to KeyEvent.KEYCODE_FORWARD_DEL,
            "DELETE" to KeyEvent.KEYCODE_FORWARD_DEL,
            "UP" to KeyEvent.KEYCODE_DPAD_UP,
            "LEFT" to KeyEvent.KEYCODE_DPAD_LEFT,
            "DOWN" to KeyEvent.KEYCODE_DPAD_DOWN,
            "RIGHT" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "HOME" to KeyEvent.KEYCODE_MOVE_HOME,
            "END" to KeyEvent.KEYCODE_MOVE_END,
            "PGUP" to KeyEvent.KEYCODE_PAGE_UP,
            "PAGEUP" to KeyEvent.KEYCODE_PAGE_UP,
            "PAGE_UP" to KeyEvent.KEYCODE_PAGE_UP,
            "PGDN" to KeyEvent.KEYCODE_PAGE_DOWN,
            "PAGEDOWN" to KeyEvent.KEYCODE_PAGE_DOWN,
            "PAGE_DOWN" to KeyEvent.KEYCODE_PAGE_DOWN,
            "INS" to KeyEvent.KEYCODE_INSERT,
            "INSERT" to KeyEvent.KEYCODE_INSERT,
            "-" to KeyEvent.KEYCODE_MINUS,
            "=" to KeyEvent.KEYCODE_EQUALS,
            "[" to KeyEvent.KEYCODE_LEFT_BRACKET,
            "]" to KeyEvent.KEYCODE_RIGHT_BRACKET,
            "\\" to KeyEvent.KEYCODE_BACKSLASH,
            ";" to KeyEvent.KEYCODE_SEMICOLON,
            "'" to KeyEvent.KEYCODE_APOSTROPHE,
            "," to KeyEvent.KEYCODE_COMMA,
            "." to KeyEvent.KEYCODE_PERIOD,
            "/" to KeyEvent.KEYCODE_SLASH,
        )

    private val KEY_CODE_TO_TERMUX =
        TERMUX_TO_KEY_CODE.entries
            .filterNot {
                it.key in
                    setOf(
                        "CONTROL", "SHFT", "FUNCTION", "ESCAPE", "RETURN",
                        "BACKSPACE", "DELETE", "PAGEUP", "PAGE_UP", "PAGEDOWN",
                        "PAGE_DOWN", "INSERT",
                    )
            }
            .associate { (name, keyCode) -> keyCode to name }
}

class TerminalExtraKeysStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): TerminalExtraKeysState {
        val rows =
            preferences.getString(KEY_ROWS, null)?.let { saved ->
                runCatching { TerminalExtraKeys.parseTermuxJson(saved).rows }.getOrNull()
            } ?: TerminalExtraKeys.defaults
        return TerminalExtraKeysState(
            visible = preferences.getBoolean(KEY_VISIBLE, true),
            rows = rows,
        ).normalized()
    }

    fun save(state: TerminalExtraKeysState): TerminalExtraKeysState =
        state.normalized().also { normalized ->
            preferences.edit()
                .putBoolean(KEY_VISIBLE, normalized.visible)
                .putString(KEY_ROWS, TerminalExtraKeys.toTermuxJson(normalized.rows))
                .apply()
        }

    private companion object {
        const val PREFERENCES_NAME = "terminal-extra-keys"
        const val KEY_VISIBLE = "visible"
        const val KEY_ROWS = "rows"
    }
}
