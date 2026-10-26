package org.hello.reader.feature.library

import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.DownloadStatus
import org.hello.reader.core.model.SyncCheckpoint
import androidx.annotation.StringRes
import org.hello.reader.R

/** Chip labels live with the chips (`LibraryFilter.label()` in LibraryScreen). */
sealed interface LibraryFilter {
    data object All : LibraryFilter
    data object Readaloud : LibraryFilter
    data object EbookOnly : LibraryFilter
    data object AudioOnly : LibraryFilter
    data object Downloaded : LibraryFilter
    data object Unread : LibraryFilter
    data class Status(val name: String, val label: String) : LibraryFilter
    data class Tag(val name: String) : LibraryFilter

    companion object {
        val fixed: List<LibraryFilter> = listOf(All, Readaloud, EbookOnly, AudioOnly, Downloaded, Unread)
    }
}

enum class LibraryViewMode { GRID, LIST }

enum class LibrarySort(@StringRes val labelRes: Int) {
    RECENT(R.string.sort_recent),
    TITLE(R.string.sort_title),
    AUTHOR(R.string.sort_author),
    ADDED(R.string.sort_added),
}

/** A book in the hero "Continue" carousel with its progress resolved from the latest checkpoint. */
data class ContinueItem(
    val book: Book,
    /** 0f..1f */
    val progress: Float,
    /** Listening time left, or null when the book has no audio duration. */
    val remainingMs: Long?,
)

/** Pure library shaping logic, kept out of the view model so it is unit-testable. */
object LibraryFilters {

    fun progressOf(checkpoint: SyncCheckpoint?): Float =
        ((checkpoint?.progressPercent ?: 0f) / 100f).coerceIn(0f, 1f)

    fun effectiveProgress(book: Book, checkpoint: SyncCheckpoint?): Float = when {
        book.isRead -> 1f
        book.isToRead -> 0f
        else -> {
            val prog = progressOf(checkpoint)
            if (book.readingStatus == null && prog < 0.01f) 0f else prog
        }
    }

    fun isUnread(book: Book, checkpoint: SyncCheckpoint?): Boolean =
        progressOf(checkpoint) <= 0f && (checkpoint?.audioPositionMs ?: 0L) <= 0L && book.lastOpenedAt == null

    fun matches(book: Book, checkpoint: SyncCheckpoint?, filter: LibraryFilter): Boolean = when (filter) {
        LibraryFilter.All -> true
        LibraryFilter.Readaloud -> book.mediaType == BookMediaType.READALOUD
        LibraryFilter.EbookOnly -> book.mediaType == BookMediaType.EBOOK_ONLY
        LibraryFilter.AudioOnly -> book.mediaType == BookMediaType.AUDIO_ONLY
        LibraryFilter.Downloaded -> book.downloadStatus == DownloadStatus.DOWNLOADED
        LibraryFilter.Unread -> isUnread(book, checkpoint)
        is LibraryFilter.Status -> {
            val targetName = filter.name.lowercase()
            if (targetName in listOf("read", "finished", "completed")) {
                book.isRead
            } else if (targetName in listOf("to_read", "to-read", "toread", "want_to_read")) {
                book.readingStatus?.lowercase() in listOf("to_read", "to-read", "toread", "want_to_read") ||
                    (book.readingStatus == null && !book.isRead && progressOf(checkpoint) < 0.01f)
            } else if (targetName in listOf("reading", "currently_reading", "in_progress")) {
                book.readingStatus?.lowercase() in listOf("reading", "currently_reading", "in_progress") ||
                    (book.readingStatus == null && !book.isRead && progressOf(checkpoint) in 0.01f..0.99f)
            } else {
                book.readingStatus?.equals(filter.name, ignoreCase = true) == true ||
                    book.readingStatusLabel?.equals(filter.label, ignoreCase = true) == true
            }
        }
        is LibraryFilter.Tag -> book.tags.any { it.equals(filter.name, ignoreCase = true) }
    }

