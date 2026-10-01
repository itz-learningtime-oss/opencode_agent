package ai.opencode.term.opencode

import org.junit.Assert.*
import org.junit.Test

class OpenCodeClientTest {

    @Test
    fun `result Ok carries value`() {
        val r: OpenCodeClient.Result<Int> = OpenCodeClient.Result.Ok(42)
        assertEquals(42, (r as OpenCodeClient.Result.Ok).value)
    }

    @Test
    fun `result Err carries kind and message`() {
        val r: OpenCodeClient.Result<Int> = OpenCodeClient.Result.Err(OpenCodeClient.Result.Kind.AUTH, "nope")
        assertEquals(OpenCodeClient.Result.Kind.AUTH, r.kind)
        assertEquals("nope", r.message)
    }

    @Test
    fun `health data class defaults`() {
        val h = OpenCodeClient.Health()
        assertFalse(h.healthy)
        assertEquals("unknown", h.version)
    }

    @Test
    fun `session info parses minimal json`() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val s = json.decodeFromString<OpenCodeClient.SessionInfo>("""{"id":"abc","extra":1}""")
        assertEquals("abc", s.id)
        assertNull(s.title)
    }
}
