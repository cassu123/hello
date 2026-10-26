package org.hello.reader.core.data

import org.hello.reader.core.database.entity.SyncCheckpointEntity
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.DownloadStatus
import org.hello.reader.core.model.SyncCheckpoint
import org.hello.reader.core.network.NetworkModule
import org.hello.reader.core.network.ServerAuthInterceptor
import org.hello.reader.core.security.SecurityVaultImpl
import org.hello.reader.testing.FakeBookDao
import org.hello.reader.testing.FakeSyncCheckpointDao
import org.hello.reader.testing.InMemorySharedPreferences
import org.hello.reader.testing.profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BookRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var vault: SecurityVaultImpl
    private lateinit var bookDao: FakeBookDao
    private lateinit var checkpointDao: FakeSyncCheckpointDao
    private lateinit var seriesDao: org.hello.reader.testing.FakeSeriesDao
    private lateinit var collectionDao: org.hello.reader.testing.FakeCollectionDao
    private lateinit var repository: BookRepository

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        vault = SecurityVaultImpl(InMemorySharedPreferences(), Dispatchers.Unconfined)
        bookDao = FakeBookDao()
        checkpointDao = FakeSyncCheckpointDao()
        seriesDao = org.hello.reader.testing.FakeSeriesDao(bookDao)
        collectionDao = org.hello.reader.testing.FakeCollectionDao(bookDao)
        val client = NetworkModule.okHttpClient(
            ServerAuthInterceptor({ vault.activeProfile.value }) {},
            enableLogging = false,
        )
        repository = BookRepository(
            api = NetworkModule.storytellerApi(client),
            bookDao = bookDao,
            checkpointDao = checkpointDao,
            vault = vault,
            seriesDao = seriesDao,
            collectionDao = collectionDao,
            clock = { 1_000L },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private val baseUrl get() = server.url("/").toString().trimEnd('/')

    private fun jsonResponse(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `reading status and rating go to the book uuid, and null status is rejected`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        val bookUuid = "0b6f2c1e-8a4d-4c3b-9f1e-2d7a5b8c9e01"
        val reading = org.hello.reader.core.model.ReadingStatusItem(
            uuid = "5a1d0c2b-3e4f-4a5b-8c6d-7e8f9a0b1c2d", name = "reading", label = "Reading", isServerStatus = true,
        )
        server.enqueue(jsonResponse("""{"id":42,"uuid":"$bookUuid","title":"Blood Meridian"}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(jsonResponse("{}"))

        assertTrue(repository.updateBookReadingStatus("42", reading).isSuccess)
        assertFalse(repository.updateBookReadingStatus("42", null).isSuccess)
        repository.setBookRating("42", 4f)

        assertEquals("/api/v2/books/42", server.takeRequest().path)
        val set = server.takeRequest()
        assertEquals("/api/v2/books/$bookUuid/status", set.path)
        assertEquals("""{"status":"${reading.uuid}"}""", set.body.readUtf8())
        // The uuid is looked up once per book, then reused.
        assertEquals("/api/v2/books/$bookUuid/rating", server.takeRequest().path)
    }

    @Test
    fun `healInvalidReadingStatuses heals stuck none statuses to to_read`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        val b1 = org.hello.reader.core.database.entity.BookEntity(
            id = "b1", serverId = "s1", userId = "u1", title = "Stuck Book", authors = "Author",
            narrators = "", coverUrl = null, description = null, mediaType = BookMediaType.EBOOK_ONLY,
            durationSeconds = 0L, totalChapters = 1, downloadStatus = DownloadStatus.NOT_DOWNLOADED, downloadProgress = 0f,
            localFilePath = null, localAudioPath = null, sha256Checksum = null, tags = "", createdAt = 0L,
            lastOpenedAt = null, hasReadaloud = false, hasEbook = true, hasAudiobook = false,
            readingStatus = "none", readingStatusLabel = "No status",
        )
        bookDao.upsertBooks(listOf(b1))

        val healed = bookDao.healInvalidReadingStatuses()
        assertEquals(1, healed)

        val updated = bookDao.getBookById("b1", "s1", "u1")
        assertEquals("to_read", updated?.readingStatus)
        assertEquals("To read", updated?.readingStatusLabel)
    }

    @Test
    fun `refreshCatalog parses ARCHITECTURE paginated envelope and scopes to active server`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        server.enqueue(
            jsonResponse(
                """
                {
                  "items": [
                    {"id":"b1","title":"Readaloud Book","authors":["River Song"],"has_audio":true,"has_epub":true,"is_aligned":true,"duration_seconds":3600},
                    {"id":"b2","title":"Audio Only","authors":["The Doctor"],"has_audio":true,"has_epub":false,"is_aligned":false,"duration_seconds":1800},
                    {"id":"b3","title":"Ebook Only","authors":["Amy Pond"],"has_audio":false,"has_epub":true,"is_aligned":false}
                  ],
                  "total": 3,
                  "page": 1
                }
                """.trimIndent(),
            ),
        )

        val result = repository.refreshCatalog()

        assertTrue(result.isSuccess)
        val books = repository.observeLibrary().first()
        assertEquals(3, books.size)
        assertEquals(BookMediaType.READALOUD, books.first { it.id == "b1" }.mediaType)
        assertEquals(BookMediaType.AUDIO_ONLY, books.first { it.id == "b2" }.mediaType)
        assertEquals(BookMediaType.EBOOK_ONLY, books.first { it.id == "b3" }.mediaType)
    }

    @Test
    fun `refreshCatalog parses Storyteller v2 JSON array fallback`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        server.enqueue(
            jsonResponse(
                """
                [
                  {
                    "uuid": "uuid-1",
                    "title": "Silence in the Library",
                    "authors": [{"name": "Steven Moffat"}],
                    "narrators": [{"name": "Alex Kingston"}],
                    "tags": [{"name": "Sci-Fi"}],
                    "readaloud": {"filepath": "/books/silence.epub", "missing": false}
                  }
                ]
                """.trimIndent(),
            ),
        )

        val result = repository.refreshCatalog()

        assertTrue(result.isSuccess)
        val books = repository.observeLibrary().first()
        assertEquals(1, books.size)
        assertEquals("uuid-1", books.single().id)
        assertEquals(BookMediaType.READALOUD, books.single().mediaType)
        assertEquals(listOf("Steven Moffat"), books.single().authors)
        assertEquals(listOf("Sci-Fi"), books.single().tags)
    }

    @Test
    fun `refreshCatalog parses Storyteller v2 books object envelope with object authors and float duration`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        server.enqueue(
            jsonResponse(
                """
                {
                  "books": [
                    {
                      "id": "book-v2-1",
                      "title": "A Good Man Goes to War",
                      "authors": [{"id": "a1", "name": "Steven Moffat"}],
                      "narrators": [{"id": "n1", "name": "Arthur Darvill"}],
                      "duration": 7200.5,
                      "isAligned": true,
                      "hasAudio": true,
                      "hasEpub": true,
                      "tags": [{"name": "Doctor Who"}]
                    }
                  ],
                  "total": 1,
                  "page": 1,
                  "pageSize": 50
                }
                """.trimIndent(),
            ),
        )

        val result = repository.refreshCatalog()

        assertTrue(result.isSuccess)
        val books = repository.observeLibrary().first()
        assertEquals(1, books.size)
        val book = books.single()
        assertEquals("book-v2-1", book.id)
        assertEquals("A Good Man Goes to War", book.title)
        assertEquals(listOf("Steven Moffat"), book.authors)
        assertEquals(listOf("Arthur Darvill"), book.narrators)
        assertEquals(BookMediaType.READALOUD, book.mediaType)
        assertEquals(7200L, book.durationSeconds)
        assertEquals(listOf("Doctor Who"), book.tags)
    }

    @Test
    fun `refreshCatalog calls v2 books first and falls back to v1 api books when v2 returns 404`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
        server.enqueue(
            jsonResponse(
                """
                {
                  "items": [
                    {
                      "id": "v1-fallback-1",
                      "title": "The Time of the Doctor",
                      "authors": ["Matt Smith"],
                      "has_audio": false,
                      "has_epub": true
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        val result = repository.refreshCatalog()

        assertTrue(result.isSuccess)
        val books = repository.observeLibrary().first()
        assertEquals(1, books.size)
        assertEquals("v1-fallback-1", books.single().id)
        assertEquals(2, server.requestCount)
        assertEquals("/api/v2/books", server.takeRequest().path)
        assertEquals("/api/books", server.takeRequest().path)
    }

    @Test
    fun `refreshCatalog gracefully handles empty books envelope without throwing`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        server.enqueue(jsonResponse("""{"books":[],"total":0}"""))

        val result = repository.refreshCatalog()

        assertTrue(result.isSuccess)
        val books = repository.observeLibrary().first()
        assertTrue(books.isEmpty())
    }

    @Test
    fun `switching active server isolates library flows without data bleed`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        vault.saveProfile(profile("s2", baseUrl = baseUrl, userId = "u2", isActive = false))

        server.enqueue(
            jsonResponse("""{"items":[{"id":"b1","title":"Server 1 Book","authors":["A"],"has_audio":false,"has_epub":true,"is_aligned":false}],"total":1,"page":1}"""),
        )
        repository.refreshCatalog()
        assertEquals(listOf("b1"), repository.observeLibrary().first().map { it.id })

        vault.setActiveProfile("s2")
        assertTrue(repository.observeLibrary().first().isEmpty())
    }

    @Test
    fun `syncBookPosition emits SyncConflict when unsynced local and newer remote differ by over 60 seconds`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        val localCheckpoint = SyncCheckpoint(
            bookId = "b1",
            serverId = "s1",
            userId = "u1",
            readingCfi = "epubcfi(/6/2!/4/2)",
            chapterIndex = 1,
            chapterTitle = "Chapter 1",
            audioPositionMs = 10_000L,
            totalDurationMs = 600_000L,
            progressPercent = 1.6f,
            activeSentenceId = "s1",
            updatedAt = 500L,
            isDirty = true,
        )
        checkpointDao.upsertCheckpoint(SyncCheckpointEntity.fromDomain(localCheckpoint))

        // Remote is at 120_000ms (110s difference > 60s threshold)
        server.enqueue(
            jsonResponse(
                """{"book_id":"b1","reading_cfi":"epubcfi(/6/4!/4/10)","audio_position_ms":120000,"total_duration_ms":600000,"chapter_index":2,"progress_percent":20.0,"timestamp":900}""",
            ),
        )

        repository.syncBookPosition("b1")

        val conflicts = repository.pendingConflicts.value
        assertEquals(1, conflicts.size)
        assertEquals(10_000L, conflicts.single().local.audioPositionMs)
        assertEquals(120_000L, conflicts.single().remote.audioPositionMs)

        // Resolve in favor of remote position
        val resolved = repository.resolveConflict("b1", useRemote = true)
        assertEquals(120_000L, resolved?.audioPositionMs)
        assertTrue(repository.pendingConflicts.value.isEmpty())
    }

    @Test
    fun `syncBookPosition follows a newer server position without asking when local is already synced`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        val local = SyncCheckpoint(
            bookId = "b1",
            serverId = "s1",
            userId = "u1",
            readingCfi = "chapter01.xhtml",
            chapterIndex = 0,
            chapterTitle = "Chapter 1",
            audioPositionMs = 870_000L,
            totalDurationMs = 46_000_000L,
            progressPercent = 1f,
            activeSentenceId = "s-3",
            updatedAt = 500L,
            isDirty = false,
        )
        checkpointDao.upsertCheckpoint(SyncCheckpointEntity.fromDomain(local))
        server.enqueue(
            jsonResponse(
                """{"locator":{"href":"chapter09.xhtml","locations":{"fragments":["s-900"],"totalProgression":0.4}},"timestamp":1729639000}""",
            ),
        )

        val result = repository.syncBookPosition("b1").getOrNull()

        assertTrue(repository.pendingConflicts.value.isEmpty())
        assertEquals("s-900", result?.activeSentenceId)
    }

    @Test
    fun `a resolved conflict is not asked about again on the next sync`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        val local = SyncCheckpoint(
            bookId = "b1",
            serverId = "s1",
            userId = "u1",
            readingCfi = "chapter01.xhtml",
            chapterIndex = 0,
            chapterTitle = "Chapter 1",
            audioPositionMs = 0L,
            totalDurationMs = 0L,
            progressPercent = 1f,
            activeSentenceId = "s-3",
            updatedAt = 500L,
            isDirty = true,
        )
        checkpointDao.upsertCheckpoint(SyncCheckpointEntity.fromDomain(local))
        val remoteBody = """{"locator":{"href":"chapter09.xhtml","locations":{"fragments":["s-900"],"totalProgression":0.4}},"timestamp":1729639000}"""
        server.enqueue(jsonResponse(remoteBody))
        repository.syncBookPosition("b1")
        assertEquals(1, repository.pendingConflicts.value.size)

        repository.resolveConflict("b1", useRemote = true)
        server.enqueue(jsonResponse(remoteBody))
        repository.syncBookPosition("b1")

        assertTrue(repository.pendingConflicts.value.isEmpty())
    }

    @Test
    fun `observeLibrary returns local books when activeProfile is null and alongside server books when active`() = runTest {
        val localBook = org.hello.reader.core.database.entity.BookEntity(
            id = "local-1",
            serverId = "local",
            userId = "local",
            title = "My Local EPUB",
            authors = "Author",
            narrators = "",
            coverUrl = null,
            description = null,
            mediaType = BookMediaType.EBOOK_ONLY,
            durationSeconds = 0L,
            totalChapters = 10,
            downloadStatus = org.hello.reader.core.model.DownloadStatus.DOWNLOADED,
            downloadProgress = 1f,
            localFilePath = "/path/to/local.epub",
            localAudioPath = null,
            sha256Checksum = null,
            tags = "",
            createdAt = 100L,
            lastOpenedAt = null,
        )
        bookDao.upsertBooks(listOf(localBook))

        // When no active profile, observeLibrary surfaces the local book
        val localBooks = repository.observeLibrary().first()
        assertEquals(listOf("local-1"), localBooks.map { it.id })

        // When active profile s1 is set, observeLibrary surfaces both server books and local books
        server.enqueue(
            jsonResponse(
                """{"items":[{"id":"server-1","title":"Remote Book","authors":["Remote Author"],"has_audio":false,"has_epub":true,"is_aligned":false}],"total":1,"page":1}""",
            ),
        )
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        repository.refreshCatalog()

        val allBooks = repository.observeLibrary().first()
        assertTrue(allBooks.any { it.id == "local-1" })
        assertTrue(allBooks.any { it.id == "server-1" })
    }

    @Test
    fun `syncBookPosition pulls position from v2 positions endpoint and adopts newer server position`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))

        server.enqueue(
            jsonResponse(
                """
                {
                  "locator": {
                    "href": "OEBPS/Text/chapter02.xhtml",
                    "locations": {
                      "fragments": ["sentence_101"],
                      "totalProgression": 0.42
                    }
                  },
                  "timestamp": 1729639000
                }
                """.trimIndent(),
            ),
        )

        val result = repository.syncBookPosition("b1")

        assertTrue(result.isSuccess)
        val checkpoint = result.getOrNull()
        assertNotNull(checkpoint)
        assertEquals("sentence_101", checkpoint?.activeSentenceId)
        assertEquals("OEBPS/Text/chapter02.xhtml", checkpoint?.readingCfi)
        assertEquals(42.0f, checkpoint?.progressPercent ?: 0f, 0.01f)
        assertEquals(1729639000000L, checkpoint?.updatedAt)

        val request = server.takeRequest()
        assertEquals("/api/v2/books/b1/positions", request.path)
    }

    @Test
    fun `saveCheckpoint pushes Readium locator to v2 positions and handles 409 conflict by adopting remote position`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))

        val local = SyncCheckpoint(
            bookId = "b1",
            serverId = "s1",
            userId = "u1",
            readingCfi = "chapter01.xhtml",
            chapterIndex = 0,
            chapterTitle = "Chapter 1",
            audioPositionMs = 5_000L,
            totalDurationMs = 100_000L,
            progressPercent = 5.0f,
            activeSentenceId = "s_5",
            updatedAt = 1000L,
            isDirty = true,
        )

        // 1. POST returns 409 Conflict
        server.enqueue(MockResponse().setResponseCode(409).setBody("Conflict"))
        // 2. GET positions returns newer remote position
        server.enqueue(
            jsonResponse(
                """
                {
                  "locator": {
                    "href": "chapter01.xhtml",
                    "locations": {
                      "fragments": ["s_8"],
                      "totalProgression": 0.08
                    }
                  },
                  "timestamp": 2000
                }
                """.trimIndent(),
            ),
        )

        repository.saveCheckpoint(local)

        val postRequest = server.takeRequest()
        assertEquals("/api/v2/books/b1/positions", postRequest.path)
        assertEquals("POST", postRequest.method)
        val postBody = postRequest.body.readUtf8()
        assertTrue(postBody.contains(""""href":"chapter01.xhtml""""))
        assertTrue(postBody.contains(""""fragments":["s_5"]"""))

        val getRequest = server.takeRequest()
        assertEquals("/api/v2/books/b1/positions", getRequest.path)
        assertEquals("GET", getRequest.method)

        // Remote position with timestamp 2000 > local 1000 was adopted
        val adopted = checkpointDao.getCheckpoint("b1", "s1", "u1")
        assertNotNull(adopted)
        assertEquals("s_8", adopted?.activeSentenceId)
        assertEquals(2000000L, adopted?.updatedAt)
    }

    @Test
    fun `v2 catalog refresh preserves downloaded status and local file paths`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))

        val existingDownloaded = org.hello.reader.core.database.entity.BookEntity(
            id = "101",
            serverId = "s1",
            userId = "u1",
            title = "Existing Downloaded Book",
            authors = "Author",
            narrators = "",
            coverUrl = "http://cover.png",
            description = "Desc",
            mediaType = BookMediaType.READALOUD,
            durationSeconds = 1200L,
            totalChapters = 5,
            downloadStatus = DownloadStatus.DOWNLOADED,
            downloadProgress = 1.0f,
            localFilePath = "/storage/books/101/package.epub",
            localAudioPath = null,
            sha256Checksum = "sha123",
            tags = "",
            createdAt = 100L,
            lastOpenedAt = 500L,
            hasReadaloud = true,
            hasEbook = true,
            hasAudiobook = true,
        )
        bookDao.upsertBooks(listOf(existingDownloaded))

        server.enqueue(
            jsonResponse(
                """
                [
                  {
                    "id": 101,
                    "uuid": "u-101",
                    "title": "Existing Downloaded Book Updated",
                    "authors": [{"name": "Author"}],
                    "ebook": { "filepath": "book.epub", "missing": 0 },
                    "audiobook": { "filepath": "audio.m4b", "missing": 0, "duration": 1200.0 },
                    "readaloud": { "filepath": "aligned.epub", "missing": 0, "status": "ALIGNED" }
                  }
                ]
                """.trimIndent(),
            ),
        )

        repository.refreshCatalog()

        val saved = bookDao.getBookById("101", "s1", "u1")
        assertNotNull(saved)
        assertEquals(DownloadStatus.DOWNLOADED, saved?.downloadStatus)
        assertEquals("/storage/books/101/package.epub", saved?.localFilePath)
        assertEquals(500L, saved?.lastOpenedAt)
        assertEquals(BookMediaType.READALOUD, saved?.mediaType)
    }

    @Test
    fun `saveCheckpoint pushes audiobook locator with track href, audio mime type, and t= fragment`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))

        val audioCheckpoint = SyncCheckpoint(
            bookId = "audio_1",
            serverId = "s1",
            userId = "u1",
            readingCfi = "tracks/part2.mp3",
            chapterIndex = 1,
            chapterTitle = "Part 2",
            audioPositionMs = 65_400L,
            totalDurationMs = 300_000L,
            progressPercent = 21.8f,
            activeSentenceId = "t=65.400",
            updatedAt = 1729639555123L,
            isDirty = true,
        )

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        repository.saveCheckpoint(audioCheckpoint)

        val request = server.takeRequest()
        assertEquals("/api/v2/books/audio_1/positions", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains(""""href":"tracks/part2.mp3""""))
        assertTrue(body.contains(""""type":"audio/mpeg""""))
        assertTrue(body.contains(""""fragments":["t=65.400"]"""))
        assertTrue(body.contains(""""timestamp":1000"""))
    }

    @Test
    fun `syncBookPosition parses Storyteller mobile Readium locator fixture accurately`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))

        // Fixture from Storyteller mobile test (OPS/c01.xhtml, c01-sentence1, totalProgression 0.2)
        server.enqueue(
            jsonResponse(
                """
                {
                  "locator": {
                    "href": "OPS/c01.xhtml",
                    "type": "application/xhtml+xml",
                    "locations": {
                      "fragments": ["c01-sentence1"],
                      "progression": 0.15,
                      "totalProgression": 0.2
                    }
                  },
                  "timestamp": 1729639123456
                }
                """.trimIndent(),
            ),
        )

        val result = repository.syncBookPosition("book_fixture")
        assertTrue(result.isSuccess)
        val checkpoint = result.getOrNull()
        assertNotNull(checkpoint)
        assertEquals("c01-sentence1", checkpoint?.activeSentenceId)
        assertEquals("OPS/c01.xhtml", checkpoint?.readingCfi)
        assertEquals(20.0f, checkpoint?.progressPercent ?: 0f, 0.01f)
        assertEquals(1729639123456L, checkpoint?.updatedAt)
    }

    @Test
    fun `refreshCatalog persists series and collections into Room and offline flows emit them`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))

        val catalogJson = """
            [
              {
                "id": "book-1",
                "title": "Leviathan Wakes",
                "authors": [{"name": "James S.A. Corey"}],
                "ebook": {"filepath": "lw.epub", "missing": 0},
                "series": [
                  {
                    "uuid": "series-expanse",
                    "name": "The Expanse",
                    "position": 1,
                    "featured": 1
                  }
                ],
                "collections": [
                  {
                    "uuid": "coll-favs",
                    "name": "Favorites",
                    "icon": "star",
                    "color": "#FFD700",
                    "public": true
                  }
                ]
              },
              {
                "id": "book-2",
                "title": "Caliban's War",
                "authors": [{"name": "James S.A. Corey"}],
                "ebook": {"filepath": "cw.epub", "missing": 0},
                "series": [
                  {
                    "uuid": "series-expanse",
                    "name": "The Expanse",
                    "position": 2,
                    "featured": 0
                  }
                ],
                "collections": [
                  {
                    "uuid": "coll-favs",
                    "name": "Favorites",
                    "public": true
                  }
                ]
              }
            ]
        """.trimIndent()

        server.enqueue(jsonResponse(catalogJson))

        val result = repository.refreshCatalog()
        assertTrue(result.isSuccess)

        // Offline observations from Room
        val seriesList = repository.observeSeries().first()
        assertEquals(1, seriesList.size)
        val series = seriesList.single()
        assertEquals("series-expanse", series.uuid)
        assertEquals("The Expanse", series.name)

        val seriesDetail = repository.observeSeriesDetail("series-expanse").first()
        assertNotNull(seriesDetail)
        assertEquals(2, seriesDetail!!.books.size)
        assertEquals("book-1", seriesDetail.books[0].book.id)
        assertEquals("Book 1", seriesDetail.books[0].positionLabel)
        assertEquals("book-2", seriesDetail.books[1].book.id)
        assertEquals("Book 2", seriesDetail.books[1].positionLabel)

        val collList = repository.observeCollections().first()
        assertEquals(1, collList.size)
        val coll = collList.single()
        assertEquals("coll-favs", coll.uuid)
        assertEquals("Favorites", coll.name)

        val collDetail = repository.observeCollectionDetail("coll-favs").first()
        assertNotNull(collDetail)
        assertEquals(1, collDetail!!.seriesGroups.size)
        assertEquals("The Expanse", collDetail.seriesGroups[0].series.name)
        assertEquals(2, collDetail.seriesGroups[0].books.size)

        val book1Series = repository.observeSeriesForBook("book-1").first()
        assertEquals(1, book1Series.size)
        assertEquals("The Expanse", book1Series.single().seriesName)
        assertEquals("Book 1", book1Series.single().positionLabel)

        val book1Colls = repository.observeCollectionsForBook("book-1").first()
        assertEquals(1, book1Colls.size)
        assertEquals("Favorites", book1Colls.single().name)
    }

    @Test
    fun `MIGRATION_3_4 creates series, collections, book_series, book_collections and preserves existing tables`() {
        val executedSql = mutableListOf<String>()
        val mockDb = java.lang.reflect.Proxy.newProxyInstance(
            androidx.sqlite.db.SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedSql.add(args[0] as String)
            }
            null
        } as androidx.sqlite.db.SupportSQLiteDatabase

        org.hello.reader.core.database.HelloDatabase.MIGRATION_3_4.migrate(mockDb)

        assertTrue(executedSql.any { it.contains("CREATE TABLE IF NOT EXISTS `series`") })
        assertTrue(executedSql.any { it.contains("CREATE TABLE IF NOT EXISTS `collections`") })
        assertTrue(executedSql.any { it.contains("CREATE TABLE IF NOT EXISTS `book_series`") })
        assertTrue(executedSql.any { it.contains("CREATE TABLE IF NOT EXISTS `book_collections`") })
        assertTrue(executedSql.any { it.contains("CREATE INDEX IF NOT EXISTS `index_series_server_id`") })
        assertTrue(executedSql.any { it.contains("CREATE INDEX IF NOT EXISTS `index_collections_server_id`") })
        assertTrue(executedSql.any { it.contains("CREATE INDEX IF NOT EXISTS `index_book_series_server_id_series_uuid`") })
        assertTrue(executedSql.any { it.contains("CREATE INDEX IF NOT EXISTS `index_book_collections_server_id_collection_uuid`") })
    }

    @Test
    fun `pushCheckpoint sends within-chapter progression and totalProgression`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        server.enqueue(jsonResponse("""{"success": true}"""))

        val checkpoint = SyncCheckpoint(
            bookId = "b1",
            serverId = "s1",
            userId = "u1",
            readingCfi = "chapter01.xhtml",
            chapterIndex = 0,
            chapterTitle = "Chapter 1",
            audioPositionMs = 0L,
            totalDurationMs = 0L,
            progressPercent = 50f,
            activeSentenceId = "s_5",
            updatedAt = 1000L,
            isDirty = true,
            chapterProgression = 0.25f,
        )

        repository.saveCheckpoint(checkpoint)

        val request = server.takeRequest()
        assertEquals("/api/v2/books/b1/positions", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains(""""progression":0.25"""))
        assertTrue(body.contains(""""totalProgression":0.5"""))
    }

    @Test
    fun `MIGRATION_5_6 adds chapter_progression column to sync_checkpoints`() {
        val executedSql = mutableListOf<String>()
        val mockDb = java.lang.reflect.Proxy.newProxyInstance(
            androidx.sqlite.db.SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                executedSql.add(args[0] as String)
            }
            null
        } as androidx.sqlite.db.SupportSQLiteDatabase

        org.hello.reader.core.database.HelloDatabase.MIGRATION_5_6.migrate(mockDb)

        assertTrue(executedSql.any { it.contains("ALTER TABLE `sync_checkpoints` ADD COLUMN `chapter_progression` REAL") })
    }

    @Test
    fun `dirty checkpoint saved offline persists chapterProgression and sends it on retry`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        // 1. GET /api/v2/books/b1/positions returns 404 (no remote position)
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(404))
        // 2. GET /api/books/b1/sync (v1 fallback) returns 404
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(404))
        // 3. POST /api/v2/books/b1/positions (push dirty local position) returns 200
        server.enqueue(jsonResponse("""{"success": true}"""))

        val entity = org.hello.reader.core.database.entity.SyncCheckpointEntity(
            bookId = "b1",
            serverId = "s1",
            userId = "u1",
            readingCfi = "chapter02.xhtml",
            chapterIndex = 1,
            chapterTitle = "Chapter 2",
            audioPositionMs = 0L,
            totalDurationMs = 0L,
            progressPercent = 40f,
            activeSentenceId = "s_10",
            updatedAt = 1000L,
            isDirty = true,
            chapterProgression = 0.33f,
        )
        checkpointDao.upsertCheckpoint(entity)

        val loaded = checkpointDao.getCheckpoint("b1", "s1", "u1")
        assertNotNull(loaded)
        assertEquals(0.33f, loaded!!.toDomain().chapterProgression!!, 0.001f)

        // Now run syncBookPosition to push the offline checkpoint
        repository.syncBookPosition("b1")

        // First request: v2 GET
        val v2Get = server.takeRequest()
        assertEquals("/api/v2/books/b1/positions", v2Get.path)
        assertEquals("GET", v2Get.method)

        // Second request: v1 GET fallback
        val v1Get = server.takeRequest()
        assertEquals("/api/sync/b1", v1Get.path)
        assertEquals("GET", v1Get.method)

        // Third request: v2 POST push
        val postRequest = server.takeRequest()
        assertEquals("/api/v2/books/b1/positions", postRequest.path)
        assertEquals("POST", postRequest.method)
        val body = postRequest.body.readUtf8()
        assertTrue(body.contains(""""progression":0.33"""))
        assertTrue(body.contains(""""totalProgression":0.4"""))
    }

    @Test
    fun `refreshCatalog clears series and collections when server returns empty lists`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))

        seriesDao.upsertSeries(listOf(org.hello.reader.core.database.entity.SeriesEntity("s1_old", "s1", "Old Series", null, 1)))
        collectionDao.upsertCollections(listOf(org.hello.reader.core.database.entity.CollectionEntity("c1_old", "s1", "Old Collection", null, null, null, true, 1)))

        server.enqueue(jsonResponse("""{"books": []}"""))
        repository.refreshCatalog()

        assertTrue(seriesDao.getSeries("s1", "u1").isEmpty())
        assertTrue(collectionDao.getCollections("s1", "u1").isEmpty())
    }

    @Test
    fun `observeSeries scopes series to user on the same server`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))

        val b1 = org.hello.reader.core.database.entity.BookEntity(
            id = "b1", serverId = "s1", userId = "u1", title = "User 1 Book", authors = "Author",
            narrators = "", coverUrl = null, description = null, mediaType = BookMediaType.EBOOK_ONLY,
            durationSeconds = 0L, totalChapters = 1, downloadStatus = DownloadStatus.NOT_DOWNLOADED, downloadProgress = 0f,
            localFilePath = null, localAudioPath = null, sha256Checksum = null, tags = "", createdAt = 0L,
            lastOpenedAt = null, hasReadaloud = false, hasEbook = true, hasAudiobook = false,
        )
        val b2 = org.hello.reader.core.database.entity.BookEntity(
            id = "b2", serverId = "s1", userId = "u2", title = "User 2 Book", authors = "Author",
            narrators = "", coverUrl = null, description = null, mediaType = BookMediaType.EBOOK_ONLY,
            durationSeconds = 0L, totalChapters = 1, downloadStatus = DownloadStatus.NOT_DOWNLOADED, downloadProgress = 0f,
            localFilePath = null, localAudioPath = null, sha256Checksum = null, tags = "", createdAt = 0L,
            lastOpenedAt = null, hasReadaloud = false, hasEbook = true, hasAudiobook = false,
        )
        bookDao.upsertBooks(listOf(b1, b2))

        seriesDao.upsertSeries(listOf(
            org.hello.reader.core.database.entity.SeriesEntity("s_u1", "s1", "User 1 Series", null, 1),
            org.hello.reader.core.database.entity.SeriesEntity("s_u2", "s1", "User 2 Series", null, 1),
        ))
        seriesDao.upsertBookSeries(listOf(
            org.hello.reader.core.database.entity.BookSeriesCrossRef("b1", "s1", "s_u1", 1.0, false),
            org.hello.reader.core.database.entity.BookSeriesCrossRef("b2", "s1", "s_u2", 1.0, false),
        ))

        // Active profile is user u1:
        val u1Series = repository.observeSeries().first()
        assertEquals(listOf("User 1 Series"), u1Series.map { it.name })

        // Switch to user u2 on same server s1:
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u2", isActive = true))
        val u2Series = repository.observeSeries().first()
        assertEquals(listOf("User 2 Series"), u2Series.map { it.name })
    }

    @Test
    fun `observeCollections scopes collections to user on the same server`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))

        val b1 = org.hello.reader.core.database.entity.BookEntity(
            id = "b1", serverId = "s1", userId = "u1", title = "User 1 Book", authors = "Author",
            narrators = "", coverUrl = null, description = null, mediaType = BookMediaType.EBOOK_ONLY,
            durationSeconds = 0L, totalChapters = 1, downloadStatus = DownloadStatus.NOT_DOWNLOADED, downloadProgress = 0f,
            localFilePath = null, localAudioPath = null, sha256Checksum = null, tags = "", createdAt = 0L,
            lastOpenedAt = null, hasReadaloud = false, hasEbook = true, hasAudiobook = false,
        )
        val b2 = org.hello.reader.core.database.entity.BookEntity(
            id = "b2", serverId = "s1", userId = "u2", title = "User 2 Book", authors = "Author",
            narrators = "", coverUrl = null, description = null, mediaType = BookMediaType.EBOOK_ONLY,
            durationSeconds = 0L, totalChapters = 1, downloadStatus = DownloadStatus.NOT_DOWNLOADED, downloadProgress = 0f,
            localFilePath = null, localAudioPath = null, sha256Checksum = null, tags = "", createdAt = 0L,
            lastOpenedAt = null, hasReadaloud = false, hasEbook = true, hasAudiobook = false,
        )
        bookDao.upsertBooks(listOf(b1, b2))

        collectionDao.upsertCollections(listOf(
            org.hello.reader.core.database.entity.CollectionEntity("c_u1", "s1", "User 1 Collection", null, null, null, false, 1),
            org.hello.reader.core.database.entity.CollectionEntity("c_u2", "s1", "User 2 Collection", null, null, null, false, 1),
        ))
        collectionDao.upsertBookCollections(listOf(
            org.hello.reader.core.database.entity.BookCollectionCrossRef("b1", "s1", "c_u1"),
            org.hello.reader.core.database.entity.BookCollectionCrossRef("b2", "s1", "c_u2"),
        ))

        // Active profile is user u1:
        val u1Colls = repository.observeCollections().first()
        assertEquals(listOf("User 1 Collection"), u1Colls.map { it.name })

        // Switch to user u2 on same server s1:
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u2", isActive = true))
        val u2Colls = repository.observeCollections().first()
        assertEquals(listOf("User 2 Collection"), u2Colls.map { it.name })
    }

    @Test
    fun `updateBookReadingStatus sends status UUID in status key and succeeds on 204`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        val b1 = org.hello.reader.core.database.entity.BookEntity(
            id = "9d3f6a2b-1c4e-4f5a-8b7c-0d1e2f3a4b5c", serverId = "s1", userId = "u1", title = "Test Book", authors = "Author",
            narrators = "", coverUrl = null, description = null, mediaType = BookMediaType.EBOOK_ONLY,
            durationSeconds = 0L, totalChapters = 1, downloadStatus = DownloadStatus.NOT_DOWNLOADED, downloadProgress = 0f,
            localFilePath = null, localAudioPath = null, sha256Checksum = null, tags = "", createdAt = 0L,
            lastOpenedAt = null, hasReadaloud = false, hasEbook = true, hasAudiobook = false,
            readingStatus = "reading", readingStatusLabel = "Reading", statusUuid = "uuid-reading",
        )
        bookDao.upsertBooks(listOf(b1))

        server.enqueue(MockResponse().setResponseCode(204))

        val newStatus = org.hello.reader.core.model.ReadingStatusItem(
            uuid = "uuid-read",
            name = "read",
            label = "Read",
            isServerStatus = true,
        )

        val result = repository.updateBookReadingStatus("9d3f6a2b-1c4e-4f5a-8b7c-0d1e2f3a4b5c", newStatus)
        assertTrue(result.isSuccess)

        val request = server.takeRequest()
        assertEquals("/api/v2/books/9d3f6a2b-1c4e-4f5a-8b7c-0d1e2f3a4b5c/status", request.path)
        assertEquals("PUT", request.method)
        assertEquals("""{"status":"uuid-read"}""", request.body.readUtf8().trim())

        val updated = bookDao.getBooksSnapshot("s1", "u1").first { it.id == "9d3f6a2b-1c4e-4f5a-8b7c-0d1e2f3a4b5c" }
        assertEquals("read", updated.readingStatus)
        assertEquals("uuid-read", updated.statusUuid)
    }

    @Test
    fun `updateBookReadingStatus rolls back local status on server failure`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        val b1 = org.hello.reader.core.database.entity.BookEntity(
            id = "9d3f6a2b-1c4e-4f5a-8b7c-0d1e2f3a4b5c", serverId = "s1", userId = "u1", title = "Test Book", authors = "Author",
            narrators = "", coverUrl = null, description = null, mediaType = BookMediaType.EBOOK_ONLY,
            durationSeconds = 0L, totalChapters = 1, downloadStatus = DownloadStatus.NOT_DOWNLOADED, downloadProgress = 0f,
            localFilePath = null, localAudioPath = null, sha256Checksum = null, tags = "", createdAt = 0L,
            lastOpenedAt = null, hasReadaloud = false, hasEbook = true, hasAudiobook = false,
            readingStatus = "reading", readingStatusLabel = "Reading", statusUuid = "uuid-reading",
        )
        bookDao.upsertBooks(listOf(b1))

        server.enqueue(MockResponse().setResponseCode(400).setBody("Invalid UUID"))

        val newStatus = org.hello.reader.core.model.ReadingStatusItem(
            uuid = "uuid-read",
            name = "read",
            label = "Read",
            isServerStatus = true,
        )

        val result = repository.updateBookReadingStatus("9d3f6a2b-1c4e-4f5a-8b7c-0d1e2f3a4b5c", newStatus)
        assertTrue(result.isFailure)

        val rolledBack = bookDao.getBooksSnapshot("s1", "u1").first { it.id == "9d3f6a2b-1c4e-4f5a-8b7c-0d1e2f3a4b5c" }
        assertEquals("reading", rolledBack.readingStatus)
        assertEquals("Reading", rolledBack.readingStatusLabel)
        assertEquals("uuid-reading", rolledBack.statusUuid)
    }

    @Test
    fun `updateBookReadingStatus does not send non-server status to server`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))
        val b1 = org.hello.reader.core.database.entity.BookEntity(
            id = "9d3f6a2b-1c4e-4f5a-8b7c-0d1e2f3a4b5c", serverId = "s1", userId = "u1", title = "Test Book", authors = "Author",
            narrators = "", coverUrl = null, description = null, mediaType = BookMediaType.EBOOK_ONLY,
            durationSeconds = 0L, totalChapters = 1, downloadStatus = DownloadStatus.NOT_DOWNLOADED, downloadProgress = 0f,
            localFilePath = null, localAudioPath = null, sha256Checksum = null, tags = "", createdAt = 0L,
            lastOpenedAt = null, hasReadaloud = false, hasEbook = true, hasAudiobook = false,
        )
        bookDao.upsertBooks(listOf(b1))

        val fakeStatus = org.hello.reader.core.model.ReadingStatusItem(
            uuid = "to-read",
            name = "to-read",
            label = "Want to read",
            isServerStatus = false,
        )

        val result = repository.updateBookReadingStatus("9d3f6a2b-1c4e-4f5a-8b7c-0d1e2f3a4b5c", fakeStatus)
        assertTrue(result.isSuccess)
        assertEquals(0, server.requestCount)

        val updated = bookDao.getBooksSnapshot("s1", "u1").first { it.id == "9d3f6a2b-1c4e-4f5a-8b7c-0d1e2f3a4b5c" }
        assertEquals("to-read", updated.readingStatus)
    }

    @Test
    fun `existing ebook path survives audio download queuing progress ticks failure cancellation and completion`() = runTest {
        val activeProfile = profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true)
        vault.saveProfile(activeProfile)
        val downloadDao = org.hello.reader.testing.FakeBookDownloadDao()
        val tempDir = java.nio.file.Files.createTempDirectory("hello_dl_test").toFile()
        try {
            val repoWithDownloads = BookRepository(
                api = NetworkModule.storytellerApi(
                    NetworkModule.okHttpClient(
                        ServerAuthInterceptor({ vault.activeProfile.value }) {},
                        enableLogging = false,
                    ),
                ),
                bookDao = bookDao,
                checkpointDao = checkpointDao,
                vault = vault,
                downloadDao = downloadDao,
                filesDir = tempDir,
                clock = { 1_000L },
            )

            val existingEpubPath = java.io.File(tempDir, "existing_book.epub").apply {
                writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
            }.absolutePath
            val initialEntity = org.hello.reader.core.database.entity.BookEntity(
                id = "b1",
                serverId = "s1",
                userId = "u1",
                title = "Dual Format Book",
                authors = "Author",
                narrators = "Narrator",
                coverUrl = null,
                description = null,
                mediaType = BookMediaType.EBOOK_ONLY,
                durationSeconds = 600L,
                totalChapters = 5,
                downloadStatus = DownloadStatus.DOWNLOADED,
                downloadProgress = 1.0f,
                localFilePath = existingEpubPath,
                localAudioPath = null,
                sha256Checksum = "ebook-sha256",
                tags = "",
                createdAt = 100L,
                lastOpenedAt = null,
                hasReadaloud = false,
                hasEbook = true,
                hasAudiobook = true,
            )
            bookDao.upsertBooks(listOf(initialEntity))
            downloadDao.upsertDownload(
                org.hello.reader.core.database.entity.BookDownloadEntity(
                    bookId = "b1",
                    serverId = "s1",
                    userId = "u1",
                    format = BookMediaType.EBOOK_ONLY,
                    localPath = existingEpubPath,
                    sizeBytes = 4L,
                    downloadStatus = DownloadStatus.DOWNLOADED,
                    downloadProgress = 1.0f,
                    sha256 = "ebook-sha256",
                    updatedAt = 100L,
                ),
            )

            val domainBook = repoWithDownloads.observeLibrary().first().single()

            // 1. Queuing audio download preserves existing ebook path & sha256
            repoWithDownloads.markDownloadQueued(domainBook)
            val afterQueue = bookDao.getBookById("b1", "s1", "u1")!!
            assertEquals(DownloadStatus.QUEUED, afterQueue.downloadStatus)
            assertEquals(existingEpubPath, afterQueue.localFilePath)
            assertEquals("ebook-sha256", afterQueue.sha256Checksum)

            // 2. Cancelling download restores DOWNLOADED status and preserves ebook path & sha256
            repoWithDownloads.markDownloadCancelled(domainBook)
            val afterCancel = bookDao.getBookById("b1", "s1", "u1")!!
            assertEquals(DownloadStatus.DOWNLOADED, afterCancel.downloadStatus)
            assertEquals(existingEpubPath, afterCancel.localFilePath)
            assertEquals("ebook-sha256", afterCancel.sha256Checksum)

            // 3. Failed audio download in worker preserves existing ebook path & DOWNLOADED status
            val client = NetworkModule.okHttpClient(
                ServerAuthInterceptor({ vault.activeProfile.value }) {},
                enableLogging = false,
            )
            val api = NetworkModule.storytellerApi(client)
            server.enqueue(MockResponse().setResponseCode(404)) // getBookDetails
            server.enqueue(MockResponse().setResponseCode(500)) // candidate download fails
            val failedResult = org.hello.reader.core.download.BookDownloadWorker.executeDownload(
                bookId = "b1",
                serverId = "s1",
                userId = "u1",
                requestedMediaType = BookMediaType.AUDIO_ONLY,
                filesDir = tempDir,
                bookDao = bookDao,
                downloadDao = downloadDao,
                api = api,
                jobProfile = activeProfile,
            )
            assertEquals(androidx.work.ListenableWorker.Result.failure(), failedResult)
            val afterFail = bookDao.getBookById("b1", "s1", "u1")!!
            assertEquals(DownloadStatus.DOWNLOADED, afterFail.downloadStatus)
            assertEquals(existingEpubPath, afterFail.localFilePath)
            assertEquals("ebook-sha256", afterFail.sha256Checksum)

            // 4. Successful audio download with progress ticks preserves ebook path during ticks and after completion
            val fakeAudioBytes = ByteArray(16_384) { (it % 251).toByte() }
            server.enqueue(
                jsonResponse(
                    """{"id":"b1","title":"Dual Format Book","audio_download_url":"/api/v2/books/b1/files?format=audiobook"}""",
                ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "audio/mp4")
                    .setBody(okio.Buffer().write(fakeAudioBytes)),
            )

            var progressTicksObserved = 0
            val successResult = org.hello.reader.core.download.BookDownloadWorker.executeDownload(
                bookId = "b1",
                serverId = "s1",
                userId = "u1",
                requestedMediaType = BookMediaType.AUDIO_ONLY,
                filesDir = tempDir,
                bookDao = bookDao,
                downloadDao = downloadDao,
                api = api,
                jobProfile = activeProfile,
                onProgressReport = {
                    progressTicksObserved++
                    val duringTick = bookDao.getBookById("b1", "s1", "u1")!!
                    assertEquals(
                        "Progress tick must not clobber existing localFilePath",
                        existingEpubPath,
                        duringTick.localFilePath,
                    )
                    assertEquals(
                        "Progress tick must not clobber existing sha256Checksum",
                        "ebook-sha256",
                        duringTick.sha256Checksum,
                    )
                },
            )
            assertTrue(successResult is androidx.work.ListenableWorker.Result.Success)
            assertTrue("Expected at least one progress tick", progressTicksObserved > 0)

            val afterComplete = bookDao.getBookById("b1", "s1", "u1")!!
            assertEquals(DownloadStatus.DOWNLOADED, afterComplete.downloadStatus)
            assertEquals(1.0f, afterComplete.downloadProgress, 0.001f)
            assertEquals(existingEpubPath, afterComplete.localFilePath)
            assertNotNull("Audio path must be populated after audio download", afterComplete.localAudioPath)
            assertTrue(java.io.File(afterComplete.localAudioPath!!).exists())
            assertEquals("ebook-sha256", afterComplete.sha256Checksum)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `refreshCatalog updates to_read book to reading when server status changes after position sync`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = baseUrl, userId = "u1", isActive = true))

        server.enqueue(
            jsonResponse(
                """
                [
                  {
                    "id": "b1",
                    "title": "Blood Meridian",
                    "status": {"id": "to-read-uuid", "name": "to_read", "label": "To read"}
                  }
                ]
                """.trimIndent(),
            ),
        )
        repository.refreshCatalog()

        val initialBook = repository.observeLibrary().first().single { it.id == "b1" }
        assertTrue(initialBook.isToRead)

        // After reading and pushing a position, Storyteller updates bookToStatus to Reading.
        // The next catalog sync returns status as Reading.
        server.enqueue(
            jsonResponse(
                """
                [
                  {
                    "id": "b1",
                    "title": "Blood Meridian",
                    "status": {"id": "reading-uuid", "name": "reading", "label": "Reading"}
                  }
                ]
                """.trimIndent(),
            ),
        )
        repository.refreshCatalog()

        val updatedBook = repository.observeLibrary().first().single { it.id == "b1" }
        assertFalse(updatedBook.isToRead)
        assertEquals("reading", updatedBook.readingStatus)
        assertEquals("Reading", updatedBook.readingStatusLabel)
        assertEquals("reading-uuid", updatedBook.statusUuid)
    }
}
