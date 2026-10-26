package org.hello.reader.core.engine.smil

/**
 * One table-of-contents entry. A chapter starts at the first paragraph of its spine document
 * and runs until the next chapter; spine documents the TOC doesn't list (title pages,
 * interludes) fold into the chapter before them.
 */
data class BookChapter(
    val index: Int,
    /** From the TOC or a heading-like first paragraph; null means the UI shows "Chapter N". */
    val title: String?,
    val href: String,
    val firstElementId: String,
    /** Narration start in the loaded audio, or null when the book has no usable timing. */
    val startMs: Long?,
    /** Narration end in the loaded audio, or null when the book has no usable timing. */
    val endMs: Long?,
)

/** The minimum a paragraph needs to be placed in a chapter. */
data class ChapterParagraph(
    val elementId: String,
    val textHref: String,
    val text: String,
    val clipBeginMs: Long,
    val clipEndMs: Long,
)

/** A book's chapters plus lookups by paragraph and by narration position. */
class BookChapters private constructor(
    val chapters: List<BookChapter>,
    private val chapterByElement: Map<String, Int>,
) {
    val size: Int get() = chapters.size

    /** Prev/next and a contents list only make sense with more than one chapter. */
    val isNavigable: Boolean get() = chapters.size > 1

    /** True when every chapter has narration timing, in reading order. */
    val hasTiming: Boolean get() = chapters.isNotEmpty() && chapters.all { it.startMs != null }

    operator fun get(index: Int): BookChapter? = chapters.getOrNull(index)

    fun indexOfElement(elementId: String?): Int? = elementId?.let(chapterByElement::get)

    /** The chapter narrated at [positionMs]: the last one starting at or before it. */
    fun indexAt(positionMs: Long): Int? {
        if (!hasTiming) return null
        var low = 0
        var high = chapters.lastIndex
        var found = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (chapters[mid].startMs!! <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /** Chapter starts as fractions of [totalMs], for tick marks on a scrubber. Skips the first (always 0). */
    fun tickFractions(totalMs: Long): List<Float> {
        if (!hasTiming || totalMs <= 0L || !isNavigable) return emptyList()
        return chapters.drop(1).map { (it.startMs!!.toDouble() / totalMs).toFloat().coerceIn(0f, 1f) }
    }

    companion object {
        val EMPTY = BookChapters(emptyList(), emptyMap())

        private const val MAX_FALLBACK_TITLE = 80

        fun buildFromTracks(tracks: List<AudioTrackItem>): BookChapters {
            if (tracks.isEmpty()) return EMPTY
            val chapters = tracks.mapIndexed { index, track ->
                val rawName = track.file.nameWithoutExtension
                val cleanTitle = rawName.replace('_', ' ').replace('-', ' ').trim()
                BookChapter(
                    index = index,
                    title = cleanTitle.ifBlank { "Track ${index + 1}" },
                    href = track.audioSrc,
                    firstElementId = "track_$index",
                    startMs = track.startOffsetMs,
                    endMs = if (track.durationMs > 0L) track.startOffsetMs + track.durationMs else null,
                )
            }
            val chapterByElement = chapters.associate { it.firstElementId to it.index }
            return BookChapters(chapters, chapterByElement)
        }

        /**
         * Groups [paragraphs] (in reading order) into chapters. [tocTitles] maps spine hrefs to
         * TOC labels; when it is empty every spine document becomes a chapter. With [timed],
         * chapters get narration bounds, dropped again if they don't run forward through the audio
         * (e.g. a readaloud whose chapters each restart at 0 in their own file).
         */
        fun build(
            paragraphs: List<ChapterParagraph>,
            tocTitles: Map<String, String> = emptyMap(),
            timed: Boolean = false,
        ): BookChapters {
            if (paragraphs.isEmpty()) return EMPTY
            val titleFor = TocLookup(tocTitles)

            val groups = mutableListOf<Pair<String, MutableList<ChapterParagraph>>>()
            var lastHref: String? = null
            for (paragraph in paragraphs) {
                val startsChapter = paragraph.textHref != lastHref && (
                    groups.isEmpty() || tocTitles.isEmpty() || titleFor(paragraph.textHref) != null
                    )
                if (startsChapter) {
                    groups += paragraph.textHref to mutableListOf(paragraph)
                } else {
                    groups.last().second += paragraph
                }
                lastHref = paragraph.textHref
            }

            var chapters = groups.mapIndexed { index, (href, items) ->
                BookChapter(
                    index = index,
                    title = titleFor(href) ?: fallbackTitle(items.first().text),
                    href = href,
                    firstElementId = items.first().elementId,
                    startMs = if (timed) items.minOf { it.clipBeginMs } else null,
                    endMs = if (timed) items.maxOf { it.clipEndMs } else null,
                )
            }
            // Each chapter must run forward through the audio; if starts restart (e.g. per-file), drop timing.
            if (timed) {
                if (chapters.zipWithNext().any { (a, b) -> b.startMs!! <= a.startMs!! }) {
                    chapters = chapters.map { it.copy(startMs = null, endMs = null) }
                } else {
                    chapters = chapters.mapIndexed { i, ch ->
                        val nextStart = chapters.getOrNull(i + 1)?.startMs
                        if (nextStart != null && ch.endMs != null && ch.endMs > nextStart) {
                            ch.copy(endMs = maxOf(ch.startMs ?: 0L, nextStart))
                        } else {
                            ch
                        }
                    }
                }
            }

            // Element ids can repeat across spine documents; the first occurrence wins, as in the reader.
            val byElement = HashMap<String, Int>(paragraphs.size)
            groups.forEachIndexed { index, (_, items) ->
                items.forEach { byElement.putIfAbsent(it.elementId, index) }
            }
            return BookChapters(chapters, byElement)
        }

        /** A short first paragraph is usually the chapter heading. */
        private fun fallbackTitle(firstText: String): String? =
            firstText.trim().takeIf { it.isNotEmpty() && it.length <= MAX_FALLBACK_TITLE }
    }
}

/** Matches TOC hrefs to spine hrefs exactly, then by file name (TOCs and SMIL resolve paths differently). */
private class TocLookup(titles: Map<String, String>) {
    private val normalizedTitles: Map<String, String> = titles.entries
        .associate { (k, v) -> k.substringBefore('#') to v }
    private val byFileName: Map<String, String> = titles.entries
        .groupBy { it.key.substringBefore('#').substringAfterLast('/') }
        .mapValues { it.value.first().value }

    operator fun invoke(href: String): String? {
        val clean = href.substringBefore('#')
        return normalizedTitles[clean] ?: byFileName[clean.substringAfterLast('/')]
    }
}

private val chapterNumberWords = listOf(
    "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
    "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen", "Twenty",
    "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety", "Hundred",
    "First", "Second", "Third", "Fourth", "Fifth", "Sixth", "Seventh", "Eighth", "Ninth", "Tenth",
).joinToString("|")

private val chapterPrefixRegex = Regex(
    """^(?:Chapter|Book|Part|Letter)\s+(?:[0-9]+|[IVXLCDM]+|$chapterNumberWords)\b.*""",
    RegexOption.IGNORE_CASE,
)

private val prologueEpilogueRegex = Regex(
    """^(?:Prologue|Epilogue)\b.*""",
    RegexOption.IGNORE_CASE,
)

private val romanNumeralRegex = Regex(
    """^[IVXLCDM]{1,8}\.?$""",
)

/**
 * Heuristic to detect common chapter heading formats (e.g. "Chapter 1", "Part One", "Prologue", "IV").
 */
fun isChapterHeadingText(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.length > 80 || trimmed.isEmpty()) return false
    return chapterPrefixRegex.matches(trimmed) ||
        prologueEpilogueRegex.matches(trimmed) ||
        romanNumeralRegex.matches(trimmed)
}
