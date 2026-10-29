package org.randomcoder.udroid.x11

import android.view.KeyEvent

data class DisplayKey(val keyCode: Int, val x: Float, val y: Float)

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

    val defaults: List<Int> =
        listOf(
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_META_LEFT,
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
        )

    fun normalized(saved: List<Int>): List<Int> =
        defaults.indices.map { index ->
            saved.getOrNull(index)?.takeIf { it in keys } ?: defaults[index]
        }

    fun normalizedDisplayKeys(saved: List<DisplayKey>): List<DisplayKey> =
        saved.take(32).mapNotNull { key ->
            key.takeIf { it.keyCode in keys && it.x.isFinite() && it.y.isFinite() }
                ?.copy(x = key.x.coerceIn(0f, 1f), y = key.y.coerceIn(0f, 1f))
        }

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
            else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
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
