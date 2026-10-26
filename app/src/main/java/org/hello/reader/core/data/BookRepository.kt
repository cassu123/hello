package org.hello.reader.core.data

import androidx.room.withTransaction
import java.io.File
import org.hello.reader.core.database.HelloDatabase
import org.hello.reader.core.database.converter.DatabaseConverters
import org.hello.reader.core.database.dao.BookDao
import org.hello.reader.core.database.dao.BookDownloadDao
import org.hello.reader.core.database.dao.CollectionDao
import org.hello.reader.core.database.dao.SeriesDao
import org.hello.reader.core.database.dao.SyncCheckpointDao
import org.hello.reader.core.database.entity.BookDownloadEntity
import org.hello.reader.core.database.entity.BookEntity
import org.hello.reader.core.database.entity.CollectionEntity
import org.hello.reader.core.database.entity.SeriesEntity
import org.hello.reader.core.database.entity.SyncCheckpointEntity
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookCollection
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.BookSeriesEntry
import org.hello.reader.core.model.CollectionGroupedBooks
import org.hello.reader.core.model.DownloadStatus
import org.hello.reader.core.model.HomeShelfConfig
import org.hello.reader.core.model.HomeShelfType
import org.hello.reader.core.model.LocalLibrary
import org.hello.reader.core.model.ReadingStatusItem
import org.hello.reader.core.model.ServerProfile
import org.hello.reader.core.model.Series
import org.hello.reader.core.model.SeriesBookItem
import org.hello.reader.core.model.SeriesWithBooks
import org.hello.reader.core.model.SyncCheckpoint
import org.hello.reader.core.model.SyncConflict
import org.hello.reader.core.model.groupCollectionBooks
import org.hello.reader.core.network.NetworkModule
import org.hello.reader.core.network.NoActiveServerException
import org.hello.reader.core.network.StorytellerApi
import org.hello.reader.core.network.model.CatalogParser
import org.hello.reader.core.network.model.ReadiumLocationsDto
import org.hello.reader.core.network.model.ReadiumLocatorDto
import org.hello.reader.core.network.model.StorytellerPositionPayloadDto
import org.hello.reader.core.network.model.SyncCheckpointDto
import org.hello.reader.core.security.SecurityVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

private val SERVER_UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

