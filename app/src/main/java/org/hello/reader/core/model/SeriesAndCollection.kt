package org.hello.reader.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Series(
    val uuid: String,
    val serverId: String,
    val name: String,
    val description: String? = null,
    val totalBooks: Int = 0,
)

@Serializable
data class BookCollection(
    val uuid: String,
    val serverId: String,
    val name: String,
    val description: String? = null,
    val icon: String? = null,
    val color: String? = null,
    val isPublic: Boolean = true,
    val totalBooks: Int = 0,
)

@Serializable
data class BookSeriesEntry(
    val seriesUuid: String,
    val seriesName: String,
    val position: Double? = null,
    val featured: Boolean = false,
) {
    val positionNumber: String? get() = formatSeriesPositionNumber(position)
    val positionLabel: String? get() = formatSeriesPositionLabel(position)
}

@Serializable
data class SeriesBookItem(
    val book: Book,
    val position: Double? = null,
    val featured: Boolean = false,
) {
    val positionNumber: String? get() = formatSeriesPositionNumber(position)
    val positionLabel: String? get() = formatSeriesPositionLabel(position)
}

data class SeriesWithBooks(
    val series: Series,
    val books: List<SeriesBookItem>,
)

data class CollectionGroupedBooks(
    val collection: BookCollection,
    val seriesGroups: List<CollectionSeriesGroup>,
    val standaloneBooks: List<Book>,
)

data class CollectionSeriesGroup(
    val series: Series,
    val books: List<SeriesBookItem>,
)

/** Formats position numeric string: 1.0 -> "1", 2.5 -> "2.5", null -> null */
fun formatSeriesPositionNumber(position: Double?): String? {
    if (position == null) return null
    return if (position % 1.0 == 0.0) {
        position.toLong().toString()
    } else {
        position.toString().trimEnd('0').trimEnd('.')
    }
}

/** Formats position label: 1.0 -> "Book 1", 2.5 -> "Book 2.5", null -> null */
fun formatSeriesPositionLabel(position: Double?): String? {
    val numStr = formatSeriesPositionNumber(position) ?: return null
    return "Book $numStr"
}

/**
 * Series comparator:
 * 1. position ASC numeric (1 < 2 < 2.5 < 10)
 * 2. null positions placed last, sorted alphabetically by book title
 */
val SERIES_BOOK_COMPARATOR = Comparator<SeriesBookItem> { a, b ->
    when {
        a.position != null && b.position != null -> {
            val cmp = a.position.compareTo(b.position)
            if (cmp != 0) cmp else a.book.title.compareTo(b.book.title, ignoreCase = true)
        }
        a.position != null && b.position == null -> -1
        a.position == null && b.position != null -> 1
        else -> a.book.title.compareTo(b.book.title, ignoreCase = true)
    }
}

/**
 * Groups a collection's books by series in series order, followed by standalone books.
 */
fun groupCollectionBooks(
    collection: BookCollection,
    books: List<Book>,
    bookSeriesEntries: Map<String, List<BookSeriesEntry>>,
    seriesMap: Map<String, Series>,
): CollectionGroupedBooks {
    val seriesToBooks = mutableMapOf<String, MutableList<SeriesBookItem>>()
    val standalone = mutableListOf<Book>()

    for (book in books) {
        val seriesEntries = bookSeriesEntries[book.id].orEmpty()
        if (seriesEntries.isEmpty()) {
            standalone.add(book)
        } else {
            for (entry in seriesEntries) {
                val list = seriesToBooks.getOrPut(entry.seriesUuid) { mutableListOf() }
                list.add(SeriesBookItem(book = book, position = entry.position, featured = entry.featured))
            }
        }
    }

    val groups = seriesToBooks.mapNotNull { (seriesUuid, items) ->
        val series = seriesMap[seriesUuid] ?: Series(
            uuid = seriesUuid,
            serverId = collection.serverId,
            name = items.firstOrNull()?.book?.title ?: "Series",
        )
        val sortedBooks = items.sortedWith(SERIES_BOOK_COMPARATOR)
        CollectionSeriesGroup(series = series, books = sortedBooks)
    }.sortedBy { it.series.name.lowercase() }

    return CollectionGroupedBooks(
        collection = collection,
        seriesGroups = groups,
        standaloneBooks = standalone.sortedBy { it.title.lowercase() },
    )
}
