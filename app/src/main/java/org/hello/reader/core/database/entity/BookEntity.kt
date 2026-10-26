package org.hello.reader.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import org.hello.reader.core.database.converter.DatabaseConverters
import org.hello.reader.core.model.Book
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.DownloadStatus

@Entity(
    tableName = "books",
    primaryKeys = ["id", "server_id", "user_id"],
    indices = [
        Index(value = ["server_id", "user_id"]),
        Index(value = ["server_id", "user_id", "media_type"]),
        Index(value = ["server_id", "user_id", "download_status"]),
    ],
)
data class BookEntity(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "server_id") val serverId: String,
    @ColumnInfo(name = "user_id") val userId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "authors") val authors: String,
    @ColumnInfo(name = "narrators") val narrators: String,
    @ColumnInfo(name = "cover_url") val coverUrl: String?,
    @ColumnInfo(name = "description") val description: String?,
    @ColumnInfo(name = "media_type") val mediaType: BookMediaType,
    @ColumnInfo(name = "duration_seconds") val durationSeconds: Long,
    @ColumnInfo(name = "total_chapters") val totalChapters: Int,
    @ColumnInfo(name = "download_status") val downloadStatus: DownloadStatus,
    @ColumnInfo(name = "download_progress") val downloadProgress: Float,
    @ColumnInfo(name = "local_file_path") val localFilePath: String?,
    @ColumnInfo(name = "local_audio_path") val localAudioPath: String? = null,
    @ColumnInfo(name = "sha256_checksum") val sha256Checksum: String? = null,
    @ColumnInfo(name = "tags") val tags: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_opened_at") val lastOpenedAt: Long?,
    @ColumnInfo(name = "has_readaloud", defaultValue = "0") val hasReadaloud: Boolean = false,
    @ColumnInfo(name = "has_ebook", defaultValue = "0") val hasEbook: Boolean = false,
    @ColumnInfo(name = "has_audiobook", defaultValue = "0") val hasAudiobook: Boolean = false,
    @ColumnInfo(name = "reading_status") val readingStatus: String? = null,
    @ColumnInfo(name = "reading_status_label") val readingStatusLabel: String? = null,
    @ColumnInfo(name = "status_uuid") val statusUuid: String? = null,
    @ColumnInfo(name = "rating") val rating: Float? = null,
    @ColumnInfo(name = "publication_date") val publicationDate: String? = null,
    @ColumnInfo(name = "language") val language: String? = null,
    @ColumnInfo(name = "publisher") val publisher: String? = null,
    @ColumnInfo(name = "isbn") val isbn: String? = null,
    @ColumnInfo(name = "external_ratings") val externalRatings: String? = null,
) {
    fun toDomain(converters: DatabaseConverters = DatabaseConverters()): Book = Book(
        id = id,
        serverId = serverId,
        userId = userId,
        title = title,
        authors = converters.toStringList(authors),
        narrators = converters.toStringList(narrators),
        coverUrl = coverUrl,
        description = description,
        mediaType = mediaType,
        durationSeconds = durationSeconds,
        totalChapters = totalChapters,
        downloadStatus = downloadStatus,
        downloadProgress = downloadProgress,
        localFilePath = localFilePath,
        localAudioPath = localAudioPath,
        sha256Checksum = sha256Checksum,
        tags = converters.toStringList(tags),
        createdAt = createdAt,
        lastOpenedAt = lastOpenedAt,
        hasReadaloud = hasReadaloud,
        hasEbook = hasEbook,
        hasAudiobook = hasAudiobook,
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

    companion object {
        fun fromDomain(book: Book, userId: String, converters: DatabaseConverters = DatabaseConverters()): BookEntity =
            BookEntity(
                id = book.id,
                serverId = book.serverId,
                userId = userId,
                title = book.title,
                authors = converters.fromStringList(book.authors),
                narrators = converters.fromStringList(book.narrators),
                coverUrl = book.coverUrl,
                description = book.description,
                mediaType = book.mediaType,
                durationSeconds = book.durationSeconds,
                totalChapters = book.totalChapters,
                downloadStatus = book.downloadStatus,
                downloadProgress = book.downloadProgress,
                localFilePath = book.localFilePath,
                localAudioPath = book.localAudioPath,
                sha256Checksum = book.sha256Checksum,
                tags = converters.fromStringList(book.tags),
                createdAt = book.createdAt,
                lastOpenedAt = book.lastOpenedAt,
                hasReadaloud = book.hasReadaloud,
                hasEbook = book.hasEbook,
                hasAudiobook = book.hasAudiobook,
                readingStatus = book.readingStatus,
                readingStatusLabel = book.readingStatusLabel,
                statusUuid = book.statusUuid,
                rating = book.rating,
                publicationDate = book.publicationDate,
                language = book.language,
                publisher = book.publisher,
                isbn = book.isbn,
                externalRatings = book.externalRatings,
            )
    }
}
