package org.hello.reader.core.network.model

import org.hello.reader.core.model.BookMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogParserTest {

    private val serverId = "srv_1"
    private val userId = "user_1"
    private val baseUrl = "https://storyteller.example.com"

    @Test
    fun `parses Storyteller v2 books payload with object authors and float duration`() {
        val json = """
            {
              "books": [
                {
                  "id": "b1234567-89ab-cdef-0123-456789abcdef",
                  "title": "Moby Dick",
                  "authors": [
                    {
                      "id": "a1234567-89ab-cdef-0123-456789abcdef",
                      "name": "Herman Melville"
                    }
                  ],
                  "narrators": [
                    {
                      "id": "n1",
                      "name": "Narrator Guy"
                    }
                  ],
                  "description": "An epic novel about the voyage of the whaling ship Pequod.",
                  "coverUrl": "/api/v2/books/b1234567-89ab-cdef-0123-456789abcdef/cover",
                  "duration": 85400.25,
                  "isAligned": true,
                  "hasAudio": true,
                  "hasEpub": true,
                  "totalChapters": 42,
                  "tags": [
                    { "name": "Classic" },
                    { "name": "Sea Adventures" }
                  ]
                }
              ],
              "total": 1,
              "page": 1,
              "pageSize": 50
            }
        """.trimIndent()

        val books = CatalogParser.parse(json, serverId, userId, baseUrl)

        assertEquals(1, books.size)
        val book = books.first()
        assertEquals("b1234567-89ab-cdef-0123-456789abcdef", book.id)
        assertEquals(serverId, book.serverId)
        assertEquals(userId, book.userId)
        assertEquals("Moby Dick", book.title)
        assertEquals(listOf("Herman Melville"), book.authors)
        assertEquals(listOf("Narrator Guy"), book.narrators)
        assertEquals("An epic novel about the voyage of the whaling ship Pequod.", book.description)
        assertEquals("https://storyteller.example.com/api/v2/books/b1234567-89ab-cdef-0123-456789abcdef/cover", book.coverUrl)
        assertEquals(85400L, book.durationSeconds)
        assertEquals(BookMediaType.READALOUD, book.mediaType)
        assertEquals(42, book.totalChapters)
        assertEquals(listOf("Classic", "Sea Adventures"), book.tags)
    }

    @Test
    fun `parses empty books envelope without error`() {
        val json = """{ "books": [], "total": 0 }"""
        val books = CatalogParser.parse(json, serverId, userId, baseUrl)
        assertTrue(books.isEmpty())
    }

    @Test
    fun `parses integer book ids and string authors`() {
        val json = """
            {
              "items": [
                {
                  "id": 101,
                  "title": "Numeric ID Book",
                  "authors": ["Author One", "Author Two"],
                  "has_audio": true,
                  "has_epub": false,
                  "duration_seconds": 3600
                }
              ]
            }
        """.trimIndent()

        val books = CatalogParser.parse(json, serverId, userId, baseUrl)

        assertEquals(1, books.size)
        val book = books.single()
        assertEquals("101", book.id)
        assertEquals("Numeric ID Book", book.title)
        assertEquals(listOf("Author One", "Author Two"), book.authors)
        assertEquals(BookMediaType.AUDIO_ONLY, book.mediaType)
        assertEquals(3600L, book.durationSeconds)
    }

    @Test
    fun `parses raw json array of books`() {
        val json = """
            [
              {
                "uuid": "book-arr-1",
                "title": "Array Book",
                "authors": [{ "name": "Arthur Conan Doyle" }],
                "readaloud": { "filepath": "/books/study.epub", "missing": false }
              }
            ]
        """.trimIndent()

        val books = CatalogParser.parse(json, serverId, userId, baseUrl)

        assertEquals(1, books.size)
        val book = books.single()
        assertEquals("book-arr-1", book.id)
        assertEquals("Array Book", book.title)
        assertEquals(listOf("Arthur Conan Doyle"), book.authors)
        assertEquals(BookMediaType.READALOUD, book.mediaType)
    }

    @Test
    fun `parses generic data envelope with single comma-separated authors`() {
        val json = """
            {
              "data": [
                {
                  "id": "d-1",
                  "name": "Data Book",
                  "author": "Isaac Asimov, Robert Silverberg",
                  "cover": "covers/d1.png",
                  "has_epub": true
                }
              ]
            }
        """.trimIndent()

        val books = CatalogParser.parse(json, serverId, userId, baseUrl)

        assertEquals(1, books.size)
        val book = books.single()
        assertEquals("d-1", book.id)
        assertEquals("Data Book", book.title)
        assertEquals(listOf("Isaac Asimov", "Robert Silverberg"), book.authors)
        assertEquals("https://storyteller.example.com/covers/d1.png", book.coverUrl)
        assertEquals(BookMediaType.EBOOK_ONLY, book.mediaType)
    }

    @Test
    fun `skips malformed elements without crashing entire sync`() {
        val json = """
            {
              "books": [
                null,
                "not an object",
                { "missing_id_and_uuid": true, "title": "No ID" },
                { "id": "valid-1", "title": "Valid Book" }
              ]
            }
        """.trimIndent()

        val books = CatalogParser.parse(json, serverId, userId, baseUrl)

        assertEquals(1, books.size)
        assertEquals("valid-1", books.single().id)
        assertEquals("Valid Book", books.single().title)
    }

    @Test
    fun `returns empty list for blank or empty payload`() {
        assertTrue(CatalogParser.parse("", serverId, userId, baseUrl).isEmpty())
        assertTrue(CatalogParser.parse("   ", serverId, userId, baseUrl).isEmpty())
    }

    @Test
    fun `book with unaligned ebook and audio resolves to EBOOK_ONLY and reports ebook and audiobook available but not readaloud`() {
        val json = """
            {
              "books": [
                {
                  "id": "unaligned-1",
                  "title": "Unaligned Book",
                  "ebook": { "filepath": "book.epub", "missing": false },
                  "audiobook": { "filepath": "book.m4b", "missing": false },
                  "readaloud": { "filepath": null, "missing": true },
                  "isAligned": false
                }
              ]
            }
        """.trimIndent()

        val books = CatalogParser.parse(json, serverId, userId, baseUrl)

        assertEquals(1, books.size)
        val book = books.single()
        assertEquals(BookMediaType.EBOOK_ONLY, book.mediaType)
        assertTrue(book.hasEbook)
        assertTrue(book.hasAudiobook)
        assertFalse(book.hasReadaloud)
        assertEquals(listOf(BookMediaType.EBOOK_ONLY, BookMediaType.AUDIO_ONLY), book.availableFormats())
    }

    @Test
    fun `parses real Storyteller v2 payload with aligned readaloud, integer booleans, and nested duration`() {
        val json = """
            [
              {
                "id": 101,
                "uuid": "4c21b203-d34e-4e4b-8e12-402b85e05d01",
                "title": "The War of the Worlds",
                "authors": [{ "name": "H. G. Wells" }],
                "narrators": [{ "name": "David Tennant" }],
                "coverUrl": "/api/v2/books/4c21b203-d34e-4e4b-8e12-402b85e05d01/cover",
                "ebook": {
                  "filepath": "books/101/book.epub",
                  "missing": 0,
                  "fileSize": 1234567
                },
                "audiobook": {
                  "filepath": "books/101/audio.m4b",
                  "missing": 0,
                  "duration": 21600.0,
                  "fileSize": 123456789
                },
                "readaloud": {
                  "filepath": "books/101/synced.epub",
                  "missing": 0,
                  "status": "ALIGNED",
                  "fileSize": 124691356
                }
              }
            ]
        """.trimIndent()

        val books = CatalogParser.parse(json, serverId, userId, baseUrl)

        assertEquals(1, books.size)
        val book = books.single()
        // Prefers numeric id over uuid to maintain continuity with existing local downloads/checkpoints
        assertEquals("101", book.id)
        assertEquals("The War of the Worlds", book.title)
        assertEquals(listOf("H. G. Wells"), book.authors)
        assertEquals(listOf("David Tennant"), book.narrators)
        assertEquals("https://storyteller.example.com/api/v2/books/4c21b203-d34e-4e4b-8e12-402b85e05d01/cover", book.coverUrl)
        assertEquals(BookMediaType.READALOUD, book.mediaType)
        assertTrue(book.hasReadaloud)
        assertTrue(book.hasEbook)
        assertTrue(book.hasAudiobook)
        assertEquals(21600L, book.durationSeconds)
        assertEquals(listOf(BookMediaType.READALOUD, BookMediaType.EBOOK_ONLY, BookMediaType.AUDIO_ONLY), book.availableFormats())
    }

    @Test
    fun `readaloud with queued or processing status is not reported as available readaloud`() {
        val json = """
            [
              {
                "id": 102,
                "title": "Processing Alignment Book",
                "ebook": { "filepath": "books/102/book.epub", "missing": 0 },
                "audiobook": { "filepath": "books/102/audio.m4b", "missing": 0, "duration": 3600.0 },
                "readaloud": {
                  "filepath": "books/102/temp.epub",
                  "missing": 0,
                  "status": "PROCESSING",
                  "stage_progress": 0.45
                }
              }
            ]
        """.trimIndent()

        val books = CatalogParser.parse(json, serverId, userId, baseUrl)

        assertEquals(1, books.size)
        val book = books.single()
        assertEquals(BookMediaType.EBOOK_ONLY, book.mediaType)
        assertFalse(book.hasReadaloud)
        assertTrue(book.hasEbook)
        assertTrue(book.hasAudiobook)
        assertEquals(listOf(BookMediaType.EBOOK_ONLY, BookMediaType.AUDIO_ONLY), book.availableFormats())
    }

    @Test
    fun `CatalogParser parseFull extracts books, series with positions 1, 2_5, null, and collections with boolean or 0-1 public`() {
        val json = """
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
                    "description": "Epic space opera",
                    "position": 1,
                    "featured": 1
                  },
                  {
                    "uuid": "series-hugo",
                    "name": "Hugo Winners",
                    "position": null,
                    "featured": false
                  }
                ],
                "collections": [
                  {
                    "uuid": "coll-favorites",
                    "name": "Favorites",
                    "description": "My all time favorite books",
                    "icon": "star",
                    "color": "#FFD700",
                    "public": true
                  }
                ]
              },
              {
                "id": "book-2",
                "title": "The Butcher of Anderson Station",
                "authors": [{"name": "James S.A. Corey"}],
                "ebook": {"filepath": "butcher.epub", "missing": 0},
                "series": [
                  {
                    "uuid": "series-expanse",
                    "name": "The Expanse",
                    "position": 0.5,
                    "featured": 0
                  }
                ],
                "collections": [
                  {
                    "uuid": "coll-scifi",
                    "name": "Sci-Fi",
                    "icon": "rocket",
                    "color": "#00AAFF",
                    "public": 1
                  }
                ]
              },
              {
                "id": "book-3",
                "title": "Caliban's War",
                "authors": [{"name": "James S.A. Corey"}],
                "ebook": {"filepath": "cw.epub", "missing": 0},
                "series": [
                  {
                    "uuid": "series-expanse",
                    "name": "The Expanse",
                    "position": 2.5,
                    "featured": false
                  }
                ],
                "collections": [
                  {
                    "uuid": "coll-favorites",
                    "name": "Favorites",
                    "public": true
                  }
                ]
              }
            ]
        """.trimIndent()

        val result = CatalogParser.parseFull(json, serverId, userId, baseUrl)

        assertEquals(3, result.books.size)
        assertEquals(2, result.series.size)
        assertEquals(2, result.collections.size)

        val expanse = result.series.first { it.uuid == "series-expanse" }
        assertEquals("The Expanse", expanse.name)
        assertEquals(3, expanse.totalBooks)

        val expanseRefs = result.bookSeriesRelations.filter { it.seriesUuid == "series-expanse" }
        assertEquals(3, expanseRefs.size)
        val posByBook = expanseRefs.associate { it.bookId to it.position }
        assertEquals(1.0, posByBook["book-1"]!!, 0.001)
        assertEquals(0.5, posByBook["book-2"]!!, 0.001)
        assertEquals(2.5, posByBook["book-3"]!!, 0.001)

        val hugoRefs = result.bookSeriesRelations.filter { it.seriesUuid == "series-hugo" }
        assertEquals(1, hugoRefs.size)
        assertNull(hugoRefs.single().position)

        val favs = result.collections.first { it.uuid == "coll-favorites" }
        assertEquals("Favorites", favs.name)
        assertEquals(2, favs.totalBooks)
        assertTrue(favs.isPublic)

        val scifi = result.collections.first { it.uuid == "coll-scifi" }
        assertEquals("Sci-Fi", scifi.name)
        assertEquals(1, scifi.totalBooks)
        assertTrue(scifi.isPublic)
    }

    @Test
    fun `parses reading status as object and as string`() {
        val json = """
            [
              {
                "id": "b-1",
                "title": "Book 1",
                "status": {
                  "uuid": "st-1",
                  "name": "to_read",
                  "label": "To read"
                }
              },
              {
                "id": "b-2",
                "title": "Book 2",
                "readingStatus": "reading"
              },
              {
                "id": "b-3",
                "title": "Book 3",
                "status": {
                  "name": "read",
                  "label": "Read"
                }
              }
            ]
        """.trimIndent()

        val books = CatalogParser.parse(json, serverId, userId, baseUrl)
        assertEquals(3, books.size)

        assertEquals("to_read", books[0].readingStatus)
        assertEquals("To read", books[0].readingStatusLabel)
        assertFalse(books[0].isRead)

        assertEquals("reading", books[1].readingStatus)
        assertEquals("reading", books[1].readingStatusLabel)
        assertFalse(books[1].isRead)

        assertEquals("read", books[2].readingStatus)
        assertEquals("Read", books[2].readingStatusLabel)
        assertTrue(books[2].isRead)
    }

    @Test
    fun `CatalogParser parses extended metadata publication date, language, publisher, isbn, rating, and external ratings`() {
        val json = """
            [
              {
                "id": "b-meta",
                "title": "Metadata Book",
                "publication_date": "2024-05-18",
                "publisher": "Acme Publishing",
                "language": "en",
                "isbn": "978-0123456789",
                "rating": 4.5,
                "external_ratings": "Goodreads: 4.2 (1,234 ratings)",
                "status": {
                  "uuid": "stat-uuid-1",
                  "name": "reading",
                  "label": "Currently Reading"
                }
              }
            ]
        """.trimIndent()

        val books = CatalogParser.parse(json, serverId, userId, baseUrl)
        assertEquals(1, books.size)
        val b = books[0]
        assertEquals("2024-05-18", b.publicationDate)
        assertEquals("Acme Publishing", b.publisher)
        assertEquals("en", b.language)
        assertEquals("978-0123456789", b.isbn)
        assertEquals(4.5f, b.rating)
        assertEquals("Goodreads: 4.2 (1,234 ratings)", b.externalRatings)
        assertEquals("stat-uuid-1", b.statusUuid)
        assertEquals("reading", b.readingStatus)
        assertEquals("Currently Reading", b.readingStatusLabel)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `throws IllegalArgumentException on malformed json`() {
        CatalogParser.parse("{ invalid json content", serverId, userId, baseUrl)
    }
}
