package org.hello.reader.core.security

import org.hello.reader.testing.InMemorySharedPreferences
import org.hello.reader.testing.profile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class SecurityVaultImplTest {

    private val prefs = InMemorySharedPreferences()
    private var now = 1_000L

    private fun vault(dispatcher: kotlinx.coroutines.CoroutineDispatcher) =
        SecurityVaultImpl(prefs, dispatcher, clock = { now })

    @Test
    fun `empty vault has no active profile`() = runTest {
        val vault = vault(StandardTestDispatcher(testScheduler))
        assertNull(vault.activeProfile.value)
        assertTrue(vault.getAllProfiles().isEmpty())
    }

    @Test
    fun `first saved profile becomes active even if not flagged`() = runTest {
        val vault = vault(StandardTestDispatcher(testScheduler))
        vault.saveProfile(profile("a"))
        assertEquals("a", vault.activeProfile.value?.id)
        assertTrue(vault.activeProfile.value!!.isActive)
    }

    @Test
    fun `saving an active profile deactivates the others`() = runTest {
        val vault = vault(StandardTestDispatcher(testScheduler))
        vault.saveProfile(profile("a"))
        vault.saveProfile(profile("b", isActive = true))

        assertEquals("b", vault.activeProfile.value?.id)
        assertEquals(listOf("b"), vault.getAllProfiles().filter { it.isActive }.map { it.id })
    }

    @Test
    fun `saving an inactive profile keeps the current active one`() = runTest {
        val vault = vault(StandardTestDispatcher(testScheduler))
        vault.saveProfile(profile("a"))
        vault.saveProfile(profile("b"))
        assertEquals("a", vault.activeProfile.value?.id)
        assertEquals(listOf("a", "b"), vault.profiles.value.map { it.id })
    }

    @Test
    fun `replacing the active profile keeps it active and in place`() = runTest {
        val vault = vault(StandardTestDispatcher(testScheduler))
        vault.saveProfile(profile("a"))
        vault.saveProfile(profile("b"))
        vault.saveProfile(profile("a", token = "refreshed"))

        assertEquals("a", vault.activeProfile.value?.id)
        assertEquals("refreshed", vault.activeProfile.value?.authToken)
        assertEquals(listOf("a", "b"), vault.profiles.value.map { it.id })
    }

    @Test
    fun `setActiveProfile switches and stamps lastUsedAt`() = runTest {
        val vault = vault(StandardTestDispatcher(testScheduler))
        vault.saveProfile(profile("a"))
        vault.saveProfile(profile("b"))
        now = 5_000L

        vault.setActiveProfile("b")

        assertEquals("b", vault.activeProfile.value?.id)
        assertEquals(5_000L, vault.activeProfile.value?.lastUsedAt)
        assertFalse(vault.profiles.value.first { it.id == "a" }.isActive)
    }

    @Test
    fun `setActiveProfile rejects unknown ids without changing state`() = runTest {
        val vault = vault(StandardTestDispatcher(testScheduler))
        vault.saveProfile(profile("a"))
        try {
            vault.setActiveProfile("missing")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        assertEquals("a", vault.activeProfile.value?.id)
    }

    @Test
    fun `deleting the active profile promotes the most recently used`() = runTest {
        val vault = vault(StandardTestDispatcher(testScheduler))
        vault.saveProfile(profile("a", isActive = true))
        vault.saveProfile(profile("b", lastUsedAt = 10L))
        vault.saveProfile(profile("c", lastUsedAt = 50L))

        vault.deleteProfile("a")

        assertEquals("c", vault.activeProfile.value?.id)
        assertEquals(1, vault.profiles.value.count { it.isActive })
    }

    @Test
    fun `deleting the last profile clears active`() = runTest {
        val vault = vault(StandardTestDispatcher(testScheduler))
        vault.saveProfile(profile("a"))
        vault.deleteProfile("a")
        assertNull(vault.activeProfile.value)
        assertTrue(vault.profiles.value.isEmpty())
    }

    @Test
    fun `profiles survive a reload from storage`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        vault(dispatcher).apply {
            saveProfile(profile("a"))
            saveProfile(profile("b", isActive = true))
        }

        val reloaded = vault(dispatcher)

        assertEquals(listOf("a", "b"), reloaded.profiles.value.map { it.id })
        assertEquals("b", reloaded.activeProfile.value?.id)
        assertEquals("token-b", reloaded.activeProfile.value?.authToken)
    }

    @Test
    fun `corrupt storage loads as empty`() = runTest {
        prefs.values["vault_server_profiles"] = "{not json"
        assertTrue(vault(StandardTestDispatcher(testScheduler)).profiles.value.isEmpty())
    }

    @Test
    fun `stored record violating invariants loads as empty`() = runTest {
        prefs.values["vault_server_profiles"] =
            """[{"id":"a","name":"a","baseUrl":"https://x.example/","authToken":"t","userId":"u","username":"n"}]"""
        assertTrue(vault(StandardTestDispatcher(testScheduler)).profiles.value.isEmpty())
    }

    @Test
    fun `stored list with no or multiple active flags is normalized to exactly one`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        vault(dispatcher).apply {
            saveProfile(profile("a"))
            saveProfile(profile("b"))
        }
        val raw = prefs.values["vault_server_profiles"] as String
        prefs.values["vault_server_profiles"] = raw.replace("\"isActive\":true", "\"isActive\":false")
        assertEquals("a", vault(dispatcher).activeProfile.value?.id)

        prefs.values["vault_server_profiles"] = raw.replace("\"isActive\":false", "\"isActive\":true")
        val repaired = vault(dispatcher)
        assertEquals(1, repaired.profiles.value.count { it.isActive })
        assertEquals("a", repaired.activeProfile.value?.id)
    }

    @Test
    fun `failed commit throws and leaves in-memory state unchanged`() = runTest {
        val vault = vault(StandardTestDispatcher(testScheduler))
        vault.saveProfile(profile("a"))
        prefs.failCommits = true
        try {
            vault.saveProfile(profile("b", isActive = true))
            fail("expected IOException")
        } catch (e: IOException) {
            // expected
        }
        assertEquals(listOf("a"), vault.profiles.value.map { it.id })
        assertEquals("a", vault.activeProfile.value?.id)
    }

    @Test
    fun `concurrent saves are serialized without losing writes`() = runTest {
        val vault = vault(kotlinx.coroutines.Dispatchers.Default)
        (1..50).map { i -> async { vault.saveProfile(profile("p$i")) } }.awaitAll()
        assertEquals(50, vault.profiles.value.size)
        assertEquals(1, vault.profiles.value.count { it.isActive })
    }

    @Test
    fun `renameProfile updates display name while keeping active state`() = runTest {
        val vault = vault(StandardTestDispatcher(testScheduler))
        vault.saveProfile(profile("a", isActive = true))

        vault.renameProfile("a", "  River Song Library  ")

        assertEquals("River Song Library", vault.activeProfile.value?.name)
        assertTrue(vault.activeProfile.value!!.isActive)
    }

    @Test
    fun `profile toString does not leak tokens`() {
        assertFalse(profile("a", token = "super-secret").toString().contains("super-secret"))
    }
}
