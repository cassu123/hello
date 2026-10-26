package org.hello.reader.feature.reader

import org.hello.reader.core.engine.smil.isChapterHeadingText

/** One measured line of a paragraph: its character span and rendered height in px. */
data class MeasuredLine(val start: Int, val end: Int, val height: Float)

/** A contiguous run of one paragraph's lines that sits on a single page. */
data class PageFragment(val sentence: ReaderSentenceItem, val start: Int, val end: Int) {
    val text: String get() = sentence.text.substring(start, end)
    val isParagraphStart: Boolean get() = start == 0
}

data class ReaderPage(val fragments: List<PageFragment>) {
    /** True when this page shows any part of [elementId]; used to follow narration across pages. */
    fun contains(elementId: String): Boolean = fragments.any { it.sentence.elementId == elementId }
}

/** Two-page book spread for dual-page view on foldables and tablets. Left page is always even-aligned. */
data class ReaderSpread(
    val spreadIndex: Int,
    val leftPage: ReaderPage,
    val rightPage: ReaderPage? = null,
) {
    val leftPageIndex: Int get() = spreadIndex * 2
    val rightPageIndex: Int? get() = if (rightPage != null) spreadIndex * 2 + 1 else null

    fun contains(elementId: String): Boolean =
        leftPage.contains(elementId) || rightPage?.contains(elementId) == true
}

/**
 * Greedy line-level pagination: paragraphs flow onto a page line by line, and a
 * paragraph taller than the remaining space is split at a line boundary, so no
 * line is ever clipped and pages never start with blank space.
 *
 * [measure] lays a paragraph out at the page's text width and reports its
 * lines; it is a parameter so this stays pure and JVM-testable.
 */
object ReaderPaginator {

    fun paginate(
        sentences: List<ReaderSentenceItem>,
        pageHeight: Float,
        paragraphSpacing: Float,
        measure: (ReaderSentenceItem) -> List<MeasuredLine>,
    ): List<ReaderPage> {
        require(pageHeight > 0f) { "pageHeight must be positive" }
        val pages = mutableListOf<ReaderPage>()
        val current = mutableListOf<PageFragment>()
        var used = 0f

        fun flush() {
            if (current.isNotEmpty()) pages += ReaderPage(current.toList())
            current.clear()
            used = 0f
        }

        var previousHref: String? = null
        for (sentence in sentences) {
            if (sentence.text.isBlank()) continue
            val isNewDocument = previousHref != null && sentence.textHref != previousHref
            val isNewChapter = isNewDocument || (previousHref != null && sentence.isChapterStart)
            previousHref = sentence.textHref

            if (isNewChapter && current.isNotEmpty()) {
                val hasBodyText = current.any { frag ->
                    !frag.sentence.isChapterStart && !isChapterHeadingText(frag.sentence.text)
                }
                if (hasBodyText || isNewDocument) {
                    flush()
                }
            }

            val lines = measure(sentence).filter { it.end > it.start }
            if (lines.isEmpty()) continue
            var fragmentStart = lines.first().start
            var fragmentEnd = fragmentStart
            var gap = if (current.isEmpty()) 0f else paragraphSpacing

            for (line in lines) {
                val needed = gap + line.height
                if (used + needed > pageHeight && (current.isNotEmpty() || fragmentEnd > fragmentStart)) {
                    if (fragmentEnd > fragmentStart) current += PageFragment(sentence, fragmentStart, fragmentEnd)
                    flush()
                    fragmentStart = line.start
                    gap = 0f
                }
                used += gap + line.height
                gap = 0f
                fragmentEnd = line.end
            }
            current += PageFragment(sentence, fragmentStart, fragmentEnd)
        }
        flush()
        return pages
    }

    /** Index of the page showing [elementId], or null if it isn't on any page. */
    fun pageOf(pages: List<ReaderPage>, elementId: String?): Int? =
        elementId?.let { id -> pages.indexOfFirst { it.contains(id) }.takeIf { it >= 0 } }

    /** Number of spreads for the given page count: always ceil(pageCount / 2). */
    fun spreadCount(pageCount: Int): Int = if (pageCount <= 0) 0 else (pageCount + 1) / 2

    /** Spread index showing [elementId], or null if not found. */
    fun spreadOf(pages: List<ReaderPage>, elementId: String?): Int? {
        val pageIndex = pageOf(pages, elementId) ?: return null
        return pageIndex / 2
    }

    /** Groups pages into two-page spreads: left page is always even-aligned (`spreadIndex * 2`). */
    fun buildSpreads(pages: List<ReaderPage>): List<ReaderSpread> {
        if (pages.isEmpty()) return emptyList()
        val count = spreadCount(pages.size)
        return (0 until count).map { spreadIndex ->
            ReaderSpread(
                spreadIndex = spreadIndex,
                leftPage = pages[spreadIndex * 2],
                rightPage = pages.getOrNull(spreadIndex * 2 + 1),
            )
        }
    }
}
