package org.hello.reader.testing

import org.hello.reader.core.database.dao.BookDao
import org.hello.reader.core.database.dao.SyncCheckpointDao
import org.hello.reader.core.database.entity.BookEntity
import org.hello.reader.core.database.entity.SyncCheckpointEntity
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.DownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakeBookDao : BookDao {
    private val state = MutableStateFlow<List<BookEntity>>(emptyList())

    override fun observeBooks(serverId: String, userId: String): Flow<List<BookEntity>> =
        state.map { list ->
            list.filter { (it.serverId == serverId && it.userId == userId) || it.serverId == "local" }
                .sortedWith(compareByDescending<BookEntity> { it.lastOpenedAt }.thenBy { it.title })
        }

    override fun observeLocalBooks(): Flow<List<BookEntity>> =
        state.map { list ->
            list.filter { it.serverId == "local" }
                .sortedWith(compareByDescending<BookEntity> { it.lastOpenedAt }.thenBy { it.title })
        }

    override fun observeBooksByMediaType(
        serverId: String,
        userId: String,
        mediaType: BookMediaType,
    ): Flow<List<BookEntity>> =
        observeBooks(serverId, userId).map { list -> list.filter { it.mediaType == mediaType } }

    override fun observeLocalBooksByMediaType(mediaType: BookMediaType): Flow<List<BookEntity>> =
        observeLocalBooks().map { list -> list.filter { it.mediaType == mediaType } }

    override fun observeBooksByDownloadStatus(
        serverId: String,
        userId: String,
        status: DownloadStatus,
    ): Flow<List<BookEntity>> =
        observeBooks(serverId, userId).map { list -> list.filter { it.downloadStatus == status } }

    override fun observeLocalBooksByDownloadStatus(status: DownloadStatus): Flow<List<BookEntity>> =
        observeLocalBooks().map { list -> list.filter { it.downloadStatus == status } }

    override fun observeContinueReading(serverId: String, userId: String): Flow<List<BookEntity>> =
        observeBooks(serverId, userId).map { list ->
            list.filter { it.lastOpenedAt != null }
                .sortedByDescending { it.lastOpenedAt }
                .take(10)
        }

    override fun observeLocalContinueReading(): Flow<List<BookEntity>> =
        observeLocalBooks().map { list ->
            list.filter { it.lastOpenedAt != null }
                .sortedByDescending { it.lastOpenedAt }
                .take(10)
        }

    override fun observeBookById(bookId: String, serverId: String, userId: String): Flow<BookEntity?> =
        observeBooks(serverId, userId).map { list -> list.firstOrNull { it.id == bookId } }

    override suspend fun getBookById(bookId: String, serverId: String, userId: String): BookEntity? =
        state.value.firstOrNull { it.id == bookId && ((it.serverId == serverId && it.userId == userId) || it.serverId == "local") }

    override suspend fun findBookById(bookId: String): BookEntity? =
        state.value.firstOrNull { it.id == bookId }

    override suspend fun getBooksSnapshot(serverId: String, userId: String): List<BookEntity> =
        state.value.filter { (it.serverId == serverId && it.userId == userId) || it.serverId == "local" }

    override suspend fun getLocalBooksSnapshot(): List<BookEntity> =
        state.value.filter { it.serverId == "local" }

    override suspend fun upsertBooks(books: List<BookEntity>) {
        state.update { current ->
            val keys = books.map { Triple(it.id, it.serverId, it.userId) }.toSet()
            current.filterNot { Triple(it.id, it.serverId, it.userId) in keys } + books
        }
    }

    override suspend fun updateDownloadProgress(
        bookId: String,
        serverId: String,
        userId: String,
        status: DownloadStatus,
        progress: Float,
    ) {
        state.update { current ->
            current.map {
                if (it.id == bookId && ((it.serverId == serverId && it.userId == userId) || it.serverId == "local")) {
                    it.copy(
                        downloadStatus = status,
                        downloadProgress = progress,
                    )
                } else {
                    it
                }
            }
        }
    }

    override suspend fun updateDownloadStatus(
        bookId: String,
        serverId: String,
        userId: String,
        status: DownloadStatus,
        progress: Float,
        path: String?,
        audioPath: String?,
        sha256: String?,
    ) {
        state.update { current ->
            current.map {
                if (it.id == bookId && ((it.serverId == serverId && it.userId == userId) || it.serverId == "local")) {
                    it.copy(
                        downloadStatus = status,
                        downloadProgress = progress,
                        localFilePath = path,
                        localAudioPath = audioPath,
                        sha256Checksum = sha256,
                    )
                } else {
                    it
                }
            }
        }
    }

    override suspend fun updateLastOpenedAt(bookId: String, serverId: String, userId: String, timestamp: Long) {
        state.update { current ->
            current.map {
                if (it.id == bookId && ((it.serverId == serverId && it.userId == userId) || it.serverId == "local")) {
                    it.copy(lastOpenedAt = timestamp)
                } else {
                    it
                }
            }
        }
    }

    override suspend fun updateBookLastOpenedAt(bookId: String, timestamp: Long) {
        state.update { current ->
            current.map {
                if (it.id == bookId) {
                    it.copy(lastOpenedAt = timestamp)
                } else {
                    it
                }
            }
        }
    }

    override suspend fun updateMediaType(
        bookId: String,
        serverId: String,
        userId: String,
        mediaType: BookMediaType,
    ) {
        state.update { current ->
            current.map {
                if (it.id == bookId && it.serverId == serverId && it.userId == userId) {
                    it.copy(mediaType = mediaType)
                } else {
                    it
                }
            }
        }
    }

    override suspend fun deleteLocalBook(bookId: String) {
        state.update { current -> current.filterNot { it.id == bookId && it.serverId == "local" } }
    }

    override suspend fun clearServerData(serverId: String, userId: String) {
        state.update { current -> current.filterNot { it.serverId == serverId && it.userId == userId } }
    }

    override suspend fun clearDownloadsForBooks(
        bookIds: List<String>,
        serverId: String,
        userId: String,
    ) {
        state.update { current ->
            current.map {
                if (it.id in bookIds && ((it.serverId == serverId && it.userId == userId) || it.serverId == "local")) {
                    it.copy(
                        localFilePath = null,
                        localAudioPath = null,
                        downloadStatus = DownloadStatus.NOT_DOWNLOADED,
                        downloadProgress = 0f,
                    )
                } else {
                    it
                }
            }
        }
    }

    override suspend fun clearDownloadPaths(
        bookId: String,
        serverId: String,
        userId: String,
        clearFilePath: Boolean,
        clearAudioPath: Boolean,
        status: DownloadStatus,
    ) {
        state.update { current ->
            current.map {
                if (it.id == bookId && ((it.serverId == serverId && it.userId == userId) || it.serverId == "local")) {
                    it.copy(
                        localFilePath = if (clearFilePath) null else it.localFilePath,
                        localAudioPath = if (clearAudioPath) null else it.localAudioPath,
                        downloadStatus = status,
                    )
                } else {
                    it
                }
            }
        }
    }

    override suspend fun updateReadingStatus(
        bookId: String,
        serverId: String,
        userId: String,
        status: String?,
        label: String?,
        statusUuid: String?,
    ) {
        state.update { current ->
            current.map {
                if (it.id == bookId && ((it.serverId == serverId && it.userId == userId) || it.serverId == "local")) {
                    it.copy(
                        readingStatus = status,
                        readingStatusLabel = label,
                        statusUuid = statusUuid,
                    )
                } else {
                    it
                }
            }
        }
    }

    override suspend fun healInvalidReadingStatuses(): Int {
        var count = 0
        state.update { current ->
            current.map {
                if (it.readingStatus?.lowercase() in listOf("none", "no status", "")) {
                    count++
                    it.copy(readingStatus = "to_read", readingStatusLabel = "To read")
                } else {
                    it
                }
            }
        }
        return count
    }

    override suspend fun updateBookMetadata(
        bookId: String,
        serverId: String,
        userId: String,
        publicationDate: String?,
        language: String?,
        publisher: String?,
        isbn: String?,
        rating: Float?,
        externalRatings: String?,
    ) {
        state.update { current ->
            current.map {
                if (it.id == bookId && ((it.serverId == serverId && it.userId == userId) || it.serverId == "local")) {
                    it.copy(
                        publicationDate = publicationDate,
                        language = language,
                        publisher = publisher,
                        isbn = isbn,
                        rating = rating,
                        externalRatings = externalRatings,
                    )
                } else {
                    it
                }
            }
        }
    }

    override suspend fun updateBookRating(
        bookId: String,
        serverId: String,
        userId: String,
        rating: Float?,
    ) {
        state.update { current ->
            current.map {
                if (it.id == bookId && ((it.serverId == serverId && it.userId == userId) || it.serverId == "local")) {
                    it.copy(rating = rating)
                } else {
                    it
                }
            }
        }
    }
}

