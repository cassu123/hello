package org.hello.reader.core.network.model

import org.hello.reader.core.database.entity.BookCollectionCrossRef
import org.hello.reader.core.database.entity.BookSeriesCrossRef
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookCollection
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.Series
import org.hello.reader.core.network.NetworkModule
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

data class CatalogResult(
    val books: List<Book>,
    val series: List<Series>,
    val collections: List<BookCollection>,
    val bookSeriesRelations: List<BookSeriesCrossRef>,
    val bookCollectionRelations: List<BookCollectionCrossRef>,
)

/**
 * Resilient JSON catalog parser supporting all versions of Storyteller:
 * - Storyteller v2 `{ "books": [ ... ] }` (camelCase, object-based authors, float duration)
 * - Storyteller v1 `{ "items": [ ... ] }` (snake_case, string-array authors)
 * - Raw JSON array `[ { ... } ]`
 * - Generic envelopes: `{ "data": [...] }`, `{ "results": [...] }`, `{ "library": [...] }`
 * - Graceful fallback on missing/unexpected field types, never crashing on unknown fields.
 */
object CatalogParser {

    fun parse(
        raw: String,
        serverId: String,
        userId: String,
        baseUrl: String,
    ): List<Book> = parseFull(raw, serverId, userId, baseUrl).books

    fun parseFull(
        raw: String,
        serverId: String,
        userId: String,
        baseUrl: String,
    ): CatalogResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return CatalogResult(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }

        val rootElement = try {
            NetworkModule.json.parseToJsonElement(trimmed)
        } catch (e: Exception) {
            throw IllegalArgumentException("Catalog payload is not valid JSON", e)
        }

        val bookElements = when (rootElement) {
            is JsonArray -> rootElement
            is JsonObject -> extractBookArray(rootElement)
            else -> emptyList()
        }

        val books = mutableListOf<Book>()
        val seriesMap = mutableMapOf<String, Series>()
        val collectionsMap = mutableMapOf<String, BookCollection>()
        val bookSeriesRelations = mutableListOf<BookSeriesCrossRef>()
        val bookCollectionRelations = mutableListOf<BookCollectionCrossRef>()

        for (element in bookElements) {
            if (element !is JsonObject) continue
            val book = parseBook(element, serverId, userId, baseUrl) ?: continue
            books.add(book)

            val seriesArray = (element["series"] ?: element["seriesList"]) as? JsonArray
            if (seriesArray != null) {
                for (sEl in seriesArray) {
                    if (sEl !is JsonObject) continue
                    val uuid = extractString(sEl, "uuid", "id") ?: continue
                    val name = extractString(sEl, "name", "title") ?: "Series"
                    val description = extractString(sEl, "description")
                    val position = extractDouble(sEl, "position")
                    val featured = extractBoolean(sEl, "featured") ?: false

                    if (!seriesMap.containsKey(uuid)) {
                        seriesMap[uuid] = Series(
                            uuid = uuid,
                            serverId = serverId,
                            name = name,
                            description = description,
                        )
                    }
                    bookSeriesRelations.add(
                        BookSeriesCrossRef(
                            bookId = book.id,
                            serverId = serverId,
                            seriesUuid = uuid,
                            position = position,
                            featured = featured,
                        )
                    )
                }
            }

            val collArray = (element["collections"] ?: element["collection"]) as? JsonArray
            if (collArray != null) {
                for (cEl in collArray) {
                    if (cEl !is JsonObject) continue
                    val uuid = extractString(cEl, "uuid", "id") ?: continue
                    val name = extractString(cEl, "name", "title") ?: "Collection"
                    val description = extractString(cEl, "description")
                    val icon = extractString(cEl, "icon")
                    val color = extractString(cEl, "color")
                    val isPublic = extractBoolean(cEl, "public", "is_public") ?: true

                    if (!collectionsMap.containsKey(uuid)) {
                        collectionsMap[uuid] = BookCollection(
                            uuid = uuid,
                            serverId = serverId,
                            name = name,
                            description = description,
                            icon = icon,
                            color = color,
                            isPublic = isPublic,
                        )
                    }
                    bookCollectionRelations.add(
                        BookCollectionCrossRef(
                            bookId = book.id,
                            serverId = serverId,
                            collectionUuid = uuid,
                        )
                    )
                }
            }
        }

