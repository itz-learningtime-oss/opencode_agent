package ai.opencode.term.terminal

/**
 * Translates Android key events / toolbar actions into terminal byte sequences.
 * Pure logic — unit-testable without Android.
 */
object TerminalKeyHandler {

    const val ESC = "\u001B"
    const val CR = "\r"
    const val BS = "\u007F" // DEL, what most modern shells expect from Backspace
    const val TAB = "\t"

    fun enter(): String = CR
    fun backspace(): String = BS
    fun tab(): String = TAB
    fun escape(): String = ESC

    fun arrowUp(): String = "$ESC[A"
    fun arrowDown(): String = "$ESC[B"
    fun arrowRight(): String = "$ESC[C"
    fun arrowLeft(): String = "$ESC[D"
    fun home(): String = "$ESC[H"
    fun end(): String = "$ESC[F"
    fun pageUp(): String = "$ESC[5~"
    fun pageDown(): String = "$ESC[6~"
    fun delete(): String = "$ESC[3~"
    fun insert(): String = "$ESC[2~"

    /** One-shot CTRL modifier applied to the next key. */
    fun ctrl(key: Char): String {
        val c = key.uppercaseChar()
        val code = when (c) {
            in 'A'..'Z' -> c - 'A' + 1
            ' ' -> 0
            '@' -> 0
            '[' -> 27
            '\\' -> 28
            ']' -> 29
            '^' -> 30
            '_' -> 31
            '?' -> 127
            else -> return key.toString()
        }
        return code.toChar().toString()
    }

    /** One-shot ALT modifier: ESC prefix. */
    fun alt(seq: String): String = ESC + seq
}
