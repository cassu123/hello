package org.hello.reader.core.engine

import org.hello.reader.core.engine.smil.BookChapters
import org.hello.reader.core.engine.smil.ChapterParagraph
import org.hello.reader.core.engine.smil.isChapterHeadingText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookChaptersTest {

    private fun p(id: String, href: String, begin: Long = 0L, end: Long = begin + 1_000L, text: String = "Text of $id, long enough to be a paragraph rather than a heading. ".repeat(2)) =
        ChapterParagraph(elementId = id, textHref = href, text = text, clipBeginMs = begin, clipEndMs = end)

    @Test
    fun `without a TOC every spine document is a chapter, titled by a short first paragraph`() {
        val chapters = BookChapters.build(
            listOf(
                p("h1", "ch1.xhtml", text = "Chapter One"),
                p("a", "ch1.xhtml"),
                p("b", "ch2.xhtml"),
            ),
        )
        assertEquals(listOf("Chapter One", null), chapters.chapters.map { it.title })
        assertEquals("b", chapters[1]!!.firstElementId)
        assertTrue(chapters.isNavigable)
        assertFalse(chapters.hasTiming)
    }

    @Test
    fun `documents the TOC skips fold into the chapter before them`() {
        val chapters = BookChapters.build(
            paragraphs = listOf(
                p("cover", "OEBPS/cover.xhtml", text = "Cover"),
                p("a", "OEBPS/Text/ch1.xhtml"),
                p("i", "OEBPS/Text/interlude.xhtml"),
                p("b", "OEBPS/Text/ch2.xhtml"),
            ),
            // TOC paths can differ from the SMIL's; the file name still matches.
            tocTitles = mapOf("Text/ch1.xhtml" to "The Beginning", "OEBPS/Text/ch2.xhtml" to "The End"),
        )
        assertEquals(listOf("Cover", "The Beginning", "The End"), chapters.chapters.map { it.title })
        assertEquals(1, chapters.indexOfElement("i"))
        assertEquals(2, chapters.indexOfElement("b"))
    }

    @Test
    fun `timed chapters find the chapter at a position and place scrubber ticks`() {
        val chapters = BookChapters.build(
            listOf(p("a", "1.xhtml", 0, 30_000), p("b", "2.xhtml", 30_000, 60_000), p("c", "3.xhtml", 60_000, 100_000)),
            timed = true,
        )
        assertTrue(chapters.hasTiming)
        assertEquals(0, chapters.indexAt(0))
        assertEquals(1, chapters.indexAt(45_000))
        assertEquals(2, chapters.indexAt(99_999))
        assertEquals(60_000L, chapters[1]!!.endMs)
        assertEquals(listOf(0.3f, 0.6f), chapters.tickFractions(100_000))
    }

    @Test
    fun `timing that restarts per chapter (one audio file each) is dropped`() {
        val chapters = BookChapters.build(
            listOf(p("a", "1.xhtml", 0, 30_000), p("b", "2.xhtml", 0, 25_000)),
            timed = true,
        )
        assertFalse(chapters.hasTiming)
        assertNull(chapters.indexAt(10_000))
        assertTrue(chapters.tickFractions(60_000).isEmpty())
    }

    @Test
    fun `element ids repeated across documents map to their first chapter`() {
        val chapters = BookChapters.build(listOf(p("s1", "1.xhtml"), p("s2", "1.xhtml"), p("s1", "2.xhtml")))
        assertEquals(2, chapters.size)
        assertEquals(0, chapters.indexOfElement("s1"))
        assertEquals("s1", chapters[1]!!.firstElementId)
    }

    @Test
    fun `audiobook tracks build navigable and timed chapters`() {
        val tracks = listOf(
            org.hello.reader.core.engine.smil.AudioTrackItem(
                file = java.io.File("01_Chapter_One.mp3"),
                audioSrc = "01_Chapter_One.mp3",
                startOffsetMs = 0L,
                durationMs = 60_000L,
            ),
            org.hello.reader.core.engine.smil.AudioTrackItem(
                file = java.io.File("02_Chapter_Two.mp3"),
                audioSrc = "02_Chapter_Two.mp3",
                startOffsetMs = 60_000L,
                durationMs = 90_000L,
            ),
        )
        val chapters = BookChapters.buildFromTracks(tracks)
        assertEquals(2, chapters.size)
        assertEquals("01 Chapter One", chapters[0]!!.title)
        assertEquals("02 Chapter Two", chapters[1]!!.title)
        assertTrue(chapters.hasTiming)
        assertTrue(chapters.isNavigable)
        assertEquals(1, chapters.indexAt(75_000L))
        assertEquals(listOf(0.4f), chapters.tickFractions(150_000L))
    }

    @Test
    fun `isChapterHeadingText rejects regular sentences starting with chapter-like prefixes and accepts valid headings`() {
        // Normal sentences that should NOT be mistaken for headings:
        assertFalse(isChapterHeadingText("Partly, yes."))
        assertFalse(isChapterHeadingText("Books were everywhere."))
        assertFalse(isChapterHeadingText("Letters came daily."))
        assertFalse(isChapterHeadingText("Prolonged silence followed."))
        assertFalse(isChapterHeadingText("Just a regular sentence about things."))

        // Valid chapter headings:
        assertTrue(isChapterHeadingText("Chapter 1"))
        assertTrue(isChapterHeadingText("Chapter One"))
        assertTrue(isChapterHeadingText("Chapter 1: A Long-expected Party"))
        assertTrue(isChapterHeadingText("Book Two"))
        assertTrue(isChapterHeadingText("Book IV"))
        assertTrue(isChapterHeadingText("Part One"))
        assertTrue(isChapterHeadingText("Part III: The Return"))
        assertTrue(isChapterHeadingText("Letter 1"))
        assertTrue(isChapterHeadingText("Letter II: To Mrs. Saville, England"))
        assertTrue(isChapterHeadingText("Prologue"))
        assertTrue(isChapterHeadingText("Prologue: The Shadow of the Past"))
        assertTrue(isChapterHeadingText("Epilogue"))
        assertTrue(isChapterHeadingText("Epilogue - Five Years Later"))
        assertTrue(isChapterHeadingText("IV"))
        assertTrue(isChapterHeadingText("IV."))
        assertTrue(isChapterHeadingText("XXI"))
    }
}
