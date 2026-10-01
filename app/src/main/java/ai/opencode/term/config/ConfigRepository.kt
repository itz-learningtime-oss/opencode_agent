package ai.opencode.term.config

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Persists non-sensitive settings; the token lives in TokenStorage. */
class ConfigRepository(context: Context) {

    private val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    companion object {
        private const val KEY = "server_config"
        private const val KEY_FONT = "font_size_sp"
        private const val KEY_SCROLLBACK = "scrollback_lines"
    }

    fun loadServerConfig(): ServerConfig =
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString<ServerConfig>(it) }.getOrNull() }
            ?: ServerConfig()

    fun saveServerConfig(config: ServerConfig) {
        prefs.edit().putString(KEY, json.encodeToString(config)).apply()
    }

    fun clearServerConfig() {
        prefs.edit().remove(KEY).apply()
    }

    var fontSizeSp: Float
        get() = prefs.getFloat(KEY_FONT, 13f)
        set(value) = prefs.edit().putFloat(KEY_FONT, value).apply()

    var scrollbackLines: Int
        get() = prefs.getInt(KEY_SCROLLBACK, 200)
        set(value) = prefs.edit().putInt(KEY_SCROLLBACK, value.coerceIn(50, 2000)).apply()
}
