package ai.opencode.term.runtime

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Extracts and verifies runtime assets declared in assets/runtime-manifest.json.
 * Writes atomically (temp file + rename), verifies SHA-256 and size, sets
 * executable permissions only where the manifest says so, and skips unchanged files.
 */
class AssetExtractor(private val context: Context) {

    @Serializable
    data class ManifestEntry(
        val path: String,
        val component: String,
        val version: String,
        val sha256: String,
        val size: Long = -1,
        val arch: String = "armeabi-v7a",
        val type: String = "file",
        val executable: Boolean = false,
        val license: String = "unknown"
    )

    @Serializable
    data class Manifest(
        val manifestVersion: Int = 1,
        val runtime: String = "none",
        val entries: List<ManifestEntry> = emptyList()
    )

    data class ExtractionReport(val installed: List<String>, val failed: List<String>, val skipped: List<String>)

    private val json = Json { ignoreUnknownKeys = true }
    private val runtimeDir: File get() = File(context.filesDir, "runtime")

    fun loadManifest(): Manifest? = runCatching {
        context.assets.open("runtime-manifest.json").bufferedReader().use { it.readText() }
    }.getOrNull()?.let { runCatching { json.decodeFromString<Manifest>(it) }.getOrNull() }

    fun targetDir(): File = runtimeDir

    /** Extracts all entries; returns a report. Never fabricates success for missing assets. */
    fun extractAll(): ExtractionReport {
        val manifest = loadManifest()
            ?: return ExtractionReport(emptyList(), listOf("runtime-manifest.json missing or unreadable"), emptyList())

        val installed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        for (entry in manifest.entries) {
            val out = File(runtimeDir, entry.path)
            if (out.exists() && matches(out, entry)) {
                skipped.add(entry.path)
                continue
            }
            val assetPath = "runtime/${entry.path}"
            val stream = runCatching { context.assets.open(assetPath) }.getOrNull()
            if (stream == null) {
                failed.add("${entry.path} (asset $assetPath not packaged)")
                continue
            }
            stream.use { input ->
                val tmp = File(out.parentFile, out.name + ".tmp")
                tmp.parentFile?.mkdirs()
                tmp.outputStream().use { output -> input.copyTo(output) }
                if (!matches(tmp, entry)) {
                    tmp.delete()
                    failed.add("${entry.path} (hash/size mismatch after extraction)")
                    return@use
                }
                if (out.exists()) out.delete()
                if (!tmp.renameTo(out)) {
                    failed.add("${entry.path} (rename failed)")
                } else {
                    if (entry.executable) out.setExecutable(true, false)
                    installed.add(entry.path)
                }
            }
        }
        return ExtractionReport(installed, failed, skipped)
    }

    private fun matches(file: File, entry: ManifestEntry): Boolean {
        if (!file.exists()) return false
        if (entry.size >= 0 && file.length() != entry.size) return false
        if (entry.sha256.isBlank()) return true
        return sha256(file) == entry.sha256
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } > 0) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
