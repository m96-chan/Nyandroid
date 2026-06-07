package dev.nyandroid.terminal.input

import android.view.KeyEvent
import java.nio.charset.StandardCharsets

/**
 * Translates Android key events and committed text into the byte sequences a
 * terminal expects.
 *
 * Supports:
 * - C0 controls, CSI cursor keys, UTF-8 text
 * - DECCKM (application cursor keys mode): SS3 A/B/C/D
 * - F1-F12 function keys
 * - Alt+key (ESC prefix)
 * - Shift/Ctrl+Arrow (xterm modified key sequences)
 * - Virtual modifier keys from the extra-keys toolbar
 */
object KeyEncoder {

    private val ESC = byteArrayOf(0x1B)

    /** Encodes committed IME / clipboard text as UTF-8. */
    fun encodeText(text: CharSequence): ByteArray =
        text.toString().toByteArray(StandardCharsets.UTF_8)

    /**
     * Encodes a hardware/soft key press. Returns null if the event should be
     * handled by the platform (e.g. volume keys) rather than the terminal.
     *
     * @param applicationCursorKeys DECCKM state from the emulator.
     * @param virtualCtrl virtual Ctrl modifier from the extra-keys toolbar.
     * @param virtualAlt virtual Alt modifier from the extra-keys toolbar.
     * @param virtualFn virtual Fn modifier from the extra-keys toolbar.
     */
    fun encode(
        event: KeyEvent,
        applicationCursorKeys: Boolean = false,
        virtualCtrl: Boolean = false,
        virtualAlt: Boolean = false,
        virtualFn: Boolean = false,
    ): ByteArray? {
        val ctrl = event.isCtrlPressed || virtualCtrl
        val alt = event.isAltPressed || virtualAlt
        val shift = event.isShiftPressed

        // Fn mode: number row → F1-F12.
        if (virtualFn) {
            val fKey = fnNumberToFKey(event.keyCode)
            if (fKey != null) return maybeAltWrap(alt, fKey)
        }

        // F1-F12 (hardware or virtual Fn).
        encodeFunctionKey(event.keyCode)?.let { return maybeAltWrap(alt, it) }

        // Simple keys.
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                return maybeAltWrap(alt, byteArrayOf(CR))
            KeyEvent.KEYCODE_DEL ->
                return maybeAltWrap(alt, byteArrayOf(if (ctrl) BS else DEL))
            KeyEvent.KEYCODE_FORWARD_DEL ->
                return maybeAltWrap(alt, csi('3', '~'))
            KeyEvent.KEYCODE_ESCAPE -> return ESC
            KeyEvent.KEYCODE_TAB ->
                return if (shift) csi('Z') else maybeAltWrap(alt, byteArrayOf(HT))
        }

        // Arrow keys: respect DECCKM and modifiers.
        encodeCursorKey(event.keyCode, applicationCursorKeys, ctrl, shift)?.let {
            return maybeAltWrap(alt, it)
        }

        // Home/End/Insert/PageUp/PageDown with modifiers.
        encodeEditKey(event.keyCode, ctrl, shift)?.let {
            return maybeAltWrap(alt, it)
        }

        // Ctrl + letter -> control byte (e.g. Ctrl-C = 0x03).
        if (ctrl) {
            val c = event.getUnicodeChar(0)
            if (c in 'a'.code..'z'.code) {
                return maybeAltWrap(alt, byteArrayOf((c - 'a'.code + 1).toByte()))
            }
            if (c in 'A'.code..'Z'.code) {
                return maybeAltWrap(alt, byteArrayOf((c - 'A'.code + 1).toByte()))
            }
            when (event.keyCode) {
                KeyEvent.KEYCODE_SPACE ->
                    return maybeAltWrap(alt, byteArrayOf(0)) // Ctrl-Space = NUL
                KeyEvent.KEYCODE_LEFT_BRACKET ->
                    return maybeAltWrap(alt, ESC) // Ctrl-[ = ESC
                KeyEvent.KEYCODE_BACKSLASH ->
                    return maybeAltWrap(alt, byteArrayOf(0x1C))
                KeyEvent.KEYCODE_RIGHT_BRACKET ->
                    return maybeAltWrap(alt, byteArrayOf(0x1D))
            }
        }

