package org.randomcoder.udroid.x11

import android.view.KeyEvent
import org.randomcoder.udroid.input.ExtraKeyBinding

data class DisplayKey(val binding: ExtraKeyBinding, val x: Float, val y: Float)

object OverlayKeyboard {
    const val MOUSE_LEFT = -1
    const val MOUSE_MIDDLE = -2
    const val MOUSE_RIGHT = -3
    const val SCROLL_UP = -4
    const val SCROLL_DOWN = -5

    val keys: List<Int> =
        listOf(
            MOUSE_LEFT, MOUSE_MIDDLE, MOUSE_RIGHT, SCROLL_UP, SCROLL_DOWN,
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_INSERT, KeyEvent.KEYCODE_MINUS,
            KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_LEFT_BRACKET,
            KeyEvent.KEYCODE_RIGHT_BRACKET, KeyEvent.KEYCODE_BACKSLASH,
            KeyEvent.KEYCODE_SEMICOLON, KeyEvent.KEYCODE_APOSTROPHE,
            KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_SLASH,
        ) +
            (KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z).toList() +
            (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9).toList() +
            (KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12).toList()

    val defaultKeyCodes: List<Int> =
        listOf(
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
        )

    val defaultRows: List<List<ExtraKeyBinding>> = defaultKeyCodes.map(::binding).chunked(6)
    val defaults: List<ExtraKeyBinding> = defaultRows.flatten()

    fun normalized(saved: List<ExtraKeyBinding>): List<ExtraKeyBinding> =
        defaults.indices.map { index ->
            saved.getOrNull(index)?.normalized()?.takeIf { it.action.isNotEmpty() } ?: defaults[index]
        }

    fun normalizedRows(saved: List<List<ExtraKeyBinding>>): List<List<ExtraKeyBinding>> =
        saved.mapNotNull { row ->
            row.mapNotNull { it.normalized().takeIf { binding -> binding.action.isNotEmpty() } }
                .takeIf { it.isNotEmpty() }
        }.takeIf { it.isNotEmpty() } ?: defaultRows

    fun normalizedDisplayKeys(saved: List<DisplayKey>): List<DisplayKey> =
        saved.take(32).mapNotNull { key ->
            key.takeIf { it.binding.action.isNotBlank() && it.x.isFinite() && it.y.isFinite() }
                ?.copy(x = key.x.coerceIn(0f, 1f), y = key.y.coerceIn(0f, 1f))
        }

    fun binding(keyCode: Int): ExtraKeyBinding = ExtraKeyBinding(name(keyCode))

    fun keyCode(name: String): Int? =
        when (name.trim().uppercase()) {
            "MOUSE_LEFT" -> MOUSE_LEFT
            "MOUSE_MIDDLE" -> MOUSE_MIDDLE
            "MOUSE_RIGHT" -> MOUSE_RIGHT
            "SCROLL_UP" -> SCROLL_UP
            "SCROLL_DOWN" -> SCROLL_DOWN
            "CTRL", "CONTROL" -> KeyEvent.KEYCODE_CTRL_LEFT
            "RCTRL", "RIGHT_CTRL" -> KeyEvent.KEYCODE_CTRL_RIGHT
            "ALT" -> KeyEvent.KEYCODE_ALT_LEFT
            "RALT", "RIGHT_ALT" -> KeyEvent.KEYCODE_ALT_RIGHT
            "SHIFT" -> KeyEvent.KEYCODE_SHIFT_LEFT
            "RSHIFT", "RIGHT_SHIFT" -> KeyEvent.KEYCODE_SHIFT_RIGHT
            "META", "SUPER" -> KeyEvent.KEYCODE_META_LEFT
            "RMETA", "RSUPER" -> KeyEvent.KEYCODE_META_RIGHT
            "ESC", "ESCAPE" -> KeyEvent.KEYCODE_ESCAPE
            "TAB" -> KeyEvent.KEYCODE_TAB
            "SPACE" -> KeyEvent.KEYCODE_SPACE
            "ENTER", "RETURN" -> KeyEvent.KEYCODE_ENTER
            "BKSP", "BACKSPACE" -> KeyEvent.KEYCODE_DEL
            "DEL", "DELETE" -> KeyEvent.KEYCODE_FORWARD_DEL
            "UP" -> KeyEvent.KEYCODE_DPAD_UP
            "LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
            "DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
            "RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "HOME" -> KeyEvent.KEYCODE_MOVE_HOME
            "END" -> KeyEvent.KEYCODE_MOVE_END
            "PGUP" -> KeyEvent.KEYCODE_PAGE_UP
            "PGDN" -> KeyEvent.KEYCODE_PAGE_DOWN
            "INS" -> KeyEvent.KEYCODE_INSERT
            "-" -> KeyEvent.KEYCODE_MINUS
            "=" -> KeyEvent.KEYCODE_EQUALS
            "[" -> KeyEvent.KEYCODE_LEFT_BRACKET
            "]" -> KeyEvent.KEYCODE_RIGHT_BRACKET
            "\\" -> KeyEvent.KEYCODE_BACKSLASH
            ";" -> KeyEvent.KEYCODE_SEMICOLON
            "'" -> KeyEvent.KEYCODE_APOSTROPHE
            "," -> KeyEvent.KEYCODE_COMMA
            "." -> KeyEvent.KEYCODE_PERIOD
            "/" -> KeyEvent.KEYCODE_SLASH
            else -> parseLetterNumberOrFunction(name.trim())
        }

