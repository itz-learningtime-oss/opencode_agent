package ai.opencode.term.terminal

/** One terminal cell with style attributes. */
data class TerminalCell(
    val ch: Char = ' ',
    val attr: Attr = Attr()
) {
    data class Attr(
        val bold: Boolean = false,
        /** 0..7 base ANSI, 8..15 bright, 256+n truecolor (0xRRGGBB packed after 256 offset+65536), null = default */
        val fg: Int? = null,
        val bg: Int? = null
    )
}

/**
 * Fixed-size screen grid plus bounded scrollback, optimized for low-memory
 * 32-bit devices (rows*cols cells, back-stored as flat arrays).
 */
class TerminalBuffer(
    var cols: Int = 80,
    var rows: Int = 24,
    private val maxScrollback: Int = 200
) {
    private var cells = Array(rows) { Array(cols) { TerminalCell() } }
    private val scrollback = ArrayDeque<List<TerminalCell>>(maxScrollback)

    var cursorRow = 0
        private set
    var cursorCol = 0
        private set

    /** Monotonic counter bumped whenever content changes; TerminalView compares to decide redraws. */
    @Volatile
    var revision: Long = 0
        private set

    fun currentAttr(): TerminalCell.Attr = currentAttr
    private var currentAttr = TerminalCell.Attr()

    fun setAttribute(attr: TerminalCell.Attr) {
        currentAttr = attr
    }

    val currentAttrValue: TerminalCell.Attr get() = currentAttr

    fun putChar(ch: Char) {
        if (cursorCol >= cols) {
            cursorCol = 0
            lineFeed()
        }
        cells[cursorRow][cursorCol] = TerminalCell(ch, currentAttr)
        cursorCol++
        revision++
    }

    fun carriageReturn() { cursorCol = 0 }

    fun lineFeed() {
        if (cursorRow >= rows - 1) {
            scrollUp()
        } else {
            cursorRow++
        }
        revision++
    }

    fun backspace() {
        if (cursorCol > 0) cursorCol--
    }

    fun tab() {
        val next = ((cursorCol / 8) + 1) * 8
        cursorCol = next.coerceAtMost(cols - 1)
    }

    fun moveCursor(dx: Int, dy: Int) {
        cursorCol = (cursorCol + dx).coerceIn(0, cols - 1)
        cursorRow = (cursorRow + dy).coerceIn(0, rows - 1)
        revision++
    }

    fun setCursor(row: Int, col: Int) {
        cursorRow = (row - 1).coerceIn(0, rows - 1)
        cursorCol = (col - 1).coerceIn(0, cols - 1)
        revision++
    }

    fun setColumn(col: Int) {
        cursorCol = (col - 1).coerceIn(0, cols - 1)
        revision++
    }

    fun clearScreen() {
        for (r in 0 until rows) for (c in 0 until cols) cells[r][c] = TerminalCell(attr = currentAttr)
        revision++
    }

    fun clearFromCursor() {
        for (c in cursorCol until cols) cells[cursorRow][c] = TerminalCell(attr = currentAttr)
        for (r in cursorRow + 1 until rows) for (c in 0 until cols) cells[r][c] = TerminalCell(attr = currentAttr)
        revision++
    }

    fun clearToCursor() {
        for (r in 0 until cursorRow) for (c in 0 until cols) cells[r][c] = TerminalCell(attr = currentAttr)
        for (c in 0..cursorCol) cells[cursorRow][c] = TerminalCell(attr = currentAttr)
        revision++
    }

    fun clearLine() {
        for (c in 0 until cols) cells[cursorRow][c] = TerminalCell(attr = currentAttr)
        revision++
    }

    fun clearLineFromCursor() {
        for (c in cursorCol until cols) cells[cursorRow][c] = TerminalCell(attr = currentAttr)
        revision++
    }

    fun clearLineToCursor() {
        for (c in 0..cursorCol) cells[cursorRow][c] = TerminalCell(attr = currentAttr)
        revision++
    }

    private fun scrollUp() {
        if (scrollback.size >= maxScrollback) scrollback.removeFirst()
        scrollback.addLast(cells[0].toList())
        // Shift rows up
        for (r in 0 until rows - 1) cells[r] = cells[r + 1]
        cells[rows - 1] = Array(cols) { TerminalCell(attr = currentAttr) }
    }

    /** Total lines stored above the visible screen (scrollback + screen). */
    fun scrollbackCount(): Int = scrollback.size

    /** Get a line from combined history; offset 0 = oldest scrollback line. */
    fun lineAt(offset: Int): List<TerminalCell>? {
        val sb = scrollback.size
        return when {
            offset < sb -> scrollback[offset]
            offset < sb + rows -> cells[offset - sb].toList()
            else -> null
        }
    }

    fun resize(newRows: Int, newCols: Int) {
        if (newRows <= 0 || newCols <= 0 || (newRows == rows && newCols == cols)) return
        val newCells = Array(newRows) { r ->
            Array(newCols) { c ->
                if (r < rows && c < cols) cells[r][c] else TerminalCell()
            }
        }
        // Preserve the bottom of the screen when shrinking.
        cells = newCells
        rows = newRows
        cols = newCols
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        revision++
    }

    fun clearAll() {
        scrollback.clear()
        clearScreen()
        cursorRow = 0
        cursorCol = 0
        revision++
    }

    /** Plain text snapshot, used for copy/paste and tests. */
    fun toPlainText(): String = buildString {
        for (r in 0 until rows) {
            appendLine(cells[r].joinToString("") { it.ch.toString() }.trimEnd())
        }
    }
}
