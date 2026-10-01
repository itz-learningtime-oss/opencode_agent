package ai.opencode.term.config

import org.junit.Assert.*
import org.junit.Test

class ServerConfigTest {

    @Test
    fun `valid https url accepted`() {
        assertNull(ServerConfig.validate("https://example.com:4096", allowHttp = false))
        assertNull(ServerConfig.validate("https://192.168.1.10", allowHttp = false))
    }

    @Test
    fun `http rejected without opt-in`() {
        assertNotNull(ServerConfig.validate("http://example.com:4096", allowHttp = false))
    }

    @Test
    fun `http accepted with explicit opt-in`() {
        assertNull(ServerConfig.validate("http://192.168.1.10:4096", allowHttp = true))
    }

    @Test
    fun `empty url rejected`() {
        assertNotNull(ServerConfig.validate("", allowHttp = false))
        assertNotNull(ServerConfig.validate("   ", allowHttp = false))
    }

    @Test
    fun `malformed urls rejected`() {
        assertNotNull(ServerConfig.validate("example.com", allowHttp = false))
        assertNotNull(ServerConfig.validate("ftp://example.com", allowHttp = false))
        assertNotNull(ServerConfig.validate("https://exa mple.com", allowHttp = false))
    }

    @Test
    fun `isConfigured requires non-blank url`() {
        assertFalse(ServerConfig(baseUrl = "").isConfigured)
        assertTrue(ServerConfig(baseUrl = "https://x").isConfigured)
    }
}