class FakeSyncCheckpointDao : SyncCheckpointDao {
    private val state = MutableStateFlow<List<SyncCheckpointEntity>>(emptyList())

    override fun observeCheckpoint(bookId: String, serverId: String, userId: String): Flow<SyncCheckpointEntity?> =
        state.map { list -> list.firstOrNull { it.bookId == bookId && it.serverId == serverId && it.userId == userId } }

    override fun observeAllCheckpoints(serverId: String, userId: String): Flow<List<SyncCheckpointEntity>> =
        state.map { list -> list.filter { it.serverId == serverId && it.userId == userId } }

    override suspend fun getCheckpoint(bookId: String, serverId: String, userId: String): SyncCheckpointEntity? =
        state.value.firstOrNull { it.bookId == bookId && it.serverId == serverId && it.userId == userId }

    override suspend fun getDirtyCheckpoints(serverId: String, userId: String): List<SyncCheckpointEntity> =
        state.value.filter { it.serverId == serverId && it.userId == userId && it.isDirty }

    override suspend fun upsertCheckpoint(checkpoint: SyncCheckpointEntity) {
        state.update { current ->
            current.filterNot {
                it.bookId == checkpoint.bookId && it.serverId == checkpoint.serverId && it.userId == checkpoint.userId
            } + checkpoint
        }
    }

