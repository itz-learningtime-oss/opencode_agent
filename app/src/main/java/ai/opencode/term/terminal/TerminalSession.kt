package ai.opencode.term.terminal

import ai.opencode.term.native.NativeTerminal
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** A terminal session feeds output into a TerminalBuffer and accepts input bytes. */
interface TerminalSession {
    val buffer: TerminalBuffer
    val isRunning: Boolean
    fun start(cols: Int, rows: Int)
    fun write(data: String)
    fun resize(cols: Int, rows: Int)
    fun sendSignal(sig: Int)
    fun stop()
    /** Invoked on the caller's dispatcher whenever new output was parsed. */
    var onOutput: (() -> Unit)?
    /** Invoked once when the session ends; receives the exit code. */
    var onExit: ((Int) -> Unit)?
}

/**
 * Real local shell session: a genuine child process attached to a PTY through JNI.
 */
class NativeShellSession(
    private val scope: CoroutineScope,
    private val shellPath: String = "/system/bin/sh",
    private val workingDir: String,
    private val environment: Map<String, String> = emptyMap(),
    maxScrollback: Int = 200
) : TerminalSession {

    override val buffer = TerminalBuffer(maxScrollback = maxScrollback)
    private var handle: Long = 0
    private val running = AtomicBoolean(false)
    private var readerJob: Job? = null

    override var onOutput: (() -> Unit)? = null
    override var onExit: ((Int) -> Unit)? = null

    override val isRunning: Boolean get() = running.get()

    private val parser = AnsiParser(buffer)

    override fun start(cols: Int, rows: Int) {
        check(!running.get()) { "Session already started" }
        buffer.resize(rows, cols)
        handle = NativeTerminal.nativeCreateSession()
        if (handle == 0L) {
            onExit?.invoke(-1)
            return
        }
        val env = buildList {
            add("TERM=xterm-256color")
            add("HOME=$workingDir")
            add("PATH=/system/bin:/system/xbin")
            add("TMPDIR=${workingDir}/tmp")
            environment.forEach { (k, v) -> add("$k=$v") }
        }
        val rc = NativeTerminal.nativeStart(handle, shellPath, workingDir, arrayOf("sh"), env.toTypedArray())
        if (rc != 0) {
            NativeTerminal.nativeCloseSession(handle)
            handle = 0
            onExit?.invoke(rc)
            return
        }
        running.set(true)
        buffer.resize(rows, cols)
        readerJob = scope.launch(Dispatchers.IO) {
            val buf = ByteArray(8192)
            while (isActive && running.get()) {
                val n = NativeTerminal.nativeRead(handle, buf)
                when {
                    n > 0 -> {
                        synchronized(parser) { parser.feed(buf, n) }
                        onOutput?.invoke()
                    }
                    n == 0 -> break // EOF
                    else -> break   // error
                }
            }
            val code = NativeTerminal.nativeWaitFor(handle, 5000)
            running.set(false)
            withContext(Dispatchers.Main) { onExit?.invoke(code) }
        }
    }

    override fun write(data: String) {
        if (!running.get() || handle == 0L) return
        val bytes = data.toByteArray(Charsets.UTF_8)
        NativeTerminal.nativeWrite(handle, bytes, bytes.size)
    }

    override fun resize(cols: Int, rows: Int) {
        buffer.resize(rows, cols)
        if (handle != 0L) NativeTerminal.nativeResize(handle, rows, cols)
    }

    override fun sendSignal(sig: Int) {
        if (handle != 0L) NativeTerminal.nativeSendSignal(handle, sig)
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)
        if (handle != 0L) {
            NativeTerminal.nativeSendSignal(handle, NativeTerminal.SIGHUP)
            NativeTerminal.nativeWaitFor(handle, 1000)
            NativeTerminal.nativeCloseSession(handle)
            handle = 0
        }
        readerJob?.cancel()
    }
}