    /** Case-insensitive match on title, authors, narrators, tags, series, and collections; a blank query matches everything. */
    fun matchesQuery(
        book: Book,
        query: String,
        seriesNames: List<String> = emptyList(),
        collectionNames: List<String> = emptyList(),
    ): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return book.title.contains(q, ignoreCase = true) ||
            book.authors.any { it.contains(q, ignoreCase = true) } ||
            book.narrators.any { it.contains(q, ignoreCase = true) } ||
            book.tags.any { it.contains(q, ignoreCase = true) } ||
            seriesNames.any { it.contains(q, ignoreCase = true) } ||
            collectionNames.any { it.contains(q, ignoreCase = true) }
    }

    fun apply(
        books: List<Book>,
        checkpoints: Map<String, SyncCheckpoint>,
        filter: LibraryFilter,
        sort: LibrarySort,
    ): List<Book> = books
        .filter { matches(it, checkpoints[it.id], filter) }
        .sortedWith(comparatorFor(sort))

    fun comparatorFor(sort: LibrarySort): Comparator<Book> {
        val byTitle = compareBy<Book, String>(String.CASE_INSENSITIVE_ORDER) { sortableTitle(it.title) }
        return when (sort) {
            LibrarySort.RECENT -> compareByDescending<Book> { it.lastOpenedAt ?: Long.MIN_VALUE }
                .thenByDescending { it.createdAt }
                .then(byTitle)
            LibrarySort.TITLE -> byTitle
            LibrarySort.AUTHOR -> compareBy<Book, String>(String.CASE_INSENSITIVE_ORDER) {
                it.authors.firstOrNull()?.let(::authorSortKey) ?: "￿"
            }.then(byTitle)
            LibrarySort.ADDED -> compareByDescending<Book> { it.createdAt }.then(byTitle)
        }
    }

    /** Distinct tags across the library, case-insensitively de-duplicated, alphabetical. */
    fun tagsOf(books: List<Book>): List<String> = books
        .flatMap { it.tags }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun continueItems(books: List<Book>, checkpoints: Map<String, SyncCheckpoint>): List<ContinueItem> =
        books
            .filter { book ->
                val progress = effectiveProgress(book, checkpoints[book.id])
                !book.isRead && !book.isToRead && progress < 1f
            }
            .map { book ->
                val checkpoint = checkpoints[book.id]
                val progress = effectiveProgress(book, checkpoint)
                val totalMs = checkpoint?.totalDurationMs?.takeIf { it > 0 } ?: (book.durationSeconds * 1000L)
                val remaining = when {
                    totalMs <= 0L -> null
                    checkpoint != null && checkpoint.totalDurationMs > 0 ->
                        (checkpoint.totalDurationMs - checkpoint.audioPositionMs).coerceAtLeast(0L)
                    else -> (totalMs * (1f - progress)).toLong()
                }
                ContinueItem(book, progress, remaining)
            }

    private fun sortableTitle(title: String): String {
        val trimmed = title.trim()
        val lower = trimmed.lowercase()
        for (article in listOf("the ", "a ", "an ")) {
            if (lower.startsWith(article) && trimmed.length > article.length) return trimmed.substring(article.length)
        }
        return trimmed
    }

    /** "Ursula K. Le Guin" sorts under "Le Guin"; "Tolkien, J.R.R." is already surname-first. */
    private fun authorSortKey(name: String): String {
        val trimmed = name.trim()
        if (trimmed.contains(',')) return trimmed
        val parts = trimmed.split(Regex("\\s+"))
        if (parts.size < 2) return trimmed
        val particles = setOf("le", "de", "van", "von", "der", "da", "di", "du", "la")
        var surnameStart = parts.lastIndex
        while (surnameStart > 1 && parts[surnameStart - 1].lowercase() in particles) surnameStart--
        return parts.drop(surnameStart).joinToString(" ") + ", " + parts.take(surnameStart).joinToString(" ")
    }
}