    override suspend fun markSynced(bookId: String, serverId: String, userId: String) {
        state.update { current ->
            current.map {
                if (it.bookId == bookId && it.serverId == serverId && it.userId == userId) {
                    it.copy(isDirty = false)
                } else {
                    it
                }
            }
        }
    }
}


class FakeAnnotationDao : org.hello.reader.core.database.dao.AnnotationDao {
    private val state = MutableStateFlow<List<org.hello.reader.core.database.entity.AnnotationEntity>>(emptyList())

    override fun observeAnnotations(
        serverId: String,
        userId: String,
        bookId: String,
    ): Flow<List<org.hello.reader.core.database.entity.AnnotationEntity>> =
        state.map { list ->
            list.filter { it.serverId == serverId && it.userId == userId && it.bookId == bookId }
                .sortedByDescending { it.createdAt }
        }

    override fun observeAnnotationsByType(
        serverId: String,
        userId: String,
        bookId: String,
        type: org.hello.reader.core.model.AnnotationType,
    ): Flow<List<org.hello.reader.core.database.entity.AnnotationEntity>> =
        state.map { list ->
            list.filter { it.serverId == serverId && it.userId == userId && it.bookId == bookId && it.type == type }
                .sortedByDescending { it.createdAt }
        }

    override fun observeAnnotationsInScopes(
        serverId: String,
        userId: String,
        localServerId: String,
        localUserId: String,
    ): Flow<List<org.hello.reader.core.database.entity.AnnotationEntity>> =
        state.map { list ->
            list.filter {
                (it.serverId == serverId && it.userId == userId) || (it.serverId == localServerId && it.userId == localUserId)
            }.sortedByDescending { it.createdAt }
        }

    override suspend fun upsertAnnotation(annotation: org.hello.reader.core.database.entity.AnnotationEntity) {
        state.update { current -> current.filterNot { it.id == annotation.id } + annotation }
    }

    override suspend fun getAnnotation(
        id: String,
        serverId: String,
        userId: String,
    ): org.hello.reader.core.database.entity.AnnotationEntity? =
        state.value.firstOrNull { it.id == id && it.serverId == serverId && it.userId == userId }

    override suspend fun deleteAnnotation(id: String, serverId: String, userId: String) {
        state.update { current -> current.filterNot { it.id == id && it.serverId == serverId && it.userId == userId } }
    }

    override suspend fun clearServerAnnotations(serverId: String, userId: String) {
        state.update { current -> current.filterNot { it.serverId == serverId && it.userId == userId } }
    }
}

class FakeSeriesDao(private val bookDao: FakeBookDao? = null) : org.hello.reader.core.database.dao.SeriesDao {
    private val seriesState = MutableStateFlow<List<org.hello.reader.core.database.entity.SeriesEntity>>(emptyList())
    private val crossRefState = MutableStateFlow<List<org.hello.reader.core.database.entity.BookSeriesCrossRef>>(emptyList())

