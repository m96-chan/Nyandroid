package dev.nyandroid.terminal.emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Pure-JVM tests for the VT emulator. No Android dependencies, so these run on
 * a normal `test` source set without a device.
 */
class TerminalEmulatorTest {

    private val replies = StringBuilder()
    private val emu = TerminalEmulator(20, 5) {
        replies.append(String(it, StandardCharsets.US_ASCII))
    }
    private val frame = FrameSnapshot()

    private fun feed(s: String) {
        val b = s.toByteArray(StandardCharsets.UTF_8)
        emu.feed(b, b.size)
    }

    private fun row(r: Int): String {
        emu.snapshot(frame)
        val sb = StringBuilder()
        for (c in 0 until frame.cols) {
            val cp = frame.codePoints[r * frame.cols + c]
            sb.append(if (cp in 32..0x10FFFF) String(Character.toChars(cp)) else ' ')
        }
        return sb.toString().trimEnd()
    }

    @Test
    fun writesTextAndHandlesCrLf() {
        feed("hello\r\nworld")
        assertEquals("hello", row(0))
        assertEquals("world", row(1))
    }

    @Test
    fun cursorPositioningAndSgr() {
        feed("\u001B[3;1Habc\u001B[31mRED\u001B[0m")
        assertEquals("abcRED", row(2))
    }

    @Test
    fun deviceStatusReportReturnsCursorPosition() {
        feed("\u001B[2;5H\u001B[6n")
        assertTrue("expected CPR reply, got '$replies'", replies.contains("\u001B[2;5R"))
    }

    @Test
    fun eraseDisplayClearsScreen() {
        feed("filling text\r\nmore text\u001B[2J\u001B[H")
        for (r in 0 until 5) assertEquals("", row(r))
    }

    @Test
    fun deferredWrapAtRightMargin() {
        // 20 columns: write 21 chars, the 21st must land on the next row.
        feed("0123456789ABCDEFGHIJZ")
        assertEquals("0123456789ABCDEFGHIJ", row(0))
        assertEquals("Z", row(1))
    }

    @Test
    fun utf8SplitAcrossFeedsDecodesOnce() {
        val bytes = "あ".toByteArray(StandardCharsets.UTF_8) // 3 bytes: E3 81 82
        emu.feed(byteArrayOf(bytes[0]), 1)            // lead byte only
        emu.feed(byteArrayOf(bytes[1], bytes[2]), 2)  // remaining continuation bytes
        // The parser keeps UTF-8 state across feeds, so the rune decodes once.
        assertTrue(row(0).contains("あ"))
    }
}
