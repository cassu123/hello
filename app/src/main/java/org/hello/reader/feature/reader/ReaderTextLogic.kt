package org.hello.reader.feature.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import java.text.BreakIterator
import java.util.Locale

/** A character range within one paragraph's text. `end` is exclusive. */
data class CharSpan(val start: Int, val end: Int) {
    init {
        require(start in 0..end) { "Invalid range $start..$end" }
    }

    val isEmpty: Boolean get() = start == end

    /** Smallest range covering both, in reading order regardless of drag direction. */
    fun union(other: CharSpan): CharSpan = CharSpan(minOf(start, other.start), maxOf(end, other.end))
}

/** The user's current long-press selection inside one paragraph. */
data class ReaderSelection(
    val sentence: ReaderSentenceItem,
    /** Word under the initial long press; drag extension always keeps it selected. */
    val anchor: CharSpan,
    val range: CharSpan,
) {
    val text: String get() = sentence.text.substring(range.start, range.end)

    /** A single word is offered to the dictionary; longer selections are not. */
    val isSingleWord: Boolean get() = range == anchor
}

/** A colored span to paint behind paragraph text (saved highlights). */
data class TextMark(val range: CharSpan, val color: Color)

object ReaderTextLogic {

    /**
     * Returns the word containing [offset], using locale-aware word boundaries
     * (handles apostrophes, hyphenation points and non-Latin scripts). When the
     * offset lands on whitespace or punctuation, the word immediately before it
     * is used, matching how a finger tends to land just after a word. Returns
     * null when there is no word nearby.
     */
    fun wordRangeAt(text: String, offset: Int, locale: Locale = Locale.getDefault()): CharSpan? {
        if (text.isEmpty()) return null
        val clamped = offset.coerceIn(0, text.length - 1)
        val iterator = BreakIterator.getWordInstance(locale).apply { setText(text) }

        fun wordAt(index: Int): CharSpan? {
            if (index !in text.indices) return null
            val end = iterator.following(index)
            val start = iterator.previous()
            if (start == BreakIterator.DONE || end == BreakIterator.DONE) return null
            val candidate = text.substring(start, end)
            return if (candidate.any { it.isLetterOrDigit() }) CharSpan(start, end) else null
        }

        return wordAt(clamped) ?: wordAt(clamped - 1)
    }

    /** Selection after dragging from the [anchor] word to the word at [offset]. */
    fun extendSelection(text: String, anchor: CharSpan, offset: Int, locale: Locale = Locale.getDefault()): CharSpan {
        val target = wordRangeAt(text, offset, locale) ?: return anchor
        return anchor.union(target)
    }

    /**
     * Paints [marks] (saved highlights) and then the live [selection] on top, so
     * the selection stays visible while re-highlighting an already marked span.
     * Ranges are clamped to the text, so stale annotations can never crash rendering.
     */
    fun annotate(
        text: String,
        marks: List<TextMark>,
        selection: CharSpan?,
        selectionColor: Color,
        offset: Int = 0,
    ): AnnotatedString = buildAnnotatedString {
        append(text)
        fun paint(range: CharSpan, color: Color) {
            val start = (range.start - offset).coerceIn(0, text.length)
            val end = (range.end - offset).coerceIn(0, text.length)
            if (start < end) addStyle(SpanStyle(background = color), start, end)
        }
        marks.forEach { paint(it.range, it.color) }
        selection?.let { paint(it, selectionColor) }
    }
}