    override fun observeSeries(serverId: String, userId: String): Flow<List<org.hello.reader.core.database.entity.SeriesEntity>> =
        kotlinx.coroutines.flow.combine(seriesState, crossRefState) { series, refs ->
            if (bookDao == null) {
                series.filter { it.serverId == serverId }.sortedBy { it.name.lowercase() }
            } else {
                val userBookIds = bookDao.getBooksSnapshot(serverId, userId).map { it.id }.toSet()
                val seriesUuids = refs.filter { it.serverId == serverId && it.bookId in userBookIds }
                    .map { it.seriesUuid }.toSet()
                series.filter { it.serverId == serverId && it.uuid in seriesUuids }
                    .sortedBy { it.name.lowercase() }
            }
        }

    override fun observeAllBookSeries(serverId: String): Flow<List<org.hello.reader.core.database.entity.BookSeriesCrossRef>> =
        crossRefState.map { list -> list.filter { it.serverId == serverId } }

    override suspend fun getSeries(serverId: String, userId: String): List<org.hello.reader.core.database.entity.SeriesEntity> {
        val series = seriesState.value
        val refs = crossRefState.value
        return if (bookDao == null) {
            series.filter { it.serverId == serverId }.sortedBy { it.name.lowercase() }
        } else {
            val userBookIds = bookDao.getBooksSnapshot(serverId, userId).map { it.id }.toSet()
            val seriesUuids = refs.filter { it.serverId == serverId && it.bookId in userBookIds }
                .map { it.seriesUuid }.toSet()
            series.filter { it.serverId == serverId && it.uuid in seriesUuids }
                .sortedBy { it.name.lowercase() }
        }
    }

    override fun observeSeriesByUuid(serverId: String, uuid: String): Flow<org.hello.reader.core.database.entity.SeriesEntity?> =
        seriesState.map { list -> list.firstOrNull { it.serverId == serverId && it.uuid == uuid } }

    override suspend fun getSeriesByUuid(serverId: String, uuid: String): org.hello.reader.core.database.entity.SeriesEntity? =
        seriesState.value.firstOrNull { it.serverId == serverId && it.uuid == uuid }

    override fun observeBooksForSeries(
        serverId: String,
        userId: String,
        seriesUuid: String,
    ): Flow<List<org.hello.reader.core.database.dao.SeriesBookItemEntity>> =
        crossRefState.map { getBooksForSeries(serverId, userId, seriesUuid) }

    override suspend fun getBooksForSeries(
        serverId: String,
        userId: String,
        seriesUuid: String,
    ): List<org.hello.reader.core.database.dao.SeriesBookItemEntity> {
        val refs = crossRefState.value.filter { it.serverId == serverId && it.seriesUuid == seriesUuid }
        val books = bookDao?.getBooksSnapshot(serverId, userId).orEmpty().associateBy { it.id }
        return refs.mapNotNull { ref ->
            val b = books[ref.bookId] ?: return@mapNotNull null
            org.hello.reader.core.database.dao.SeriesBookItemEntity(
                book = b,
                position = ref.position,
                featured = ref.featured,
            )
        }.sortedWith { a, b ->
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
    }

    override fun observeSeriesForBook(serverId: String, bookId: String): Flow<List<org.hello.reader.core.database.dao.BookSeriesEntryEntity>> =
        crossRefState.map { getSeriesForBook(serverId, bookId) }

    override suspend fun getSeriesForBook(serverId: String, bookId: String): List<org.hello.reader.core.database.dao.BookSeriesEntryEntity> {
        val refs = crossRefState.value.filter { it.serverId == serverId && it.bookId == bookId }
        val allSeries = seriesState.value.filter { it.serverId == serverId }.associateBy { it.uuid }
        return refs.mapNotNull { ref ->
            val s = allSeries[ref.seriesUuid] ?: return@mapNotNull null
            org.hello.reader.core.database.dao.BookSeriesEntryEntity(
                seriesUuid = s.uuid,
                seriesName = s.name,
                position = ref.position,
                featured = ref.featured,
            )
        }.sortedWith { a, b ->
            when {
                a.position != null && b.position != null -> {
                    val cmp = a.position.compareTo(b.position)
                    if (cmp != 0) cmp else a.seriesName.compareTo(b.seriesName, ignoreCase = true)
                }
                a.position != null && b.position == null -> -1
                a.position == null && b.position != null -> 1
                else -> a.seriesName.compareTo(b.seriesName, ignoreCase = true)
            }
        }
    }

    override suspend fun upsertSeries(series: List<org.hello.reader.core.database.entity.SeriesEntity>) {
        seriesState.update { current ->
            val keys = series.map { it.uuid to it.serverId }.toSet()
            current.filterNot { (it.uuid to it.serverId) in keys } + series
        }
    }

    override suspend fun upsertBookSeries(refs: List<org.hello.reader.core.database.entity.BookSeriesCrossRef>) {
        crossRefState.update { current ->
            val keys = refs.map { Triple(it.bookId, it.serverId, it.seriesUuid) }.toSet()
            current.filterNot { Triple(it.bookId, it.serverId, it.seriesUuid) in keys } + refs
        }
    }

    override suspend fun clearBookSeries(serverId: String) {
        crossRefState.update { current -> current.filterNot { it.serverId == serverId } }
    }

    override suspend fun pruneOrphanedSeries(serverId: String, activeUuids: List<String>) {
        val set = activeUuids.toSet()
        seriesState.update { current -> current.filter { it.serverId != serverId || it.uuid in set } }
    }

    override suspend fun clearSeries(serverId: String) {
        seriesState.update { current -> current.filterNot { it.serverId == serverId } }
    }
}

class FakeCollectionDao(private val bookDao: FakeBookDao? = null) : org.hello.reader.core.database.dao.CollectionDao {
    private val collectionState = MutableStateFlow<List<org.hello.reader.core.database.entity.CollectionEntity>>(emptyList())
    private val crossRefState = MutableStateFlow<List<org.hello.reader.core.database.entity.BookCollectionCrossRef>>(emptyList())

