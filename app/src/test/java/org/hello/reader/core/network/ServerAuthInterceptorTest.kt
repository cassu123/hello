package org.hello.reader.core.network

import org.hello.reader.core.model.ServerProfile
import org.hello.reader.testing.profile
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ServerAuthInterceptorTest {

    private lateinit var server: MockWebServer
    private var active: ServerProfile? = null
    private val expired = mutableListOf<String>()
    private lateinit var client: OkHttpClient

    private val placeholder = ServerAuthInterceptor.PLACEHOLDER_BASE_URL

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = OkHttpClient.Builder()
            .addInterceptor(ServerAuthInterceptor({ active }) { expired += it })
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun serverBase(path: String = "") = server.url(path).toString().trimEnd('/')

    private fun execute(request: Request): Int = client.newCall(request).execute().use { it.code }

    @Test
    fun `rewrites placeholder host to active profile and injects bearer token`() {
        active = profile("a", baseUrl = serverBase(), token = "tok-a")
        server.enqueue(MockResponse())

        execute(Request.Builder().url("${placeholder}api/books?page=2").build())

        val recorded = server.takeRequest()
        assertEquals("/api/books?page=2", recorded.path)
        assertEquals("Bearer tok-a", recorded.getHeader("Authorization"))
    }

    @Test
    fun `prepends profile sub-path`() {
        active = profile("a", baseUrl = serverBase("/apps/storyteller"))
        server.enqueue(MockResponse())

        execute(Request.Builder().url("${placeholder}api/health").build())

        assertEquals("/apps/storyteller/api/health", server.takeRequest().path)
    }

    @Test
    fun `switching active profile reroutes without rebuilding the client`() {
        val other = MockWebServer().apply { start() }
        try {
            active = profile("a", baseUrl = serverBase(), token = "tok-a")
            server.enqueue(MockResponse())
            execute(Request.Builder().url("${placeholder}api/books").build())

            active = profile("b", baseUrl = other.url("/").toString().trimEnd('/'), token = "tok-b")
            other.enqueue(MockResponse())
            execute(Request.Builder().url("${placeholder}api/books").build())

            assertEquals("Bearer tok-a", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer tok-b", other.takeRequest().getHeader("Authorization"))
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `no-auth header suppresses token and is stripped`() {
        active = profile("a", baseUrl = serverBase())
        server.enqueue(MockResponse())

        execute(
            Request.Builder().url("${placeholder}api/health")
                .header(ServerAuthInterceptor.HEADER_NO_AUTH, "true").build(),
        )

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
        assertNull(recorded.getHeader(ServerAuthInterceptor.HEADER_NO_AUTH))
    }

    @Test
    fun `explicit target routes there and never receives the active token`() {
        val activeServer = MockWebServer().apply { start() }
        try {
            active = profile("a", baseUrl = activeServer.url("/").toString().trimEnd('/'), token = "tok-a")
            server.enqueue(MockResponse())

            execute(
                Request.Builder().url("${placeholder}api/user/me")
                    .header(ServerAuthInterceptor.HEADER_TARGET_BASE_URL, serverBase("/st"))
                    .build(),
            )

            val recorded = server.takeRequest()
            assertEquals("/st/api/user/me", recorded.path)
            assertNull(recorded.getHeader("Authorization"))
            assertNull(recorded.getHeader(ServerAuthInterceptor.HEADER_TARGET_BASE_URL))
            assertEquals(0, activeServer.requestCount)
        } finally {
            activeServer.shutdown()
        }
    }

    @Test
    fun `explicit Authorization header is preserved`() {
        active = profile("a", baseUrl = serverBase(), token = "tok-a")
        server.enqueue(MockResponse())

        execute(
            Request.Builder().url("${placeholder}api/user/me")
                .header(ServerAuthInterceptor.HEADER_TARGET_BASE_URL, serverBase())
                .header("Authorization", "Bearer from-qr").build(),
        )

        assertEquals("Bearer from-qr", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `absolute url on a different origin does not receive the token`() {
        val otherHost = MockWebServer().apply { start() }
        try {
            active = profile("a", baseUrl = serverBase(), token = "tok-a")
            otherHost.enqueue(MockResponse())

            execute(Request.Builder().url(otherHost.url("/cover.jpg")).build())

            assertNull(otherHost.takeRequest().getHeader("Authorization"))
        } finally {
            otherHost.shutdown()
        }
    }

    @Test
    fun `absolute url on the active origin receives the token`() {
        active = profile("a", baseUrl = serverBase(), token = "tok-a")
        server.enqueue(MockResponse())

        execute(Request.Builder().url(server.url("/api/books/1/cover")).build())

        assertEquals("Bearer tok-a", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `absolute url on same origin outside server sub-path does not receive token`() {
        active = profile("a", baseUrl = serverBase("/apps/storyteller"), token = "tok-a")
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())

        execute(Request.Builder().url(server.url("/other-service/secret")).build())
        execute(Request.Builder().url(server.url("/apps/storyteller/api/books/1/cover")).build())

        assertNull(server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer tok-a", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `401 on authenticated request reports expiry for active profile`() {
        active = profile("a", baseUrl = serverBase())
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(401, execute(Request.Builder().url("${placeholder}api/books").build()))
        assertEquals(listOf("a"), expired)
    }

    @Test
    fun `401 on unauthenticated or explicit-target request does not report expiry`() {
        active = profile("a", baseUrl = serverBase())
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))

        execute(
            Request.Builder().url("${placeholder}api/token")
                .header(ServerAuthInterceptor.HEADER_NO_AUTH, "true").build(),
        )
        execute(
            Request.Builder().url("${placeholder}api/user/me")
                .header(ServerAuthInterceptor.HEADER_TARGET_BASE_URL, serverBase())
                .header("Authorization", "Bearer bad").build(),
        )

        assertTrue(expired.isEmpty())
    }

    @Test
    fun `placeholder request without any server fails fast`() {
        active = null
        try {
            execute(Request.Builder().url("${placeholder}api/books").build())
            fail("expected NoActiveServerException")
        } catch (e: NoActiveServerException) {
            // expected
        }
    }
}
