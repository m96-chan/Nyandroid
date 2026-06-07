package dev.nyandroid.terminal.emulator

import java.nio.charset.StandardCharsets

/**
 * Byte-stream VT/ANSI parser driving a [TerminalGrid].
 *
 * Implements the subset of xterm/VT100 behaviour needed to host an interactive
 * shell and full-screen TUIs (`vi`, `less`, `top`): C0 controls, CSI cursor /
 * erase / edit / SGR, scroll regions, DEC private modes for cursor visibility
 * and the alternate screen, and OSC title strings (consumed and ignored).
 *
 * UTF-8 is decoded incrementally so multi-byte runes split across reads are
 * handled correctly. The parser is single-threaded; the emulator serialises
 * access.
 */
class VtParser(
    private val grid: TerminalGrid,
    private val respond: (ByteArray) -> Unit,
) {
    private enum class State { GROUND, ESCAPE, CSI, OSC, OSC_ESC, CHARSET }

    private var state = State.GROUND

    private val params = IntArray(MAX_PARAMS)
    private var paramCount = 0
    private var currentParam = -1
    private var privateMarker = false
    private var intermediateChar = 0

    // OSC string buffer.
    private val oscBuffer = StringBuilder()

    // Incremental UTF-8 decoding.
    private var utf8Remaining = 0
    private var utf8CodePoint = 0

    fun parse(data: ByteArray, length: Int) {
        var i = 0
        while (i < length) {
            val b = data[i].toInt() and 0xFF
            when (state) {
                State.GROUND -> ground(b)
                State.ESCAPE -> escape(b)
                State.CSI -> csi(b)
                State.OSC -> osc(b)
                State.OSC_ESC -> oscEsc(b)
                State.CHARSET -> { state = State.GROUND } // consume the designator byte
            }
            i++
        }
    }

    // --- GROUND -------------------------------------------------------------

    private fun ground(b: Int) {
        if (utf8Remaining > 0) {
            if (b and 0xC0 == 0x80) {
                utf8CodePoint = (utf8CodePoint shl 6) or (b and 0x3F)
                if (--utf8Remaining == 0) grid.putCodePoint(utf8CodePoint)
                return
            }
            // Malformed continuation; drop the partial sequence and reprocess b.
            utf8Remaining = 0
        }

        when {
            b == ESC -> state = State.ESCAPE
            b < 0x20 -> control(b)
            b < 0x80 -> grid.putCodePoint(b)
            b and 0xE0 == 0xC0 -> { utf8Remaining = 1; utf8CodePoint = b and 0x1F }
            b and 0xF0 == 0xE0 -> { utf8Remaining = 2; utf8CodePoint = b and 0x0F }
            b and 0xF8 == 0xF0 -> { utf8Remaining = 3; utf8CodePoint = b and 0x07 }
            else -> grid.putCodePoint(REPLACEMENT) // invalid lead byte
        }
    }

    private fun control(b: Int) {
        when (b) {
            BEL -> { /* bell: no-op for PoC */ }
            BS -> grid.backspace()
            HT -> grid.tab()
            LF, VT, FF -> grid.lineFeed()
            CR -> grid.carriageReturn()
            SO, SI -> { /* charset shift: ignored */ }
        }
    }

    // --- ESCAPE -------------------------------------------------------------

    private fun escape(b: Int) {
        when (b.toChar()) {
            '[' -> beginCsi()
            ']' -> { oscBuffer.setLength(0); state = State.OSC }
            'M' -> { grid.reverseIndex(); state = State.GROUND }
            'D' -> { grid.lineFeed(); state = State.GROUND }
            'E' -> { grid.carriageReturn(); grid.lineFeed(); state = State.GROUND }
            '7' -> { grid.saveCursor(); state = State.GROUND }
            '8' -> { grid.restoreCursor(); state = State.GROUND }
            'c' -> { grid.fullReset(); state = State.GROUND }
            '=' -> { grid.setApplicationKeypad(true); state = State.GROUND }  // DECKPAM
            '>' -> { grid.setApplicationKeypad(false); state = State.GROUND } // DECKPNM
            '(', ')', '*', '+' -> state = State.CHARSET
            else -> state = State.GROUND
        }
    }

    // --- CSI ----------------------------------------------------------------

    private fun beginCsi() {
        state = State.CSI
        paramCount = 0
        currentParam = -1
        privateMarker = false
        intermediateChar = 0
        params.fill(0)
    }

    private fun csi(b: Int) {
        when {
            b == '?'.code -> privateMarker = true
            b == '>'.code || b == '!'.code -> { /* private prefixes we ignore */ }
            b in '0'.code..'9'.code -> {
                if (currentParam < 0) currentParam = 0
                currentParam = currentParam * 10 + (b - '0'.code)
            }
            b == ';'.code -> pushParam()
            b in 0x20..0x2F -> intermediateChar = b
            b in 0x40..0x7E -> {
                pushParam()
                dispatchCsi(b.toChar())
                state = State.GROUND
            }
            else -> state = State.GROUND
        }
    }

    private fun pushParam() {
        if (paramCount < MAX_PARAMS) {
            params[paramCount++] = if (currentParam < 0) 0 else currentParam
        }
        currentParam = -1
    }

    private fun param(index: Int, default: Int): Int {
        if (index >= paramCount) return default
        val v = params[index]
        return if (v == 0) default else v
    }

    private fun dispatchCsi(final: Char) {
        when (final) {
            'A' -> grid.moveCursor(-param(0, 1), 0)
            'B' -> grid.moveCursor(param(0, 1), 0)
            'C' -> grid.moveCursor(0, param(0, 1))
            'D' -> grid.moveCursor(0, -param(0, 1))
            'E' -> { grid.moveCursor(param(0, 1), 0); grid.carriageReturn() }
            'F' -> { grid.moveCursor(-param(0, 1), 0); grid.carriageReturn() }
            'G', '`' -> grid.setCursorCol(param(0, 1))
            'd' -> grid.setCursorRow(param(0, 1))
            'H', 'f' -> grid.setCursor(param(0, 1), param(1, 1))
            'J' -> grid.eraseInDisplay(param(0, 0))
            'K' -> grid.eraseInLine(param(0, 0))
            'L' -> grid.insertLines(param(0, 1))
            'M' -> grid.deleteLines(param(0, 1))
            '@' -> grid.insertChars(param(0, 1))
            'P' -> grid.deleteChars(param(0, 1))
            'X' -> grid.eraseChars(param(0, 1))
            'S' -> grid.scrollUp(param(0, 1))
            'T' -> grid.scrollDown(param(0, 1))
            'r' -> grid.setScrollRegion(param(0, 1), param(1, grid.rows))
            'm' -> grid.applySgr(params, paramCount)
            'h' -> setMode(true)
            'l' -> setMode(false)
            's' -> grid.saveCursor()
            'u' -> grid.restoreCursor()
            'n' -> deviceStatusReport(param(0, 0))
            'q' -> if (intermediateChar == ' '.code) grid.setCursorShape(param(0, 0))
            else -> { /* unsupported: ignore */ }
        }
    }

    private fun setMode(enable: Boolean) {
        if (!privateMarker) return
        when (param(0, 0)) {
            1 -> grid.setApplicationCursorKeys(enable)  // DECCKM
            25 -> grid.setCursorVisible(enable)         // DECTCEM
            47, 1047, 1049 -> grid.setAltScreen(enable) // alternate screen
            1000 -> grid.setMouseTracking(if (enable) TerminalGrid.MOUSE_X10 else TerminalGrid.MOUSE_NONE)
            1002 -> grid.setMouseTracking(if (enable) TerminalGrid.MOUSE_BUTTON else TerminalGrid.MOUSE_NONE)
            1003 -> grid.setMouseTracking(if (enable) TerminalGrid.MOUSE_ANY else TerminalGrid.MOUSE_NONE)
            1006 -> grid.setMouseSgrFormat(enable)      // SGR extended mouse
            2004 -> grid.setBracketedPasteMode(enable)  // bracketed paste
        }
    }

    private fun deviceStatusReport(code: Int) {
        when (code) {
            5 -> respond(csiResponse("0n"))                                // OK
            6 -> respond(csiResponse("${grid.cursorRow + 1};${grid.cursorCol + 1}R"))
        }
    }

    private fun csiResponse(body: String): ByteArray =
        ("\u001B[$body").toByteArray(StandardCharsets.US_ASCII)

    // --- OSC ----------------------------------------------------------------

    private fun osc(b: Int) {
        when (b) {
            BEL -> { dispatchOsc(); state = State.GROUND }
            ESC -> state = State.OSC_ESC
            else -> {
                if (oscBuffer.length < MAX_OSC_LEN) oscBuffer.append(b.toChar())
            }
        }
    }

    private fun oscEsc(b: Int) {
        if (b == '\\'.code) dispatchOsc()
        state = State.GROUND
    }

    private fun dispatchOsc() {
        val s = oscBuffer.toString()
        val semi = s.indexOf(';')
        if (semi < 0) return
        val code = s.substring(0, semi).toIntOrNull() ?: return
        val payload = s.substring(semi + 1)
        when (code) {
            4 -> parseOsc4(payload)      // palette color
            10 -> parseOscColor(payload) { TerminalColors.DEFAULT_FG = it } // fg
            11 -> parseOscColor(payload) { TerminalColors.DEFAULT_BG = it } // bg
            12 -> parseOscColor(payload) { TerminalColors.CURSOR = it }     // cursor
            // 0, 1, 2: window title — ignored for now
        }
    }

    private fun parseOsc4(payload: String) {
        // Format: index;color e.g. "1;#ff0000"
        val parts = payload.split(';', limit = 2)
        if (parts.size < 2) return
        val index = parts[0].toIntOrNull() ?: return
        val color = parseHexColor(parts[1]) ?: return
        TerminalColors.setPaletteColor(index, color)
    }

    private inline fun parseOscColor(payload: String, setter: (Int) -> Unit) {
        val color = parseHexColor(payload) ?: return
        setter(color)
    }

    private fun parseHexColor(s: String): Int? {
        val hex = s.trim().removePrefix("#").removePrefix("rgb:")
        // Handle rgb:RR/GG/BB format
        if (hex.contains('/')) {
            val parts = hex.split('/')
            if (parts.size == 3) {
                val r = parts[0].take(2).toIntOrNull(16) ?: return null
                val g = parts[1].take(2).toIntOrNull(16) ?: return null
                val b = parts[2].take(2).toIntOrNull(16) ?: return null
                return (r shl 16) or (g shl 8) or b
            }
            return null
        }
        return if (hex.length == 6) hex.toIntOrNull(16) else null
    }

    companion object {
        private const val MAX_PARAMS = 16
        private const val REPLACEMENT = 0xFFFD

        private const val ESC = 0x1B
        private const val BEL = 0x07
        private const val BS = 0x08
        private const val HT = 0x09
        private const val LF = 0x0A
        private const val VT = 0x0B
        private const val FF = 0x0C
        private const val CR = 0x0D
        private const val SO = 0x0E
        private const val SI = 0x0F
        private const val MAX_OSC_LEN = 512
    }
}