        val seriesWithCounts = seriesMap.values.map { s ->
            val count = bookSeriesRelations.count { it.seriesUuid == s.uuid }
            s.copy(totalBooks = count)
        }
        val collectionsWithCounts = collectionsMap.values.map { c ->
            val count = bookCollectionRelations.count { it.collectionUuid == c.uuid }
            c.copy(totalBooks = count)
        }

        return CatalogResult(
            books = books,
            series = seriesWithCounts,
            collections = collectionsWithCounts,
            bookSeriesRelations = bookSeriesRelations,
            bookCollectionRelations = bookCollectionRelations,
        )
    }

    private fun extractBookArray(obj: JsonObject): List<JsonElement> {
        // Priority list of standard envelope keys across Storyteller versions
        val envelopeKeys = listOf(
            "books",
            "items",
            "data",
            "results",
            "catalog",
            "library",
            "content",
        )
        for (key in envelopeKeys) {
            val candidate = obj[key]
            if (candidate is JsonArray) {
                return candidate
            }
        }

        // If the object has a single array field, assume it holds the books
        val arrayValues = obj.values.filterIsInstance<JsonArray>()
        if (arrayValues.size == 1) {
            return arrayValues.first()
        }

        // If the object itself looks like a single book (has id or uuid, plus title or name)
        if ((obj.containsKey("id") || obj.containsKey("uuid")) &&
            (obj.containsKey("title") || obj.containsKey("name"))
        ) {
            return listOf(obj)
        }

        return emptyList()
    }

    private fun parseBook(
        obj: JsonObject,
        serverId: String,
        userId: String,
        baseUrl: String,
    ): Book? {
        val id = extractString(obj, "id", "uuid", "book_id", "bookId", "slug")
            ?: return null

        val title = extractString(obj, "title", "name", "book_title", "bookTitle")
            ?: "Untitled"

        val authors = extractStringList(
            obj = obj,
            listKeys = listOf("authors", "author"),
            itemKeys = listOf("name", "full_name", "fullName", "author_name", "author"),
        )

        val narrators = extractStringList(
            obj = obj,
            listKeys = listOf("narrators", "narrator", "readers", "reader"),
            itemKeys = listOf("name", "full_name", "fullName"),
        )

        val tags = extractStringList(
            obj = obj,
            listKeys = listOf("tags", "categories", "genres"),
            itemKeys = listOf("name", "tag", "label", "category"),
        )

        val description = extractString(obj, "description", "summary", "synopsis", "blurb")

        val coverUrl = resolveCoverUrl(obj, id, baseUrl)

        val mediaType = resolveMediaType(obj)
        val (hasReadaloud, hasEpub, hasAudio) = resolveFormatAvailability(obj, mediaType)

        val durationSeconds = extractLong(
            obj = obj,
            keys = listOf("durationSeconds", "duration_seconds", "duration", "audio_duration", "total_duration"),
        ).let { topLevel ->
            if (topLevel > 0L) topLevel
            else {
                val audiobookObj = obj["audiobook"] as? JsonObject
                if (audiobookObj != null) {
                    extractLong(audiobookObj, listOf("durationSeconds", "duration_seconds", "duration"))
                } else 0L
            }
        }

        val totalChapters = extractInt(
            obj = obj,
            keys = listOf("totalChapters", "total_chapters", "chapters_count", "chapters"),
        )

        var statusUuid: String? = null
        val (readingStatus, readingStatusLabel) = when (val statusEl = obj["status"] ?: obj["readingStatus"] ?: obj["reading_status"]) {
            is JsonObject -> {
                statusUuid = extractString(statusEl, "uuid", "id", "status_uuid", "status_id")
                val name = extractString(statusEl, "name", "id", "slug")
                val label = extractString(statusEl, "label", "title", "name") ?: name
                Pair(name, label)
            }
            is JsonPrimitive -> {
                val str = statusEl.content.trim()
                if (str.isNotBlank()) Pair(str.lowercase(), str) else Pair(null, null)
            }
            else -> Pair(null, null)
        }

        val rating = extractDouble(obj, "rating", "user_rating", "userRating")?.toFloat()
        val publicationDate = extractString(obj, "publication_date", "publicationDate", "published_date", "publishedDate", "published", "release_date")
        val language = extractString(obj, "language", "lang")
        val publisher = extractString(obj, "publisher")
        val isbn = extractString(obj, "isbn", "isbn13", "isbn10")
        val externalRatings = extractString(obj, "external_ratings", "externalRatings") ?: (obj["external_ratings"] ?: obj["externalRatings"])?.toString()

        return Book(
            id = id,
            serverId = serverId,
            userId = userId,
            title = title,
            authors = authors,
            narrators = narrators,
            coverUrl = coverUrl,
            description = description,
            mediaType = mediaType,
            durationSeconds = durationSeconds,
            totalChapters = totalChapters,
            tags = tags,
            hasReadaloud = hasReadaloud,
            hasEbook = hasEpub,
            hasAudiobook = hasAudio,
            readingStatus = readingStatus,
            readingStatusLabel = readingStatusLabel,
            statusUuid = statusUuid,
            rating = rating,
            publicationDate = publicationDate,
            language = language,
            publisher = publisher,
            isbn = isbn,
            externalRatings = externalRatings,
        )
    }

    private fun resolveCoverUrl(obj: JsonObject, bookId: String, baseUrl: String): String {
        val rawPath = extractString(
            obj,
            "coverUrl",
            "cover_url",
            "coverPath",
            "cover_path",
            "cover",
            "cover_image",
            "thumbnailUrl",
            "thumbnail_url",
            "thumbnail",
        ) ?: run {
            val ebookObj = obj["ebook"] as? JsonObject
            val audioObj = obj["audiobook"] as? JsonObject
            val readaloudObj = obj["readaloud"] as? JsonObject
            (ebookObj?.let { extractString(it, "coverUrl", "cover_url", "coverPath", "cover_path", "cover") }
                ?: audioObj?.let { extractString(it, "coverUrl", "cover_url", "coverPath", "cover_path", "cover") }
                ?: readaloudObj?.let { extractString(it, "coverUrl", "cover_url", "coverPath", "cover_path", "cover") })
        }
        val trimmedBase = baseUrl.trimEnd('/')
        return when {
            rawPath.isNullOrBlank() -> "$trimmedBase/api/books/$bookId/cover"
            rawPath.startsWith("http://") || rawPath.startsWith("https://") -> rawPath
            rawPath.startsWith("/") -> "$trimmedBase$rawPath"
            else -> "$trimmedBase/$rawPath"
        }
    }

    private fun resolveMediaType(obj: JsonObject): BookMediaType {
        // Explicit media_type / mediaType override if provided
        val explicitType = extractString(obj, "mediaType", "media_type", "type")?.uppercase()
        when (explicitType) {
            "READALOUD", "READ_ALOUD", "SYNCED", "ALIGNED" -> return BookMediaType.READALOUD
            "AUDIO_ONLY", "AUDIOBOOK", "AUDIO" -> return BookMediaType.AUDIO_ONLY
            "EBOOK_ONLY", "EBOOK", "EPUB" -> return BookMediaType.EBOOK_ONLY
        }

        val isAligned = extractBoolean(obj, "isAligned", "is_aligned", "aligned") ?: false
        val hasAudio = extractBoolean(obj, "hasAudio", "has_audio") ?: hasValidAsset(obj, "audiobook", "audio")
        val hasEpub = extractBoolean(obj, "hasEpub", "has_epub") ?: hasValidAsset(obj, "ebook", "epub") ?: true
        val hasReadaloud = hasValidAsset(obj, "readaloud", "synced") || (isAligned && hasAudio && hasEpub)

        return when {
            hasReadaloud -> BookMediaType.READALOUD
            hasAudio && !hasEpub -> BookMediaType.AUDIO_ONLY
            else -> BookMediaType.EBOOK_ONLY
        }
    }

    private fun resolveFormatAvailability(obj: JsonObject, mediaType: BookMediaType): Triple<Boolean, Boolean, Boolean> {
        val isAligned = extractBoolean(obj, "isAligned", "is_aligned", "aligned") ?: false
        val hasAudio = extractBoolean(obj, "hasAudio", "has_audio") ?: hasValidAsset(obj, "audiobook", "audio")
        val hasEpub = extractBoolean(obj, "hasEpub", "has_epub") ?: hasValidAsset(obj, "ebook", "epub") ?: true
        val hasReadaloud = hasValidAsset(obj, "readaloud", "synced") || (isAligned && hasAudio && hasEpub) || mediaType == BookMediaType.READALOUD
        return Triple(
            hasReadaloud,
            hasEpub || mediaType == BookMediaType.EBOOK_ONLY,
            hasAudio || mediaType == BookMediaType.AUDIO_ONLY,
        )
    }

    private fun hasValidAsset(obj: JsonObject, vararg keys: String): Boolean {
        for (key in keys) {
            val element = obj[key] ?: continue
            when (element) {
                is JsonObject -> {
                    val missing = extractBoolean(element, "missing") ?: false
                    if (missing) continue

                    // If filepath is present, it must be non-null and not blank
                    if (element.containsKey("filepath")) {
                        val filepath = extractString(element, "filepath")
                        if (filepath.isNullOrBlank()) continue
                    }

                    // For readaloud assets, check that alignment is not in a queued/processing/error state
                    if (key in listOf("readaloud", "synced")) {
                        val status = extractString(element, "status")?.uppercase()
                        if (status != null && status in listOf("QUEUED", "PROCESSING", "ALIGNING", "TRANSCRIBING", "FAILED", "ERROR")) {
                            continue
                        }
                    }

                    return true
                }
                is JsonPrimitive -> {
                    val boolVal = element.booleanOrNull
                    if (boolVal != null) return boolVal
                    if (element.content.isNotBlank() && element.content != "null") return true
                }
                else -> return true
            }
        }
        return false
    }

    private fun extractString(obj: JsonObject, vararg keys: String): String? {
        for (key in keys) {
            val element = obj[key]
            if (element is JsonPrimitive) {
                val content = element.content.trim()
                if (content.isNotBlank() && content != "null") {
                    return content
                }
            }
        }
        return null
    }

    private fun extractBoolean(obj: JsonObject, vararg keys: String): Boolean? {
        for (key in keys) {
            val element = obj[key]
            if (element is JsonPrimitive) {
                element.booleanOrNull?.let { return it }
                val content = element.content.trim().lowercase()
                if (content == "true" || content == "1") return true
                if (content == "false" || content == "0") return false
            }
        }
        return null
    }

    private fun extractDouble(obj: JsonObject, vararg keys: String): Double? {
        for (key in keys) {
            val element = obj[key]
            if (element is JsonPrimitive) {
                element.doubleOrNull?.let { return it }
                element.content.toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun extractLong(obj: JsonObject, keys: List<String>): Long {
        for (key in keys) {
            val element = obj[key]
            if (element is JsonPrimitive) {
                element.longOrNull?.let { return it }
                element.doubleOrNull?.let { return it.toLong() }
                element.content.toDoubleOrNull()?.let { return it.toLong() }
            }
        }
        return 0L
    }

    private fun extractInt(obj: JsonObject, keys: List<String>): Int {
        for (key in keys) {
            val element = obj[key]
            if (element is JsonPrimitive) {
                element.intOrNull?.let { return it }
                element.content.toIntOrNull()?.let { return it }
            }
        }
        return 0
    }

    private fun extractStringList(
        obj: JsonObject,
        listKeys: List<String>,
        itemKeys: List<String>,
    ): List<String> {
        for (key in listKeys) {
            val element = obj[key] ?: continue
            when (element) {
                is JsonArray -> {
                    val list = mutableListOf<String>()
                    for (item in element) {
                        when (item) {
                            is JsonPrimitive -> {
                                val text = item.content.trim()
                                if (text.isNotBlank() && text != "null") {
                                    list.add(text)
                                }
                            }
                            is JsonObject -> {
                                for (itemKey in itemKeys) {
                                    val candidate = item[itemKey]
                                    if (candidate is JsonPrimitive) {
                                        val text = candidate.content.trim()
                                        if (text.isNotBlank() && text != "null") {
                                            list.add(text)
                                            break
                                        }
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }
                    return list
                }
                is JsonPrimitive -> {
                    val text = element.content.trim()
                    if (text.isNotBlank() && text != "null") {
                        return if (text.contains(",")) {
                            text.split(",").map { it.trim() }.filter { it.isNotBlank() }
                        } else {
                            listOf(text)
                        }
                    }
                }
                else -> Unit
            }
        }
        return emptyList()
    }
}
