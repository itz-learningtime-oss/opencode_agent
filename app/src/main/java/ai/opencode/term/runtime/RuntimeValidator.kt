package ai.opencode.term.runtime

import android.content.Context
import android.os.Build
import android.system.Os

/**
 * Validates which runtime components can actually execute on this device.
 * Honest reporting only — never claims an embedded runtime exists when it does not.
 */
object RuntimeValidator {

    data class Report(
        val deviceAbi: String,
        val supportsArmeabiV7a: Boolean,
        val nativeLibDir: String,
        val embeddedRuntimeAvailable: Boolean,
        val message: String
    )

    fun validate(context: Context): Report {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val v7a = Build.SUPPORTED_ABIS.any { it == "armeabi-v7a" }

        // Android 10+ (targetSdk 29+): only nativeLibraryDir is executable for app files.
        val libDir = context.applicationInfo.nativeLibraryDir ?: ""

        // The manifest may declare an embedded runtime executable packaged as lib*.so;
        // probe for it. Absence is a normal, expected outcome on stock builds.
        val candidates = listOf("libopencode.so", "libnode.so")
        val found = candidates.any { name ->
            try {
                java.io.File(libDir, name).exists()
            } catch (e: Exception) {
                false
            }
        }

        val message = when {
            found -> "Embedded runtime detected in $libDir"
            v7a -> "No embedded OpenCode runtime for ${abi}. Use remote-server mode (Settings → Server URL)."
            else -> "Device ABI ${abi} is not supported by any known OpenCode runtime. Use remote-server mode."
        }

        return Report(
            deviceAbi = abi,
            supportsArmeabiV7a = v7a,
            nativeLibDir = libDir,
            embeddedRuntimeAvailable = found,
            message = message
        )
    }
}
