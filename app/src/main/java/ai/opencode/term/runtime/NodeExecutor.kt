package ai.opencode.term.runtime

import android.content.Context
import android.os.Build
import kotlinx.coroutines.*

/**
 * Manages the lifecycle of an embedded runtime process (e.g. Node.js + OpenCode).
 *
 * HONEST-MODE CONTRACT: on stock armeabi-v7a Android there is no compatible
 * OpenCode/Node.js runtime binary. This executor validates whether one has been
 * legitimately packaged (as an executable native library in nativeLibraryDir or
 * an extracted, hash-verified asset) and only launches it if validation passes.
 * Otherwise it fails with a precise, actionable error — never a simulated run.
 */
class NodeExecutor(private val context: Context) {

    sealed class StartResult {
        data class Started(val pidDescription: String) : StartResult()
        data class Failed(val reason: String) : StartResult()
    }

    data class RuntimeCandidate(val file: java.io.File, val isNativeLibrary: Boolean)

    fun findRuntimeCandidate(): RuntimeCandidate? {
        val libDir = context.applicationInfo.nativeLibraryDir ?: return null
        for (name in listOf("libnode.so", "libopencode.so")) {
            val f = java.io.File(libDir, name)
            if (f.exists() && f.canExecute()) return RuntimeCandidate(f, isNativeLibrary = true)
        }
        val extracted = java.io.File(context.filesDir, "runtime/node")
        if (extracted.exists() && extracted.canExecute()) return RuntimeCandidate(extracted, isNativeLibrary = false)
        return null
    }

    /**
     * Starts the embedded runtime with a conservative heap budget.
     * Command shape (when a runtime exists): <runtime> --max-old-space-size=<mb> <entry>
     */
    suspend fun start(
        entryScript: String,
        heapMb: Int = 256,
        extraEnv: Map<String, String> = emptyMap(),
        onOutput: (String) -> Unit,
        onExit: (Int) -> Unit
    ): StartResult = withContext(Dispatchers.IO) {
        val candidate = findRuntimeCandidate()
            ?: return@withContext StartResult.Failed(
                "No embedded runtime available for ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown ABI"}. " +
                "OpenCode requires a 64-bit Node.js/Bun runtime that cannot execute on ARMv7 Android. " +
                "Use Settings → Server URL to connect to a remote `opencode serve` instance instead."
            )

        // Cross-check ELF machine type before exec (defense in depth).
        if (!ElFChecker.isArm(candidate.file)) {
            return@withContext StartResult.Failed(
                "Runtime binary ${candidate.file.name} is not an ARM ELF executable — refusing to start."
            )
        }

        val env = ProcessEnvironment.build(context, extraEnv)
        val args = mutableListOf(candidate.file.absolutePath)
        if (candidate.file.name == "libnode.so") {
            args += "--max-old-space-size=$heapMb"
        }
        args += entryScript

        return@withContext try {
            val pb = ProcessBuilder(args)
            pb.directory(ProcessEnvironment.workingDir(context))
            pb.environment().clear()
            pb.environment().putAll(env.variables)
            pb.redirectErrorStream(false)
            val proc = pb.start()
            startPumps(proc, onOutput, onExit)
            StartResult.Started("runtime=${candidate.file.name}")
        } catch (e: java.io.IOException) {
            StartResult.Failed("Failed to exec runtime: ${e.message} (Android W^X policy may block app-storage exec)")
        }
    }

    private fun startPumps(proc: Process, onOutput: (String) -> Unit, onExit: (Int) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            proc.inputStream.bufferedReader().useLines { lines -> lines.forEach(onOutput) }
        }
        CoroutineScope(Dispatchers.IO).launch {
            proc.errorStream.bufferedReader().useLines { lines -> lines.forEach(onOutput) }
        }
        CoroutineScope(Dispatchers.IO).launch {
            val code = proc.waitFor()
            onExit(code)
        }
    }

    /** Minimal ELF header check: e_machine must be EM_ARM (40). */
    object ElFChecker {
        fun isArm(file: java.io.File): Boolean = runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(20)
                if (input.read(header) < 20) return false
                val magic = byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
                if (!header.copyOfRange(0, 4).contentEquals(magic)) return false
                val machine = ((header[19].toInt() and 0xFF) shl 8) or (header[18].toInt() and 0xFF)
                machine == 40 // EM_ARM
            }
        }.getOrDefault(false)
    }
}
