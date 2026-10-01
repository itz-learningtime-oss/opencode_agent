package ai.opencode.term.runtime

import android.content.Context
import java.io.File

/**
 * Constructs the child-process environment from validated, existing directories.
 * Never hardcodes /data/data/<pkg>; everything derives from Context.
 */
object ProcessEnvironment {

    data class Env(val home: File, val path: String, val variables: Map<String, String>)

    fun build(context: Context, extra: Map<String, String> = emptyMap()): Env {
        val home = File(context.filesDir, "home").apply { mkdirs() }
        val tmp = File(context.cacheDir, "tmp").apply { mkdirs() }
        val bin = File(context.filesDir, "bin")

        val pathParts = buildList {
            if (bin.exists() && bin.isDirectory) add(bin.absolutePath)
            add("/system/bin")
            add("/system/xbin")
        }

        val vars = mutableMapOf(
            "HOME" to home.absolutePath,
            "TMPDIR" to tmp.absolutePath,
            "PATH" to pathParts.joinToString(":"),
            "TERM" to "xterm-256color",
            "LANG" to "C.UTF-8"
        )
        // Only include secrets if the caller explicitly passes them; callers are
        // responsible for never passing tokens when the runtime doesn't need them.
        extra.forEach { (k, v) -> vars[k] = v }

        return Env(home, vars["PATH"] ?: "/system/bin", vars)
    }

    fun homeDir(context: Context): File = File(context.filesDir, "home").apply { mkdirs() }
    fun workingDir(context: Context): File = File(context.filesDir, "workspace").apply { mkdirs() }
}
