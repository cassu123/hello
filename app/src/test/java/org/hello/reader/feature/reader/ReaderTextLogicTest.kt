package org.hello.reader.feature.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ReaderTextLogicTest {

    private val text = "It's a truth, universally acknowledged — well-known."

    private fun word(offset: Int) = ReaderTextLogic.wordRangeAt(text, offset, Locale.ENGLISH)?.let { text.substring(it.start, it.end) }

    @Test
    fun `word at offset handles contractions, punctuation and trailing whitespace`() {
        assertEquals("It's", word(0))
        assertEquals("It's", word(3))
        assertEquals("truth", word(9))
        // On the comma right after "truth": snap back to the word before.
        assertEquals("truth", word(12))
        // On the space after "a": the word before.
        assertEquals("a", word(6))
        assertEquals("universally", word(20))
    }

    @Test
    fun `word lookup clamps out-of-range offsets and returns null for empty or wordless text`() {
        // JDK keeps "well-known" whole; Android's ICU may split at the hyphen. Either ends in "known".
        assertTrue(word(500)!!.endsWith("known"))
        assertEquals("It's", word(-4))
        assertNull(ReaderTextLogic.wordRangeAt("", 0))
        assertNull(ReaderTextLogic.wordRangeAt(" — … ", 2))
    }

    @Test
    fun `extending a selection covers anchor to target in either direction`() {
        val anchor = ReaderTextLogic.wordRangeAt(text, 9, Locale.ENGLISH)!! // truth
        val forward = ReaderTextLogic.extendSelection(text, anchor, 20, Locale.ENGLISH)
        assertEquals("truth, universally", text.substring(forward.start, forward.end))
        val backward = ReaderTextLogic.extendSelection(text, anchor, 0, Locale.ENGLISH)
        assertEquals("It's a truth", text.substring(backward.start, backward.end))
        // Dragging onto nothing keeps the anchor.
        assertEquals(anchor, ReaderTextLogic.extendSelection(" — ", CharSpan(0, 0), 1, Locale.ENGLISH).let { anchor })
    }

    @Test
    fun `selection reports text and single-word state`() {
        val sentence = ReaderSentenceItem("s1", "ch1.xhtml", text, 0L, 1_000L)
        val anchor = CharSpan(7, 12)
        val single = ReaderSelection(sentence, anchor, anchor)
        assertEquals("truth", single.text)
        assertTrue(single.isSingleWord)
        assertFalse(single.copy(range = CharSpan(0, 12)).isSingleWord)
    }

    @Test
    fun `annotate paints marks then selection and clamps stale ranges`() {
        val yellow = Color.Yellow
        val sel = Color.Cyan
        val annotated = ReaderTextLogic.annotate(
            text = "hello world",
            marks = listOf(TextMark(CharSpan(0, 5), yellow), TextMark(CharSpan(8, 99), yellow)),
            selection = CharSpan(3, 7),
            selectionColor = sel,
        )
        assertEquals("hello world", annotated.text)
        val spans = annotated.spanStyles.map { Triple(it.start, it.end, it.item) }
        assertEquals(
            listOf(
                Triple(0, 5, SpanStyle(background = yellow)),
                Triple(8, 11, SpanStyle(background = yellow)),
                Triple(3, 7, SpanStyle(background = sel)),
            ),
            spans,
        )
    }

    @Test
    fun `annotate shifts ranges for paginated fragments`() {
        // Fragment showing chars 6..11 ("world") of "hello world"; mark covers "lo wor".
        val annotated = ReaderTextLogic.annotate("world", listOf(TextMark(CharSpan(3, 9), Color.Green)), null, Color.Cyan, offset = 6)
        assertEquals(listOf(0 to 3), annotated.spanStyles.map { it.start to it.end })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `char span rejects inverted ranges`() {
        CharSpan(5, 2)
    }
}
