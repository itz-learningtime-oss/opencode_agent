package ai.opencode.term.native

/**
 * Kotlin side of the JNI contract implemented in NativeTerminalJNI.c.
 * Loaded once; all methods are static and operate on an opaque session handle.
 */
object NativeTerminal {
    init {
        System.loadLibrary("opencode_term")
    }

    @JvmStatic external fun nativeCreateSession(): Long
    @JvmStatic external fun nativeStart(handle: Long, shell: String, cwd: String, cmd: Array<String>, env: Array<String>): Int
    @JvmStatic external fun nativeRead(handle: Long, buf: ByteArray): Int
    @JvmStatic external fun nativeWrite(handle: Long, buf: ByteArray, len: Int): Int
    @JvmStatic external fun nativeResize(handle: Long, rows: Int, cols: Int): Int
    @JvmStatic external fun nativeSendSignal(handle: Long, sig: Int)
    @JvmStatic external fun nativeWaitFor(handle: Long, timeoutMs: Int): Int
    @JvmStatic external fun nativeCloseSession(handle: Long)

    const val SIGINT = 2
    const val SIGTERM = 15
    const val SIGHUP = 1
}
