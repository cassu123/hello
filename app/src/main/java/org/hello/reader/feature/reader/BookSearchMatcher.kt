package org.hello.reader.feature.reader

import kotlinx.coroutines.ensureActive
import org.hello.reader.core.engine.smil.BookChapters
import java.text.Normalizer
import kotlin.coroutines.CoroutineContext

/** Pre-indexed normalized sentence for high-performance in-book search across large novels. */
data class SearchableSentence(
    val sentence: ReaderSentenceItem,
    val normalizedText: String,
    val indexMapping: IntArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SearchableSentence) return false
        return sentence == other.sentence && normalizedText == other.normalizedText
    }

    override fun hashCode(): Int = sentence.hashCode() * 31 + normalizedText.hashCode()
}

/** A single match within a sentence or paragraph. */
data class BookSearchResult(
    val elementId: String,
    val textHref: String,
    val chapterIndex: Int,
    val chapterTitle: String?,
    val snippet: String,
    val matchRangeInSnippet: IntRange,
)

/** Search results grouped under a specific chapter. */
data class ChapterSearchResults(
    val chapterIndex: Int,
    val chapterTitle: String?,
    val results: List<BookSearchResult>,
)

/** UI state for in-book search sheet. */
sealed interface BookSearchState {
    data object Idle : BookSearchState
    data object Searching : BookSearchState
    data class Results(
        val query: String,
        val totalMatches: Int,
        val chapters: List<ChapterSearchResults>,
    ) : BookSearchState
    data class Empty(val query: String) : BookSearchState
}

/**
 * Pure search matcher for book sentences:
 * - ASCII fast path for low allocations
 * - Diacritic-insensitive (e.g. searching "chateau" matches "Château", "cafe" matches "café")
 * - Multi-word phrase matching
 * - Surrounding snippet generation with the exact matched character range
 * - Cooperative coroutine cancellation
 */
object BookSearchMatcher {

    /**
     * Strips diacritics and converts to lowercase, returning the normalized string
     * along with an index mapping array where mapping[normIdx] = origIdx in [text].
     * Uses an ASCII fast-path to eliminate per-character Normalizer allocations on standard text.
     */
    fun normalizeWithMapping(text: String): Pair<String, IntArray> {
        val stripped = StringBuilder(text.length)
        val mapping = ArrayList<Int>(text.length)

        for (i in text.indices) {
            val c = text[i]
            if (c.code < 0x80) {
                val lower = if (c in 'A'..'Z') (c.code + 32).toChar() else c
                stripped.append(lower)
                mapping.add(i)
                continue
            }
            val decomposed = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
            for (d in decomposed) {
                val type = Character.getType(d)
                if (type != Character.NON_SPACING_MARK.toInt() &&
                    type != Character.COMBINING_SPACING_MARK.toInt() &&
                    type != Character.ENCLOSING_MARK.toInt()
                ) {
                    stripped.append(d.lowercaseChar())
                    mapping.add(i)
                }
            }
        }
        return stripped.toString() to mapping.toIntArray()
    }

    /**
     * Normalizes a query string by stripping diacritics, lowercasing, and trimming.
     */
    fun normalizeQuery(query: String): String {
        val sb = StringBuilder(query.length)
        for (i in query.indices) {
            val c = query[i]
            if (c.code < 0x80) {
                val lower = if (c in 'A'..'Z') (c.code + 32).toChar() else c
                sb.append(lower)
                continue
            }
            val decomposed = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
            for (d in decomposed) {
                val type = Character.getType(d)
                if (type != Character.NON_SPACING_MARK.toInt() &&
                    type != Character.COMBINING_SPACING_MARK.toInt() &&
                    type != Character.ENCLOSING_MARK.toInt()
                ) {
                    sb.append(d.lowercaseChar())
                }
            }
        }
        return sb.toString().trim()
    }

    /** Pre-computes normalized text and index mappings for all sentences once when a book opens. */
    fun prepareSentences(sentences: List<ReaderSentenceItem>): List<SearchableSentence> =
        sentences.map { sentence ->
            val (normText, mapping) = normalizeWithMapping(sentence.text)
            SearchableSentence(sentence, normText, mapping)
        }

