package org.hello.reader.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {

    private fun valid(input: String) = (ServerUrl.normalize(input) as ServerUrl.Result.Valid).baseUrl

    @Test
    fun `adds https when scheme is missing`() {
        assertEquals("https://storyteller.example.com", valid("storyteller.example.com"))
    }

    @Test
    fun `keeps explicit http and non-default port`() {
        assertEquals("http://192.168.1.20:8001", valid("http://192.168.1.20:8001"))
    }

    @Test
    fun `drops default port, lowercases host and scheme, trims trailing slashes`() {
        assertEquals("https://storyteller.example.com", valid("  HTTPS://Storyteller.Example.com:443///  "))
    }

    @Test
    fun `preserves sub-path for reverse-proxied servers`() {
        assertEquals("https://example.com/apps/storyteller", valid("https://example.com/apps/storyteller/"))
    }

    @Test
    fun `rejects blank, unsupported scheme, query, credentials and spaces`() {
        listOf(
            "",
            "   ",
            "ftp://example.com",
            "https://example.com/?a=b",
            "https://example.com/#frag",
            "https://user:pw@example.com",
            "https://exa mple.com",
            "https://",
        ).forEach { input ->
            assertTrue("expected invalid: '$input'", ServerUrl.normalize(input) is ServerUrl.Result.Invalid)
        }
    }

    @Test
    fun `detects cleartext`() {
        assertTrue(ServerUrl.isCleartext("http://nas.local"))
        assertFalse(ServerUrl.isCleartext("https://nas.local"))
    }
}