    fun name(keyCode: Int): String =
        when (keyCode) {
            MOUSE_LEFT -> "MOUSE_LEFT"
            MOUSE_MIDDLE -> "MOUSE_MIDDLE"
            MOUSE_RIGHT -> "MOUSE_RIGHT"
            SCROLL_UP -> "SCROLL_UP"
            SCROLL_DOWN -> "SCROLL_DOWN"
            KeyEvent.KEYCODE_CTRL_LEFT -> "CTRL"
            KeyEvent.KEYCODE_CTRL_RIGHT -> "RCTRL"
            KeyEvent.KEYCODE_ALT_LEFT -> "ALT"
            KeyEvent.KEYCODE_ALT_RIGHT -> "RALT"
            KeyEvent.KEYCODE_SHIFT_LEFT -> "SHIFT"
            KeyEvent.KEYCODE_SHIFT_RIGHT -> "RSHIFT"
            KeyEvent.KEYCODE_META_LEFT -> "META"
            KeyEvent.KEYCODE_META_RIGHT -> "RMETA"
            KeyEvent.KEYCODE_ESCAPE -> "ESC"
            KeyEvent.KEYCODE_TAB -> "TAB"
            KeyEvent.KEYCODE_SPACE -> "SPACE"
            KeyEvent.KEYCODE_ENTER -> "ENTER"
            KeyEvent.KEYCODE_DEL -> "BKSP"
            KeyEvent.KEYCODE_FORWARD_DEL -> "DEL"
            KeyEvent.KEYCODE_DPAD_UP -> "UP"
            KeyEvent.KEYCODE_DPAD_LEFT -> "LEFT"
            KeyEvent.KEYCODE_DPAD_DOWN -> "DOWN"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "RIGHT"
            KeyEvent.KEYCODE_MOVE_HOME -> "HOME"
            KeyEvent.KEYCODE_MOVE_END -> "END"
            KeyEvent.KEYCODE_PAGE_UP -> "PGUP"
            KeyEvent.KEYCODE_PAGE_DOWN -> "PGDN"
            KeyEvent.KEYCODE_INSERT -> "INS"
            KeyEvent.KEYCODE_MINUS -> "-"
            KeyEvent.KEYCODE_EQUALS -> "="
            KeyEvent.KEYCODE_LEFT_BRACKET -> "["
            KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
            KeyEvent.KEYCODE_BACKSLASH -> "\\"
            KeyEvent.KEYCODE_SEMICOLON -> ";"
            KeyEvent.KEYCODE_APOSTROPHE -> "'"
            KeyEvent.KEYCODE_COMMA -> ","
            KeyEvent.KEYCODE_PERIOD -> "."
            KeyEvent.KEYCODE_SLASH -> "/"
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A'.code + keyCode - KeyEvent.KEYCODE_A).toChar().toString()
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> ('0'.code + keyCode - KeyEvent.KEYCODE_0).toChar().toString()
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F${keyCode - KeyEvent.KEYCODE_F1 + 1}"
            else -> "Key $keyCode"
        }

