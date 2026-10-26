package org.hello.reader.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesAndCollectionTest {

    private fun testBook(id: String, title: String) = Book(
        id = id,
        serverId = "srv-1",
        userId = "usr-1",
        title = title,
        authors = listOf("Author"),
        mediaType = BookMediaType.EBOOK_ONLY,
    )

    @Test
    fun `formatSeriesPositionNumber formats integers and decimals cleanly and returns null for null`() {
        assertEquals("1", formatSeriesPositionNumber(1.0))
        assertEquals("2", formatSeriesPositionNumber(2.0))
        assertEquals("2.5", formatSeriesPositionNumber(2.5))
        assertEquals("10", formatSeriesPositionNumber(10.0))
        assertEquals("0.5", formatSeriesPositionNumber(0.5))
        assertNull(formatSeriesPositionNumber(null))
    }

    @Test
    fun `formatSeriesPositionLabel formats Book prefix or null`() {
        assertEquals("Book 1", formatSeriesPositionLabel(1.0))
        assertEquals("Book 2", formatSeriesPositionLabel(2.0))
        assertEquals("Book 2.5", formatSeriesPositionLabel(2.5))
        assertEquals("Book 10", formatSeriesPositionLabel(10.0))
        assertNull(formatSeriesPositionLabel(null))
    }

    @Test
    fun `series book comparator sorts numeric positions 1, 2, 2_5, 10 then nulls by title`() {
        val b10 = SeriesBookItem(testBook("10", "Tenth"), position = 10.0)
        val b1 = SeriesBookItem(testBook("1", "First"), position = 1.0)
        val bNullZulu = SeriesBookItem(testBook("zulu", "Zulu Companion"), position = null)
        val b2_5 = SeriesBookItem(testBook("2.5", "Novella 2.5"), position = 2.5)
        val bNullAlpha = SeriesBookItem(testBook("alpha", "Alpha Prequel Unnumbered"), position = null)
        val b2 = SeriesBookItem(testBook("2", "Second"), position = 2.0)

        val list = listOf(b10, b1, bNullZulu, b2_5, bNullAlpha, b2)
        val sorted = list.sortedWith(SERIES_BOOK_COMPARATOR)

        val expectedOrder = listOf("1", "2", "2.5", "10", "alpha", "zulu")
        assertEquals(expectedOrder, sorted.map { it.book.id })
    }

    @Test
    fun `groupCollectionBooks groups books by series in series order followed by standalone books`() {
        val collection = BookCollection(
            uuid = "c1",
            serverId = "srv-1",
            name = "Epic Collection",
        )
        val seriesA = Series(uuid = "sA", serverId = "srv-1", name = "A Series")
        val seriesB = Series(uuid = "sB", serverId = "srv-1", name = "B Series")

        val bookA2 = testBook("a2", "Book A2")
        val bookA1 = testBook("a1", "Book A1")
        val bookB1 = testBook("b1", "Book B1")
        val bookStandaloneZulu = testBook("z", "Zulu Standalone")
        val bookStandaloneAlpha = testBook("a", "Alpha Standalone")

        val books = listOf(bookA2, bookStandaloneZulu, bookB1, bookA1, bookStandaloneAlpha)
        val bookSeriesMap = mapOf(
            "a2" to listOf(BookSeriesEntry("sA", "A Series", position = 2.0)),
            "a1" to listOf(BookSeriesEntry("sA", "A Series", position = 1.0)),
            "b1" to listOf(BookSeriesEntry("sB", "B Series", position = 1.0)),
        )
        val seriesMap = mapOf(
            "sA" to seriesA,
            "sB" to seriesB,
        )

        val grouped = groupCollectionBooks(collection, books, bookSeriesMap, seriesMap)

        assertEquals("Epic Collection", grouped.collection.name)
        assertEquals(2, grouped.seriesGroups.size)

        // Series A group (in series position order: 1, then 2)
        val groupA = grouped.seriesGroups[0]
        assertEquals("A Series", groupA.series.name)
        assertEquals(listOf("a1", "a2"), groupA.books.map { it.book.id })
        assertEquals(listOf("Book 1", "Book 2"), groupA.books.map { it.positionLabel })

        // Series B group
        val groupB = grouped.seriesGroups[1]
        assertEquals("B Series", groupB.series.name)
        assertEquals(listOf("b1"), groupB.books.map { it.book.id })

        // Standalone books sorted by title: Alpha then Zulu
        assertEquals(listOf("a", "z"), grouped.standaloneBooks.map { it.id })
    }
}
