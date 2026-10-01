package ai.opencode.term.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlin.math.ceil
import kotlin.math.max

/**
 * Lightweight Canvas-rendered terminal for low-end ARMv7 devices. No WebView.
 * Renders from TerminalBuffer and only invalidates when the buffer revision changed.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var session: TerminalSession? = null
        set(value) {
            field?.onOutput = null
            field = value
            value?.onOutput = { postInvalidateOnAnimation() }
            scrollOffset = 0
            invalidate()
        }

    var fontSizeSp = 13f
        set(value) {
            field = value.coerceIn(8f, 32f)
            textPaint.textSize = spToPx(field)
            invalidateDimensions()
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = spToPx(fontSizeSp)
    }
    private val cursorPaint = Paint().apply { color = 0xFF3B82F6.toInt() }
    private val bgPaint = Paint().apply { color = 0xFF101418.toInt() }
    private val cellBgPaint = Paint()

    private var cellWidth = 1f
    private var cellHeight = 1f

    private var scrollOffset = 0 // lines from bottom (0 = live)

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollOffset = (scrollOffset + (dy / cellHeight).toInt()).coerceAtLeast(0)
            invalidate()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            requestFocus()
            showSoftInput()
            return true
        }
    })

    private val inputConnection = TerminalInputConnection()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        measureCells()
    }

    private fun spToPx(sp: Float) = sp * resources.displayMetrics.scaledDensity

    private fun measureCells() {
        val fm = textPaint.fontMetrics
        cellHeight = ceil(fm.descent - fm.ascent)
        cellWidth = textPaint.measureText("M")
    }

    private fun invalidateDimensions() {
        measureCells()
        requestLayout()
        invalidate()
    }

    fun cols(): Int = max(8, (width / cellWidth).toInt())
    fun rows(): Int = max(4, (height / cellHeight).toInt())

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        session?.resize(cols(), rows())
    }

    override fun onDraw(canvas: Canvas) {
        val s = session ?: return
        val buf = s.buffer
        val totalLines = buf.scrollbackCount() + buf.rows

        canvas.drawPaint(bgPaint)

        val visibleRows = rows()
        val firstLine = (totalLines - visibleRows - scrollOffset).coerceAtLeast(0)

        for (vr in 0 until visibleRows) {
            val line = buf.lineAt(firstLine + vr) ?: continue
            val y = vr * cellHeight - textPaint.fontMetrics.ascent
            for (c in line.indices) {
                val cell = line[c]
                val x = c * cellWidth
                // Background
                cell.attr.bg?.let { bg ->
                    cellBgPaint.color = colorFor(bg, defaultBg = false)
                    canvas.drawRect(x, y + textPaint.fontMetrics.ascent, x + cellWidth, y + textPaint.fontMetrics.descent, cellBgPaint)
                }
                if (cell.ch != ' ') {
                    textPaint.color = colorFor(cell.attr.fg, defaultBg = true)
                    textPaint.isFakeBoldText = cell.attr.bold
                    canvas.drawText(cell.ch.toString(), x, y, textPaint)
                }
            }
        }

        // Cursor (only when viewing live output)
        if (scrollOffset == 0) {
            val cx = buf.cursorCol * cellWidth
            val cy = (totalLines - visibleRows).coerceAtLeast(0)
            val rowOnScreen = buf.cursorRow + cy - firstLine
            if (rowOnScreen in 0 until visibleRows) {
                canvas.drawRect(
                    cx, rowOnScreen * cellHeight,
                    cx + cellWidth, (rowOnScreen + 1) * cellHeight,
                    cursorPaint
                )
            }
        }
    }

    private fun colorFor(colorIndex: Int?, defaultBg: Boolean): Int {
        if (colorIndex == null) return if (defaultBg) 0xFFD8DEE9.toInt() else 0xFF101418.toInt()
        return when {
            colorIndex < 16 -> BASE_COLORS[colorIndex]
            colorIndex in 256..(256 + 65536) -> colorIndex - 256 // packed truecolor
            else -> 0xFFD8DEE9.toInt()
        }
    }

    // ---------------- Input ----------------

    fun sendToSession(data: String) {
        session?.write(data)
        scrollOffset = 0
        invalidate()
    }

    fun sendKey(seq: String) = sendToSession(seq)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        outAttrs.inputType = EditorInfo.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return inputConnection
    }

    private fun showSoftInput() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(this, 0)
    }

    fun hideSoftInput() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val seq = hardKeySequence(keyCode, event)
        if (seq != null) {
            sendToSession(seq)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private inner class TerminalInputConnection : BaseInputConnection(this, true) {
        private val composing = StringBuilder()

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            composing.append(text)
            val text = composing.toString()
            composing.clear()
            // IME commit may contain multiple chars; also intercept Enter.
            text.forEach { ch ->
                when (ch) {
                    '\n' -> sendToSession(TerminalKeyHandler.enter())
                    else -> sendToSession(ch.toString())
                }
            }
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength) { sendToSession(TerminalKeyHandler.backspace()) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val seq = hardKeySequence(event.keyCode, event)
                if (seq != null) {
                    sendToSession(seq)
                    return true
                }
            }
            return super.sendKeyEvent(event)
        }

        override fun performEditorAction(actionCode: Int): Boolean {
            sendToSession(TerminalKeyHandler.enter())
            return true
        }
    }

    private fun hardKeySequence(keyCode: Int, event: KeyEvent): String? {
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed || event.isSymPressed
        val base: String? = when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> TerminalKeyHandler.enter()
            KeyEvent.KEYCODE_DEL -> TerminalKeyHandler.backspace()
            KeyEvent.KEYCODE_FORWARD_DEL -> TerminalKeyHandler.delete()
            KeyEvent.KEYCODE_TAB -> TerminalKeyHandler.tab()
            KeyEvent.KEYCODE_ESCAPE -> TerminalKeyHandler.escape()
            KeyEvent.KEYCODE_DPAD_UP -> TerminalKeyHandler.arrowUp()
            KeyEvent.KEYCODE_DPAD_DOWN -> TerminalKeyHandler.arrowDown()
            KeyEvent.KEYCODE_DPAD_LEFT -> TerminalKeyHandler.arrowLeft()
            KeyEvent.KEYCODE_DPAD_RIGHT -> TerminalKeyHandler.arrowRight()
            KeyEvent.KEYCODE_MOVE_HOME -> TerminalKeyHandler.home()
            KeyEvent.KEYCODE_MOVE_END -> TerminalKeyHandler.end()
            KeyEvent.KEYCODE_PAGE_UP -> TerminalKeyHandler.pageUp()
            KeyEvent.KEYCODE_PAGE_DOWN -> TerminalKeyHandler.pageDown()
            else -> null
        }
        if (base != null) return if (alt) TerminalKeyHandler.alt(base) else base

        // Ctrl+letter from hardware keyboard
        if (ctrl) {
            val ch = event.unicodeChar.toChar()
            if (ch.isLetter()) return TerminalKeyHandler.ctrl(ch)
        }
        if (!ctrl && !alt && event.unicodeChar >= 32) {
            return event.unicodeChar.toChar().toString()
        }
        return null
    }

    companion object {
        private val BASE_COLORS = intArrayOf(
            0xFF000000.toInt(), 0xFFCD3131.toInt(), 0xFF0DBC79.toInt(), 0xFFE5E510.toInt(),
            0xFF2472C8.toInt(), 0xFFBC3FBC.toInt(), 0xFF11A8CD.toInt(), 0xFFE5E5E5.toInt(),
            0xFF666666.toInt(), 0xFFF14C4C.toInt(), 0xFF23D18B.toInt(), 0xFFF5F543.toInt(),
            0xFF3B8EEA.toInt(), 0xFFD670D6.toInt(), 0xFF29B8DB.toInt(), 0xFFFFFFFF.toInt()
        )
    }
}
