package org.hello.reader.feature.settings

import org.hello.reader.core.data.ConnectionResult
import org.hello.reader.core.security.SecurityVaultImpl
import org.hello.reader.feature.library.BookImportController
import org.hello.reader.testing.FakeBookDao
import org.hello.reader.testing.InMemorySharedPreferences
import org.hello.reader.testing.profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var vault: SecurityVaultImpl
    private lateinit var bookDao: FakeBookDao
    private lateinit var vm: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        vault = SecurityVaultImpl(InMemorySharedPreferences(), testDispatcher)
        bookDao = FakeBookDao()
        // For unit testing SettingsViewModel, dummy importer is sufficient for non-import tests
        vm = SettingsViewModel(
            vault = vault,
            imports = BookImportController(CoroutineScope(Dispatchers.Unconfined)) { _: android.net.Uri ->
                Result.failure(IllegalStateException("not used in these tests"))
            },
            bookDao = bookDao,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `selectProfile and disconnectProfile mutate vault state`() = runTest {
        val p1 = profile("p1", baseUrl = "https://s1.example", isActive = true)
        val p2 = profile("p2", baseUrl = "https://s2.example", isActive = false)
        vault.saveProfile(p1)
        vault.saveProfile(p2)

        assertEquals("p1", vm.activeProfile.value?.id)

        vm.selectProfile("p2")
        assertEquals("p2", vm.activeProfile.value?.id)

        vm.disconnectProfile()
        assertNull(vm.activeProfile.value)
    }
}
