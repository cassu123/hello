package org.hello.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import org.hello.reader.core.database.entity.BookEntity
import org.hello.reader.core.model.BookMediaType
import org.hello.reader.core.model.DownloadStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE (server_id = :serverId AND user_id = :userId) OR server_id = 'local' ORDER BY last_opened_at DESC, title ASC")
    fun observeBooks(serverId: String, userId: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE server_id = 'local' ORDER BY last_opened_at DESC, title ASC")
    fun observeLocalBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE ((server_id = :serverId AND user_id = :userId) OR server_id = 'local') AND media_type = :mediaType ORDER BY title ASC")
    fun observeBooksByMediaType(serverId: String, userId: String, mediaType: BookMediaType): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE server_id = 'local' AND media_type = :mediaType ORDER BY title ASC")
    fun observeLocalBooksByMediaType(mediaType: BookMediaType): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE ((server_id = :serverId AND user_id = :userId) OR server_id = 'local') AND download_status = :status ORDER BY title ASC")
    fun observeBooksByDownloadStatus(
        serverId: String,
        userId: String,
        status: DownloadStatus = DownloadStatus.DOWNLOADED,
    ): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE server_id = 'local' AND download_status = :status ORDER BY title ASC")
    fun observeLocalBooksByDownloadStatus(
        status: DownloadStatus = DownloadStatus.DOWNLOADED,
    ): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE ((server_id = :serverId AND user_id = :userId) OR server_id = 'local') AND last_opened_at IS NOT NULL ORDER BY last_opened_at DESC LIMIT 10")
    fun observeContinueReading(serverId: String, userId: String): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE server_id = 'local' AND last_opened_at IS NOT NULL ORDER BY last_opened_at DESC LIMIT 10")
    fun observeLocalContinueReading(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :bookId AND ((server_id = :serverId AND user_id = :userId) OR server_id = 'local') LIMIT 1")
    fun observeBookById(bookId: String, serverId: String, userId: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :bookId AND ((server_id = :serverId AND user_id = :userId) OR server_id = 'local') LIMIT 1")
    suspend fun getBookById(bookId: String, serverId: String, userId: String): BookEntity?

    @Query("SELECT * FROM books WHERE id = :bookId LIMIT 1")
    suspend fun findBookById(bookId: String): BookEntity?

    @Query("SELECT * FROM books WHERE (server_id = :serverId AND user_id = :userId) OR server_id = 'local'")
    suspend fun getBooksSnapshot(serverId: String, userId: String): List<BookEntity>

    @Query("SELECT * FROM books WHERE server_id = 'local'")
    suspend fun getLocalBooksSnapshot(): List<BookEntity>

    @Upsert
    suspend fun upsertBooks(books: List<BookEntity>)

    @Query(
        "UPDATE books SET download_status = :status, download_progress = :progress " +
            "WHERE id = :bookId AND ((server_id = :serverId AND user_id = :userId) OR server_id = 'local')",
    )
    suspend fun updateDownloadProgress(
        bookId: String,
        serverId: String,
        userId: String,
        status: DownloadStatus,
        progress: Float,
    )

    @Query(
        "UPDATE books SET download_status = :status, download_progress = :progress, " +
            "local_file_path = :path, local_audio_path = :audioPath, sha256_checksum = :sha256 " +
            "WHERE id = :bookId AND ((server_id = :serverId AND user_id = :userId) OR server_id = 'local')",
    )
    suspend fun updateDownloadStatus(
        bookId: String,
        serverId: String,
        userId: String,
        status: DownloadStatus,
        progress: Float,
        path: String?,
        audioPath: String? = null,
        sha256: String? = null,
    )

    @Query("UPDATE books SET last_opened_at = :timestamp WHERE id = :bookId AND ((server_id = :serverId AND user_id = :userId) OR server_id = 'local')")
    suspend fun updateLastOpenedAt(bookId: String, serverId: String, userId: String, timestamp: Long)

    @Query("UPDATE books SET media_type = :mediaType WHERE id = :bookId AND ((server_id = :serverId AND user_id = :userId) OR server_id = 'local')")
    suspend fun updateMediaType(bookId: String, serverId: String, userId: String, mediaType: BookMediaType)

    @Query("UPDATE books SET last_opened_at = :timestamp WHERE id = :bookId")
    suspend fun updateBookLastOpenedAt(bookId: String, timestamp: Long)

    @Query("DELETE FROM books WHERE id = :bookId AND server_id = 'local'")
    suspend fun deleteLocalBook(bookId: String)

    @Query("DELETE FROM books WHERE server_id = :serverId AND user_id = :userId")
    suspend fun clearServerData(serverId: String, userId: String)

    @Query(
        "UPDATE books SET local_file_path = NULL, local_audio_path = NULL, download_status = 'NOT_DOWNLOADED', download_progress = 0 " +
            "WHERE id IN (:bookIds) AND ((server_id = :serverId AND user_id = :userId) OR server_id = 'local')",
    )
    suspend fun clearDownloadsForBooks(
        bookIds: List<String>,
        serverId: String,
        userId: String,
    )

    @Query(
        "UPDATE books SET " +
            "local_file_path = CASE WHEN :clearFilePath = 1 THEN NULL ELSE local_file_path END, " +
            "local_audio_path = CASE WHEN :clearAudioPath = 1 THEN NULL ELSE local_audio_path END, " +
            "download_status = :status " +
            "WHERE id = :bookId AND ((server_id = :serverId AND user_id = :userId) OR server_id = 'local')",
    )
    suspend fun clearDownloadPaths(
        bookId: String,
        serverId: String,
        userId: String,
        clearFilePath: Boolean,
        clearAudioPath: Boolean,
        status: DownloadStatus,
    )

    @Query("UPDATE books SET reading_status = :status, reading_status_label = :label, status_uuid = :statusUuid WHERE id = :bookId AND ((server_id = :serverId AND user_id = :userId) OR server_id = 'local')")
    suspend fun updateReadingStatus(
        bookId: String,
        serverId: String,
        userId: String,
        status: String?,
        label: String?,
        statusUuid: String?,
    )

    @Query("UPDATE books SET reading_status = 'to_read', reading_status_label = 'To read' WHERE LOWER(reading_status) = 'none' OR LOWER(reading_status) = 'no status' OR reading_status = ''")
    suspend fun healInvalidReadingStatuses(): Int

    @Query("UPDATE books SET publication_date = :publicationDate, language = :language, publisher = :publisher, isbn = :isbn, rating = :rating, external_ratings = :externalRatings WHERE id = :bookId AND ((server_id = :serverId AND user_id = :userId) OR server_id = 'local')")
    suspend fun updateBookMetadata(
        bookId: String,
        serverId: String,
        userId: String,
        publicationDate: String?,
        language: String?,
        publisher: String?,
        isbn: String?,
        rating: Float?,
        externalRatings: String?,
    )

    @Query("UPDATE books SET rating = :rating WHERE id = :bookId AND ((server_id = :serverId AND user_id = :userId) OR server_id = 'local')")
    suspend fun updateBookRating(
        bookId: String,
        serverId: String,
        userId: String,
        rating: Float?,
    )
}
