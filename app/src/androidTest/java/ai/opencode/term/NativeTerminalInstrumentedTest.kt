package ai.opencode.term

import ai.opencode.term.native.NativeTerminal
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeTerminalInstrumentedTest {

    @Test
    fun sessionStartEchoExit() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val h = NativeTerminal.nativeCreateSession()
        assertTrue(h != 0L)
        try {
            val rc = NativeTerminal.nativeStart(
                h, "/system/bin/sh", ctx.cacheDir.absolutePath,
                arrayOf("sh"), arrayOf("TERM=dumb")
            )
            assertEquals("nativeStart failed rc=$rc", 0, rc)

            // Write a known command and read until we see its output or time out.
            NativeTerminal.nativeWrite(h, "echo oct_9c\n".toByteArray(), 12)
            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + 5000
            var collected = StringBuilder()
            while (System.currentTimeMillis() < deadline && !collected.contains("oct_9c")) {
                val n = NativeTerminal.nativeRead(h, buf)
                if (n > 0) collected.append(String(buf, 0, n))
            }
            assertTrue("expected echo output, got: $collected", collected.contains("oct_9c"))

            NativeTerminal.nativeSendSignal(h, NativeTerminal.SIGTERM)
            val code = NativeTerminal.nativeWaitFor(h, 5000)
            assertTrue("exit code $code", code >= 0)
        } finally {
            NativeTerminal.nativeCloseSession(h)
        }
    }

    @Test
    fun resizeDoesNotCrash() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val h = NativeTerminal.nativeCreateSession()
        assertEquals(0, NativeTerminal.nativeStart(h, "/system/bin/sh", ctx.cacheDir.absolutePath, arrayOf("sh"), arrayOf()))
        assertEquals(0, NativeTerminal.nativeResize(h, 40, 120))
        NativeTerminal.nativeCloseSession(h)
    }

    @Test
    fun doubleCloseIsSafe() {
        val h = NativeTerminal.nativeCreateSession()
        NativeTerminal.nativeCloseSession(h)
        NativeTerminal.nativeCloseSession(h) // must not crash / double-free
    }
}
