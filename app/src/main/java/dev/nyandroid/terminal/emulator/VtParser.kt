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
    /** Callback for OSC 99/777 notifications: (oscCode, payload). */
    var onNotification: ((Int, String) -> Unit)? = null

    /** Callback for BEL (terminal bell). */
    var onBell: (() -> Unit)? = null
    private enum class State { GROUND, ESCAPE, CSI, OSC, OSC_ESC, CHARSET, APC, APC_ESC }

    private var state = State.GROUND

    private val params = IntArray(MAX_PARAMS)
    /** Marks params that were colon-joined to the previous one (SGR sub-params). */
    private val paramIsSub = BooleanArray(MAX_PARAMS)
    private var nextParamIsSub = false
    private var paramCount = 0
    private var currentParam = -1
    private var privateMarker = false
    private var intermediateChar = 0

    // OSC string buffer.
    private val oscBuffer = StringBuilder()
    // APC string buffer (kitty graphics protocol).
    private val apcBuffer = StringBuilder()

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
                State.APC -> apc(b)
                State.APC_ESC -> apcEsc(b)
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
            BEL -> onBell?.invoke()
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
            '_' -> { apcBuffer.setLength(0); state = State.APC } // APC (kitty graphics)
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
        nextParamIsSub = false
        params.fill(0)
        paramIsSub.fill(false)
    }

    private fun csi(b: Int) {
        when {
            b == '?'.code -> privateMarker = true
            b == '>'.code || b == '<'.code || b == '!'.code -> intermediateChar = b
            b in '0'.code..'9'.code -> {
                if (currentParam < 0) currentParam = 0
                currentParam = currentParam * 10 + (b - '0'.code)
            }
            b == ';'.code -> pushParam()
            b == ':'.code -> { pushParam(); nextParamIsSub = true } // SGR sub-param
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
            paramIsSub[paramCount] = nextParamIsSub
            params[paramCount++] = if (currentParam < 0) 0 else currentParam
        }
        currentParam = -1
        nextParamIsSub = false
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
            'm' -> grid.applySgr(params, paramIsSub, paramCount)
            'h' -> setMode(true)
            'l' -> setMode(false)
            's' -> grid.saveCursor()
            'u' -> dispatchCsiU()
            'n' -> deviceStatusReport(param(0, 0))
            'q' -> if (intermediateChar == ' '.code) grid.setCursorShape(param(0, 0))
            else -> { /* unsupported: ignore */ }
        }
    }

    private fun dispatchCsiU() {
        when {
            privateMarker -> {
                // CSI ? u — query keyboard protocol flags.
                // Respond with current flags: CSI ? <flags> u
                val flags = grid.kittyKeyboardFlags
                respond("\u001B[?${flags}u".toByteArray(StandardCharsets.US_ASCII))
            }
            intermediateChar == '>'.code -> {
                // CSI > <flags> u — push keyboard mode.
                grid.pushKittyKeyboardFlags(param(0, 0))
            }
            intermediateChar == '<'.code -> {
                // CSI < u — pop keyboard mode.
                grid.popKittyKeyboardFlags()
            }
            else -> grid.restoreCursor() // Plain CSI u = DECRC
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
            2026 -> grid.setSynchronizedUpdate(enable)  // synchronized output
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
            7 -> parseOsc7(payload)            // CWD reporting
            8 -> parseOsc8(payload)            // hyperlink
            10 -> parseOscColor(payload) { TerminalColors.DEFAULT_FG = it } // fg
            11 -> parseOscColor(payload) { TerminalColors.DEFAULT_BG = it } // bg
            12 -> parseOscColor(payload) { TerminalColors.CURSOR = it }     // cursor
            99 -> onNotification?.invoke(99, payload)   // kitty notification
            133 -> parseOsc133(payload)                   // shell integration marks
            777 -> onNotification?.invoke(777, payload)  // rxvt notification
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

    private fun parseOsc7(payload: String) {
        // Format: file://hostname/path
        val prefix = "file://"
        if (!payload.startsWith(prefix)) return
        val rest = payload.removePrefix(prefix)
        val slashIdx = rest.indexOf('/')
        if (slashIdx < 0) return
        val path = rest.substring(slashIdx)
        grid.currentWorkingDirectory = path
    }

    private fun parseOsc8(payload: String) {
        // Format: params;URI  (params is usually empty or "id=..."). Empty URI
        // closes the current hyperlink.
        val semi = payload.indexOf(';')
        val uri = if (semi >= 0) payload.substring(semi + 1) else ""
        grid.setHyperlink(uri.ifEmpty { null })
    }

    private fun parseOsc133(payload: String) {
        // Shell integration marks: A=prompt start, B=command start,
        // C=output start, D;exitcode=command end
        when {
            payload == "A" -> grid.shellMarkPromptStart()
            payload == "B" -> grid.shellMarkCommandStart()
            payload == "C" -> grid.shellMarkOutputStart()
            payload.startsWith("D") -> {
                val exitCode = payload.removePrefix("D").removePrefix(";").toIntOrNull() ?: 0
                grid.shellMarkCommandEnd(exitCode)
            }
        }
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

    // --- APC (kitty graphics protocol) ----------------------------------------

    private fun apc(b: Int) {
        when (b) {
            ESC -> state = State.APC_ESC
            else -> {
                if (apcBuffer.length < MAX_APC_LEN) apcBuffer.append(b.toChar())
            }
        }
    }

    private fun apcEsc(b: Int) {
        if (b == '\\'.code) dispatchApc()
        state = State.GROUND
    }

    private fun dispatchApc() {
        val s = apcBuffer.toString()
        if (!s.startsWith("G")) return // Only kitty graphics APC
        val payload = s.substring(1)
        grid.handleGraphicsCommand(payload)
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
        private const val MAX_APC_LEN = 4 * 1024 * 1024 // 4MB for image data
    }
}
