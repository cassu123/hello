package org.hello.reader.feature.widget

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.hello.reader.core.data.BookRepository
import org.hello.reader.core.data.ReaderPreferencesRepository
import org.hello.reader.core.database.entity.BookEntity
import org.hello.reader.core.engine.audio.AudioPlaybackController
import org.hello.reader.core.engine.audio.PlaybackSession
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.DownloadStatus
import org.hello.reader.core.model.ServerProfile
import org.hello.reader.core.model.SyncCheckpoint
import org.hello.reader.core.network.NetworkModule
import org.hello.reader.core.network.ServerAuthInterceptor
import org.hello.reader.core.security.SecurityVaultImpl
import org.hello.reader.testing.FakeBookDao
import org.hello.reader.testing.FakeSyncCheckpointDao
import org.hello.reader.testing.InMemorySharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContinueReadingWidgetTest {

    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        testScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    private class TestAudioController : AudioPlaybackController {
        var playing = false
        var position = 0L
        override var hasLoadedMedia = false
        override fun prepareFile(
            audioFile: File,
            title: String,
            artist: String?,
            artworkUri: String?,
            initialPositionMs: Long,
            playbackSpeed: Float,
        ) {
            hasLoadedMedia = true
            position = initialPositionMs
        }
        override fun play() { playing = true }
        override fun pause() { playing = false }
        override fun seekTo(positionMs: Long) { position = positionMs }
        override fun setSpeed(speed: Float) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun currentPositionMs(): Long = position
        override fun isPlaybackRequested(): Boolean = playing
        override fun stop() {
            playing = false
            hasLoadedMedia = false
        }
        override fun release() = Unit
    }

    @Test
    fun `empty widget data holds correct default properties`() {
        val empty = ContinueReadingWidgetData.EMPTY
        assertTrue(empty.isEmpty)
        assertNull(empty.bookId)
        assertEquals("", empty.title)
        assertEquals("", empty.author)
        assertNull(empty.coverBitmap)
        assertEquals(0f, empty.progressPercent, 0.001f)
        assertFalse(empty.hasAudio)
        assertFalse(empty.isPlaying)
        assertFalse(empty.isEInk)
    }

    @Test
    fun `saveCheckpoint triggers onCheckpointSaved callback for widget update`() = runTest {
        var savedCheckpoint: SyncCheckpoint? = null

        val fakeCheckpointDao = FakeSyncCheckpointDao()
        val fakeBookDao = FakeBookDao()
        val fakeVault = SecurityVaultImpl(InMemorySharedPreferences(), Dispatchers.Unconfined)
        val client = NetworkModule.okHttpClient(
            ServerAuthInterceptor({ fakeVault.activeProfile.value }) {},
            enableLogging = false,
        )

        val repo = BookRepository(
            api = NetworkModule.storytellerApi(client),
            bookDao = fakeBookDao,
            checkpointDao = fakeCheckpointDao,
            vault = fakeVault,
            onCheckpointSaved = { savedCheckpoint = it },
        )

        val cp = SyncCheckpoint(
            bookId = "book_abc",
            serverId = "local",
            userId = "local",
            readingCfi = null,
            chapterIndex = 1,
            chapterTitle = "Prologue",
            audioPositionMs = 120_000L,
            totalDurationMs = 600_000L,
            progressPercent = 20f,
            activeSentenceId = "p_1",
        )

        repo.saveCheckpoint(cp)

        assertNotNull(savedCheckpoint)
        assertEquals("book_abc", savedCheckpoint!!.bookId)
        assertEquals(20f, savedCheckpoint!!.progressPercent, 0.001f)
    }

    @Test
    fun `data loader returns EMPTY when no profile is active and no local books are in progress`() = runTest {
        val fakeCheckpointDao = FakeSyncCheckpointDao()
        val fakeBookDao = FakeBookDao()
        val fakeVault = SecurityVaultImpl(InMemorySharedPreferences(), Dispatchers.Unconfined)
        val client = NetworkModule.okHttpClient(
            ServerAuthInterceptor({ fakeVault.activeProfile.value }) {},
            enableLogging = false,
        )
        val repo = BookRepository(
            api = NetworkModule.storytellerApi(client),
            bookDao = fakeBookDao,
            checkpointDao = fakeCheckpointDao,
            vault = fakeVault,
        )
        val audio = TestAudioController()
        val session = PlaybackSession({ audio }, { }, testScope)
        val tmpDir = java.nio.file.Files.createTempDirectory("widget-test-empty").toFile()
        val prefs = ReaderPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = testScope) { File(tmpDir, "prefs.preferences_pb") },
        )

        val result = ContinueReadingWidgetUpdater.loadWidgetDataFromRepositories(
            bookRepository = repo,
            checkpointDao = fakeCheckpointDao,
            playbackSession = session,
            readerPreferencesRepository = prefs,
            loadCover = { null },
        )

        assertTrue(result.isEmpty)
        assertNull(result.bookId)
    }

    @Test
    fun `data loader returns 0f fallback progress when book is downloaded but has no checkpoint`() = runTest {
        val fakeCheckpointDao = FakeSyncCheckpointDao()
        val fakeBookDao = FakeBookDao()
        val fakeVault = SecurityVaultImpl(InMemorySharedPreferences(), Dispatchers.Unconfined)
        val profile = ServerProfile("s1", "Server 1", "https://example.com", "token", null, "u1", "User 1")
        fakeVault.saveProfile(profile)
        fakeVault.setActiveProfile("s1")

        val client = NetworkModule.okHttpClient(
            ServerAuthInterceptor({ fakeVault.activeProfile.value }) {},
            enableLogging = false,
        )
        val repo = BookRepository(
            api = NetworkModule.storytellerApi(client),
            bookDao = fakeBookDao,
            checkpointDao = fakeCheckpointDao,
            vault = fakeVault,
        )
        val audio = TestAudioController()
        val session = PlaybackSession({ audio }, { }, testScope)
        val tmpDir = java.nio.file.Files.createTempDirectory("widget-test-fallback").toFile()
        val prefs = ReaderPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = testScope) { File(tmpDir, "prefs.preferences_pb") },
        )

        val book = BookEntity(
            id = "book_downloaded",
            serverId = "s1",
            userId = "u1",
            title = "Downloaded Book",
            authors = "Author Name",
            narrators = "",
            coverUrl = null,
            description = null,
            mediaType = BookMediaType.READALOUD,
            durationSeconds = 1200L,
            totalChapters = 5,
            downloadStatus = DownloadStatus.DOWNLOADED,
            downloadProgress = 1.0f,
            localFilePath = "/path/to/book.epub",
            localAudioPath = "/path/to/audio",
            tags = "",
            createdAt = 0L,
            lastOpenedAt = 1000L,
        )
        fakeBookDao.upsertBooks(listOf(book))

        val result = ContinueReadingWidgetUpdater.loadWidgetDataFromRepositories(
            bookRepository = repo,
            checkpointDao = fakeCheckpointDao,
            playbackSession = session,
            readerPreferencesRepository = prefs,
            loadCover = { null },
        )

        assertFalse(result.isEmpty)
        assertEquals("book_downloaded", result.bookId)
        // Verified: Fallback must be 0f, not 100f (1.0f * 100)
        assertEquals(0f, result.progressPercent, 0.001f)
    }

    @Test
    fun `profile switch isolates continue reading and clears widget when switched to empty profile`() = runTest {
        val fakeCheckpointDao = FakeSyncCheckpointDao()
        val fakeBookDao = FakeBookDao()
        val fakeVault = SecurityVaultImpl(InMemorySharedPreferences(), Dispatchers.Unconfined)
        val profile1 = ServerProfile("s1", "Server 1", "https://example1.com", "token1", null, "u1", "User 1")
        val profile2 = ServerProfile("s2", "Server 2", "https://example2.com", "token2", null, "u2", "User 2")
        fakeVault.saveProfile(profile1)
        fakeVault.saveProfile(profile2)
        fakeVault.setActiveProfile("s1")

        val client = NetworkModule.okHttpClient(
            ServerAuthInterceptor({ fakeVault.activeProfile.value }) {},
            enableLogging = false,
        )
        val repo = BookRepository(
            api = NetworkModule.storytellerApi(client),
            bookDao = fakeBookDao,
            checkpointDao = fakeCheckpointDao,
            vault = fakeVault,
        )
        val audio = TestAudioController()
        val session = PlaybackSession({ audio }, { }, testScope)
        val tmpDir = java.nio.file.Files.createTempDirectory("widget-test-profiles").toFile()
        val prefs = ReaderPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = testScope) { File(tmpDir, "prefs.preferences_pb") },
        )

        val bookProfile1 = BookEntity(
            id = "b_p1",
            serverId = "s1",
            userId = "u1",
            title = "Profile 1 Book",
            authors = "Author 1",
            narrators = "",
            coverUrl = null,
            description = null,
            mediaType = BookMediaType.READALOUD,
            durationSeconds = 1200L,
            totalChapters = 5,
            downloadStatus = DownloadStatus.DOWNLOADED,
            downloadProgress = 1.0f,
            localFilePath = "/path/to/book.epub",
            localAudioPath = "/path/to/audio",
            tags = "",
            createdAt = 0L,
            lastOpenedAt = 1000L,
        )
        fakeBookDao.upsertBooks(listOf(bookProfile1))

        // Profile 1 active: book shows
        val result1 = ContinueReadingWidgetUpdater.loadWidgetDataFromRepositories(
            bookRepository = repo,
            checkpointDao = fakeCheckpointDao,
            playbackSession = session,
            readerPreferencesRepository = prefs,
            loadCover = { null },
        )
        assertFalse(result1.isEmpty)
        assertEquals("b_p1", result1.bookId)

        // Switch to Profile 2 (which has no books)
        fakeVault.setActiveProfile("s2")
        val result2 = ContinueReadingWidgetUpdater.loadWidgetDataFromRepositories(
            bookRepository = repo,
            checkpointDao = fakeCheckpointDao,
            playbackSession = session,
            readerPreferencesRepository = prefs,
            loadCover = { null },
        )
        assertTrue(result2.isEmpty)
        assertNull(result2.bookId)
    }

    @Test
    fun `initialize runs vault init off Main, collects on Main, and preserves pre-init requestUpdate`() = runTest {
        ContinueReadingWidgetUpdater.reset()
        try {
            val ioDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler, name = "test-io")
            val mainDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler, name = "test-main")
            val widgetScope = CoroutineScope(SupervisorJob() + ioDispatcher)
            val fakeVault = SecurityVaultImpl(InMemorySharedPreferences(), Dispatchers.Unconfined)
            val audio = TestAudioController()
            val session = PlaybackSession({ audio }, { }, widgetScope)

            var vaultInitialized = false
            var updatesCount = 0

            val dummyContext = object : android.content.ContextWrapper(null) {
                override fun getApplicationContext(): android.content.Context = this
            }

            try {
                ContinueReadingWidgetUpdater.initialize(
                    context = dummyContext,
                    scope = widgetScope,
                    vaultProvider = {
                        vaultInitialized = true
                        fakeVault
                    },
                    playbackSessionProvider = { session },
                    ioDispatcher = ioDispatcher,
                    mainDispatcher = mainDispatcher,
                    onUpdateImmediate = { updatesCount++ },
                )

                // Before ioDispatcher runs, vault has not been touched on caller thread
                assertFalse("vaultInitialized should be false before scheduler advances", vaultInitialized)

                // Request a widget update before init finishes
                ContinueReadingWidgetUpdater.requestUpdate(dummyContext)

                // Advance scheduler so ioDispatcher initializes vault and mainDispatcher collects flows
                testScheduler.advanceUntilIdle()

                assertTrue("vaultInitialized should be true after scheduler advances", vaultInitialized)
                // 1 from pre-init requestUpdate + initial flow emissions on Main
                assertTrue("Expected pre-init update and initial flow updates to execute (got $updatesCount)", updatesCount >= 2)

                val beforeSwitch = updatesCount
                fakeVault.saveProfile(ServerProfile("s1", "Server 1", "https://example.com", "t1", null, "u1", "U1"))
                fakeVault.setActiveProfile("s1")
                testScheduler.advanceUntilIdle()
                assertTrue("Expected activeProfile change on Main to trigger widget update", updatesCount > beforeSwitch)
            } finally {
                widgetScope.cancel()
            }
        } finally {
            ContinueReadingWidgetUpdater.reset()
        }
    }
}
