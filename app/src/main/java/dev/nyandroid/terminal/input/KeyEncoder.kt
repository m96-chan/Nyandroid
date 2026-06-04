package dev.nyandroid.terminal.input

import android.view.KeyEvent
import java.nio.charset.StandardCharsets

/**
 * Translates Android key events and committed text into the byte sequences a
 * terminal expects (C0 controls, CSI cursor keys, UTF-8 text).
 *
 * Kept deliberately small for the PoC; xterm has many more modes (application
 * cursor keys, modifyOtherKeys, etc.) that can be layered on later.
 */
object KeyEncoder {

    private val ESC = byteArrayOf(0x1B)

    /** Encodes committed IME / clipboard text as UTF-8. */
    fun encodeText(text: CharSequence): ByteArray =
        text.toString().toByteArray(StandardCharsets.UTF_8)

    /**
     * Encodes a hardware/soft key press. Returns null if the event should be
     * handled by the platform (e.g. volume keys) rather than the terminal.
     */
    fun encode(event: KeyEvent): ByteArray? {
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> return byteArrayOf(CR)
            KeyEvent.KEYCODE_DEL -> return byteArrayOf(DEL)
            KeyEvent.KEYCODE_FORWARD_DEL -> return csi('3', '~')
            KeyEvent.KEYCODE_ESCAPE -> return ESC
            KeyEvent.KEYCODE_TAB -> return byteArrayOf(HT)
            KeyEvent.KEYCODE_DPAD_UP -> return csi('A')
            KeyEvent.KEYCODE_DPAD_DOWN -> return csi('B')
            KeyEvent.KEYCODE_DPAD_RIGHT -> return csi('C')
            KeyEvent.KEYCODE_DPAD_LEFT -> return csi('D')
            KeyEvent.KEYCODE_MOVE_HOME -> return csi('H')
            KeyEvent.KEYCODE_MOVE_END -> return csi('F')
            KeyEvent.KEYCODE_PAGE_UP -> return csi('5', '~')
            KeyEvent.KEYCODE_PAGE_DOWN -> return csi('6', '~')
            KeyEvent.KEYCODE_INSERT -> return csi('2', '~')
        }

        // Ctrl + letter -> control byte (e.g. Ctrl-C = 0x03).
        if (event.isCtrlPressed) {
            val c = event.getUnicodeChar(0)
            if (c in 'a'.code..'z'.code) return byteArrayOf((c - 'a'.code + 1).toByte())
            if (c in 'A'.code..'Z'.code) return byteArrayOf((c - 'A'.code + 1).toByte())
            when (event.keyCode) {
                KeyEvent.KEYCODE_SPACE -> return byteArrayOf(0) // Ctrl-Space = NUL
                KeyEvent.KEYCODE_LEFT_BRACKET -> return ESC     // Ctrl-[ = ESC
                KeyEvent.KEYCODE_BACKSLASH -> return byteArrayOf(0x1C)
            }
        }

        val unicode = event.unicodeChar
        if (unicode != 0) {
            return String(Character.toChars(unicode)).toByteArray(StandardCharsets.UTF_8)
        }
        return null
    }

    private fun csi(final: Char): ByteArray =
        byteArrayOf(0x1B, '['.code.toByte(), final.code.toByte())

    private fun csi(param: Char, final: Char): ByteArray =
        byteArrayOf(0x1B, '['.code.toByte(), param.code.toByte(), final.code.toByte())

    private const val CR: Byte = 0x0D
    private const val DEL: Byte = 0x7F
    private const val HT: Byte = 0x09
}
