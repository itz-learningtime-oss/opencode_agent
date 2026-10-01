package ai.opencode.term.terminal

/**
 * Incremental ANSI/VT parser. Feed bytes (UTF-8 aware for printable text) and it
 * emits callbacks into a TerminalBuffer. Escape sequences split across reads are
 * buffered until complete.
 *
 * Supported: SGR (colors, bold, reset), cursor movement (CUP, CUU/D/F/B), clear
 * screen/line (ED, EL), CR, LF, BEL, BS, TAB, cursor show/hide.
 */
class AnsiParser(private val screen: TerminalBuffer) {

    private enum class State { GROUND, ESC, CSI }

    private var state = State.GROUND
    private val csi = StringBuilder()
    private val utf8Buf = ArrayList<Byte>(4)

    var cursorVisible = true
        private set

    fun feed(bytes: ByteArray, length: Int) {
        var i = 0
        while (i < length) {
            val b = bytes[i].toInt() and 0xFF
            when (state) {
                State.GROUND -> {
                    when {
                        b == 0x1B -> { state = State.ESC; csi.clear() }
                        b == '\r'.code -> screen.carriageReturn()
                        b == '\n'.code -> screen.lineFeed()
                        b == '\b'.code -> screen.backspace()
                        b == '\t'.code -> screen.tab()
                        b == 7 -> { /* BEL — ignore */ }
                        b < 0x20 -> { /* other control — ignore */ }
                        else -> {
                            // UTF-8 multibyte assembly
                            val seqLen = utf8SequenceLength(b)
                            if (seqLen == 1) {
                                utf8Buf.clear()
                                screen.putChar(b.toChar())
                            } else {
                                utf8Buf.clear()
                                utf8Buf.add(bytes[i])
                                var complete = true
                                if (i + seqLen > length) {
                                    // Incomplete across chunk boundary — keep and stop.
                                    complete = false
                                    i = length
                                } else {
                                    for (k in 1 until seqLen) utf8Buf.add(bytes[i + k])
                                }
                                if (complete) {
                                    screen.putChar(decodeUtf8(utf8Buf))
                                    i += seqLen - 1
                                }
                            }
                        }
                    }
                    i++
                }
                State.ESC -> {
                    when (b) {
                        '['.code -> { state = State.CSI; csi.clear() }
                        ']'.code -> { state = State.GROUND /* OSC: skip until we see ST — simplified */ }
                        '('.code, ')'.code -> { /* charset designation: consume next */ state = State.GROUND; i++ }
                        else -> state = State.GROUND
                    }
                    i++
                }
                State.CSI -> {
                    if (b in 0x40..0x7E) {
                        handleCsi(csi.toString(), b.toChar())
                        state = State.GROUND
                    } else {
                        csi.append(b.toInt().toChar())
                    }
                    i++
                }
            }
        }
    }

    private fun utf8SequenceLength(first: Int): Int = when {
        first < 0x80 -> 1
        first and 0xE0 == 0xC0 -> 2
        first and 0xF0 == 0xE0 -> 3
        first and 0xF8 == 0xF0 -> 4
        else -> 1
    }

    private fun decodeUtf8(bytes: List<Byte>): Char {
        if (bytes.isEmpty()) return '?'
        return try {
            val s = String(bytes.toByteArray(), Charsets.UTF_8)
            if (s.isEmpty()) '?' else s[0]
        } catch (e: Exception) {
            '?'
        }
    }

    private fun handleCsi(params: String, final: Char) {
        val parts = params.split(';').map { it.toIntOrNull() ?: 0 }
        when (final) {
            'A' -> screen.moveCursor(0, -(parts.getOrElse(0) { 1 }.coerceAtLeast(1)))
            'B' -> screen.moveCursor(0, parts.getOrElse(0) { 1 }.coerceAtLeast(1))
            'C' -> screen.moveCursor(parts.getOrElse(0) { 1 }.coerceAtLeast(1), 0)
            'D' -> screen.moveCursor(-(parts.getOrElse(0) { 1 }.coerceAtLeast(1)), 0)
            'E' -> { screen.carriageReturn(); screen.moveCursor(0, parts.getOrElse(0) { 1 }.coerceAtLeast(1)) }
            'F' -> { screen.carriageReturn(); screen.moveCursor(0, -(parts.getOrElse(0) { 1 }.coerceAtLeast(1))) }
            'G' -> screen.setColumn(parts.getOrElse(0) { 1 })
            'H', 'f' -> screen.setCursor(
                (parts.getOrElse(1) { 1 }).coerceAtLeast(1),
                (parts.getOrElse(0) { 1 }).coerceAtLeast(1)
            )
            'J' -> when (parts.getOrElse(0) { 0 }) {
                0 -> screen.clearFromCursor()
                1 -> screen.clearToCursor()
                else -> screen.clearScreen()
            }
            'K' -> when (parts.getOrElse(0) { 0 }) {
                0 -> screen.clearLineFromCursor()
                1 -> screen.clearLineToCursor()
                else -> screen.clearLine()
            }
            'm' -> handleSgr(parts)
            'h', 'l' -> {
                // Private modes (cursor visibility etc.)
                if (params.contains("25")) cursorVisible = final == 'h'
            }
        }
    }

    private fun handleSgr(parts: List<Int>) {
        if (parts.isEmpty()) { screen.setAttribute(TerminalCell.Attr()); return }
        var i = 0
        while (i < parts.size) {
            when (val p = parts[i]) {
                0 -> screen.setAttribute(TerminalCell.Attr())
                1 -> screen.setAttribute(screen.currentAttr.copy(bold = true))
                22 -> screen.setAttribute(screen.currentAttr.copy(bold = false))
                in 30..37 -> screen.setAttribute(screen.currentAttr.copy(fg = p - 30))
                39 -> screen.setAttribute(screen.currentAttr.copy(fg = null))
                in 40..47 -> screen.setAttribute(screen.currentAttr.copy(bg = p - 40))
                49 -> screen.setAttribute(screen.currentAttr.copy(bg = null))
                in 90..97 -> screen.setAttribute(screen.currentAttr.copy(fg = p - 90 + 8))
                in 100..107 -> screen.setAttribute(screen.currentAttr.copy(bg = p - 100 + 8))
                38, 48 -> {
                    // Extended color: 38;5;N or 38;2;R;G;B
                    val target = if (p == 38) "fg" else "bg"
                    if (parts.getOrElse(i + 1) { -1 } == 5) {
                        val color = parts.getOrElse(i + 2) { -1 }
                        if (color in 0..255) {
                            screen.setAttribute(
                                if (target == "fg") screen.currentAttr.copy(fg = 256 + color)
                                else screen.currentAttr.copy(bg = 256 + color)
                            )
                        }
                        i += 2
                    } else if (parts.getOrElse(i + 1) { -1 } == 2) {
                        val r = parts.getOrElse(i + 2) { 0 }
                        val g = parts.getOrElse(i + 3) { 0 }
                        val b = parts.getOrElse(i + 4) { 0 }
                        val color = 256 + 256 * 256 + (r shl 16) + (g shl 8) + b  // truecolor marker
                        screen.setAttribute(
                            if (target == "fg") screen.currentAttr.copy(fg = color)
                            else screen.currentAttr.copy(bg = color)
                        )
                        i += 4
                    }
                }
            }
            i++
        }
    }
}
