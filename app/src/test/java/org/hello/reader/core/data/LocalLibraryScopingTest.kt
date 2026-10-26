package org.hello.reader.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.hello.reader.core.database.entity.BookEntity
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.DownloadStatus
import org.hello.reader.core.model.HighlightColor
import org.hello.reader.core.model.LocalLibrary
import org.hello.reader.core.model.SyncCheckpoint
import org.hello.reader.core.network.NetworkModule
import org.hello.reader.core.network.ServerAuthInterceptor
import org.hello.reader.core.security.SecurityVaultImpl
import org.hello.reader.testing.FakeAnnotationDao
import org.hello.reader.testing.FakeBookDao
import org.hello.reader.testing.FakeSyncCheckpointDao
import org.hello.reader.testing.InMemorySharedPreferences
import org.hello.reader.testing.profile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Imported books must keep their progress and notes in the local scope, whatever server is active. */
class LocalLibraryScopingTest {

    private lateinit var server: MockWebServer
    private lateinit var vault: SecurityVaultImpl
    private lateinit var bookDao: FakeBookDao
    private lateinit var checkpointDao: FakeSyncCheckpointDao
    private lateinit var books: BookRepository
    private lateinit var annotations: AnnotationRepository

    private val localId = LocalLibrary.ID_PREFIX + "abc123"

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        vault = SecurityVaultImpl(InMemorySharedPreferences(), Dispatchers.Unconfined)
        bookDao = FakeBookDao()
        checkpointDao = FakeSyncCheckpointDao()
        val client = NetworkModule.okHttpClient(ServerAuthInterceptor({ vault.activeProfile.value }) {}, enableLogging = false)
        books = BookRepository(NetworkModule.storytellerApi(client), bookDao, checkpointDao, vault, clock = { 5_000L })
        annotations = AnnotationRepository(FakeAnnotationDao(), vault, clock = { 1L }, idGenerator = { "a-" + System.nanoTime() })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private suspend fun seedLocalBook() {
        val book = Book(
            id = localId, serverId = LocalLibrary.SERVER_ID, userId = LocalLibrary.USER_ID, title = "Imported",
            authors = listOf("Me"), mediaType = BookMediaType.EBOOK_ONLY, downloadStatus = DownloadStatus.DOWNLOADED,
            localFilePath = "/data/books/$localId.epub",
        )
        bookDao.upsertBooks(listOf(BookEntity.fromDomain(book, LocalLibrary.USER_ID)))
    }

    private fun localCheckpoint(percent: Float) = SyncCheckpoint(
        bookId = localId, serverId = LocalLibrary.SERVER_ID, userId = LocalLibrary.USER_ID, readingCfi = null,
        chapterIndex = 0, chapterTitle = null, audioPositionMs = 0L, totalDurationMs = 0L,
        progressPercent = percent, activeSentenceId = "p3",
    )

    private suspend fun connectServer() =
        vault.saveProfile(profile("s1", baseUrl = server.url("/").toString().trimEnd('/'), userId = "u1", isActive = true))

    @Test
    fun `local progress is saved locally and never pushed or pulled, even with a server connected`() = runTest {
        seedLocalBook()
        connectServer()

        books.saveCheckpoint(localCheckpoint(42f))
        val synced = books.syncBookPosition(localId).getOrThrow()

        assertEquals(0, server.requestCount)
        val saved = checkpointDao.getCheckpoint(localId, LocalLibrary.SERVER_ID, LocalLibrary.USER_ID)!!
        assertEquals(false, saved.isDirty)
        assertEquals(42f, synced!!.progressPercent, 0f)
    }

    @Test
    fun `library progress includes local books alongside the server's`() = runTest {
        seedLocalBook()
        books.saveCheckpoint(localCheckpoint(10f))
        assertEquals(10f, books.observeCheckpoints().first().getValue(localId).progressPercent, 0f)

        connectServer()
        assertEquals(10f, books.observeCheckpoints().first().getValue(localId).progressPercent, 0f)
    }

    @Test
    fun `opening a local book updates recently opened with or without a server`() = runTest {
        seedLocalBook()
        books.markBookOpened(localId)
        assertEquals(5_000L, bookDao.findBookById(localId)!!.lastOpenedAt)
        connectServer()
        books.markBookOpened(localId)
        assertNotNull(books.observeContinueReading().first().firstOrNull { it.id == localId })
    }

    @Test
    fun `local highlights save without a server and survive connecting, editing and deleting`() = runTest {
        val saved = annotations.addHighlight(localId, selectedText = "hello", color = HighlightColor.BLUE, elementId = "p1", endOffset = 5)
        assertNotNull("must save with no server connected", saved)
        assertEquals(LocalLibrary.SERVER_ID, saved!!.serverId)

        connectServer()
        val visible = annotations.observeAnnotations(localId).first()
        assertEquals(listOf(saved.id), visible.map { it.id })

        annotations.updateNote(saved.id, "edited while connected")
        assertEquals("edited while connected", annotations.observeAnnotations(localId).first().single().note)

        annotations.deleteAnnotation(saved.id)
        assertTrue(annotations.observeAnnotations(localId).first().isEmpty())
    }

    @Test
    fun `notebook shows local annotations always and only the active server's others`() = runTest {
        val local = annotations.addHighlight(localId, selectedText = "local", elementId = "p1", endOffset = 5)!!
        assertEquals(listOf(local.id), annotations.observeAllAnnotations().first().map { it.id })

        connectServer()
        val onServer = annotations.addBookmark("server-book", chapterIndex = 2)!!
        assertEquals(setOf(local.id, onServer.id), annotations.observeAllAnnotations().first().map { it.id }.toSet())

        vault.saveProfile(profile("s2", baseUrl = "https://other.example.com", userId = "u2", isActive = true))
        assertEquals(listOf(local.id), annotations.observeAllAnnotations().first().map { it.id })
    }

    @Test
    fun `server-book annotations still need a server and stay in its scope`() = runTest {
        assertNull(annotations.addBookmark("server-book", chapterIndex = 0))
        connectServer()
        val saved = annotations.addBookmark("server-book", chapterIndex = 0)!!
        assertEquals("s1", saved.serverId)
    }

    @Test
    fun `server-book progress made while disconnected stays dirty so it syncs later`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val checkpoint = localCheckpoint(5f).copy(bookId = "server-book", serverId = "s1", userId = "u1")
        books.saveCheckpoint(checkpoint)
        assertEquals(true, checkpointDao.getCheckpoint("server-book", "s1", "u1")!!.isDirty)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `local book reading status and rating update locally even when a server is connected`() = runTest {
        seedLocalBook()
        connectServer()

        val status = org.hello.reader.core.model.ReadingStatusItem(
            uuid = "uuid-read",
            name = "read",
            label = "Read",
            isServerStatus = true,
        )
        assertTrue(books.updateBookReadingStatus(localId, status).isSuccess)
        books.setBookRating(localId, 4.5f)

        assertEquals(0, server.requestCount)
        val updated = bookDao.getBookById(localId, LocalLibrary.SERVER_ID, LocalLibrary.USER_ID)!!
        assertEquals("read", updated.readingStatus)
        assertEquals(4.5f, updated.rating!!, 0.001f)
    }
}