/**
 * Multi-tenant offline-first repository for the user's book catalog and reading/listening
 * checkpoints.
 *
 * - Automatically scopes all Room `Flow` queries to the currently active [ServerProfile]
 *   (`serverId` + `userId`), guaranteeing zero data bleed on server switch.
 * - Supports both the `ARCHITECTURE.md` paginated `/api/books` envelope and Storyteller v2's
 *   top-level JSON array response.
 * - Detects bi-directional sync conflicts where local and remote positions differ by > 60s
 *   and surfaces them in [pendingConflicts] so the UI can prompt the user.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookRepository(
    private val api: StorytellerApi,
    private val bookDao: BookDao,
    private val checkpointDao: SyncCheckpointDao,
    private val vault: SecurityVault,
    private val seriesDao: SeriesDao? = null,
    private val collectionDao: CollectionDao? = null,
    private val database: HelloDatabase? = null,
    private val converters: DatabaseConverters = DatabaseConverters(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val filesDir: File? = null,
    private val downloadDao: BookDownloadDao? = null,
    private val onCheckpointSaved: ((SyncCheckpoint) -> Unit)? = null,
) {
    private val serverBookUuids = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val effectiveDownloadDao get() = downloadDao ?: database?.bookDownloadDao()

    private val _pendingConflicts = MutableStateFlow<List<SyncConflict>>(emptyList())

    /** Unresolved >60s position conflicts awaiting user selection in the UI dialog. */
    val pendingConflicts: StateFlow<List<SyncConflict>> = _pendingConflicts.asStateFlow()

    /** Observes all books for the currently active server profile, plus any imported local books. */
    fun observeLibrary(): Flow<List<Book>> =
        vault.activeProfile.flatMapLatest { profile ->
            if (profile == null) {
                bookDao.observeLocalBooks()
                    .map { entities -> entities.map { it.toDomain(converters) } }
            } else {
                bookDao.observeBooks(profile.id, profile.userId)
                    .map { entities -> entities.map { it.toDomain(converters) } }
            }
        }

    /** Observes in-progress books for the Hero "Continue" Carousel. */
    fun observeContinueReading(): Flow<List<Book>> =
        vault.activeProfile.flatMapLatest { profile ->
            val flow = if (profile == null) {
                bookDao.observeLocalContinueReading()
            } else {
                bookDao.observeContinueReading(profile.id, profile.userId)
            }
            flow.map { entities ->
                entities.map { it.toDomain(converters) }.filter { !it.isRead && !it.isToRead }
            }
        }

    /** Observes books filtered by [BookMediaType] (`READALOUD`, `EBOOK_ONLY`, `AUDIO_ONLY`). */
    fun observeBooksByMediaType(mediaType: BookMediaType): Flow<List<Book>> =
        vault.activeProfile.flatMapLatest { profile ->
            if (profile == null) {
                bookDao.observeLocalBooksByMediaType(mediaType)
                    .map { entities -> entities.map { it.toDomain(converters) } }
            } else {
                bookDao.observeBooksByMediaType(profile.id, profile.userId, mediaType)
                    .map { entities -> entities.map { it.toDomain(converters) } }
            }
        }

    /** Observes downloaded books available offline for the active server profile. */
    fun observeDownloadedBooks(): Flow<List<Book>> =
        vault.activeProfile.flatMapLatest { profile ->
            if (profile == null) {
                bookDao.observeLocalBooksByDownloadStatus(DownloadStatus.DOWNLOADED)
                    .map { entities -> entities.map { it.toDomain(converters) } }
            } else {
                bookDao.observeBooksByDownloadStatus(profile.id, profile.userId, DownloadStatus.DOWNLOADED)
                    .map { entities -> entities.map { it.toDomain(converters) } }
            }
        }

    /** Observes book IDs for which a readaloud format has been downloaded. */
    fun observeDownloadedReadaloudIds(): Flow<Set<String>> =
        vault.activeProfile.flatMapLatest { profile ->
            val dao = effectiveDownloadDao
            if (dao == null) {
                flowOf(emptySet())
            } else {
                val serverId = profile?.id ?: LocalLibrary.SERVER_ID
                val userId = profile?.userId ?: LocalLibrary.USER_ID
                val remoteFlow = dao.observeAllDownloads(serverId, userId).map { list ->
                    list.filter { it.format == BookMediaType.READALOUD && it.downloadStatus == DownloadStatus.DOWNLOADED }
                        .map { it.bookId }
                        .toSet()
                }
                val localFlow = dao.observeAllDownloads(LocalLibrary.SERVER_ID, LocalLibrary.USER_ID).map { list ->
                    list.filter { it.format == BookMediaType.READALOUD && it.downloadStatus == DownloadStatus.DOWNLOADED }
                        .map { it.bookId }
                        .toSet()
                }
                combine(remoteFlow, localFlow) { remote, local -> remote + local }
            }
        }

    /** Observes all sync checkpoints keyed by `bookId` for the active server profile or local books. */
    fun observeCheckpoints(): Flow<Map<String, SyncCheckpoint>> =
        vault.activeProfile.flatMapLatest { profile ->
            val local = checkpointDao.observeAllCheckpoints(LocalLibrary.SERVER_ID, LocalLibrary.USER_ID)
            if (profile == null) {
                local.map { list -> list.associate { it.bookId to it.toDomain() } }
            } else {
                // Local books are shown alongside the server's, so their progress must be too.
                combine(checkpointDao.observeAllCheckpoints(profile.id, profile.userId), local) { remote, mine ->
                    (mine + remote).associate { it.bookId to it.toDomain() }
                }
            }
        }

    /**
     * Fetches the remote catalog from the active Storyteller server (supporting both
     * `ARCHITECTURE.md` paginated JSON and Storyteller v2 JSON array format) and merges
     * it into Room while preserving local download status, file paths, and `lastOpenedAt`.
     */
    suspend fun refreshCatalog(): Result<List<Book>> = try {
        val profile = vault.activeProfile.value ?: throw NoActiveServerException()
        var response = try {
            api.listBooksV2Raw()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (response == null || response.code() == 404 || response.code() == 405) {
            response = api.listBooksRaw()
        }
        if (!response.isSuccessful) {
            throw IllegalStateException("Catalog request failed with HTTP ${response.code()}")
        }
        val rawBody = response.body()?.string()?.trim().orEmpty()
        val catalogResult = CatalogParser.parseFull(rawBody, profile.id, profile.userId, profile.baseUrl)
        val remoteBooks = catalogResult.books

        val existingById = bookDao.getBooksSnapshot(profile.id, profile.userId)
            .associateBy { it.id }
        val now = clock()

        val mergedEntities = remoteBooks.map { remote ->
            val existing = existingById[remote.id]
            BookEntity(
                id = remote.id,
                serverId = profile.id,
                userId = profile.userId,
                title = remote.title,
                authors = converters.fromStringList(remote.authors),
                narrators = converters.fromStringList(remote.narrators),
                coverUrl = remote.coverUrl,
                description = remote.description,
                mediaType = if (existing != null && existing.downloadStatus == DownloadStatus.DOWNLOADED) existing.mediaType else remote.mediaType,
                durationSeconds = remote.durationSeconds,
                totalChapters = remote.totalChapters,
                downloadStatus = existing?.downloadStatus ?: DownloadStatus.NOT_DOWNLOADED,
                downloadProgress = existing?.downloadProgress ?: 0f,
                localFilePath = existing?.localFilePath,
                localAudioPath = existing?.localAudioPath,
                sha256Checksum = existing?.sha256Checksum,
                tags = converters.fromStringList(remote.tags),
                createdAt = existing?.createdAt ?: now,
                lastOpenedAt = existing?.lastOpenedAt,
                hasReadaloud = remote.hasReadaloud,
                hasEbook = remote.hasEbook,
                hasAudiobook = remote.hasAudiobook,
                readingStatus = if (remote.readingStatus?.lowercase() in listOf("none", "no status", "")) "to_read" else remote.readingStatus,
                readingStatusLabel = if (remote.readingStatus?.lowercase() in listOf("none", "no status", "")) "To read" else remote.readingStatusLabel,
                statusUuid = remote.statusUuid ?: existing?.statusUuid,
                rating = remote.rating ?: existing?.rating,
                publicationDate = remote.publicationDate ?: existing?.publicationDate,
                language = remote.language ?: existing?.language,
                publisher = remote.publisher ?: existing?.publisher,
                isbn = remote.isbn ?: existing?.isbn,
                externalRatings = remote.externalRatings ?: existing?.externalRatings,
            )
        }
        val applyCatalogChanges: suspend () -> Unit = {
            bookDao.upsertBooks(mergedEntities)

            if (seriesDao != null) {
                val seriesEntities = catalogResult.series.map { SeriesEntity.fromDomain(it) }
                if (seriesEntities.isNotEmpty()) {
                    seriesDao.upsertSeries(seriesEntities)
                }
                seriesDao.clearBookSeries(profile.id)
                if (catalogResult.bookSeriesRelations.isNotEmpty()) {
                    seriesDao.upsertBookSeries(catalogResult.bookSeriesRelations)
                }
                val activeSeriesUuids = catalogResult.series.map { it.uuid }
                if (activeSeriesUuids.isNotEmpty()) {
                    seriesDao.pruneOrphanedSeries(profile.id, activeSeriesUuids)
                } else {
                    seriesDao.clearSeries(profile.id)
                }
            }

            if (collectionDao != null) {
                val collectionEntities = catalogResult.collections.map { CollectionEntity.fromDomain(it) }
                if (collectionEntities.isNotEmpty()) {
                    collectionDao.upsertCollections(collectionEntities)
                }
                collectionDao.clearBookCollections(profile.id)
                if (catalogResult.bookCollectionRelations.isNotEmpty()) {
                    collectionDao.upsertBookCollections(catalogResult.bookCollectionRelations)
                }
                val activeCollUuids = catalogResult.collections.map { it.uuid }
                if (activeCollUuids.isNotEmpty()) {
                    collectionDao.pruneOrphanedCollections(profile.id, activeCollUuids)
                } else {
                    collectionDao.clearCollections(profile.id)
                }
            }
        }

        if (database != null) {
            database.withTransaction {
                applyCatalogChanges()
                bookDao.healInvalidReadingStatuses()
            }
        } else {
            applyCatalogChanges()
            bookDao.healInvalidReadingStatuses()
        }

        Result.success(mergedEntities.map { it.toDomain(converters) })
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /** Observes all series for the active server profile. */
    fun observeSeries(): Flow<List<Series>> =
        vault.activeProfile.flatMapLatest { profile ->
            if (profile == null || seriesDao == null) flowOf(emptyList())
            else seriesDao.observeSeries(profile.id, profile.userId).map { list -> list.map { it.toDomain() } }
        }

    /** Observes all book-series cross references for the active server profile. */
    fun observeAllBookSeries(): Flow<List<org.hello.reader.core.database.entity.BookSeriesCrossRef>> =
        vault.activeProfile.flatMapLatest { profile ->
            if (profile == null || seriesDao == null) flowOf(emptyList())
            else seriesDao.observeAllBookSeries(profile.id)
        }

    /** Observes details of a series including all its books in series order. */
    fun observeSeriesDetail(seriesUuid: String): Flow<SeriesWithBooks?> =
        vault.activeProfile.flatMapLatest { profile ->
            if (profile == null || seriesDao == null) flowOf(null)
            else {
                combine(
                    seriesDao.observeSeriesByUuid(profile.id, seriesUuid),
                    seriesDao.observeBooksForSeries(profile.id, profile.userId, seriesUuid),
                ) { seriesEntity, bookItems ->
                    if (seriesEntity == null) null
                    else {
                        val books = bookItems.map { item ->
                            SeriesBookItem(
                                book = item.book.toDomain(converters),
                                position = item.position,
                                featured = item.featured,
                            )
                        }
                        SeriesWithBooks(
                            series = seriesEntity.toDomain().copy(totalBooks = books.size),
                            books = books,
                        )
                    }
                }
            }
        }

    /** Observes all collections for the active server profile. */
    fun observeCollections(): Flow<List<BookCollection>> =
        vault.activeProfile.flatMapLatest { profile ->
            if (profile == null || collectionDao == null) flowOf(emptyList())
            else collectionDao.observeCollections(profile.id, profile.userId).map { list -> list.map { it.toDomain() } }
        }

    /** Observes all book-collection cross references for the active server profile. */
    fun observeAllBookCollections(): Flow<List<org.hello.reader.core.database.entity.BookCollectionCrossRef>> =
        vault.activeProfile.flatMapLatest { profile ->
            if (profile == null || collectionDao == null) flowOf(emptyList())
            else collectionDao.observeAllBookCollections(profile.id)
        }

    /** Observes details of a collection with books grouped by series in series order, followed by standalone books. */
    fun observeCollectionDetail(collectionUuid: String): Flow<CollectionGroupedBooks?> =
        vault.activeProfile.flatMapLatest { profile ->
            if (profile == null || collectionDao == null) flowOf(null)
            else {
                combine(
                    collectionDao.observeCollectionByUuid(profile.id, collectionUuid),
                    collectionDao.observeBooksForCollection(profile.id, profile.userId, collectionUuid),
                    seriesDao?.observeSeries(profile.id, profile.userId) ?: flowOf(emptyList()),
                    seriesDao?.observeAllBookSeries(profile.id) ?: flowOf(emptyList()),
                ) { collEntity, booksInColl, allSeries, allRefs ->
                    if (collEntity == null) null
                    else {
                        val books = booksInColl.map { it.toDomain(converters) }
                        val seriesMap = allSeries.associate { it.uuid to it.toDomain() }
                        val seriesNamesByUuid = allSeries.associate { it.uuid to it.name }
                        val refsByBookId = allRefs.groupBy { it.bookId }
                        val bookSeriesEntries = books.associate { b ->
                            b.id to refsByBookId[b.id].orEmpty().map { ref ->
                                BookSeriesEntry(
                                    seriesUuid = ref.seriesUuid,
                                    seriesName = seriesNamesByUuid[ref.seriesUuid].orEmpty(),
                                    position = ref.position,
                                    featured = ref.featured,
                                )
                            }
                        }
                        groupCollectionBooks(
                            collection = collEntity.toDomain().copy(totalBooks = books.size),
                            books = books,
                            bookSeriesEntries = bookSeriesEntries,
                            seriesMap = seriesMap,
                        )
                    }
                }
            }
        }

    /** Observes series memberships for a specific book. */
    fun observeSeriesForBook(bookId: String): Flow<List<BookSeriesEntry>> =
        vault.activeProfile.flatMapLatest { profile ->
            if (profile == null || seriesDao == null) flowOf(emptyList())
            else seriesDao.observeSeriesForBook(profile.id, bookId).map { list ->
                list.map {
                    BookSeriesEntry(
                        seriesUuid = it.seriesUuid,
                        seriesName = it.seriesName,
                        position = it.position,
                        featured = it.featured,
                    )
                }
            }
        }

    /** Observes collections containing a specific book. */
    fun observeCollectionsForBook(bookId: String): Flow<List<BookCollection>> =
        vault.activeProfile.flatMapLatest { profile ->
            if (profile == null || collectionDao == null) flowOf(emptyList())
            else collectionDao.observeCollectionsForBook(profile.id, bookId).map { list ->
                list.map { it.toDomain() }
            }
        }

    /** Marks a book as opened now so it surfaces in the Hero "Continue" Carousel. */
    suspend fun markBookOpened(bookId: String) {
        val now = clock()
        if (LocalLibrary.isLocalBookId(bookId)) {
            bookDao.updateLastOpenedAt(bookId, LocalLibrary.SERVER_ID, LocalLibrary.USER_ID, now)
            return
        }
        val profile = vault.activeProfile.value ?: return
        bookDao.updateLastOpenedAt(bookId, profile.id, profile.userId, now)
    }

    /** Marks a book as [DownloadStatus.QUEUED] in Room immediately when a download is requested. */
    suspend fun markDownloadQueued(book: Book) {
        val userId = book.userId.ifEmpty { vault.activeProfile.value?.userId.orEmpty() }
        bookDao.updateDownloadProgress(
            bookId = book.id,
            serverId = book.serverId,
            userId = userId,
            status = DownloadStatus.QUEUED,
            progress = 0f,
        )
    }

    /** Updates the media type of a book in Room (e.g. when the user downloads it as EBOOK_ONLY or AUDIO_ONLY). */
    suspend fun updateBookMediaType(book: Book, mediaType: BookMediaType) {
        val userId = book.userId.ifEmpty { vault.activeProfile.value?.userId.orEmpty() }
        bookDao.updateMediaType(book.id, book.serverId, userId, mediaType)
    }

    /** Resets a book's download status to [DownloadStatus.NOT_DOWNLOADED] (or restores remaining downloaded formats) upon cancellation. */
    suspend fun markDownloadCancelled(book: Book) {
        val userId = book.userId.ifEmpty { vault.activeProfile.value?.userId.orEmpty() }
        val currentBook = bookDao.getBookById(book.id, book.serverId, userId)
        val remaining = effectiveDownloadDao?.getDownloadsForBook(book.id, book.serverId, userId)
            ?.filter { it.downloadStatus == DownloadStatus.DOWNLOADED }
            .orEmpty()

        val readaloudDownload = remaining.firstOrNull { it.format == BookMediaType.READALOUD }
        val ebookDownload = remaining.firstOrNull { it.format == BookMediaType.EBOOK_ONLY }
        val audioDownload = remaining.firstOrNull { it.format == BookMediaType.AUDIO_ONLY }

        val effectiveTextPath = readaloudDownload?.localPath
            ?: ebookDownload?.localPath
            ?: currentBook?.localFilePath
            ?: book.localFilePath
        val effectiveAudioPath = audioDownload?.localPath
            ?: currentBook?.localAudioPath
            ?: book.localAudioPath
        val hasExisting = remaining.isNotEmpty() || effectiveTextPath != null || effectiveAudioPath != null
        val effectiveSha256 = readaloudDownload?.sha256
            ?: ebookDownload?.sha256
            ?: audioDownload?.sha256
            ?: if (hasExisting) (currentBook?.sha256Checksum ?: book.sha256Checksum) else null

        bookDao.updateDownloadStatus(
            bookId = book.id,
            serverId = book.serverId,
            userId = userId,
            status = if (hasExisting) DownloadStatus.DOWNLOADED else DownloadStatus.NOT_DOWNLOADED,
            progress = if (hasExisting) 1.0f else 0f,
            path = effectiveTextPath,
            audioPath = effectiveAudioPath,
            sha256 = effectiveSha256,
        )
    }

    suspend fun getReadingStatuses(): List<ReadingStatusItem> {
        vault.activeProfile.value ?: return ReadingStatusItem.DEFAULT_STATUSES
        return try {
            val response = api.getStatusesV2Raw()
            if (response.isSuccessful) {
                val bodyStr = response.body()?.string()?.trim().orEmpty()
                if (bodyStr.isNotEmpty()) {
                    val element = NetworkModule.json.parseToJsonElement(bodyStr)
                    val array = when (element) {
                        is kotlinx.serialization.json.JsonArray -> element
                        is kotlinx.serialization.json.JsonObject -> (element["statuses"] ?: element["items"]) as? kotlinx.serialization.json.JsonArray
                        else -> null
                    }
                    if (array != null) {
                        val parsed = array.mapNotNull { item ->
                            if (item !is kotlinx.serialization.json.JsonObject) return@mapNotNull null
                            val uuid = (item["uuid"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                                ?: (item["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
                            val name = (item["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                                ?: (item["slug"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: uuid
                            val label = (item["label"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                                ?: (item["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: name
                            val desc = (item["description"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                            if (name.equals("none", ignoreCase = true) || label.equals("no status", ignoreCase = true)) return@mapNotNull null
                            ReadingStatusItem(uuid = uuid, name = name, label = label, description = desc, isServerStatus = true)
                        }
                        if (parsed.isNotEmpty()) return parsed
                    }
                }
            }
            emptyList()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun updateBookReadingStatus(bookId: String, statusItem: ReadingStatusItem?): Result<Unit> {
        if (statusItem == null) {
            return Result.failure(IllegalArgumentException("Storyteller does not support removing reading status"))
        }
        val isLocal = LocalLibrary.isLocalBookId(bookId)
        val profile = vault.activeProfile.value
        val serverId = if (isLocal) LocalLibrary.SERVER_ID else (profile?.id ?: LocalLibrary.SERVER_ID)
        val userId = if (isLocal) LocalLibrary.USER_ID else (profile?.userId ?: LocalLibrary.USER_ID)
        val previous = bookDao.getBookById(bookId, serverId, userId)

        bookDao.updateReadingStatus(
            bookId = bookId,
            serverId = serverId,
            userId = userId,
            status = statusItem.name,
            label = statusItem.label,
            statusUuid = statusItem.uuid,
        )

        if (profile != null && !isLocal) {
            if (!statusItem.isServerStatus) {
                return Result.success(Unit)
            }
            try {
                val body = buildJsonObject { put("status", statusItem.uuid) }
                val response = api.updateBookStatus(serverBookUuid(bookId, profile), body)
                if (!response.isSuccessful && response.code() != 204 && response.code() != 200) {
                    bookDao.updateReadingStatus(
                        bookId = bookId,
                        serverId = serverId,
                        userId = userId,
                        status = previous?.readingStatus,
                        label = previous?.readingStatusLabel,
                        statusUuid = previous?.statusUuid,
                    )
                    return Result.failure(java.io.IOException("Server rejected reading status update: HTTP ${response.code()}"))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                bookDao.updateReadingStatus(
                    bookId = bookId,
                    serverId = serverId,
                    userId = userId,
                    status = previous?.readingStatus,
                    label = previous?.readingStatusLabel,
                    statusUuid = previous?.statusUuid,
                )
                return Result.failure(e)
            }
        }
        return Result.success(Unit)
    }

    suspend fun setBookRating(bookId: String, rating: Float?) {
        val isLocal = LocalLibrary.isLocalBookId(bookId)
        val profile = vault.activeProfile.value
        val serverId = if (isLocal) LocalLibrary.SERVER_ID else (profile?.id ?: LocalLibrary.SERVER_ID)
        val userId = if (isLocal) LocalLibrary.USER_ID else (profile?.userId ?: LocalLibrary.USER_ID)
        bookDao.updateBookRating(bookId, serverId, userId, rating)

        if (profile != null && !isLocal) {
            try {
                val bookUuid = serverBookUuid(bookId, profile)
                if (rating == null || rating <= 0f) {
                    api.deleteBookRating(bookUuid)
                } else {
                    api.setBookRating(bookUuid, mapOf("rating" to rating))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Preserved in local Room
            }
        }
    }

    /**
     * Storyteller's status and rating routes look books up by UUID only, while the library lists
     * give the numeric id. Resolves (and remembers) the UUID through the v2 book route.
     */
    private suspend fun serverBookUuid(bookId: String, profile: ServerProfile): String {
        if (SERVER_UUID_PATTERN.matches(bookId)) return bookId
        val key = "${profile.id}_${profile.userId}_$bookId"
        serverBookUuids[key]?.let { return it }
        val response = api.getBookV2Raw(bookId)
        if (!response.isSuccessful) {
            throw java.io.IOException("Couldn't look up the book on the server: HTTP ${response.code()}")
        }
        val element = NetworkModule.json.parseToJsonElement(response.body()?.string().orEmpty().ifBlank { "{}" })
        val uuid = ((element as? JsonObject)?.get("uuid") as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: throw java.io.IOException("The server didn't return an id for this book")
        serverBookUuids[key] = uuid
        return uuid
    }

    fun activeProfileKey(): String? = vault.activeProfile.value?.let { "${it.id}_${it.userId}" }

    suspend fun fetchServerHomeSections(): List<HomeShelfConfig> {
        vault.activeProfile.value ?: return emptyList()
        return try {
            val response = api.getHomeSectionsV2Raw()
            if (!response.isSuccessful) return emptyList()

            val bodyStr = response.body()?.string()?.trim().orEmpty()
            if (bodyStr.isEmpty()) return emptyList()

            val element = NetworkModule.json.parseToJsonElement(bodyStr)
            val array = when (element) {
                is JsonArray -> element
                is JsonObject -> (element["sections"] ?: element["items"]) as? JsonArray
                else -> null
            } ?: return emptyList()

            val ignoredKinds = setOf("getStarted", "hero", "stats", "addSection")

            array.mapNotNull { item ->
                if (item !is JsonObject) return@mapNotNull null
                val enabled = (item["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true
                if (!enabled) return@mapNotNull null

                val kind = (item["kind"] as? JsonPrimitive)?.content.orEmpty()
                if (kind in ignoredKinds) return@mapNotNull null

                val uuid = (item["uuid"] as? JsonPrimitive)?.content
                    ?: (item["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val shelfUuid = (item["shelfUuid"] as? JsonPrimitive)?.content
                val name = (item["name"] as? JsonPrimitive)?.content
                    ?: (item["title"] as? JsonPrimitive)?.content

                when {
                    kind == "currentlyReading" -> {
                        HomeShelfConfig(
                            id = "server_$uuid",
                            title = name ?: "Continue reading",
                            type = HomeShelfType.CONTINUE_READING,
                            serverSectionId = uuid,
                        )
                    }
                    kind == "recentlyAdded" -> {
                        HomeShelfConfig(
                            id = "server_$uuid",
                            title = name ?: "Recently added",
                            type = HomeShelfType.RECENTLY_ADDED,
                            serverSectionId = uuid,
                        )
                    }
                    !shelfUuid.isNullOrBlank() || kind == "shelf" -> {
                        val targetShelfUuid = shelfUuid ?: uuid
                        val booksResponse = try {
                            api.getShelfBooksV2Raw(targetShelfUuid)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                        val bookIds = if (booksResponse?.isSuccessful == true) {
                            val booksBody = booksResponse.body()?.string()?.trim().orEmpty()
                            if (booksBody.isNotEmpty()) {
                                val booksElem = try {
                                    NetworkModule.json.parseToJsonElement(booksBody)
                                } catch (_: Exception) { null }
                                val booksArray = when (booksElem) {
                                    is JsonArray -> booksElem
                                    is JsonObject -> (booksElem["books"] ?: booksElem["items"]) as? JsonArray
                                    else -> null
                                }
                                booksArray?.mapNotNull { b ->
                                    when (b) {
                                        is JsonObject -> (b["id"] as? JsonPrimitive)?.content
                                            ?: (b["uuid"] as? JsonPrimitive)?.content
                                        is JsonPrimitive -> b.content
                                        else -> null
                                    }
                                } ?: emptyList()
                            } else emptyList()
                        } else emptyList()

                        HomeShelfConfig(
                            id = "server_$uuid",
                            title = name ?: "Shelf",
                            type = HomeShelfType.SERVER_SECTION,
                            serverSectionId = uuid,
                            bookIds = bookIds,
                        )
                    }
                    else -> null
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun observeDownloadsForBook(bookId: String): Flow<List<BookDownloadEntity>> {
        val dao = effectiveDownloadDao ?: return flowOf(emptyList())
        if (LocalLibrary.isLocalBookId(bookId)) {
            return dao.observeDownloadsForBook(bookId, LocalLibrary.SERVER_ID, LocalLibrary.USER_ID)
        }
        return vault.activeProfile.flatMapLatest { profile ->
            val serverId = profile?.id ?: LocalLibrary.SERVER_ID
            val userId = profile?.userId ?: LocalLibrary.USER_ID
            dao.observeDownloadsForBook(bookId, serverId, userId)
        }
    }

    suspend fun calculateTotalSizeForBooks(bookIds: List<String>): Long {
        val serverId = vault.activeProfile.value?.id ?: LocalLibrary.SERVER_ID
        val userId = vault.activeProfile.value?.userId ?: LocalLibrary.USER_ID
        val downloads = effectiveDownloadDao?.getDownloadsForBooks(bookIds, serverId, userId) ?: emptyList()
        var total = downloads.sumOf { it.sizeBytes }
        if (total == 0L) {
            for (d in downloads) {
                val f = File(d.localPath)
                if (f.exists()) {
                    total += if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()
                }
            }
        }
        return total
    }

    suspend fun removeDownload(book: Book, format: BookMediaType? = null) {
        val userId = book.userId.ifEmpty { vault.activeProfile.value?.userId.orEmpty() }
        val downloadDao = effectiveDownloadDao
        if (format != null) {
            val currentBook = bookDao.getBookById(book.id, book.serverId, userId)
            val download = downloadDao?.getDownload(book.id, book.serverId, userId, format)
            if (download != null) {
                File(download.localPath).deleteRecursively()
                downloadDao.deleteDownload(book.id, book.serverId, userId, format)
            } else {
                val legacyPath = if (format == BookMediaType.AUDIO_ONLY) {
                    currentBook?.localAudioPath ?: book.localAudioPath
                } else {
                    currentBook?.localFilePath ?: book.localFilePath
                }
                if (legacyPath != null) {
                    File(legacyPath).deleteRecursively()
                }
            }
            val remaining = downloadDao?.getDownloadsForBook(book.id, book.serverId, userId)
                ?.filter { it.downloadStatus == DownloadStatus.DOWNLOADED } ?: emptyList()

            val readaloudDownload = remaining.firstOrNull { it.format == BookMediaType.READALOUD }
            val ebookDownload = remaining.firstOrNull { it.format == BookMediaType.EBOOK_ONLY }
            val audioDownload = remaining.firstOrNull { it.format == BookMediaType.AUDIO_ONLY }

            val preservedTextPath = if (format == BookMediaType.AUDIO_ONLY) {
                currentBook?.localFilePath ?: book.localFilePath
            } else {
                null
            }
            val preservedAudioPath = if (format != BookMediaType.AUDIO_ONLY) {
                currentBook?.localAudioPath ?: book.localAudioPath
            } else {
                null
            }

            val effectiveTextPath = readaloudDownload?.localPath ?: ebookDownload?.localPath ?: preservedTextPath
            val effectiveAudioPath = audioDownload?.localPath ?: preservedAudioPath
            val hasRemaining = remaining.isNotEmpty() || effectiveTextPath != null || effectiveAudioPath != null
            val status = if (hasRemaining) DownloadStatus.DOWNLOADED else DownloadStatus.NOT_DOWNLOADED
            val effectiveSha256 = readaloudDownload?.sha256
                ?: ebookDownload?.sha256
                ?: audioDownload?.sha256
                ?: if (hasRemaining) (currentBook?.sha256Checksum ?: book.sha256Checksum) else null

            val effectiveMediaType = when {
                readaloudDownload != null -> BookMediaType.READALOUD
                ebookDownload != null -> BookMediaType.EBOOK_ONLY
                effectiveTextPath != null -> {
                    if ((currentBook?.mediaType ?: book.mediaType) == BookMediaType.READALOUD && format != BookMediaType.READALOUD) {
                        BookMediaType.READALOUD
                    } else {
                        BookMediaType.EBOOK_ONLY
                    }
                }
                audioDownload != null || effectiveAudioPath != null -> BookMediaType.AUDIO_ONLY
                else -> book.mediaType
            }

            bookDao.updateMediaType(book.id, book.serverId, userId, effectiveMediaType)
            bookDao.updateDownloadStatus(
                bookId = book.id,
                serverId = book.serverId,
                userId = userId,
                status = status,
                progress = if (hasRemaining) 1.0f else 0f,
                path = effectiveTextPath,
                audioPath = effectiveAudioPath,
                sha256 = effectiveSha256,
            )
        } else {
            val downloads = downloadDao?.getDownloadsForBook(book.id, book.serverId, userId) ?: emptyList()
            for (d in downloads) {
                File(d.localPath).deleteRecursively()
            }
            val baseDir = filesDir
            if (baseDir != null) {
                val bookDir = org.hello.reader.core.download.BookDownloadWorker.bookDirectory(baseDir, book.serverId, book.id)
                if (bookDir.exists()) bookDir.deleteRecursively()
            }
            downloadDao?.deleteDownloadsForBook(book.id, book.serverId, userId)
            bookDao.clearDownloadPaths(
                bookId = book.id,
                serverId = book.serverId,
                userId = userId,
                clearFilePath = true,
                clearAudioPath = true,
                status = DownloadStatus.NOT_DOWNLOADED,
            )
        }
    }

    suspend fun removeDownloadsBulk(books: List<Book>): Long {
        val bookIds = books.map { it.id }
        val totalFreed = calculateTotalSizeForBooks(bookIds)
        for (book in books) {
            removeDownload(book, null)
        }
        return totalFreed
    }

    /**
     * Saves a local reading/listening checkpoint in Room (marked `isDirty = true`)
     * and attempts an immediate upstream push to Storyteller if a server profile is active.
     */
    suspend fun saveCheckpoint(checkpoint: SyncCheckpoint) {
        val now = clock()
        // Local books never sync. Server books stay dirty while offline/disconnected so they push later.
        val isLocal = checkpoint.serverId == LocalLibrary.SERVER_ID
        val dirty = checkpoint.copy(updatedAt = now, isDirty = !isLocal)
        checkpointDao.upsertCheckpoint(SyncCheckpointEntity.fromDomain(dirty))
        bookDao.updateLastOpenedAt(checkpoint.bookId, checkpoint.serverId, checkpoint.userId, now)
        onCheckpointSaved?.invoke(dirty)
        val active = vault.activeProfile.value
        if (!isLocal && active != null && active.id == checkpoint.serverId) {
            pushCheckpointInternal(dirty)
        }
    }

    /**
     * Synchronizes a book's checkpoint with the server.
     * - A local position that is already synced simply follows a newer server position.
     * - Only unsynced local progress that the server has since overtaken with a clearly different
     *   position becomes a [SyncConflict] in [pendingConflicts] for the reader to ask about.
     * - Otherwise pushes any dirty local state.
     */
    suspend fun syncBookPosition(bookId: String): Result<SyncCheckpoint?> = try {
        // Local books have no server copy: never ask a Storyteller server about them.
        if (LocalLibrary.isLocalBookId(bookId)) {
            Result.success(checkpointDao.getCheckpoint(bookId, LocalLibrary.SERVER_ID, LocalLibrary.USER_ID)?.toDomain())
        } else {
            val profile = vault.activeProfile.value
            if (profile == null) {
                Result.success(null)
            } else {
                val local = checkpointDao.getCheckpoint(bookId, profile.id, profile.userId)?.toDomain()
                val remote = pullRemotePosition(bookId, profile)

                val resolved = when {
                    local != null && remote != null -> {
                        val remoteIsNewer = remote.updatedAt > local.updatedAt
                        when {
                            !local.isDirty && remoteIsNewer -> {
                                checkpointDao.upsertCheckpoint(SyncCheckpointEntity.fromDomain(remote))
                                remote
                            }
                            !local.isDirty -> local
                            remoteIsNewer && local.conflictsWith(remote) -> {
                                recordConflict(SyncConflict(bookId = bookId, local = local, remote = remote))
                                local
                            }
                            else -> {
                                pushCheckpointInternal(local)
                                local
                            }
                        }
                    }
                    local == null && remote != null -> {
                        checkpointDao.upsertCheckpoint(SyncCheckpointEntity.fromDomain(remote))
                        remote
                    }
                    local != null -> {
                        if (local.isDirty) pushCheckpointInternal(local)
                        local
                    }
                    else -> null
                }
                Result.success(resolved)
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    internal fun recordConflict(conflict: SyncConflict) {
        _pendingConflicts.update { current ->
            current.filterNot { it.bookId == conflict.bookId } + conflict
        }
    }

    /**
     * Resolves a >60s [SyncConflict] after the user picks either the remote or local position.
     */
    suspend fun resolveConflict(bookId: String, useRemote: Boolean): SyncCheckpoint? {
        val conflict = _pendingConflicts.value.firstOrNull { it.bookId == bookId } ?: return null
        _pendingConflicts.update { list -> list.filterNot { it.bookId == bookId } }

        return if (useRemote) {
            val chosen = conflict.remote.copy(isDirty = false, updatedAt = clock())
            checkpointDao.upsertCheckpoint(SyncCheckpointEntity.fromDomain(chosen))
            chosen
        } else {
            val chosen = conflict.local.copy(isDirty = true, updatedAt = clock())
            checkpointDao.upsertCheckpoint(SyncCheckpointEntity.fromDomain(chosen))
            pushCheckpointInternal(chosen)
            chosen
        }
    }

    private suspend fun pullRemotePosition(bookId: String, profile: ServerProfile): SyncCheckpoint? {
        val v2Response = try {
            api.getBookPosition(bookId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (v2Response != null && v2Response.isSuccessful) {
            val raw = v2Response.body()?.string()?.trim().orEmpty()
            val parsed = parseV2PositionPayload(raw, bookId, profile.id, profile.userId)
            if (parsed != null) return parsed
        }
        val v1Response = try {
            api.pullSyncCheckpoint(bookId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return if (v1Response != null && v1Response.isSuccessful) {
            v1Response.body()?.toDomain(profile.id, profile.userId)
        } else {
            null
        }
    }

    private fun parseV2PositionPayload(raw: String, bookId: String, serverId: String, userId: String): SyncCheckpoint? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == "null" || trimmed == "{}") return null
        return try {
            val root = NetworkModule.json.parseToJsonElement(trimmed)
            val posObj = when (root) {
                is JsonArray -> root.firstOrNull() as? JsonObject
                is JsonObject -> {
                    (root["position"] as? JsonObject)
                        ?: (root["data"] as? JsonObject)
                        ?: root
                }
                else -> null
            } ?: return null

            if (posObj.containsKey("book_id") || posObj.containsKey("reading_cfi") || posObj.containsKey("audio_position_ms")) {
                val dto = NetworkModule.json.decodeFromString(SyncCheckpointDto.serializer(), trimmed)
                return dto.toDomain(serverId, userId)
            }

            val locatorObj = (posObj["locator"] as? JsonObject) ?: posObj
            val locationsObj = locatorObj["locations"] as? JsonObject

            val fragmentsArray = locationsObj?.get("fragments") as? JsonArray
            val activeSentenceId = fragmentsArray?.firstOrNull()?.let { (it as? JsonPrimitive)?.content }
                ?: (locationsObj?.get("fragments") as? JsonPrimitive)?.content

            val href = (locatorObj["href"] as? JsonPrimitive)?.content
            val totalProgression = (locationsObj?.get("totalProgression") as? JsonPrimitive)?.doubleOrNull?.toFloat()
                ?: (locationsObj?.get("progression") as? JsonPrimitive)?.doubleOrNull?.toFloat()
                ?: 0f
            val percent = (totalProgression * 100f).coerceIn(0f, 100f)

            val rawTimestamp = (posObj["timestamp"] as? JsonPrimitive)?.longOrNull
                ?: (posObj["updated_at"] as? JsonPrimitive)?.longOrNull
                ?: (posObj["updatedAt"] as? JsonPrimitive)?.longOrNull
                ?: clock()
            val timestampMs = if (rawTimestamp in 1L..9_999_999_999L) rawTimestamp * 1000L else rawTimestamp

            val initialAudioMs = if (activeSentenceId?.startsWith("t=") == true) {
                val sec = activeSentenceId.removePrefix("t=").toDoubleOrNull() ?: 0.0
                (sec * 1000.0).toLong()
            } else 0L

            SyncCheckpoint(
                bookId = bookId,
                serverId = serverId,
                userId = userId,
                readingCfi = href,
                chapterIndex = 0,
                chapterTitle = null,
                audioPositionMs = initialAudioMs,
                totalDurationMs = 0L,
                progressPercent = percent,
                activeSentenceId = activeSentenceId,
                updatedAt = timestampMs,
                isDirty = false,
            )
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun pushCheckpointInternal(checkpoint: SyncCheckpoint): Boolean {
        return try {
            val totalProg = (checkpoint.progressPercent / 100f).coerceIn(0f, 1f)
            val isAudio = checkpoint.activeSentenceId?.startsWith("t=") == true ||
                checkpoint.readingCfi?.let {
                    val lower = it.lowercase()
                    lower.endsWith(".mp3") || lower.endsWith(".m4b") || lower.endsWith(".m4a") || lower.endsWith(".aac")
                } == true

            val href = checkpoint.readingCfi?.takeIf { !it.startsWith("epubcfi") }
            // Never invent an href for audio or text; if the real resource is unknown, skip pushing
            if (href.isNullOrBlank()) {
                return false
            }

            val mimeType = if (isAudio) mimeTypeForAudio(href) else "application/xhtml+xml"
            val fragments = listOfNotNull(checkpoint.activeSentenceId)
            val timestampMs = checkpoint.updatedAt

            val chapterProg = checkpoint.chapterProgression?.coerceIn(0f, 1f) ?: totalProg

            val v2Payload = StorytellerPositionPayloadDto(
                locator = ReadiumLocatorDto(
                    href = href,
                    type = mimeType,
                    title = checkpoint.chapterTitle,
                    locations = ReadiumLocationsDto(
                        fragments = fragments,
                        totalProgression = totalProg,
                        progression = chapterProg,
                    ),
                ),
                timestamp = timestampMs,
            )

            val v2Response = try {
                api.postBookPosition(checkpoint.bookId, v2Payload)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            if (v2Response != null) {
                if (v2Response.isSuccessful) {
                    checkpointDao.markSynced(checkpoint.bookId, checkpoint.serverId, checkpoint.userId)
                    return true
                }
                if (v2Response.code() == 409) {
                    val profile = vault.activeProfile.value
                    if (profile != null) {
                        val remote = pullRemotePosition(checkpoint.bookId, profile)
                        if (remote != null) {
                            if (checkpoint.conflictsWith(remote)) {
                                recordConflict(SyncConflict(bookId = checkpoint.bookId, local = checkpoint, remote = remote))
                            } else if (remote.updatedAt > checkpoint.updatedAt) {
                                checkpointDao.upsertCheckpoint(SyncCheckpointEntity.fromDomain(remote))
                            }
                        }
                    }
                    return false
                }
                if (v2Response.code() !in listOf(404, 405, 501)) {
                    return false
                }
            }

            // Fallback for v1 servers
            val response = api.pushSyncCheckpoint(
                SyncCheckpointDto(
                    bookId = checkpoint.bookId,
                    readingCfi = checkpoint.readingCfi,
                    audioPositionMs = checkpoint.audioPositionMs,
                    totalDurationMs = checkpoint.totalDurationMs,
                    chapterIndex = checkpoint.chapterIndex,
                    chapterTitle = checkpoint.chapterTitle,
                    activeSentenceId = checkpoint.activeSentenceId,
                    progressPercent = checkpoint.progressPercent,
                    timestamp = checkpoint.updatedAt,
                ),
            )
            if (response.isSuccessful) {
                checkpointDao.markSynced(checkpoint.bookId, checkpoint.serverId, checkpoint.userId)
                true
            } else {
                false
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    private fun mimeTypeForAudio(href: String): String {
        val lower = href.lowercase()
        return when {
            lower.endsWith(".m4b") || lower.endsWith(".m4a") -> "audio/mp4"
            lower.endsWith(".aac") -> "audio/aac"
            lower.endsWith(".flac") -> "audio/flac"
            lower.endsWith(".ogg") || lower.endsWith(".opus") -> "audio/ogg"
            else -> "audio/mpeg"
        }
    }

    private fun SyncCheckpointDto.toDomain(serverId: String, userId: String): SyncCheckpoint =
        SyncCheckpoint(
            bookId = bookId,
            serverId = serverId,
            userId = userId,
            readingCfi = readingCfi,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            audioPositionMs = audioPositionMs,
            totalDurationMs = totalDurationMs,
            progressPercent = progressPercent,
            activeSentenceId = activeSentenceId,
            updatedAt = timestamp,
            isDirty = false,
        )
}
