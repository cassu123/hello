package org.hello.reader.feature.notebook

import org.hello.reader.core.model.AnnotationType
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookAnnotation
import org.hello.reader.core.model.BookMediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotebookViewModelTest {

    private fun book(id: String) = Book(id = id, serverId = "s", title = "Title $id", authors = listOf("A"), mediaType = BookMediaType.EBOOK_ONLY)

    private fun highlight(id: String, bookId: String, chapter: Int, offset: Int, createdAt: Long, note: String? = null) = BookAnnotation(
        id = id, bookId = bookId, serverId = "s", userId = "u", type = AnnotationType.HIGHLIGHT,
        selectedText = "text $id", note = note, chapterIndex = chapter, startOffset = offset, createdAt = createdAt,
    )

    @Test
    fun `groups by book, newest-annotated book first, entries in reading order`() {
        val sections = NotebookViewModel.group(
            listOf(
                highlight("a2", "a", chapter = 2, offset = 0, createdAt = 10),
                highlight("b1", "b", chapter = 0, offset = 5, createdAt = 30),
                highlight("a1", "a", chapter = 1, offset = 40, createdAt = 20),
                highlight("a0", "a", chapter = 1, offset = 3, createdAt = 5),
            ),
            listOf(book("a"), book("b")),
        )
        assertEquals(listOf("b", "a"), sections.map { it.bookId })
        assertEquals(listOf("a0", "a1", "a2"), sections[1].entries.map { it.id })
        assertEquals("Title a", sections[1].book?.title)
    }

    @Test
    fun `annotations for books missing from the library keep a section without a book`() {
        val sections = NotebookViewModel.group(listOf(highlight("x", "gone", 0, 0, 1)), emptyList())
        assertNull(sections.single().book)
    }

    @Test
    fun `filters match by type and by note`() {
        val noted = highlight("n", "a", 0, 0, 1, note = "why")
        val plain = highlight("p", "a", 0, 0, 1)
        val bookmark = plain.copy(id = "b", type = AnnotationType.BOOKMARK, selectedText = null)
        assertTrue(NotebookFilter.NOTES.matches(noted))
        assertFalse(NotebookFilter.NOTES.matches(plain))
        assertTrue(NotebookFilter.HIGHLIGHTS.matches(plain))
        assertFalse(NotebookFilter.HIGHLIGHTS.matches(bookmark))
        assertTrue(NotebookFilter.BOOKMARKS.matches(bookmark))
        assertTrue(NotebookFilter.ALL.matches(bookmark))
    }
}
