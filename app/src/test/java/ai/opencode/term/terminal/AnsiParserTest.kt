package ai.opencode.term.terminal

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AnsiParserTest {

    private lateinit var buffer: TerminalBuffer
    private lateinit var parser: AnsiParser

    @Before
    fun setup() {
        buffer = TerminalBuffer(cols = 20, rows = 5, maxScrollback = 10)
        parser = AnsiParser(buffer)
    }

    private fun feed(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        parser.feed(bytes, bytes.size)
    }

    @Test
    fun `plain text lands in buffer`() {
        feed("hello")
        assertEquals("hello", buffer.toPlainText().trimEnd())
    }

    @Test
    fun `carriage return overwrites line`() {
        feed("abc\r\nXY")
        assertTrue(buffer.toPlainText().startsWith("XYc"))
    }

    @Test
    fun `backspace moves cursor left`() {
        feed("ab\bX")
        assertEquals("aX", buffer.toPlainText().trimEnd())
    }

    @Test
    fun `tab advances to next tab stop`() {
        feed("a\tb")
        val line = buffer.toPlainText().lines().first()
        assertEquals('b', line[8])
    }

    @Test
    fun `sgr bold and color applied`() {
        feed("\u001B[1;31mR\u001B[0mN")
        val line = buffer.lineAt(buffer.scrollbackCount())!!
        assertEquals(true, line[0].attr.bold)
        assertEquals(1, line[0].attr.fg) // red
        assertEquals(null, line[1].attr.fg)
        assertFalse(line[1].attr.bold)
    }

    @Test
    fun `escape sequence split across reads parses correctly`() {
        val seq = "\u001B[31mred"
        val bytes = seq.toByteArray()
        parser.feed(bytes, 2)      // ESC and '['
        parser.feed(bytes, bytes.size - 2)  // rest
        val line = buffer.lineAt(buffer.scrollbackCount())!!
        assertEquals(1, line[0].attr.fg)
        assertEquals('r', line[0].ch)
    }

    @Test
    fun `cursor positioning moves correctly`() {
        feed("A\u001B[1;1H B")
        val line = buffer.lineAt(buffer.scrollbackCount())!!
        assertEquals(' ', line[0].ch)
        assertEquals('B', line[1].ch)
    }

    @Test
    fun `clear screen empties buffer`() {
        feed("junk\u001B[2J")
        assertEquals("", buffer.toPlainText().trim())
    }

    @Test
    fun `cursor hide and show`() {
        feed("\u001B[?25l")
        assertFalse(parser.cursorVisible)
        feed("\u001B[?25h")
        assertTrue(parser.cursorVisible)
    }

    @Test
    fun `line feed scrolls and preserves scrollback`() {
        val b = TerminalBuffer(cols = 10, rows = 3, maxScrollback = 5)
        val p = AnsiParser(b)
        val bytes = "one\r\ntwo\r\nthree\r\nfour".toByteArray()
        p.feed(bytes, bytes.size)
        assertEquals(2, b.scrollbackCount())
        assertEquals("one", b.lineAt(0)?.joinToString("") { it.ch }?.trim())
    }
}
