package org.hello.reader.feature.reader

import org.hello.reader.core.engine.smil.BookChapter
import org.hello.reader.core.engine.smil.BookChapters
import org.hello.reader.core.engine.smil.ChapterParagraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSearchMatcherTest {

    private fun createSentence(
        id: String,
        text: String,
        chapterTitle: String = "Chapter 1",
    ) = ReaderSentenceItem(
        elementId = id,
        textHref = "ch1.xhtml",
        text = text,
        clipBeginMs = 0L,
        clipEndMs = 1_000L,
        chapterTitle = chapterTitle,
    )

    private fun createChapters(sentences: List<ReaderSentenceItem>): BookChapters {
        val paras = sentences.map {
            ChapterParagraph(it.elementId, it.textHref, it.text, it.clipBeginMs, it.clipEndMs)
        }
        return BookChapters.build(paras, emptyMap(), timed = false)
    }

    @Test
    fun `case-insensitive matching finds uppercase, lowercase, and mixed-case`() {
        val sentences = listOf(
            createSentence("s1", "The Quick Brown Fox jumps over the lazy dog."),
        )
        val chapters = createChapters(sentences)

        val lower = BookSearchMatcher.search(sentences, chapters, "fox")
        assertEquals(1, lower.size)
        assertEquals(1, lower[0].results.size)
        assertEquals("s1", lower[0].results[0].elementId)
        val res = lower[0].results[0]
        assertEquals("Fox", res.snippet.substring(res.matchRangeInSnippet))

        val upper = BookSearchMatcher.search(sentences, chapters, "QUICK")
        assertEquals(1, upper.size)
        val resQuick = upper[0].results[0]
        assertEquals("Quick", resQuick.snippet.substring(resQuick.matchRangeInSnippet))

        val mixed = BookSearchMatcher.search(sentences, chapters, "LaZy")
        assertEquals(1, mixed.size)
        val resLazy = mixed[0].results[0]
        assertEquals("lazy", resLazy.snippet.substring(resLazy.matchRangeInSnippet))
    }

    @Test
    fun `diacritic-insensitive matching finds accented characters with plain query`() {
        val sentences = listOf(
            createSentence("s1", "Nous visitons le magnifique château au bord de la rivière."),
            createSentence("s2", "Un café chaud avec une délicieuse crème brûlée."),
            createSentence("s3", "Er sprach über das schöne Wetter in München."),
            createSentence("s4", "El niño jugaba en el patio con alegría."),
        )
        val chapters = createChapters(sentences)

        // Searching without accents matches accented words
        val chateauMatch = BookSearchMatcher.search(sentences, chapters, "chateau")
        assertEquals(1, chateauMatch.size)
        assertEquals("s1", chateauMatch[0].results[0].elementId)
        val res1 = chateauMatch[0].results[0]
        assertEquals("château", res1.snippet.substring(res1.matchRangeInSnippet))

        val cafeMatch = BookSearchMatcher.search(sentences, chapters, "cafe")
        assertEquals(1, cafeMatch.size)
        val res2 = cafeMatch[0].results[0]
        assertEquals("café", res2.snippet.substring(res2.matchRangeInSnippet))

        val cremeMatch = BookSearchMatcher.search(sentences, chapters, "creme brulee")
        assertEquals(1, cremeMatch.size)
        val res3 = cremeMatch[0].results[0]
        assertEquals("crème brûlée", res3.snippet.substring(res3.matchRangeInSnippet))

        val munchenMatch = BookSearchMatcher.search(sentences, chapters, "munchen")
        assertEquals(1, munchenMatch.size)
        val res4 = munchenMatch[0].results[0]
        assertEquals("München", res4.snippet.substring(res4.matchRangeInSnippet))

        val ninoMatch = BookSearchMatcher.search(sentences, chapters, "nino")
        assertEquals(1, ninoMatch.size)
        val res5 = ninoMatch[0].results[0]
        assertEquals("niño", res5.snippet.substring(res5.matchRangeInSnippet))
    }

    @Test
    fun `accented query matches unaccented text as well`() {
        val sentences = listOf(
            createSentence("s1", "A simple cafe opened down the street."),
        )
        val chapters = createChapters(sentences)

        val res = BookSearchMatcher.search(sentences, chapters, "café")
        assertEquals(1, res.size)
        assertEquals("cafe", res[0].results[0].snippet.substring(res[0].results[0].matchRangeInSnippet))
    }

    @Test
    fun `multi-word phrase matching matches exact phrase across word boundaries`() {
        val sentences = listOf(
            createSentence("s1", "Call me Ishmael. Some years ago—never mind how long precisely..."),
            createSentence("s2", "It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife."),
        )
        val chapters = createChapters(sentences)

        val res1 = BookSearchMatcher.search(sentences, chapters, "some years ago")
        assertEquals(1, res1.size)
        assertEquals("s1", res1[0].results[0].elementId)
        assertEquals("Some years ago", res1[0].results[0].snippet.substring(res1[0].results[0].matchRangeInSnippet))

        val res2 = BookSearchMatcher.search(sentences, chapters, "universally acknowledged")
        assertEquals(1, res2.size)
        assertEquals("s2", res2[0].results[0].elementId)
        assertEquals("universally acknowledged", res2[0].results[0].snippet.substring(res2[0].results[0].matchRangeInSnippet))
    }

    @Test
    fun `multiple occurrences in one sentence are each captured`() {
        val sentences = listOf(
            createSentence("s1", "The cat chased the mouse into the barn."),
        )
        val chapters = createChapters(sentences)

        val results = BookSearchMatcher.search(sentences, chapters, "the")
        assertEquals(1, results.size)
        assertEquals(3, results[0].results.size)
        assertEquals("The", results[0].results[0].snippet.substring(results[0].results[0].matchRangeInSnippet))
        assertEquals("the", results[0].results[1].snippet.substring(results[0].results[1].matchRangeInSnippet))
        assertEquals("the", results[0].results[2].snippet.substring(results[0].results[2].matchRangeInSnippet))
    }

    @Test
    fun `empty or blank query returns empty results`() {
        val sentences = listOf(createSentence("s1", "Hello world"))
        val chapters = createChapters(sentences)

        assertTrue(BookSearchMatcher.search(sentences, chapters, "").isEmpty())
        assertTrue(BookSearchMatcher.search(sentences, chapters, "   ").isEmpty())
    }

    @Test
    fun `no matches returns empty list`() {
        val sentences = listOf(createSentence("s1", "Call me Ishmael."))
        val chapters = createChapters(sentences)

        assertTrue(BookSearchMatcher.search(sentences, chapters, "Captain Ahab").isEmpty())
    }

    @Test
    fun `results are grouped and sorted by chapter`() {
        val s1 = ReaderSentenceItem("s1", "ch1.xhtml", "Ahab sought the great white whale.", 0L, 0L, isChapterStart = true, chapterTitle = "Chapter 1: Loomings")
        val s2 = ReaderSentenceItem("s2", "ch2.xhtml", "The white whale swam into the deep blue ocean.", 0L, 0L, isChapterStart = true, chapterTitle = "Chapter 2: The Carpet-Bag")
        val sentences = listOf(s1, s2)
        val chapters = createChapters(sentences)

        val results = BookSearchMatcher.search(sentences, chapters, "white whale")
        assertEquals(2, results.size)
        assertEquals(0, results[0].chapterIndex)
        assertEquals(1, results[1].chapterIndex)
        assertEquals("s1", results[0].results[0].elementId)
        assertEquals("s2", results[1].results[0].elementId)
    }

    @Test
    fun `snippet context bounds correctly and adds ellipsis when truncated`() {
        val text = "Beginning of a very long sentence that has many words before reaching the secret treasure chamber deep inside the mountain and then continues with even more words to the end."
        val (snippet, range) = BookSearchMatcher.buildSnippet(text, text.indexOf("secret treasure"), text.indexOf("secret treasure") + "secret treasure".length, contextChars = 20)

        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertEquals("secret treasure", snippet.substring(range))
    }

    @Test
    fun `searching 1M-char synthetic book completes in under 500ms on the JVM`() {
        val paragraphTemplate = "It was the best of times, it was the worst of times, with a grand château and delicious café au lait. "
        val sentences = (0 until 5_000).map { i ->
            val text = if (i == 2_500) {
                "In the middle of the story, the captain discovered the hidden treasure in the cave."
            } else {
                paragraphTemplate.repeat(2)
            }
            createSentence("s_$i", text, "Chapter ${i / 250 + 1}")
        }
        val chapters = createChapters(sentences)
        val searchable = BookSearchMatcher.prepareSentences(sentences)

        val startTime = System.nanoTime()
        val results = BookSearchMatcher.search(searchable, chapters, "hidden treasure")
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000

        assertEquals(1, results.size)
        assertEquals(1, results[0].results.size)
        assertEquals("s_2500", results[0].results[0].elementId)
        assertTrue("Expected search under 500ms, took ${elapsedMs}ms", elapsedMs < 500)
    }
}
