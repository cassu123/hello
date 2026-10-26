package org.hello.reader.core.data

import org.hello.reader.core.model.AnnotationType
import org.hello.reader.core.model.HighlightColor
import org.hello.reader.core.security.SecurityVaultImpl
import org.hello.reader.testing.FakeAnnotationDao
import org.hello.reader.testing.InMemorySharedPreferences
import org.hello.reader.testing.profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnnotationRepositoryTest {

    private lateinit var vault: SecurityVaultImpl
    private lateinit var dao: FakeAnnotationDao
    private lateinit var repository: AnnotationRepository
    private var idCounter = 0

    @Before
    fun setUp() {
        vault = SecurityVaultImpl(InMemorySharedPreferences(), Dispatchers.Unconfined)
        dao = FakeAnnotationDao()
        idCounter = 0
        repository = AnnotationRepository(
            annotationDao = dao,
            vault = vault,
            clock = { 5_000L },
            idGenerator = { "fixed-id-${++idCounter}" },
        )
    }

    @Test
    fun `addHighlight and addBookmark persist and scope to active server and user`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = "https://server1.local", userId = "u1", isActive = true))

        val highlight = repository.addHighlight(
            bookId = "b1",
            selectedText = "All that is gold does not glitter,",
            note = "Strider's riddle",
            color = HighlightColor.YELLOW,
            textHref = "chapter1.xhtml",
            elementId = "p1",
            startOffset = 0,
            endOffset = 33,
            chapterIndex = 0,
            chapterTitle = "Prologue",
        )
        assertNotNull(highlight)

        val bookmark = repository.addBookmark(
            bookId = "b1",
            chapterIndex = 1,
            chapterTitle = "Chapter 1",
            textHref = "chapter2.xhtml",
            elementId = "p5",
        )
        assertNotNull(bookmark)

        val all = repository.observeAnnotations("b1").first()
        assertEquals(2, all.size)

        val highlights = repository.observeHighlights("b1").first()
        assertEquals(1, highlights.size)
        assertEquals(AnnotationType.HIGHLIGHT, highlights.single().type)
        assertEquals("Strider's riddle", highlights.single().note)

        val bookmarks = repository.observeBookmarks("b1").first()
        assertEquals(1, bookmarks.size)
        assertEquals(AnnotationType.BOOKMARK, bookmarks.single().type)

        // Switch to server 2 -> zero data bleed
        vault.saveProfile(profile("s2", baseUrl = "https://server2.local", userId = "u2", isActive = true))
        assertTrue(repository.observeAnnotations("b1").first().isEmpty())
    }

    @Test
    fun `updateAnnotation edits note and color while preserving identity and scope invariants`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = "https://server1.local", userId = "u1", isActive = true))

        val original = repository.addHighlight(
            bookId = "b1",
            selectedText = "Fly, you fools!",
            note = "Initial note",
            color = HighlightColor.YELLOW,
        )!!

        // Attempt to illicitly move it to a different book, server, or change createdAt
        val updated = repository.updateAnnotation(original.id) { existing ->
            existing.copy(
                note = "Gandalf at the Bridge of Khazad-dum",
                color = HighlightColor.PINK,
                bookId = "hacked-book",
                serverId = "hacked-server",
                createdAt = 999_999L,
            )
        }

        assertNotNull(updated)
        assertEquals(original.id, updated!!.id)
        assertEquals("b1", updated.bookId) // Protected against tamper
        assertEquals("s1", updated.serverId) // Protected against tamper
        assertEquals(5_000L, updated.createdAt) // Preserved
        assertEquals("Gandalf at the Bridge of Khazad-dum", updated.note)
        assertEquals(HighlightColor.PINK, updated.color)

        // Update color via helper
        val recolored = repository.updateColor(original.id, HighlightColor.BLUE)
        assertEquals(HighlightColor.BLUE, recolored?.color)

        // Update note via helper
        val renoted = repository.updateNote(original.id, "Final note")
        assertEquals("Final note", renoted?.note)
    }

    @Test
    fun `exportToMarkdown generates formatted header, quotes, notes, and chapter sections`() = runTest {
        vault.saveProfile(profile("s1", baseUrl = "https://server1.local", userId = "u1", isActive = true))

        val h1 = repository.addHighlight(
            bookId = "b1",
            selectedText = "It is a truth universally acknowledged.",
            note = "Famous opening line",
            color = HighlightColor.PINK,
            chapterIndex = 0,
            chapterTitle = "Chapter 1",
        )!!

        val b1 = repository.addBookmark(
            bookId = "b1",
            chapterIndex = 0,
            chapterTitle = "Chapter 1",
        )!!

        val md = repository.exportToMarkdown(
            bookTitle = "Pride and Prejudice",
            bookAuthor = "Jane Austen",
            annotations = listOf(h1, b1),
        )

        assertTrue(md.contains("# Notes & Highlights: Pride and Prejudice"))
        assertTrue(md.contains("**Author:** Jane Austen"))
        assertTrue(md.contains("## Chapter 1"))
        assertTrue(md.contains("> \"It is a truth universally acknowledged.\""))
        assertTrue(md.contains("**Note:** Famous opening line"))
        assertTrue(md.contains("🔖 **Bookmark**"))
    }
}