    fun label(binding: ExtraKeyBinding): String =
        binding.display ?: if (binding.macro) binding.action else keyCode(binding.action)?.let(::label) ?: binding.action

    fun isModifier(keyCode: Int): Boolean =
        keyCode in KeyEvent.KEYCODE_CTRL_LEFT..KeyEvent.KEYCODE_CTRL_RIGHT ||
            keyCode in KeyEvent.KEYCODE_ALT_LEFT..KeyEvent.KEYCODE_ALT_RIGHT ||
            keyCode in KeyEvent.KEYCODE_SHIFT_LEFT..KeyEvent.KEYCODE_SHIFT_RIGHT ||
            keyCode in KeyEvent.KEYCODE_META_LEFT..KeyEvent.KEYCODE_META_RIGHT

    fun label(keyCode: Int): String =
        when (keyCode) {
            MOUSE_LEFT -> "Mouse L"
            MOUSE_MIDDLE -> "Mouse M"
            MOUSE_RIGHT -> "Mouse R"
            SCROLL_UP -> "Scroll ↑"
            SCROLL_DOWN -> "Scroll ↓"
            KeyEvent.KEYCODE_CTRL_LEFT -> "Ctrl"
            KeyEvent.KEYCODE_CTRL_RIGHT -> "R Ctrl"
            KeyEvent.KEYCODE_ALT_LEFT -> "Alt"
            KeyEvent.KEYCODE_ALT_RIGHT -> "R Alt"
            KeyEvent.KEYCODE_SHIFT_LEFT -> "Shift"
            KeyEvent.KEYCODE_SHIFT_RIGHT -> "R Shift"
            KeyEvent.KEYCODE_META_LEFT -> "Super"
            KeyEvent.KEYCODE_META_RIGHT -> "R Super"
            KeyEvent.KEYCODE_ESCAPE -> "Esc"
            KeyEvent.KEYCODE_DEL -> "Backspace"
            KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
            KeyEvent.KEYCODE_DPAD_UP -> "↑"
            KeyEvent.KEYCODE_DPAD_LEFT -> "←"
            KeyEvent.KEYCODE_DPAD_DOWN -> "↓"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "→"
            KeyEvent.KEYCODE_MOVE_HOME -> "Home"
            KeyEvent.KEYCODE_MOVE_END -> "End"
            KeyEvent.KEYCODE_PAGE_UP -> "PgUp"
            KeyEvent.KEYCODE_PAGE_DOWN -> "PgDn"
            else -> name(keyCode)
        }

    private fun parseLetterNumberOrFunction(name: String): Int? {
        val upper = name.uppercase()
        return when {
            upper.length == 1 && upper[0] in 'A'..'Z' -> KeyEvent.KEYCODE_A + (upper[0] - 'A')
            upper.length == 1 && upper[0] in '0'..'9' -> KeyEvent.KEYCODE_0 + (upper[0] - '0')
            upper.startsWith("F") && upper.drop(1).toIntOrNull() in 1..12 ->
                KeyEvent.KEYCODE_F1 + (upper.drop(1).toInt() - 1)
            else -> null
        }
    }
}

/** Tracks each touch/latch separately so duplicate bindings release only after their last owner. */
internal class OverlayKeyState(private val send: (Int, Boolean) -> Unit) {
    private val owners = mutableMapOf<Int, Int>()

    fun press(owner: Int, keyCode: Int) {
        if (owners[owner] == keyCode) return
        release(owner)
        if (keyCode !in owners.values) send(keyCode, true)
        owners[owner] = keyCode
    }

    fun release(owner: Int) {
        val keyCode = owners.remove(owner) ?: return
        if (keyCode !in owners.values) send(keyCode, false)
    }

    fun isPressed(owner: Int): Boolean = owner in owners

    fun releaseAll() {
        owners.values.toSet().forEach { send(it, false) }
        owners.clear()
    }

    fun clear() {
        owners.clear()
    }
}
