package org.hello.reader.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPaginatorTest {

    private fun sentence(id: String, lineCount: Int) =
        ReaderSentenceItem(id, "ch.xhtml", "x".repeat(lineCount * 10), 0L, 0L)

    /** Every line is 10 chars and 20px tall. */
    private val measure: (ReaderSentenceItem) -> List<MeasuredLine> = { s ->
        (0 until s.text.length / 10).map { MeasuredLine(it * 10, it * 10 + 10, 20f) }
    }

    private fun shape(pages: List<ReaderPage>) =
        pages.map { page -> page.fragments.map { "${it.sentence.elementId}:${it.start}-${it.end}" } }

    @Test
    fun `paragraphs that fit share a page with spacing between them`() {
        // 2 lines (40) + spacing 10 + 2 lines (40) = 90 <= 100
        val pages = ReaderPaginator.paginate(listOf(sentence("a", 2), sentence("b", 2)), 100f, 10f, measure)
        assertEquals(listOf(listOf("a:0-20", "b:0-20")), shape(pages))
    }

    @Test
    fun `a paragraph is split at a line boundary when it overflows`() {
        // a: 3 lines (60). b: spacing 10 + 1 line = 90, + 1 more line = 110 > 100 -> split.
        val pages = ReaderPaginator.paginate(listOf(sentence("a", 3), sentence("b", 3)), 100f, 10f, measure)
        assertEquals(listOf(listOf("a:0-30", "b:0-10"), listOf("b:10-30")), shape(pages))
        assertEquals(true, pages[0].fragments[1].isParagraphStart)
        assertEquals(false, pages[1].fragments[0].isParagraphStart)
    }

    @Test
    fun `a paragraph taller than several pages spans them without empty pages`() {
        val pages = ReaderPaginator.paginate(listOf(sentence("long", 12)), 100f, 10f, measure)
        // 5 lines of 20px per 100px page.
        assertEquals(listOf(listOf("long:0-50"), listOf("long:50-100"), listOf("long:100-120")), shape(pages))
    }

    @Test
    fun `spacing never pushes a lone paragraph start onto an otherwise empty page`() {
        // a fills exactly 100. b must start at the top of page 2 with no leading gap counted.
        val pages = ReaderPaginator.paginate(listOf(sentence("a", 5), sentence("b", 5)), 100f, 10f, measure)
        assertEquals(listOf(listOf("a:0-50"), listOf("b:0-50")), shape(pages))
    }

    @Test
    fun `a single line taller than the page still gets its own page`() {
        val pages = ReaderPaginator.paginate(listOf(sentence("a", 1)), 10f, 0f, measure)
        assertEquals(listOf(listOf("a:0-10")), shape(pages))
    }

    @Test
    fun `empty input and empty paragraphs produce no pages`() {
        assertEquals(emptyList<ReaderPage>(), ReaderPaginator.paginate(emptyList(), 100f, 0f, measure))
        assertEquals(emptyList<ReaderPage>(), ReaderPaginator.paginate(listOf(sentence("e", 0)), 100f, 0f, measure))
    }

    @Test
    fun `pageOf finds the page showing a sentence`() {
        val pages = ReaderPaginator.paginate(listOf(sentence("a", 5), sentence("b", 5)), 100f, 10f, measure)
        assertEquals(1, ReaderPaginator.pageOf(pages, "b"))
        assertNull(ReaderPaginator.pageOf(pages, "zzz"))
        assertNull(ReaderPaginator.pageOf(pages, null))
    }

    @Test
    fun `a chapter boundary breaks onto a fresh page even if there is room remaining`() {
        val ch1 = ReaderSentenceItem("c1", "chapter1.xhtml", "x".repeat(20), 0L, 0L)
        val ch2 = ReaderSentenceItem("c2", "chapter2.xhtml", "y".repeat(20), 0L, 0L)
        val pages = ReaderPaginator.paginate(listOf(ch1, ch2), 100f, 10f, measure)
        assertEquals(listOf(listOf("c1:0-20"), listOf("c2:0-20")), shape(pages))
    }

    @Test
    fun `consecutive chapter headings share the chapter page rather than orphaning the first heading`() {
        val partHeader = ReaderSentenceItem("h1", "ch.xhtml", "Part One  ", 0L, 0L, isChapterStart = true)
        val chapterHeader = ReaderSentenceItem("h2", "ch.xhtml", "Chapter 1 ", 0L, 0L, isChapterStart = true)
        val bodyText = ReaderSentenceItem("p1", "ch.xhtml", "Body paragraph text.", 0L, 0L, isChapterStart = false)
        val pages = ReaderPaginator.paginate(listOf(partHeader, chapterHeader, bodyText), 100f, 10f, measure)
        assertEquals(1, pages.size)
        assertEquals(listOf("h1:0-10", "h2:0-10", "p1:0-20"), shape(pages)[0])
    }

    @Test
    fun `spreadCount correctly calculates ceil half page count`() {
        assertEquals(0, ReaderPaginator.spreadCount(0))
        assertEquals(1, ReaderPaginator.spreadCount(1))
        assertEquals(1, ReaderPaginator.spreadCount(2))
        assertEquals(2, ReaderPaginator.spreadCount(3))
        assertEquals(2, ReaderPaginator.spreadCount(4))
        assertEquals(3, ReaderPaginator.spreadCount(5))
    }

    @Test
    fun `buildSpreads ensures left page is always even-aligned and pairs right page`() {
        val s1 = sentence("s1", 5)
        val s2 = sentence("s2", 5)
        val s3 = sentence("s3", 5)
        val s4 = sentence("s4", 5)
        val s5 = sentence("s5", 5)
        // Each sentence fills one 100px page (5 lines of 20px)
        val pages = ReaderPaginator.paginate(listOf(s1, s2, s3, s4, s5), 100f, 0f, measure)
        assertEquals(5, pages.size)

        val spreads = ReaderPaginator.buildSpreads(pages)
        assertEquals(3, spreads.size)

        // Spread 0: pages 0 and 1
        assertEquals(0, spreads[0].spreadIndex)
        assertEquals(0, spreads[0].leftPageIndex)
        assertEquals(1, spreads[0].rightPageIndex)
        assertEquals(pages[0], spreads[0].leftPage)
        assertEquals(pages[1], spreads[0].rightPage)
        assertEquals(true, spreads[0].contains("s1"))
        assertEquals(true, spreads[0].contains("s2"))
        assertEquals(false, spreads[0].contains("s3"))

        // Spread 1: pages 2 and 3
        assertEquals(1, spreads[1].spreadIndex)
        assertEquals(2, spreads[1].leftPageIndex)
        assertEquals(3, spreads[1].rightPageIndex)
        assertEquals(pages[2], spreads[1].leftPage)
        assertEquals(pages[3], spreads[1].rightPage)
        assertEquals(true, spreads[1].contains("s3"))
        assertEquals(true, spreads[1].contains("s4"))

        // Spread 2: page 4 (odd total, right page is null)
        assertEquals(2, spreads[2].spreadIndex)
        assertEquals(4, spreads[2].leftPageIndex)
        assertNull(spreads[2].rightPageIndex)
        assertEquals(pages[4], spreads[2].leftPage)
        assertNull(spreads[2].rightPage)
        assertEquals(true, spreads[2].contains("s5"))

        // Test spreadOf helper
        assertEquals(0, ReaderPaginator.spreadOf(pages, "s1"))
        assertEquals(0, ReaderPaginator.spreadOf(pages, "s2"))
        assertEquals(1, ReaderPaginator.spreadOf(pages, "s3"))
        assertEquals(1, ReaderPaginator.spreadOf(pages, "s4"))
        assertEquals(2, ReaderPaginator.spreadOf(pages, "s5"))
        assertNull(ReaderPaginator.spreadOf(pages, "nonexistent"))
    }

    @Test
    fun `anchor element is preserved across width changes and maps to correct spread`() {
        val s1 = sentence("para1", 4)
        val s2 = sentence("para2", 4)
        val s3 = sentence("para3", 4)
        val sentences = listOf(s1, s2, s3)

        // Narrow width (single page): each 4-line sentence takes its own page (100px page height)
        val narrowPages = ReaderPaginator.paginate(sentences, 80f, 10f, measure)
        assertEquals(3, narrowPages.size)

        // User is reading sentence para2 (currently page 1)
        val anchorId = "para2"
        val narrowPage = ReaderPaginator.pageOf(narrowPages, anchorId)
        assertEquals(1, narrowPage)

        // User unfolds Pixel Fold to wide landscape / dual-page spread: taller / wider layout
        // At 200px page height, all paragraphs fit into fewer pages
        val widePages = ReaderPaginator.paginate(sentences, 200f, 10f, measure)
        val widePage = ReaderPaginator.pageOf(widePages, anchorId)
        assertEquals(0, widePage) // para2 is on wide page 0

        val spreadIndex = ReaderPaginator.spreadOf(widePages, anchorId)
        assertEquals(0, spreadIndex) // Spread 0 displays wide page 0, preserving reading spot
    }

    @Test
    fun `paginated content height never exceeds pageHeight so bottom line is not clipped`() {
        val s = sentence("s", 15) // 15 lines of 20px = 300px total
        val pageHeight = 105f // Exactly 5 lines = 100px <= 105px, 6 lines = 120px > 105px
        val pages = ReaderPaginator.paginate(listOf(s), pageHeight, 0f, measure)
        assertEquals(3, pages.size)
        for (page in pages) {
            val totalHeight = page.fragments.sumOf { frag ->
                (frag.start until frag.end step 10).count() * 20
            }
            assertTrue("Total height $totalHeight must be <= pageHeight $pageHeight", totalHeight <= pageHeight)
        }
    }

    @Test
    fun `blank or whitespace-only sentences are ignored and do not produce pages or extra spacing`() {
        val blankSentence = ReaderSentenceItem("blank", "ch.xhtml", "   \n\t  ", 0L, 0L)
        val pages = ReaderPaginator.paginate(listOf(blankSentence), 100f, 10f, measure)
        assertEquals(emptyList<ReaderPage>(), pages)

        val mixedPages = ReaderPaginator.paginate(
            listOf(sentence("a", 2), blankSentence, sentence("b", 2)),
            100f,
            10f,
            measure,
        )
        assertEquals(listOf(listOf("a:0-20", "b:0-20")), shape(mixedPages))
    }
}