    /**
     * Searches pre-indexed [searchableSentences] for occurrences of [query], grouping results by chapter.
     * Cooperatively cancels every 200 sentences if [coroutineContext] is cancelled.
     */
    @JvmName("searchIndexed")
    fun search(
        searchableSentences: List<SearchableSentence>,
        chapters: BookChapters,
        query: String,
        contextChars: Int = 40,
        maxResults: Int = 500,
        coroutineContext: CoroutineContext? = null,
    ): List<ChapterSearchResults> {
        val normQuery = normalizeQuery(query)
        if (normQuery.isEmpty()) return emptyList()

        val results = ArrayList<BookSearchResult>()

        for ((idx, item) in searchableSentences.withIndex()) {
            if (idx % 200 == 0) {
                coroutineContext?.ensureActive()
            }
            if (results.size >= maxResults) break

            val normText = item.normalizedText
            val mapping = item.indexMapping
            if (normText.isEmpty() || mapping.isEmpty()) continue

            val originalText = item.sentence.text

            var searchIdx = 0
            while (searchIdx <= normText.length - normQuery.length) {
                val matchIdx = normText.indexOf(normQuery, searchIdx)
                if (matchIdx < 0) break

                val matchEndNorm = matchIdx + normQuery.length
                val origStart = mapping.getOrElse(matchIdx) { 0 }
                var origEnd = mapping.getOrElse(matchEndNorm - 1) { originalText.length - 1 } + 1
                while (origEnd < originalText.length && Character.getType(originalText[origEnd]) == Character.NON_SPACING_MARK.toInt()) {
                    origEnd++
                }

                val (snippet, matchRange) = buildSnippet(originalText, origStart, origEnd, contextChars)

                val chIdx = chapters.indexOfElement(item.sentence.elementId) ?: 0
                val chTitle = chapters[chIdx]?.title?.takeIf { it.isNotBlank() }
                    ?: item.sentence.chapterTitle?.takeIf { it.isNotBlank() }

                results.add(
                    BookSearchResult(
                        elementId = item.sentence.elementId,
                        textHref = item.sentence.textHref,
                        chapterIndex = chIdx,
                        chapterTitle = chTitle,
                        snippet = snippet,
                        matchRangeInSnippet = matchRange,
                    )
                )

                if (results.size >= maxResults) break
                searchIdx = matchIdx + maxOf(1, normQuery.length)
            }
        }

        return results.groupBy { it.chapterIndex }
            .map { (chIdx, items) ->
                ChapterSearchResults(
                    chapterIndex = chIdx,
                    chapterTitle = items.first().chapterTitle,
                    results = items,
                )
            }
            .sortedBy { it.chapterIndex }
    }

    /** Convenience search function taking raw sentences. */
    fun search(
        sentences: List<ReaderSentenceItem>,
        chapters: BookChapters,
        query: String,
        contextChars: Int = 40,
        maxResults: Int = 500,
        coroutineContext: CoroutineContext? = null,
    ): List<ChapterSearchResults> = search(
        searchableSentences = prepareSentences(sentences),
        chapters = chapters,
        query = query,
        contextChars = contextChars,
        maxResults = maxResults,
        coroutineContext = coroutineContext,
    )

    internal fun buildSnippet(
        text: String,
        matchStart: Int,
        matchEnd: Int,
        contextChars: Int = 40,
    ): Pair<String, IntRange> {
        val safeStart = matchStart.coerceIn(0, text.length)
        val safeEnd = matchEnd.coerceIn(safeStart, text.length)

        var ctxStart = (safeStart - contextChars).coerceAtLeast(0)
        if (ctxStart > 0) {
            val space = text.indexOf(' ', ctxStart)
            if (space in ctxStart until safeStart) {
                ctxStart = space + 1
            }
        }

        var ctxEnd = (safeEnd + contextChars).coerceAtMost(text.length)
        if (ctxEnd < text.length) {
            val space = text.lastIndexOf(' ', ctxEnd)
            if (space > safeEnd && space >= ctxEnd - 15) {
                ctxEnd = space
            }
        }

        val prefixEllipsis = if (ctxStart > 0) "…" else ""
        val suffixEllipsis = if (ctxEnd < text.length) "…" else ""

        val prefix = prefixEllipsis + text.substring(ctxStart, safeStart).trimStart()
        val matched = text.substring(safeStart, safeEnd)
        val suffix = text.substring(safeEnd, ctxEnd).trimEnd() + suffixEllipsis

        val snippet = prefix + matched + suffix
        val rangeStart = prefix.length
        val rangeEnd = rangeStart + matched.length
        return snippet to (rangeStart until rangeEnd)
    }
}
