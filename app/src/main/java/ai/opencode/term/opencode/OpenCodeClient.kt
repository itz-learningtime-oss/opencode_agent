package ai.opencode.term.opencode

import ai.opencode.term.config.ServerConfig
import ai.opencode.term.security.TokenStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

/**
 * Client for the documented OpenCode server HTTP API (opencode.ai/docs/server):
 *   GET  /global/health        -> { healthy, version }
 *   GET  /project/current      -> Project
 *   POST /session              -> create session
 *   GET  /session              -> list sessions
 *   POST /session/:id/message  -> send prompt, wait for reply
 *   GET  /event                -> SSE stream
 * Auth: HTTP Basic (username defaults to "opencode"), matching OPENCODE_SERVER_PASSWORD.
 */
class OpenCodeClient(
    private val config: ServerConfig,
    private val tokenStorage: TokenStorage
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Health(val healthy: Boolean = false, val version: String = "unknown")

    @Serializable
    data class SessionInfo(val id: String, val title: String? = null)

    @Serializable
    data class MessagePart(val type: String = "text", val text: String = "")

    @Serializable
    data class TextPart(val type: String = "text", val text: String)

    sealed class Result<out T> {
        data class Ok<T>(val value: T) : Result<T>()
        data class Err(val kind: Kind, val message: String) : Result<Nothing>()
        enum class Kind { TIMEOUT, AUTH, NETWORK, PROTOCOL, UNSUPPORTED }
    }

    private fun openConnection(path: String): HttpURLConnection {
        val base = config.baseUrl.trimEnd('/')
        val url = URL("$base$path")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = config.connectTimeoutMs
        conn.readTimeout = config.readTimeoutMs
        conn.setRequestProperty("Accept", "application/json")
        tokenStorage.loadToken()?.let { token ->
            val user = config.username.ifBlank { "opencode" }
            val cred = android.util.Base64.encodeToString("$user:$token".toByteArray(), android.util.Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $cred")
        }
        return conn
    }

    /** Real health check against /global/health. Never fakes success. */
    suspend fun checkHealth(): Result<Health> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = openConnection("/global/health")
            val code = conn.responseCode
            when {
                code == 401 || code == 403 -> Result.Err(Result.Kind.AUTH, "Server rejected credentials (HTTP $code)")
                code in 200..299 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val h = runCatching { json.decodeFromString<Health>(body) }.getOrDefault(Health())
                    if (h.healthy) Result.Ok(h) else Result.Err(Result.Kind.PROTOCOL, "Server reported unhealthy")
                }
                else -> Result.Err(Result.Kind.PROTOCOL, "Unexpected HTTP status $code")
            }
        } catch (e: java.net.SocketTimeoutException) {
            Result.Err(Result.Kind.TIMEOUT, "Connection timed out")
        } catch (e: java.net.UnknownHostException) {
            Result.Err(Result.Kind.NETWORK, "Host not found: ${config.baseUrl}")
        } catch (e: java.io.IOException) {
            Result.Err(Result.Kind.NETWORK, "Network error: ${e.message ?: "connection failed"}")
        } catch (e: Exception) {
            Result.Err(Result.Kind.PROTOCOL, "Unexpected error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun listSessions(): Result<List<SessionInfo>> = withContext(Dispatchers.IO) {
        requestJson("/session") { body ->
            json.decodeFromString<List<SessionInfo>>(body)
        }
    }

    suspend fun createSession(title: String = "Terminal"): Result<SessionInfo> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = openConnection("/session")
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write("""{"title":"$title"}""".toByteArray()) }
            handleResponse(conn) { body -> json.decodeFromString<SessionInfo>(body) }
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Sends a prompt to a session and returns the assistant text parts.
     * POST /session/:id/message — not retried automatically (side effects).
     */
    suspend fun sendMessage(sessionId: String, text: String): Result<String> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = openConnection("/session/$sessionId/message")
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = """{"parts":[{"type":"text","text":${json.encodeToString(kotlinx.serialization.serializer<String>(), text)}}]}"""
            conn.outputStream.use { it.write(payload.toByteArray()) }
            handleResponse(conn) { body ->
                // Response contains { info, parts }; extract text parts.
                val root = json.parseToJsonElement(body)
                val parts = root.jsonObject["parts"]?.jsonArray
                parts.orEmpty()
                    .mapNotNull { p ->
                        val obj = p.jsonObject
                        if (obj["type"]?.jsonPrimitive?.contentOrNull == "text")
                            obj["text"]?.jsonPrimitive?.contentOrNull else null
                    }
                    .joinToString("\n")
            }
        } finally {
            conn?.disconnect()
        }
    }

    // ---------------- SSE events ----------------

    private val eventFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val events: SharedFlow<String> get() = eventFlow
    private val sseActive = AtomicBoolean(false)
    private var sseJob: Job? = null

    fun startEvents(scope: CoroutineScope) {
        if (sseActive.getAndSet(true)) return
        sseJob = scope.launch(Dispatchers.IO) {
            while (isActive && sseActive.get()) {
                try {
                    val conn = openConnection("/event")
                    conn.readTimeout = 0 // stream indefinitely
                    conn.inputStream.bufferedReader().use { reader ->
                        val sb = StringBuilder()
                        while (sseActive.get()) {
                            val line = reader.readLine() ?: break
                            when {
                                line.startsWith("data:") -> sb.appendLine(line.removePrefix("data:").trim())
                                line.isEmpty() && sb.isNotEmpty() -> {
                                    eventFlow.emit(sb.toString())
                                    sb.clear()
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Back off and retry — server disconnects are expected.
                }
                if (sseActive.get()) delay(3000)
            }
        }
    }

    fun stopEvents() {
        sseActive.set(false)
        sseJob?.cancel()
        sseJob = null
    }

    // ---------------- helpers ----------------

    private fun <T> handleResponse(conn: HttpURLConnection, parse: (String) -> T): Result<T> {
        val code = conn.responseCode
        return when {
            code == 401 || code == 403 -> Result.Err(Result.Kind.AUTH, "Invalid credentials (HTTP $code)")
            code in 200..299 -> {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                runCatching { parse(body) }.fold(
                    onSuccess = { Result.Ok(it) },
                    onFailure = { Result.Err(Result.Kind.PROTOCOL, "Bad response body: ${it.message}") }
                )
            }
            else -> {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                Result.Err(Result.Kind.PROTOCOL, "HTTP $code${err?.take(200)?.let { ": $it" } ?: ""}")
            }
        }
    }

    private suspend fun <T> requestJson(path: String, parse: (String) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = openConnection(path)
                handleResponse(conn, parse)
            } catch (e: java.net.SocketTimeoutException) {
                Result.Err(Result.Kind.TIMEOUT, "Request timed out")
            } catch (e: java.io.IOException) {
                Result.Err(Result.Kind.NETWORK, "Network error: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }

    @Suppress("UNCHECKED_CAST")
    private val kotlinx.serialization.json.JsonElement.jsonObject: kotlinx.serialization.json.JsonObject
        get() = this as kotlinx.serialization.json.JsonObject
    @Suppress("UNCHECKED_CAST")
    private val kotlinx.serialization.json.JsonElement.jsonArray: kotlinx.serialization.json.JsonArray
        get() = this as kotlinx.serialization.json.JsonArray
    private val kotlinx.serialization.json.JsonElement.jsonPrimitive: kotlinx.serialization.json.JsonPrimitive
        get() = this as kotlinx.serialization.json.JsonPrimitive
    private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
        get() = if (this is kotlinx.serialization.json.JsonNull) null else content
}
