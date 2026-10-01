package ai.opencode.term.terminal

import org.junit.Assert.*
import org.junit.Test

class TerminalKeyHandlerTest {

    @Test
    fun `enter sends CR`() = assertEquals("\r", TerminalKeyHandler.enter())

    @Test
    fun `backspace sends DEL`() = assertEquals("\u007F", TerminalKeyHandler.backspace())

    @Test
    fun `arrows send CSI sequences`() {
        assertEquals("\u001B[A", TerminalKeyHandler.arrowUp())
        assertEquals("\u001B[B", TerminalKeyHandler.arrowDown())
        assertEquals("\u001B[C", TerminalKeyHandler.arrowRight())
        assertEquals("\u001B[D", TerminalKeyHandler.arrowLeft())
    }

    @Test
    fun `page keys send tilde sequences`() {
        assertEquals("\u001B[5~", TerminalKeyHandler.pageUp())
        assertEquals("\u001B[6~", TerminalKeyHandler.pageDown())
    }

    @Test
    fun `ctrl letters map to control codes`() {
        assertEquals("\u0003", TerminalKeyHandler.ctrl('C')) // SIGINT
        assertEquals("\u0004", TerminalKeyHandler.ctrl('D')) // EOF
        assertEquals("\u001A", TerminalKeyHandler.ctrl('Z')) // SUSP
        assertEquals("\u005F".let { "\u001F" }, TerminalKeyHandler.ctrl('_'))
    }

    @Test
    fun `ctrl space maps to NUL`() = assertEquals("\u0000", TerminalKeyHandler.ctrl(' '))

    @Test
    fun `alt prefixes ESC`() = assertEquals("\u001Bx", TerminalKeyHandler.alt("x"))

    @Test
    fun `ctrl lowercase equals uppercase`() = assertEquals(TerminalKeyHandler.ctrl('c'), TerminalKeyHandler.ctrl('C'))
}
