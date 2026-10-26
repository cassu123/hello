package org.hello.reader.feature.library

import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.DownloadStatus
import org.hello.reader.core.model.SyncCheckpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFiltersTest {

    private fun book(
        id: String,
        title: String = id,
        authors: List<String> = listOf("Author $id"),
        type: BookMediaType = BookMediaType.READALOUD,
        status: DownloadStatus = DownloadStatus.NOT_DOWNLOADED,
        tags: List<String> = emptyList(),
        createdAt: Long = 0L,
        lastOpenedAt: Long? = null,
        durationSeconds: Long = 0L,
    ) = Book(
        id = id, serverId = "s", userId = "u", title = title, authors = authors, mediaType = type,
        downloadStatus = status, tags = tags, createdAt = createdAt, lastOpenedAt = lastOpenedAt,
        durationSeconds = durationSeconds,
    )

    private fun checkpoint(id: String, percent: Float, positionMs: Long = 0L, totalMs: Long = 0L) = SyncCheckpoint(
        bookId = id, serverId = "s", userId = "u", readingCfi = null, chapterIndex = 0, chapterTitle = null,
        audioPositionMs = positionMs, totalDurationMs = totalMs, progressPercent = percent, activeSentenceId = null,
        updatedAt = 0L,
    )

    private val library = listOf(
        book("r", type = BookMediaType.READALOUD, status = DownloadStatus.DOWNLOADED, tags = listOf("Fantasy")),
        book("e", type = BookMediaType.EBOOK_ONLY, tags = listOf("fantasy", "Classics")),
        book("a", type = BookMediaType.AUDIO_ONLY, lastOpenedAt = 5L),
    )

    private fun ids(filter: LibraryFilter, checkpoints: Map<String, SyncCheckpoint> = emptyMap()) =
        LibraryFilters.apply(library, checkpoints, filter, LibrarySort.TITLE).map { it.id }

    @Test
    fun `media type, downloaded and tag filters`() {
        assertEquals(listOf("a", "e", "r"), ids(LibraryFilter.All))
        assertEquals(listOf("r"), ids(LibraryFilter.Readaloud))
        assertEquals(listOf("e"), ids(LibraryFilter.EbookOnly))
        assertEquals(listOf("a"), ids(LibraryFilter.AudioOnly))
        assertEquals(listOf("r"), ids(LibraryFilter.Downloaded))
        assertEquals(listOf("e", "r"), ids(LibraryFilter.Tag("FANTASY")))
    }

    @Test
    fun `unread excludes opened books and books with progress or position`() {
        val checkpoints = mapOf("r" to checkpoint("r", percent = 0f, positionMs = 30_000L))
        assertEquals(listOf("e"), ids(LibraryFilter.Unread, checkpoints))
    }

    @Test
    fun `tags are de-duplicated case-insensitively and sorted`() {
        assertEquals(listOf("Classics", "Fantasy"), LibraryFilters.tagsOf(library))
    }

    @Test
    fun `title sort ignores leading articles and case`() {
        val books = listOf(book("1", "The Hobbit"), book("2", "an Echo"), book("3", "Dune"))
        val sorted = LibraryFilters.apply(books, emptyMap(), LibraryFilter.All, LibrarySort.TITLE)
        assertEquals(listOf("Dune", "an Echo", "The Hobbit"), sorted.map { it.title })
    }

    @Test
    fun `author sort uses surname including particles and keeps authorless books last`() {
        val books = listOf(
            book("1", authors = listOf("Ursula K. Le Guin")),
            book("2", authors = listOf("Iain M. Banks")),
            book("3", authors = emptyList()),
            book("4", authors = listOf("Tolkien, J.R.R.")),
        )
        val sorted = LibraryFilters.apply(books, emptyMap(), LibraryFilter.All, LibrarySort.AUTHOR)
        assertEquals(listOf("2", "1", "4", "3"), sorted.map { it.id })
    }

    @Test
    fun `recent sort puts opened books first, then newest added`() {
        val books = listOf(
            book("old", createdAt = 1L),
            book("opened", createdAt = 0L, lastOpenedAt = 100L),
            book("new", createdAt = 50L),
        )
        val sorted = LibraryFilters.apply(books, emptyMap(), LibraryFilter.All, LibrarySort.RECENT)
        assertEquals(listOf("opened", "new", "old"), sorted.map { it.id })
    }

    @Test
    fun `continue items use checkpoint duration for remaining time and drop finished books`() {
        val books = listOf(
            book("listening", durationSeconds = 3_600L),
            book("reading"),
            book("finished"),
            book("fresh", durationSeconds = 1_000L),
        )
        val checkpoints = mapOf(
            "listening" to checkpoint("listening", percent = 25f, positionMs = 900_000L, totalMs = 3_600_000L),
            "reading" to checkpoint("reading", percent = 40f),
            "finished" to checkpoint("finished", percent = 100f),
        )

        val items = LibraryFilters.continueItems(books, checkpoints).associateBy { it.book.id }

        assertEquals(setOf("listening", "reading", "fresh"), items.keys)
        assertEquals(0.25f, items.getValue("listening").progress, 0.0001f)
        assertEquals(2_700_000L, items.getValue("listening").remainingMs)
        assertNull(items.getValue("reading").remainingMs)
        assertEquals(1_000_000L, items.getValue("fresh").remainingMs)
    }

    @Test
    fun `progress is clamped`() {
        assertEquals(1f, LibraryFilters.progressOf(checkpoint("x", percent = 140f)), 0f)
        assertEquals(0f, LibraryFilters.progressOf(checkpoint("x", percent = -3f)), 0f)
        assertEquals(0f, LibraryFilters.progressOf(null), 0f)
        assertTrue(LibraryFilters.isUnread(book("x"), null))
        assertFalse(LibraryFilters.isUnread(book("x"), checkpoint("x", percent = 1f)))
    }

    @Test
    fun `matchesQuery matches series and collection names in addition to title, author, narrator, and tags`() {
        val testBook = book("1", title = "Leviathan Wakes", authors = listOf("James S.A. Corey"))
        assertTrue(LibraryFilters.matchesQuery(testBook, "Leviathan"))
        assertTrue(LibraryFilters.matchesQuery(testBook, "Corey"))
        assertTrue(LibraryFilters.matchesQuery(testBook, "Expanse", seriesNames = listOf("The Expanse")))
        assertTrue(LibraryFilters.matchesQuery(testBook, "Space Opera", collectionNames = listOf("Space Opera Favorites")))
        assertFalse(LibraryFilters.matchesQuery(testBook, "Foundation", seriesNames = listOf("The Expanse"), collectionNames = listOf("Space Opera")))
    }

    @Test
    fun `matches respects reading status filter`() {
        val toReadBook = book("1", title = "Dune").copy(readingStatus = "to_read", readingStatusLabel = "To read")
        val readingBook = book("2", title = "Messiah").copy(readingStatus = "reading", readingStatusLabel = "Reading")
        val readBook = book("3", title = "Children of Dune").copy(readingStatus = "read", readingStatusLabel = "Read")

        assertTrue(LibraryFilters.matches(toReadBook, null, LibraryFilter.Status("to_read", "To read")))
        assertFalse(LibraryFilters.matches(toReadBook, null, LibraryFilter.Status("read", "Read")))

        assertTrue(LibraryFilters.matches(readingBook, null, LibraryFilter.Status("reading", "Reading")))
        assertFalse(LibraryFilters.matches(readingBook, null, LibraryFilter.Status("to_read", "To read")))

        assertTrue(LibraryFilters.matches(readBook, null, LibraryFilter.Status("read", "Read")))
        assertTrue(readBook.isRead)
        assertFalse(toReadBook.isRead)

        // Book with no status from server is treated as "To read" by default
        val unassignedBook = book("4", title = "God Emperor")
        assertTrue(LibraryFilters.matches(unassignedBook, null, LibraryFilter.Status("to_read", "To read")))
        assertFalse(LibraryFilters.matches(unassignedBook, null, LibraryFilter.Status("read", "Read")))
        assertFalse(LibraryFilters.matches(unassignedBook, null, LibraryFilter.Status("reading", "Reading")))

        // Book with no status but with reading progress is matched under "Reading"
        val inProgressUnassigned = unassignedBook
        val inProgressCheckpoint = checkpoint("4", percent = 20f)
        assertTrue(LibraryFilters.matches(inProgressUnassigned, inProgressCheckpoint, LibraryFilter.Status("reading", "Reading")))
        assertFalse(LibraryFilters.matches(inProgressUnassigned, inProgressCheckpoint, LibraryFilter.Status("to_read", "To read")))
    }

    @Test
    fun `effectiveProgress respects status and suppresses progress for to_read`() {
        val cp = checkpoint("x", percent = 34f)
        val toReadBook = book("1").copy(readingStatus = "to_read", readingStatusLabel = "To read")
        val readingBook = book("2").copy(readingStatus = "reading", readingStatusLabel = "Reading")
        val readBook = book("3").copy(readingStatus = "read", readingStatusLabel = "Read")
        val unassignedEmpty = book("4")
        val unassignedWithProgress = book("5")

        // To read books always have 0f progress, suppressing progress bar
        assertEquals(0f, LibraryFilters.effectiveProgress(toReadBook, cp), 0f)
        assertEquals(0f, LibraryFilters.effectiveProgress(toReadBook, null), 0f)

        // Read books always have 1f progress
        assertEquals(1f, LibraryFilters.effectiveProgress(readBook, cp), 0f)
        assertEquals(1f, LibraryFilters.effectiveProgress(readBook, null), 0f)

        // Reading books show synced progress even if not downloaded
        assertEquals(0.34f, LibraryFilters.effectiveProgress(readingBook, cp), 0.0001f)
        assertEquals(0f, LibraryFilters.effectiveProgress(readingBook, null), 0f)

        // Unassigned books with < 1% progress default to unstarted (0f)
        assertEquals(0f, LibraryFilters.effectiveProgress(unassignedEmpty, null), 0f)
        assertEquals(0f, LibraryFilters.effectiveProgress(unassignedEmpty, checkpoint("4", percent = 0f)), 0f)

        // Unassigned books with progress show synced progress
        assertEquals(0.34f, LibraryFilters.effectiveProgress(unassignedWithProgress, cp), 0.0001f)
    }

    @Test
    fun `continueItems drops books marked to_read even with leftover synced progress`() {
        val books = listOf(
            book("b1").copy(readingStatus = "to_read", readingStatusLabel = "To read"),
            book("b2").copy(readingStatus = "reading", readingStatusLabel = "Reading"),
            book("b3").copy(readingStatus = "read", readingStatusLabel = "Read"),
        )
        val checkpoints = mapOf(
            "b1" to checkpoint("b1", percent = 34f, positionMs = 3000L, totalMs = 10000L),
            "b2" to checkpoint("b2", percent = 50f, positionMs = 5000L, totalMs = 10000L),
            "b3" to checkpoint("b3", percent = 100f, positionMs = 10000L, totalMs = 10000L),
        )

        val items = LibraryFilters.continueItems(books, checkpoints)
        assertEquals(1, items.size)
        assertEquals("b2", items.first().book.id)
        assertEquals(0.5f, items.first().progress, 0.0001f)
    }
}
