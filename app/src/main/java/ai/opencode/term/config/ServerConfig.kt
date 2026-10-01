package ai.opencode.term.config

import kotlinx.serialization.Serializable

@Serializable
data class ServerConfig(
    val baseUrl: String = "",
    val username: String = "",
    /** Whether the user explicitly accepted cleartext HTTP for this URL. */
    val allowHttp: Boolean = false,
    val connectTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 30_000
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()

    companion object {
        private val URL_REGEX = Regex("^https?://[A-Za-z0-9._\\-\\[\\]:]+(:\\d{1,5})?(/[A-Za-z0-9._~\\-/]*)?$")

        /** Returns null if valid, else a human-readable reason. */
        fun validate(url: String, allowHttp: Boolean): String? {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return "URL is empty"
            if (!URL_REGEX.matches(trimmed)) return "Malformed URL"
            if (trimmed.startsWith("http://") && !allowHttp) {
                return "Insecure HTTP not allowed without explicit opt-in"
            }
            return null
        }
    }
}
