package org.hello.reader.core.data

import org.hello.reader.core.network.NetworkModule
import org.hello.reader.core.network.ServerAuthInterceptor
import org.hello.reader.core.security.SecurityVaultImpl
import org.hello.reader.testing.InMemorySharedPreferences
import org.hello.reader.testing.profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ServerConnectionRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var vault: SecurityVaultImpl
    private lateinit var repository: ServerConnectionRepository
    private var nextId = 0

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        vault = SecurityVaultImpl(InMemorySharedPreferences(), Dispatchers.Unconfined)
        val client = NetworkModule.okHttpClient(
            ServerAuthInterceptor({ vault.activeProfile.value }) {},
            enableLogging = false,
        )
        repository = ServerConnectionRepository(
            api = NetworkModule.storytellerApi(client),
            vault = vault,
            newId = { "id-${++nextId}" },
            clock = { 42L },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val baseUrl get() = server.url("/st").toString().trimEnd('/')

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun failure(result: ConnectionResult<*>) = (result as ConnectionResult.Failure).error

    @Test
    fun `health check succeeds for ok status`() = runTest {
        server.enqueue(json("""{"status":"ok","version":"1.2.3","extra":true}"""))

        val result = repository.checkHealth(baseUrl)

        assertEquals("1.2.3", (result as ConnectionResult.Success).value.version)
        val recorded = server.takeRequest()
        assertEquals("/st/api/health", recorded.path)
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun `health check succeeds for healthy status`() = runTest {
        server.enqueue(json("""{"status":"healthy","message":"All services are running"}"""))

        val result = repository.checkHealth(baseUrl)

        assertTrue(result is ConnectionResult.Success)
        assertEquals("healthy", (result as ConnectionResult.Success).value.status)
        assertTrue(result.value.isHealthy)
    }

    @Test
    fun `health check maps 404 and non-json to NotStoryteller`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>router</html>"))

        assertEquals(ConnectionError.NotStoryteller, failure(repository.checkHealth(baseUrl)))
        assertEquals(ConnectionError.NotStoryteller, failure(repository.checkHealth(baseUrl)))
    }

    @Test
    fun `health check reports unhealthy status and http errors`() = runTest {
        server.enqueue(json("""{"status":"migrating"}"""))
        server.enqueue(MockResponse().setResponseCode(503))

        assertEquals(ConnectionError.Unhealthy("migrating"), failure(repository.checkHealth(baseUrl)))
        assertEquals(ConnectionError.Http(503), failure(repository.checkHealth(baseUrl)))
    }

    @Test
    fun `unreachable server maps to Unreachable`() = runTest {
        val deadUrl = baseUrl
        server.shutdown()
        assertEquals(ConnectionError.Unreachable, failure(repository.checkHealth(deadUrl)))
    }

    @Test
    fun `login with user_id saves an active profile`() = runTest {
        server.enqueue(json("""{"access_token":"tok","token_type":"bearer","user_id":"u1"}"""))

        val profile = (repository.login(baseUrl, "river", "pw") as ConnectionResult.Success).value

        assertEquals("id-1", profile.id)
        assertEquals("u1", profile.userId)
        assertEquals("river", profile.username)
        assertEquals("tok", profile.authToken)
        assertEquals("localhost", profile.name)
        assertEquals(profile.copy(isActive = true), vault.activeProfile.value)

        val recorded = server.takeRequest()
        assertEquals("/st/api/token", recorded.path)
        assertEquals("""{"username":"river","password":"pw"}""", recorded.body.readUtf8())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `login without user_id resolves user via user-me using the new token`() = runTest {
        server.enqueue(json("""{"access_token":"tok"}"""))
        server.enqueue(json("""{"id":"u9","username":"River Song"}"""))

        val profile = (repository.login(baseUrl, "river", "pw") as ConnectionResult.Success).value

        assertEquals("u9", profile.userId)
        assertEquals("River Song", profile.username)
        server.takeRequest()
        val me = server.takeRequest()
        assertEquals("/st/api/user/me", me.path)
        assertEquals("Bearer tok", me.getHeader("Authorization"))
    }

    @Test
    fun `login falls back to form encoding when json body is rejected`() = runTest {
        server.enqueue(MockResponse().setResponseCode(422))
        server.enqueue(json("""{"access_token":"tok","user_id":"u1"}"""))

        val profile = (repository.login(baseUrl, "river", "p&w") as ConnectionResult.Success).value

        assertEquals("tok", profile.authToken)
        server.takeRequest()
        val form = server.takeRequest()
        assertEquals("/st/api/token", form.path)
        assertTrue(form.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        assertEquals("username=river&password=p%26w", form.body.readUtf8())
    }

    @Test
    fun `login falls back to form encoding on 500 internal server error from json`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(json("""{"access_token":"tok","user_id":"u1"}"""))

        val profile = (repository.login(baseUrl, "river", "p&w") as ConnectionResult.Success).value

        assertEquals("tok", profile.authToken)
        server.takeRequest()
        val form = server.takeRequest()
        assertEquals("/st/api/token", form.path)
        assertTrue(form.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
    }

    @Test
    fun `login rejects bad credentials`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(ConnectionError.InvalidCredentials, failure(repository.login(baseUrl, "river", "nope")))
        assertNull(vault.activeProfile.value)
    }

    @Test
    fun `re-login for same server and user reuses profile id`() = runTest {
        server.enqueue(json("""{"access_token":"old","user_id":"u1"}"""))
        server.enqueue(json("""{"access_token":"new","user_id":"u1"}"""))

        repository.login(baseUrl, "river", "pw")
        val second = (repository.login(baseUrl, "river", "pw") as ConnectionResult.Success).value

        assertEquals("id-1", second.id)
        assertEquals(1, vault.profiles.value.size)
        assertEquals("new", vault.activeProfile.value?.authToken)
    }

    @Test
    fun `adding a second server never sends the active profile's token to it`() = runTest {
        vault.saveProfile(profile("existing", baseUrl = "https://other.example", token = "existing-secret"))
        server.enqueue(json("""{"status":"ok"}"""))
        server.enqueue(json("""{"access_token":"tok","user_id":"u1"}"""))

        repository.checkHealth(baseUrl)
        repository.login(baseUrl, "river", "pw")

        repeat(2) { assertNull(server.takeRequest().getHeader("Authorization")) }
        assertEquals(2, vault.profiles.value.size)
        assertEquals("id-1", vault.activeProfile.value?.id)
    }

    @Test
    fun `connectWithToken validates token and saves profile`() = runTest {
        server.enqueue(json("""{"id":"u5","username":"amy"}"""))

        val profile = (repository.connectWithToken(baseUrl, "qr-token") as ConnectionResult.Success).value

        assertEquals("u5", profile.userId)
        assertEquals("qr-token", profile.authToken)
        assertEquals("Bearer qr-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `connectWithToken rejects invalid token`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        assertEquals(ConnectionError.InvalidToken, failure(repository.connectWithToken(baseUrl, "bad")))
        assertTrue(vault.profiles.value.isEmpty())
    }

    @Test
    fun `login falls back to JWT sub claim when api user me returns 404`() = runTest {
        val jwtHeader = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"alg":"none"}""".toByteArray())
        val jwtPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"user-jwt-123"}""".toByteArray())
        val jwtToken = "$jwtHeader.$jwtPayload."

        // Login response returns access_token without user_id
        server.enqueue(json("""{"access_token":"$jwtToken","token_type":"bearer"}"""))
        // Subsequent /api/user/me returns 404
        server.enqueue(MockResponse().setResponseCode(404))

        val result = repository.login(baseUrl, "river", "password")
        val profile = (result as ConnectionResult.Success).value

        assertEquals("user-jwt-123", profile.userId)
        assertEquals("river", profile.username)
        assertEquals(jwtToken, profile.authToken)
    }

    @Test
    fun `login falls back to username when token is opaque and user me returns 404`() = runTest {
        server.enqueue(json("""{"access_token":"opaque-random-token","token_type":"bearer"}"""))
        server.enqueue(MockResponse().setResponseCode(404))

        val result = repository.login(baseUrl, "river", "password")
        val profile = (result as ConnectionResult.Success).value

        assertEquals("river", profile.userId)
        assertEquals("river", profile.username)
        assertEquals("opaque-random-token", profile.authToken)
    }

    @Test
    fun `health check treats arbitrary positive status as healthy and fails on maintenance`() = runTest {
        server.enqueue(json("""{"status":"alive"}"""))
        val aliveResult = repository.checkHealth(baseUrl)
        assertTrue((aliveResult as ConnectionResult.Success).value.isHealthy)

        server.enqueue(json("""{"status":"maintenance"}"""))
        val maintResult = repository.checkHealth(baseUrl)
        assertEquals(ConnectionError.Unhealthy("maintenance"), failure(maintResult))
    }
}