        // Plain unicode character.
        val unicode = event.unicodeChar
        if (unicode != 0) {
            val text = String(Character.toChars(unicode)).toByteArray(StandardCharsets.UTF_8)
            return maybeAltWrap(alt, text)
        }
        return null
    }

    // --- Cursor keys (DECCKM-aware) ------------------------------------------

    private fun encodeCursorKey(
        keyCode: Int,
        appMode: Boolean,
        ctrl: Boolean,
        shift: Boolean,
    ): ByteArray? {
        val ch = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 'A'
            KeyEvent.KEYCODE_DPAD_DOWN -> 'B'
            KeyEvent.KEYCODE_DPAD_RIGHT -> 'C'
            KeyEvent.KEYCODE_DPAD_LEFT -> 'D'
            else -> return null
        }
        val modifier = modifierParam(ctrl, shift)
        if (modifier > 1) {
            // xterm modified cursor: CSI 1 ; <mod> <ch>
            return "\u001B[1;${modifier}${ch}".toByteArray(StandardCharsets.US_ASCII)
        }
        return if (appMode) ss3(ch) else csi(ch)
    }

    // --- Edit keys (Home/End/Insert/PageUp/PageDown) -------------------------

    private fun encodeEditKey(keyCode: Int, ctrl: Boolean, shift: Boolean): ByteArray? {
        val modifier = modifierParam(ctrl, shift)
        return when (keyCode) {
            KeyEvent.KEYCODE_MOVE_HOME ->
                if (modifier > 1) "\u001B[1;${modifier}H".toByteArray(StandardCharsets.US_ASCII)
                else csi('H')
            KeyEvent.KEYCODE_MOVE_END ->
                if (modifier > 1) "\u001B[1;${modifier}F".toByteArray(StandardCharsets.US_ASCII)
                else csi('F')
            KeyEvent.KEYCODE_PAGE_UP ->
                if (modifier > 1) "\u001B[5;${modifier}~".toByteArray(StandardCharsets.US_ASCII)
                else csi('5', '~')
            KeyEvent.KEYCODE_PAGE_DOWN ->
                if (modifier > 1) "\u001B[6;${modifier}~".toByteArray(StandardCharsets.US_ASCII)
                else csi('6', '~')
            KeyEvent.KEYCODE_INSERT ->
                if (modifier > 1) "\u001B[2;${modifier}~".toByteArray(StandardCharsets.US_ASCII)
                else csi('2', '~')
            else -> null
        }
    }

    // --- Function keys -------------------------------------------------------

    private fun encodeFunctionKey(keyCode: Int): ByteArray? = when (keyCode) {
        KeyEvent.KEYCODE_F1 -> ss3('P')
        KeyEvent.KEYCODE_F2 -> ss3('Q')
        KeyEvent.KEYCODE_F3 -> ss3('R')
        KeyEvent.KEYCODE_F4 -> ss3('S')
        KeyEvent.KEYCODE_F5 -> csi('1', '5', '~')
        KeyEvent.KEYCODE_F6 -> csi('1', '7', '~')
        KeyEvent.KEYCODE_F7 -> csi('1', '8', '~')
        KeyEvent.KEYCODE_F8 -> csi('1', '9', '~')
        KeyEvent.KEYCODE_F9 -> csi('2', '0', '~')
        KeyEvent.KEYCODE_F10 -> csi('2', '1', '~')
        KeyEvent.KEYCODE_F11 -> csi('2', '3', '~')
        KeyEvent.KEYCODE_F12 -> csi('2', '4', '~')
        else -> null
    }

    /** Maps number row (1-9, 0) to F1-F10, minus to F11, equals to F12 when Fn is active. */
    private fun fnNumberToFKey(keyCode: Int): ByteArray? = when (keyCode) {
        KeyEvent.KEYCODE_1 -> ss3('P')           // F1
        KeyEvent.KEYCODE_2 -> ss3('Q')           // F2
        KeyEvent.KEYCODE_3 -> ss3('R')           // F3
        KeyEvent.KEYCODE_4 -> ss3('S')           // F4
        KeyEvent.KEYCODE_5 -> csi('1', '5', '~') // F5
        KeyEvent.KEYCODE_6 -> csi('1', '7', '~') // F6
        KeyEvent.KEYCODE_7 -> csi('1', '8', '~') // F7
        KeyEvent.KEYCODE_8 -> csi('1', '9', '~') // F8
        KeyEvent.KEYCODE_9 -> csi('2', '0', '~') // F9
        KeyEvent.KEYCODE_0 -> csi('2', '1', '~') // F10
        KeyEvent.KEYCODE_MINUS -> csi('2', '3', '~') // F11
        KeyEvent.KEYCODE_EQUALS -> csi('2', '4', '~') // F12
        else -> null
    }

    // --- Helpers --------------------------------------------------------------

    /** xterm modifier parameter: 2=Shift, 5=Ctrl, 6=Ctrl+Shift. */
    private fun modifierParam(ctrl: Boolean, shift: Boolean): Int = when {
        ctrl && shift -> 6
        ctrl -> 5
        shift -> 2
        else -> 1
    }

    /** Wraps [bytes] with an ESC prefix if alt is held (Meta sends Escape). */
    private fun maybeAltWrap(alt: Boolean, bytes: ByteArray): ByteArray =
        if (alt) byteArrayOf(0x1B) + bytes else bytes

    private fun csi(final: Char): ByteArray =
        byteArrayOf(0x1B, '['.code.toByte(), final.code.toByte())

    private fun csi(param: Char, final: Char): ByteArray =
        byteArrayOf(0x1B, '['.code.toByte(), param.code.toByte(), final.code.toByte())

    private fun csi(p1: Char, p2: Char, final: Char): ByteArray =
        byteArrayOf(0x1B, '['.code.toByte(), p1.code.toByte(), p2.code.toByte(), final.code.toByte())

    private fun ss3(final: Char): ByteArray =
        byteArrayOf(0x1B, 'O'.code.toByte(), final.code.toByte())

    private const val CR: Byte = 0x0D
    private const val BS: Byte = 0x08
    private const val DEL: Byte = 0x7F
    private const val HT: Byte = 0x09
}