    override fun observeCollections(serverId: String, userId: String): Flow<List<org.hello.reader.core.database.entity.CollectionEntity>> =
        kotlinx.coroutines.flow.combine(collectionState, crossRefState) { colls, refs ->
            if (bookDao == null) {
                colls.filter { it.serverId == serverId }.sortedBy { it.name.lowercase() }
            } else {
                val userBookIds = bookDao.getBooksSnapshot(serverId, userId).map { it.id }.toSet()
                val collUuids = refs.filter { it.serverId == serverId && it.bookId in userBookIds }
                    .map { it.collectionUuid }.toSet()
                colls.filter { it.serverId == serverId && it.uuid in collUuids }
                    .sortedBy { it.name.lowercase() }
            }
        }

    override suspend fun getCollections(serverId: String, userId: String): List<org.hello.reader.core.database.entity.CollectionEntity> {
        val colls = collectionState.value
        val refs = crossRefState.value
        return if (bookDao == null) {
            colls.filter { it.serverId == serverId }.sortedBy { it.name.lowercase() }
        } else {
            val userBookIds = bookDao.getBooksSnapshot(serverId, userId).map { it.id }.toSet()
            val collUuids = refs.filter { it.serverId == serverId && it.bookId in userBookIds }
                .map { it.collectionUuid }.toSet()
            colls.filter { it.serverId == serverId && it.uuid in collUuids }
                .sortedBy { it.name.lowercase() }
        }
    }

    override fun observeAllBookCollections(serverId: String): Flow<List<org.hello.reader.core.database.entity.BookCollectionCrossRef>> =
        crossRefState.map { list -> list.filter { it.serverId == serverId } }

    override fun observeCollectionByUuid(serverId: String, uuid: String): Flow<org.hello.reader.core.database.entity.CollectionEntity?> =
        collectionState.map { list -> list.firstOrNull { it.serverId == serverId && it.uuid == uuid } }

    override suspend fun getCollectionByUuid(serverId: String, uuid: String): org.hello.reader.core.database.entity.CollectionEntity? =
        collectionState.value.firstOrNull { it.serverId == serverId && it.uuid == uuid }

    override fun observeCollectionsForBook(serverId: String, bookId: String): Flow<List<org.hello.reader.core.database.entity.CollectionEntity>> =
        crossRefState.map { getCollectionsForBook(serverId, bookId) }

    override suspend fun getCollectionsForBook(serverId: String, bookId: String): List<org.hello.reader.core.database.entity.CollectionEntity> {
        val refs = crossRefState.value.filter { it.serverId == serverId && it.bookId == bookId }
        val allColls = collectionState.value.filter { it.serverId == serverId }.associateBy { it.uuid }
        return refs.mapNotNull { allColls[it.collectionUuid] }.sortedBy { it.name.lowercase() }
    }

    override fun observeBooksForCollection(
        serverId: String,
        userId: String,
        collectionUuid: String,
    ): Flow<List<BookEntity>> =
        crossRefState.map { getBooksForCollection(serverId, userId, collectionUuid) }

