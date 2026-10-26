package org.hello.reader.feature.library

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.hello.reader.core.data.BookRepository
import org.hello.reader.core.data.ReaderPreferencesRepository
import org.hello.reader.core.database.entity.BookCollectionCrossRef
import org.hello.reader.core.database.entity.BookEntity
import org.hello.reader.core.database.entity.BookSeriesCrossRef
import org.hello.reader.core.database.entity.CollectionEntity
import org.hello.reader.core.database.entity.SeriesEntity
import org.hello.reader.core.database.entity.SyncCheckpointEntity
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.DownloadStatus
import org.hello.reader.core.model.HomeShelfConfig
import org.hello.reader.core.model.HomeShelfType
import org.hello.reader.core.model.ReadingStatusItem
import org.hello.reader.core.network.NetworkModule
import org.hello.reader.core.network.ServerAuthInterceptor
import org.hello.reader.core.security.SecurityVaultImpl
import org.hello.reader.testing.FakeBookDao
import org.hello.reader.testing.FakeBookDownloadDao
import org.hello.reader.testing.FakeCollectionDao
import org.hello.reader.testing.FakeSeriesDao
import org.hello.reader.testing.FakeSyncCheckpointDao
import org.hello.reader.testing.InMemorySharedPreferences
import org.hello.reader.testing.profile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var viewModelStore: ViewModelStore
    private var viewModelCounter = 0
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var preferences: ReaderPreferencesRepository
    private lateinit var server: MockWebServer
    private lateinit var vault: SecurityVaultImpl
    private lateinit var bookDao: FakeBookDao
    private lateinit var checkpointDao: FakeSyncCheckpointDao
    private lateinit var downloadDao: FakeBookDownloadDao
    private lateinit var seriesDao: FakeSeriesDao
    private lateinit var collectionDao: FakeCollectionDao
    private lateinit var repository: BookRepository
    private lateinit var scheduler: FakeDownloadScheduler

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModelStore = ViewModelStore()
        viewModelCounter = 0
        dataStoreScope = CoroutineScope(testDispatcher + SupervisorJob())
        preferences = ReaderPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = dataStoreScope) {
                tmp.newFile("test_prefs_${System.currentTimeMillis()}.preferences_pb").also { it.delete() }
            },
        )
        server = MockWebServer().apply {
            (dispatcher as QueueDispatcher).setFailFast(MockResponse().setResponseCode(404))
            start()
        }
        vault = SecurityVaultImpl(InMemorySharedPreferences(), Dispatchers.Unconfined)
        bookDao = FakeBookDao()
        checkpointDao = FakeSyncCheckpointDao()
        downloadDao = FakeBookDownloadDao()
        seriesDao = FakeSeriesDao(bookDao)
        collectionDao = FakeCollectionDao(bookDao)
        okHttpClient = NetworkModule.okHttpClient(
            ServerAuthInterceptor({ vault.activeProfile.value }) {},
            enableLogging = false,
        )
        repository = BookRepository(
            api = NetworkModule.storytellerApi(okHttpClient),
            bookDao = bookDao,
            checkpointDao = checkpointDao,
            vault = vault,
            seriesDao = seriesDao,
            collectionDao = collectionDao,
            downloadDao = downloadDao,
            clock = { 1_000L },
        )
        scheduler = FakeDownloadScheduler()
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        okHttpClient.dispatcher.cancelAll()
        okHttpClient.dispatcher.executorService.shutdownNow()
        okHttpClient.connectionPool.evictAll()
        testDispatcher.scheduler.advanceUntilIdle()
        dataStoreScope.cancel()
        testDispatcher.scheduler.advanceUntilIdle()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        preferencesRepository: ReaderPreferencesRepository? = null,
    ): LibraryViewModel {
        val key = "library_vm_${viewModelCounter++}"
        val provider = ViewModelProvider(
            viewModelStore,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LibraryViewModel(
                        repository = repository,
                        activeProfile = vault.activeProfile,
                        downloads = scheduler,
                        preferencesRepository = preferencesRepository,
                    ) as T
            },
        )
        return provider[key, LibraryViewModel::class.java]
    }

    private val baseUrl get() = server.url("/").toString().trimEnd('/')

    private fun jsonResponse(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `automatically refreshes catalog when active server profile becomes active or switches`() = runTest {
        server.enqueue(
            jsonResponse(
                """{"items":[{"id":"b1","title":"Dune","authors":["Frank Herbert"],"has_audio":true,"has_epub":true,"is_aligned":true,"tags":["Sci-Fi"]}],"total":1,"page":1}""",
            ),
        )
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))

        val vm = createViewModel()
        val collectJob = backgroundScope.launch(testDispatcher) { vm.state.collect {} }

        val loaded = vm.state.first { it.books.isNotEmpty() }
        assertEquals(listOf("b1"), loaded.books.map { it.id })
        assertEquals(listOf("Sci-Fi"), loaded.tags)

        // Select the "Sci-Fi" tag filter, then switch to server s2 whose catalog lacks that tag
        vm.setFilter(LibraryFilter.Tag("Sci-Fi"))
        assertEquals(LibraryFilter.Tag("Sci-Fi"), vm.state.value.filter)

        server.enqueue(
            jsonResponse(
                """{"items":[{"id":"b2","title":"The Hobbit","authors":["J.R.R. Tolkien"],"has_audio":false,"has_epub":true,"is_aligned":false,"tags":["Fantasy"]}],"total":1,"page":1}""",
            ),
        )
        vault.saveProfile(profile("s2", baseUrl = baseUrl, userId = "u2", isActive = true))

        val switched = vm.state.first { it.books.any { b -> b.id == "b2" } }
        assertEquals(listOf("b2"), switched.books.map { it.id })
        // Missing tag filter falls back to LibraryFilter.All automatically
        assertEquals(LibraryFilter.All, switched.filter)
        collectJob.cancel()
    }

    @Test
    fun `download marks QUEUED in Room before enqueueing and cancel resets to NOT_DOWNLOADED`() = runTest {
        server.enqueue(
            jsonResponse(
                """{"items":[{"id":"b1","title":"Hyperion","authors":["Dan Simmons"],"has_audio":true,"has_epub":true,"is_aligned":true}],"total":1,"page":1}""",
            ),
        )
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        scheduler.statusAtEnqueue = { id ->
            runBlocking { bookDao.getBookById(id, "s1", "u1")?.downloadStatus }
        }

        val vm = createViewModel()
        val collectJob = backgroundScope.launch(testDispatcher) { vm.state.collect {} }

        val book = vm.state.first { it.books.isNotEmpty() }.books.single()
        vm.download(book)

        val queued = vm.state.first { it.books.single().downloadStatus == DownloadStatus.QUEUED }
        assertEquals(DownloadStatus.QUEUED, queued.books.single().downloadStatus)
        assertEquals(listOf("b1"), scheduler.enqueuedIds)
        // The worker must never be able to observe a pre-QUEUED row.
        assertEquals(listOf(DownloadStatus.QUEUED), scheduler.statusesSeenAtEnqueue)

        vm.cancelDownload(queued.books.single())

        val cancelled = vm.state.first { it.books.single().downloadStatus == DownloadStatus.NOT_DOWNLOADED }
        assertEquals(0f, cancelled.books.single().downloadProgress, 0f)
        assertEquals(listOf("b1"), scheduler.cancelledIds)
        collectJob.cancel()
    }

    @Test
    fun `view mode toggles between grid and list`() = runTest {
        server.enqueue(jsonResponse("""{"items":[],"total":0,"page":1}"""))
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        val vm = createViewModel()
        val collectJob = backgroundScope.launch(testDispatcher) { vm.state.collect {} }

        assertEquals(LibraryViewMode.GRID, vm.state.first { !it.isLoading }.viewMode)
        vm.setViewMode(LibraryViewMode.LIST)
        assertEquals(LibraryViewMode.LIST, vm.state.first { it.viewMode == LibraryViewMode.LIST }.viewMode)
        collectJob.cancel()
    }

    @Test
    fun `with no server connected the library finishes loading instead of spinning`() = runTest {
        val vm = createViewModel()
        val collectJob = backgroundScope.launch(testDispatcher) { vm.state.collect {} }

        val state = vm.state.first { !it.isLoading }
        assertEquals(0, state.totalBookCount)
        assertEquals(0, server.requestCount)
        collectJob.cancel()
    }

    @Test
    fun `setActiveTab toggles active tab between books, series and collections`() = runTest {
        val vm = createViewModel()
        val collectJob = backgroundScope.launch(testDispatcher) { vm.state.collect {} }

        assertEquals(LibraryTab.BOOKS, vm.state.first { !it.isLoading }.activeTab)
        vm.setActiveTab(LibraryTab.SERIES)
        assertEquals(LibraryTab.SERIES, vm.state.first { it.activeTab == LibraryTab.SERIES }.activeTab)
        vm.setActiveTab(LibraryTab.COLLECTIONS)
        assertEquals(LibraryTab.COLLECTIONS, vm.state.first { it.activeTab == LibraryTab.COLLECTIONS }.activeTab)

        collectJob.cancel()
    }

    @Test
    fun `series and collections are populated and searchable in library view model`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        val b1 = testBook("b1", serverId = "s1", userId = "u1", title = "Dune", coverUrl = "http://example.com/c1.jpg")
        val b2 = testBook("b2", serverId = "s1", userId = "u1", title = "Dune Messiah", coverUrl = "http://example.com/c2.jpg")
        bookDao.upsertBooks(listOf(BookEntity.fromDomain(b1, "u1"), BookEntity.fromDomain(b2, "u1")))

        seriesDao.upsertSeries(listOf(
            SeriesEntity("s-dune", "s1", "Dune Chronicles", "Chronicles of Dune", 6)
        ))
        seriesDao.upsertBookSeries(listOf(
            BookSeriesCrossRef(bookId = "b1", seriesUuid = "s-dune", serverId = "s1", position = 1.0, featured = true),
            BookSeriesCrossRef(bookId = "b2", seriesUuid = "s-dune", serverId = "s1", position = 2.0, featured = false)
        ))

        collectionDao.upsertCollections(listOf(
            CollectionEntity("c-fav", "s1", "Favorites", "Best books", null, "#FF0000", isPublic = true, totalBooks = 2)
        ))
        collectionDao.upsertBookCollections(listOf(
            BookCollectionCrossRef(bookId = "b1", serverId = "s1", collectionUuid = "c-fav")
        ))

        val vm = createViewModel()
        val collectJob = backgroundScope.launch(testDispatcher) { vm.state.collect {} }

        val initial = vm.state.first { it.series.isNotEmpty() && it.collections.isNotEmpty() && it.books.size == 2 }
        assertEquals(1, initial.series.size)
        assertEquals("Dune Chronicles", initial.series[0].series.name)
        assertEquals(listOf("http://example.com/c1.jpg", "http://example.com/c2.jpg"), initial.series[0].coverUrls)
        assertEquals(1, initial.collections.size)
        assertEquals("Favorites", initial.collections[0].name)

        // Search for series name filters books tab
        vm.setQuery("Chronicles")
        val searchedBooks = vm.state.first { it.query == "Chronicles" }
        assertEquals(listOf("b1", "b2"), searchedBooks.books.map { it.id })

        // Search also filters series and collections tab
        assertEquals(1, searchedBooks.series.size)
        assertEquals(0, searchedBooks.collections.size)

        vm.setQuery("Favorites")
        val searchedColls = vm.state.first { it.query == "Favorites" }
        assertEquals(0, searchedColls.series.size)
        assertEquals(1, searchedColls.collections.size)

        collectJob.cancel()
    }

    @Test
    fun `multi-select toggling, selecting all, and clearing updates state`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        bookDao.upsertBooks(listOf(
            testBookEntity("b1"),
            testBookEntity("b2"),
            testBookEntity("b3"),
        ))

        val vm = createViewModel()
        val collectJob = backgroundScope.launch(testDispatcher) { vm.state.collect {} }

        val initial = vm.state.first { it.books.size == 3 }
        assertEquals(emptySet<String>(), initial.selectedBookIds)
        assertEquals(false, initial.isSelectionMode)

        // Toggle b1
        vm.toggleBookSelection("b1")
        val withB1 = vm.state.first { "b1" in it.selectedBookIds }
        assertEquals(setOf("b1"), withB1.selectedBookIds)
        assertEquals(true, withB1.isSelectionMode)

        // Toggle b2
        vm.toggleBookSelection("b2")
        val withB1B2 = vm.state.first { it.selectedBookIds.size == 2 }
        assertEquals(setOf("b1", "b2"), withB1B2.selectedBookIds)

        // Select all
        vm.selectAllBooks(listOf("b1", "b2", "b3"))
        val allSelected = vm.state.first { it.selectedBookIds.size == 3 }
        assertEquals(setOf("b1", "b2", "b3"), allSelected.selectedBookIds)

        // Deselect all
        vm.selectAllBooks(listOf("b1", "b2", "b3"))
        val noneSelected = vm.state.first { it.selectedBookIds.isEmpty() }
        assertEquals(emptySet<String>(), noneSelected.selectedBookIds)
        assertEquals(false, noneSelected.isSelectionMode)

        // Clear selection
        vm.toggleBookSelection("b3")
        vm.clearSelection()
        val cleared = vm.state.first { it.selectedBookIds.isEmpty() }
        assertEquals(false, cleared.isSelectionMode)

        collectJob.cancel()
    }

    @Test
    fun `bulk download enqueues available formats for selected books`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        bookDao.upsertBooks(listOf(
            testBookEntity("b1", hasReadaloud = true, hasEbook = true),
            testBookEntity("b2", hasAudiobook = true),
        ))

        val vm = createViewModel()
        val collectJob = backgroundScope.launch(testDispatcher) { vm.state.collect {} }

        vm.state.first { it.books.size == 2 }
        vm.toggleBookSelection("b1")
        vm.toggleBookSelection("b2")

        // Bulk download readaloud & audiobook
        vm.bulkDownload(setOf(BookMediaType.READALOUD, BookMediaType.AUDIO_ONLY))

        // b1 gets READALOUD, b2 gets AUDIO_ONLY
        assertEquals(listOf("b1", "b2"), scheduler.enqueuedIds)
        // Selection cleared after bulk download
        assertEquals(emptySet<String>(), vm.state.value.selectedBookIds)

        collectJob.cancel()
    }

    @Test
    fun `updateReadingStatus and setRating update Room via repository`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        val b1 = testBook("b1", serverId = "s1", userId = "u1", title = "Dune")
        bookDao.upsertBooks(listOf(BookEntity.fromDomain(b1, "u1")))

        val vm = createViewModel()
        val collectJob = backgroundScope.launch(testDispatcher) { vm.state.collect {} }

        val statusItem = ReadingStatusItem(uuid = "stat-read", name = "read", label = "Read")
        vm.updateReadingStatus(b1, statusItem)
        testDispatcher.scheduler.advanceUntilIdle()

        val updatedStatus = bookDao.getBooksSnapshot("s1", "u1").first { it.id == "b1" }
        assertEquals("read", updatedStatus.readingStatus)
        assertEquals("Read", updatedStatus.readingStatusLabel)
        assertEquals("stat-read", updatedStatus.statusUuid)

        vm.setRating(b1, 4.5f)
        testDispatcher.scheduler.advanceUntilIdle()

        val updatedRating = bookDao.getBooksSnapshot("s1", "u1").first { it.id == "b1" }
        assertEquals(4.5f, updatedRating.rating)

        collectJob.cancel()
    }

    @Test
    fun `readingStatuses in view model state excludes none and defaults unassigned books to to_read`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        val b1 = testBook("b1", serverId = "s1", userId = "u1", title = "Unassigned")
        val b2 = testBook("b2", serverId = "s1", userId = "u1", title = "Reading").copy(readingStatus = "reading", readingStatusLabel = "Reading")
        val b3 = testBook("b3", serverId = "s1", userId = "u1", title = "Stuck").copy(readingStatus = "none", readingStatusLabel = "No status")
        bookDao.upsertBooks(listOf(BookEntity.fromDomain(b1, "u1"), BookEntity.fromDomain(b2, "u1"), BookEntity.fromDomain(b3, "u1")))

        val vm = createViewModel()
        val collectJob = backgroundScope.launch(testDispatcher) { vm.state.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val statuses = vm.state.value.readingStatuses
        assertTrue(statuses.any { it.name == "to_read" })
        assertTrue(statuses.any { it.name == "reading" })
        assertFalse(statuses.any { it.name.equals("none", ignoreCase = true) })

        collectJob.cancel()
    }

    @Test
    fun `state progress suppresses to_read progress and continueItems excludes to_read`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        val b1 = testBook("b1", serverId = "s1", userId = "u1", title = "To Read Book")
            .copy(readingStatus = "to_read", readingStatusLabel = "To read", lastOpenedAt = 100L)
        val b2 = testBook("b2", serverId = "s1", userId = "u1", title = "Reading Book")
            .copy(readingStatus = "reading", readingStatusLabel = "Reading", lastOpenedAt = 200L)
        val b3 = testBook("b3", serverId = "s1", userId = "u1", title = "Read Book")
            .copy(readingStatus = "read", readingStatusLabel = "Read", lastOpenedAt = 300L)
        bookDao.upsertBooks(listOf(BookEntity.fromDomain(b1, "u1"), BookEntity.fromDomain(b2, "u1"), BookEntity.fromDomain(b3, "u1")))

        // All three books have synced checkpoints with 34% progress
        checkpointDao.upsertCheckpoint(
            SyncCheckpointEntity(
                bookId = "b1", serverId = "s1", userId = "u1",
                readingCfi = null, chapterIndex = 0, chapterTitle = null,
                audioPositionMs = 3000L, totalDurationMs = 10000L, progressPercent = 34f,
                activeSentenceId = null, updatedAt = 1L, isDirty = false,
            )
        )
        checkpointDao.upsertCheckpoint(
            SyncCheckpointEntity(
                bookId = "b2", serverId = "s1", userId = "u1",
                readingCfi = null, chapterIndex = 0, chapterTitle = null,
                audioPositionMs = 3000L, totalDurationMs = 10000L, progressPercent = 34f,
                activeSentenceId = null, updatedAt = 1L, isDirty = false,
            )
        )
        checkpointDao.upsertCheckpoint(
            SyncCheckpointEntity(
                bookId = "b3", serverId = "s1", userId = "u1",
                readingCfi = null, chapterIndex = 0, chapterTitle = null,
                audioPositionMs = 3000L, totalDurationMs = 10000L, progressPercent = 34f,
                activeSentenceId = null, updatedAt = 1L, isDirty = false,
            )
        )

        val vm = createViewModel()
        val collectJob = backgroundScope.launch(testDispatcher) { vm.state.collect {} }
        testDispatcher.scheduler.advanceUntilIdle()

        val state = vm.state.value
        // To read book progress is 0f
        assertEquals(0f, state.progress["b1"] ?: 0f, 0f)
        // Reading book shows synced progress
        assertEquals(0.34f, state.progress["b2"] ?: 0f, 0.0001f)
        // Read book shows 1f
        assertEquals(1f, state.progress["b3"] ?: 0f, 0f)

        // Only the reading book is in continueItems
        assertEquals(1, state.continueItems.size)
        assertEquals("b2", state.continueItems.first().book.id)
        assertEquals(0.34f, state.continueItems.first().progress, 0.0001f)

        collectJob.cancel()
    }

    private fun testBookEntity(
        id: String,
        serverId: String = "s1",
        userId: String = "u1",
        title: String = "Title $id",
        hasReadaloud: Boolean = false,
        hasEbook: Boolean = true,
        hasAudiobook: Boolean = false,
    ) = BookEntity(
        id = id,
        serverId = serverId,
        userId = userId,
        title = title,
        authors = "Author",
        narrators = "",
        coverUrl = null,
        description = null,
        mediaType = BookMediaType.EBOOK_ONLY,
        durationSeconds = 0L,
        totalChapters = 1,
        downloadStatus = DownloadStatus.NOT_DOWNLOADED,
        downloadProgress = 0f,
        localFilePath = null,
        localAudioPath = null,
        sha256Checksum = null,
        tags = "",
        createdAt = 0L,
        lastOpenedAt = null,
        hasReadaloud = hasReadaloud,
        hasEbook = hasEbook,
        hasAudiobook = hasAudiobook,
    )

    private fun testBook(
        id: String,
        serverId: String = "s1",
        userId: String = "u1",
        title: String = "Title $id",
        coverUrl: String? = null,
    ) = Book(
        id = id,
        serverId = serverId,
        userId = userId,
        title = title,
        authors = listOf("Author"),
        mediaType = BookMediaType.EBOOK_ONLY,
        coverUrl = coverUrl,
    )

    private class FakeDownloadScheduler : DownloadScheduler {
        val enqueuedIds = mutableListOf<String>()
        val cancelledIds = mutableListOf<String>()
        val statusesSeenAtEnqueue = mutableListOf<DownloadStatus?>()
        var statusAtEnqueue: (String) -> DownloadStatus? = { null }

        override fun enqueue(book: Book, requestedMediaType: org.hello.reader.core.model.BookMediaType?) {
            enqueuedIds += book.id
            statusesSeenAtEnqueue += statusAtEnqueue(book.id)
        }

        override fun cancel(book: Book) {
            cancelledIds += book.id
        }
    }

    @Test
    fun `loadHomeShelves defaults to built-in shelves when no preferences stored`() = runTest {
        val vm = createViewModel(preferencesRepository = preferences)
        backgroundScope.launch(testDispatcher) { vm.state.collect {} }
        vm.state.first { !it.isRefreshing }

        vm.loadHomeShelves()

        assertEquals(HomeShelfConfig.DEFAULT_SHELVES, vm.homeShelves.value)
    }

    @Test
    fun `updateHomeShelves updates state and persists to preferences`() = runTest {
        val vm = createViewModel(preferencesRepository = preferences)
        val modified = HomeShelfConfig.DEFAULT_SHELVES.map { it.copy(isEnabled = false) }

        vm.updateHomeShelves(modified)
        assertEquals(modified, vm.homeShelves.value)

        vm.persistHomeShelves(modified)

        val vm2 = createViewModel(preferencesRepository = preferences)
        vm2.loadHomeShelves()
        val loaded = vm2.homeShelves.first { shelves -> shelves.all { !it.isEnabled } }
        assertEquals(modified, loaded)
    }

    @Test
    fun `resetHomeShelvesToDefault restores default configuration`() = runTest {
        val vm = createViewModel(preferencesRepository = preferences)
        val modified = HomeShelfConfig.DEFAULT_SHELVES.take(2)
        vm.updateHomeShelves(modified)
        assertEquals(modified, vm.homeShelves.value)

        vm.resetHomeShelvesToDefault()
        assertEquals(HomeShelfConfig.DEFAULT_SHELVES, vm.homeShelves.value)
    }

    @Test
    fun `server home sections are fetched and merged into home shelves`() = runTest {
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = request.path ?: ""
                return when {
                    path.contains("api/v2/home/sections") ->
                        jsonResponse(
                            """
                            [
                                {"uuid":"sec_cr","kind":"currentlyReading","name":"Reading Now","enabled":true},
                                {"uuid":"sec_ra","kind":"recentlyAdded","name":"New Arrivals","enabled":true},
                                {"uuid":"sec_sh","kind":"shelf","shelfUuid":"shelf_uuid_1","name":"Cosmere Favorites","enabled":true},
                                {"uuid":"sec_hero","kind":"hero","enabled":true},
                                {"uuid":"sec_stats","kind":"stats","enabled":true},
                                {"uuid":"sec_dis","kind":"shelf","shelfUuid":"shelf_uuid_2","name":"Disabled Shelf","enabled":false}
                            ]
                            """.trimIndent()
                        )
                    path.contains("api/v2/shelves/shelf_uuid_1/books") ->
                        jsonResponse("""[{"id":"b1","title":"The Way of Kings"},{"id":"b2","title":"Words of Radiance"}]""")
                    else ->
                        jsonResponse("""{"items":[],"total":0,"page":1}""")
                }
            }
        }
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))

        val vm = createViewModel(preferencesRepository = preferences)
        backgroundScope.launch(testDispatcher) { vm.state.collect {} }
        vm.state.first { !it.isRefreshing }

        vm.loadHomeShelves()

        val serverSections = vm.serverHomeSections.first { it.isNotEmpty() }
        assertEquals(3, serverSections.size)

        val cr = serverSections.find { it.id == "server_sec_cr" }
        assertNotNull(cr)
        assertEquals("Reading Now", cr!!.title)
        assertEquals(HomeShelfType.CONTINUE_READING, cr.type)

        val ra = serverSections.find { it.id == "server_sec_ra" }
        assertNotNull(ra)
        assertEquals("New Arrivals", ra!!.title)
        assertEquals(HomeShelfType.RECENTLY_ADDED, ra.type)

        val shelf = serverSections.find { it.id == "server_sec_sh" }
        assertNotNull(shelf)
        assertEquals("Cosmere Favorites", shelf!!.title)
        assertEquals(HomeShelfType.SERVER_SECTION, shelf.type)
        assertEquals(listOf("b1", "b2"), shelf.bookIds)

        val shelves = vm.homeShelves.first { s -> s.any { it.id == "server_sec_sh" } }
        assertTrue(shelves.any { it.id == "server_sec_sh" })
    }

    @Test
    fun `old home shelves JSON without selectedIds decodes with emptyList default`() {
        val oldJson = """
            [
                {"id":"series","title":"Series","type":"SERIES","isEnabled":true},
                {"id":"collections","title":"Collections","type":"COLLECTIONS","isEnabled":true}
            ]
        """.trimIndent()

        val decoded = org.hello.reader.core.network.NetworkModule.json.decodeFromString<List<HomeShelfConfig>>(oldJson)
        assertEquals(2, decoded.size)
        assertEquals(emptyList<String>(), decoded[0].selectedIds)
        assertEquals(emptyList<String>(), decoded[1].selectedIds)
    }

    @Test
    fun `home shelves persistence is scoped per server profile`() = runTest {
        val jsonA = """[{"id":"shelfA","title":"Shelf A","type":"SERIES","isEnabled":true}]"""
        val jsonB = """[{"id":"shelfB","title":"Shelf B","type":"COLLECTIONS","isEnabled":true}]"""

        preferences.saveHomeShelvesJson(jsonA, "serverA_userA")
        preferences.saveHomeShelvesJson(jsonB, "serverB_userB")

        assertEquals(jsonA, preferences.homeShelvesJson("serverA_userA").first())
        assertEquals(jsonB, preferences.homeShelvesJson("serverB_userB").first())

        // Default/fallback for unspecified profile
        val defaultJson = """[{"id":"default","title":"Default","type":"RECENTLY_ADDED","isEnabled":true}]"""
        preferences.saveHomeShelvesJson(defaultJson, null)
        assertEquals(defaultJson, preferences.homeShelvesJson(null).first())
        // Profile with no custom shelves falls back to default
        assertEquals(defaultJson, preferences.homeShelvesJson("unknown_profile").first())
    }

    @Test
    fun `setPreferredMediaType ignores changing readaloud to ebook or audio only`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        val b1 = testBook("b1", serverId = "s1", userId = "u1", title = "Readaloud Book")
            .copy(mediaType = BookMediaType.READALOUD, hasReadaloud = true)
        bookDao.upsertBooks(listOf(BookEntity.fromDomain(b1, "u1")))

        val vm = createViewModel()
        backgroundScope.launch(testDispatcher) { vm.state.collect {} }

        val book = vm.state.first { it.books.isNotEmpty() }.books.first { it.id == "b1" }
        assertEquals(BookMediaType.READALOUD, book.mediaType)

        vm.setPreferredMediaType(book, BookMediaType.EBOOK_ONLY)
        testDispatcher.scheduler.advanceUntilIdle()

        val updated = bookDao.getBooksSnapshot("s1", "u1").first { it.id == "b1" }
        assertEquals(BookMediaType.READALOUD, updated.mediaType)

        vm.setPreferredMediaType(book, BookMediaType.AUDIO_ONLY)
        testDispatcher.scheduler.advanceUntilIdle()

        val updated2 = bookDao.getBooksSnapshot("s1", "u1").first { it.id == "b1" }
        assertEquals(BookMediaType.READALOUD, updated2.mediaType)
    }

    @Test
    fun `downloadedReadaloudIds only includes books with downloaded readaloud format`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        val b1 = testBook("b1", serverId = "s1", userId = "u1", title = "Downloaded Readaloud")
            .copy(mediaType = BookMediaType.READALOUD, downloadStatus = DownloadStatus.DOWNLOADED, localFilePath = "/path/b1.epub")
        val b2 = testBook("b2", serverId = "s1", userId = "u1", title = "Offered Only Readaloud")
            .copy(mediaType = BookMediaType.EBOOK_ONLY, hasReadaloud = true, downloadStatus = DownloadStatus.NOT_DOWNLOADED)
        val b3 = testBook("b3", serverId = "s1", userId = "u1", title = "Downloaded Ebook Only")
            .copy(mediaType = BookMediaType.EBOOK_ONLY, downloadStatus = DownloadStatus.DOWNLOADED, localFilePath = "/path/b3.epub")
        bookDao.upsertBooks(listOf(BookEntity.fromDomain(b1, "u1"), BookEntity.fromDomain(b2, "u1"), BookEntity.fromDomain(b3, "u1")))

        val vm = createViewModel()
        backgroundScope.launch(testDispatcher) { vm.state.collect {} }

        val state = vm.state.first { it.books.size == 3 }
        assertTrue(state.downloadedReadaloudIds.contains("b1"))
        assertFalse(state.downloadedReadaloudIds.contains("b2"))
        assertFalse(state.downloadedReadaloudIds.contains("b3"))
    }
}
