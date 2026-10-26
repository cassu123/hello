package org.hello.reader.feature.library

import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.SeriesBookItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesDetailScreenTest {

    private fun testBook(id: String, readingStatus: String? = null, label: String? = null) = Book(
        id = id,
        serverId = "srv-1",
        userId = "usr-1",
        title = "Book $id",
        authors = listOf("Author"),
        mediaType = BookMediaType.EBOOK_ONLY,
        readingStatus = readingStatus,
        readingStatusLabel = label,
    )

    @Test
    fun `findFirstUnfinishedBook returns null for empty list`() {
        assertNull(findFirstUnfinishedBook(emptyList(), emptyMap()))
    }

    @Test
    fun `findFirstUnfinishedBook skips books marked Read and picks first unread`() {
        val b1 = SeriesBookItem(testBook("1", readingStatus = "read", label = "Read"), position = 1.0)
        val b2 = SeriesBookItem(testBook("2", readingStatus = "to_read", label = "To read"), position = 2.0)
        val b3 = SeriesBookItem(testBook("3"), position = 3.0)

        val books = listOf(b1, b2, b3)
        val result = findFirstUnfinishedBook(books, emptyMap())
        assertEquals("2", result?.id)
    }

    @Test
    fun `findFirstUnfinishedBook picks in-progress unread book before fresh unread book`() {
        val b1 = SeriesBookItem(testBook("1", readingStatus = "read", label = "Read"), position = 1.0)
        val b2 = SeriesBookItem(testBook("2", readingStatus = "reading", label = "Reading"), position = 2.0)
        val b3 = SeriesBookItem(testBook("3", readingStatus = "to_read", label = "To read"), position = 3.0)

        val books = listOf(b1, b2, b3)
        val progress = mapOf("1" to 1.0f, "2" to 0.45f, "3" to 0.0f)
        val result = findFirstUnfinishedBook(books, progress)
        assertEquals("2", result?.id)
    }

    @Test
    fun `findFirstUnfinishedBook skips book with 100 percent progress even if reading status not set`() {
        val b1 = SeriesBookItem(testBook("1"), position = 1.0)
        val b2 = SeriesBookItem(testBook("2"), position = 2.0)

        val books = listOf(b1, b2)
        val progress = mapOf("1" to 1.0f, "2" to 0.0f)
        val result = findFirstUnfinishedBook(books, progress)
        assertEquals("2", result?.id)
    }

    @Test
    fun `findFirstUnfinishedBook falls back to first book if all books are read`() {
        val b1 = SeriesBookItem(testBook("1", readingStatus = "read", label = "Read"), position = 1.0)
        val b2 = SeriesBookItem(testBook("2", readingStatus = "read", label = "Read"), position = 2.0)

        val books = listOf(b1, b2)
        val result = findFirstUnfinishedBook(books, mapOf("1" to 1.0f, "2" to 1.0f))
        assertEquals("1", result?.id)
    }
}