    override suspend fun getBooksForCollection(
        serverId: String,
        userId: String,
        collectionUuid: String,
    ): List<BookEntity> {
        val refs = crossRefState.value.filter { it.serverId == serverId && it.collectionUuid == collectionUuid }
        val books = bookDao?.getBooksSnapshot(serverId, userId).orEmpty().associateBy { it.id }
        return refs.mapNotNull { books[it.bookId] }.sortedBy { it.title.lowercase() }
    }

    override suspend fun upsertCollections(collections: List<org.hello.reader.core.database.entity.CollectionEntity>) {
        collectionState.update { current ->
            val keys = collections.map { it.uuid to it.serverId }.toSet()
            current.filterNot { (it.uuid to it.serverId) in keys } + collections
        }
    }

    override suspend fun upsertBookCollections(refs: List<org.hello.reader.core.database.entity.BookCollectionCrossRef>) {
        crossRefState.update { current ->
            val keys = refs.map { Triple(it.bookId, it.serverId, it.collectionUuid) }.toSet()
            current.filterNot { Triple(it.bookId, it.serverId, it.collectionUuid) in keys } + refs
        }
    }

    override suspend fun clearBookCollections(serverId: String) {
        crossRefState.update { current -> current.filterNot { it.serverId == serverId } }
    }

    override suspend fun pruneOrphanedCollections(serverId: String, activeUuids: List<String>) {
        val set = activeUuids.toSet()
        collectionState.update { current -> current.filter { it.serverId != serverId || it.uuid in set } }
    }

    override suspend fun clearCollections(serverId: String) {
        collectionState.update { current -> current.filterNot { it.serverId == serverId } }
    }
}

class FakeBookDownloadDao : org.hello.reader.core.database.dao.BookDownloadDao {
    private val state = MutableStateFlow<List<org.hello.reader.core.database.entity.BookDownloadEntity>>(emptyList())

    override fun observeDownloadsForBook(
        bookId: String,
        serverId: String,
        userId: String,
    ): Flow<List<org.hello.reader.core.database.entity.BookDownloadEntity>> =
        state.map { list -> list.filter { it.bookId == bookId && ((it.serverId == serverId && it.userId == userId) || it.serverId == "local") } }

    override suspend fun getDownloadsForBook(
        bookId: String,
        serverId: String,
        userId: String,
    ): List<org.hello.reader.core.database.entity.BookDownloadEntity> =
        state.value.filter { it.bookId == bookId && ((it.serverId == serverId && it.userId == userId) || it.serverId == "local") }

    override suspend fun getDownload(
        bookId: String,
        serverId: String,
        userId: String,
        format: BookMediaType,
    ): org.hello.reader.core.database.entity.BookDownloadEntity? =
        state.value.firstOrNull { it.bookId == bookId && it.format == format && ((it.serverId == serverId && it.userId == userId) || it.serverId == "local") }

    override fun observeAllDownloads(
        serverId: String,
        userId: String,
    ): Flow<List<org.hello.reader.core.database.entity.BookDownloadEntity>> =
        state.map { list -> list.filter { (it.serverId == serverId && it.userId == userId) || it.serverId == "local" } }

    override suspend fun getDownloadsForBooks(
        bookIds: List<String>,
        serverId: String,
        userId: String,
    ): List<org.hello.reader.core.database.entity.BookDownloadEntity> =
        state.value.filter { it.bookId in bookIds && ((it.serverId == serverId && it.userId == userId) || it.serverId == "local") }

    override suspend fun upsertDownload(download: org.hello.reader.core.database.entity.BookDownloadEntity) {
        state.update { current ->
            current.filterNot { it.bookId == download.bookId && it.serverId == download.serverId && it.userId == download.userId && it.format == download.format } + download
        }
    }

    override suspend fun deleteDownload(
        bookId: String,
        serverId: String,
        userId: String,
        format: BookMediaType,
    ) {
        state.update { current ->
            current.filterNot { it.bookId == bookId && it.serverId == serverId && it.userId == userId && it.format == format }
        }
    }

    override suspend fun deleteDownloadsForBook(bookId: String, serverId: String, userId: String) {
        state.update { current ->
            current.filterNot { it.bookId == bookId && it.serverId == serverId && it.userId == userId }
        }
    }

    override suspend fun deleteDownloadsForBooks(bookIds: List<String>, serverId: String, userId: String) {
        state.update { current ->
            current.filterNot { it.bookId in bookIds && it.serverId == serverId && it.userId == userId }
        }
    }
}
