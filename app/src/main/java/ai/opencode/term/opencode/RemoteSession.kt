package ai.opencode.term.opencode

import ai.opencode.term.config.ServerConfig
import ai.opencode.term.security.TokenStorage
import ai.opencode.term.terminal.TerminalBuffer
import ai.opencode.term.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Renders an OpenCode remote server conversation into the terminal buffer.
 * This is not a PTY — it draws real server responses as plain session output.
 */
class RemoteSession(
    private val scope: CoroutineScope,
    private val client: OpenCodeClient,
    private val tokenStorage: TokenStorage,
    private val config: ServerConfig,
    maxScrollback: Int = 200
) : TerminalSession {

    override val buffer = TerminalBuffer(maxScrollback = maxScrollback)
    private val parser = ai.opencode.term.terminal.AnsiParser(buffer)
    private val running = AtomicBoolean(false)
    private var currentSessionId: String? = null
    private var lineBuf = StringBuilder()

    override var onOutput: (() -> Unit)? = null
    override var onExit: ((Int) -> Unit)? = null
    override val isRunning: Boolean get() = running.get()

    private var pendingJob: Job? = null

    override fun start(cols: Int, rows: Int) {
        if (running.getAndSet(true)) return
        buffer.resize(rows, cols)
        printBanner()
        running.set(false) // "running" here means the UI stays interactive; no process to keep alive
        onOutput?.invoke()
    }

    private fun printBanner() {
        val host = config.baseUrl
        feed("OpenCode remote client\r\nServer: $host\r\nType a prompt and press Enter.\r\n\r\n")
    }

    /** Feed raw text (already CR/LF formatted) into the buffer via the ANSI parser. */
    fun feed(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        synchronized(parser) { parser.feed(bytes, bytes.size) }
        onOutput?.invoke()
    }

    override fun write(data: String) {
        // Accumulate the user's typed line; Enter submits.
        data.forEach { ch ->
            when {
                ch == '\r' || ch == '\n' -> submitLine()
                ch == '\u007F' -> { if (lineBuf.isNotEmpty()) lineBuf.deleteCharAt(lineBuf.length - 1) }
                else -> lineBuf.append(ch)
            }
        }
    }

    private fun submitLine() {
        val line = lineBuf.toString()
        lineBuf.clear()
        feed("\r\n")
        if (line.isBlank()) return
        pendingJob = scope.launch(Dispatchers.IO) {
            val sid = currentSessionId ?: run {
                when (val r = client.createSession()) {
                    is OpenCodeClient.Result.Ok -> { currentSessionId = r.value.id; r.value.id }
                    is OpenCodeClient.Result.Err -> {
                        feed("Error: ${r.message}\r\n")
                        return@launch
                    }
                }
            }
            feed("…thinking\r\n")
            when (val r = client.sendMessage(sid, line)) {
                is OpenCodeClient.Result.Ok -> feed("${r.value}\r\n\r\n")
                is OpenCodeClient.Result.Err -> feed("Error (${r.kind}): ${r.message}\r\n\r\n")
            }
        }
    }

    override fun resize(cols: Int, rows: Int) = buffer.resize(rows, cols)
    override fun sendSignal(sig: Int) { /* no process to signal */ }

    override fun stop() {
        pendingJob?.cancel()
        running.set(false)
    }
}
