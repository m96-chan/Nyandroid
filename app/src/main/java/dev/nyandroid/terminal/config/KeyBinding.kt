package dev.nyandroid.terminal.config

import android.view.KeyEvent

/**
 * A single key binding parsed from `map` directives in kitty.conf.
 *
 * Example: `map ctrl+shift+c copy_to_clipboard`
 */
data class KeyBinding(
    val ctrl: Boolean,
    val alt: Boolean,
    val shift: Boolean,
    val keyCode: Int,
    val action: String,
    val args: String = "",
) {
    fun matches(event: KeyEvent, virtualCtrl: Boolean, virtualAlt: Boolean): Boolean {
        if (event.keyCode != keyCode) return false
        if (ctrl != (event.isCtrlPressed || virtualCtrl)) return false
        if (alt != (event.isAltPressed || virtualAlt)) return false
        if (shift != event.isShiftPressed) return false
        return true
    }

    companion object {
        /** Parse all `map` directives from kitty.conf text. */
        fun parseAll(text: String): List<KeyBinding> {
            val bindings = mutableListOf<KeyBinding>()
            for (line in text.lines()) {
                val trimmed = line.trim()
                if (!trimmed.startsWith("map ")) continue
                val parts = trimmed.removePrefix("map ").trim().split(Regex("\\s+"), limit = 3)
                if (parts.size < 2) continue
                val keySpec = parts[0]
                val action = parts[1]
                val args = if (parts.size > 2) parts[2] else ""
                parseKeySpec(keySpec)?.let { (ctrl, alt, shift, keyCode) ->
                    bindings.add(KeyBinding(ctrl, alt, shift, keyCode, action, args))
                }
            }
            return bindings
        }

        private data class KeySpec(val ctrl: Boolean, val alt: Boolean, val shift: Boolean, val keyCode: Int)

        private fun parseKeySpec(spec: String): KeySpec? {
            val parts = spec.lowercase().split('+')
            var ctrl = false
            var alt = false
            var shift = false
            var keyName = ""
            for (part in parts) {
                when (part) {
                    "ctrl", "control" -> ctrl = true
                    "alt", "opt" -> alt = true
                    "shift" -> shift = true
                    else -> keyName = part
                }
            }
            val keyCode = keyNameToCode(keyName) ?: return null
            return KeySpec(ctrl, alt, shift, keyCode)
        }

        private fun keyNameToCode(name: String): Int? = when (name) {
            // Letters
            "a" -> KeyEvent.KEYCODE_A; "b" -> KeyEvent.KEYCODE_B
            "c" -> KeyEvent.KEYCODE_C; "d" -> KeyEvent.KEYCODE_D
            "e" -> KeyEvent.KEYCODE_E; "f" -> KeyEvent.KEYCODE_F
            "g" -> KeyEvent.KEYCODE_G; "h" -> KeyEvent.KEYCODE_H
            "i" -> KeyEvent.KEYCODE_I; "j" -> KeyEvent.KEYCODE_J
            "k" -> KeyEvent.KEYCODE_K; "l" -> KeyEvent.KEYCODE_L
            "m" -> KeyEvent.KEYCODE_M; "n" -> KeyEvent.KEYCODE_N
            "o" -> KeyEvent.KEYCODE_O; "p" -> KeyEvent.KEYCODE_P
            "q" -> KeyEvent.KEYCODE_Q; "r" -> KeyEvent.KEYCODE_R
            "s" -> KeyEvent.KEYCODE_S; "t" -> KeyEvent.KEYCODE_T
            "u" -> KeyEvent.KEYCODE_U; "v" -> KeyEvent.KEYCODE_V
            "w" -> KeyEvent.KEYCODE_W; "x" -> KeyEvent.KEYCODE_X
            "y" -> KeyEvent.KEYCODE_Y; "z" -> KeyEvent.KEYCODE_Z
            // Numbers
            "0" -> KeyEvent.KEYCODE_0; "1" -> KeyEvent.KEYCODE_1
            "2" -> KeyEvent.KEYCODE_2; "3" -> KeyEvent.KEYCODE_3
            "4" -> KeyEvent.KEYCODE_4; "5" -> KeyEvent.KEYCODE_5
            "6" -> KeyEvent.KEYCODE_6; "7" -> KeyEvent.KEYCODE_7
            "8" -> KeyEvent.KEYCODE_8; "9" -> KeyEvent.KEYCODE_9
            // Special
            "enter", "return" -> KeyEvent.KEYCODE_ENTER
            "escape", "esc" -> KeyEvent.KEYCODE_ESCAPE
            "tab" -> KeyEvent.KEYCODE_TAB
            "space" -> KeyEvent.KEYCODE_SPACE
            "backspace" -> KeyEvent.KEYCODE_DEL
            "delete" -> KeyEvent.KEYCODE_FORWARD_DEL
            "up" -> KeyEvent.KEYCODE_DPAD_UP
            "down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "home" -> KeyEvent.KEYCODE_MOVE_HOME
            "end" -> KeyEvent.KEYCODE_MOVE_END
            "page_up" -> KeyEvent.KEYCODE_PAGE_UP
            "page_down" -> KeyEvent.KEYCODE_PAGE_DOWN
            "insert" -> KeyEvent.KEYCODE_INSERT
            // Function keys
            "f1" -> KeyEvent.KEYCODE_F1; "f2" -> KeyEvent.KEYCODE_F2
            "f3" -> KeyEvent.KEYCODE_F3; "f4" -> KeyEvent.KEYCODE_F4
            "f5" -> KeyEvent.KEYCODE_F5; "f6" -> KeyEvent.KEYCODE_F6
            "f7" -> KeyEvent.KEYCODE_F7; "f8" -> KeyEvent.KEYCODE_F8
            "f9" -> KeyEvent.KEYCODE_F9; "f10" -> KeyEvent.KEYCODE_F10
            "f11" -> KeyEvent.KEYCODE_F11; "f12" -> KeyEvent.KEYCODE_F12
            // Symbols
            "minus", "-" -> KeyEvent.KEYCODE_MINUS
            "equal", "equals", "+" -> KeyEvent.KEYCODE_EQUALS
            "plus" -> KeyEvent.KEYCODE_EQUALS // shift+= on most layouts
            else -> null
        }
    }
}
