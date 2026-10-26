package org.hello.reader.feature.reader

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.hello.reader.core.model.AnnotationType
import org.hello.reader.core.model.BookAnnotation
import org.hello.reader.core.model.HighlightColor
import org.hello.reader.core.model.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderUiLogicTest {

    private val window = IntSize(1080, 2000)
    private val popup = IntSize(600, 120)

    @Test
    fun `selection menu sits centered above the selection when there is room`() {
        val anchor = Rect(left = 400f, top = 1000f, right = 600f, bottom = 1040f)
        assertEquals(IntOffset(200, 1000 - 120 - 16), selectionMenuOffset(anchor, window, popup))
    }

    @Test
    fun `selection menu flips below near the top and clamps to the window edges`() {
        val nearTopLeft = Rect(left = 0f, top = 40f, right = 80f, bottom = 80f)
        assertEquals(IntOffset(0, 80 + 16), selectionMenuOffset(nearTopLeft, window, popup))
        val nearRight = Rect(left = 1000f, top = 1000f, right = 1080f, bottom = 1040f)
        assertEquals(1080 - 600, selectionMenuOffset(nearRight, window, popup).x)
    }

    @Test
    fun `selection menu stays on screen when neither above nor below fits`() {
        val tall = Rect(left = 400f, top = 50f, right = 600f, bottom = 1950f)
        assertEquals(2000 - 120, selectionMenuOffset(tall, window, popup).y)
    }

    @Test
    fun `page turn zones are the outer fifths`() {
        assertEquals(-1, pageTurnZone(100f, 1000f))
        assertEquals(0, pageTurnZone(500f, 1000f))
        assertEquals(1, pageTurnZone(900f, 1000f))
        assertEquals(0, pageTurnZone(10f, 0f))
    }

    private fun highlight(id: String, elementId: String?, start: Int, end: Int, created: Long = 0L) = BookAnnotation(
        id = id, bookId = "b", serverId = "s", userId = "u", type = AnnotationType.HIGHLIGHT, selectedText = "x",
        color = HighlightColor.BLUE, elementId = elementId, startOffset = start, endOffset = end, createdAt = created,
    )

    @Test
    fun `highlight marks skip bookmarks and empty or unanchored ranges, and dim on dark pages`() {
        val list = listOf(
            highlight("1", "p1", 0, 4),
            highlight("2", "p1", 3, 3),
            highlight("3", null, 0, 4),
            highlight("4", "p2", 1, 2).copy(type = AnnotationType.BOOKMARK),
        )
        val light = highlightMarks(list, darkPage = false)
        assertEquals(setOf("p1"), light.keys)
        assertEquals(listOf(CharSpan(0, 4)), light.getValue("p1").map { it.range })
        assertEquals(1f, light.getValue("p1").single().color.alpha, 0.001f)
        assertEquals(0.35f, highlightMarks(list, darkPage = true).getValue("p1").single().color.alpha, 0.01f)
    }

    @Test
    fun `notebook lists annotations in book order, not id order`() {
        val order = mapOf("s2" to 1, "s10" to 9)
        val sorted = annotationsInReadingOrder(
            listOf(
                highlight("late", "s10", 0, 1),
                highlight("orphan", "gone", 0, 1),
                highlight("early-second", "s2", 5, 6),
                highlight("early-first", "s2", 0, 1),
            ),
            order,
        )
        assertEquals(listOf("early-first", "early-second", "late", "orphan"), sorted.map { it.id })
    }

    @Test
    fun `boldness maps to font weights and clamps`() {
        fun weight(b: Int) = readerTextStyle(ReaderSettings(boldWeight = b), Color.Black, FontFamily.Serif).fontWeight
        assertEquals(FontWeight.Normal, weight(0))
        assertEquals(FontWeight.Medium, weight(1))
        assertEquals(FontWeight.SemiBold, weight(2))
        assertEquals(FontWeight.Bold, weight(3))
        assertEquals(FontWeight.Bold, weight(9))
    }

    @Test
    fun `speed labels drop trailing zeros`() {
        assertEquals("1×", formatSpeed(1.0f))
        assertEquals("1.25×", formatSpeed(1.25f))
        assertEquals("1.5×", formatSpeed(1.5f))
        assertEquals("0.8×", formatSpeed(0.8f))
    }

    @Test
    fun `reading status shows the page only when paginated`() {
        val book = org.hello.reader.core.model.Book(
            id = "b", serverId = "s", title = "T", authors = emptyList(),
            mediaType = org.hello.reader.core.model.BookMediaType.EBOOK_ONLY,
        )
        val mode = org.hello.reader.core.engine.reader.ReaderMode.ReadingOnly(
            book = book, chapterIndex = 2, chapterTitle = null, readingCfi = null,
            activeSentenceId = null, progressPercent = 42f,
        )
        val labels = object : org.hello.reader.ui.components.Labels {
            override fun timeLeft(ms: Long) = "${ms}ms left"
            override fun timeLeftInChapter(ms: Long) = "${ms}ms in chapter"
            override fun chapter(index: Int, title: String?) = title ?: "Chapter ${index + 1}"
            override fun page(page: Int, pageCount: Int) = "Page ${page + 1} of $pageCount"
        }
        val paged = readerStatus(mode, org.hello.reader.core.model.ReadingLayoutMode.PAGED, page = 11, pageCount = 40, labels = labels)
        assertEquals("Chapter 3", paged.chapter)
        assertEquals("42%", paged.percentLabel)
        assertEquals("Page 12 of 40", paged.detail)
        assertEquals(0.42f, paged.progress, 0.001f)
        val scroll = readerStatus(mode, org.hello.reader.core.model.ReadingLayoutMode.CONTINUOUS_SCROLL, page = 11, pageCount = 40, labels = labels)
        assertEquals("", scroll.detail)
    }

    @Test
    fun `tabletop split subtracts layout window Y offset from fold bounds`() {
        val split = computeTabletopSplitHeightsPx(
            foldTopInWindow = 900,
            foldBottomInWindow = 930,
            layoutWindowY = 120,
            totalHeightPx = 1600,
        )
        org.junit.Assert.assertNotNull(split)
        assertEquals(780, split!!.topHeightPx)
        assertEquals(30, split.hingeHeightPx)

        // Fold above the layout's top returns null
        org.junit.Assert.assertNull(
            computeTabletopSplitHeightsPx(
                foldTopInWindow = 100,
                foldBottomInWindow = 120,
                layoutWindowY = 120,
                totalHeightPx = 1600,
            ),
        )
    }

    @Test
    fun `horizontal page-turn drag in NONE mode is disabled while text selection is active`() {
        org.junit.Assert.assertTrue(shouldAllowHorizontalPageTurnDrag(hasActiveSelection = false))
        org.junit.Assert.assertFalse(shouldAllowHorizontalPageTurnDrag(hasActiveSelection = true))
    }
}
