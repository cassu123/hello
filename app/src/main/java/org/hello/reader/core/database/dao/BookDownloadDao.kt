package org.hello.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.hello.reader.core.database.entity.BookDownloadEntity
import org.hello.reader.core.model.BookMediaType

@Dao
interface BookDownloadDao {
    @Query("SELECT * FROM book_downloads WHERE book_id = :bookId AND server_id = :serverId AND user_id = :userId")
    fun observeDownloadsForBook(bookId: String, serverId: String, userId: String): Flow<List<BookDownloadEntity>>

    @Query("SELECT * FROM book_downloads WHERE book_id = :bookId AND server_id = :serverId AND user_id = :userId")
    suspend fun getDownloadsForBook(bookId: String, serverId: String, userId: String): List<BookDownloadEntity>

    @Query("SELECT * FROM book_downloads WHERE book_id = :bookId AND server_id = :serverId AND user_id = :userId AND format = :format LIMIT 1")
    suspend fun getDownload(bookId: String, serverId: String, userId: String, format: BookMediaType): BookDownloadEntity?

    @Query("SELECT * FROM book_downloads WHERE (server_id = :serverId AND user_id = :userId) OR server_id = 'local'")
    fun observeAllDownloads(serverId: String, userId: String): Flow<List<BookDownloadEntity>>

    @Query("SELECT * FROM book_downloads WHERE book_id IN (:bookIds) AND ((server_id = :serverId AND user_id = :userId) OR server_id = 'local')")
    suspend fun getDownloadsForBooks(bookIds: List<String>, serverId: String, userId: String): List<BookDownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDownload(download: BookDownloadEntity)

    @Query("DELETE FROM book_downloads WHERE book_id = :bookId AND server_id = :serverId AND user_id = :userId AND format = :format")
    suspend fun deleteDownload(bookId: String, serverId: String, userId: String, format: BookMediaType)

    @Query("DELETE FROM book_downloads WHERE book_id = :bookId AND server_id = :serverId AND user_id = :userId")
    suspend fun deleteDownloadsForBook(bookId: String, serverId: String, userId: String)

    @Query("DELETE FROM book_downloads WHERE book_id IN (:bookIds) AND ((server_id = :serverId AND user_id = :userId) OR server_id = 'local')")
    suspend fun deleteDownloadsForBooks(bookIds: List<String>, serverId: String, userId: String)
}
