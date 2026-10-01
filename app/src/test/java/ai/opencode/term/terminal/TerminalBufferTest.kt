package ai.opencode.term.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalBufferTest {

    @Test
    fun `scrollback is bounded`() {
        val b = TerminalBuffer(cols = 10, rows = 3, maxScrollback = 4)
        repeat(20) { b.putChar('x'); b.lineFeed() }
        assertEquals(4, b.scrollbackCount())
    }

    @Test
    fun `lineAt spans scrollback and screen`() {
        val b = TerminalBuffer(cols = 10, rows = 2, maxScrollback = 10)
        b.putChar('a'); b.lineFeed(); b.putChar('b')
        assertEquals("a", b.lineAt(0)?.joinToString("") { it.ch }?.trim())
        assertEquals("b", b.lineAt(1)?.joinToString("") { it.ch }?.trim())
    }

    @Test
    fun `resize preserves top-left content`() {
        val b = TerminalBuffer(cols = 10, rows = 4)
        b.putChar('Z')
        b.resize(3, 5)
        assertEquals('Z', b.lineAt(b.scrollbackCount())?.first()?.ch)
        assertEquals(5, b.cols)
        assertEquals(3, b.rows)
    }

    @Test
    fun `revision increments on mutation`() {
        val b = TerminalBuffer()
        val r0 = b.revision
        b.putChar('x')
        assertTrue(b.revision > r0)
    }

    @Test
    fun `clearAll resets everything`() {
        val b = TerminalBuffer(cols = 10, rows = 3, maxScrollback = 3)
        repeat(10) { b.putChar('y'); b.lineFeed() }
        b.clearAll()
        assertEquals(0, b.scrollbackCount())
        assertEquals(0, b.cursorRow)
        assertEquals("", b.toPlainText().trim())
    }
}
